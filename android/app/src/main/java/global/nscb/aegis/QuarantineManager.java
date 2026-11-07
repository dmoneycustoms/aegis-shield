package global.nscb.aegis;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.provider.Settings;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;

/**
 * Quarantine vault under app files dir + persistent hash ledger.
 * Files are moved (copy+delete when possible) into a sealed folder; apps open system UI.
 */
public final class QuarantineManager {
    public static final class Entry {
        public final String sha256, originalPath, label, verdict, vaultName;
        public final long at;

        Entry(String sha256, String originalPath, String label, String verdict, String vaultName, long at) {
            this.sha256 = sha256;
            this.originalPath = originalPath;
            this.label = label;
            this.verdict = verdict;
            this.vaultName = vaultName;
            this.at = at;
        }
    }

    private final Context app;
    private final File vault;

    public QuarantineManager(Context ctx) {
        this.app = ctx.getApplicationContext();
        this.vault = new File(app.getFilesDir(), "quarantine");
        if (!vault.exists()) vault.mkdirs();
        // marker so scanners can recognize vault
        try {
            File marker = new File(vault, ".aegis_quarantine");
            if (!marker.exists()) marker.createNewFile();
        } catch (Exception ignored) {
        }
    }

    public File vaultDir() {
        return vault;
    }

    public boolean isQuarantined(String sha256) {
        if (sha256 == null) return false;
        for (Entry e : list()) {
            if (sha256.equalsIgnoreCase(e.sha256)) return true;
        }
        return false;
    }

    public synchronized List<Entry> list() {
        List<Entry> out = new ArrayList<>();
        try {
            SharedPreferences sp = app.getSharedPreferences("aegis_q", Context.MODE_PRIVATE);
            String raw = sp.getString("ledger", "[]");
            JSONArray arr = new JSONArray(raw);
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.getJSONObject(i);
                out.add(new Entry(
                        o.optString("sha256"),
                        o.optString("path"),
                        o.optString("label"),
                        o.optString("verdict"),
                        o.optString("vault"),
                        o.optLong("at")
                ));
            }
        } catch (Exception ignored) {
        }
        return out;
    }

    public synchronized String quarantineFile(File src, String sha256, String verdict, String label) {
        if (src == null || !src.isFile()) return "File missing";
        if (src.getAbsolutePath().startsWith(vault.getAbsolutePath())) return "Already in vault";
        try {
            String safe = src.getName().replaceAll("[^a-zA-Z0-9._-]", "_");
            if (safe.length() > 40) safe = safe.substring(0, 40);
            String vaultName = System.currentTimeMillis() + "_" + safe + ".q";
            File dest = new File(vault, vaultName);
            copy(src, dest);
            boolean deleted = src.delete();
            JSONObject o = new JSONObject();
            o.put("sha256", sha256 == null ? "" : sha256);
            o.put("path", src.getAbsolutePath());
            o.put("label", label == null ? src.getName() : label);
            o.put("verdict", verdict == null ? "unknown" : verdict);
            o.put("vault", vaultName);
            o.put("at", System.currentTimeMillis());
            o.put("sourceDeleted", deleted);
            appendLedger(o);
            return deleted
                    ? "Quarantined + removed original"
                    : "Quarantined copy (original locked by system)";
        } catch (Exception e) {
            return "Quarantine failed: " + e.getMessage();
        }
    }

    public synchronized String restore(Entry e) {
        if (e == null) return "No entry";
        File q = new File(vault, e.vaultName);
        if (!q.isFile()) return "Vault file missing";
        try {
            File dest = new File(e.originalPath);
            File parent = dest.getParentFile();
            if (parent != null && !parent.exists()) parent.mkdirs();
            copy(q, dest);
            q.delete();
            removeLedger(e.sha256, e.vaultName);
            return "Restored to " + e.originalPath;
        } catch (Exception ex) {
            return "Restore failed: " + ex.getMessage();
        }
    }

    public synchronized String destroy(Entry e) {
        if (e == null) return "No entry";
        File q = new File(vault, e.vaultName);
        if (q.exists() && !q.delete()) return "Could not delete vault file";
        removeLedger(e.sha256, e.vaultName);
        return "Destroyed permanently";
    }

    public Intent appInfoIntent(String packageName) {
        Intent i = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
        i.setData(Uri.parse("package:" + packageName));
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        return i;
    }

    public Intent uninstallIntent(String packageName) {
        Intent i = new Intent(Intent.ACTION_DELETE);
        i.setData(Uri.parse("package:" + packageName));
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        return i;
    }

    private void appendLedger(JSONObject o) throws Exception {
        SharedPreferences sp = app.getSharedPreferences("aegis_q", Context.MODE_PRIVATE);
        JSONArray arr = new JSONArray(sp.getString("ledger", "[]"));
        arr.put(o);
        sp.edit().putString("ledger", arr.toString()).apply();
    }

    private void removeLedger(String sha, String vaultName) {
        try {
            SharedPreferences sp = app.getSharedPreferences("aegis_q", Context.MODE_PRIVATE);
            JSONArray arr = new JSONArray(sp.getString("ledger", "[]"));
            JSONArray next = new JSONArray();
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.getJSONObject(i);
                if (vaultName != null && vaultName.equals(o.optString("vault"))) continue;
                if (sha != null && !sha.isEmpty() && sha.equalsIgnoreCase(o.optString("sha256"))) continue;
                next.put(o);
            }
            sp.edit().putString("ledger", next.toString()).apply();
        } catch (Exception ignored) {
        }
    }

    private static void copy(File src, File dest) throws Exception {
        try (FileInputStream in = new FileInputStream(src);
             FileOutputStream out = new FileOutputStream(dest)) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            out.flush();
        }
    }
}
