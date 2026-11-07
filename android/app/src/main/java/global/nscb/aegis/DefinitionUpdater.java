package global.nscb.aegis;

import android.content.Context;
import android.content.SharedPreferences;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/**
 * Remote definition channel:
 *   1) HTTPS pack from GitHub raw (primary)
 *   2) Fallback mirror path
 *   3) On-disk cache last good pack
 * Fails closed — builtin signatures always remain.
 */
public final class DefinitionUpdater {
    public static final String PRIMARY_URL =
            "https://raw.githubusercontent.com/dmoneycustoms/aegis-shield/main/definitions/latest.json";
    public static final String FALLBACK_URL =
            "https://cdn.jsdelivr.net/gh/dmoneycustoms/aegis-shield@main/definitions/latest.json";
    /** @deprecated use PRIMARY_URL */
    public static final String DEFAULT_URL = PRIMARY_URL;

    public static final class Result {
        public final boolean ok;
        public final int added;
        public final String message;
        public final String source;

        Result(boolean ok, int added, String message, String source) {
            this.ok = ok;
            this.added = added;
            this.message = message;
            this.source = source;
        }
    }

    private DefinitionUpdater() {}

    public static Result updateNow(Context ctx, String urlOverride) {
        Context app = ctx.getApplicationContext();
        if (urlOverride != null && !urlOverride.isEmpty()) {
            Result r = pull(app, urlOverride, "override");
            if (r.ok) return r;
        }
        Result r = pull(app, PRIMARY_URL, "primary");
        if (r.ok) return r;
        Result r2 = pull(app, FALLBACK_URL, "fallback");
        if (r2.ok) return r2;
        Result cached = loadCache(app);
        if (cached.ok) return cached;
        return new Result(false, 0,
                "remote failed; using builtin only (" + SignatureDB.size() + " defs). "
                        + r.message + " | " + r2.message,
                "builtin");
    }

    /** Auto update at most once per 24h. */
    public static Result updateIfStale(Context ctx) {
        SharedPreferences sp = ctx.getSharedPreferences("aegis", Context.MODE_PRIVATE);
        long last = sp.getLong("defs_updated_at", 0L);
        if (System.currentTimeMillis() - last < 24L * 3600_000L) {
            return new Result(true, 0,
                    "defs fresh | " + SignatureDB.packVersion() + " | " + SignatureDB.size(),
                    "cached-time");
        }
        return updateNow(ctx, null);
    }

    private static Result pull(Context ctx, String url, String source) {
        try {
            HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
            c.setConnectTimeout(12000);
            c.setReadTimeout(20000);
            c.setInstanceFollowRedirects(true);
            c.setRequestProperty("User-Agent", "AegisShield/2.4");
            c.setRequestProperty("Accept", "application/json");
            int code = c.getResponseCode();
            InputStream in = code >= 400 ? c.getErrorStream() : c.getInputStream();
            if (in == null) return new Result(false, 0, "HTTP " + code + " empty", source);
            String body = readAll(in);
            if (code >= 400) {
                return new Result(false, 0, "HTTP " + code + ": " + body.substring(0, Math.min(80, body.length())), source);
            }
            if (!body.contains("\"signatures\"")) {
                return new Result(false, 0, "invalid pack shape", source);
            }
            int added = SignatureDB.mergeRemotePack(body);
            saveCache(ctx, body);
            SharedPreferences sp = ctx.getSharedPreferences("aegis", Context.MODE_PRIVATE);
            sp.edit()
                    .putLong("defs_updated_at", System.currentTimeMillis())
                    .putString("defs_version", SignatureDB.packVersion())
                    .putInt("defs_count", SignatureDB.size())
                    .putString("defs_source", source)
                    .apply();
            return new Result(true, added,
                    "pack " + SignatureDB.packVersion() + " | +" + added + " | total " + SignatureDB.size()
                            + " | src " + source,
                    source);
        } catch (Exception e) {
            return new Result(false, 0, e.getClass().getSimpleName() + ": " + e.getMessage(), source);
        }
    }

    private static void saveCache(Context ctx, String body) {
        try {
            File f = new File(ctx.getFilesDir(), "defs_latest.json");
            try (FileOutputStream fos = new FileOutputStream(f, false)) {
                fos.write(body.getBytes(StandardCharsets.UTF_8));
            }
        } catch (Exception ignored) {}
    }

    private static Result loadCache(Context ctx) {
        try {
            File f = new File(ctx.getFilesDir(), "defs_latest.json");
            if (!f.exists()) return new Result(false, 0, "no cache", "cache");
            String body = readAll(new FileInputStream(f));
            int added = SignatureDB.mergeRemotePack(body);
            return new Result(true, added,
                    "cache pack " + SignatureDB.packVersion() + " | +" + added + " | total " + SignatureDB.size(),
                    "cache");
        } catch (Exception e) {
            return new Result(false, 0, "cache: " + e.getMessage(), "cache");
        }
    }

    private static String readAll(InputStream in) throws Exception {
        StringBuilder sb = new StringBuilder();
        try (BufferedReader br = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            String line;
            while ((line = br.readLine()) != null) sb.append(line).append('\n');
        }
        return sb.toString();
    }
}
