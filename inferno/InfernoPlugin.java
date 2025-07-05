package net.runelite.client.plugins.inferno;

import com.google.inject.Provides;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.NPC;
import net.runelite.api.Prayer;
import net.runelite.api.ChatMessageType;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.events.*;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.EventBus;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.events.ConfigChanged;
import net.runelite.client.game.ItemManager;
import net.runelite.client.game.NPCManager;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDependency;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.plugins.inferno.displaymodes.InfernoPrayerDisplayMode;
import net.runelite.client.plugins.inferno.displaymodes.InfernoSafespotDisplayMode;
import net.runelite.client.plugins.inferno.displaymodes.InfernoWaveDisplayMode;
import net.runelite.client.plugins.inferno.displaymodes.InfernoZukShieldDisplayMode;
import net.runelite.client.plugins.PrayAgainstPlayer.RecommendedPrayerChangedEvent;
import net.runelite.client.ui.overlay.OverlayManager;
import net.runelite.client.ui.overlay.infobox.InfoBoxManager;
import net.runelite.client.plugins.kotoriutils.methods.NPCInteractions;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.ArrayUtils;

import javax.inject.Inject;
import java.util.*;

@PluginDependency(net.runelite.client.plugins.kotoriutils.KotoriUtils.class)
@PluginDescriptor(
		name = "Inferno",
		enabledByDefault = false,
		description = "Inferno helper with prayer automation integration",
		tags = {"combat", "overlay", "pve", "pvm", "ported", "kotori", "prayer", "automation"}
)
@Slf4j
public class InfernoPlugin extends Plugin
{
	private static final int INFERNO_REGION = 9043;

	@Inject
	private Client client;
	@Inject
	private OverlayManager overlayManager;
	@Inject
	private InfoBoxManager infoBoxManager;
	@Inject
	private ItemManager itemManager;
	@Inject
	private NPCManager npcManager;
	@Inject
	private InfernoOverlay infernoOverlay;
	@Inject
	private InfernoWaveOverlay waveOverlay;
	@Inject
	private InfernoInfoBoxOverlay jadOverlay;
	@Inject
	private InfernoConfig config;
	@Inject
	private EventBus eventBus;

	@Getter(AccessLevel.PACKAGE)
	private InfernoConfig.FontStyle fontStyle = InfernoConfig.FontStyle.BOLD;
	@Getter(AccessLevel.PACKAGE)
	private int textSize = 32;

	private WorldPoint lastLocation = new WorldPoint(0, 0, 0);

	@Getter(AccessLevel.PACKAGE)
	private int currentWaveNumber;

	@Getter(AccessLevel.PACKAGE)
	private final List<InfernoNPC> infernoNpcs = new ArrayList<>();

	@Getter(AccessLevel.PACKAGE)
	private final Map<Integer, Map<InfernoNPC.Attack, Integer>> upcomingAttacks = new HashMap<>();
	@Getter(AccessLevel.PACKAGE)
	private InfernoNPC.Attack closestAttack = null;

	@Getter(AccessLevel.PACKAGE)
	private final List<WorldPoint> obstacles = new ArrayList<>();

	@Getter(AccessLevel.PACKAGE)
	private boolean finalPhase = false;
	private boolean finalPhaseTick = false;
	private int ticksSinceFinalPhase = 0;
	@Getter(AccessLevel.PACKAGE)
	private NPC zukShield = null;
	private NPC zuk = null;
	private WorldPoint zukShieldLastPosition = null;
	private WorldPoint zukShieldBase = null;
	private int zukShieldCornerTicks = -2;
	private int zukShieldNegativeXCoord = -1;
	private int zukShieldPositiveXCoord = -1;
	private int zukShieldLastNonZeroDelta = 0;
	private int zukShieldLastDelta = 0;
	private int zukShieldTicksLeftInCorner = -1;

	@Getter(AccessLevel.PACKAGE)
	private InfernoNPC centralNibbler = null;

	@Getter(AccessLevel.PACKAGE)
	private final Map<WorldPoint, Integer> safeSpotMap = new HashMap<>();
	@Getter(AccessLevel.PACKAGE)
	private final Map<Integer, List<WorldPoint>> safeSpotAreas = new HashMap<>();

	@Getter(AccessLevel.PACKAGE)
	List<InfernoBlobDeathSpot> blobDeathSpots = new ArrayList<>();

	@Getter(AccessLevel.PACKAGE)
	private long lastTick;

	private InfernoSpawnTimerInfobox spawnTimerInfoBox;
	private InfernoNPC.Attack lastRecommendedAttack = null;

	private InfernoNpcPrediction npcPrediction;
	private InfernoPrayerRecommendation prayerRecommendation;

