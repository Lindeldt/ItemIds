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
 */
@Slf4j
public class InfernoOverlayAdvanced extends Overlay
{
    private final Client client;
    private final InfernoPlugin infernoPlugin;
    private final InfernoNpcPrediction npcPrediction;
    private final InfernoPrayerRecommendation prayerRecommendation;
    private final PanelComponent panelComponent = new PanelComponent();

    @Inject
    public InfernoOverlayAdvanced(
            Client client,
            InfernoPlugin infernoPlugin,
            InfernoNpcPrediction npcPrediction,
            InfernoPrayerRecommendation prayerRecommendation
    )
    {
        this.client = client;
        this.infernoPlugin = infernoPlugin;
        this.npcPrediction = npcPrediction;
        this.prayerRecommendation = prayerRecommendation;
        setPosition(OverlayPosition.TOP_LEFT);
    }

    @Override
    public Dimension render(Graphics2D graphics)
    {
        panelComponent.getChildren().clear();

        if (!infernoPlugin.isInInferno() || client.getLocalPlayer() == null)
        {
            return null;
        }

        WorldPoint playerLoc = client.getLocalPlayer().getWorldLocation();
        List<InfernoNPC> infernoNpcs = infernoPlugin.getInfernoNpcs();
        Map<WorldPoint, Integer> safeSpotMap = infernoPlugin.getSafeSpotMap();

        // Prayer recommendation
        Prayer recPrayer = prayerRecommendation.recommendPrayer(infernoNpcs, client, playerLoc, safeSpotMap);

        String prayerText = recPrayer == null ? "No Prayer Needed" : recPrayer.name().replace("_", " ");
        panelComponent.getChildren().add(TitleComponent.builder()
                .text("Recommended Prayer: " + prayerText)
                .color(recPrayer == null ? Color.GREEN : Color.ORANGE)
                .build());

        // 1-tick flick/overlay indicator
        boolean flickable = infernoPlugin.isFlickPossible();
        if (flickable)
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
        WorldPoint optimalTile = findOptimalTile(infernoNpcs, client, safeSpotMap, playerLoc);
        if (optimalTile != null)
        {
            panelComponent.getChildren().add(TitleComponent.builder()
                    .text("Optimal Tile: " + optimalTile)
                    .color(Color.YELLOW)
                    .build());
        }

        // Show spawn indicators (for next wave)
        // (Stub: you would use your own spawn prediction logic/data)
        // panelComponent.getChildren().add(...);

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
    private WorldPoint findOptimalTile(List<InfernoNPC> infernoNpcs, Client client, Map<WorldPoint, Integer> safeSpotMap, WorldPoint playerLoc)
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