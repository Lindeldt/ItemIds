package net.runelite.client.plugins.PrayAgainstPlayer;

import com.google.inject.Provides;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.image.BufferedImage;
import java.awt.image.ColorModel;
import java.awt.image.IndexColorModel;
import java.awt.image.WritableRaster;
import java.util.*;
import javax.inject.Inject;
import javax.inject.Singleton;

import lombok.extern.slf4j.Slf4j;
import net.runelite.api.*;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.events.AnimationChanged;
import net.runelite.api.events.GameStateChanged;
import net.runelite.api.events.GameTick;
import net.runelite.api.events.InteractingChanged;
import net.runelite.api.events.PlayerDespawned;
import net.runelite.api.events.PlayerSpawned;
import net.runelite.api.kit.KitType;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.EventBus;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.game.SpriteManager;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.ui.overlay.OverlayManager;
import net.runelite.client.util.ImageUtil;
import net.runelite.client.plugins.PiggyUtils.API.PrayerUtil;

/**
 * PrayVsPlayer Plugin - Robust freeze handling for 1v1 PvP.
 * - Tracks all freeze types using spotanims (graphic ids)
 * - Recommends melee only if enemy is not frozen and hasn't moved since frozen
 * - Clears frozen state if enemy moves a tile
 */
@Slf4j
@PluginDescriptor(
        name = "Pray Vs Player",
        description = "PvP prayer recommendation (robust freeze, movement resets freeze, 1v1 melee logic)",
        tags = {"highlight", "pvp", "overlay", "players", "piggy"},
        enabledByDefault = false
)
@Singleton
public class PrayAgainstPlayerPlugin extends Plugin
{
    private static final int[] PROTECTION_ICONS = {
            SpriteID.PRAYER_PROTECT_FROM_MISSILES,
            SpriteID.PRAYER_PROTECT_FROM_MELEE,
            SpriteID.PRAYER_PROTECT_FROM_MAGIC
    };
    private static final Dimension PROTECTION_ICON_DIMENSION = new Dimension(33, 33);
    private static final Color PROTECTION_ICON_OUTLINE_COLOR = new Color(33, 33, 33);
    private final BufferedImage[] ProtectionIcons = new BufferedImage[PROTECTION_ICONS.length];

    // Tracking containers
    private List<PlayerContainer> potentialPlayersAttackingMe;
    private List<PlayerContainer> playersAttackingMe;

    // Track last seen equipment for attackers (for instant re-evaluation)
    private final Map<Player, AttackerEquipSnapshot> attackerEquipmentMap = new HashMap<>();

    // Track last tick we saw an attack/interaction for smarter timeouts
    private final Map<Player, Long> attackerLastSeenTick = new HashMap<>();

    @Inject private Client client;
    @Inject private SpriteManager spriteManager;
    @Inject private OverlayManager overlayManager;
    @Inject private PrayAgainstPlayerOverlay overlay;
    @Inject private PrayAgainstPlayerOverlayPrayerTab overlayPrayerTab;
    @Inject private PrayAgainstPlayerConfig config;
    @Inject private EventBus eventBus;

    private String lastRecommendedPrayer = null;
    private int prayerCheckTicks = 0;

    // --- Debounce for websocket event posting ---
    private long lastPostedPrayerTime = 0;
    private String lastPostedPrayerName = null;
    private static final long RECOMMENDATION_MIN_INTERVAL_MS = 500; // Only affects websocket

    // Animation constants
    public static final int BLOCK_DEFENDER = 4177;
    public static final int BLOCK_NO_SHIELD = 420;
    public static final int BLOCK_SHIELD = 1156;
    public static final int BLOCK_SWORD = 388;
    public static final int BLOCK_UNARMED = 424;

    // Timeout config (ms)
    private static final int ATTACKER_TIMEOUT_MS = 10_000;

    // Weapon IDs for halberd-like and granite maul weapons for extended melee range
    private static final Set<Integer> HALBERD_WEAPON_IDS = new HashSet<>(Arrays.asList(
            3190, 3192, 3194, 3196, 20431, 25985, 20557
    ));

    // --- Robust freeze tracking ---
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
    private WorldPoint lastFrozenOpponentLoc = null; // For movement break

    @Provides
    PrayAgainstPlayerConfig provideConfig(ConfigManager configManager) {
        return configManager.getConfig(PrayAgainstPlayerConfig.class);
    }

