/*
 * Copyright (c) 2018, Adam <Adam@sigterm.info>
 * Copyright (c) 2018, Abexlry <abexlry@gmail.com>
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 *
 * 1. Redistributions of source code must retain the above copyright notice, this
 *    list of conditions and the following disclaimer.
 * 2. Redistributions in binary form must reproduce the above copyright notice,
 *    this list of conditions and the following disclaimer in the documentation
 *    and/or other materials provided with the distribution.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND
 * ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED
 * WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
 * DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT OWNER OR CONTRIBUTORS BE LIABLE FOR
 * ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES
 * (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES;
 * LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND
 * ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
 * (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS
 * SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */
package net.runelite.client.plugins.killswitch;

import com.google.common.base.CharMatcher;
import com.google.common.base.Splitter;
import com.google.inject.Provides;
import lombok.Getter;
import net.runelite.api.*;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.events.ChatMessage;
import net.runelite.api.events.GameTick;
import net.runelite.api.events.StatChanged;
import net.runelite.client.Notifier;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.events.ConfigChanged;
import net.runelite.client.game.ItemManager;
import net.runelite.client.input.KeyManager;
import net.runelite.client.input.MouseManager;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.ui.overlay.infobox.InfoBoxManager;
import net.runelite.client.ui.overlay.infobox.Timer;
import net.runelite.client.util.Text;

import javax.inject.Inject;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

import static net.runelite.api.AnimationID.IDLE;

@PluginDescriptor(
	name = "Kill Switch",
	description = "Allows use of WASD keys for camera movement with 'Press Enter to Chat', and remapping number keys to F-keys",
	tags = {"ahk", "ghost", "mouse","nomscripts"},
	enabledByDefault = false
)
public class KillSwitchPlugin extends Plugin
{
	@Inject
	private Client client;

	@Inject
	private ClientThread clientThread;

	@Inject
	private KeyManager keyManager;
	@Inject
	private MouseManager mouseManager;

	@Inject
	private KillSwitchListener inputListener;

	@Inject
	private Notifier notifier;

	@Inject
	private KillSwitchConfig config;

	@Inject
	private InfoBoxManager infoBoxManager;
	@Inject
	private ItemManager itemManager;

	@Override
	protected void startUp() throws Exception
	{
		keyManager.registerKeyListener(inputListener);
		mouseManager.registerMouseListener(inputListener);
		updateFilteredPatterns();
	}

	@Override
	protected void shutDown() throws Exception
	{

		keyManager.unregisterKeyListener(inputListener);
		mouseManager.unregisterMouseListener(inputListener);
	}

	@Provides
	KillSwitchConfig getConfig(ConfigManager configManager)
	{
		return configManager.getConfig(KillSwitchConfig.class);
	}


	@Subscribe
	public void onConfigChanged(ConfigChanged event)
	{
		if (event.getGroup().equals("killswitch"))
		{
			resetTimers();
			if (!config.killSwitchActive()) stopInput = false;
			updateFilteredPatterns();
		}
	}

	private Instant lastAnimating;
	private Instant lastInteracting;
	private Instant lastMoving;
	private Instant lastExperience;
	private Timer animatingTimer;
	private Timer interactingTimer;
	private Timer movingTimer;
	private Timer experienceTimer;
	private WorldPoint lastPosition;

	@Getter
	private boolean stopInput;

	@Subscribe
	public void onStatChanged(StatChanged statChanged)
	{
		if (!config.checkExperience())
		{
			return;
		}
		if (experienceTimer != null) infoBoxManager.removeInfoBox(experienceTimer);
		lastExperience = Instant.now();
		experienceTimer = new Timer(config.checkExperienceTime(), ChronoUnit.MILLIS, itemManager.getImage(ItemID.LAMP_OF_KNOWLEDGE),this);
		infoBoxManager.addInfoBox(experienceTimer);
	}

	@Subscribe
	public void onGameTick(GameTick event)
	{
		final Player local = client.getLocalPlayer();

		if (client.getGameState() != GameState.LOGGED_IN
			|| local == null ||
			!config.killSwitchActive() ||
			stopInput)
		{
			resetTimers();
			return;
		}

		if (config.checkExperience() && checkExperienceIdle(Duration.ofMillis(config.checkExperienceTime())))
		{
			stopInput = true;
			System.out.println("[" + local.getName() + "] is about to log out from idling too long!");
		}

		if (config.checkAnimation() && checkAnimationIdle(Duration.ofMillis(config.checkAnimationTime()), local))
		{
			stopInput = true;
			System.out.println("[" + local.getName() + "] is now idle!");
		}

		if (config.checkMovement() && checkMovementIdle(Duration.ofMillis(config.checkMovementTime()), local))
		{
			stopInput = true;
			System.out.println("[" + local.getName() + "] has stopped moving!");
		}
		if (config.checkStationary() && !checkIsStationary(local))
		{
			stopInput = true;
			System.out.println("[" + local.getName() + "] has started moving!");
		}

		if (config.checkInteraction() && checkInteractionIdle(Duration.ofMillis(config.checkInteractionTime()), local))
		{
			stopInput = true;
			System.out.println("[" + local.getName() + "] is now out of combat!");
		}

//		if (checkLowHitpoints())
//		{
//			stopInput = true;
//			System.out.println("[" + local.getName() + "] has low hitpoints!");
//		}
//
//		if (checkLowPrayer())
//		{
//			stopInput = true;
//			System.out.println("[" + local.getName() + "] has low prayer!");
//		}
	}


//	private boolean checkLowHitpoints()
//	{
//		if (config.getHitpointsThreshold() == 0)
//		{
//			return false;
//		}
//		if (client.getRealSkillLevel(Skill.HITPOINTS) > config.getHitpointsThreshold())
//		{
//			if (client.getBoostedSkillLevel(Skill.HITPOINTS) + client.getVar(Varbits.NMZ_ABSORPTION) <= config.getHitpointsThreshold())
//					return true;
//		}
//
//		return false;
//	}
//
//	private boolean checkLowPrayer()
//	{
//		if (config.getPrayerThreshold() == 0)
//		{
//			return false;
//		}
//		if (client.getRealSkillLevel(Skill.PRAYER) > config.getPrayerThreshold())
//		{
//			if (client.getBoostedSkillLevel(Skill.PRAYER) <= config.getPrayerThreshold())
//				return true;
//		}
//
//		return false;
//	}

