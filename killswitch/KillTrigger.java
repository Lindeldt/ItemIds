package net.runelite.client.plugins.killswitch;

public abstract class KillTrigger {
    protected boolean shouldCheck;

    public KillTrigger(boolean shouldCheck) {
        this.shouldCheck = shouldCheck;
    }
}
