package net.runelite.client.plugins.inferno;

import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.NPC;
import net.runelite.api.Prayer;
import net.runelite.api.coords.WorldPoint;
import net.runelite.client.ui.overlay.Overlay;
import net.runelite.client.ui.overlay.OverlayPosition;
import net.runelite.client.ui.overlay.components.PanelComponent;
import net.runelite.client.ui.overlay.components.TitleComponent;

import javax.inject.Inject;
import java.awt.*;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Advanced Inferno overlay: shows recommended prayer, timers, spawn locations,
 * optimal tile, 1-tick flick readiness, and debug info.
 *
 * No longer injects InfernoPlugin to avoid circular dependencies.
 * State is set via public setters from InfernoPlugin before rendering.
 */
@Slf4j
public class InfernoOverlayAdvanced extends Overlay
{
    private final Client client;
    private final InfernoNpcPrediction npcPrediction;
    private final InfernoPrayerRecommendation prayerRecommendation;
    private final PanelComponent panelComponent = new PanelComponent();

    // These are set by the plugin before each render
    private List<InfernoNPC> infernoNpcs;
    private Map<WorldPoint, Integer> safeSpotMap;
    private boolean inInferno;
    private boolean flickPossible;
    private WorldPoint playerLoc;

    @Inject
    public InfernoOverlayAdvanced(
            Client client,
            InfernoNpcPrediction npcPrediction,
            InfernoPrayerRecommendation prayerRecommendation
    )
    {
        this.client = client;
        this.npcPrediction = npcPrediction;
        this.prayerRecommendation = prayerRecommendation;
        setPosition(OverlayPosition.TOP_LEFT);
    }

    // Setters to be called from InfernoPlugin before rendering
    public void setGameState(
            boolean inInferno,
            boolean flickPossible,
            WorldPoint playerLoc,
            List<InfernoNPC> infernoNpcs,
            Map<WorldPoint, Integer> safeSpotMap
    )
    {
        this.inInferno = inInferno;
        this.flickPossible = flickPossible;
        this.playerLoc = playerLoc;
        this.infernoNpcs = infernoNpcs;
        this.safeSpotMap = safeSpotMap;
    }

    @Override
    public Dimension render(Graphics2D graphics)
    {
        panelComponent.getChildren().clear();

        // Defensive: ensure state is set
        if (!inInferno || client.getLocalPlayer() == null || infernoNpcs == null || safeSpotMap == null || playerLoc == null)
        {
            return null;
        }

        // Prayer recommendation
        Prayer recPrayer = prayerRecommendation.recommendPrayer(infernoNpcs, client, playerLoc, safeSpotMap);

        String prayerText = recPrayer == null ? "No Prayer Needed" : recPrayer.name().replace("_", " ");
        panelComponent.getChildren().add(TitleComponent.builder()
                .text("Recommended Prayer: " + prayerText)
                .color(recPrayer == null ? Color.GREEN : Color.ORANGE)
                .build());

        // 1-tick flick/overlay indicator
        if (flickPossible)
        {
            panelComponent.getChildren().add(TitleComponent.builder()
                    .text("Tick Flick: SAFE")
                    .color(Color.GREEN)
                    .build());
        }

        // True safespot indicator
        if (prayerRecommendation.isInTrueSafespot(playerLoc, safeSpotMap))
        {
            panelComponent.getChildren().add(TitleComponent.builder()
                    .text("True Safespot: Yes")
                    .color(Color.CYAN)
                    .build());
        }

        // Timers above each NPC (draw on the game world for in-game overlays, or here for debug/log)
        StringBuilder sb = new StringBuilder();
        for (InfernoNPC npc : infernoNpcs)
        {
            int ticks = npcPrediction.getTicksUntilNextAttack(npc.getNpc());
            String npcName = npc.getNpc().getName();
            InfernoNPC.Attack atk = npcPrediction.getNextAttack(npc.getNpc());
            sb.append(String.format("%s: %s in %d ticks\n", npcName, atk, ticks));
        }
        panelComponent.getChildren().add(TitleComponent.builder()
                .text("Timers: \n" + sb.toString())
                .color(Color.LIGHT_GRAY)
                .build());

        // Show optimal tile
        WorldPoint optimalTile = findOptimalTile(safeSpotMap, playerLoc);
        if (optimalTile != null)
        {
            panelComponent.getChildren().add(TitleComponent.builder()
                    .text("Optimal Tile: " + optimalTile)
                    .color(Color.YELLOW)
                    .build());
        }

        // Debug (incoming attacks this tick)
        Set<InfernoNPC.Attack> incoming = prayerRecommendation.getIncomingAttacks(infernoNpcs, client, playerLoc);
        panelComponent.getChildren().add(TitleComponent.builder()
                .text("Incoming attacks: " + incoming)
                .color(Color.PINK)
                .build());

        // Debug: Show all attack predictions
        panelComponent.getChildren().add(TitleComponent.builder()
                .text(npcPrediction.debugAttackPredictions())
                .color(Color.GRAY)
                .build());

        return panelComponent.render(graphics);
    }

    /**
     * Finds the optimal tile (minimizes incoming attacks).
     * Simple version: pick any tile in safeSpotMap with value 0 (true safespot).
     * More advanced: find tile with least incoming attacks (can extend as needed).
     */
    private WorldPoint findOptimalTile(Map<WorldPoint, Integer> safeSpotMap, WorldPoint playerLoc)
    {
        if (safeSpotMap == null || safeSpotMap.isEmpty()) return null;
        for (Map.Entry<WorldPoint, Integer> entry : safeSpotMap.entrySet())
        {
            if (entry.getValue() == 0)
            {
                return entry.getKey();
            }
        }
        // Fallback: no true safespot, just return player's current tile
        return playerLoc;
    }
}