	public static final int JAL_NIB = 7574;
	public static final int JAL_MEJRAH = 7578;
	public static final int JAL_MEJRAH_STAND = 7577;
	public static final int JAL_AK_RANGE_ATTACK = 7581;
	public static final int JAL_AK_MELEE_ATTACK = 7582;
	public static final int JAL_AK_MAGIC_ATTACK = 7583;
	public static final int JAL_IMKOT = 7597;
	public static final int JAL_XIL_MELEE_ATTACK = 7604;
	public static final int JAL_XIL_RANGE_ATTACK = 7605;
	public static final int JAL_ZEK_MAGE_ATTACK = 7610;
	public static final int JAL_ZEK_MELEE_ATTACK = 7612;
	public static final int JALTOK_JAD_MAGE_ATTACK = 7592;
	public static final int JALTOK_JAD_RANGE_ATTACK = 7593;
	public static final int TZKAL_ZUK = 7566;

	@Provides
	public InfernoConfig provideConfig(ConfigManager configManager)
	{
		return configManager.getConfig(InfernoConfig.class);
	}

	@Override
	protected void startUp()
	{
		waveOverlay.setDisplayMode(config.waveDisplay());
		waveOverlay.setWaveHeaderColor(config.getWaveOverlayHeaderColor());
		waveOverlay.setWaveTextColor(config.getWaveTextColor());

		npcPrediction = new InfernoNpcPrediction();
		prayerRecommendation = new InfernoPrayerRecommendation(npcPrediction);

		if (client.getGameState() != GameState.LOGGED_IN || !isInInferno())
		{
			return;
		}

		init();
	}

	@Override
	protected void shutDown()
	{
		overlayManager.remove(infernoOverlay);
		overlayManager.remove(waveOverlay);
		overlayManager.remove(jadOverlay);

		if (spawnTimerInfoBox != null)
		{
			infoBoxManager.removeInfoBox(spawnTimerInfoBox);
		}
		spawnTimerInfoBox = null;

		infernoNpcs.clear();
		upcomingAttacks.clear();
		obstacles.clear();
		safeSpotMap.clear();
		safeSpotAreas.clear();
		blobDeathSpots.clear();

		currentWaveNumber = -1;
		zuk = null;
		zukShield = null;
		centralNibbler = null;
		zukShieldLastPosition = null;
		zukShieldBase = null;
		closestAttack = null;
		lastRecommendedAttack = null;

		postPrayerRecommendation(null);
	}

	private void init()
	{
		overlayManager.add(infernoOverlay);
		overlayManager.add(jadOverlay);

		if (config.waveDisplay() != InfernoWaveDisplayMode.NONE)
		{
			overlayManager.add(waveOverlay);
		}
	}

	@Subscribe
	private void onConfigChanged(ConfigChanged event)
	{
		if (!"inferno".equals(event.getGroup()))
		{
			return;
		}

		if (event.getKey().endsWith("color"))
		{
			waveOverlay.setWaveHeaderColor(config.getWaveOverlayHeaderColor());
			waveOverlay.setWaveTextColor(config.getWaveTextColor());
		}
		else if ("waveDisplay".equals(event.getKey()))
		{
			overlayManager.remove(waveOverlay);

			waveOverlay.setDisplayMode(config.waveDisplay());

			if (isInInferno() && config.waveDisplay() != InfernoWaveDisplayMode.NONE)
			{
				overlayManager.add(waveOverlay);
			}
		}
	}

	@Subscribe
	private void onGameTick(GameTick event)
	{
		if (!isInInferno())
		{
			if (lastRecommendedAttack != null)
			{
				postPrayerRecommendation(null);
				lastRecommendedAttack = null;
			}
			return;
		}

		lastTick = System.currentTimeMillis();

		WorldPoint playerLoc = client.getLocalPlayer().getWorldLocation();
		npcPrediction.onGameTick(infernoNpcs, client, playerLoc);
		Prayer advancedRecommendedPrayer = prayerRecommendation.recommendPrayer(
				infernoNpcs, client, playerLoc, safeSpotMap
		);
		if (!Objects.equals(advancedRecommendedPrayer, attackToPrayer(closestAttack)))
		{
			postPrayerRecommendation(advancedRecommendedPrayer);
			closestAttack = advancedRecommendedPrayer == null ? null : inferAttackFromPrayer(advancedRecommendedPrayer);
			lastRecommendedAttack = closestAttack;
		}

		upcomingAttacks.clear();
		calculateUpcomingAttacks();

		closestAttack = null;
		calculateClosestAttack();

		safeSpotMap.clear();
		calculateSafespots();

		safeSpotAreas.clear();
		calculateSafespotAreas();

		obstacles.clear();
		calculateObstacles();

		centralNibbler = null;
		calculateCentralNibbler();

		calculateSpawnTimerInfobox();

		manageBlobDeathLocations();

		if (finalPhaseTick)
		{
			finalPhaseTick = false;
		}
		else if (finalPhase)
		{
			ticksSinceFinalPhase++;
		}
	}

