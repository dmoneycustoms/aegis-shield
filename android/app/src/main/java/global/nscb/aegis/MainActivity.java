package global.nscb.aegis;

import android.Manifest;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.view.View;
import android.os.Environment;
import android.os.Handler;
import android.os.PowerManager;
import android.os.Looper;
import android.provider.Settings;
import android.widget.Button;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.app.NotificationCompat;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class MainActivity extends AppCompatActivity {
    private static final int REQ = 42;
    private static final String CH_ALERTS = "aegis_global_alerts";

    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService pool = Executors.newSingleThreadExecutor();
    private final FileScanner scanner = new FileScanner();
    private final List<FileScanner.Finding> lastHits = new ArrayList<>();
    private final StringBuilder findingsBuf = new StringBuilder();

    private TextView status, stats, current, findings, badge;
    private GlHudView gl;
    private QuarantineManager quarantine;
    private boolean scanning;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        quarantine = new QuarantineManager(this);
        ensureAlertChannel();
        OnnxEngine.init(this);
        pool.execute(() -> {
            DefinitionUpdater.Result dr = DefinitionUpdater.updateIfStale(MainActivity.this);
            main.post(() -> {
                if (status != null && dr.ok && dr.added > 0) {
                    status.setText("DEFS +" + dr.added + " · " + SignatureDB.packVersion());
                }
            });
        });
        startShieldMonitor();
        // battery opt: user enables in system settings once — no prompt spam

        status = findViewById(R.id.status);
        stats = findViewById(R.id.stats);
        current = findViewById(R.id.current);
        findings = findViewById(R.id.findings);
        badge = findViewById(R.id.badge);
        gl = findViewById(R.id.gl_hud);

        findViewById(R.id.btn_scan).setOnClickListener(v -> startDeepScan());
        findViewById(R.id.btn_stop).setOnClickListener(v -> {
            scanner.cancel();
            scanning = false;
            status.setText("STOPPING...");
            gl.setScanning(false);
                gl.setActive(false);
        });
        findViewById(R.id.btn_apps).setOnClickListener(v -> scanInstalledApps());
        findViewById(R.id.btn_defs).setOnClickListener(v -> updateDefinitions());
        findViewById(R.id.btn_alerts).setOnClickListener(v -> loadAlerts(true));
        findViewById(R.id.btn_quarantine).setOnClickListener(v -> quarantineAllHits());
        findViewById(R.id.btn_vault).setOnClickListener(v -> showVault());
        View bs = findViewById(R.id.btn_signal);
        if (bs != null) bs.setOnClickListener(v -> startActivity(new Intent(MainActivity.this, SignalPanelActivity.class)));
        View bm = findViewById(R.id.btn_sms);
        if (bm != null) bm.setOnClickListener(v -> runSmsScan());
        View be = findViewById(R.id.btn_email);
        if (be != null) be.setOnClickListener(v -> runEmailScan());

        refreshDefStats();
        gl.setThreat(0.08f);
        gl.setScanning(false);
                gl.setActive(false);
        // silent alert refresh on open
        loadAlerts(false);
    }

    private void refreshDefStats() {
        String when = new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US)
                .format(new Date(SignatureDB.packUpdatedAt()));
        int q = quarantine.list().size();
        stats.setText(String.format(Locale.US,
                "defs %d | pack %s | vault %d | %s",
                SignatureDB.size(), SignatureDB.packVersion(), q, when));
        status.setText("IDLE | " + SignatureDB.size() + " defs | vault " + q + " | " + OnnxEngine.status());
        if (findings != null && findings.getText() != null && findings.getText().toString().startsWith("NSCB")) {
            /* keep */
        }
        badge.setText(q > 0 ? "VAULT " + q : "SHIELD");
    }

    @Override protected void onResume() {
        super.onResume();
        if (gl != null) gl.onResume();
    }

    @Override protected void onPause() {
        if (gl != null) gl.onPause();
        super.onPause();
    }

    private void updateDefinitions() {
        status.setText("UPDATING DEFINITIONS...");
        gl.setScanning(true);
        gl.setMode(1f);
        pool.execute(() -> {
            DefinitionUpdater.Result r = DefinitionUpdater.updateNow(MainActivity.this, null);
            main.post(() -> {
                gl.setScanning(false);
                gl.setActive(false);
                refreshDefStats();
                status.setText(r.ok ? "DEFINITIONS UPDATED" : "DEFINITION UPDATE FAILED");
                findings.setText((r.ok ? "OK - " : "FAIL - ") + r.message
                        + "\n\nChannel:\n" + DefinitionUpdater.DEFAULT_URL);
                gl.setThreat(r.ok ? 0.1f : 0.45f);
            });
        });
    }

    private void loadAlerts(boolean userDriven) {
        if (userDriven) status.setText("FETCHING GLOBAL ALERTS...");
        gl.setScanning(true);
        gl.setMode(0.8f);
        pool.execute(() -> {
            List<ThreatAlertFeed.Alert> loaded;
            String source;
            try {
                loaded = ThreatAlertFeed.fetch(null);
                source = "LIVE FEED";
            } catch (Exception e) {
                loaded = ThreatAlertFeed.fallback();
                String msg = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
                source = "OFFLINE FALLBACK - " + msg;
            }
            final List<ThreatAlertFeed.Alert> alerts = loaded;
            final String srcLabel = source;
            StringBuilder buf = new StringBuilder();
            buf.append("GLOBAL THREAT ALERTS - ").append(srcLabel).append("\n\n");
            float threat = 0.1f;
            int high = 0;
            for (ThreatAlertFeed.Alert a : alerts) {
                buf.append("[").append(a.severity.toUpperCase(Locale.US)).append("] ");
                buf.append(a.title).append("\n");
                buf.append(a.region).append(" - ").append(a.body).append("\n\n");
                if ("critical".equalsIgnoreCase(a.severity) || "high".equalsIgnoreCase(a.severity)) {
                    threat = Math.max(threat, 0.6f);
                    high++;
                    if (userDriven || "critical".equalsIgnoreCase(a.severity)) {
                        notifyGlobal(a.title, a.body, a.severity);
                    }
                }
            }
            final float tFinal = threat;
            final String body = buf.toString();
            final int alertCount = alerts.size();
            final int highCount = high;
            main.post(() -> {
                gl.setScanning(false);
                gl.setActive(false);
                if (userDriven || highCount > 0) {
                    status.setText("ALERTS - " + alertCount + (highCount > 0 ? " | HIGH " + highCount : ""));
                    findings.setText(body);
                }
                gl.setThreat(tFinal);
            });
        });
    }

    private void startDeepScan() {
        if (!ensureStoragePermission()) return;
        if (scanning) return;
        scanning = true;
        lastHits.clear();
        findingsBuf.setLength(0);
        findings.setText("Deep scan running...");
        status.setText("DEEP SCAN | LIVE | " + SignatureDB.size() + " defs");
        gl.setScanning(true);
        gl.setThreat(0.15f);
        status.setText("DEEP SCAN | multi-volume | " + SignatureDB.size() + " defs");
        pool.execute(() -> scanner.scanAll(MainActivity.this, new FileScanner.Listener() {
            @Override
            public void onProgress(FileScanner.ScanStats s, String path) {
                main.post(() -> {
                    current.setText(path);
                    stats.setText(String.format(Locale.US,
                            "roots %d | scanned %d | hidden %d | mal %d | sus %d | skip %d | defs %d",
                            s.roots, s.scanned, s.hidden, s.malicious, s.suspicious, s.skipped, SignatureDB.size()));
                    gl.setThreat(Math.min(1f, s.malicious * 0.25f + s.suspicious * 0.08f));
                });
            }

            @Override
            public void onHit(FileScanner.Finding f) {
                main.post(() -> {
                    lastHits.add(0, f);
                    if (lastHits.size() > 80) lastHits.remove(lastHits.size() - 1);
                    findingsBuf.insert(0, String.format(Locale.US,
                            "[%s] %s\n%s\n%s%s\n\n",
                            f.verdict.toUpperCase(Locale.US), f.topMatch, f.path, f.detail,
                            f.hidden ? " | HIDDEN" : ""));
                    if (findingsBuf.length() > 16000) findingsBuf.setLength(16000);
                    findings.setText(findingsBuf.toString());
                });
            }

            @Override
            public void onDone(FileScanner.ScanStats s) {
                main.post(() -> {
                    scanning = false;
                    gl.setScanning(false);
                gl.setActive(false);
                    status.setText(s.done
                            ? ("SCAN COMPLETE | roots " + s.roots + " | hits " + lastHits.size()
                                + (s.rootsDenied.isEmpty() ? "" : (" | denied " + s.rootsDenied.size())))
                            : "SCAN ABORTED");
                    stats.setText(String.format(Locale.US,
                            "roots %d | scanned %d | hidden %d | mal %d | sus %d | fail %d | skip %d",
                            s.roots, s.scanned, s.hidden, s.malicious, s.suspicious, s.failed, s.skipped));
                    if (findingsBuf.length() == 0) {
                        findings.setText("No findings under current definitions (" + SignatureDB.size() + ").");
                    } else {
                        findingsBuf.append("\n---\nTap QUARANTINE HITS to vault malicious/suspicious files.");
                        findings.setText(findingsBuf.toString());
                    }
                    gl.setThreat(Math.min(1f, s.malicious * 0.3f + s.suspicious * 0.1f));
                    refreshDefStats();
                });
            }
        }));
    }

    private void quarantineAllHits() {
        if (lastHits.isEmpty()) {
            status.setText("NO HITS TO QUARANTINE");
            findings.setText("Run a deep scan first. Quarantine acts on the latest finding list.");
            return;
        }
        status.setText("QUARANTINING...");
        gl.setScanning(true);
        pool.execute(() -> {
            int ok = 0, fail = 0;
            StringBuilder log = new StringBuilder();
            for (FileScanner.Finding f : new ArrayList<>(lastHits)) {
                if (!"malicious".equals(f.verdict) && !"suspicious".equals(f.verdict)) continue;
                if (quarantine.isQuarantined(f.sha256)) {
                    log.append("SKIP already vaulted\n").append(f.path).append("\n\n");
                    continue;
                }
                File file = new File(f.path);
                String msg = quarantine.quarantineFile(file, f.sha256, f.verdict, f.topMatch);
                if (msg.startsWith("Quarantine failed")) {
                    fail++;
                    log.append("FAIL ").append(msg).append("\n").append(f.path).append("\n\n");
                } else {
                    ok++;
                    log.append("OK ").append(msg).append("\n").append(f.path).append("\n\n");
                }
            }
            final int o = ok, f = fail;
            final String body = log.toString();
            main.post(() -> {
                gl.setScanning(false);
                gl.setActive(false);
                status.setText("QUARANTINE DONE | ok " + o + " | fail " + f);
                findings.setText(body.isEmpty() ? "Nothing eligible to quarantine." : body);
                refreshDefStats();
                gl.setThreat(o > 0 ? 0.2f : 0.35f);
            });
        });
    }

    private void showVault() {
        List<QuarantineManager.Entry> entries = quarantine.list();
        if (entries.isEmpty()) {
            findings.setText("Vault empty.\nPath: " + quarantine.vaultDir().getAbsolutePath());
            status.setText("VAULT EMPTY");
            return;
        }
        String[] labels = new String[entries.size()];
        for (int i = 0; i < entries.size(); i++) {
            QuarantineManager.Entry e = entries.get(i);
            labels[i] = e.verdict.toUpperCase(Locale.US) + " | " + e.label;
        }
        new AlertDialog.Builder(this)
                .setTitle("Quarantine vault (" + entries.size() + ")")
                .setItems(labels, (d, which) -> {
                    QuarantineManager.Entry e = entries.get(which);
                    new AlertDialog.Builder(this)
                            .setTitle(e.label)
                            .setMessage(e.originalPath + "\n\nsha " + e.sha256 + "\nverdict " + e.verdict)
                            .setPositiveButton("Destroy", (a, b) -> {
                                String msg = quarantine.destroy(e);
                                status.setText(msg);
                                refreshDefStats();
                                showVault();
                            })
                            .setNeutralButton("Restore", (a, b) -> {
                                String msg = quarantine.restore(e);
                                status.setText(msg);
                                refreshDefStats();
                            })
                            .setNegativeButton("Close", null)
                            .show();
                })
                .setNegativeButton("Close", null)
                .show();
        status.setText("VAULT | " + entries.size() + " sealed");
    }


    private void scanInstalledApps() {
        status.setText("APP METADATA SCAN...");
        gl.setScanning(true);
        gl.setMode(0.6f);
        pool.execute(() -> {
            PackageManager pm = getPackageManager();
            List<PackageInfo> pkgs;
            try {
                pkgs = pm.getInstalledPackages(PackageManager.GET_PERMISSIONS);
            } catch (Exception e) {
                main.post(() -> status.setText("APP SCAN FAILED - " + e.getMessage()));
                return;
            }
            final List<String> pkgHits = new ArrayList<>();
            final List<String> labels = new ArrayList<>();
            final List<String> reasons = new ArrayList<>();
            StringBuilder buf = new StringBuilder();
            int hits = 0;
            int skippedSystem = 0;
            for (PackageInfo pi : pkgs) {
                if (pi.applicationInfo == null) continue;
                // Skip pure system image apps unless they look like sideload overlays
                boolean system = (pi.applicationInfo.flags & android.content.pm.ApplicationInfo.FLAG_SYSTEM) != 0;
                boolean updatedSystem = (pi.applicationInfo.flags & android.content.pm.ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0;
                String pkg = pi.packageName == null ? "" : pi.packageName;
                if (isTrustedVendor(pkg) || (system && !updatedSystem)) {
                    skippedSystem++;
                    continue;
                }
                if (pi.requestedPermissions == null || pi.requestedPermissions.length == 0) continue;

                java.util.HashSet<String> perms = new java.util.HashSet<>();
                for (String p : pi.requestedPermissions) {
                    if (p != null) perms.add(p);
                }
                String reason = riskReason(perms, pkg);
                if (reason == null) continue;

                hits++;
                String label = String.valueOf(pm.getApplicationLabel(pi.applicationInfo));
                pkgHits.add(pkg);
                labels.add(label);
                reasons.add(reason);
                buf.append("[RISK] ").append(label).append("\n");
                buf.append(pkg).append("\n");
                buf.append(reason).append("\n\n");
            }
            final int finalHits = hits;
            final int skipped = skippedSystem;
            final String body = buf.length() == 0
                    ? "No high-risk permission clusters on non-system apps.\nSkipped trusted/system packages: " + skipped
                    : buf.toString() + "\nSkipped trusted/system: " + skipped;
            main.post(() -> {
                gl.setScanning(false);
                gl.setActive(false);
                status.setText("APP SCAN DONE - " + finalHits + " risk | skipped " + skipped);
                gl.setThreat(Math.min(1f, finalHits * 0.12f));
                findings.setText(body);
                if (!pkgHits.isEmpty()) {
                    String[] items = new String[pkgHits.size()];
                    for (int i = 0; i < pkgHits.size(); i++) {
                        items[i] = labels.get(i) + "\n" + reasons.get(i);
                    }
                    new AlertDialog.Builder(MainActivity.this)
                            .setTitle("Risk apps (" + pkgHits.size() + ")")
                            .setItems(items, (d, which) -> {
                                String pkgName = pkgHits.get(which);
                                new AlertDialog.Builder(MainActivity.this)
                                        .setTitle(labels.get(which))
                                        .setMessage(pkgName + "\n\n" + reasons.get(which))
                                        .setPositiveButton("App info", (a, b) ->
                                                startActivity(quarantine.appInfoIntent(pkgName)))
                                        .setNeutralButton("Uninstall", (a, b) ->
                                                startActivity(quarantine.uninstallIntent(pkgName)))
                                        .setNegativeButton("Close", null)
                                        .show();
                            })
                            .setNegativeButton("Close", null)
                            .show();
                }
            });
        });
    }

    /** Trusted stacks - never list as malware from permission presence alone. */
    private static boolean isTrustedVendor(String pkg) {
        String p = pkg.toLowerCase(Locale.US);
        return p.startsWith("com.android.")
                || p.startsWith("android.")
                || p.startsWith("com.google.")
                || p.startsWith("com.samsung.")
                || p.startsWith("com.sec.")
                || p.startsWith("com.verizon.")
                || p.startsWith("com.qualcomm.")
                || p.startsWith("com.mediatek.")
                || p.startsWith("com.microsoft.device")
                || p.equals("com.microsoft.appmanager");
    }

    /**
     * Only flag dangerous COMBOS - single common perms (camera, boot, contacts)
     * are normal and must not mark the whole phone.
     */
    private static String riskReason(java.util.Set<String> perms, String pkg) {
        boolean sendSms = has(perms, "SEND_SMS");
        boolean readSms = has(perms, "READ_SMS") || has(perms, "RECEIVE_SMS");
        boolean contacts = has(perms, "READ_CONTACTS");
        boolean boot = has(perms, "RECEIVE_BOOT_COMPLETED");
        boolean a11y = has(perms, "BIND_ACCESSIBILITY_SERVICE");
        boolean admin = has(perms, "BIND_DEVICE_ADMIN");
        boolean install = has(perms, "REQUEST_INSTALL_PACKAGES");
        boolean overlay = has(perms, "SYSTEM_ALERT_WINDOW");
        boolean notif = has(perms, "BIND_NOTIFICATION_LISTENER_SERVICE");
        boolean callLog = has(perms, "READ_CALL_LOG");

        // Classic stealer cluster
        if (sendSms && readSms && contacts) {
            return "SMS send+read + contacts (stealer cluster)";
        }
        if (sendSms && (contacts || callLog)) {
            return "SMS send + contacts/call log";
        }
        if (readSms && notif && boot) {
            return "SMS + notification listener + boot persistence";
        }
        // Banking malware style
        if (a11y && (overlay || admin || readSms)) {
            return "Accessibility + overlay/admin/SMS (takeover pattern)";
        }
        if (admin && install) {
            return "Device admin + install packages";
        }
        if (install && boot && (sendSms || readSms)) {
            return "Installer + boot + SMS";
        }
        if (a11y && install) {
            return "Accessibility + install packages";
        }
        // Unknown package with heavy combo
        if (!isTrustedVendor(pkg) && sendSms && boot && (overlay || a11y)) {
            return "SMS + boot + overlay/a11y on non-system app";
        }
        return null;
    }

    private static boolean has(java.util.Set<String> perms, String leaf) {
        for (String p : perms) {
            if (p.endsWith(leaf) || p.equals("android.permission." + leaf)) return true;
            if (p.contains(leaf)) return true;
        }
        return false;
    }

    private void ensureAlertChannel() {
        if (Build.VERSION.SDK_INT < 26) return;
        NotificationChannel ch = new NotificationChannel(
                CH_ALERTS, "AEGIS Global Alerts", NotificationManager.IMPORTANCE_HIGH);
        ch.setDescription("Major malware / incident bulletins");
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (nm != null) nm.createNotificationChannel(ch);
    }

    private void notifyGlobal(String title, String body, String severity) {
        try {
            String fp = "ui|" + severity + "|" + title + "|" + body;
            if (!AlertDeduper.shouldNotify(this, fp)) return;
            NotificationCompat.Builder b = new NotificationCompat.Builder(this, CH_ALERTS)
                    .setSmallIcon(android.R.drawable.ic_dialog_alert)
                    .setContentTitle("AEGIS | " + severity.toUpperCase(Locale.US) + " | " + title)
                    .setContentText(body)
                    .setStyle(new NotificationCompat.BigTextStyle().bigText(body))
                    .setPriority(NotificationCompat.PRIORITY_HIGH)
                    .setAutoCancel(true);
            NotificationManager nm = getSystemService(NotificationManager.class);
            if (nm != null) nm.notify((title + severity).hashCode(), b.build());
        } catch (Exception ignored) {
        }
    }

    private boolean ensureStoragePermission() {
        if (Build.VERSION.SDK_INT >= 30) {
            if (!Environment.isExternalStorageManager()) {
                try {
                    Intent i = new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION);
                    i.setData(Uri.parse("package:" + getPackageName()));
                    startActivity(i);
                } catch (Exception e) {
                    startActivity(new Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION));
                }
                status.setText("Grant ALL FILES access, then scan again");
                return false;
            }
            return true;
        }
        if (checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.READ_EXTERNAL_STORAGE}, REQ);
            return false;
        }
        return true;
    }

    @Override
    public void onRequestPermissionsResult(int code, @NonNull String[] p, @NonNull int[] r) {
        super.onRequestPermissionsResult(code, p, r);
        if (code == REQ) startDeepScan();
    }


    private void startShieldMonitor() {
        try {
            Intent svc = new Intent(this, ScanService.class);
            svc.setAction(ScanService.ACTION_START);
            if (Build.VERSION.SDK_INT >= 26) startForegroundService(svc);
            else startService(svc);
            if (status != null) {
                // status line may be set again by refreshDefStats
            }
        } catch (Exception e) {
            if (status != null) status.setText("Monitor start failed: " + e.getMessage());
        }
    }

    private void maybeAskBatteryOptimization() {
        if (Build.VERSION.SDK_INT < 23) return;
        try {
            PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
            if (pm == null) return;
            if (pm.isIgnoringBatteryOptimizations(getPackageName())) return;
            Intent i = new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS);
            i.setData(Uri.parse("package:" + getPackageName()));
            startActivity(i);
        } catch (Exception ignored) {
            try {
                startActivity(new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS));
            } catch (Exception ignored2) {
            }
        }
    }


    private void runSignalProbe() {
        java.util.ArrayList<String> need = new java.util.ArrayList<>();
        if (checkSelfPermission(android.Manifest.permission.READ_PHONE_STATE) != PackageManager.PERMISSION_GRANTED)
            need.add(android.Manifest.permission.READ_PHONE_STATE);
        if (checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED)
            need.add(android.Manifest.permission.ACCESS_FINE_LOCATION);
        if (!need.isEmpty()) {
            requestPermissions(need.toArray(new String[0]), 992);
            status.setText("GRANT PHONE/LOCATION FOR LIVE TOWER");
            return;
        }
        if (SignalMonitor.isLive()) {
            SignalMonitor.stop();
            status.setText("SIGNAL LIVE · STOPPED");
            gl.setScanning(false);
            return;
        }
        status.setText("SIGNAL LIVE · TOWER FEED...");
        gl.setScanning(true);
        gl.setMode(0.8f);
        SignalMonitor.start(this, r -> {
            float threat = "degraded".equals(r.verdict) ? 0.6f : ("healthy".equals(r.verdict) ? 0.08f : 0.25f);
            gl.setThreat(threat);
            status.setText("SIGNAL LIVE · " + r.verdict.toUpperCase(Locale.US)
                    + " · " + r.rat + " · " + (r.dbm == Integer.MIN_VALUE ? "?" : (r.dbm + " dBm")));
            findings.setText(r.detail + "\n\nTap Signal again to stop live feed.");
        });
    }

    private void runSmsScan() {
        if (checkSelfPermission(android.Manifest.permission.READ_SMS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{android.Manifest.permission.READ_SMS}, 991);
            status.setText("GRANT READ_SMS");
            return;
        }
        status.setText("SMS THREAT SCAN...");
        gl.setScanning(true);
        pool.execute(() -> {
            java.util.List<SmsThreatScanner.Hit> hits = SmsThreatScanner.scan(this, 400);
            StringBuilder buf = new StringBuilder();
            int bad = 0;
            for (SmsThreatScanner.Hit h : hits) {
                if ("info".equals(h.severity)) {
                    buf.append(h.body).append("\n");
                    continue;
                }
                bad++;
                buf.append("[").append(h.severity.toUpperCase(Locale.US)).append("] ")
                        .append(h.address).append("\n")
                        .append(h.match).append("\n")
                        .append(h.body).append("\n\n");
            }
            final int badF = bad;
            final String body = buf.length() == 0 ? "No SMS threats in sampled inbox." : buf.toString();
            main.post(() -> {
                gl.setScanning(false);
                gl.setThreat(Math.min(1f, badF * 0.15f));
                status.setText("SMS SCAN · " + badF + " threat(s)");
                findings.setText(body);
            });
        });
    }

    private void runEmailScan() {
        status.setText("EMAIL FILE SCAN (.eml)...");
        gl.setScanning(true);
        pool.execute(() -> {
            java.util.List<EmailThreatScanner.Hit> hits = EmailThreatScanner.scan(this, 80);
            StringBuilder buf = new StringBuilder();
            buf.append("Scans .eml/.msg on shared storage.\n")
                    .append("Full Gmail bodies need Google OAuth (not in this build).\n\n");
            for (EmailThreatScanner.Hit h : hits) {
                buf.append("[").append(h.severity.toUpperCase(Locale.US)).append("] ")
                        .append(h.match).append("\n")
                        .append(h.path).append("\n")
                        .append(h.snippet).append("\n\n");
            }
            if (hits.isEmpty()) buf.append("No mail-file threats found in Downloads/Documents.");
            final int n = hits.size();
            final String body = buf.toString();
            main.post(() -> {
                gl.setScanning(false);
                gl.setThreat(Math.min(1f, n * 0.12f));
                status.setText("EMAIL FILES · " + n + " hit(s)");
                findings.setText(body);
            });
        });
    }

}
