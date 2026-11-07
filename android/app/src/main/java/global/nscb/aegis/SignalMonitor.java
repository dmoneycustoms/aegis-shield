package global.nscb.aegis;

import android.annotation.SuppressLint;
import android.content.Context;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.telephony.CellIdentityLte;
import android.telephony.CellIdentityNr;
import android.telephony.CellInfo;
import android.telephony.CellInfoGsm;
import android.telephony.CellInfoLte;
import android.telephony.CellInfoNr;
import android.telephony.CellInfoWcdma;
import android.telephony.PhoneStateListener;
import android.telephony.SignalStrength;
import android.telephony.TelephonyCallback;
import android.telephony.TelephonyManager;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Real-time tower feed + analytic event history:
 * unexpected serving-cell changes, neighbor anomalies,
 * abrupt quality drops, suspicious transport transitions.
 */
public final class SignalMonitor {
    public interface Listener {
        void onReport(Report r);
    }

    public static final class TowerEvent {
        public final long atMs;
        public final String type;
        public final String detail;
        public final String severity; // info | watch | high

        TowerEvent(long atMs, String type, String detail, String severity) {
            this.atMs = atMs;
            this.type = type;
            this.detail = detail;
            this.severity = severity;
        }

        public String line() {
            String ts = new SimpleDateFormat("HH:mm:ss", Locale.US).format(new Date(atMs));
            return ts + " [" + severity.toUpperCase(Locale.US) + "] " + type + " — " + detail;
        }
    }

    public static final class Report {
        public String transport = "unknown";
        public int dbm = Integer.MIN_VALUE;
        public int level = -1;
        public int rsrp = Integer.MIN_VALUE;
        public int rsrq = Integer.MIN_VALUE;
        public int rssi = Integer.MIN_VALUE;
        public String rat = "-";
        public String cellKey = "-";
        public int pci = -1;
        public int tac = -1;
        public int ci = -1;
        public String operator = "-";
        public String verdict = "unknown";
        public String detail = "";
        public final List<String> flags = new ArrayList<>();
        public final List<String> neighbors = new ArrayList<>();
        public final List<String> eventLines = new ArrayList<>();
        public long atMs = System.currentTimeMillis();
    }

    private static final AtomicBoolean live = new AtomicBoolean(false);
    private static TelephonyManager tm;
    private static Object callback;
    private static Listener listener;
    private static Context appCtx;
    private static final Handler main = new Handler(Looper.getMainLooper());
    private static final CopyOnWriteArrayList<TowerEvent> events = new CopyOnWriteArrayList<>();

    private static String lastCellKey = "";
    private static long lastCellChangeAt;
    private static int lastRsrp = Integer.MIN_VALUE;
    private static int lastDbm = Integer.MIN_VALUE;
    private static int lastLevel = -1;
    private static String lastTransport = "";
    private static String lastRat = "";
    private static int lastNeighborCount = -1;
    private static long dwellStartMs;
    private static long lastMissingCellLogAt;
    private static String dwellCell = "";

    private SignalMonitor() {}

    public static boolean isLive() {
        return live.get();
    }

    public static List<TowerEvent> history() {
        return new ArrayList<>(events);
    }

    private static void pushEvent(String type, String detail, String severity) {
        TowerEvent e = new TowerEvent(System.currentTimeMillis(), type, detail, severity);
        events.add(e);
        while (events.size() > 80) events.remove(0);
    }