	private InfernoNPC.Attack inferAttackFromPrayer(Prayer prayer)
	{
		if (prayer == null) return null;
		switch(prayer)
		{
			case PROTECT_FROM_MAGIC: return InfernoNPC.Attack.MAGIC;
			case PROTECT_FROM_MISSILES: return InfernoNPC.Attack.RANGED;
			case PROTECT_FROM_MELEE: return InfernoNPC.Attack.MELEE;
			default: return null;
		}
	}

	private Prayer attackToPrayer(InfernoNPC.Attack attack)
	{
		if (attack == null) return null;
		switch (attack)
		{
			case MAGIC: return Prayer.PROTECT_FROM_MAGIC;
			case RANGED: return Prayer.PROTECT_FROM_MISSILES;
			case MELEE: return Prayer.PROTECT_FROM_MELEE;
			default: return null;
		}
	}

	private void postPrayerRecommendation(Prayer prayer)
	{
		String prayerName = prayer != null ? prayer.name().toLowerCase() : null;
		RecommendedPrayerChangedEvent event = new RecommendedPrayerChangedEvent(prayerName);
		eventBus.post(event);
	}

	@Subscribe
	private void onNpcSpawned(NpcSpawned event)
	{
		if (!isInInferno())
		{
			return;
		}

		final int npcId = event.getNpc().getId();

		if (npcId == net.runelite.api.NpcID.ANCESTRAL_GLYPH)
		{
			zukShield = event.getNpc();
			return;
		}

		final InfernoNPC.Type infernoNPCType = InfernoNPC.Type.typeFromId(npcId);

		if (infernoNPCType == null)
		{
			return;
		}

		switch (infernoNPCType)
		{
			case BLOB:
				infernoNpcs.add(new InfernoNPC(event.getNpc()));
				return;
			case MAGE:
				if (zuk != null && spawnTimerInfoBox != null)
				{
					spawnTimerInfoBox.reset();
					spawnTimerInfoBox.run();
				}
				break;
			case ZUK:
				finalPhase = false;
				zukShieldCornerTicks = -2;
				zukShieldLastPosition = null;
				zukShieldBase = null;
				log.debug("[INFERNO] Zuk spawn detected, not in final phase");

				if (config.spawnTimerInfobox())
				{
					zuk = event.getNpc();

					if (spawnTimerInfoBox != null)
					{
						infoBoxManager.removeInfoBox(spawnTimerInfoBox);
					}

					spawnTimerInfoBox = new InfernoSpawnTimerInfobox(itemManager.getImage(net.runelite.api.ItemID.TZREKZUK), this);
					infoBoxManager.addInfoBox(spawnTimerInfoBox);
				}
				break;
			case HEALER_ZUK:
				finalPhase = true;
				ticksSinceFinalPhase = 1;
				finalPhaseTick = true;
				for (InfernoNPC infernoNPC : infernoNpcs)
				{
					if (infernoNPC.getType() == InfernoNPC.Type.ZUK)
					{
						infernoNPC.setTicksTillNextAttack(-1);
					}
				}
				log.debug("[INFERNO] Final phase detected!");
				break;
		}

		infernoNpcs.add(0, new InfernoNPC(event.getNpc()));
	}

	@Subscribe
	private void onNpcDespawned(NpcDespawned event)
	{
		if (!isInInferno())
		{
			return;
		}

		int npcId = event.getNpc().getId();

		switch (npcId)
		{
			case net.runelite.api.NpcID.ANCESTRAL_GLYPH:
				zukShield = null;
				return;
			case net.runelite.api.NpcID.TZKALZUK:
				zuk = null;

				if (spawnTimerInfoBox != null)
				{
					infoBoxManager.removeInfoBox(spawnTimerInfoBox);
				}

				spawnTimerInfoBox = null;
				break;
			default:
				break;
		}

		infernoNpcs.removeIf(infernoNPC -> infernoNPC.getNpc() == event.getNpc());
	}

	@Subscribe
	private void onAnimationChanged(AnimationChanged event)
	{
		if (client.getGameState() != GameState.LOGGED_IN && !isInInferno())
		{
			return;
		}

		if (event.getActor() instanceof NPC)
		{
			final NPC npc = (NPC) event.getActor();
			int animationId = net.runelite.client.plugins.kotoriutils.ReflectionLibrary.getNpcAnimationId(npc);

			if (ArrayUtils.contains(InfernoNPC.Type.NIBBLER.getNpcIds(), npc.getId())
					&& animationId == 7576)
			{
				infernoNpcs.removeIf(infernoNPC -> infernoNPC.getNpc() == npc);
			}

			if (config.indicateBlobDeathLocation() && InfernoNPC.Type.typeFromId(npc.getId()) == InfernoNPC.Type.BLOB && animationId == InfernoBlobDeathSpot.BLOB_DEATH_ANIMATION)
			{
				infernoNpcs.removeIf(infernoNPC -> infernoNPC.getNpc() == npc);
				blobDeathSpots.add(new InfernoBlobDeathSpot(npc.getLocalLocation()));
			}
		}
	}

