package global.nscb.aegis;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CopyOnWriteArrayList;

/** Built-in + remotely refreshed definitions — dense NSCB-style pack. */
public final class SignatureDB {
    public static final class Sig {
        public final String id, name, family, severity, detail, pattern;
        public final float weight;
        public Sig(String id, String name, String family, String severity, String pattern, String detail) {
            this(id, name, family, severity, pattern, detail, 1f);
        }
        public Sig(String id, String name, String family, String severity, String pattern, String detail, float weight) {
            this.id = id; this.name = name; this.family = family;
            this.severity = severity; this.pattern = pattern; this.detail = detail;
            this.weight = weight <= 0f ? 1f : weight;
        }
    }
    public static final class Hit {
        public final Sig sig; public final int at; public final float confidence;
        Hit(Sig sig, int at, float confidence) {
            this.sig = sig; this.at = at; this.confidence = confidence;
        }
    }

    private static final CopyOnWriteArrayList<Sig> ALL = new CopyOnWriteArrayList<>();
    private static volatile String packVersion = "nscb-aegis-2.0.0";
    private static volatile long packUpdatedAt = System.currentTimeMillis();

    static { seedBuiltin(); }

    public static String packVersion() { return packVersion; }
    public static long packUpdatedAt() { return packUpdatedAt; }
    public static int size() { return ALL.size(); }

    public static List<Hit> match(String text) {
        List<Hit> hits = new ArrayList<>();
        if (text == null || text.isEmpty()) return hits;
        String hay = text.toLowerCase(Locale.US);
        for (Sig s : ALL) {
            String needle = s.pattern.toLowerCase(Locale.US);
            if (needle.length() < 4) continue; // drop ultra-short FP bait
            int at = hay.indexOf(needle);
            if (at < 0) continue;
            float conf = confidenceFor(s, needle, hay, at);
            if (conf < 0.35f) continue; // reliability floor
            hits.add(new Hit(s, at, conf));
        }
        return hits;
    }

    /** Longer patterns + severity + weight => higher confidence. */
    private static float confidenceFor(Sig s, String needle, String hay, int at) {
        float conf = 0.45f;
        int len = needle.length();
        if (len >= 24) conf += 0.25f;
        else if (len >= 16) conf += 0.18f;
        else if (len >= 10) conf += 0.1f;
        else conf -= 0.08f; // short tokens less trusted alone
        switch (s.severity) {
            case "critical": conf += 0.12f; break;
            case "high": conf += 0.08f; break;
            case "low": conf -= 0.05f; break;
        }
        conf *= s.weight;
        // word-ish boundary boost
        boolean edgeL = at == 0 || !Character.isLetterOrDigit(hay.charAt(at - 1));
        boolean edgeR = at + len >= hay.length() || !Character.isLetterOrDigit(hay.charAt(at + len));
        if (edgeL && edgeR) conf += 0.06f;
        if (conf > 1f) conf = 1f;
        if (conf < 0f) conf = 0f;
        return conf;
    }

    /** Aggregate reliability 0..1 from a hit set (family diversity helps). */
    public static float reliability(List<Hit> hits) {
        if (hits == null || hits.isEmpty()) return 0f;
        float best = 0f;
        float sum = 0f;
        java.util.HashSet<String> families = new java.util.HashSet<>();
        for (Hit h : hits) {
            best = Math.max(best, h.confidence);
            sum += h.confidence;
            if (h.sig.family != null) families.add(h.sig.family);
        }
        float diversity = Math.min(0.25f, families.size() * 0.08f);
        float avg = sum / hits.size();
        float r = 0.5f * best + 0.35f * avg + diversity;
        if (hits.size() >= 3) r += 0.08f;
        return r > 1f ? 1f : r;
    }

