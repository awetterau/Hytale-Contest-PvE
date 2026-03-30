package dev.hytalemodding.state.run;

public final class RunStartPresentationConfig {
    private static volatile boolean movementLockEnabled = true;
    private static volatile boolean introCameraEnabled = true;

    private RunStartPresentationConfig() {
    }

    public static boolean isMovementLockEnabled() {
        return movementLockEnabled;
    }

    public static void setMovementLockEnabled(boolean enabled) {
        movementLockEnabled = enabled;
    }

    public static boolean isIntroCameraEnabled() {
        return introCameraEnabled;
    }

    public static void setIntroCameraEnabled(boolean enabled) {
        introCameraEnabled = enabled;
    }
}