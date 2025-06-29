package net.runelite.client.plugins.gearstate;

import com.google.gson.Gson;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.*;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.events.*;
import net.runelite.api.widgets.Widget;
import net.runelite.api.widgets.WidgetInfo;
import net.runelite.api.widgets.WidgetID;
import net.runelite.api.kit.KitType;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.eventbus.EventBus;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.plugins.PrayAgainstPlayer.RecommendedPrayerChangedEvent;
import net.runelite.client.game.ItemManager;

import javax.inject.Inject;
import java.awt.*;
import java.net.InetSocketAddress;
import java.util.*;
import java.util.List;
import java.util.concurrent.*;

import org.java_websocket.server.WebSocketServer;
import org.java_websocket.WebSocket;
import org.java_websocket.handshake.ClientHandshake;

@Slf4j
@PluginDescriptor(
        name = "Gearstate WebSocket Exporter",
        description = "Sends game/UI state and tick events over WebSocket for event-driven automation",
        tags = {"gear", "coords", "websocket", "automation"}
)
public class InventoryEventWebSocketPlugin extends Plugin
{
    @Inject private Client client;
    @Inject private ClientThread clientThread;
    @Inject private EventBus eventBus;
    @Inject private ItemManager itemManager;

    private static final Gson gson = new Gson();
    private static final int WS_PORT = 8765;

    private SimpleWebSocketServer wsServer;
    private ScheduledExecutorService scheduler;

    private String lastTabName = null;
    private int lastEquipmentHash = 0;
    private int lastInventoryHash = 0;

    // Store the latest recommended prayer received from the event
    private volatile String recommendedPrayer = null;
    private long lastPrayerEventTime = 0;

    // Self-contained robust freeze detection (all types, clears on enemy movement)
    private enum FreezeType {
        BIND(181, 8),
        SNARE(180, 16),
        ENTANGLE(179, 24),
        RUSH(361, 8),
        BURST(363, 16),
        BLITZ(367, 24),
        BARRAGE(369, 32),
        SCORCHING_BOW(2808, 20);
        public final int graphicId;
        public final int durationTicks;
        FreezeType(int graphicId, int durationTicks) {
            this.graphicId = graphicId;
            this.durationTicks = durationTicks;
        }
        static FreezeType fromGraphic(int graphicId) {
            for (FreezeType f : values()) if (f.graphicId == graphicId) return f;
            return null;
        }
    }
    private int frozenTicksRemaining = 0;
    private Player trackedOpponent = null;
    private WorldPoint lastFrozenOpponentLoc = null;

    @Override
    protected void startUp()
    {
        wsServer = new SimpleWebSocketServer(WS_PORT);
        wsServer.start();
        scheduler = Executors.newSingleThreadScheduledExecutor();
        scheduler.scheduleAtFixedRate(this::broadcastFullState, 0, 200, TimeUnit.MILLISECONDS);
        eventBus.register(this);
        recommendedPrayer = null;
        lastPrayerEventTime = 0;
        frozenTicksRemaining = 0;
        trackedOpponent = null;
        lastFrozenOpponentLoc = null;
        log.info("GearstateWebSocketPlugin started on port {} - Prayer event listening enabled", WS_PORT);
    }

    @Override
    protected void shutDown()
    {
        try {
            if (wsServer != null) wsServer.stop(1000);
        } catch (Exception e) {
            log.warn("Error stopping WebSocket server", e);
        }
        wsServer = null;
        if (scheduler != null) {
            scheduler.shutdownNow();
            scheduler = null;
        }
        lastTabName = null;
        lastEquipmentHash = 0;
        lastInventoryHash = 0;
        eventBus.unregister(this);
        recommendedPrayer = null;
        lastPrayerEventTime = 0;
        frozenTicksRemaining = 0;
        trackedOpponent = null;
        lastFrozenOpponentLoc = null;
        log.info("GearstateWebSocketPlugin stopped");
    }

    private void broadcastFullState()
    {
        clientThread.invokeLater(() -> {
            Map<String, Object> state = getTickState();
            wsSend("tick_state", state, false);
        });
    }

