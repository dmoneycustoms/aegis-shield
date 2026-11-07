package global.nscb.aegis;

import android.content.Context;
import android.os.Environment;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Email threat scan without Gmail OAuth:
 *  - .eml / .msg files on shared storage
 *  - Download/Documents caches often used by mail apps
 * Full Gmail body access needs Google OAuth — not shipped here.
 */
public final class EmailThreatScanner {
    public static final class Hit {
        public final String path, severity, match, snippet;
        Hit(String path, String severity, String match, String snippet) {
            this.path = path; this.severity = severity; this.match = match; this.snippet = snippet;
        }
    }

    public static List<Hit> scan(Context ctx, int maxFiles) {
        List<Hit> out = new ArrayList<>();
        List<File> roots = new ArrayList<>();
        roots.add(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS));
        roots.add(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS));
        roots.add(Environment.getExternalStorageDirectory());
        int[] count = {0};
        for (File r : roots) {
            if (r != null) walk(r, out, count, maxFiles, 0);
        }
        return out;
    }

    private static void walk(File node, List<Hit> out, int[] count, int max, int depth) {
        if (node == null || count[0] >= max || depth > 10) return;
        if (node.isFile()) {
            String n = node.getName().toLowerCase(Locale.US);
            if (!(n.endsWith(".eml") || n.endsWith(".msg") || n.endsWith(".mbox"))) return;
            count[0]++;
            try {
                String text = readText(node, 64 * 1024);
                List<SignatureDB.Hit> hits = SignatureDB.match(text);
                boolean phish = text.toLowerCase(Locale.US).contains("content-type: text/html")
                        && (text.toLowerCase(Locale.US).contains("verify your account")
                        || text.toLowerCase(Locale.US).contains("password")
                        || text.toLowerCase(Locale.US).contains("http://"));
                if (hits.isEmpty() && !phish) return;
                String sev = hits.isEmpty() ? "medium" : hits.get(0).sig.severity;
                String match = hits.isEmpty() ? "html mail lure" : hits.get(0).sig.name;
                String snip = text.length() > 120 ? text.substring(0, 120).replace('\n', ' ') : text;
                out.add(new Hit(node.getAbsolutePath(), sev, match, snip));
            } catch (Exception ignored) {}
            return;
        }
        File[] kids = node.listFiles();
        if (kids == null) return;
        for (File k : kids) {
            if (count[0] >= max) return;
            walk(k, out, count, max, depth + 1);
        }
    }

    private static String readText(File f, int max) throws Exception {
        StringBuilder sb = new StringBuilder();
        try (BufferedReader br = new BufferedReader(new InputStreamReader(new FileInputStream(f), StandardCharsets.UTF_8))) {
            char[] buf = new char[4096];
            int n, total = 0;
            while ((n = br.read(buf)) > 0 && total < max) {
                int take = Math.min(n, max - total);
                sb.append(buf, 0, take);
                total += take;
            }
        }
        return sb.toString();
    }
}
