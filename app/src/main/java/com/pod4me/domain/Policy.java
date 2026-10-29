package com.pod4me.domain;

/** Business rules independent of Android, storage and wall-clock access. */
public final class Policy {
    public static final long DAY = 86_400_000L;
    private Policy() {}

    public static Long downloadExpiry(long downloadedAt, Integer days) {
        validateExpiryDays(days);
        if (days == null) return null;
        return Math.addExact(downloadedAt, Math.multiplyExact(days.longValue(), DAY));
    }

    public static void validateExpiryDays(Integer days) {
        if (days != null && (days < 1 || days > 36500)) throw new IllegalArgumentException("Expiry must be 1–36500 days or forever");
    }

    public static boolean isHeard(long position, long duration) {
        return duration > 0 && position >= duration * 0.95d;
    }

    public static Long heardExpiry(Long previous, long heardAt) {
        long limit = Math.addExact(heardAt, 7 * DAY);
        return previous == null ? limit : Math.min(previous, limit);
    }

    public static boolean canDelete(boolean protectedForever, boolean currentPlayback) {
        return !protectedForever && !currentPlayback;
    }

    public static boolean isExpired(Long deadline, long now, boolean protectedForever, boolean currentPlayback) {
        return deadline != null && deadline <= now && canDelete(protectedForever, currentPlayback);
    }

    public static long resumePosition(long position, long duration) {
        return duration > 0 && position >= duration ? 0 : Math.max(0, position);
    }
}