    @Subscribe
    public void onGameTick(GameTick event)
    {
        long tickTimestamp = System.currentTimeMillis();
        String detectedTab = detectTabByWidget();
        if (detectedTab != null && !detectedTab.equals(lastTabName)) {
            lastTabName = detectedTab;
            wsSend("tab_changed", createTabData(detectedTab, -1), true);
        }

        // --- Robust freeze handling for 1v1 opponent ---
        Player local = client.getLocalPlayer();
        Player opponent = null;
        if (local != null) {
            Actor interacting = local.getInteracting();
            if (interacting instanceof Player)
                opponent = (Player) interacting;
        }
        if (opponent != null) {
            int graphic = opponent.getGraphic();
            FreezeType freeze = FreezeType.fromGraphic(graphic);
            if (freeze != null) {
                frozenTicksRemaining = freeze.durationTicks;
                trackedOpponent = opponent;
                lastFrozenOpponentLoc = opponent.getWorldLocation();
                log.debug("[gearstate] Detected freeze graphic {} on {} ({} ticks)", graphic, opponent.getName(), freeze.durationTicks);
            } else if (opponent == trackedOpponent && frozenTicksRemaining > 0) {
                WorldPoint currLoc = opponent.getWorldLocation();
                if (lastFrozenOpponentLoc != null && !currLoc.equals(lastFrozenOpponentLoc)) {
                    frozenTicksRemaining = 0;
                    log.debug("[gearstate] Opponent moved ({} -> {}), clearing freeze.", lastFrozenOpponentLoc, currLoc);
                } else {
                    frozenTicksRemaining--;
                }
            } else if (opponent != trackedOpponent) {
                frozenTicksRemaining = 0;
                trackedOpponent = opponent;
                lastFrozenOpponentLoc = null;
            }
        } else {
            frozenTicksRemaining = 0;
            trackedOpponent = null;
            lastFrozenOpponentLoc = null;
        }

        Map<String, Object> state = getTickState();
        state.put("tick_timestamp", tickTimestamp);
        wsSend("tick_state", state, false);

        Map<String, Object> tickData = new HashMap<>();
        tickData.put("tick", client.getTickCount());
        tickData.put("tick_timestamp", tickTimestamp);
        wsSend("tick", tickData, false);
    }

    @Subscribe
    public void onRecommendedPrayerChangedEvent(RecommendedPrayerChangedEvent event)
    {
        try {
            String newPrayer = event.getRecommendedPrayer();
            long currentTime = System.currentTimeMillis();

            recommendedPrayer = newPrayer;
            lastPrayerEventTime = currentTime;

            log.info("[gearstate] Received RecommendedPrayerChangedEvent: {} at time: {}",
                    newPrayer, currentTime);

            Map<String, Object> prayerData = new HashMap<>();
            prayerData.put("recommended_prayer", newPrayer);
            prayerData.put("timestamp", currentTime);
            wsSend("recommended_protection_prayer", prayerData, true);

        } catch (Exception e) {
            log.error("[gearstate] Error handling RecommendedPrayerChangedEvent", e);
        }
    }

    private String detectTabByWidget() {
        if (isWidgetVisible(WidgetInfo.INVENTORY)) return "inventory";
        if (isWidgetVisible(WidgetInfo.EQUIPMENT)) return "equipment";
        if (isWidgetVisible(WidgetInfo.SPELL_LUMBRIDGE_HOME_TELEPORT)) return "spellbook";
        if (isPrayerTabOpen()) return "prayer";
        if (isWidgetVisible(WidgetInfo.COMBAT_LEVEL)) return "combat";
        if (isWidgetVisible(WidgetInfo.QUESTLIST_BOX)) return "quest";
        if (isWidgetVisible(WidgetInfo.SKILLS_CONTAINER)) return "stats";
        if (isWidgetVisible(WidgetInfo.FRIENDS_LIST)) return "friends";
        if (isWidgetVisible(WidgetInfo.IGNORE_LIST)) return "ignore";
        if (isWidgetVisible(WidgetInfo.FRIENDS_CHAT)) return "clan";
        if (isWidgetVisible(WidgetInfo.EMOTE_WINDOW)) return "emotes";
        if (isWidgetVisible(WidgetInfo.MUSIC_WINDOW)) return "music";
        if (isWidgetVisible(WidgetInfo.LOGOUT_BUTTON)) return "logout";
        if (isWidgetVisible(WidgetInfo.SETTINGS_INIT)) return "settings";
        return null;
    }