    @SuppressLint("MissingPermission")
    public static synchronized void start(Context ctx, Listener l) {
        listener = l;
        appCtx = ctx.getApplicationContext();
        tm = (TelephonyManager) appCtx.getSystemService(Context.TELEPHONY_SERVICE);
        if (tm == null) {
            Report r = new Report();
            r.verdict = "error";
            r.detail = "No TelephonyManager";
            emit(r);
            return;
        }
        if (live.get()) {
            emit(probe(appCtx));
            return;
        }
        live.set(true);
        pushEvent("MONITOR", "Live tower feed started", "info");
        emit(probe(appCtx));

        if (Build.VERSION.SDK_INT >= 31) {
            TelephonyCallback signalCb = new SignalCallback31(appCtx);
            callback = signalCb;
            try {
                tm.registerTelephonyCallback(appCtx.getMainExecutor(), signalCb);
            } catch (Exception e) {
                pushEvent("REGISTER", "TelephonyCallback failed: " + e.getMessage(), "watch");
            }
        } else {
            PhoneStateListener psl = new PhoneStateListener() {
                @Override
                public void onSignalStrengthsChanged(SignalStrength signalStrength) {
                    emit(probe(appCtx));
                }

                @Override
                public void onCellInfoChanged(List<CellInfo> cellInfo) {
                    emit(probe(appCtx));
                }
            };
            callback = psl;
            try {
                tm.listen(psl, PhoneStateListener.LISTEN_SIGNAL_STRENGTHS
                        | PhoneStateListener.LISTEN_CELL_INFO);
            } catch (Exception e) {
                pushEvent("REGISTER", "PhoneStateListener failed: " + e.getMessage(), "watch");
            }
        }
        main.post(pollRunnable);
    }

    private static final Runnable pollRunnable = new Runnable() {
        @Override public void run() {
            if (!live.get()) return;
            try {
                if (appCtx != null) emit(probe(appCtx));
            } catch (Exception ignored) {}
            if (live.get()) main.postDelayed(this, 3000);
        }
    };

    public static synchronized void stop() {
        live.set(false);
        main.removeCallbacks(pollRunnable);
        pushEvent("MONITOR", "Live tower feed stopped", "info");
        if (tm != null && callback != null) {
            try {
                if (Build.VERSION.SDK_INT >= 31 && callback instanceof TelephonyCallback) {
                    tm.unregisterTelephonyCallback((TelephonyCallback) callback);
                } else if (callback instanceof PhoneStateListener) {
                    tm.listen((PhoneStateListener) callback, PhoneStateListener.LISTEN_NONE);
                }
            } catch (Exception ignored) {}
        }
        callback = null;
        listener = null;
    }

    private static void emit(Report r) {
        if (r == null) return;
        for (int i = events.size() - 1; i >= 0 && r.eventLines.size() < 12; i--) {
            r.eventLines.add(0, events.get(i).line());
        }
        Listener l = listener;
        if (l != null) main.post(() -> l.onReport(r));
    }

