package net.runelite.client.plugins.inferno;

import lombok.extern.slf4j.Slf4j;
import net.runelite.api.NPC;
import net.runelite.api.Prayer;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.Client;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Robust tick/attack prediction for all Inferno NPCs.
 * Supports: animation tracking, fallback cycles, public tick-to-next-attack, and debug output.
 */
@Slf4j
public class InfernoNpcPrediction
{
    // Map of NPC instance to its prediction state
    private final Map<NPC, NpcPredictionState> npcStateMap = new HashMap<>();

    // Static: Attack cycles (in ticks) for each NPC type
    private static final Map<InfernoNPC.Type, Integer> DEFAULT_ATTACK_CYCLES = new EnumMap<>(InfernoNPC.Type.class);
    static {
        DEFAULT_ATTACK_CYCLES.put(InfernoNPC.Type.MELEE, 4);
        DEFAULT_ATTACK_CYCLES.put(InfernoNPC.Type.RANGER, 4);
        DEFAULT_ATTACK_CYCLES.put(InfernoNPC.Type.MAGE, 4);
        DEFAULT_ATTACK_CYCLES.put(InfernoNPC.Type.JAD, 8); // Can be more granular if needed
        DEFAULT_ATTACK_CYCLES.put(InfernoNPC.Type.BLOB, 4); // Blob splat cycle
        DEFAULT_ATTACK_CYCLES.put(InfernoNPC.Type.BAT, 3); // Confirm if 3 or 4 for bats
        // ...other types
    }

    /**
     * Call this on every game tick, passing the list of all active Inferno NPCs.
     * Updates internal state for all NPCs.
     */
    public void onGameTick(List<InfernoNPC> infernoNpcs, Client client, WorldPoint playerLoc)
    {
        // Remove stale entries
        Set<NPC> currentNpcs = infernoNpcs.stream().map(InfernoNPC::getNpc).collect(Collectors.toSet());
        npcStateMap.keySet().removeIf(npc -> !currentNpcs.contains(npc));

        for (InfernoNPC infernoNpc : infernoNpcs)
        {
            NPC npc = infernoNpc.getNpc();
            InfernoNPC.Type type = infernoNpc.getType();
            NpcPredictionState state = npcStateMap.computeIfAbsent(npc, n -> new NpcPredictionState(type));

            // Animation-based: If animation indicates attack, reset timer
            int currentAnimation = npc.getAnimation();
            boolean attackAnimation = isAttackAnimation(type, currentAnimation);

            if (attackAnimation)
            {
                state.ticksUntilNextAttack = getAttackCycle(type);
                state.lastAttackTick = System.currentTimeMillis();
                state.lastAnimation = currentAnimation;
                state.animationBased = true;
                log.debug("[INFERNO NPC] {} attacked (animation {}) - resetting attack timer to {} ticks", npc.getName(), currentAnimation, state.ticksUntilNextAttack);
            }
            else
            {
                // No animation: For meleeers, use proximity/4-tick fallback
                if (type == InfernoNPC.Type.MELEE)
                {
                    if (isAdjacent(npc, playerLoc))
                    {
                        if (state.ticksUntilNextAttack <= 0)
                        {
                            state.ticksUntilNextAttack = getAttackCycle(type);
                            log.debug("[INFERNO NPC] {} fallback melee tick reset to {}", npc.getName(), state.ticksUntilNextAttack);
                        }
                        else
                        {
                            state.ticksUntilNextAttack--;
                        }
                    }
                }
                else
                {
                    if (state.ticksUntilNextAttack > 0)
                        state.ticksUntilNextAttack--;
                }
            }
        }
    }

    /**
     * Returns the predicted tick(s) until the next attack for the given NPC.
     */
    public int getTicksUntilNextAttack(NPC npc)
    {
        NpcPredictionState state = npcStateMap.get(npc);
        return state != null ? state.ticksUntilNextAttack : -1;
    }

    /**
     * Returns the predicted next attack type for the given NPC.
     */
    public InfernoNPC.Attack getNextAttack(NPC npc)
    {
        NpcPredictionState state = npcStateMap.get(npc);
        return state != null ? state.attackType : null;
    }

    /**
     * Returns a debug string with all NPCs' attack predictions for overlays/logging.
     */
    public String debugAttackPredictions()
    {
        StringBuilder sb = new StringBuilder();
        npcStateMap.forEach((npc, state) ->
                sb.append(String.format("%s [id:%d] – Next attack in %d ticks (%s)%n",
                        npc.getName(), npc.getId(), state.ticksUntilNextAttack, state.attackType)));
        return sb.toString();
    }

    // Utility: Is this animation an attack animation for this NPC type?
    private boolean isAttackAnimation(InfernoNPC.Type type, int animation)
    {
        if (type == null) return false;
        for (InfernoNPC.Attack attack : InfernoNPC.Attack.values())
        {
            for (int anim : attack.getAnimationIds())
            {
                if (anim == animation)
                    return true;
            }
        }
        return false;
    }

    // Utility: Is the NPC adjacent (melee range) to the player?
    private boolean isAdjacent(NPC npc, WorldPoint playerLoc)
    {
        if (playerLoc == null) return false;
        WorldPoint npcLoc = npc.getWorldLocation();
        int dx = Math.abs(playerLoc.getX() - npcLoc.getX());
        int dy = Math.abs(playerLoc.getY() - npcLoc.getY());
        return dx <= 1 && dy <= 1;
    }

    // Per-NPC-type attack cycle (ticks)
    private int getAttackCycle(InfernoNPC.Type type)
    {
        return DEFAULT_ATTACK_CYCLES.getOrDefault(type, 4);
    }

    // NPC prediction state class
    private static class NpcPredictionState
    {
        InfernoNPC.Type type;
        int ticksUntilNextAttack = 0;
        long lastAttackTick = 0;
        int lastAnimation = -1;
        InfernoNPC.Attack attackType = InfernoNPC.Attack.UNKNOWN;
        boolean animationBased = false;

        NpcPredictionState(InfernoNPC.Type type)
        {
            this.type = type;
        }
    }
}