	@Subscribe
	private void onGameStateChanged(GameStateChanged event)
	{
		if (event.getGameState() != GameState.LOGGED_IN)
		{
			return;
		}

		if (!isInInferno())
		{
			shutDown();
		}
		else if (currentWaveNumber == -1)
		{
			init();
			infernoNpcs.clear();
			currentWaveNumber = 1;
		}
	}

	@Subscribe
	private void onChatMessage(net.runelite.api.events.ChatMessage event)
	{
		if (!isInInferno() || event.getType() != ChatMessageType.GAMEMESSAGE)
		{
			return;
		}

		String message = event.getMessage();

		if (event.getMessage().contains("Wave:"))
		{
			message = message.substring(message.indexOf(": ") + 2);
			currentWaveNumber = Integer.parseInt(message.substring(0, message.indexOf('<')));
		}
	}

	public boolean isInInferno()
	{
		return net.runelite.client.plugins.kotoriutils.methods.MiscUtilities.getPlayerRegionID() == INFERNO_REGION;
	}

	public List<InfernoNPC> getInfernoNpcs()
	{
		return infernoNpcs;
	}

	public Map<WorldPoint, Integer> getSafeSpotMap()
	{
		return safeSpotMap;
	}

	public InfernoNPC getCentralNibbler()
	{
		return centralNibbler;
	}

	public int getCurrentWaveNumber()
	{
		return currentWaveNumber;
	}

	public int getNextWaveNumber()
	{
		return currentWaveNumber == -1 || currentWaveNumber == 69 ? -1 : currentWaveNumber + 1;
	}

	public InfernoNPC.Attack getClosestAttack()
	{
		return closestAttack;
	}

	private void calculateUpcomingAttacks()
	{
		for (InfernoNPC infernoNPC : infernoNpcs)
		{
			infernoNPC.gameTick(client, lastLocation, finalPhase, ticksSinceFinalPhase);

			if (infernoNPC.getType() == InfernoNPC.Type.ZUK && zukShieldCornerTicks == -1)
			{
				infernoNPC.updateNextAttack(InfernoNPC.Attack.UNKNOWN, 12);
				zukShieldCornerTicks = 0;
			}

			if (infernoNPC.getTicksTillNextAttack() > 0 && isPrayerHelper(infernoNPC)
					&& (infernoNPC.getNextAttack() != InfernoNPC.Attack.UNKNOWN
					|| (config.indicateBlobDetectionTick() && infernoNPC.getType() == InfernoNPC.Type.BLOB
					&& infernoNPC.getTicksTillNextAttack() >= 4)))
			{
				upcomingAttacks.computeIfAbsent(infernoNPC.getTicksTillNextAttack(), k -> new HashMap<>());

				if (config.indicateBlobDetectionTick() && infernoNPC.getType() == InfernoNPC.Type.BLOB
						&& infernoNPC.getTicksTillNextAttack() >= 4)
				{
					upcomingAttacks.computeIfAbsent(infernoNPC.getTicksTillNextAttack() - 3, k -> new HashMap<>());
					upcomingAttacks.computeIfAbsent(infernoNPC.getTicksTillNextAttack() - 4, k -> new HashMap<>());

					if (upcomingAttacks.get(infernoNPC.getTicksTillNextAttack() - 3).containsKey(InfernoNPC.Attack.MAGIC))
					{
						if (upcomingAttacks.get(infernoNPC.getTicksTillNextAttack() - 3).get(InfernoNPC.Attack.MAGIC) > InfernoNPC.Type.BLOB.getPriority())
						{
							upcomingAttacks.get(infernoNPC.getTicksTillNextAttack() - 3).put(InfernoNPC.Attack.MAGIC, InfernoNPC.Type.BLOB.getPriority());
						}
					}
					else if (upcomingAttacks.get(infernoNPC.getTicksTillNextAttack() - 3).containsKey(InfernoNPC.Attack.RANGED))
					{
						if (upcomingAttacks.get(infernoNPC.getTicksTillNextAttack() - 3).get(InfernoNPC.Attack.RANGED) > InfernoNPC.Type.BLOB.getPriority())
						{
							upcomingAttacks.get(infernoNPC.getTicksTillNextAttack() - 3).put(InfernoNPC.Attack.RANGED, InfernoNPC.Type.BLOB.getPriority());
						}
					}
					else if (upcomingAttacks.get(infernoNPC.getTicksTillNextAttack()).containsKey(InfernoNPC.Attack.MAGIC)
							|| upcomingAttacks.get(infernoNPC.getTicksTillNextAttack() - 4).containsKey(InfernoNPC.Attack.MAGIC))
					{
						if (!upcomingAttacks.get(infernoNPC.getTicksTillNextAttack() - 3).containsKey(InfernoNPC.Attack.RANGED)
								|| upcomingAttacks.get(infernoNPC.getTicksTillNextAttack() - 3).get(InfernoNPC.Attack.RANGED) > InfernoNPC.Type.BLOB.getPriority())
						{
							upcomingAttacks.get(infernoNPC.getTicksTillNextAttack() - 3).put(InfernoNPC.Attack.RANGED, InfernoNPC.Type.BLOB.getPriority());
						}
					}
					else if (upcomingAttacks.get(infernoNPC.getTicksTillNextAttack()).containsKey(InfernoNPC.Attack.RANGED)
							|| upcomingAttacks.get(infernoNPC.getTicksTillNextAttack() - 4).containsKey(InfernoNPC.Attack.RANGED))
					{
						if (!upcomingAttacks.get(infernoNPC.getTicksTillNextAttack() - 3).containsKey(InfernoNPC.Attack.MAGIC)
								|| upcomingAttacks.get(infernoNPC.getTicksTillNextAttack() - 3).get(InfernoNPC.Attack.MAGIC) > InfernoNPC.Type.BLOB.getPriority())
						{
							upcomingAttacks.get(infernoNPC.getTicksTillNextAttack() - 3).put(InfernoNPC.Attack.MAGIC, InfernoNPC.Type.BLOB.getPriority());
						}
					}
					else
					{
						upcomingAttacks.get(infernoNPC.getTicksTillNextAttack() - 3).put(InfernoNPC.Attack.MAGIC, InfernoNPC.Type.BLOB.getPriority());
					}
				}
				else
				{
					final InfernoNPC.Attack attack = infernoNPC.getNextAttack();
					final int priority = infernoNPC.getType().getPriority();

					if (!upcomingAttacks.get(infernoNPC.getTicksTillNextAttack()).containsKey(attack)
							|| upcomingAttacks.get(infernoNPC.getTicksTillNextAttack()).get(attack) > priority)
					{
						upcomingAttacks.get(infernoNPC.getTicksTillNextAttack()).put(attack, priority);
					}
				}
			}
		}
	}