    @SuppressLint("MissingPermission")
    public static Report probe(Context ctx) {
        Report r = new Report();
        r.atMs = System.currentTimeMillis();
        if (tm == null) {
            tm = (TelephonyManager) ctx.getSystemService(Context.TELEPHONY_SERVICE);
        }
        try {
            ConnectivityManager cm = (ConnectivityManager) ctx.getSystemService(Context.CONNECTIVITY_SERVICE);
            if (cm != null) {
                Network n = cm.getActiveNetwork();
                NetworkCapabilities caps = n != null ? cm.getNetworkCapabilities(n) : null;
                if (caps != null) {
                    if (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) r.transport = "WIFI";
                    else if (caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) r.transport = "CELLULAR";
                    else if (caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) r.transport = "ETHERNET";
                }
            }

            if (tm == null) {
                r.verdict = "error";
                r.detail = "No telephony";
                return r;
            }

            r.operator = tm.getNetworkOperatorName();
            if (r.operator == null || r.operator.isEmpty()) r.operator = tm.getSimOperatorName();
            if (r.operator == null) r.operator = "-";

            if (Build.VERSION.SDK_INT >= 28) {
                SignalStrength ss = tm.getSignalStrength();
                if (ss != null) {
                    r.level = ss.getLevel();
                    List<? extends android.telephony.CellSignalStrength> list = ss.getCellSignalStrengths();
                    if (list != null && !list.isEmpty()) r.dbm = list.get(0).getDbm();
                }
            }

            List<CellInfo> cells = null;
            try {
                cells = tm.getAllCellInfo();
            } catch (SecurityException se) {
                r.flags.add("Need LOCATION for cell tower IDs");
            }

            int nbr = 0;
            if (cells != null) {
                for (CellInfo ci : cells) {
                    if (!ci.isRegistered()) {
                        nbr++;
                        String line = formatNeighbor(ci);
                        if (line != null && r.neighbors.size() < 10) r.neighbors.add(line);
                    } else {
                        fillRegistered(r, ci);
                    }
                }
            }

            analyzeTransitions(r, nbr);
            // Dead-zone feed uses telephony service + optional GPS from panel
            boolean hasSvc = r.level > 0 || (r.dbm != Integer.MIN_VALUE && r.dbm > -120);
            if ("unknown".equals(r.transport) && r.level <= 0) hasSvc = false;
            DeadZoneTracker.noteServing(r.cellKey, r.pci, r.rat);

            if (r.flags.isEmpty() && r.level >= 3) r.verdict = "healthy";
            else if (r.flags.isEmpty()) r.verdict = "nominal";
            else r.verdict = "degraded";

            r.detail = String.format(Locale.US,
                    "%s | %s | %s | level=%d dbm=%s RSRP=%s RSRQ=%s\nPCI=%s TAC=%s CI=%s\ncell=%s",
                    r.transport, r.rat, r.operator, r.level,
                    num(r.dbm), num(r.rsrp), num(r.rsrq),
                    r.pci >= 0 ? r.pci : "?",
                    r.tac >= 0 ? r.tac : "?",
                    r.ci >= 0 ? r.ci : "?",
                    r.cellKey);
            if (!r.neighbors.isEmpty()) {
                r.detail += "\n\nNeighbors (" + r.neighbors.size() + "):\n" + String.join("\n", r.neighbors);
            }
            if (!r.flags.isEmpty()) {
                r.detail += "\n\nFlags:\n- " + String.join("\n- ", r.flags);
            }
            if (!r.eventLines.isEmpty()) {
                r.detail += "\n\nEvent history:\n" + String.join("\n", r.eventLines);
            } else if (!events.isEmpty()) {
                StringBuilder hist = new StringBuilder("\n\nEvent history:\n");
                int from = Math.max(0, events.size() - 12);
                for (int i = from; i < events.size(); i++) hist.append(events.get(i).line()).append('\n');
                r.detail += hist;
            }
        } catch (Exception e) {
            r.verdict = "error";
            r.detail = e.getMessage() == null ? "probe failed" : e.getMessage();
        }
        return r;
    }

