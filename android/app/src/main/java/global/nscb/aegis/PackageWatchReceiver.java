package global.nscb.aegis;

import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.os.Build;

import androidx.core.app.NotificationCompat;

import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/** Lightweight check when a new package is installed. */
public final class PackageWatchReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null || intent.getData() == null) return;
        String action = intent.getAction();
        if (action == null) return;
        if (!Intent.ACTION_PACKAGE_ADDED.equals(action)
                && !Intent.ACTION_PACKAGE_REPLACED.equals(action)) return;
        if (intent.getBooleanExtra(Intent.EXTRA_REPLACING, false)
                && Intent.ACTION_PACKAGE_ADDED.equals(action)) return;

        String pkg = intent.getData().getSchemeSpecificPart();
        if (pkg == null) return;
        String low = pkg.toLowerCase(Locale.US);
        if (low.startsWith("com.android.") || low.startsWith("com.google.")
                || low.startsWith("com.samsung.") || low.startsWith("com.sec.")) {
            return;
        }
        try {
            PackageManager pm = context.getPackageManager();
            PackageInfo pi = pm.getPackageInfo(pkg, PackageManager.GET_PERMISSIONS);
            if (pi.requestedPermissions == null) return;
            Set<String> perms = new HashSet<>();
            for (String p : pi.requestedPermissions) if (p != null) perms.add(p);
            boolean sendSms = contains(perms, "SEND_SMS");
            boolean readSms = contains(perms, "READ_SMS") || contains(perms, "RECEIVE_SMS");
            boolean a11y = contains(perms, "BIND_ACCESSIBILITY_SERVICE");
            boolean install = contains(perms, "REQUEST_INSTALL_PACKAGES");
            boolean admin = contains(perms, "BIND_DEVICE_ADMIN");
            String reason = null;
            if (sendSms && readSms) reason = "New app: SMS send+read";
            else if (a11y && (admin || install)) reason = "New app: accessibility + admin/install";
            else if (admin && install) reason = "New app: device admin + installer";
            if (reason == null) return;
            String fp = "pkgadd|" + pkg + "|" + reason;
            if (!AlertDeduper.shouldNotify(context, fp)) return;
            String label = pkg;
            if (pi.applicationInfo != null) {
                label = String.valueOf(pm.getApplicationLabel(pi.applicationInfo));
            }
            notify(context, "AEGIS: " + label, reason);
        } catch (Exception ignored) {
        }
    }

    private static boolean contains(Set<String> perms, String leaf) {
        for (String p : perms) if (p.contains(leaf)) return true;
        return false;
    }

    private static void notify(Context ctx, String title, String body) {
        String ch = "aegis_global_alerts";
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel c = new NotificationChannel(ch, "AEGIS Threats", NotificationManager.IMPORTANCE_HIGH);
            NotificationManager nm = ctx.getSystemService(NotificationManager.class);
            if (nm != null) nm.createNotificationChannel(c);
        }
        NotificationCompat.Builder b = new NotificationCompat.Builder(ctx, ch)
                .setSmallIcon(android.R.drawable.ic_dialog_alert)
                .setContentTitle(title)
                .setContentText(body)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setAutoCancel(true);
        NotificationManager nm = ctx.getSystemService(NotificationManager.class);
        if (nm != null) nm.notify(title.hashCode(), b.build());
    }
}
