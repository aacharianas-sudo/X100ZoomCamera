package com.anas.x100zoom;

import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.media.MediaRecorder;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Range;
import android.util.Size;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.PathInterpolator;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

/**
 * V15 mode-specific camera UI cleanup.
 *
 * The V13/V14 shell is retained, but PHOTO and VIDEO no longer share the same
 * settings contents. VIDEO has real resolution/FPS/stabilization controls and
 * never shows the photo ratio/timer rows. Legacy top badges are physically
 * detached so old V12 watchers cannot make them ghost through the new UI.
 */
public class V15VideoUiActivity extends V14PolishActivity {
    private static final int ACCENT = 0xFFFFD129;
    private static final int PANEL = 0xFF050505;
    private static final int TILE = 0xFF292929;
    private static final int DISABLED = 0xFF777777;

    private final Handler v15 = new Handler(Looper.getMainLooper());

    private FrameLayout settingsSheet15;
    private LinearLayout videoPanel;
    private TextView configPill15;
    private View zoomStrip15;
    private TextView video1080;
    private TextView video4k;
    private TextView fps30;
    private TextView fps60;
    private TextView videoGrid;
    private TextView videoFocus;
    private TextView videoStab;
    private TextView videoFlash;

    private boolean installed15 = false;
    private boolean lastPhoto15 = true;
    private boolean lastSettings15 = false;
    private boolean videoStabilizationEnabled = true;
    private long lastStabApplyMs = 0L;