    private static void analyzeTransitions(Report r, int neighborCount) {
        long now = r.atMs;

        // --- Unexpected serving-cell changes ---
        if (r.cellKey != null && !"-".equals(r.cellKey) && !r.cellKey.isEmpty()) {
            if (dwellCell.isEmpty()) {
                dwellCell = r.cellKey;
                dwellStartMs = now;
            }
            if (!r.cellKey.equals(lastCellKey) && !lastCellKey.isEmpty()) {
                long dwell = now - dwellStartMs;
                long since = now - lastCellChangeAt;
                String msg = lastCellKey + " → " + r.cellKey + " (dwell " + (dwell / 1000) + "s)";
                if (dwell < 8000) {
                    r.flags.add("Unexpected serving-cell change (short dwell)");
                    pushEvent("SERVING_CELL", msg + " short dwell", "high");
                } else if (since < 5000) {
                    r.flags.add("Rapid serving-cell reselection");
                    pushEvent("SERVING_CELL", msg + " rapid", "watch");
                } else {
                    pushEvent("SERVING_CELL", msg, "info");
                }
                dwellCell = r.cellKey;
                dwellStartMs = now;
            }
            lastCellKey = r.cellKey;
            lastCellChangeAt = now;
        }

        // --- Abrupt signal-quality changes ---
        if (r.rsrp != Integer.MIN_VALUE && lastRsrp != Integer.MIN_VALUE) {
            int d = r.rsrp - lastRsrp;
            if (d <= -12) {
                r.flags.add("Abrupt RSRP drop " + lastRsrp + " → " + r.rsrp + " dBm");
                pushEvent("QUALITY", "RSRP drop " + lastRsrp + " → " + r.rsrp, "high");
            } else if (d >= 12) {
                pushEvent("QUALITY", "RSRP jump " + lastRsrp + " → " + r.rsrp, "info");
            }
        }
        if (r.dbm != Integer.MIN_VALUE && lastDbm != Integer.MIN_VALUE) {
            int d = r.dbm - lastDbm;
            if (d <= -15) {
                r.flags.add("Abrupt signal drop " + lastDbm + " → " + r.dbm + " dBm");
                pushEvent("QUALITY", "dBm drop " + lastDbm + " → " + r.dbm, "high");
            }
        }
        if (lastLevel >= 3 && r.level == 0) {
            r.flags.add("Signal level collapsed to 0");
            pushEvent("QUALITY", "level " + lastLevel + " → 0", "high");
        }
        if (r.rsrp != Integer.MIN_VALUE) lastRsrp = r.rsrp;
        if (r.dbm != Integer.MIN_VALUE) lastDbm = r.dbm;
        if (r.level >= 0) lastLevel = r.level;

        // --- Unusual neighbor-cell patterns ---
        if (lastNeighborCount >= 0) {
            if (neighborCount == 0 && lastNeighborCount >= 3 && "CELLULAR".equals(r.transport)) {
                r.flags.add("Unusual neighbor pattern: neighbors vanished");
                pushEvent("NEIGHBOR", "neighbor count " + lastNeighborCount + " → 0", "watch");
            } else if (neighborCount >= 8 && lastNeighborCount <= 2) {
                r.flags.add("Unusual neighbor pattern: sudden neighbor surge");
                pushEvent("NEIGHBOR", "neighbor count " + lastNeighborCount + " → " + neighborCount, "watch");
            }
        }
        // Registered cell missing from list while on cellular
        if ("CELLULAR".equals(r.transport) && (r.cellKey == null || "-".equals(r.cellKey))) {
            r.flags.add("No registered serving cell while on cellular");
            long nowMs = System.currentTimeMillis();
            // Samsung often returns empty registered cell between measurement windows — debounce
            if (nowMs - lastMissingCellLogAt > 15000) {
                lastMissingCellLogAt = nowMs;
                pushEvent("SERVING_CELL", "missing registered cell (debounced)", "watch");
            }
        }
        lastNeighborCount = neighborCount;

        // --- Suspicious network transitions ---
        if (!lastTransport.isEmpty() && !lastTransport.equals(r.transport)) {
            String msg = lastTransport + " → " + r.transport;
            // Cellular drop to unknown/wifi flap can be normal; flag cellular→down patterns
            if ("CELLULAR".equals(lastTransport) && "WIFI".equals(r.transport)) {
                pushEvent("TRANSPORT", msg, "info");
            } else if ("WIFI".equals(lastTransport) && "CELLULAR".equals(r.transport)) {
                pushEvent("TRANSPORT", msg, "info");
            } else if ("CELLULAR".equals(lastTransport) && "unknown".equals(r.transport)) {
                r.flags.add("Suspicious network transition: cellular lost");
                pushEvent("TRANSPORT", msg + " (cellular lost)", "high");
            } else {
                pushEvent("TRANSPORT", msg, "watch");
            }
        }
        if (!lastRat.isEmpty() && !lastRat.equals(r.rat) && !"-".equals(r.rat) && !"-".equals(lastRat)) {
            String msg = lastRat + " → " + r.rat;
            // NR→GSM or LTE→GSM is unusual in healthy 5G areas
            if (("NR/5G".equals(lastRat) || "LTE".equals(lastRat)) && "GSM".equals(r.rat)) {
                r.flags.add("Suspicious RAT fallback " + msg);
                pushEvent("RAT", msg + " fallback", "high");
            } else {
                pushEvent("RAT", msg, "info");
            }
        }
        lastTransport = r.transport;
        if (r.rat != null && !"-".equals(r.rat)) lastRat = r.rat;

        // Absolute quality floors
        if (r.rsrp != Integer.MIN_VALUE && r.rsrp < -120) {
            r.flags.add("RSRP critical (" + r.rsrp + " dBm)");
        } else if (r.rsrp != Integer.MIN_VALUE && r.rsrp < -110) {
            r.flags.add("RSRP weak (" + r.rsrp + " dBm)");
        }
        if (r.level == 0) r.flags.add("Android signal level 0");
    }

