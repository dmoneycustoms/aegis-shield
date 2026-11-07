package global.nscb.aegis;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * Only allow a notification once per fingerprint until TTL expires.
 * Stops identical alert spam that drains battery and patience.
 */
public final class AlertDeduper {
    private static final String PREF = "aegis_notify_seen";
    private static final long DEFAULT_TTL_MS = 7L * 24 * 60 * 60 * 1000; // 7 days

    private AlertDeduper() {}

    public static boolean shouldNotify(Context ctx, String fingerprint) {
        return shouldNotify(ctx, fingerprint, DEFAULT_TTL_MS);
    }

    public static boolean shouldNotify(Context ctx, String fingerprint, long ttlMs) {
        if (fingerprint == null || fingerprint.isEmpty()) return false;
        String key = "fp_" + Integer.toHexString(fingerprint.toLowerCase().hashCode());
        SharedPreferences sp = ctx.getApplicationContext().getSharedPreferences(PREF, Context.MODE_PRIVATE);
        long now = System.currentTimeMillis();
        long last = sp.getLong(key, 0L);
        if (last > 0 && (now - last) < ttlMs) return false;
        sp.edit().putLong(key, now).apply();
        return true;
    }

    public static boolean hasSeen(Context ctx, String fingerprint) {
        if (fingerprint == null) return true;
        String key = "fp_" + Integer.toHexString(fingerprint.toLowerCase().hashCode());
        SharedPreferences sp = ctx.getApplicationContext().getSharedPreferences(PREF, Context.MODE_PRIVATE);
        long last = sp.getLong(key, 0L);
        return last > 0 && (System.currentTimeMillis() - last) < DEFAULT_TTL_MS;
    }
}
