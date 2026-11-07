package global.nscb.aegis;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/** Global alert bulletin board - JSON feed, local cache display. */
public final class ThreatAlertFeed {
    public static final String DEFAULT_URL =
            "https://raw.githubusercontent.com/dmoneycustoms/aegis-shield/main/definitions/alerts.json";

    public static final class Alert {
        public final String id, title, severity, body, region;

        public Alert(String id, String title, String severity, String body, String region) {
            this.id = id;
            this.title = title;
            this.severity = severity;
            this.body = body;
            this.region = region;
        }
    }

    public static List<Alert> fetch(String urlOverride) throws Exception {
        String url = urlOverride == null || urlOverride.isEmpty() ? DEFAULT_URL : urlOverride;
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(12000);
        c.setReadTimeout(20000);
        c.setRequestProperty("User-Agent", "AegisShield/1.1");
        InputStream in = c.getInputStream();
        String body = readAll(in);
        JSONObject root = new JSONObject(body);
        JSONArray arr = root.getJSONArray("alerts");
        List<Alert> out = new ArrayList<>();
        for (int i = 0; i < arr.length(); i++) {
            JSONObject o = arr.getJSONObject(i);
            out.add(new Alert(
                    o.optString("id", "a" + i),
                    o.optString("title", "Alert"),
                    o.optString("severity", "medium"),
                    o.optString("body", ""),
                    o.optString("region", "global")
            ));
        }
        return out;
    }

    /** Offline fallback bulletins so UI is never empty. */
    public static List<Alert> fallback() {
        List<Alert> out = new ArrayList<>();
        out.add(new Alert("local-1", "Sideloaded APK + SMS perms", "high",
                "APKs combining SEND_SMS and READ_CONTACTS remain a top mobile stealer pattern.", "global"));
        out.add(new Alert("local-2", "Accessibility malware", "high",
                "Banking overlays still abuse AccessibilityService. Flag BIND_ACCESSIBILITY_SERVICE on unknown apps.", "global"));
        out.add(new Alert("local-3", "Discord/Telegram exfil", "medium",
                "Stealers posting to discord webhooks or Telegram bots - definitions cover common endpoints.", "global"));
        out.add(new Alert("local-4", "Definition packs", "low",
                "Tap Update definitions daily. Pack merges additive signatures without wiping builtin set.", "global"));
        return out;
    }

    private static String readAll(InputStream in) throws Exception {
        BufferedReader br = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
        StringBuilder sb = new StringBuilder();
        String line;
        while ((line = br.readLine()) != null) sb.append(line).append('\n');
        return sb.toString();
    }
}
