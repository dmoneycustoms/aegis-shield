package global.nscb.aegis;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Dead-zone tracker with GPS path sampling for accurate heat spread.
 */
public final class DeadZoneTracker {
    public static final class TrackPoint {
        public final double lat, lon;
        public final float accM;
        public final long atMs;
        public final int rsrp; // optional, Integer.MIN_VALUE if unknown

        public TrackPoint(double lat, double lon, float accM, long atMs, int rsrp) {
            this.lat = lat;
            this.lon = lon;
            this.accM = accM;
            this.atMs = atMs;
            this.rsrp = rsrp;
        }
    }

    public static final class Event {
        public long lossAtMs;
        public long returnAtMs;
        public double lossLat = Double.NaN, lossLon = Double.NaN;
        public float lossAccM = -1f;
        public double returnLat = Double.NaN, returnLon = Double.NaN;
        public float returnAccM = -1f;
        public String lastCellKey = "-";
        public int lastPci = -1;
        public String lastRat = "-";
        public String serviceState = "unknown";
        public String firstCellAfter = "-";
        public int firstPciAfter = -1;
        public int worstRsrp = Integer.MIN_VALUE;
        public boolean open = true;
        /** GPS samples along the outage path (accurate heat corridor). */
        public final List<TrackPoint> path = new ArrayList<>();

        public long durationSec() {
            long end = returnAtMs > 0 ? returnAtMs : System.currentTimeMillis();
            return Math.max(0, (end - lossAtMs) / 1000L);
        }

        /** Radius in meters for a path sample — GPS accuracy + severity. */
        public float bloomRadiusM(TrackPoint tp) {
            float base = tp.accM > 0 ? tp.accM * 1.8f : 35f;
            // longer outage → slightly wider uncertainty band
            float durBoost = Math.min(80f, durationSec() * 0.8f);
            float rsrpBoost = 0f;
            if (tp.rsrp != Integer.MIN_VALUE && tp.rsrp < -110) {
                rsrpBoost = Math.min(60f, (-110 - tp.rsrp) * 2.5f);
            }
            return Math.min(280f, Math.max(40f, base + durBoost * 0.35f + rsrpBoost));
        }

        public float intensity() {
            float i = 0.4f;
            long d = durationSec();
            if (d >= 5) i += 0.15f;
            if (d >= 15) i += 0.15f;
            if (d >= 45) i += 0.15f;
            if (open) i += 0.2f;
            if (worstRsrp != Integer.MIN_VALUE && worstRsrp < -115) i += 0.1f;
            return Math.min(1f, i);
        }