    private final Runnable watcher15 = new Runnable() {
        @Override public void run() {
            if (installed15) syncV15();
            v15.postDelayed(this, 80L);
        }
    };

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        v15.postDelayed(this::installV15, 3250L);
        v15.postDelayed(watcher15, 3400L);
    }

    @Override protected void onDestroy() {
        v15.removeCallbacksAndMessages(null);
        super.onDestroy();
    }

    private int dp15(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private GradientDrawable rounded15(int color, int radiusDp) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(color);
        d.setCornerRadius(dp15(radiusDp));
        return d;
    }

    private void installV15() {
        settingsSheet15 = field15("settingsSheet", FrameLayout.class);
        configPill15 = field15("configPill", TextView.class);
        zoomStrip15 = field15("zoomStrip", View.class);
        if (settingsSheet15 == null) return;

        // V14 exposed a nearly opaque sheet. Make it fully opaque so no camera/photo
        // controls can bleed through while settings are open.
        settingsSheet15.setBackgroundColor(PANEL);

        detachLegacyTopView("modeBadge");
        detachLegacyTopView("ratioButton");
        detachLegacyTopView("photoTopRow");
        hideLegacyPanel("photoPanel");

        buildVideoSettingsPanel();
        polishZoomTouchAnimation();

        lastPhoto15 = bool15("photoMode");
        lastSettings15 = bool15("settingsOpen");
        installed15 = true;
        syncV15();
    }

    /** Remove old top widgets from the hierarchy, not merely alpha=0. */
    private void detachLegacyTopView(String fieldName) {
        View v = field15(fieldName, View.class);
        if (v != null && v.getParent() instanceof ViewGroup) {
            try { ((ViewGroup) v.getParent()).removeView(v); } catch (Exception ignored) {}
        }
    }

    private void hideLegacyPanel(String fieldName) {
        View v = field15(fieldName, View.class);
        if (v != null) v.setVisibility(View.GONE);
    }

    private void buildVideoSettingsPanel() {
        videoPanel = new LinearLayout(this);
        videoPanel.setOrientation(LinearLayout.VERTICAL);
        videoPanel.setPadding(dp15(18), dp15(8), dp15(18), dp15(12));
        videoPanel.setBackgroundColor(PANEL);
        videoPanel.setElevation(dp15(70));
        videoPanel.setVisibility(View.GONE);

        TextView resolutionLabel = sectionLabel("Resolution");
        videoPanel.addView(resolutionLabel, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp15(28)));

        LinearLayout resolution = segmentedRow();
        video1080 = segment("1080P");
        video4k = segment("4K");
        resolution.addView(video1080, new LinearLayout.LayoutParams(0, dp15(52), 1f));
        resolution.addView(video4k, new LinearLayout.LayoutParams(0, dp15(52), 1f));
        videoPanel.addView(resolution, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp15(52)));

        TextView frameLabel = sectionLabel("Frame rate");
        LinearLayout.LayoutParams flp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp15(28));
        flp.topMargin = dp15(8);
        videoPanel.addView(frameLabel, flp);

        LinearLayout frameRate = segmentedRow();
        fps30 = segment("30 fps");
        fps60 = segment("60 fps");
        frameRate.addView(fps30, new LinearLayout.LayoutParams(0, dp15(52), 1f));
        frameRate.addView(fps60, new LinearLayout.LayoutParams(0, dp15(52), 1f));
        videoPanel.addView(frameRate, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp15(52)));

        LinearLayout tiles = new LinearLayout(this);
        tiles.setOrientation(LinearLayout.HORIZONTAL);
        tiles.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp15(92));
        tlp.topMargin = dp15(16);
        videoPanel.addView(tiles, tlp);

        videoGrid = tile("Grid lines");
        videoFocus = tile("Focus\nAuto");
        videoStab = tile("Stabilization\nOn");
        videoFlash = tile("Flash");
        tiles.addView(videoGrid, tileLp());
        tiles.addView(videoFocus, tileLp());
        tiles.addView(videoStab, tileLp());
        tiles.addView(videoFlash, tileLp());

        TextView note = new TextView(this);
        note.setText("60 fps is selectable only when the active X100 camera path exposes a fixed 60 fps Camera2 range.");
        note.setTextColor(0xFF8B8B8B);
        note.setTextSize(10f);
        note.setGravity(Gravity.CENTER);
        note.setPadding(dp15(8), dp15(5), dp15(8), 0);
        videoPanel.addView(note, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp15(42)));

        video1080.setOnClickListener(v -> selectResolution(false));
        video4k.setOnClickListener(v -> selectResolution(true));
        fps30.setOnClickListener(v -> selectFrameRate(30));
        fps60.setOnClickListener(v -> selectFrameRate(60));
        videoGrid.setOnClickListener(v -> invoke15("toggleGrid", new Class[]{}));
        videoFocus.setOnClickListener(v -> toggleFocusReal());
        videoStab.setOnClickListener(v -> toggleVideoStabilization());
        videoFlash.setOnClickListener(v -> invoke15("toggleFlash", new Class[]{}));

        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, dp15(342));
        lp.gravity = Gravity.BOTTOM;
        lp.bottomMargin = dp15(12);
        settingsSheet15.addView(videoPanel, lp);
    }

    private TextView sectionLabel(String text) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextColor(0xFFBDBDBD);
        t.setTextSize(11f);
        t.setGravity(Gravity.CENTER_VERTICAL);
        return t;
    }

    private LinearLayout segmentedRow() {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER);
        row.setPadding(dp15(2), dp15(2), dp15(2), dp15(2));
        row.setBackground(rounded15(0xFF262626, 10));
        return row;
    }

    private TextView segment(String text) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextColor(Color.WHITE);
        t.setTextSize(14f);
        t.setTypeface(null, android.graphics.Typeface.BOLD);
        t.setGravity(Gravity.CENTER);
        t.setClickable(true);
        t.setFocusable(true);
        pressFeedback(t);
        return t;
    }

    private TextView tile(String text) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextColor(Color.WHITE);
        t.setTextSize(11.5f);
        t.setTypeface(null, android.graphics.Typeface.BOLD);
        t.setGravity(Gravity.CENTER);
        t.setPadding(dp15(4), dp15(6), dp15(4), dp15(6));
        t.setBackground(rounded15(TILE, 10));
        t.setClickable(true);
        t.setFocusable(true);
        pressFeedback(t);
        return t;
    }

    private LinearLayout.LayoutParams tileLp() {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, dp15(86), 1f);
        p.setMargins(dp15(4), dp15(3), dp15(4), dp15(3));
        return p;
    }

    private void pressFeedback(View view) {
        view.setOnTouchListener((v, e) -> {
            if (e.getActionMasked() == MotionEvent.ACTION_DOWN) {
                v.animate().scaleX(0.965f).scaleY(0.965f).alpha(0.72f).setDuration(65L).start();
            } else if (e.getActionMasked() == MotionEvent.ACTION_UP ||
                    e.getActionMasked() == MotionEvent.ACTION_CANCEL) {
                v.animate().scaleX(1f).scaleY(1f).alpha(1f).setDuration(130L)
                        .setInterpolator(new PathInterpolator(0.15f, 0.75f, 0.25f, 1f)).start();
            }
            return false;
        });
    }

    private void polishZoomTouchAnimation() {
        if (zoomStrip15 == null) return;
        zoomStrip15.setLayerType(View.LAYER_TYPE_HARDWARE, null);
        zoomStrip15.setOnTouchListener((v, e) -> {
            if (e.getActionMasked() == MotionEvent.ACTION_DOWN) {
                v.animate().scaleY(1.035f).setDuration(80L).start();
            } else if (e.getActionMasked() == MotionEvent.ACTION_UP ||
                    e.getActionMasked() == MotionEvent.ACTION_CANCEL) {
                v.animate().scaleY(1f).setDuration(170L)
                        .setInterpolator(new PathInterpolator(0.18f, 0.72f, 0.18f, 1f)).start();
            }
            // Do not consume: X300UltraUiActivity keeps the real smooth zoom handling.
            return false;
        });
    }

    private void syncV15() {
        boolean photo = bool15("photoMode");
        boolean settings = bool15("settingsOpen");

        // Do not show video resolution/FPS as a persistent top-left/top-row badge.
        if (configPill15 != null) {
            configPill15.setVisibility(photo ? View.VISIBLE : View.GONE);
        }

        if (videoPanel != null) {
            boolean showVideoPanel = settings && !photo;
            if (showVideoPanel && videoPanel.getVisibility() != View.VISIBLE) {
                videoPanel.setAlpha(0f);
                videoPanel.setTranslationY(-dp15(8));
                videoPanel.setVisibility(View.VISIBLE);
                videoPanel.animate().alpha(1f).translationY(0f).setDuration(190L)
                        .setInterpolator(new PathInterpolator(0.18f, 0.78f, 0.22f, 1f)).start();
            } else if (!showVideoPanel && videoPanel.getVisibility() == View.VISIBLE) {
                videoPanel.setVisibility(View.GONE);
            }
        }

        if (photo != lastPhoto15) {
            lastPhoto15 = photo;
            if (!photo) updateVideoControls();
        }
        if (settings != lastSettings15) {
            lastSettings15 = settings;
            if (settings && !photo) updateVideoControls();
        }
        if (!photo) {
            updateVideoControls();
            long now = android.os.SystemClock.elapsedRealtime();
            if (now - lastStabApplyMs > 550L) {
                lastStabApplyMs = now;
                applyVideoStabilization();
            }
        }
    }

    private void updateVideoControls() {
        if (video1080 == null) return;
        CameraCharacteristics chars = field15("currentChars", CameraCharacteristics.class);
        Size current = object15("selectedSize") instanceof Size
                ? (Size) object15("selectedSize") : new Size(3840, 2160);
        int currentFps = int15("selectedFps", 30);
        boolean uhd = current.getWidth() >= 3800;

        styleSegment(video1080, !uhd, supportsMode(chars, new Size(1920, 1080), currentFps));
        styleSegment(video4k, uhd, supportsMode(chars, new Size(3840, 2160), currentFps));
        styleSegment(fps30, currentFps <= 30, supportsMode(chars, current, 30));
        styleSegment(fps60, currentFps >= 60, supportsMode(chars, current, 60));

        boolean grid = bool15("gridEnabled");
        boolean manual = bool15("manualMode");
        boolean flash = bool15("flashEnabled");
        styleTile(videoGrid, grid, true);
        videoFocus.setText(manual ? "Focus\nManual" : "Focus\nAuto");
        styleTile(videoFocus, manual, true);
        styleTile(videoFlash, flash, true);

        boolean stabSupported = supportsVideoStabilization(chars);
        videoStab.setText(stabSupported
                ? (videoStabilizationEnabled ? "Stabilization\nOn" : "Stabilization\nOff")
                : "Stabilization\nOIS only");
        styleTile(videoStab, stabSupported && videoStabilizationEnabled, stabSupported);
    }

    private void styleSegment(TextView t, boolean selected, boolean supported) {
        if (t == null) return;
        t.setEnabled(supported);
        t.setAlpha(supported ? 1f : 0.35f);
        t.setTextColor(selected && supported ? Color.BLACK : (supported ? Color.WHITE : DISABLED));
        t.setBackground(selected && supported ? rounded15(ACCENT, 8) : null);
    }

    private void styleTile(TextView t, boolean active, boolean enabled) {
        if (t == null) return;
        t.setEnabled(enabled);
        t.setAlpha(enabled ? 1f : 0.42f);
        t.setTextColor(active && enabled ? Color.BLACK : (enabled ? Color.WHITE : DISABLED));
        t.setBackground(rounded15(active && enabled ? ACCENT : TILE, 10));
    }

    private void selectResolution(boolean want4k) {
        if (bool15("recording") || bool15("recordingStarting")) return;
        CameraCharacteristics chars = field15("currentChars", CameraCharacteristics.class);
        Size wanted = want4k ? new Size(3840, 2160) : new Size(1920, 1080);
        int fps = int15("selectedFps", 30);
        if (!supportsMode(chars, wanted, fps)) {
            int fallbackFps = supportsMode(chars, wanted, 30) ? 30 : (supportsMode(chars, wanted, 60) ? 60 : -1);
            if (fallbackFps < 0) {
                Toast.makeText(this, "That resolution is not exposed by the active camera path.", Toast.LENGTH_SHORT).show();
                return;
            }
            fps = fallbackFps;
        }
        applyVideoMode(wanted, fps);
    }

    private void selectFrameRate(int fps) {
        if (bool15("recording") || bool15("recordingStarting")) return;
        CameraCharacteristics chars = field15("currentChars", CameraCharacteristics.class);
        Size current = object15("selectedSize") instanceof Size
                ? (Size) object15("selectedSize") : new Size(3840, 2160);
        if (!supportsMode(chars, current, fps)) {
            Toast.makeText(this,
                    fps == 60 ? "Fixed 60 fps is not exposed for this active lens/resolution."
                            : "30 fps is not exposed for this active lens/resolution.",
                    Toast.LENGTH_SHORT).show();
            return;
        }
        applyVideoMode(current, fps);
    }

    private void applyVideoMode(Size size, int fps) {
        setObject15("selectedSize", size);
        setInt15("selectedFps", fps);
        invoke15("updateModeBadge", new Class[]{});
        Handler cameraHandler = field15("cameraHandler", Handler.class);
        if (cameraHandler != null) {
            cameraHandler.post(() -> invoke15("startPreviewSession", new Class[]{}));
        }
        updateVideoControls();
    }

    private boolean supportsMode(CameraCharacteristics chars, Size size, int fps) {
        if (chars == null) return false;
        StreamConfigurationMap map = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
        if (map == null || !hasSize(map.getOutputSizes(MediaRecorder.class), size)) return false;
        Range<Integer>[] ranges = chars.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES);
        if (ranges == null) return false;
        if (fps >= 60) {
            for (Range<Integer> r : ranges) {
                if (r.getLower() == 60 && r.getUpper() == 60) return true;
            }
            return false;
        }
        for (Range<Integer> r : ranges) {
            if (r.getLower() <= 30 && r.getUpper() >= 30) return true;
        }
        return false;
    }

    private boolean hasSize(Size[] values, Size wanted) {
        if (values == null) return false;
        for (Size s : values) if (wanted.equals(s)) return true;
        return false;
    }

    private void toggleFocusReal() {
        TextView old = field15("focusModeButton", TextView.class);
        if (old != null) old.performClick();
    }

    private boolean supportsVideoStabilization(CameraCharacteristics chars) {
        if (chars == null) return false;
        int[] modes = chars.get(CameraCharacteristics.CONTROL_AVAILABLE_VIDEO_STABILIZATION_MODES);
        if (modes == null) return false;
        for (int m : modes) {
            if (m == CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_ON ||
                    m == CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_PREVIEW_STABILIZATION) return true;
        }
        return false;
    }

    private void toggleVideoStabilization() {
        CameraCharacteristics chars = field15("currentChars", CameraCharacteristics.class);
        if (!supportsVideoStabilization(chars)) return;
        videoStabilizationEnabled = !videoStabilizationEnabled;
        applyVideoStabilization();
        updateVideoControls();
    }

    /** Real Camera2 EIS/preview-stabilization toggle; OIS remains hardware-managed. */
    private void applyVideoStabilization() {
        CameraCharacteristics chars = field15("currentChars", CameraCharacteristics.class);
        CaptureRequest.Builder builder = field15("repeatingBuilder", CaptureRequest.Builder.class);
        CameraCaptureSession session = field15("captureSession", CameraCaptureSession.class);
        Handler cameraHandler = field15("cameraHandler", Handler.class);
        if (chars == null || builder == null || session == null || cameraHandler == null) return;
        int[] modes = chars.get(CameraCharacteristics.CONTROL_AVAILABLE_VIDEO_STABILIZATION_MODES);
        if (modes == null) return;

        int chosen = CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_OFF;
        if (videoStabilizationEnabled) {
            if (containsInt(modes, CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_PREVIEW_STABILIZATION)) {
                chosen = CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_PREVIEW_STABILIZATION;
            } else if (containsInt(modes, CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_ON)) {
                chosen = CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_ON;
            }
        }
        try {
            builder.set(CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE, chosen);
            session.setRepeatingRequest(builder.build(), null, cameraHandler);
        } catch (Exception ignored) {}
    }

    private boolean containsInt(int[] values, int wanted) {
        if (values == null) return false;
        for (int v : values) if (v == wanted) return true;
        return false;
    }

    @SuppressWarnings("unchecked")
    private <T> T field15(String name, Class<T> type) {
        Class<?> c = getClass();
        while (c != null) {
            try {
                Field f = c.getDeclaredField(name);
                f.setAccessible(true);
                Object value = f.get(this);
                return value == null ? null : (T) value;
            } catch (NoSuchFieldException e) {
                c = c.getSuperclass();
            } catch (Exception e) {
                return null;
            }
        }
        return null;
    }

    private boolean bool15(String name) {
        Class<?> c = getClass();
        while (c != null) {
            try {
                Field f = c.getDeclaredField(name);
                f.setAccessible(true);
                return f.getBoolean(this);
            } catch (NoSuchFieldException e) {
                c = c.getSuperclass();
            } catch (Exception e) {
                return false;
            }
        }
        return false;
    }

    private int int15(String name, int fallback) {
        Class<?> c = getClass();
        while (c != null) {
            try {
                Field f = c.getDeclaredField(name);
                f.setAccessible(true);
                return f.getInt(this);
            } catch (NoSuchFieldException e) {
                c = c.getSuperclass();
            } catch (Exception e) {
                return fallback;
            }
        }
        return fallback;
    }

    private Object object15(String name) {
        Class<?> c = getClass();
        while (c != null) {
            try {
                Field f = c.getDeclaredField(name);
                f.setAccessible(true);
                return f.get(this);
            } catch (NoSuchFieldException e) {
                c = c.getSuperclass();
            } catch (Exception e) {
                return null;
            }
        }
        return null;
    }

    private void setInt15(String name, int value) {
        Class<?> c = getClass();
        while (c != null) {
            try {
                Field f = c.getDeclaredField(name);
                f.setAccessible(true);
                f.setInt(this, value);
                return;
            } catch (NoSuchFieldException e) {
                c = c.getSuperclass();
            } catch (Exception e) {
                return;
            }
        }
    }

    private void setObject15(String name, Object value) {
        Class<?> c = getClass();
        while (c != null) {
            try {
                Field f = c.getDeclaredField(name);
                f.setAccessible(true);
                f.set(this, value);
                return;
            } catch (NoSuchFieldException e) {
                c = c.getSuperclass();
            } catch (Exception e) {
                return;
            }
        }
    }

    private Object invoke15(String name, Class<?>[] types, Object... args) {
        Class<?> c = getClass();
        while (c != null) {
            try {
                Method m = c.getDeclaredMethod(name, types);
                m.setAccessible(true);
                return m.invoke(this, args);
            } catch (NoSuchMethodException e) {
                c = c.getSuperclass();
            } catch (Exception e) {
                return null;
            }
        }
        return null;
    }
}
