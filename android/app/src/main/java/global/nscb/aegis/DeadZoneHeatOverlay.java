package global.nscb.aegis;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Point;
import android.graphics.RadialGradient;
import android.graphics.Shader;

import org.osmdroid.util.GeoPoint;
import org.osmdroid.views.MapView;
import org.osmdroid.views.Projection;
import org.osmdroid.views.overlay.Overlay;

import java.util.ArrayList;
import java.util.List;

/**
 * Path-accurate heat: one bloom per GPS sample along each dead-zone corridor.
 * Radius driven by measured GPS accuracy + outage severity.
 */
public final class DeadZoneHeatOverlay extends Overlay {
    public static final class Bloom {
        public final double lat, lon;
        public final float radiusM;
        public final float intensity; // 0..1
        public final boolean open;
        public final String label;

        public Bloom(double lat, double lon, float radiusM, float intensity, boolean open, String label) {
            this.lat = lat;
            this.lon = lon;
            this.radiusM = radiusM;
            this.intensity = Math.max(0.15f, Math.min(1f, intensity));
            this.open = open;
            this.label = label != null ? label : "Dead zone";
        }
    }

    private final List<Bloom> blooms = new ArrayList<>();
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint ring = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Point scratch = new Point();

    public DeadZoneHeatOverlay() {
        fill.setStyle(Paint.Style.FILL);
        ring.setStyle(Paint.Style.STROKE);
        ring.setStrokeWidth(2f);
    }

    public synchronized void setBlooms(List<Bloom> next) {
        blooms.clear();
        if (next != null) blooms.addAll(next);
    }

    @Override
    public void draw(Canvas canvas, MapView mapView, boolean shadow) {
        if (shadow || mapView == null || canvas == null) return;
        Projection proj = mapView.getProjection();
        if (proj == null) return;

        List<Bloom> snap;
        synchronized (this) {
            snap = new ArrayList<>(blooms);
        }
        // draw low intensity first so hot cores sit on top
        for (int pass = 0; pass < 2; pass++) {
            for (Bloom b : snap) {
                if (Double.isNaN(b.lat) || Double.isNaN(b.lon)) continue;
                boolean hot = b.intensity >= 0.65f || b.open;
                if (pass == 0 && hot) continue;
                if (pass == 1 && !hot) continue;

                proj.toPixels(new GeoPoint(b.lat, b.lon), scratch);
                float pxPerM = metersToPixels(mapView, b.lat);
                float r = Math.max(18f, b.radiusM * pxPerM);

                int alphaCore = (int) (90 + b.intensity * 130);
                int alphaMid = (int) (40 + b.intensity * 70);
                int cr = b.open ? 255 : 224;
                int cg = b.open ? 42 : 69;
                int cb = b.open ? 42 : 42;
                int core = (alphaCore << 24) | (cr << 16) | (cg << 8) | cb;
                int mid = (alphaMid << 24) | (cr << 16) | (cg << 8) | cb;
                int edge = 0x00E0452A;

                RadialGradient grad = new RadialGradient(
                        scratch.x, scratch.y, r,
                        new int[]{core, mid, edge},
                        new float[]{0f, 0.5f, 1f},
                        Shader.TileMode.CLAMP
                );
                fill.setShader(grad);
                canvas.drawCircle(scratch.x, scratch.y, r, fill);
                fill.setShader(null);

                if (hot) {
                    ring.setColor(0xCCFFB4A0);
                    canvas.drawCircle(scratch.x, scratch.y, Math.max(6f, r * 0.28f), ring);
                }
            }
        }
    }

    private static float metersToPixels(MapView map, double lat) {
        double z = map.getZoomLevelDouble();
        double mPerPx = Math.cos(Math.toRadians(lat)) * 40075016.686
                / (256.0 * Math.pow(2.0, z));
        if (mPerPx < 0.05) mPerPx = 0.05;
        return (float) (1.0 / mPerPx);
    }
}