	private boolean checkInteractionIdle(Duration waitDuration, Player local)
	{
		if (lastInteracting == null)
		{
			lastInteracting = Instant.now();
			return false;
		}
		final Actor interact = local.getInteracting();

		if (interact == null)
		{
			if (Instant.now().compareTo(lastInteracting.plus(waitDuration)) >= 0)
			{
				lastInteracting = null;
				return true;
			}
		}
		else
		{
			if (interactingTimer != null) infoBoxManager.removeInfoBox(interactingTimer);
			lastInteracting = Instant.now();
			interactingTimer = new Timer(config.checkInteractionTime(), ChronoUnit.MILLIS,itemManager.getImage(ItemID.CHRISTMAS_CRACKER),this);
			infoBoxManager.addInfoBox(interactingTimer);
		}

		return false;
	}

	private boolean checkAnimationIdle(Duration waitDuration, Player local)
	{
		if (lastAnimating == null)
		{
			lastAnimating = Instant.now();
			return false;
		}

		final int animation = local.getAnimation();

		if (animation == IDLE)
		{
			if (Instant.now().compareTo(lastAnimating.plus(waitDuration)) >= 0)
			{
				lastAnimating = null;
				return true;
			}
		}
		else
		{
			if (animatingTimer != null) infoBoxManager.removeInfoBox(animatingTimer);
			lastAnimating = Instant.now();
			animatingTimer = new Timer(config.checkAnimationTime(), ChronoUnit.MILLIS, itemManager.getImage(ItemID.FIYR_REMAINS),this);
			infoBoxManager.addInfoBox(animatingTimer);
		}

		return false;
	}

	private boolean checkMovementIdle(Duration waitDuration, Player local)
	{
		if (lastPosition == null || lastMoving == null)
		{
			lastPosition = local.getWorldLocation();
			lastMoving = Instant.now();
			return false;
		}

		WorldPoint position = local.getWorldLocation();

		if (lastPosition.equals(position))
		{
			return Instant.now().compareTo(lastMoving.plus(waitDuration)) >= 0;
		}
		else
		{
			lastPosition = position;
			lastMoving = Instant.now();

			if (movingTimer != null) infoBoxManager.removeInfoBox(movingTimer);
			lastMoving = Instant.now();
			movingTimer = new Timer(config.checkMovementTime(), ChronoUnit.MILLIS, itemManager.getImage(ItemID.FIYR_REMAINS),this);
			infoBoxManager.addInfoBox(movingTimer);
		}

		return false;
	}

	private boolean checkIsStationary(Player local)
	{
		if (lastPosition == null)
		{
			lastPosition = local.getWorldLocation();
			return true;
		}

		WorldPoint position = local.getWorldLocation();

		if (!lastPosition.equals(position))
		{
			return false;
		}


		return true;
	}

	private boolean checkExperienceIdle(Duration waitDuration)
	{
		if (lastExperience == null)
		{
			return false;
		}
		return Instant.now().compareTo(lastExperience.plus(waitDuration)) >= 0;
	}

	private void resetTimers()
	{
		final Player local = client.getLocalPlayer();
		if (local != null)
		lastPosition = local.getWorldLocation();
		// Reset animation idle timer
		lastAnimating = null;

		// Reset interaction idle timer
		lastInteracting = null;

		lastMoving = null;
		lastExperience = null;

		infoBoxManager.removeIf(ib -> true);
	}
	private static final Splitter NEWLINE_SPLITTER = Splitter
		.on("\n")
		.omitEmptyStrings()
		.trimResults();
	private final List<Pattern> filteredPatterns = new ArrayList<>();
	void updateFilteredPatterns()
	{
		filteredPatterns.clear();

		Text.fromCSV(config.filteredWords()).stream()
			.map(s -> Pattern.compile(Pattern.quote(s), Pattern.CASE_INSENSITIVE))
			.forEach(filteredPatterns::add);

		NEWLINE_SPLITTER.splitToList(config.filteredRegex()).stream()
			.map(s ->
			{
				try
				{
					return Pattern.compile(s, Pattern.CASE_INSENSITIVE);
				}
				catch (PatternSyntaxException ex)
				{
					return null;
				}
			})
			.filter(Objects::nonNull)
			.forEach(filteredPatterns::add);
	}

	@Subscribe
	public void onChatMessage(ChatMessage event)
	{
		if (event.getType() == ChatMessageType.GAMEMESSAGE)
		{
			if (censorMessage(event.getMessage()))
			{
				stopInput = true;
				System.out.println("Message has been detected: " + event.getMessage());
			}
		}
	}
	private final CharMatcher jagexPrintableCharMatcher = Text.JAGEX_PRINTABLE_CHAR_MATCHER;
	boolean censorMessage(final String message)
	{
		String strippedMessage = jagexPrintableCharMatcher.retainFrom(message)
			.replace('\u00A0', ' ');
		boolean filtered = false;
		for (Pattern pattern : filteredPatterns)
		{
			Matcher m = pattern.matcher(strippedMessage);
			if (m.matches())
			{
				return true;
			}
		}

		return false;
	}
}