    private boolean isPrayerTabOpen() {
        Widget root = client.getWidget(WidgetID.PRAYER_GROUP_ID, 0);
        return root != null && !root.isHidden();
    }

    private boolean isWidgetVisible(WidgetInfo widgetInfo) {
        Widget w = client.getWidget(widgetInfo);
        return w != null && !w.isHidden();
    }

    @Subscribe
    public void onVarbitChanged(VarbitChanged event)
    {
        int specPercent = client.getVarpValue(VarPlayer.SPECIAL_ATTACK_PERCENT);
        boolean specEnabled = client.getVarpValue(VarPlayer.SPECIAL_ATTACK_ENABLED) == 1;
        Map<String, Object> specBar = new HashMap<>();
        specBar.put("percent", specPercent / 10);
        specBar.put("enabled", specEnabled);
        wsSend("spec_bar", specBar, false);
    }

    @Subscribe
    public void onItemContainerChanged(ItemContainerChanged event)
    {
        int id = event.getContainerId();
        if (id == InventoryID.EQUIPMENT.getId())
        {
            ItemContainer equipment = event.getItemContainer();
            int hash = Arrays.hashCode(equipment != null ? equipment.getItems() : new Item[0]);
            if (hash != lastEquipmentHash) {
                wsSend("equipment_changed", Collections.singletonMap("items", itemsToMapWithNames(equipment)), true);
                lastEquipmentHash = hash;
            }
        }
        else if (id == InventoryID.INVENTORY.getId())
        {
            ItemContainer inv = event.getItemContainer();
            int hash = Arrays.hashCode(inv != null ? inv.getItems() : new Item[0]);
            if (hash != lastInventoryHash) {
                wsSend("inventory_changed", Collections.singletonMap("items", itemsToMapWithNames(inv)), true);
                lastInventoryHash = hash;
            }
        }
    }

    @Subscribe
    public void onGameStateChanged(GameStateChanged event)
    {
        if (event.getGameState() == GameState.LOGGED_IN) {
            Map<String, Object> state = getTickState();
            state.put("tick_timestamp", System.currentTimeMillis());
            wsSend("tick_state", state, false);
        }
    }

