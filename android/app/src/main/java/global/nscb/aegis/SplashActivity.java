package global.nscb.aegis;

import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

public final class SplashActivity extends AppCompatActivity {
    private final Handler handler = new Handler(Looper.getMainLooper());
    private TextView boot;
    private int step;

    private static final String[] LINES = {
            "MOUNTING TACTICAL SHELL",
            "GL RENDER PIPELINE  ... OK",
            "SIGNATURE CORE       ... OK",
            "QUARANTINE VAULT     ... OK",
            "ALWAYS-ON MONITOR    ... OK",
            "GLOBAL ALERT LINK    ... OK",
            "AEGIS OS  //  ONLINE"
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(0xFF03040A);

        GlHudView gl = new GlHudView(this);
        gl.setThreat(0.18f);
        gl.setScanning(true);
        root.addView(gl, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT));

        LinearLayout center = new LinearLayout(this);
        center.setOrientation(LinearLayout.VERTICAL);
        center.setGravity(Gravity.CENTER_HORIZONTAL);
        FrameLayout.LayoutParams lpC = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT);
        lpC.gravity = Gravity.CENTER;
        lpC.leftMargin = 28;
        lpC.rightMargin = 28;
        root.addView(center, lpC);

        TextView brand = new TextView(this);
        brand.setText("AEGIS OS");
        brand.setTextColor(0xFFF7F2E6);
        brand.setTextSize(40);
        brand.setLetterSpacing(0.42f);
        brand.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        brand.setGravity(Gravity.CENTER);
        center.addView(brand);

        TextView by = new TextView(this);
        by.setText("A PRODUCT OF");
        by.setTextColor(0xFF6E6758);
        by.setTextSize(10);
        by.setLetterSpacing(0.35f);
        by.setTypeface(android.graphics.Typeface.MONOSPACE);
        by.setGravity(Gravity.CENTER);
        by.setPadding(0, 18, 0, 4);
        center.addView(by);

        TextView co = new TextView(this);
        co.setText("NSCB GLOBAL TECHNOLOGIES");
        co.setTextColor(0xFFF0A202);
        co.setTextSize(13);
        co.setLetterSpacing(0.22f);
        co.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        co.setGravity(Gravity.CENTER);
        center.addView(co);

        boot = new TextView(this);
        boot.setTextColor(0xFF9BE8DE);
        boot.setTextSize(11);
        boot.setLetterSpacing(0.1f);
        boot.setTypeface(android.graphics.Typeface.MONOSPACE);
        boot.setGravity(Gravity.CENTER);
        boot.setPadding(0, 28, 0, 0);
        center.addView(boot);

        setContentView(root);
        tickBoot();
        handler.postDelayed(() -> {
            startActivity(new Intent(this, MainActivity.class));
            overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out);
            finish();
        }, 4500);
    }

    private void tickBoot() {
        if (step >= LINES.length) return;
        boot.setText(LINES[step]);
        step++;
        handler.postDelayed(this::tickBoot, 550);
    }

    @Override
    protected void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        super.onDestroy();
    }
}
