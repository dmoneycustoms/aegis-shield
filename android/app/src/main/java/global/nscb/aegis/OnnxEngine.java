package global.nscb.aegis;

import android.content.Context;
import android.content.res.AssetManager;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtSession;

/**
 * Multi-model offline ORT stack for AEGIS (NSCB / SpiritScan lineage):
 *  - omega trust fusion
 *  - SDE stability (14-D)
 *  - novelty autoencoder (192-D recon residual)
 *  - ARK residual
 *  - Mars OOD (32x32 field) optional heavy path
 */
public final class OnnxEngine {
    private static volatile boolean inited;
    private static OrtEnvironment env;
    private static OrtSession omega, sde, novelty, ark, mars;
    private static String status = "ORT not loaded";
    private static String detail = "";

    private OnnxEngine() {}

    public static synchronized void init(Context ctx) {
        if (inited) return;
        inited = true;
        StringBuilder live = new StringBuilder();
        try {
            env = OrtEnvironment.getEnvironment();
            OrtSession.SessionOptions opts = new OrtSession.SessionOptions();
            opts.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT);
            opts.setIntraOpNumThreads(2);

            omega = open(ctx, opts, "onnx/aegis_omega.onnx", "aegis_omega.onnx");
            if (omega != null) live.append("omega ");
            sde = open(ctx, opts, "onnx/aegis_sde.onnx", "aegis_sde.onnx");
            if (sde != null) live.append("sde ");
            novelty = open(ctx, opts, "onnx/aegis_novelty.onnx", "aegis_novelty.onnx");
            if (novelty != null) live.append("novelty ");
            ark = open(ctx, opts, "onnx/aegis_ark.onnx", "aegis_ark.onnx");
            if (ark != null) live.append("ark ");
            // Mars is large — load but failures are non-fatal
            try {
                mars = open(ctx, opts, "onnx/aegis_mars_ood.onnx", "aegis_mars_ood.onnx");
                if (mars != null) live.append("mars ");
            } catch (Throwable t) {
                mars = null;
            }

            if (live.length() == 0) {
                status = "ORT fail · no sessions";
            } else {
                status = "ORT live · " + live.toString().trim() + " · on-device";
            }
            detail = status;
        } catch (Throwable t) {
            status = "ORT fail · " + t.getClass().getSimpleName();
            detail = t.getMessage() == null ? status : t.getMessage();
        }
    }

    public static String status() { return status; }
    public static String detail() { return detail; }
    public static boolean isLive() {
        return omega != null || sde != null || novelty != null;
    }

    /**
     * Full risk score 0..1 for a scan finding.
     * @param head bytes from file head (may be null)
     */
    public static float scoreFinding(int hitCount, int criticalHits, boolean hidden, int scorePct, byte[] head) {
        if (!isLive()) return Math.min(1f, scorePct / 100f);
        float heuristic = clamp(scorePct / 100f);
        float omegaRisk = runOmega(hitCount, criticalHits, hidden, scorePct);
        float sdeRisk = runSde(hitCount, criticalHits, hidden, scorePct);
        float novRisk = runNovelty(head);
        float arkRisk = runArk(hitCount, criticalHits, scorePct);
        float marsRisk = 0f;
        if (mars != null && head != null && head.length > 64) {
            marsRisk = runMars(head);
        }

        // Weighted ensemble — models reinforce, heuristic anchors
        float risk = 0.28f * heuristic
                + 0.22f * omegaRisk
                + 0.18f * sdeRisk
                + 0.16f * novRisk
                + 0.10f * arkRisk
                + 0.06f * marsRisk;
        risk = clamp(risk);
        detail = String.format(Locale.US,
                "h=%.2f ω=%.2f sde=%.2f nov=%.2f ark=%.2f mars=%.2f → %.2f",
                heuristic, omegaRisk, sdeRisk, novRisk, arkRisk, marsRisk, risk);
        status = "ORT live · ensemble · on-device";
        return risk;
    }

    public static float scoreFinding(int hitCount, int criticalHits, boolean hidden, int scorePct) {
        return scoreFinding(hitCount, criticalHits, hidden, scorePct, null);
    }

    private static float runOmega(int hits, int crit, boolean hidden, int scorePct) {
        if (omega == null || env == null) return clamp(scorePct / 100f);
        try {
            float base = clamp(1f - scorePct / 100f);
            float residual = clamp(hits / 8f);
            float adversarial = clamp(crit / 3f);
            float tGnss = hidden ? 0.35f : 0.7f;
            float anomaly = clamp(scorePct / 100f);
            Map<String, OnnxTensor> feeds = new HashMap<>();
            feeds.put("base_confidence", OnnxTensor.createTensor(env, base));
            feeds.put("qida_residual", OnnxTensor.createTensor(env, residual));
            feeds.put("adversarial_score", OnnxTensor.createTensor(env, adversarial));
            feeds.put("T_gnss", OnnxTensor.createTensor(env, tGnss));
            feeds.put("T_imu", OnnxTensor.createTensor(env, clamp(1f - scorePct / 120f)));
            feeds.put("T_rf", OnnxTensor.createTensor(env, 0.6f));
            feeds.put("T_hydro", OnnxTensor.createTensor(env, 0.5f));
            feeds.put("anomaly_score", OnnxTensor.createTensor(env, anomaly));
            try (OrtSession.Result out = omega.run(feeds)) {
                float penalty = readFloat(out, "penalty", anomaly);
                float global = readFloat(out, "global_trust", base);
                return clamp(0.5f * (1f - global) + 0.5f * penalty);
            } finally {
                closeFeeds(feeds);
            }
        } catch (Throwable t) {
            return clamp(scorePct / 100f);
        }
    }

    private static float runSde(int hits, int crit, boolean hidden, int scorePct) {
        if (sde == null || env == null) return 0f;
        try {
            float[] feat = new float[14];
            feat[0] = hits / 10f;
            feat[1] = crit / 5f;
            feat[2] = scorePct / 100f;
            feat[3] = hidden ? 1f : 0f;
            feat[4] = Math.min(1f, hits * crit / 12f);
            for (int i = 5; i < 14; i++) feat[i] = feat[i % 5] * (0.7f + 0.05f * i);
            Map<String, OnnxTensor> feeds = new HashMap<>();
            feeds.put("features", OnnxTensor.createTensor(env, feat));
            try (OrtSession.Result out = sde.run(feeds)) {
                float sComposite = readFloat(out, "s_composite", 0.5f);
                float sdeScore = readFloat(out, "sde_score", 0.5f);
                // higher stability => lower risk
                return clamp(1f - 0.5f * (sComposite + sdeScore));
            } finally {
                closeFeeds(feeds);
            }
        } catch (Throwable t) {
            return 0f;
        }
    }

    private static float runNovelty(byte[] head) {
        if (novelty == null || env == null || head == null || head.length == 0) return 0f;
        try {
            float[] x = new float[192];
            // byte histogram + raw head embedding
            int[] hist = new int[64];
            int n = Math.min(head.length, 4096);
            for (int i = 0; i < n; i++) hist[(head[i] & 0xff) % 64]++;
            for (int i = 0; i < 64; i++) x[i] = hist[i] / (float) Math.max(1, n);
            for (int i = 0; i < 128 && i < head.length; i++) x[64 + i] = (head[i] & 0xff) / 255f;
            float[][] batch = new float[][]{x};
            Map<String, OnnxTensor> feeds = new HashMap<>();
            feeds.put("x", OnnxTensor.createTensor(env, batch));
            try (OrtSession.Result out = novelty.run(feeds)) {
                float[] recon = readFloatVec(out, "recon", 192);
                double err = 0;
                for (int i = 0; i < 192; i++) {
                    double d = x[i] - (i < recon.length ? recon[i] : 0);
                    err += d * d;
                }
                err = Math.sqrt(err / 192.0);
                return clamp((float) Math.min(1.0, err * 3.0));
            } finally {
                closeFeeds(feeds);
            }
        } catch (Throwable t) {
            return 0f;
        }
    }

    private static float runArk(int hits, int crit, int scorePct) {
        if (ark == null || env == null) return 0f;
        try {
            float[] raw = new float[10];
            raw[0] = hits / 10f;
            raw[1] = crit / 5f;
            raw[2] = scorePct / 100f;
            for (int i = 3; i < 10; i++) raw[i] = raw[i % 3] * 0.8f;
            float[][] time = new float[][]{{0.5f}};
            float[][] feats = new float[][]{raw};
            Map<String, OnnxTensor> feeds = new HashMap<>();
            feeds.put("time", OnnxTensor.createTensor(env, time));
            feeds.put("raw_features", OnnxTensor.createTensor(env, feats));
            try (OrtSession.Result out = ark.run(feeds)) {
                float r = Math.abs(readFloat(out, "ark_residual", 0f));
                float s = Math.abs(readFloat(out, "sde_residual", 0f));
                return clamp(0.5f * (r + s));
            } finally {
                closeFeeds(feeds);
            }
        } catch (Throwable t) {
            return 0f;
        }
    }

    private static float runMars(byte[] head) {
        if (mars == null || env == null) return 0f;
        try {
            float[][][][] x = new float[1][1][32][32];
            for (int i = 0; i < 32 * 32; i++) {
                int b = head[i % head.length] & 0xff;
                x[0][0][i / 32][i % 32] = b / 255f;
            }
            Map<String, OnnxTensor> feeds = new HashMap<>();
            feeds.put("x", OnnxTensor.createTensor(env, x));
            try (OrtSession.Result out = mars.run(feeds)) {
                float score = readFloat(out, "score", 0f);
                return clamp(Math.abs(score));
            } finally {
                closeFeeds(feeds);
            }
        } catch (Throwable t) {
            return 0f;
        }
    }

    private static OrtSession open(Context ctx, OrtSession.SessionOptions opts, String asset, String name) throws Exception {
        File f = materialize(ctx, asset, name);
        return env.createSession(f.getAbsolutePath(), opts);
    }

    private static File materialize(Context ctx, String asset, String name) throws Exception {
        File dir = new File(ctx.getFilesDir(), "onnx");
        if (!dir.exists()) dir.mkdirs();
        File out = new File(dir, name);
        try (InputStream in = ctx.getAssets().open(asset);
             FileOutputStream fos = new FileOutputStream(out, false)) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) fos.write(buf, 0, n);
        }
        return out;
    }

    private static void closeFeeds(Map<String, OnnxTensor> feeds) {
        for (OnnxTensor t : feeds.values()) {
            try { t.close(); } catch (Exception ignored) {}
        }
    }

    private static float readFloat(OrtSession.Result out, String name, float def) {
        try {
            Object raw = out.get(name);
            if (raw instanceof java.util.Optional) {
                java.util.Optional<?> opt = (java.util.Optional<?>) raw;
                if (!opt.isPresent()) return def;
                raw = opt.get();
            }
            if (raw instanceof OnnxTensor) {
                Object val = ((OnnxTensor) raw).getValue();
                if (val instanceof Float) return (Float) val;
                if (val instanceof float[]) {
                    float[] a = (float[]) val;
                    return a.length > 0 ? a[0] : def;
                }
                if (val instanceof float[][]) {
                    float[][] a = (float[][]) val;
                    return a.length > 0 && a[0].length > 0 ? a[0][0] : def;
                }
            }
        } catch (Exception ignored) {}
        return def;
    }

    private static float[] readFloatVec(OrtSession.Result out, String name, int n) {
        float[] def = new float[n];
        try {
            Object raw = out.get(name);
            if (raw instanceof java.util.Optional) {
                java.util.Optional<?> opt = (java.util.Optional<?>) raw;
                if (!opt.isPresent()) return def;
                raw = opt.get();
            }
            if (raw instanceof OnnxTensor) {
                Object val = ((OnnxTensor) raw).getValue();
                if (val instanceof float[][]) {
                    return ((float[][]) val)[0];
                }
                if (val instanceof float[]) return (float[]) val;
            }
        } catch (Exception ignored) {}
        return def;
    }

    private static float clamp(float v) {
        if (v < 0f) return 0f;
        if (v > 1f) return 1f;
        return v;
    }
}