	private void calculateClosestAttack()
	{
		if (config.prayerDisplayMode() == InfernoPrayerDisplayMode.PRAYER_TAB
				|| config.prayerDisplayMode() == InfernoPrayerDisplayMode.BOTH)
		{
			int closestTick = 999;
			int closestPriority = 999;
			InfernoNPC.Attack previousClosestAttack = closestAttack;

			for (Integer tick : upcomingAttacks.keySet())
			{
				final Map<InfernoNPC.Attack, Integer> attackPriority = upcomingAttacks.get(tick);

				for (InfernoNPC.Attack currentAttack : attackPriority.keySet())
				{
					final int currentPriority = attackPriority.get(currentAttack);
					if (tick < closestTick || (tick == closestTick && currentPriority < closestPriority))
					{
						closestAttack = currentAttack;
						closestPriority = currentPriority;
						closestTick = tick;
					}
				}
			}

			if (closestAttack != previousClosestAttack)
			{
				Prayer recommendedPrayer = attackToPrayer(closestAttack);
				postPrayerRecommendation(recommendedPrayer);
				lastRecommendedAttack = closestAttack;
			}
		}
	}

	private void calculateSafespots()
	{
		if (currentWaveNumber < 69)
		{
			if (config.safespotDisplayMode() != InfernoSafespotDisplayMode.OFF)
			{
				int checkSize = (int) Math.floor(config.safespotsCheckSize() / 2.0);

				for (int x = -checkSize; x <= checkSize; x++)
				{
					for (int y = -checkSize; y <= checkSize; y++)
					{
						final WorldPoint checkLoc = client.getLocalPlayer().getWorldLocation().dx(x).dy(y);

						if (obstacles.contains(checkLoc))
						{
							continue;
						}

						for (InfernoNPC infernoNPC : infernoNpcs)
						{
							if (!isNormalSafespots(infernoNPC))
							{
								continue;
							}

							if (!safeSpotMap.containsKey(checkLoc))
							{
								safeSpotMap.put(checkLoc, 0);
							}

							if (infernoNPC.canAttack(client, checkLoc)
									|| infernoNPC.canMoveToAttack(client, checkLoc, obstacles))
							{
								if (infernoNPC.getType().getDefaultAttack() == InfernoNPC.Attack.MELEE)
								{
									if (safeSpotMap.get(checkLoc) == 0)
									{
										safeSpotMap.put(checkLoc, 1);
									}
									else if (safeSpotMap.get(checkLoc) == 2)
									{
										safeSpotMap.put(checkLoc, 4);
									}
									else if (safeSpotMap.get(checkLoc) == 3)
									{
										safeSpotMap.put(checkLoc, 5);
									}
									else if (safeSpotMap.get(checkLoc) == 6)
									{
										safeSpotMap.put(checkLoc, 7);
									}
								}

								if (infernoNPC.getType().getDefaultAttack() == InfernoNPC.Attack.MAGIC
										|| (infernoNPC.getType() == InfernoNPC.Type.BLOB
										&& safeSpotMap.get(checkLoc) != 2 && safeSpotMap.get(checkLoc) != 4))
								{
									if (safeSpotMap.get(checkLoc) == 0)
									{
										safeSpotMap.put(checkLoc, 3);
									}
									else if (safeSpotMap.get(checkLoc) == 1)
									{
										safeSpotMap.put(checkLoc, 5);
									}
									else if (safeSpotMap.get(checkLoc) == 2)
									{
										safeSpotMap.put(checkLoc, 6);
									}
									else if (safeSpotMap.get(checkLoc) == 5)
									{
										safeSpotMap.put(checkLoc, 7);
									}
								}

								if (infernoNPC.getType().getDefaultAttack() == InfernoNPC.Attack.RANGED
										|| (infernoNPC.getType() == InfernoNPC.Type.BLOB
										&& safeSpotMap.get(checkLoc) != 3 && safeSpotMap.get(checkLoc) != 5))
								{
									if (safeSpotMap.get(checkLoc) == 0)
									{
										safeSpotMap.put(checkLoc, 2);
									}
									else if (safeSpotMap.get(checkLoc) == 1)
									{
										safeSpotMap.put(checkLoc, 4);
									}
									else if (safeSpotMap.get(checkLoc) == 3)
									{
										safeSpotMap.put(checkLoc, 6);
									}
									else if (safeSpotMap.get(checkLoc) == 4)
									{
										safeSpotMap.put(checkLoc, 7);
									}
								}

								if (infernoNPC.getType() == InfernoNPC.Type.JAD
										&& infernoNPC.getNpc().getWorldArea().isInMeleeDistance(checkLoc))
								{
									if (safeSpotMap.get(checkLoc) == 0)
									{
										safeSpotMap.put(checkLoc, 1);
									}
									else if (safeSpotMap.get(checkLoc) == 2)
									{
										safeSpotMap.put(checkLoc, 4);
									}
									else if (safeSpotMap.get(checkLoc) == 3)
									{
										safeSpotMap.put(checkLoc, 5);
									}
									else if (safeSpotMap.get(checkLoc) == 6)
									{
										safeSpotMap.put(checkLoc, 7);
									}
								}
							}
						}
					}
				}
			}
		}
		else if (currentWaveNumber == 69 && zukShield != null)
		{
			final WorldPoint zukShieldCurrentPosition = zukShield.getWorldLocation();

			if (zukShieldLastPosition != null && zukShieldLastPosition.getX() != zukShieldCurrentPosition.getX() && zukShieldCornerTicks == -2)
			{
				zukShieldBase = zukShieldLastPosition;
				zukShieldCornerTicks = -1;
			}

			if (zukShieldLastPosition != null)
			{
				int zukShieldDelta = zukShieldCurrentPosition.getX() - zukShieldLastPosition.getX();

				if (zukShieldDelta != 0)
				{
					zukShieldLastNonZeroDelta = zukShieldDelta;
				}

				if (zukShieldLastDelta == 0 && zukShieldDelta != 0)
				{
					zukShieldTicksLeftInCorner = 4;
				}

				if (zukShieldDelta == 0)
				{
					if (zukShieldLastNonZeroDelta > 0)
					{
						zukShieldPositiveXCoord = zukShieldCurrentPosition.getX();
					}
					else if (zukShieldLastNonZeroDelta < 0)
					{
						zukShieldNegativeXCoord = zukShieldCurrentPosition.getX();
					}

					if (zukShieldTicksLeftInCorner > 0)
					{
						zukShieldTicksLeftInCorner--;
					}
				}

				zukShieldLastDelta = zukShieldDelta;
			}

			zukShieldLastPosition = zukShieldCurrentPosition;

			if (config.safespotDisplayMode() != InfernoSafespotDisplayMode.OFF)
			{
				if ((finalPhase && config.safespotsZukShieldAfterHealers() == InfernoZukShieldDisplayMode.LIVE)
						|| (!finalPhase && config.safespotsZukShieldBeforeHealers() == InfernoZukShieldDisplayMode.LIVE))
				{
					drawZukSafespot(zukShield.getWorldLocation().getX(), zukShield.getWorldLocation().getY(), 0);
				}

				if ((finalPhase && config.safespotsZukShieldAfterHealers() == InfernoZukShieldDisplayMode.LIVEPLUSPREDICT)
						|| (!finalPhase && config.safespotsZukShieldBeforeHealers() == InfernoZukShieldDisplayMode.LIVEPLUSPREDICT))
				{
					drawZukSafespot(zukShield.getWorldLocation().getX(), zukShield.getWorldLocation().getY(), 0);

					drawZukPredictedSafespot();
				}
				else if ((finalPhase && config.safespotsZukShieldAfterHealers() == InfernoZukShieldDisplayMode.PREDICT)
						|| (!finalPhase && config.safespotsZukShieldBeforeHealers() == InfernoZukShieldDisplayMode.PREDICT))
				{
					drawZukPredictedSafespot();
				}
			}
		}
	}

