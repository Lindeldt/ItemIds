package net.runelite.client.plugins.PrayAgainstPlayer;

/**
 * Event for cross-plugin communication when recommended prayer changes
 */
public class RecommendedPrayerChangedEvent {
    private final String recommendedPrayer;

    public RecommendedPrayerChangedEvent(String recommendedPrayer) {
        this.recommendedPrayer = recommendedPrayer;
    }

    public String getRecommendedPrayer() {
        return recommendedPrayer;
    }

    @Override
    public String toString() {
        return "RecommendedPrayerChangedEvent{" +
                "recommendedPrayer='" + recommendedPrayer + '\'' +
                '}';
    }
}