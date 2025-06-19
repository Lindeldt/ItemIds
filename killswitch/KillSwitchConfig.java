/*
 * Copyright (c) 2018, Adam <Adam@sigterm.info>
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

import net.runelite.client.config.Config;
import net.runelite.client.config.ConfigGroup;
import net.runelite.client.config.ConfigItem;

@ConfigGroup("killswitch")
public interface KillSwitchConfig extends Config
{
	@ConfigItem(
		position = 1,
		keyName = "killSwitchActive",
		name = "Activate kill switch",
		description = "aa"
	)
	default boolean killSwitchActive()
	{
		return false;
	}

	@ConfigItem(
		keyName = "checkAnimation",
		name = "Check animation",
		description = "aa",
		position = 2
	)
	default boolean checkAnimation()
	{
		return true;
	}

	@ConfigItem(
		keyName = "checkAnimationTime",
		name = "Check animation time (ms)",
		description = "The notification delay after the player is idle",
		position = 3
	)
	default int checkAnimationTime()
	{
		return 5000;
	}

	@ConfigItem(
		keyName = "checkExperience",
		name = "Check experience",
		description = "aa",
		position = 4
	)
	default boolean checkExperience()
	{
		return true;
	}

	@ConfigItem(
		keyName = "checkExperienceTime",
		name = "Check experience time (ms)",
		description = "The notification delay after the player is idle",
		position = 5
	)
	default int checkExperienceTime()
	{
		return 5000;
	}

	@ConfigItem(
		keyName = "checkMovement",
		name = "Check movement",
		description = "aa",
		position = 6
	)
	default boolean checkMovement()
	{
		return true;
	}

	@ConfigItem(
		keyName = "checkMovementTime",
		name = "Check movement time (ms)",
		description = "The notification delay after the player is idle",
		position = 7
	)
	default int checkMovementTime()
	{
		return 5000;
	}

	@ConfigItem(
		keyName = "checkStationary",
		name = "Check stationary",
		description = "aa",
		position = 8
	)
	default boolean checkStationary()
	{
		return false;
	}

	@ConfigItem(
		keyName = "checkInteraction",
		name = "Check interaction",
		description = "aa",
		position = 9
	)
	default boolean checkInteraction()
	{
		return true;
	}

	@ConfigItem(
		keyName = "checkInteractionTime",
		name = "Check interaction time (ms)",
		description = "The notification delay after the player is idle",
		position = 10
	)
	default int checkInteractionTime()
	{
		return 5000;
	}

	@ConfigItem(
		keyName = "filteredWords",
		name = "Filtered Words",
		description = "List of filtered words, separated by commas",
		position = 11
	)
	default String filteredWords()
	{
		return "";
	}

	@ConfigItem(
		keyName = "filteredRegex",
		name = "Filtered Regex",
		description = "List of regular expressions to filter, one per line",
		position = 12
	)
	default String filteredRegex()
	{
		return "";
	}

//	@ConfigItem(
//		keyName = "hitpoints",
//		name = "Hitpoints Notification Threshold",
//		description = "The amount of hitpoints to send a notification at. A value of 0 will disable notification.",
//		position = 13
//	)
//	default int getHitpointsThreshold()
//	{
//		return 0;
//	}

//	@ConfigItem(
//		keyName = "prayer",
//		name = "Prayer Notification Threshold",
//		description = "The amount of prayer points to send a notification at. A value of 0 will disable notification.",
//		position = 14
//	)
//	default int getPrayerThreshold()
//	{
//		return 0;
//	}
}