    /**
     * Comprehensive tick state including animation, weapon id, weapon name, graphic, all inventory/equipment, etc.
     */
    private Map<String, Object> getTickState()
    {
        Map<String, Object> output = new HashMap<>();
        if (client == null || client.getGameState() != GameState.LOGGED_IN)
            return output;

        output.put("tick", client.getTickCount());
        output.put("timestamp", System.currentTimeMillis());
        output.put("canvas_width", client.getCanvasWidth());
        output.put("canvas_height", client.getCanvasHeight());

        // --- PLAYER (SELF) DATA PATCH ---
        Player local = client.getLocalPlayer();
        if (local != null)
        {
            WorldPoint wp = local.getWorldLocation();
            Map<String, Object> playerPos = new HashMap<>();
            playerPos.put("x", wp.getX());
            playerPos.put("y", wp.getY());
            playerPos.put("plane", wp.getPlane());
            output.put("player_world_position", playerPos);
            output.put("player_name", local.getName());

            output.put("player_animation", local.getAnimation());
            output.put("player_graphic", local.getGraphic());

            ItemContainer equipment = client.getItemContainer(InventoryID.EQUIPMENT);
            output.put("equipment", itemsToMapWithNames(equipment));

            int weaponId = -1;
            String weaponName = null;
            PlayerComposition pc = local.getPlayerComposition();
            if (pc != null)
            {
                weaponId = pc.getEquipmentId(KitType.WEAPON);
                if (itemManager != null && weaponId > 0)
                {
                    ItemComposition weaponComp = itemManager.getItemComposition(weaponId);
                    if (weaponComp != null)
                        weaponName = weaponComp.getName();
                }
            }
            output.put("player_weapon_id", weaponId > 0 ? weaponId : null);
            output.put("player_weapon_name", weaponName);
        }

        // --- OPPONENT DATA (IF PLAYER INTERACTING WITH ANOTHER PLAYER) ---
        Player target = null;
        if (local != null)
        {
            Actor interacting = local.getInteracting();
            if (interacting instanceof Player)
            {
                target = (Player) interacting;
            }
        }
        if (target != null)
        {
            Map<String, Object> oppData = new HashMap<>();
            oppData.put("name", target.getName());
            WorldPoint tp = target.getWorldLocation();
            Map<String, Object> pos = new HashMap<>();
            pos.put("x", tp.getX());
            pos.put("y", tp.getY());
            pos.put("plane", tp.getPlane());
            oppData.put("world_position", pos);

            oppData.put("animation", target.getAnimation());
            oppData.put("graphic", target.getGraphic());

            PlayerComposition oppPc = target.getPlayerComposition();
            Map<String, Object> oppEquipment = new HashMap<>();
            Integer oppWeaponId = null;
            String oppWeaponName = null;
            if (oppPc != null) {
                for (KitType kitType : KitType.values()) {
                    int eqId = oppPc.getEquipmentId(kitType);
                    if (eqId > 0) {
                        Map<String, Object> eqMap = new HashMap<>();
                        eqMap.put("item_id", eqId);
                        String eqName = null;
                        if (itemManager != null) {
                            ItemComposition eqComp = itemManager.getItemComposition(eqId);
                            if (eqComp != null)
                                eqName = eqComp.getName();
                        }
                        eqMap.put("name", eqName);
                        oppEquipment.put(String.valueOf(kitType.getIndex()), eqMap);
                        if (kitType == KitType.WEAPON) {
                            oppWeaponId = eqId;
                            oppWeaponName = eqName;
                        }
                    }
                }
            }
            oppData.put("equipment", oppEquipment);
            oppData.put("weapon_id", oppWeaponId);
            oppData.put("weapon_name", oppWeaponName);

            Actor oppInteracting = target.getInteracting();
            oppData.put("interacting", oppInteracting instanceof Player ? ((Player) oppInteracting).getName() : null);

            // Robust freeze state: expose to websocket/statestream
            oppData.put("frozen_ticks_remaining", frozenTicksRemaining);
            oppData.put("frozen_active", frozenTicksRemaining > 0);

            output.put("opponent", oppData);
            output.put("opponent_frozen_ticks", frozenTicksRemaining);
        } else {
            output.put("opponent_frozen_ticks", 0);
        }

        List<Map<String, Object>> armedPlayers = new ArrayList<>();
        List<Player> players = client.getPlayers();
        if (players != null) {
            for (Player p : players) {
                if (p == null) continue;
                PlayerComposition pc = p.getPlayerComposition();
                if (pc == null) continue;
                int weapon = pc.getEquipmentId(KitType.WEAPON);
                int shield = pc.getEquipmentId(KitType.SHIELD);
                if ((weapon > 0 && weapon != -1) || (shield > 0 && shield != -1)) {
                    Map<String, Object> playerMap = new HashMap<>();
                    playerMap.put("name", p.getName());
                    WorldPoint pwp = p.getWorldLocation();
                    playerMap.put("x", pwp.getX());
                    playerMap.put("y", pwp.getY());
                    playerMap.put("plane", pwp.getPlane());
                    armedPlayers.add(playerMap);
                }
            }
        }
        output.put("armed_players", armedPlayers);

        boolean inWilderness = client.getVarbitValue(5963) == 1;
        boolean onPvp = client.getWorldType() != null && (
                client.getWorldType().contains(WorldType.PVP) ||
                        client.getWorldType().contains(WorldType.HIGH_RISK)
        );
        boolean inSafe = !inWilderness && !onPvp;
        output.put("in_wilderness", inWilderness);
        output.put("on_pvp_or_high_risk", onPvp);
        output.put("in_safe_zone", inSafe);

        List<Map<String, Object>> prayers = new ArrayList<>();
        for (Prayer prayer : Prayer.values())
        {
            int prayerVarbit = prayer.getVarbit();
            if (prayerVarbit != -1 && client.getVarbitValue(prayerVarbit) == 1)
            {
                Map<String, Object> prayerMap = new HashMap<>();
                prayerMap.put("name", prayer.name().toLowerCase());
                prayerMap.put("id", prayer.ordinal());
                prayers.add(prayerMap);
            }
        }
        output.put("prayers", prayers);

        ItemContainer inventory = client.getItemContainer(InventoryID.INVENTORY);
        output.put("inventory_slots", itemsToMapWithNames(inventory));

        String detectedTab = detectTabByWidget();
        output.put("tab", detectedTab);
        output.put("tab_idx", -1);

        int specPercent = client.getVarpValue(VarPlayer.SPECIAL_ATTACK_PERCENT);
        boolean specEnabled = client.getVarpValue(VarPlayer.SPECIAL_ATTACK_ENABLED) == 1;
        Map<String, Object> specBar = new HashMap<>();
        specBar.put("percent", specPercent / 10);
        specBar.put("enabled", specEnabled);
        output.put("spec_bar", specBar);

        Map<String, Object> spells = new HashMap<>();
        spells.put("ice_barrage", spellBox(566, 381, 24, 24));
        spells.put("blood_barrage", spellBox(698, 353, 24, 24));
        spells.put("vengeance", spellBox(555, 416, 24, 24));
        spells.put("augury", spellBox(662, 399, 34, 34));
        spells.put("rigour", spellBox(625, 399, 34, 34));
        spells.put("piety", spellBox(588, 399, 34, 34));
        output.put("spells", spells);

        output.put("recommended_protection_prayer", recommendedPrayer);
        output.put("last_prayer_event_time", lastPrayerEventTime);

        return output;
    }