    public static synchronized int mergeRemotePack(String json) throws Exception {
        JSONObject root = new JSONObject(json);
        String ver = root.optString("version", "remote");
        JSONArray arr = root.getJSONArray("signatures");
        int added = 0;
        for (int i = 0; i < arr.length(); i++) {
            JSONObject o = arr.getJSONObject(i);
            String id = o.getString("id");
            boolean exists = false;
            for (Sig s : ALL) { if (s.id.equals(id)) { exists = true; break; } }
            if (exists) continue;
            float w = (float) o.optDouble("weight", 1.0);
            ALL.add(new Sig(id, o.optString("name", id), o.optString("family", "malware"),
                    o.optString("severity", "medium"), o.getString("pattern"), o.optString("detail", ""), w));
            added++;
        }
        packVersion = ver;
        packUpdatedAt = System.currentTimeMillis();
        return added;
    }

    private static void add(String id, String name, String family, String sev, String pat, String detail) {
        ALL.add(new Sig(id, name, family, sev, pat, detail));
    }

    private static void seedBuiltin() {
        // --- Android stealer / banker / spyware ---
        add("apk-send-sms", "SEND_SMS", "stealer", "high", "android.permission.SEND_SMS", "SMS send capability");
        add("apk-read-sms", "READ_SMS", "stealer", "high", "android.permission.READ_SMS", "OTP / SMS intercept");
        add("apk-recv-sms", "RECEIVE_SMS", "stealer", "high", "android.permission.RECEIVE_SMS", "SMS intercept");
        add("apk-recv-mms", "RECEIVE_MMS", "stealer", "medium", "android.permission.RECEIVE_MMS", "MMS intercept");
        add("apk-boot", "Boot complete", "malware", "medium", "RECEIVE_BOOT_COMPLETED", "Persistence after reboot");
        add("apk-access", "Accessibility bind", "malware", "high", "BIND_ACCESSIBILITY_SERVICE", "Overlay/clicker abuse");
        add("apk-admin", "Device admin", "malware", "high", "BIND_DEVICE_ADMIN", "Locker / hard uninstall");
        add("apk-install", "Request install", "dropper", "high", "REQUEST_INSTALL_PACKAGES", "Sideload other APKs");
        add("apk-query", "Query all packages", "spyware", "medium", "QUERY_ALL_PACKAGES", "App enumeration");
        add("apk-record", "RECORD_AUDIO", "spyware", "medium", "RECORD_AUDIO", "Mic capture");
        add("apk-camera", "CAMERA", "spyware", "medium", "android.permission.CAMERA", "Camera capture");
        add("apk-contacts", "READ_CONTACTS", "stealer", "medium", "READ_CONTACTS", "Contact harvest");
        add("apk-call-log", "READ_CALL_LOG", "stealer", "medium", "READ_CALL_LOG", "Call log harvest");
        add("apk-phone", "READ_PHONE_STATE", "spyware", "medium", "READ_PHONE_STATE", "Device identifiers");
        add("apk-notif", "Notification listener", "spyware", "high", "BIND_NOTIFICATION_LISTENER_SERVICE", "Read notifications/OTP");
        add("apk-usage", "Usage stats", "spyware", "medium", "PACKAGE_USAGE_STATS", "App usage surveillance");
        add("apk-overlay", "System alert window", "malware", "high", "SYSTEM_ALERT_WINDOW", "Draw over other apps");
        add("apk-write-settings", "Write settings", "malware", "medium", "WRITE_SETTINGS", "Modify system settings");
        add("apk-accessibility-cls", "AccessibilityService", "malware", "high", "AccessibilityService", "A11y malware class");
        add("apk-dpm", "DevicePolicyManager", "malware", "high", "DevicePolicyManager", "Admin API");
        add("apk-js-iface", "JS interface", "malware", "medium", "addJavascriptInterface", "WebView bridge");
        add("apk-req-del", "REQUEST_DELETE_PACKAGES", "malware", "medium", "REQUEST_DELETE_PACKAGES", "Uninstall other apps");
        add("apk-manage-ext", "MANAGE_EXTERNAL_STORAGE", "spyware", "medium", "MANAGE_EXTERNAL_STORAGE", "Broad storage control");
        add("apk-schedule-exact", "SCHEDULE_EXACT_ALARM", "malware", "low", "SCHEDULE_EXACT_ALARM", "Exact alarm wake");
        add("dex-magic", "DEX header", "malware", "medium", "dex\n035", "DEX bytecode");
        add("apk-zip-magic", "APK/ZIP local hdr", "dropper", "low", "PK\u0003\u0004", "ZIP/APK container");

        // --- Script / LOLBins / living off land ---
        add("eval", "eval(", "malware", "high", "eval(", "Dynamic code");
        add("atob", "atob(", "malware", "medium", "atob(", "Hidden payload decode");
        add("powershell", "powershell", "malware", "high", "powershell", "Script host");
        add("encodedcmd", "-encodedcommand", "malware", "high", "-encodedcommand", "Obfuscated shell");
        add("iex", "invoke-expression", "malware", "high", "invoke-expression", "PowerShell exec");
        add("certutil", "certutil", "dropper", "high", "certutil", "LOLBin download");
        add("bitsadmin", "bitsadmin", "dropper", "high", "bitsadmin", "Background transfer");
        add("mshta", "mshta", "malware", "high", "mshta", "HTA host");
        add("wscript", "wscript.shell", "malware", "high", "wscript.shell", "WSH");
        add("cscript", "cscript", "malware", "medium", "cscript", "Script host");
        add("regsvr32", "regsvr32", "malware", "high", "regsvr32", "COM registration abuse");
        add("rundll32", "rundll32", "malware", "high", "rundll32", "DLL launch abuse");
        add("cmd-c", "cmd.exe /c", "malware", "medium", "cmd.exe /c", "Command shell");
        add("bash-c", "bash -c", "malware", "medium", "bash -c", "Unix shell exec");
        add("curl-pipe", "curl | sh", "dropper", "critical", "curl | sh", "Remote pipe install");
        add("wget-pipe", "wget | sh", "dropper", "critical", "wget | sh", "Remote pipe install");
        add("curl-o", "curl -o", "dropper", "medium", "curl -o", "Remote file drop");
        add("wget-O", "wget -O", "dropper", "medium", "wget -O", "Remote file drop");
        add("python-c", "python -c", "malware", "medium", "python -c", "Inline python exec");
        add("perl-e", "perl -e", "malware", "medium", "perl -e", "Inline perl exec");
        add("busybox", "busybox", "tooling", "low", "busybox", "Embedded toolkit");

        // --- Miners ---
        add("xmrig", "xmrig", "miner", "critical", "xmrig", "CPU miner");
        add("stratum", "stratum+tcp", "miner", "critical", "stratum+tcp", "Mining pool");
        add("stratum-ssl", "stratum+ssl", "miner", "critical", "stratum+ssl", "Mining pool TLS");
        add("coinhive", "coinhive", "miner", "high", "coinhive", "Browser miner");
        add("cryptonight", "cryptonight", "miner", "high", "cryptonight", "Miner algo");
        add("minerd", "minerd", "miner", "high", "minerd", "Miner binary name");
        add("nicehash", "nicehash", "miner", "high", "nicehash", "Mining marketplace");
        add("monero", "monero", "miner", "medium", "monero", "XMR reference");

        // --- Phishing / social / wallet ---
        add("seed", "seed phrase", "phishing", "critical", "seed phrase", "Wallet theft");
        add("recovery", "recovery phrase", "phishing", "critical", "recovery phrase", "Wallet theft");
        add("mnemonic", "mnemonic phrase", "phishing", "critical", "mnemonic", "Wallet theft");
        add("private-key", "private key", "phishing", "critical", "private key", "Key theft lure");
        add("phish-verify", "verify your account", "phishing", "medium", "verify your account", "Credential lure");
        add("phish-suspend", "account suspended", "phishing", "medium", "account suspended", "Credential lure");
        add("phish-unusual", "unusual sign-in", "phishing", "medium", "unusual sign-in", "Credential lure");
        add("phish-ssn", "social security number", "phishing", "medium", "social security number", "PII lure");
        add("phish-wire", "wire transfer", "phishing", "medium", "wire transfer", "BEC lure");
        add("phish-password-exp", "password expires", "phishing", "medium", "password expires", "Credential lure");
        add("phish-gift", "claim your reward", "phishing", "medium", "claim your reward", "Reward scam");
        add("phish-kyc", "complete kyc", "phishing", "medium", "complete kyc", "KYC phishing");
        add("metamask", "metamask", "phishing", "medium", "metamask", "Wallet brand lure");
        add("trustwallet", "trust wallet", "phishing", "medium", "trust wallet", "Wallet brand lure");

        // --- Android shell / root / frameworks ---
        add("hidden-sh", "#!/system/bin/sh", "malware", "medium", "#!/system/bin/sh", "Android shell script");
        add("su-xbin", "/system/xbin/su", "tooling", "low", "/system/xbin/su", "Root binary path");
        add("magisk", "magisk", "tooling", "low", "magisk", "Root framework");
        add("supersu", "supersu", "tooling", "low", "supersu", "Root framework");
        add("frida", "frida", "tooling", "medium", "frida", "Instrumentation");
        add("xposed", "xposed", "tooling", "medium", "xposed", "Hook framework");
        add("lsposed", "lsposed", "tooling", "medium", "lsposed", "Hook framework");
        add("substrate", "cydia substrate", "tooling", "medium", "cydia substrate", "Hook framework");
        add("metasploit", "metasploit", "malware", "critical", "metasploit", "Offensive framework");
        add("meterpreter", "meterpreter", "malware", "critical", "meterpreter", "C2 payload");
        add("payload-apk", "payload.apk", "dropper", "high", "payload.apk", "Dropper naming");
        add("base64-decode", "base64_decode", "malware", "medium", "base64_decode", "Obfuscation");
        add("fromcharcode", "fromCharCode", "malware", "medium", "fromcharcode", "JS obfuscation");
        add("unescape", "unescape(", "malware", "medium", "unescape(", "JS obfuscation");
        add("runtime-exec", "Runtime.getRuntime().exec", "malware", "high", "Runtime.getRuntime().exec", "Process spawn");
        add("dexclassloader", "DexClassLoader", "malware", "high", "DexClassLoader", "Dynamic code load");
        add("pathclassloader", "PathClassLoader", "malware", "medium", "PathClassLoader", "Dynamic load");
        add("loadclass", "loadClass(", "malware", "medium", "loadClass(", "Reflective load");
        add("system-bin-su", "/system/bin/su", "tooling", "low", "/system/bin/su", "Root path");
        add("data-local-tmp", "/data/local/tmp", "malware", "medium", "/data/local/tmp", "Staging path");

        // --- C2 / network / exfil ---
        add("ngrok", "ngrok.io", "malware", "high", "ngrok.io", "Tunnel C2 pattern");
        add("ngrok-app", "ngrok-free.app", "malware", "high", "ngrok-free.app", "Tunnel C2");
        add("duckdns", "duckdns.org", "malware", "medium", "duckdns.org", "Dynamic DNS C2");
        add("noip", "no-ip.com", "malware", "medium", "no-ip.com", "Dynamic DNS");
        add("pastebin-raw", "pastebin.com/raw", "dropper", "medium", "pastebin.com/raw", "Raw paste payload host");
        add("discord-webhook", "discord.com/api/webhooks", "stealer", "high", "discord.com/api/webhooks", "Exfil webhook");
        add("telegram-bot", "api.telegram.org/bot", "stealer", "high", "api.telegram.org/bot", "Telegram bot exfil");
        add("gist-raw", "gist.githubusercontent.com", "dropper", "medium", "gist.githubusercontent.com", "Raw gist host");
        add("raw-github", "raw.githubusercontent.com", "dropper", "low", "raw.githubusercontent.com", "Raw github host");
        add("trycloudflare", "trycloudflare.com", "malware", "medium", "trycloudflare.com", "Tunnel C2");
        add("localtunnel", "loca.lt", "malware", "medium", "loca.lt", "Tunnel C2");
        add("serveo", "serveo.net", "malware", "medium", "serveo.net", "Tunnel C2");

        // --- Docs / macros / PDF ---
        add("autoopen", "AutoOpen", "malware", "high", "autoopen", "Office auto macro");
        add("document-open", "Document_Open", "malware", "high", "document_open", "Word macro");
        add("workbook-open", "Workbook_Open", "malware", "high", "workbook_open", "Excel macro");
        add("openaction", "/OpenAction", "malware", "medium", "/openaction", "PDF auto action");
        add("js-pdf", "/JavaScript", "malware", "medium", "/javascript", "PDF JS");
        add("shell-pdf", "/Launch /Action", "malware", "high", "/launch /action", "PDF launch action");
        add("vba-createobject", "CreateObject", "malware", "high", "createobject", "VBA object spawn");
        add("vba-wscript", "WScript.Shell", "malware", "high", "wscript.shell", "VBA shell");

        // --- Ransomware ---
        add("ransom-decrypt", "your files have been encrypted", "ransomware", "critical", "your files have been encrypted", "Ransom note");
        add("ransom-bitcoin", "pay the ransom", "ransomware", "critical", "pay the ransom", "Ransom note");
        add("ransom-onion", ".onion", "ransomware", "high", ".onion", "Tor payment/C2 hint");
        add("ransom-decryptor", "decryptor", "ransomware", "medium", "decryptor", "Ransom tooling string");
        add("ransom-readme", "README_FOR_DECRYPT", "ransomware", "critical", "readme_for_decrypt", "Ransom note name");
        add("ransom-how", "HOW_TO_DECRYPT", "ransomware", "critical", "how_to_decrypt", "Ransom note name");

        // --- Banking / ATO patterns ---
        add("overlay-login", "fake login", "malware", "high", "fake login", "Overlay banking");
        add("screen-capture", "MediaProjection", "spyware", "high", "MediaProjection", "Screen capture API");
        add("keylog", "keylog", "spyware", "high", "keylog", "Keylogger string");
        add("sms-forward", "sms forward", "stealer", "high", "sms forward", "SMS forwarder");
        add("ussd", "callUssd", "malware", "high", "callussd", "USSD abuse");

        // --- Packers / obfuscators hints ---
        add("dex2oat", "dex2oat", "tooling", "low", "dex2oat", "ART compile");
        add("multidex", "MultiDex", "tooling", "low", "multidex", "Multidex loader");
        add("stringer", "stringer", "tooling", "low", "stringer", "Obfuscator brand");
        add("dexguard", "dexguard", "tooling", "low", "dexguard", "Obfuscator brand");
        add("allatori", "allatori", "tooling", "low", "allatori", "Obfuscator brand");

        // --- NSCB-style infrastructure markers (detection of tooling strings) ---
        add("c2-beacon", "beacon_interval", "malware", "medium", "beacon_interval", "C2 beacon config");
        add("c2-heartbeat", "heartbeat_token", "malware", "medium", "heartbeat_token", "C2 heartbeat");
        add("c2-exfil", "exfil_endpoint", "stealer", "high", "exfil_endpoint", "Exfil config key");
        add("c2-stage2", "stage2_url", "dropper", "high", "stage2_url", "Second stage URL key");
        add("rat-bind", "bind_shell", "malware", "critical", "bind_shell", "Bind shell marker");
        add("rat-reverse", "reverse_shell", "malware", "critical", "reverse_shell", "Reverse shell marker");
        add("socks-proxy", "socks5://", "malware", "medium", "socks5://", "Proxy tunnel");
        add("tor-socks", "127.0.0.1:9050", "malware", "medium", "127.0.0.1:9050", "Local Tor SOCKS");
    }
}
