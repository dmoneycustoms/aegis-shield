package global.nscb.aegis;

import android.content.Context;
import android.os.Environment;

import java.io.File;
import java.io.FileInputStream;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Multi-root shared-storage walker.
 * Android without root cannot enter other apps' /data/data — that is an OS wall, not a stub.
 * We scan every readable shared volume: emulated primary, secondary SD, public dirs, app-visible trees.
 */
public final class FileScanner {
    public interface Listener {
        void onProgress(ScanStats stats, String currentPath);
        void onHit(Finding finding);
        void onDone(ScanStats stats);
    }

    public static final class ScanStats {
        public int scanned, skipped, failed, clear, suspicious, malicious, hidden, roots;
        public String root = "";
        public final List<String> rootsTried = new ArrayList<>();
        public final List<String> rootsDenied = new ArrayList<>();
        public boolean done;
    }

    public static final class Finding {
        public final String path, sha256, verdict, topMatch, detail;
        public final boolean hidden;
        public final int scorePct;

        Finding(String path, String sha256, String verdict, int scorePct, String topMatch, String detail, boolean hidden) {
            this.path = path;
            this.sha256 = sha256;
            this.verdict = verdict;
            this.scorePct = scorePct;
            this.topMatch = topMatch;
            this.detail = detail;
            this.hidden = hidden;
        }
    }

    private static final long MAX_BYTES = 32L * 1024 * 1024;
    private static final int MAX_DEPTH = 24;
    private final AtomicBoolean cancel = new AtomicBoolean(false);
    private final Set<String> visited = new HashSet<>();

    public void cancel() {
        cancel.set(true);
    }