    @Subscribe
    private void onGameStateChanged(GameStateChanged gameStateChanged) {
        if (gameStateChanged.getGameState() == GameState.LOGGED_IN) {
            loadProtectionIcons();
        }
    }

    @Subscribe
    private void onGameTick(GameTick event) {
        prayerCheckTicks++;

        if (client.getGameState() != GameState.LOGGED_IN) {
            postRecommendationIfChanged(null);
            clearAttackers();
            frozenTicksRemaining = 0;
            trackedOpponent = null;
            lastFrozenOpponentLoc = null;
            return;
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
                log.debug("[PrayVsPlayer] Detected freeze graphic {} on {} ({} ticks)", graphic, opponent.getName(), freeze.durationTicks);
            } else if (opponent == trackedOpponent && frozenTicksRemaining > 0) {
                // If the opponent has moved, clear frozen state immediately
                WorldPoint currLoc = opponent.getWorldLocation();
                if (lastFrozenOpponentLoc != null && !currLoc.equals(lastFrozenOpponentLoc)) {
                    frozenTicksRemaining = 0;
                    log.debug("[PrayVsPlayer] Opponent moved ({} -> {}), clearing freeze.", lastFrozenOpponentLoc, currLoc);
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

        // Smarter timeout: Remove attackers who haven't been seen recently
        pruneInactiveAttackers();
        // Check for equipment changes in attackers (instant re-evaluation)
        refreshAttackerEquipment();

        Prayer rec = getPrayerToActivate();
        String name = rec != null ? rec.name().toLowerCase() : null;

        if (playersAttackingMe == null || playersAttackingMe.isEmpty()) {
            postRecommendationIfChanged(null);
        } else {
            postRecommendationIfChanged(name);
        }

        if (config.activatePrayer() && rec != null && !PrayerUtil.isPrayerActive(rec)) {
            PrayerUtil.togglePrayer(rec);
        }
    }

    /** Remove all attackers from state */
    private void clearAttackers() {
        if (playersAttackingMe != null) playersAttackingMe.clear();
        if (attackerEquipmentMap != null) attackerEquipmentMap.clear();
        if (attackerLastSeenTick != null) attackerLastSeenTick.clear();
    }

    /**
     * Enhanced posting with better error handling and validation.
     * Overlay is always updated immediately.
     * Websocket event is posted only if changed, or repeatedly after a minimum interval.
     */
    private void postRecommendationIfChanged(String newPrayerName)
    {
        try
        {
            long now = System.currentTimeMillis();

            // Overlay logic: always update lastRecommendedPrayer for overlay (no debounce)
            boolean overlayChanged = !Objects.equals(lastRecommendedPrayer, newPrayerName);
            if (overlayChanged)
            {
                lastRecommendedPrayer = newPrayerName;
            }

            // Websocket logic: only post if prayer changed, or enough time passed for same prayer
            boolean shouldPost = false;
            if (lastPostedPrayerName == null && newPrayerName != null)
            {
                shouldPost = true;
            }
            else if (lastPostedPrayerName != null && !Objects.equals(lastPostedPrayerName, newPrayerName))
            {
                shouldPost = true;
            }
            else if (lastPostedPrayerName != null && newPrayerName == null)
            {
                shouldPost = true;
            }
            else if (Objects.equals(lastPostedPrayerName, newPrayerName)
                    && now - lastPostedPrayerTime > RECOMMENDATION_MIN_INTERVAL_MS)
            {
                shouldPost = true;
            }

            if (shouldPost)
            {
                lastPostedPrayerName = newPrayerName;
                lastPostedPrayerTime = now;
                log.info("[PrayVsPlayer] Posting RecommendedPrayerChangedEvent: {} (tick: {})", newPrayerName, prayerCheckTicks);
                RecommendedPrayerChangedEvent event = new RecommendedPrayerChangedEvent(newPrayerName);
                eventBus.post(event);
                log.debug("[PrayVsPlayer] Event posted successfully for prayer: {}", newPrayerName);
            }
        }
        catch (Exception e)
        {
            log.error("[PrayVsPlayer] Error posting prayer recommendation event", e);
        }
    }

    @Override
    protected void startUp() {
        potentialPlayersAttackingMe = new ArrayList<>();
        playersAttackingMe = new ArrayList<>();
        overlayManager.add(overlay);
        overlayManager.add(overlayPrayerTab);
        lastRecommendedPrayer = null;
        prayerCheckTicks = 0;
        attackerEquipmentMap.clear();
        attackerLastSeenTick.clear();
        frozenTicksRemaining = 0;
        trackedOpponent = null;
        lastFrozenOpponentLoc = null;
        log.info("PrayAgainstPlayerPlugin started - EventBus registered: {}", eventBus != null);
    }

    @Override
    protected void shutDown() throws Exception {
        overlayManager.remove(overlay);
        overlayManager.remove(overlayPrayerTab);
        postRecommendationIfChanged(null);
        clearAttackers();
        lastRecommendedPrayer = null;
        prayerCheckTicks = 0;
        frozenTicksRemaining = 0;
        trackedOpponent = null;
        lastFrozenOpponentLoc = null;
        log.info("PrayAgainstPlayerPlugin stopped");
    }

    @Subscribe
    private void onAnimationChanged(AnimationChanged animationChanged) {
        if ((animationChanged.getActor() instanceof Player) &&
                (animationChanged.getActor().getInteracting() instanceof Player) &&
                (animationChanged.getActor().getInteracting() == client.getLocalPlayer())) {

            Player sourcePlayer = (Player) animationChanged.getActor();

            if (client.isFriended(sourcePlayer.getName(), true) && config.ignoreFriends()) {
                return;
            }
            if (sourcePlayer.isFriendsChatMember() && config.ignoreClanMates()) {
                return;
            }

            if ((sourcePlayer.getAnimation() != -1) && (!isBlockAnimation(sourcePlayer.getAnimation()))) {
                PlayerContainer existing = findPlayerInAttackerList(sourcePlayer);
                if (existing != null) {
                    resetPlayerFromAttackerContainerTimer(existing);
                }
                PlayerContainer pot = findPlayerInPotentialList(sourcePlayer);
                if (!potentialPlayersAttackingMe.isEmpty() && potentialPlayersAttackingMe.contains(pot)) {
                    removePlayerFromPotentialContainer(pot);
                }
                if (existing == null) {
                    PlayerContainer container = new PlayerContainer(sourcePlayer, System.currentTimeMillis(), (config.attackerTargetTimeout() * 1000));
                    playersAttackingMe.add(container);
                    attackerLastSeenTick.put(sourcePlayer, System.currentTimeMillis());
                    attackerEquipmentMap.put(sourcePlayer, AttackerEquipSnapshot.of(sourcePlayer));
                    log.debug("[PrayVsPlayer] Added attacker: {}", sourcePlayer.getName());
                } else {
                    attackerLastSeenTick.put(sourcePlayer, System.currentTimeMillis());
                }
            }
        }
    }

    @Subscribe
    private void onInteractingChanged(InteractingChanged interactingChanged) {
        if ((interactingChanged.getSource() instanceof Player) && (interactingChanged.getTarget() instanceof Player)) {
            Player sourcePlayer = (Player) interactingChanged.getSource();
            Player targetPlayer = (Player) interactingChanged.getTarget();
            if ((targetPlayer == client.getLocalPlayer()) && (findPlayerInPotentialList(sourcePlayer) == null)) {
                if (client.isFriended(sourcePlayer.getName(), true) && config.ignoreFriends()) {
                    return;
                }
                if (sourcePlayer.isFriendsChatMember() && config.ignoreClanMates()) {
                    return;
                }
                PlayerContainer container = new PlayerContainer(sourcePlayer, System.currentTimeMillis(), (config.potentialTargetTimeout() * 1000));
                potentialPlayersAttackingMe.add(container);
            }
        }
    }

    @Subscribe
    private void onPlayerDespawned(PlayerDespawned playerDespawned) {
        Player player = playerDespawned.getPlayer();
        PlayerContainer container = findPlayerInAttackerList(player);
        PlayerContainer container2 = findPlayerInPotentialList(player);
        if (container != null) {
            playersAttackingMe.remove(container);
            attackerEquipmentMap.remove(player);
            attackerLastSeenTick.remove(player);
            log.debug("[PrayVsPlayer] Removed attacker: {}", player.getName());
        }
        if (container2 != null) {
            potentialPlayersAttackingMe.remove(container2);
        }
    }

    @Subscribe
    private void onPlayerSpawned(PlayerSpawned playerSpawned) {
        if (config.markNewPlayer()) {
            Player p = playerSpawned.getPlayer();

            if (client.isFriended(p.getName(), true) && config.ignoreFriends()) {
                return;
            }
            if (p.isFriendsChatMember() && config.ignoreClanMates()) {
                return;
            }

            PlayerContainer container = findPlayerInPotentialList(p);
            if (container == null) {
                container = new PlayerContainer(p, System.currentTimeMillis(), (config.newSpawnTimeout() * 1000));
                potentialPlayersAttackingMe.add(container);
            }
        }
    }

    private PlayerContainer findPlayerInAttackerList(Player player) {
        if (playersAttackingMe.isEmpty()) {
            return null;
        }
        for (PlayerContainer container : playersAttackingMe) {
            if (container.getPlayer() == player) {
                return container;
            }
        }
        return null;
    }

    private PlayerContainer findPlayerInPotentialList(Player player) {
        if (potentialPlayersAttackingMe.isEmpty()) {
            return null;
        }
        for (PlayerContainer container : potentialPlayersAttackingMe) {
            if (container.getPlayer() == player) {
                return container;
            }
        }
        return null;
    }

    private void resetPlayerFromAttackerContainerTimer(PlayerContainer container) {
        removePlayerFromAttackerContainer(container);
        PlayerContainer newContainer = new PlayerContainer(container.getPlayer(), System.currentTimeMillis(), (config.attackerTargetTimeout() * 1000));
        playersAttackingMe.add(newContainer);
        attackerLastSeenTick.put(container.getPlayer(), System.currentTimeMillis());
    }

    void removePlayerFromPotentialContainer(PlayerContainer container) {
        if ((potentialPlayersAttackingMe != null) && (!potentialPlayersAttackingMe.isEmpty())) {
            potentialPlayersAttackingMe.remove(container);
        }
    }

    void removePlayerFromAttackerContainer(PlayerContainer container) {
        if ((playersAttackingMe != null) && (!playersAttackingMe.isEmpty())) {
            playersAttackingMe.remove(container);
            attackerEquipmentMap.remove(container.getPlayer());
            attackerLastSeenTick.remove(container.getPlayer());
        }
    }

    private boolean isBlockAnimation(int anim) {
        switch (anim) {
            case BLOCK_DEFENDER:
            case BLOCK_NO_SHIELD:
            case BLOCK_SHIELD:
            case BLOCK_SWORD:
            case BLOCK_UNARMED:
                return true;
            default:
                return false;
        }
    }

    List<PlayerContainer> getPotentialPlayersAttackingMe() {
        return potentialPlayersAttackingMe;
    }

    List<PlayerContainer> getPlayersAttackingMe() {
        return playersAttackingMe;
    }

    /**
     * Improved: recommend prayer for single or multiple attackers.
     * If 1v1 and opponent is frozen and hasn't moved, don't recommend melee.
     * If >1, picks the most common style among all attackers.
     * If melee, will NOT recommend melee if all melee attackers are out of melee range (inc. halberd/gmaul/diagonal logic).
     * If all melee attackers are out of range, fallback is always ranged.
     */
    public Prayer getPrayerToActivate() {
        try {
            // 1v1 logic with robust freeze/movement
            if (playersAttackingMe != null && playersAttackingMe.size() == 1 && trackedOpponent != null) {
                PlayerContainer container = playersAttackingMe.get(0);
                Player attacker = container.getPlayer();
                WeaponType type = WeaponType.checkWeaponOnPlayer(client, attacker);

                if (frozenTicksRemaining > 0 && type == WeaponType.WEAPON_MELEE) {
                    // Only recommend Protect from Missiles if *out of melee range* while opponent is frozen and using melee.
                    Player local = client.getLocalPlayer();
                    WorldPoint myLoc = (local != null) ? local.getWorldLocation() : null;
                    WorldPoint attackerLoc = attacker.getWorldLocation();
                    int weaponId = -1;
                    try {
                        weaponId = attacker.getPlayerComposition().getEquipmentId(KitType.WEAPON);
                    } catch (Exception ignore) {}

                    // If not in melee range, recommend Protect from Missiles
                    if (!isInMeleeRange(myLoc, attackerLoc, weaponId)) {
                        return Prayer.PROTECT_FROM_MISSILES;
                    } else {
                        return Prayer.PROTECT_FROM_MELEE;
                    }
                }

                // Not frozen, just recommend by weapon as usual
                switch (type) {
                    case WEAPON_MAGIC:
                        return Prayer.PROTECT_FROM_MAGIC;
                    case WEAPON_MELEE:
                        return Prayer.PROTECT_FROM_MELEE;
                    case WEAPON_RANGED:
                        return Prayer.PROTECT_FROM_MISSILES;
                }
            }

            // Multi-attacker logic (unchanged from your original)
            if (playersAttackingMe != null && !playersAttackingMe.isEmpty()) {
                Map<WeaponType, Integer> styleCounts = new EnumMap<>(WeaponType.class);
                Map<Player, WeaponType> playerStyle = new HashMap<>();
                for (PlayerContainer container : playersAttackingMe) {
                    Player attacker = container.getPlayer();
                    if (attacker != null) {
                        WeaponType type = WeaponType.checkWeaponOnPlayer(client, attacker);
                        playerStyle.put(attacker, type);
                        styleCounts.put(type, styleCounts.getOrDefault(type, 0) + 1);
                        log.debug("[PrayVsPlayer] Attacker: {}, Weapon type: {}", attacker.getName(), type);
                    }
                }
                WeaponType bestType = null;
                int bestCount = 0;
                for (Map.Entry<WeaponType, Integer> entry : styleCounts.entrySet()) {
                    if (entry.getKey() != WeaponType.WEAPON_UNKNOWN && entry.getValue() > bestCount) {
                        bestType = entry.getKey();
                        bestCount = entry.getValue();
                    }
                }
                if (bestType == WeaponType.WEAPON_MELEE) {
                    boolean allOutOfRange = true;
                    WorldPoint myLoc = client.getLocalPlayer() != null ? client.getLocalPlayer().getWorldLocation() : null;
                    for (Map.Entry<Player, WeaponType> entry : playerStyle.entrySet()) {
                        if (entry.getValue() == WeaponType.WEAPON_MELEE) {
                            Player attacker = entry.getKey();
                            WorldPoint attackerLoc = attacker.getWorldLocation();
                            int weaponId = -1;
                            try {
                                weaponId = attacker.getPlayerComposition().getEquipmentId(KitType.WEAPON);
                            } catch (Exception ignore) {}
                            if (myLoc != null && attackerLoc != null && isInMeleeRange(myLoc, attackerLoc, weaponId)) {
                                allOutOfRange = false;
                                break;
                            }
                        }
                    }
                    if (allOutOfRange) {
                        log.debug("[PrayVsPlayer] Not recommending melee prayer, all melee attackers are out of range; fallback to ranged.");
                        bestType = WeaponType.WEAPON_RANGED;
                    }
                }
                if (bestType == null) {
                    log.debug("[PrayVsPlayer] All attackers unknown style or none present");
                    return null;
                }
                switch (bestType) {
                    case WEAPON_MAGIC:
                        return Prayer.PROTECT_FROM_MAGIC;
                    case WEAPON_MELEE:
                        return Prayer.PROTECT_FROM_MELEE;
                    case WEAPON_RANGED:
                        return Prayer.PROTECT_FROM_MISSILES;
                }
            }
        } catch (Exception e) {
            log.error("[PrayVsPlayer] Error determining prayer to activate", e);
        }
        return null;
    }

    // --- Helper for melee range calculation ---
    private boolean isInMeleeRange(WorldPoint player, WorldPoint attacker, int weaponId) {
        if (player == null || attacker == null) return false;
        int dx = Math.abs(player.getX() - attacker.getX());
        int dy = Math.abs(player.getY() - attacker.getY());
        int maxRange = HALBERD_WEAPON_IDS.contains(weaponId) ? 2 : 1;
        if (dx == 1 && dy == 1) {
            return false;
        }
        return (dx <= maxRange && dy <= maxRange && dx + dy <= maxRange);
    }

    // Helper for websocket plugins: get the recommended prayer as a string (lowercase)
    public String getPrayerToActivateName() {
        Prayer p = getPrayerToActivate();
        return p != null ? p.name().toLowerCase() : null;
    }

    private void loadProtectionIcons() {
        for (int i = 0; i < PROTECTION_ICONS.length; i++) {
            final int resource = PROTECTION_ICONS[i];
            ProtectionIcons[i] = rgbaToIndexedBufferedImage(ProtectionIconFromSprite(spriteManager.getSprite(resource, 0)));
        }
    }

    private static BufferedImage rgbaToIndexedBufferedImage(final BufferedImage sourceBufferedImage) {
        final BufferedImage indexedImage = new BufferedImage(
                sourceBufferedImage.getWidth(),
                sourceBufferedImage.getHeight(),
                BufferedImage.TYPE_BYTE_INDEXED);

        final ColorModel cm = indexedImage.getColorModel();
        final IndexColorModel icm = (IndexColorModel) cm;

        final int size = icm.getMapSize();
        final byte[] reds = new byte[size];
        final byte[] greens = new byte[size];
        final byte[] blues = new byte[size];
        icm.getReds(reds);
        icm.getGreens(greens);
        icm.getBlues(blues);

        final WritableRaster raster = indexedImage.getRaster();
        final int pixel = raster.getSample(0, 0, 0);
        final IndexColorModel resultIcm = new IndexColorModel(8, size, reds, greens, blues, pixel);
        final BufferedImage resultIndexedImage = new BufferedImage(resultIcm, raster, sourceBufferedImage.isAlphaPremultiplied(), null);
        resultIndexedImage.getGraphics().drawImage(sourceBufferedImage, 0, 0, null);
        return resultIndexedImage;
    }

    private static BufferedImage ProtectionIconFromSprite(final BufferedImage freezeSprite) {
        final BufferedImage freezeCanvas = ImageUtil.resizeCanvas(freezeSprite, PROTECTION_ICON_DIMENSION.width, PROTECTION_ICON_DIMENSION.height);
        return ImageUtil.outlineImage(freezeCanvas, PROTECTION_ICON_OUTLINE_COLOR);
    }

    BufferedImage getProtectionIcon(WeaponType weaponType) {
        switch (weaponType) {
            case WEAPON_RANGED:
                return ProtectionIcons[0];
            case WEAPON_MELEE:
                return ProtectionIcons[1];
            case WEAPON_MAGIC:
                return ProtectionIcons[2];
        }
        return null;
    }

    private void refreshAttackerEquipment() {
        boolean updated = false;
        for (PlayerContainer container : new ArrayList<>(playersAttackingMe)) {
            Player attacker = container.getPlayer();
            if (attacker == null)
                continue;
            AttackerEquipSnapshot prev = attackerEquipmentMap.get(attacker);
            AttackerEquipSnapshot current = AttackerEquipSnapshot.of(attacker);
            if (prev == null || !prev.equals(current)) {
                attackerEquipmentMap.put(attacker, current);
                updated = true;
                log.debug("[PrayVsPlayer] Equipment changed for attacker {}, updating detection.", attacker.getName());
            }
        }
        if (updated) {
            lastRecommendedPrayer = null;
        }
    }

    private void pruneInactiveAttackers() {
        long now = System.currentTimeMillis();
        Iterator<PlayerContainer> it = playersAttackingMe.iterator();
        while (it.hasNext()) {
            PlayerContainer container = it.next();
            Player player = container.getPlayer();
            Long lastSeen = attackerLastSeenTick.get(player);
            if (lastSeen == null || now - lastSeen > ATTACKER_TIMEOUT_MS) {
                it.remove();
                attackerLastSeenTick.remove(player);
                attackerEquipmentMap.remove(player);
                log.debug("[PrayVsPlayer] Pruned inactive attacker: {}", player.getName());
            }
        }
    }

    private static class AttackerEquipSnapshot {
        public final int weaponId;
        public final int torsoId;

        private AttackerEquipSnapshot(int weaponId, int torsoId) {
            this.weaponId = weaponId;
            this.torsoId = torsoId;
        }

        public static AttackerEquipSnapshot of(Player player) {
            if (player == null || player.getPlayerComposition() == null)
                return new AttackerEquipSnapshot(-1, -1);
            int weapon = -1;
            int torso = -1;
            try {
                weapon = player.getPlayerComposition().getEquipmentId(KitType.WEAPON);
                torso = player.getPlayerComposition().getEquipmentId(KitType.TORSO);
            } catch (Exception ignored) {}
            return new AttackerEquipSnapshot(weapon, torso);
        }

        @Override
        public boolean equals(Object obj) {
            if (!(obj instanceof AttackerEquipSnapshot)) return false;
            AttackerEquipSnapshot other = (AttackerEquipSnapshot) obj;
            return this.weaponId == other.weaponId && this.torsoId == other.torsoId;
        }

        @Override
        public int hashCode() {
            return Objects.hash(weaponId, torsoId);
        }
    }
}