package net.runelite.client.plugins.inferno;

import net.runelite.api.Prayer;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.Client;

import java.util.*;

/**
 * Centralized, robust prayer recommendation engine for Inferno.
 * Uses NPC attack predictions, safespot analysis, and game state.
 */
public class InfernoPrayerRecommendation
{
    public enum RecommendationMode
    {
        LEAST_DAMAGE,         // Recommend prayer for highest incoming damage
        MOST_COMMON,          // Recommend prayer for most common incoming type
        FIRST_IN_LIST         // Recommend prayer for the first detected attack
    }

    private final InfernoNpcPrediction npcPrediction;
    private RecommendationMode mode = RecommendationMode.LEAST_DAMAGE;

    public InfernoPrayerRecommendation(InfernoNpcPrediction npcPrediction)
    {
        this.npcPrediction = npcPrediction;
    }

    public void setRecommendationMode(RecommendationMode mode)
    {
        this.mode = mode;
    }

    /**
     * Recommend the optimal protection prayer for the player's current tile, given all live NPCs.
     * @param infernoNpcs List of all live InfernoNPCs
     * @param client      Client instance
     * @param playerLoc   Player's current WorldPoint
     * @param safeSpotMap Map of safe spots (as produced by overlay logic, if available)
     * @return null if no prayer needed, or the recommended Prayer to use for this tick
     */
    public Prayer recommendPrayer(List<InfernoNPC> infernoNpcs,
                                  Client client,
                                  WorldPoint playerLoc,
                                  Map<WorldPoint, Integer> safeSpotMap)
    {
        // 1. True safespot: recommend nothing
        if (isInTrueSafespot(playerLoc, safeSpotMap))
        {
            return null;
        }

        // 2. Gather all predicted attacks on player tile for this tick
        Map<InfernoNPC.Attack, Integer> attackCounts = new HashMap<>();
        for (InfernoNPC npc : infernoNpcs)
        {
            int ticks = npcPrediction.getTicksUntilNextAttack(npc.getNpc());
            if (ticks == 0 && canNpcAttackTile(npc, client, playerLoc))
            {
                InfernoNPC.Attack attack = npcPrediction.getNextAttack(npc.getNpc());
                if (attack == null || attack == InfernoNPC.Attack.UNKNOWN)
                    continue;
                attackCounts.put(attack, attackCounts.getOrDefault(attack, 0) + 1);
            }
        }

        if (attackCounts.isEmpty())
        {
            // No attacks this tick, so no prayer needed
            return null;
        }

        // 3. Recommend prayer based on selected mode
        InfernoNPC.Attack chosen = null;
        switch (mode)
        {
            case MOST_COMMON:
                int maxCount = 0;
                for (Map.Entry<InfernoNPC.Attack, Integer> entry : attackCounts.entrySet())
                {
                    if (entry.getValue() > maxCount)
                    {
                        chosen = entry.getKey();
                        maxCount = entry.getValue();
                    }
                }
                break;
            case FIRST_IN_LIST:
                chosen = attackCounts.keySet().iterator().next();
                break;
            case LEAST_DAMAGE:
            default:
                // Use highest priority (lowest value), or fallback to most common
                int bestPriority = Integer.MAX_VALUE;
                for (InfernoNPC.Attack atk : attackCounts.keySet())
                {
                    int priority = getAttackPriority(atk);
                    if (priority < bestPriority)
                    {
                        bestPriority = priority;
                        chosen = atk;
                    }
                }
                break;
        }
        return chosen != null ? chosen.getPrayer() : null;
    }

    /**
     * Is the player tile a true safespot (can't be attacked by any NPC, as per safeSpotMap)?
     */
    public boolean isInTrueSafespot(WorldPoint playerLoc, Map<WorldPoint, Integer> safeSpotMap)
    {
        if (playerLoc == null || safeSpotMap == null)
            return false;
        Integer val = safeSpotMap.get(playerLoc);
        // 0 is usually "total safespot" in most overlay logic
        return val != null && val == 0;
    }

    /**
     * Can this NPC attack the specified tile? (For robust/edge-case logic)
     */
    private boolean canNpcAttackTile(InfernoNPC npc, Client client, WorldPoint tile)
    {
        // Use existing canAttack logic (meleeers: must be adjacent, others: range+los)
        if (npc == null || tile == null)
            return false;
        return npc.canAttack(client, tile);
    }

    /**
     * Assign a priority value to attack types for the "LEAST_DAMAGE" mode.
     * Lower number = higher priority (block this first).
     * Modify as needed for your encounter.
     */
    private int getAttackPriority(InfernoNPC.Attack attack)
    {
        switch (attack)
        {
            case MELEE: return 1;
            case RANGED: return 2;
            case MAGIC: return 3;
            default: return 99;
        }
    }

    /**
     * Returns a set of all attack types that will hit the player's tile on this tick.
     */
    public Set<InfernoNPC.Attack> getIncomingAttacks(List<InfernoNPC> infernoNpcs,
                                                     Client client,
                                                     WorldPoint playerLoc)
    {
        Set<InfernoNPC.Attack> attacks = new HashSet<>();
        for (InfernoNPC npc : infernoNpcs)
        {
            int ticks = npcPrediction.getTicksUntilNextAttack(npc.getNpc());
            if (ticks == 0 && canNpcAttackTile(npc, client, playerLoc))
            {
                InfernoNPC.Attack attack = npcPrediction.getNextAttack(npc.getNpc());
                if (attack != null && attack != InfernoNPC.Attack.UNKNOWN)
                {
                    attacks.add(attack);
                }
            }
        }
        return attacks;
    }
}