    /** All roots we can see without root. */
    public static List<File> collectRoots(Context ctx) {
        List<File> roots = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        addRoot(roots, seen, Environment.getExternalStorageDirectory());
        addRoot(roots, seen, Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS));
        addRoot(roots, seen, Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS));
        addRoot(roots, seen, Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM));
        addRoot(roots, seen, Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES));
        addRoot(roots, seen, Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES));
        addRoot(roots, seen, Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC));
        addRoot(roots, seen, Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PODCASTS));
        addRoot(roots, seen, Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_ALARMS));
        addRoot(roots, seen, Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_NOTIFICATIONS));
        addRoot(roots, seen, Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_RINGTONES));
        // Multi-volume (SD cards, USB OTGs exposed as files)
        try {
            File[] dirs = ctx.getExternalFilesDirs(null);
            if (dirs != null) {
                for (File d : dirs) {
                    if (d == null) continue;
                    // climb to volume root when possible
                    File vol = d;
                    for (int i = 0; i < 6 && vol.getParentFile() != null; i++) {
                        String p = vol.getAbsolutePath();
                        if (p.startsWith("/storage/") && p.split("/").length <= 3) break;
                        vol = vol.getParentFile();
                    }
                    addRoot(roots, seen, vol);
                    addRoot(roots, seen, d);
                }
            }
        } catch (Exception ignored) {}
        addRoot(roots, seen, new File("/storage/emulated/0"));
        addRoot(roots, seen, new File("/sdcard"));
        addRoot(roots, seen, new File("/storage"));
        // Readable system areas (names only / public) — not private app data
        addRoot(roots, seen, new File("/data/local/tmp"));
        addRoot(roots, seen, ctx.getFilesDir());
        addRoot(roots, seen, ctx.getCacheDir());
        addRoot(roots, seen, ctx.getExternalCacheDir());
        return roots;
    }

    private static void addRoot(List<File> roots, Set<String> seen, File f) {
        if (f == null) return;
        try {
            if (!f.exists()) return;
            String path = f.getCanonicalPath();
            if (!seen.add(path)) return;
            roots.add(f);
        } catch (Exception e) {
            String path = f.getAbsolutePath();
            if (seen.add(path) && f.exists()) roots.add(f);
        }
    }

    public void scanAll(Context ctx, Listener listener) {
        cancel.set(false);
        visited.clear();
        ScanStats stats = new ScanStats();
        List<File> roots = collectRoots(ctx);
        stats.roots = roots.size();
        StringBuilder rootLabel = new StringBuilder();
        for (File r : roots) {
            if (cancel.get()) break;
            stats.rootsTried.add(r.getAbsolutePath());
            if (rootLabel.length() > 0) rootLabel.append(" | ");
            rootLabel.append(r.getAbsolutePath());
            File[] probe = r.isDirectory() ? r.listFiles() : null;
            if (r.isDirectory() && probe == null && !r.canRead()) {
                stats.rootsDenied.add(r.getAbsolutePath());
                continue;
            }
            walk(r, listener, stats, 0);
        }
        stats.root = rootLabel.toString();
        stats.done = true;
        listener.onDone(stats);
    }

    /** @deprecated use scanAll for full coverage */
    public void scan(File root, Listener listener) {
        cancel.set(false);
        visited.clear();
        ScanStats stats = new ScanStats();
        stats.root = root != null ? root.getAbsolutePath() : "";
        stats.roots = 1;
        if (root != null) {
            stats.rootsTried.add(root.getAbsolutePath());
            walk(root, listener, stats, 0);
        }
        stats.done = true;
        listener.onDone(stats);
    }

    private void walk(File node, Listener listener, ScanStats stats, int depth) {
        if (cancel.get() || node == null || depth > MAX_DEPTH) return;
        String key;
        try {
            key = node.getCanonicalPath();
        } catch (Exception e) {
            key = node.getAbsolutePath();
        }
        if (!visited.add(key)) return;

        if (node.isFile()) {
            scanFile(node, listener, stats);
            return;
        }
        if (shouldSkipDir(node)) {
            stats.skipped++;
            return;
        }
        File[] kids = node.listFiles();
        if (kids == null) {
            stats.rootsDenied.add(node.getAbsolutePath());
            return;
        }
        listener.onProgress(stats, node.getAbsolutePath());
        for (File k : kids) {
            if (cancel.get()) return;
            if (k.isDirectory()) walk(k, listener, stats, depth + 1);
            else scanFile(k, listener, stats);
        }
    }

    private static boolean shouldSkipDir(File dir) {
        String name = dir.getName();
        if (name == null) return false;
        String n = name.toLowerCase(Locale.US);
        // Huge media caches / offline maps — still scannable via public leaf roots, skip deep vendor junk
        if (n.equals("cache") || n.equals(".cache") || n.equals("code_cache")) return true;
        if (n.equals("lost+found")) return true;
        // Android OS internal (not readable anyway on non-root)
        String p = dir.getAbsolutePath().toLowerCase(Locale.US);
        if (p.startsWith("/data/data") || p.startsWith("/data/user")) return true;
        if (p.startsWith("/proc") || p.startsWith("/sys") || p.startsWith("/dev")) return true;
        if (p.startsWith("/apex") || p.startsWith("/vendor") || p.startsWith("/system")) return true;
        return false;
    }

    private void scanFile(File file, Listener listener, ScanStats stats) {
        if (cancel.get()) return;
        String path = file.getAbsolutePath();
        boolean hidden = isHidden(path);
        if (hidden) stats.hidden++;
        listener.onProgress(stats, path);
        long len = file.length();
        if (len <= 0 || len > MAX_BYTES) {
            stats.skipped++;
            return;
        }
        // Skip pure media bulk by extension for speed (optional — still scan scripts/apks/docs)
        String lower = file.getName().toLowerCase(Locale.US);
        if (isBulkMedia(lower) && len > 2L * 1024 * 1024) {
            stats.skipped++;
            return;
        }
        try {
            byte[] head = readHead(file, (int) Math.min(len, 256 * 1024));
            String text = buildHaystack(file.getName(), head);
            List<SignatureDB.Hit> hits = SignatureDB.match(text);
            int score = score(hits, text, file.getName());
            int crit = 0;
            for (SignatureDB.Hit h : hits) {
                if ("critical".equals(h.sig.severity) || "high".equals(h.sig.severity)) crit++;
            }
            try {
                float ortRisk = OnnxEngine.scoreFinding(hits.size(), crit, hidden, score, head);
                if (ortRisk > 0.55f) score = Math.max(score, (int) (ortRisk * 99));
                else if (ortRisk > 0.35f) score = Math.max(score, Math.min(99, score + 8));
            } catch (Throwable ignored) {}
            float rel = SignatureDB.reliability(hits);
            if (rel < 0.4f && score < 60) {
                // single weak medium hit — do not escalate
                score = Math.min(score, 40);
            } else if (rel >= 0.7f) {
                score = Math.max(score, 62);
            }
            String verdict = verdict(hits, score, rel);
            stats.scanned++;
            switch (verdict) {
                case "malicious": stats.malicious++; break;
                case "suspicious": stats.suspicious++; break;
                default: stats.clear++; break;
            }
            if (!"clear".equals(verdict)) {
                // Hash only findings — full-disk SHA was freezing the walk
                String sha = "";
                try { sha = sha256Head(file, head); } catch (Exception ignored) {}
                String top = hits.isEmpty() ? "heuristic/ort" : hits.get(0).sig.name;
                String detail = hits.isEmpty() ? "Elevated score"
                        : (hits.get(0).sig.detail + " | conf=" + String.format(java.util.Locale.US, "%.2f", rel));
                listener.onHit(new Finding(path, sha, verdict, score, top, detail, hidden));
            }
        } catch (Exception e) {
            stats.failed++;
        }
    }

    private static boolean isBulkMedia(String lower) {
        return lower.endsWith(".mp4") || lower.endsWith(".mkv") || lower.endsWith(".avi")
                || lower.endsWith(".mov") || lower.endsWith(".mp3") || lower.endsWith(".flac")
                || lower.endsWith(".wav") || lower.endsWith(".iso") || lower.endsWith(".zip")
                || lower.endsWith(".rar") || lower.endsWith(".7z");
    }

    private static boolean isHidden(String path) {
        String[] parts = path.split("/");
        for (String p : parts) {
            if (p.startsWith(".") && p.length() > 1) return true;
        }
        return false;
    }

    private static byte[] readHead(File f, int max) throws Exception {
        try (FileInputStream in = new FileInputStream(f)) {
            byte[] buf = new byte[max];
            int n = in.read(buf);
            if (n <= 0) return new byte[0];
            if (n == buf.length) return buf;
            byte[] out = new byte[n];
            System.arraycopy(buf, 0, out, 0, n);
            return out;
        }
    }

    private static String buildHaystack(String name, byte[] head) {
        StringBuilder sb = new StringBuilder(name.length() + head.length + 16);
        sb.append(name).append('\n');
        for (byte b : head) {
            int c = b & 0xff;
            if (c >= 32 && c < 127) sb.append((char) c);
            else if (c == 10 || c == 13 || c == 9) sb.append(' ');
        }
        return sb.toString();
    }

    private static int score(List<SignatureDB.Hit> hits, String text, String name) {
        int s = 5;
        for (SignatureDB.Hit h : hits) {
            switch (h.sig.severity) {
                case "critical": s += 40; break;
                case "high": s += 28; break;
                case "medium": s += 16; break;
                default: s += 8; break;
            }
        }
        String lower = name.toLowerCase(Locale.US);
        if (lower.endsWith(".apk") && !hits.isEmpty()) s += 10;
        if (text.contains("eval(") && text.contains("base64")) s += 15;
        return Math.min(99, s);
    }

    private static String verdict(List<SignatureDB.Hit> hits, int score, float reliability) {
        boolean strongHigh = false;
        for (SignatureDB.Hit h : hits) {
            if (("critical".equals(h.sig.severity) || "high".equals(h.sig.severity)) && h.confidence >= 0.55f) {
                strongHigh = true;
                break;
            }
        }
        if (strongHigh && reliability >= 0.5f) return "malicious";
        if (score >= 70 && reliability >= 0.55f) return "malicious";
        if (score >= 55 && reliability >= 0.45f) return "suspicious";
        if (!hits.isEmpty() && reliability >= 0.5f) return "suspicious";
        if (!hits.isEmpty() && score >= 50) return "suspicious";
        return "clear";
    }

    private static String sha256Head(File f, byte[] head) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        md.update(head);
        md.update(f.getName().getBytes(java.nio.charset.StandardCharsets.UTF_8));
        byte[] dig = md.digest();
        StringBuilder hex = new StringBuilder(dig.length * 2);
        for (byte b : dig) hex.append(String.format(Locale.US, "%02x", b));
        return hex.toString();
    }
}
