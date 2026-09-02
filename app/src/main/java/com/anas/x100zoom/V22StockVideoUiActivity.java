package com.anas.x100zoom;

import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.media.MediaRecorder;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Size;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Map;

/**
 * V22: video-only Vivo stock visual rebuild.
 * The 1080P/4K quality icons are extracted unchanged from the user's untouched Camera.apk.
 */
public class V22StockVideoUiActivity extends V20VideoOnlyActivity {
    private static final int ACCENT = 0xFFFFD129;
    private static final Size P720 = new Size(1280, 720);
    private static final Size FHD = new Size(1920, 1080);
    private static final Size UHD = new Size(3840, 2160);
    private static final Size K8 = new Size(7680, 4320);

    private final Handler ui22 = new Handler(Looper.getMainLooper());
    private boolean installed22;
    private TextView p720;
    private TextView p8k;
    private TextView zoom100;
    private TextView v1080;
    private TextView v4k;
    private Map<Integer, TextView> fpsButtons;

    private final Runnable watcher = new Runnable() {
        @Override public void run() {
            if (!installed22) install22();
            else sync22();
            ui22.postDelayed(this, 120L);
        }
    };

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        ui22.postDelayed(watcher, 300L);
    }

    @Override protected void onDestroy() {
        ui22.removeCallbacksAndMessages(null);
        super.onDestroy();
    }

    @SuppressWarnings("unchecked")
    private void install22() {
        if (!exactBool(V20VideoOnlyActivity.class, "installed20")) return;

        v1080 = exactField(V17HighSpeedActivity.class, "video1080_17", TextView.class);
        v4k = exactField(V17HighSpeedActivity.class, "video4k_17", TextView.class);
        fpsButtons = exactField(V17HighSpeedActivity.class, "fpsButtons17", Map.class);
        LinearLayout panel = exactField(V17HighSpeedActivity.class, "videoPanel17", LinearLayout.class);
        FrameLayout root = exactField(X300UltraUiActivity.class, "cameraRoot", FrameLayout.class);
        TextView title = exactField(X300UltraUiActivity.class, "settingsTitle", TextView.class);
        if (v1080 == null || v4k == null || fpsButtons == null || panel == null || root == null) return;

        setBoolean("photoMode", false);
        if (title != null) title.setText("Video settings");

        v1080.setText("1080P");
        v1080.setCompoundDrawablesWithIntrinsicBounds(0, R.drawable.stock_video_quality_1080p, 0, 0);
        v1080.setCompoundDrawablePadding(dp(2));

        v4k.setText("4K");
        v4k.setCompoundDrawablesWithIntrinsicBounds(0, R.drawable.stock_video_quality_4k, 0, 0);
        v4k.setCompoundDrawablePadding(dp(2));

        LinearLayout resRow = v1080.getParent() instanceof LinearLayout ? (LinearLayout) v1080.getParent() : null;
        if (resRow != null) {
            p720 = makeResolution("720P");
            p8k = makeResolution("8K");
            resRow.addView(p720, 0, new LinearLayout.LayoutParams(0, dp(52), 1f));
            resRow.addView(p8k, new LinearLayout.LayoutParams(0, dp(52), 1f));
            p720.setOnClickListener(v -> selectSize(P720));
            p8k.setOnClickListener(v -> selectSize(K8));
        }

        zoom100 = new TextView(this);
        zoom100.setText("100×");
        zoom100.setTextColor(Color.WHITE);
        zoom100.setTextSize(11f);
        zoom100.setTypeface(null, android.graphics.Typeface.BOLD);
        zoom100.setGravity(Gravity.CENTER);
        zoom100.setBackground(round(0xCC171717, 22));
        zoom100.setOnClickListener(v ->
                invokeExact(X300UltraUiActivity.class, "setUiZoom", new Class[]{float.class}, 100f));
        FrameLayout.LayoutParams zlp = new FrameLayout.LayoutParams(dp(54), dp(46));
        zlp.gravity = Gravity.BOTTOM | Gravity.END;
        zlp.rightMargin = dp(12);
        zlp.bottomMargin = dp(286);
        root.addView(zoom100, zlp);

        TextView note = exactField(V17HighSpeedActivity.class, "capabilityNote17", TextView.class);
        if (note != null) {
            note.setText("Vivo stock video UI rebuild • original 1080P/4K visuals • 24/25/30/50/60/120/240 • zoom up to 100×");
        }

        installed22 = true;
        sync22();
    }

    private TextView makeResolution(String text) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextColor(Color.WHITE);
        t.setTextSize(12f);
        t.setTypeface(null, android.graphics.Typeface.BOLD);
        t.setGravity(Gravity.CENTER);
        return t;
    }

    private void selectSize(Size wanted) {
        if (busy()) return;
        CameraCharacteristics chars = field("currentChars", CameraCharacteristics.class);
        if (!supportsOutput(chars, wanted)) {
            Toast.makeText(this, label(wanted) + " is not exposed by this active X100 video path.", Toast.LENGTH_LONG).show();
            return;
        }
        int hs = exactInt(V17HighSpeedActivity.class, "highSpeedChoice17", 0);
        if (hs >= 120) {
            Object ok = invokeExact(V17HighSpeedActivity.class, "supportsHighSpeed17",
                    new Class[]{CameraCharacteristics.class, Size.class, int.class}, chars, wanted, hs);
            if (!(ok instanceof Boolean) || !((Boolean) ok)) {
                Toast.makeText(this, label(wanted) + " is not available at " + hs + " fps.", Toast.LENGTH_LONG).show();
                return;
            }
        }
        setObject("selectedSize", wanted);
        Handler h = field("cameraHandler", Handler.class);
        if (h != null) h.post(() -> invokeAny("startPreviewSession", new Class[]{}));
        invokeExact(V16CorrectnessActivity.class, "savePreferences16", new Class[]{});
    }

    private void sync22() {
        setBoolean("photoMode", false);
        Size size = selectedSize();
        CameraCharacteristics chars = field("currentChars", CameraCharacteristics.class);

        style(v1080, size.equals(FHD), supportsOutput(chars, FHD));
        style(v4k, size.equals(UHD), supportsOutput(chars, UHD));
        style(p720, size.equals(P720), supportsOutput(chars, P720));
        style(p8k, size.equals(K8), supportsOutput(chars, K8));

        float z = floatField("requestedUiZoom", 1f);
        style(zoom100, z >= 99.5f, true);
    }

    private void style(TextView v, boolean selected, boolean enabled) {
        if (v == null) return;
        v.setEnabled(enabled);
        v.setAlpha(enabled ? 1f : 0.28f);
        v.setTextColor(selected && enabled ? Color.BLACK : (enabled ? Color.WHITE : 0xFF707070));
        v.setBackground(selected && enabled ? round(ACCENT, 8) : null);
    }

    private boolean supportsOutput(CameraCharacteristics chars, Size wanted) {
        StreamConfigurationMap map = chars == null ? null :
                chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
        Size[] sizes = map == null ? null : map.getOutputSizes(MediaRecorder.class);
        if (sizes == null) return false;
        for (Size s : sizes) if (wanted.equals(s)) return true;
        return false;
    }

    private boolean busy() {
        return boolField("recording") || boolField("recordingStarting") ||
                exactBool(V17HighSpeedActivity.class, "highSpeedRecording17");
    }

    private Size selectedSize() {
        Object o = field("selectedSize", Object.class);
        return o instanceof Size ? (Size) o : UHD;
    }

    private String label(Size s) {
        if (s.equals(K8)) return "8K";
        if (s.equals(UHD)) return "4K";
        if (s.equals(FHD)) return "1080P";
        return "720P";
    }

    private GradientDrawable round(int color, int radius) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(color);
        d.setCornerRadius(dp(radius));
        return d;
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    @SuppressWarnings("unchecked")
    private <T> T exactField(Class<?> owner, String name, Class<T> type) {
        try {
            Field f = owner.getDeclaredField(name);
            f.setAccessible(true);
            Object v = f.get(this);
            return v == null ? null : (T) v;
        } catch (Exception e) { return null; }
    }

    private boolean exactBool(Class<?> owner, String name) {
        try {
            Field f = owner.getDeclaredField(name);
            f.setAccessible(true);
            return f.getBoolean(this);
        } catch (Exception e) { return false; }
    }

    private int exactInt(Class<?> owner, String name, int fallback) {
        try {
            Field f = owner.getDeclaredField(name);
            f.setAccessible(true);
            return f.getInt(this);
        } catch (Exception e) { return fallback; }
    }

    private Object invokeExact(Class<?> owner, String name, Class<?>[] types, Object... args) {
        try {
            Method m = owner.getDeclaredMethod(name, types);
            m.setAccessible(true);
            return m.invoke(this, args);
        } catch (Exception e) { return null; }
    }

    @SuppressWarnings("unchecked")
    private <T> T field(String name, Class<T> type) {
        Class<?> c = getClass();
        while (c != null) {
            try {
                Field f = c.getDeclaredField(name);
                f.setAccessible(true);
                Object v = f.get(this);
                return v == null ? null : (T) v;
            } catch (NoSuchFieldException e) {
                c = c.getSuperclass();
            } catch (Exception e) { return null; }
        }
        return null;
    }

    private boolean boolField(String name) {
        Class<?> c = getClass();
        while (c != null) {
            try {
                Field f = c.getDeclaredField(name);
                f.setAccessible(true);
                return f.getBoolean(this);
            } catch (NoSuchFieldException e) {
                c = c.getSuperclass();
            } catch (Exception e) { return false; }
        }
        return false;
    }

    private float floatField(String name, float fallback) {
        Class<?> c = getClass();
        while (c != null) {
            try {
                Field f = c.getDeclaredField(name);
                f.setAccessible(true);
                return f.getFloat(this);
            } catch (NoSuchFieldException e) {
                c = c.getSuperclass();
            } catch (Exception e) { return fallback; }
        }
        return fallback;
    }

    private void setBoolean(String name, boolean value) {
        Class<?> c = getClass();
        while (c != null) {
            try {
                Field f = c.getDeclaredField(name);
                f.setAccessible(true);
                f.setBoolean(this, value);
                return;
            } catch (NoSuchFieldException e) {
                c = c.getSuperclass();
            } catch (Exception e) { return; }
        }
    }

    private void setObject(String name, Object value) {
        Class<?> c = getClass();
        while (c != null) {
            try {
                Field f = c.getDeclaredField(name);
                f.setAccessible(true);
                f.set(this, value);
                return;
            } catch (NoSuchFieldException e) {
                c = c.getSuperclass();
            } catch (Exception e) { return; }
        }
    }

    private Object invokeAny(String name, Class<?>[] types, Object... args) {
        Class<?> c = getClass();
        while (c != null) {
            try {
                Method m = c.getDeclaredMethod(name, types);
                m.setAccessible(true);
                return m.invoke(this, args);
            } catch (NoSuchMethodException e) {
                c = c.getSuperclass();
            } catch (Exception e) { return null; }
        }
        return null;
    }
}