    private static String num(int v) {
        return v == Integer.MIN_VALUE ? "?" : String.valueOf(v);
    }

    @SuppressLint("MissingPermission")
    private static void fillRegistered(Report r, CellInfo ci) {
        if (ci instanceof CellInfoLte) {
            CellInfoLte lte = (CellInfoLte) ci;
            CellIdentityLte id = lte.getCellIdentity();
            r.rat = "LTE";
            r.dbm = lte.getCellSignalStrength().getDbm();
            r.level = lte.getCellSignalStrength().getLevel();
            r.rsrp = lte.getCellSignalStrength().getRsrp();
            r.rsrq = lte.getCellSignalStrength().getRsrq();
            r.rssi = lte.getCellSignalStrength().getRssi();
            r.pci = id.getPci();
            r.tac = id.getTac();
            r.ci = id.getCi();
            r.cellKey = "LTE-ci" + r.ci + "-pci" + r.pci + "-tac" + r.tac;
        } else if (Build.VERSION.SDK_INT >= 29 && ci instanceof CellInfoNr) {
            CellInfoNr nr = (CellInfoNr) ci;
            r.rat = "NR/5G";
            r.dbm = nr.getCellSignalStrength().getDbm();
            r.level = nr.getCellSignalStrength().getLevel();
            CellIdentityNr id = (CellIdentityNr) nr.getCellIdentity();
            try {
                r.pci = id.getPci();
                r.tac = id.getTac();
            } catch (Exception ignored) {}
            r.cellKey = "NR-pci" + r.pci + "-tac" + r.tac;
        } else if (ci instanceof CellInfoWcdma) {
            CellInfoWcdma w = (CellInfoWcdma) ci;
            r.rat = "WCDMA";
            r.dbm = w.getCellSignalStrength().getDbm();
            r.level = w.getCellSignalStrength().getLevel();
            r.cellKey = "WCDMA-" + w.getCellIdentity().getCid();
        } else if (ci instanceof CellInfoGsm) {
            CellInfoGsm g = (CellInfoGsm) ci;
            r.rat = "GSM";
            r.dbm = g.getCellSignalStrength().getDbm();
            r.level = g.getCellSignalStrength().getLevel();
            r.cellKey = "GSM-" + g.getCellIdentity().getCid();
        }
    }

    private static String formatNeighbor(CellInfo ci) {
        try {
            if (ci instanceof CellInfoLte) {
                CellInfoLte l = (CellInfoLte) ci;
                return String.format(Locale.US, "LTE pci=%d rsrp=%d nbr",
                        l.getCellIdentity().getPci(),
                        l.getCellSignalStrength().getRsrp());
            }
            if (Build.VERSION.SDK_INT >= 29 && ci instanceof CellInfoNr) {
                CellInfoNr n = (CellInfoNr) ci;
                return String.format(Locale.US, "NR pci=%d dbm=%d nbr",
                        ((CellIdentityNr) n.getCellIdentity()).getPci(),
                        n.getCellSignalStrength().getDbm());
            }
        } catch (Exception ignored) {}
        return null;
    }

    @SuppressLint("MissingPermission")
    private static final class SignalCallback31 extends TelephonyCallback
            implements TelephonyCallback.SignalStrengthsListener,
            TelephonyCallback.CellInfoListener {
        private final Context app;

        SignalCallback31(Context app) {
            this.app = app;
        }

        @Override
        public void onSignalStrengthsChanged(SignalStrength signalStrength) {
            emit(probe(app));
        }

        @Override
        public void onCellInfoChanged(List<CellInfo> cellInfo) {
            emit(probe(app));
        }
    }
}