	private void drawZukPredictedSafespot()
	{
		final WorldPoint zukShieldCurrentPosition = zukShield.getWorldLocation();
		if (zukShieldPositiveXCoord != -1 && zukShieldNegativeXCoord != -1)
		{
			int nextShieldXCoord = zukShieldCurrentPosition.getX();

			for (InfernoNPC infernoNPC : infernoNpcs)
			{
				if (infernoNPC.getType() == InfernoNPC.Type.ZUK)
				{
					int ticksTilZukAttack = finalPhase ? infernoNPC.getTicksTillNextAttack() : infernoNPC.getTicksTillNextAttack() - 1;

					if (ticksTilZukAttack < 1)
					{
						if (finalPhase)
						{
							return;
						}
						else
						{
							ticksTilZukAttack = 10;
						}
					}

					if (zukShieldLastNonZeroDelta > 0)
					{
						nextShieldXCoord += ticksTilZukAttack;

						if (nextShieldXCoord > zukShieldPositiveXCoord)
						{
							nextShieldXCoord -= zukShieldTicksLeftInCorner;

							if (nextShieldXCoord <= zukShieldPositiveXCoord)
							{
								nextShieldXCoord = zukShieldPositiveXCoord;
							}
							else
							{
								nextShieldXCoord = zukShieldPositiveXCoord - nextShieldXCoord + zukShieldPositiveXCoord;
							}
						}
					}
					else
					{
						nextShieldXCoord -= ticksTilZukAttack;

						if (nextShieldXCoord < zukShieldNegativeXCoord)
						{
							nextShieldXCoord += zukShieldTicksLeftInCorner;

							if (nextShieldXCoord >= zukShieldNegativeXCoord)
							{
								nextShieldXCoord = zukShieldNegativeXCoord;
							}
							else
							{
								nextShieldXCoord = zukShieldNegativeXCoord - nextShieldXCoord + zukShieldNegativeXCoord;
							}
						}
					}
				}
			}

			drawZukSafespot(nextShieldXCoord, zukShield.getWorldLocation().getY(), 2);
		}
	}

