package global.nscb.aegis;

import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.provider.Telephony;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Inbox / sent SMS scan for phishing, malware links, stealer lures. */
public final class SmsThreatScanner {
    public static final class Hit {
        public final String address, body, severity, match, when;
        Hit(String address, String body, String severity, String match, String when) {
            this.address = address; this.body = body; this.severity = severity;
            this.match = match; this.when = when;
        }
    }

    public static List<Hit> scan(Context ctx, int limit) {
        List<Hit> out = new ArrayList<>();
        Uri uri = Telephony.Sms.CONTENT_URI;
        String[] proj = new String[]{
                Telephony.Sms.ADDRESS, Telephony.Sms.BODY, Telephony.Sms.DATE, Telephony.Sms.TYPE
        };
        try (Cursor c = ctx.getContentResolver().query(uri, proj, null, null, Telephony.Sms.DEFAULT_SORT_ORDER)) {
            if (c == null) return out;
            int n = 0;
            while (c.moveToNext() && n < limit) {
                n++;
                String address = c.getString(0);
                String body = c.getString(1);
                long date = c.getLong(2);
                if (body == null) continue;
                List<SignatureDB.Hit> hits = SignatureDB.match(body);
                // URL-heavy SMS without hits still flagged soft
                boolean hasUrl = body.toLowerCase(Locale.US).contains("http://")
                        || body.toLowerCase(Locale.US).contains("https://")
                        || body.toLowerCase(Locale.US).contains("bit.ly")
                        || body.toLowerCase(Locale.US).contains("tinyurl");
                boolean lure = body.toLowerCase(Locale.US).contains("verify")
                        || body.toLowerCase(Locale.US).contains("suspended")
                        || body.toLowerCase(Locale.US).contains("urgent")
                        || body.toLowerCase(Locale.US).contains("click");
                if (hits.isEmpty() && !(hasUrl && lure)) continue;
                String sev = hits.isEmpty() ? "medium" : hits.get(0).sig.severity;
                String match = hits.isEmpty() ? "suspicious link lure" : hits.get(0).sig.name;
                String snippet = body.length() > 160 ? body.substring(0, 160) + "…" : body;
                out.add(new Hit(address == null ? "?" : address, snippet, sev, match, String.valueOf(date)));
            }
        } catch (SecurityException se) {
            out.add(new Hit("permission", "READ_SMS not granted", "info", "permission", "0"));
        } catch (Exception e) {
            out.add(new Hit("error", e.getMessage() == null ? "sms scan failed" : e.getMessage(), "info", "error", "0"));
        }
        return out;
    }
}