        public String summaryLine() {
            SimpleDateFormat tf = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US);
            String loss = tf.format(new Date(lossAtMs));
            String ret = returnAtMs > 0 ? tf.format(new Date(returnAtMs)) : "OPEN";
            return String.format(Locale.US,
                    "[%s → %s] %ds | %s | last %s pci=%d | after %s pci=%d | pts=%d | lossGPS %.5f,%.5f ±%.0fm | retGPS %.5f,%.5f ±%.0fm | worstRSRP %s",
                    loss, ret, durationSec(), serviceState, lastCellKey, lastPci,
                    firstCellAfter, firstPciAfter, path.size(),
                    lossLat, lossLon, lossAccM, returnLat, returnLon, returnAccM,
                    worstRsrp == Integer.MIN_VALUE ? "?" : String.valueOf(worstRsrp));
        }
    }

    private static final CopyOnWriteArrayList<Event> EVENTS = new CopyOnWriteArrayList<>();
    private static Event openEvent;
    private static String lastKnownCell = "-";
    private static int lastKnownPci = -1;
    private static String lastKnownRat = "-";
    private static long lossCandidateAt = 0L;
    private static double lossCandLat = Double.NaN, lossCandLon = Double.NaN;
    private static float lossCandAcc = -1f;
    private static int lossCandRsrp = Integer.MIN_VALUE;
    private static final long MIN_OUTAGE_MS = 2500L; // ignore sub-2.5s glitches
    private static final float MIN_PATH_MOVE_M = 8f;

    private DeadZoneTracker() {}

    public static List<Event> all() {
        return new ArrayList<>(EVENTS);
    }

    public static Event open() {
        return openEvent;
    }

    public static void noteServing(String cellKey, int pci, String rat) {
        if (cellKey != null && !cellKey.isEmpty() && !"-".equals(cellKey)) {
            lastKnownCell = cellKey;
            lastKnownPci = pci;
            lastKnownRat = rat != null ? rat : "-";
        }
    }

    public static synchronized void onSignal(
            boolean hasService,
            boolean emergencyOnly,
            String cellKey,
            int pci,
            String rat,
            double lat,
            double lon,
            float accM
    ) {
        onSignal(hasService, emergencyOnly, cellKey, pci, rat, lat, lon, accM, Integer.MIN_VALUE);
    }

    public static synchronized void onSignal(
            boolean hasService,
            boolean emergencyOnly,
            String cellKey,
            int pci,
            String rat,
            double lat,
            double lon,
            float accM,
            int rsrp
    ) {
        noteServing(cellKey, pci, rat);
        long now = System.currentTimeMillis();
        boolean outage = !hasService || emergencyOnly;

        if (outage) {
            if (openEvent == null) {
                // debounce: wait MIN_OUTAGE_MS before committing an event
                if (lossCandidateAt == 0L) {
                    lossCandidateAt = now;
                    lossCandLat = lat;
                    lossCandLon = lon;
                    lossCandAcc = accM;
                    lossCandRsrp = rsrp;
                    return;
                }
                if (now - lossCandidateAt < MIN_OUTAGE_MS) {
                    // keep best accuracy sample while waiting
                    if (accM > 0 && (lossCandAcc < 0 || accM < lossCandAcc) && lat == lat) {
                        lossCandLat = lat;
                        lossCandLon = lon;
                        lossCandAcc = accM;
                    }
                    if (rsrp != Integer.MIN_VALUE
                            && (lossCandRsrp == Integer.MIN_VALUE || rsrp < lossCandRsrp)) {
                        lossCandRsrp = rsrp;
                    }
                    return;
                }
                // commit
                Event e = new Event();
                e.lossAtMs = lossCandidateAt;
                if (lossCandLat == lossCandLat && lossCandLon == lossCandLon) {
                    e.lossLat = lossCandLat;
                    e.lossLon = lossCandLon;
                } else if (lat == lat && lon == lon) {
                    e.lossLat = lat;
                    e.lossLon = lon;
                }
                e.lossAccM = lossCandAcc > 0 ? lossCandAcc : accM;
                e.lastCellKey = lastKnownCell;
                e.lastPci = lastKnownPci;
                e.lastRat = lastKnownRat;
                e.serviceState = emergencyOnly ? "emergency" : "noservice";
                e.open = true;
                e.worstRsrp = lossCandRsrp;
                addPath(e, e.lossLat, e.lossLon, e.lossAccM, e.lossAtMs, lossCandRsrp);
                if (lat == lat && lon == lon) {
                    addPath(e, lat, lon, accM, now, rsrp);
                }
                openEvent = e;
                EVENTS.add(0, e);
                while (EVENTS.size() > 80) EVENTS.remove(EVENTS.size() - 1);
                lossCandidateAt = 0L;
            } else {
                if (rsrp != Integer.MIN_VALUE
                        && (openEvent.worstRsrp == Integer.MIN_VALUE || rsrp < openEvent.worstRsrp)) {
                    openEvent.worstRsrp = rsrp;
                }
                if (lat == lat && lon == lon) {
                    addPath(openEvent, lat, lon, accM, now, rsrp);
                    if (accM > 0 && (openEvent.lossAccM < 0 || accM < openEvent.lossAccM)) {
                        openEvent.lossAccM = accM;
                    }
                }
            }
            return;
        }

        // service back
        lossCandidateAt = 0L;
        if (openEvent != null && openEvent.open) {
            openEvent.returnAtMs = now;
            if (lat == lat && lon == lon) {
                openEvent.returnLat = lat;
                openEvent.returnLon = lon;
                openEvent.returnAccM = accM;
                addPath(openEvent, lat, lon, accM, now, rsrp);
            }
            openEvent.firstCellAfter = cellKey != null ? cellKey : "-";
            openEvent.firstPciAfter = pci;
            openEvent.open = false;
            // drop ultra-short residual noise
            if (openEvent.durationSec() < 2 && openEvent.path.size() < 2) {
                EVENTS.remove(openEvent);
            }
            openEvent = null;
        }
    }

    private static void addPath(Event e, double lat, double lon, float accM, long atMs, int rsrp) {
        if (lat != lat || lon != lon) return;
        if (!e.path.isEmpty()) {
            TrackPoint last = e.path.get(e.path.size() - 1);
            float[] d = new float[1];
            android.location.Location.distanceBetween(last.lat, last.lon, lat, lon, d);
            if (d[0] < MIN_PATH_MOVE_M && (atMs - last.atMs) < 3000L) {
                // same spot — keep tighter accuracy if better
                if (accM > 0 && accM < last.accM) {
                    e.path.set(e.path.size() - 1, new TrackPoint(lat, lon, accM, atMs, rsrp));
                }
                return;
            }
        }
        e.path.add(new TrackPoint(lat, lon, accM > 0 ? accM : 40f, atMs, rsrp));
        while (e.path.size() > 64) e.path.remove(0);
        if (Double.isNaN(e.lossLat)) {
            e.lossLat = lat;
            e.lossLon = lon;
        }
    }

    public static String exportText() {
        StringBuilder sb = new StringBuilder();
        sb.append("AEGIS Dead-Zone / Tower Export\n");
        sb.append("Generated ").append(new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
                .format(new Date())).append("\n\n");
        sb.append("=== DEAD ZONES ===\n");
        if (EVENTS.isEmpty()) {
            sb.append("(none recorded)\n");
        } else {
            for (Event e : EVENTS) {
                sb.append(e.summaryLine()).append("\n");
                for (TrackPoint tp : e.path) {
                    sb.append(String.format(Locale.US,
                            "    path %.6f,%.6f ±%.0fm rsrp=%s\n",
                            tp.lat, tp.lon, tp.accM,
                            tp.rsrp == Integer.MIN_VALUE ? "?" : String.valueOf(tp.rsrp)));
                }
            }
        }
        sb.append("\n=== TOWER EVENT LOG ===\n");
        for (SignalMonitor.TowerEvent te : SignalMonitor.history()) {
            sb.append(te.line()).append("\n");
        }
        return sb.toString();
    }
}
