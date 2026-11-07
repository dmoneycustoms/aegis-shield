package global.nscb.aegis;

import android.Manifest;
import android.animation.ObjectAnimator;
import android.animation.ValueAnimator;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Bundle;
import android.os.Looper;
import android.preference.PreferenceManager;
import android.view.View;
import android.view.animation.LinearInterpolator;
import android.widget.Button;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

import org.osmdroid.config.Configuration;
import org.osmdroid.tileprovider.tilesource.TileSourceFactory;
import org.osmdroid.util.GeoPoint;
import org.osmdroid.views.MapView;
import org.osmdroid.views.overlay.Marker;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Native OSM map + GPS + dead-zone heat blooms. No WebView.
 */
public final class SignalPanelActivity extends AppCompatActivity {
    private TextView status, live, deadLog, towerLog;
    private MapView map;
    private Marker youMarker;
    private DeadZoneHeatOverlay heatOverlay;
    private View pulse;
    private LocationManager lm;
    private Location lastFix;
    private ObjectAnimator pulseAnim;
    private boolean drivingMode = true;
    private boolean followGps = true;
    private long lastHeatRefreshMs = 0L;
    private int lastDeadCount = -1;

    private final LocationListener gps = new LocationListener() {
        @Override public void onLocationChanged(@NonNull Location location) {
            try {
                lastFix = location;
                updateYouOnMap(true);
            } catch (Throwable ignored) {}
        }
        @Override public void onProviderEnabled(@NonNull String provider) {}
        @Override public void onProviderDisabled(@NonNull String provider) {}
        @Override public void onStatusChanged(String provider, int status, Bundle extras) {}
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        try {
            // osmdroid user agent + cache (required)
            Configuration.getInstance().load(
                    getApplicationContext(),
                    PreferenceManager.getDefaultSharedPreferences(getApplicationContext()));
            Configuration.getInstance().setUserAgentValue("AegisShield/2.6 (NSCB)");

            setContentView(R.layout.activity_signal_panel);

            status = findViewById(R.id.sp_status);
            live = findViewById(R.id.sp_live);
            deadLog = findViewById(R.id.sp_dead);
            towerLog = findViewById(R.id.sp_tower);
            pulse = findViewById(R.id.sp_pulse);
            map = findViewById(R.id.sp_map);

            setupMap();

            View back = findViewById(R.id.sp_back);
            if (back != null) back.setOnClickListener(v -> finish());
            View exp = findViewById(R.id.sp_export);
            if (exp != null) exp.setOnClickListener(v -> {
                try { exportReport(); } catch (Throwable ignored) {}
            });
            View drive = findViewById(R.id.sp_drive);
            if (drive != null) drive.setOnClickListener(v -> {
                drivingMode = !drivingMode;
                followGps = drivingMode;
                if (v instanceof Button) {
                    ((Button) v).setText(drivingMode ? "Drive anim ON" : "Drive anim OFF");
                }
                if (drivingMode) startPulse(); else stopPulse();
            });

            ensurePerms();
            startPulse();
            startLive();
        } catch (Throwable t) {
            try {
                TextView tv = new TextView(this);
                tv.setText("Signal panel error: " + t.getClass().getSimpleName() + "\n" + t.getMessage());
                tv.setTextColor(0xFFF0A202);
                tv.setPadding(40, 80, 40, 40);
                setContentView(tv);
            } catch (Throwable ignored) {
                finish();
            }
        }
    }

    private void setupMap() {
        if (map == null) return;
        map.setTileSource(TileSourceFactory.MAPNIK);
        map.setMultiTouchControls(true);
        map.setTilesScaledToDpi(true);
        map.getController().setZoom(15.0);
        // Richmond IN default until GPS
        GeoPoint start = new GeoPoint(39.8283, -84.8900);
        if (lastFix != null) {
            start = new GeoPoint(lastFix.getLatitude(), lastFix.getLongitude());
        }
        map.getController().setCenter(start);

        heatOverlay = new DeadZoneHeatOverlay();
        map.getOverlays().add(heatOverlay);

        youMarker = new Marker(map);
        youMarker.setPosition(start);
        youMarker.setTitle("You");
        youMarker.setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM);
        youMarker.setDefaultIcon();
        map.getOverlays().add(youMarker);