	private void drawZukSafespot(int xCoord, int yCoord, int colorSafeSpotId)
	{
		for (int x = xCoord - 1; x <= xCoord + 3; x++)
		{
			for (int y = yCoord - 4; y <= yCoord - 2; y++)
			{
				safeSpotMap.put(new WorldPoint(x, y, client.getTopLevelWorldView().getPlane()), colorSafeSpotId);
			}
		}
	}

	private void calculateSafespotAreas()
	{
		if (config.safespotDisplayMode() == InfernoSafespotDisplayMode.AREA)
		{
			for (WorldPoint worldPoint : safeSpotMap.keySet())
			{
				if (!safeSpotAreas.containsKey(safeSpotMap.get(worldPoint)))
				{
					safeSpotAreas.put(safeSpotMap.get(worldPoint), new ArrayList<>());
				}

				safeSpotAreas.get(safeSpotMap.get(worldPoint)).add(worldPoint);
			}
		}

		lastLocation = client.getLocalPlayer().getWorldLocation();
	}

	private void calculateObstacles()
	{
		for (NPC npc : NPCInteractions.getNpcs())
		{
			obstacles.addAll(npc.getWorldArea().toWorldPointList());
		}
	}

	private void manageBlobDeathLocations()
	{
		if (config.indicateBlobDeathLocation())
		{
			blobDeathSpots.forEach(InfernoBlobDeathSpot::decrementTick);
			blobDeathSpots.removeIf(InfernoBlobDeathSpot::isDone);
		}
	}

	private void calculateCentralNibbler()
	{
		InfernoNPC bestNibbler = null;
		int bestAmountInArea = 0;
		int bestDistanceToPlayer = 999;

		for (InfernoNPC infernoNPC : infernoNpcs)
		{
			if (infernoNPC.getType() != InfernoNPC.Type.NIBBLER)
			{
				continue;
			}

			int amountInArea = 0;
			final int distanceToPlayer = infernoNPC.getNpc().getWorldLocation().distanceTo(client.getLocalPlayer().getWorldLocation());

			for (InfernoNPC checkNpc : infernoNpcs)
			{
				if (checkNpc.getType() != InfernoNPC.Type.NIBBLER
						|| checkNpc.getNpc().getWorldArea().distanceTo(infernoNPC.getNpc().getWorldArea()) > 1)
				{
					continue;
				}

				amountInArea++;
			}

			if (amountInArea > bestAmountInArea
					|| (amountInArea == bestAmountInArea && distanceToPlayer < bestDistanceToPlayer))
			{
				bestNibbler = infernoNPC;
				bestAmountInArea = amountInArea;
				bestDistanceToPlayer = distanceToPlayer;
			}
		}

		if (bestNibbler != null)
		{
			centralNibbler = bestNibbler;
		}
	}