    private Map<String, Object> itemsToMapWithNames(ItemContainer container)
    {
        Map<String, Object> items = new HashMap<>();
        if (container != null && container.getItems() != null)
        {
            Item[] arr = container.getItems();
            for (int i = 0; i < arr.length; i++)
            {
                Item it = arr[i];
                Map<String, Object> itemMap = new HashMap<>();
                int id = it != null ? it.getId() : -1;
                itemMap.put("item_id", id);
                itemMap.put("quantity", it != null ? it.getQuantity() : 0);
                String name = null;
                if (itemManager != null && id > 0) {
                    ItemComposition comp = itemManager.getItemComposition(id);
                    if (comp != null) name = comp.getName();
                }
                itemMap.put("name", name);
                items.put(String.valueOf(i), itemMap);
            }
        }
        return items;
    }

    private Map<String, Object> createTabData(String tabName, int tabIdx) {
        Map<String, Object> data = new HashMap<>();
        data.put("tab", tabName);
        data.put("tab_idx", tabIdx);
        return data;
    }

    private static Map<String, Object> spellBox(int x, int y, int width, int height) {
        Map<String, Object> map = new HashMap<>();
        map.put("x", x);
        map.put("y", y);
        map.put("width", width);
        map.put("height", height);
        return map;
    }

    private void wsSend(String type, Object data, boolean debug)
    {
        if (wsServer == null) return;
        Map<String, Object> wrapped = new LinkedHashMap<>();
        wrapped.put("type", type);
        wrapped.put("data", data);
        String msg = gson.toJson(wrapped);
        wsServer.broadcast(msg);
        if (debug) {
            if (type.equals("tab_changed") || type.equals("equipment_changed") ||
                    type.equals("inventory_changed") || type.equals("recommended_protection_prayer")) {
                log.info("[gearstate] {}", type);
            }
        }
    }

    private static class SimpleWebSocketServer extends WebSocketServer
    {
        public SimpleWebSocketServer(int port) {
            super(new InetSocketAddress(port));
        }
        @Override public void onOpen(WebSocket conn, ClientHandshake handshake) {}
        @Override public void onClose(WebSocket conn, int code, String reason, boolean remote) {}
        @Override public void onMessage(WebSocket conn, String message) {}
        @Override public void onError(WebSocket conn, Exception ex) {}
        @Override public void onStart() {}
    }
}