        map.invalidate();
        refreshHeat(true);
    }

    private void updateYouOnMap(boolean fromGps) {
        if (map == null || youMarker == null || lastFix == null) return;
        GeoPoint gp = new GeoPoint(lastFix.getLatitude(), lastFix.getLongitude());
        youMarker.setPosition(gp);
        if (followGps && fromGps) {
            map.getController().animateTo(gp);
        }
        map.invalidate();
        refreshHeat(false);
    }

    private void refreshHeat(boolean force) {
        if (map == null || heatOverlay == null) return;
        long now = System.currentTimeMillis();
        int n = DeadZoneTracker.all().size();
        if (!force && n == lastDeadCount && (now - lastHeatRefreshMs) < 2000) return;
        lastHeatRefreshMs = now;
        lastDeadCount = n;

        List<DeadZoneHeatOverlay.Bloom> blooms = new ArrayList<>();
        for (DeadZoneTracker.Event e : DeadZoneTracker.all()) {
            if (e == null) continue;
            float intensity = e.intensity();
            String label = (e.open ? "ACTIVE " : "") + e.serviceState
                    + " pci=" + e.lastPci + " " + e.durationSec() + "s";
            if (e.path != null && !e.path.isEmpty()) {
                for (DeadZoneTracker.TrackPoint tp : e.path) {
                    blooms.add(new DeadZoneHeatOverlay.Bloom(
                            tp.lat, tp.lon, e.bloomRadiusM(tp), intensity, e.open, label));
                }
            } else if (!Double.isNaN(e.lossLat) && !Double.isNaN(e.lossLon)) {
                float radius = Math.min(280f, Math.max(50f,
                        (e.lossAccM > 0 ? e.lossAccM * 1.8f : 45f) + e.durationSec() * 0.5f));
                blooms.add(new DeadZoneHeatOverlay.Bloom(
                        e.lossLat, e.lossLon, radius, intensity, e.open, label));
            }
        }
        heatOverlay.setBlooms(blooms);
        map.invalidate();
    }

    private void ensurePerms() {
        try {
            java.util.ArrayList<String> need = new java.util.ArrayList<>();
            if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED)
                need.add(Manifest.permission.ACCESS_FINE_LOCATION);
            if (checkSelfPermission(Manifest.permission.READ_PHONE_STATE) != PackageManager.PERMISSION_GRANTED)
                need.add(Manifest.permission.READ_PHONE_STATE);
            if (checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED
                    && android.os.Build.VERSION.SDK_INT < 29) {
                need.add(Manifest.permission.WRITE_EXTERNAL_STORAGE);
            }
            if (!need.isEmpty()) requestPermissions(need.toArray(new String[0]), 700);
            else startGps();
        } catch (Throwable ignored) {}
    }

    @Override
    public void onRequestPermissionsResult(int code, @NonNull String[] p, @NonNull int[] r) {
        super.onRequestPermissionsResult(code, p, r);
        try {
            startGps();
            startLive();
        } catch (Throwable ignored) {}
    }

    private void startGps() {
        try {
            if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED)
                return;
            lm = (LocationManager) getSystemService(LOCATION_SERVICE);
            if (lm == null) return;
            try {
                lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1500L, 5f, gps, Looper.getMainLooper());
            } catch (Throwable ignored) {}
            try {
                lm.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, 3000L, 10f, gps, Looper.getMainLooper());
            } catch (Throwable ignored) {}
            try {
                Location last = lm.getLastKnownLocation(LocationManager.GPS_PROVIDER);
                if (last == null) last = lm.getLastKnownLocation(LocationManager.NETWORK_PROVIDER);
                if (last != null) {
                    lastFix = last;
                    updateYouOnMap(true);
                }
            } catch (Throwable ignored) {}
        } catch (Throwable ignored) {}
    }

    private void startLive() {
        try {
            SignalMonitor.start(this, this::onReport);
        } catch (Throwable t) {
            if (status != null) status.setText("Signal start fail");
        }
    }

    private void onReport(SignalMonitor.Report r) {
        try {
            if (r == null) return;
            boolean hasSvc = r.level > 0 || (r.dbm != Integer.MIN_VALUE && r.dbm > -115);
            if ("unknown".equals(r.transport) && r.level <= 0) hasSvc = false;
            double lat = lastFix != null ? lastFix.getLatitude() : Double.NaN;
            double lon = lastFix != null ? lastFix.getLongitude() : Double.NaN;
            float acc = lastFix != null ? lastFix.getAccuracy() : -1f;
            try {
                int rsrp = r.rsrp;
                DeadZoneTracker.onSignal(hasSvc, false, r.cellKey, r.pci, r.rat, lat, lon, acc, rsrp);
            } catch (Throwable ignored) {}

            if (status != null) {
                status.setText(String.format(Locale.US, "LIVE · %s · %s · %s dBm · PCI %s",
                        safe(r.verdict).toUpperCase(Locale.US), safe(r.rat),
                        r.dbm == Integer.MIN_VALUE ? "?" : String.valueOf(r.dbm),
                        r.pci >= 0 ? String.valueOf(r.pci) : "?"));
            }
            if (live != null) {
                StringBuilder liveSb = new StringBuilder();
                liveSb.append(r.detail != null ? r.detail : "—");
                if (lastFix != null) {
                    liveSb.append(String.format(Locale.US, "\n\nGPS %.5f, %.5f ±%.0fm",
                            lastFix.getLatitude(), lastFix.getLongitude(), lastFix.getAccuracy()));
                }
                live.setText(liveSb.toString());
            }
            if (deadLog != null) {
                StringBuilder dead = new StringBuilder();
                DeadZoneTracker.Event open = DeadZoneTracker.open();
                if (open != null) dead.append("ACTIVE OUTAGE\n").append(open.summaryLine()).append("\n\n");
                for (DeadZoneTracker.Event e : DeadZoneTracker.all()) {
                    if (e != null) dead.append(e.summaryLine()).append("\n");
                }
                if (dead.length() == 0) dead.append("No dead-zone events yet.");
                deadLog.setText(dead.toString());
            }
            if (towerLog != null) {
                StringBuilder th = new StringBuilder();
                for (SignalMonitor.TowerEvent te : SignalMonitor.history()) {
                    if (te != null) th.append(te.line()).append("\n");
                }
                towerLog.setText(th.length() == 0 ? "Waiting for tower events…" : th.toString());
            }
            refreshHeat(false);
        } catch (Throwable ignored) {}
    }

    private static String safe(String s) {
        return s == null ? "-" : s;
    }

    private void exportReport() {
        String body = DeadZoneTracker.exportText();
        Intent send = new Intent(Intent.ACTION_SEND);
        send.setType("text/plain");
        send.putExtra(Intent.EXTRA_SUBJECT, "AEGIS Tower / Dead-Zone Report");
        send.putExtra(Intent.EXTRA_TEXT, body);
        startActivity(Intent.createChooser(send, "Export tower log"));
    }

    private void startPulse() {
        try {
            if (pulse == null) return;
            stopPulse();
            pulseAnim = ObjectAnimator.ofFloat(pulse, "alpha", 0.25f, 0.9f);
            pulseAnim.setDuration(900);
            pulseAnim.setRepeatMode(ValueAnimator.REVERSE);
            pulseAnim.setRepeatCount(ValueAnimator.INFINITE);
            pulseAnim.setInterpolator(new LinearInterpolator());
            pulseAnim.start();
        } catch (Throwable ignored) {}
    }

    private void stopPulse() {
        try {
            if (pulseAnim != null) {
                pulseAnim.cancel();
                pulseAnim = null;
            }
            if (pulse != null) pulse.setAlpha(0.4f);
        } catch (Throwable ignored) {}
    }

    @Override
    protected void onResume() {
        super.onResume();
        try {
            if (map != null) map.onResume();
        } catch (Throwable ignored) {}
    }

    @Override
    protected void onPause() {
        try {
            if (map != null) map.onPause();
        } catch (Throwable ignored) {}
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        try { SignalMonitor.stop(); } catch (Throwable ignored) {}
        stopPulse();
        try {
            if (lm != null) lm.removeUpdates(gps);
        } catch (Throwable ignored) {}
        try {
            if (map != null) map.onDetach();
        } catch (Throwable ignored) {}
        super.onDestroy();
    }
}