	private void calculateSpawnTimerInfobox()
	{
		if (zuk == null || finalPhase || spawnTimerInfoBox == null)
		{
			return;
		}

		final int pauseHp = 600;
		final int resumeHp = 480;

		Integer zulHealth = npcManager.getHealth(zuk.getId());
		if (zulHealth == null)
		{
			return;
		}
		int hp = calculateNpcHp(zuk.getHealthRatio(), zuk.getHealthScale(), zulHealth);

		if (hp <= 0)
		{
			return;
		}

		if (spawnTimerInfoBox.isRunning())
		{
			if (hp >= resumeHp && hp < pauseHp)
			{
				spawnTimerInfoBox.pause();
			}
		}
		else
		{
			if (hp < resumeHp)
			{
				spawnTimerInfoBox.run();
			}
		}
	}

	private static int calculateNpcHp(int ratio, int health, int maxHp)
	{
		if (ratio < 0 || health <= 0 || maxHp == -1)
		{
			return -1;
		}

		int exactHealth = 0;

		if (ratio > 0)
		{
			int minHealth = 1;
			int maxHealth;

			if (health > 1)
			{
				if (ratio > 1)
				{
					minHealth = (maxHp * (ratio - 1) + health - 2) / (health - 1);
				}

				maxHealth = (maxHp * ratio - 1) / (health - 1);

				if (maxHealth > maxHp)
				{
					maxHealth = maxHp;
				}
			}
			else
			{
				maxHealth = maxHp;
			}

			exactHealth = (minHealth + maxHealth + 1) / 2;
		}

		return exactHealth;
	}

	private boolean isPrayerHelper(InfernoNPC infernoNPC)
	{
		switch (infernoNPC.getType())
		{
			case BAT:
				return config.prayerBat();
			case BLOB:
				return config.prayerBlob();
			case MELEE:
				return config.prayerMeleer();
			case RANGER:
				return config.prayerRanger();
			case MAGE:
				return config.prayerMage();
			case HEALER_JAD:
				return config.prayerHealerJad();
			case JAD:
				return config.prayerJad();
			default:
				return false;
		}
	}

	boolean isTicksOnNpc(InfernoNPC infernoNPC)
	{
		switch (infernoNPC.getType())
		{
			case BAT:
				return config.ticksOnNpcBat();
			case BLOB:
				return config.ticksOnNpcBlob();
			case MELEE:
				return config.ticksOnNpcMeleer();
			case RANGER:
				return config.ticksOnNpcRanger();
			case MAGE:
				return config.ticksOnNpcMage();
			case HEALER_JAD:
				return config.ticksOnNpcHealerJad();
			case JAD:
				return config.ticksOnNpcJad();
			case ZUK:
				return config.ticksOnNpcZuk();
			default:
				return false;
		}
	}

	boolean isNormalSafespots(InfernoNPC infernoNPC)
	{
		switch (infernoNPC.getType())
		{
			case BAT:
				return config.safespotsBat();
			case BLOB:
				return config.safespotsBlob();
			case MELEE:
				return config.safespotsMeleer();
			case RANGER:
				return config.safespotsRanger();
			case MAGE:
				return config.safespotsMage();
			case HEALER_JAD:
				return config.safespotsHealerJad();
			case JAD:
				return config.safespotsJad();
			default:
				return false;
		}
	}

	boolean isIndicateNpcPosition(InfernoNPC infernoNPC)
	{
		switch (infernoNPC.getType())
		{
			case BAT:
				return config.indicateNpcPositionBat();
			case BLOB:
				return config.indicateNpcPositionBlob();
			case MELEE:
				return config.indicateNpcPositionMeleer();
			case RANGER:
				return config.indicateNpcPositionRanger();
			case MAGE:
				return config.indicateNpcPositionMage();
			default:
				return false;
		}
	}

	public InfernoNpcPrediction getNpcPredictionEngine() { return npcPrediction; }
	public InfernoPrayerRecommendation getPrayerRecommendationEngine() { return prayerRecommendation; }

	public boolean isInTrueSafespot()
	{
		if (client.getLocalPlayer() == null) return false;
		return prayerRecommendation.isInTrueSafespot(client.getLocalPlayer().getWorldLocation(), safeSpotMap);
	}

	public boolean isFlickPossible()
	{
		WorldPoint playerLoc = client.getLocalPlayer() != null ? client.getLocalPlayer().getWorldLocation() : null;
		java.util.Set<InfernoNPC.Attack> incoming = prayerRecommendation.getIncomingAttacks(infernoNpcs, client, playerLoc);
		return incoming.size() == 1 && !hasActiveBlob();
	}

	private boolean hasActiveBlob()
	{
		for (InfernoNPC npc : infernoNpcs)
			if (npc.getType() == InfernoNPC.Type.BLOB)
				return true;
		return false;
	}
}