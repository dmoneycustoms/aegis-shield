package global.nscb.aegis;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;

import androidx.core.app.NotificationCompat;

import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Always-on shield — quiet by default.
 * Notifications only for NEW fingerprints (AlertDeduper).
 * Long intervals to protect battery.
 */
public final class ScanService extends Service {
    public static final String ACTION_START = "global.nscb.aegis.MONITOR_START";
    public static final String ACTION_STOP = "global.nscb.aegis.MONITOR_STOP";
    private static final String CH = "aegis_monitor";
    private static final int NID = 77;

    private static final long APP_SWEEP_MS = 30 * 60 * 1000L;  // 30 min
    private static final long ALERT_MS = 6 * 60 * 60 * 1000L;  // 6 hours

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ExecutorService pool = Executors.newSingleThreadExecutor();
    private final AtomicBoolean running = new AtomicBoolean(false);
    private PowerManager.WakeLock lightLock;

    private final Runnable appSweep = new Runnable() {
        @Override public void run() {
            if (!running.get()) return;
            pool.execute(() -> {
                try { sweepApps(); } catch (Exception ignored) {}
                if (running.get()) handler.postDelayed(this, APP_SWEEP_MS);
            });
        }
    };

    private final Runnable alertSweep = new Runnable() {
        @Override public void run() {
            if (!running.get()) return;
            pool.execute(() -> {
                try { pullAlerts(); } catch (Exception ignored) {}
                if (running.get()) handler.postDelayed(this, ALERT_MS);
            });
        }
    };

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent != null ? intent.getAction() : ACTION_START;
        if (ACTION_STOP.equals(action)) {
            stopMonitor();
            stopSelf();
            return START_NOT_STICKY;
        }
        startMonitor();
        return START_STICKY;
    }

    private void startMonitor() {
        if (running.getAndSet(true)) {
            startForeground(NID, buildNotification("Shield active"));
            return;
        }
        OnnxEngine.init(this);
        ensureChannel();
        startForeground(NID, buildNotification("Shield active · " + OnnxEngine.status()));
        acquireLightLock();
        handler.removeCallbacks(appSweep);
        handler.removeCallbacks(alertSweep);
        // first app sweep delayed — avoid notify storm on boot
        handler.postDelayed(appSweep, 60_000);
        handler.postDelayed(alertSweep, 120_000);
    }

    private void stopMonitor() {
        running.set(false);
        handler.removeCallbacks(appSweep);
        handler.removeCallbacks(alertSweep);
        releaseLightLock();
        stopForeground(true);
    }

    private void acquireLightLock() {
        try {
            PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
            if (pm == null) return;
            lightLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "aegis:monitor");
            lightLock.setReferenceCounted(false);
        } catch (Exception ignored) {}
    }

    private void releaseLightLock() {
        try {
            if (lightLock != null && lightLock.isHeld()) lightLock.release();
        } catch (Exception ignored) {}
        lightLock = null;
    }

    private void withBriefLock(Runnable work) {
        try {
            if (lightLock != null) lightLock.acquire(20_000);
        } catch (Exception ignored) {}
        try { work.run(); }
        finally {
            try {
                if (lightLock != null && lightLock.isHeld()) lightLock.release();
            } catch (Exception ignored) {}
        }
    }

    private void sweepApps() {
        withBriefLock(() -> {
            PackageManager pm = getPackageManager();
            List<PackageInfo> pkgs;
            try {
                pkgs = pm.getInstalledPackages(PackageManager.GET_PERMISSIONS);
            } catch (Exception e) {
                return;
            }
            int risks = 0;
            int newRisks = 0;
            StringBuilder sample = new StringBuilder();
            for (PackageInfo pi : pkgs) {
                if (pi.applicationInfo == null || pi.packageName == null) continue;
                if (isTrustedOrSystem(pi)) continue;
                if (pi.requestedPermissions == null) continue;
                Set<String> perms = new HashSet<>();
                for (String p : pi.requestedPermissions) if (p != null) perms.add(p);
                String reason = riskReason(perms);
                if (reason == null) continue;
                risks++;
                String fp = "app|" + pi.packageName + "|" + reason;
                if (AlertDeduper.shouldNotify(this, fp)) {
                    newRisks++;
                    if (sample.length() < 160) {
                        String label = String.valueOf(pm.getApplicationLabel(pi.applicationInfo));
                        sample.append(label).append(": ").append(reason).append("  ");
                    }
                }
            }
            // Only interrupt user if something NEW appeared
            if (newRisks > 0) {
                notifyThreat("New app risk x" + newRisks,
                        sample.length() == 0 ? "Open AEGIS to review." : sample.toString());
            }
            updateNotification(risks > 0
                    ? "Shield · " + risks + " known risk app(s) · quiet"
                    : "Shield active · quiet");
        });
    }

    private void pullAlerts() {
        withBriefLock(() -> {
            try {
                List<ThreatAlertFeed.Alert> alerts = ThreatAlertFeed.fetch(null);
                for (ThreatAlertFeed.Alert a : alerts) {
                    if (!("critical".equalsIgnoreCase(a.severity) || "high".equalsIgnoreCase(a.severity))) {
                        continue;
                    }
                    String fp = "global|" + a.id + "|" + a.title;
                    if (!AlertDeduper.shouldNotify(this, fp, 3L * 24 * 60 * 60 * 1000)) continue;
                    notifyThreat("GLOBAL " + a.severity.toUpperCase(Locale.US) + ": " + a.title, a.body);
                }
            } catch (Exception ignored) {}
        });
    }

    private boolean isTrustedOrSystem(PackageInfo pi) {
        String p = pi.packageName.toLowerCase(Locale.US);
        if (p.startsWith("com.android.") || p.startsWith("android.")
                || p.startsWith("com.google.") || p.startsWith("com.samsung.")
                || p.startsWith("com.sec.") || p.startsWith("com.verizon.")
                || p.startsWith("com.qualcomm.")) {
            return true;
        }
        int flags = pi.applicationInfo.flags;
        return (flags & ApplicationInfo.FLAG_SYSTEM) != 0
                && (flags & ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) == 0;
    }

    private static String riskReason(Set<String> perms) {
        boolean sendSms = has(perms, "SEND_SMS");
        boolean readSms = has(perms, "READ_SMS") || has(perms, "RECEIVE_SMS");
        boolean contacts = has(perms, "READ_CONTACTS");
        boolean a11y = has(perms, "BIND_ACCESSIBILITY_SERVICE");
        boolean admin = has(perms, "BIND_DEVICE_ADMIN");
        boolean install = has(perms, "REQUEST_INSTALL_PACKAGES");
        boolean overlay = has(perms, "SYSTEM_ALERT_WINDOW");
        boolean boot = has(perms, "RECEIVE_BOOT_COMPLETED");
        if (sendSms && readSms && contacts) return "SMS+contacts stealer cluster";
        if (a11y && (overlay || admin || readSms)) return "Accessibility takeover pattern";
        if (admin && install) return "Admin+installer";
        if (install && boot && (sendSms || readSms)) return "Installer+boot+SMS";
        if (a11y && install) return "Accessibility+installer";
        return null;
    }

    private static boolean has(Set<String> perms, String leaf) {
        for (String p : perms) if (p != null && p.contains(leaf)) return true;
        return false;
    }

    private void ensureChannel() {
        if (Build.VERSION.SDK_INT < 26) return;
        NotificationChannel ch = new NotificationChannel(CH, "AEGIS Monitor", NotificationManager.IMPORTANCE_LOW);
        ch.setDescription("Quiet always-on shield");
        ch.setShowBadge(false);
        ch.setSound(null, null);
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (nm != null) nm.createNotificationChannel(ch);
    }

    private Notification buildNotification(String text) {
        Intent open = new Intent(this, MainActivity.class);
        PendingIntent pi = PendingIntent.getActivity(this, 0, open,
                PendingIntent.FLAG_UPDATE_CURRENT
                        | (Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_IMMUTABLE : 0));
        return new NotificationCompat.Builder(this, CH)
                .setContentTitle("AEGIS Shield")
                .setContentText(text)
                .setSmallIcon(android.R.drawable.ic_lock_lock)
                .setContentIntent(pi)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setSilent(true)
                .setPriority(NotificationCompat.PRIORITY_MIN)
                .setCategory(Notification.CATEGORY_SERVICE)
                .build();
    }

    private void updateNotification(String text) {
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (nm != null) nm.notify(NID, buildNotification(text));
    }

    private void notifyThreat(String title, String body) {
        String ch = "aegis_threats";
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel c = new NotificationChannel(ch, "AEGIS New Threats", NotificationManager.IMPORTANCE_DEFAULT);
            c.setDescription("Only new findings");
            NotificationManager nm = getSystemService(NotificationManager.class);
            if (nm != null) nm.createNotificationChannel(c);
        }
        Notification n = new NotificationCompat.Builder(this, ch)
                .setContentTitle(title)
                .setContentText(body)
                .setStyle(new NotificationCompat.BigTextStyle().bigText(body))
                .setSmallIcon(android.R.drawable.ic_dialog_alert)
                .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                .setOnlyAlertOnce(true)
                .setAutoCancel(true)
                .build();
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (nm != null) nm.notify(("t|" + title).hashCode(), n);
    }

    @Override public void onDestroy() {
        stopMonitor();
        pool.shutdownNow();
        super.onDestroy();
    }

    @Override public IBinder onBind(Intent intent) { return null; }
}
