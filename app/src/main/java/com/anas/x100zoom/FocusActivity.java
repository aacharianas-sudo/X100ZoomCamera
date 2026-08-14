package com.anas.x100zoom;

import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.drawable.GradientDrawable;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.params.MeteringRectangle;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Range;
import android.util.Rational;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.Surface;
import android.view.TextureView;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.TextView;

import java.lang.reflect.Field;

/**
 * V6 input/UI layer on top of MainActivity.
 * Keeps the camera/zoom/recording pipeline unchanged while adding:
 *  - single-tap AF/AE metering
 *  - draggable exposure compensation control
 *  - ~2 s press to lock AF + AE + AWB
 */
public class FocusActivity extends MainActivity {
    private static final long LOCK_HOLD_MS = 1800L;
    private static final int METERING_WEIGHT = MeteringRectangle.METERING_WEIGHT_MAX;

    private final Handler ui = new Handler(Looper.getMainLooper());
    private TextureView preview;
    private FocusOverlay focusOverlay;
    private TextView lockBanner;

    private float downX;
    private float downY;
    private boolean longPressTriggered;
    private boolean exposureDragging;
    private int exposureDragStart;
    private float exposureDragStartY;

    private boolean focusLocked;
    private float focusX = -1f;
    private float focusY = -1f;
    private int exposureCompensation = 0;
    private Range<Integer> exposureRange = new Range<>(0, 0);
    private Rational exposureStep = new Rational(1, 1);

    private CaptureRequest.Builder lastBuilder;
    private CameraCaptureSession lastSession;

    private final Runnable longPressRunnable = () -> {
        longPressTriggered = true;
        focusLocked = true;
        focusX = downX;
        focusY = downY;
        showFocusUi(focusX, focusY, true);
        performMetering(focusX, focusY, true);
        showLockBanner();
    };

    private final Runnable sessionSyncRunnable = new Runnable() {
        @Override public void run() {
            try {
                CaptureRequest.Builder b = field("repeatingBuilder", CaptureRequest.Builder.class);
                CameraCaptureSession s = field("captureSession", CameraCaptureSession.class);
                if (focusLocked && b != null && s != null && (b != lastBuilder || s != lastSession)) {
                    lastBuilder = b;
                    lastSession = s;
                    performMetering(focusX, focusY, true);
                }
            } catch (Exception ignored) {}
            ui.postDelayed(this, 300L);
        }
    };

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        installFocusLayer();
        ui.post(sessionSyncRunnable);
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    private void installFocusLayer() {
        try {
            preview = field("textureView", TextureView.class);
            if (preview == null) return;

            FrameLayout content = findViewById(android.R.id.content);

            focusOverlay = new FocusOverlay(this);
            focusOverlay.setVisibility(View.GONE);
            content.addView(focusOverlay, new FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT));

            lockBanner = new TextView(this);
            lockBanner.setText("Focus, exposure, and white balance locked");
            lockBanner.setTextColor(Color.WHITE);
            lockBanner.setTextSize(15f);
            lockBanner.setGravity(Gravity.CENTER);
            lockBanner.setPadding(dp(14), dp(8), dp(14), dp(8));
            GradientDrawable bg = new GradientDrawable();
            bg.setColor(0xCC292929);
            bg.setCornerRadius(dp(5));
            lockBanner.setBackground(bg);
            lockBanner.setVisibility(View.GONE);
            FrameLayout.LayoutParams bannerLp = new FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                    FrameLayout.LayoutParams.WRAP_CONTENT);
            bannerLp.gravity = Gravity.TOP | Gravity.CENTER_HORIZONTAL;
            bannerLp.topMargin = dp(80);
            content.addView(lockBanner, bannerLp);

            preview.setOnTouchListener(this::handlePreviewTouch);
        } catch (Exception ignored) {}
    }

    private boolean handlePreviewTouch(View v, MotionEvent e) {
        final float x = e.getX();
        final float y = e.getY();

        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                if (focusOverlay != null && focusOverlay.isExposureHandleHit(x, y)) {
                    exposureDragging = true;
                    exposureDragStart = exposureCompensation;
                    exposureDragStartY = y;
                    ui.removeCallbacks(longPressRunnable);
                    return true;
                }
                exposureDragging = false;
                longPressTriggered = false;
                downX = x;
                downY = y;
                ui.removeCallbacks(longPressRunnable);
                ui.postDelayed(longPressRunnable, LOCK_HOLD_MS);
                return true;

            case MotionEvent.ACTION_MOVE:
                if (exposureDragging) {
                    updateExposureFromDrag(y);
                    return true;
                }
                float dx = x - downX;
                float dy = y - downY;
                if (dx * dx + dy * dy > dp(24) * dp(24)) {
                    ui.removeCallbacks(longPressRunnable);
                }
                return true;

            case MotionEvent.ACTION_UP:
                ui.removeCallbacks(longPressRunnable);
                if (exposureDragging) {
                    exposureDragging = false;
                    return true;
                }
                if (!longPressTriggered) {
                    focusLocked = false;
                    hideLockBanner();
                    focusX = x;
                    focusY = y;
                    showFocusUi(x, y, false);
                    performMetering(x, y, false);
                }
                return true;

            case MotionEvent.ACTION_CANCEL:
                ui.removeCallbacks(longPressRunnable);
                exposureDragging = false;
                return true;
        }
        return false;
    }

    private void showFocusUi(float x, float y, boolean locked) {
        if (focusOverlay == null) return;
        focusOverlay.showAt(x, y, locked, exposureCompensation, exposureRange);
        focusOverlay.setVisibility(View.VISIBLE);
        focusOverlay.animate().cancel();
        focusOverlay.setAlpha(1f);
        if (!locked) {
            focusOverlay.postDelayed(() -> {
                if (!focusLocked && focusOverlay != null) {
                    focusOverlay.animate().alpha(0f).setDuration(260L)
                            .withEndAction(() -> focusOverlay.setVisibility(View.GONE)).start();
                }
            }, 1500L);
        }
    }

    private void showLockBanner() {
        if (lockBanner == null) return;
        lockBanner.animate().cancel();
        lockBanner.setAlpha(0f);
        lockBanner.setVisibility(View.VISIBLE);
        lockBanner.animate().alpha(1f).setDuration(140L).start();
        lockBanner.postDelayed(() -> {
            if (lockBanner != null) {
                lockBanner.animate().alpha(0f).setDuration(250L)
                        .withEndAction(() -> lockBanner.setVisibility(View.GONE)).start();
            }
        }, 1900L);
    }

    private void hideLockBanner() {
        if (lockBanner != null) lockBanner.setVisibility(View.GONE);
    }

    private void updateExposureFromDrag(float y) {
        int low = exposureRange.getLower();
        int high = exposureRange.getUpper();
        if (low == high) return;

        int deltaSteps = Math.round((exposureDragStartY - y) / dp(22f));
        int wanted = clamp(exposureDragStart + deltaSteps, low, high);
        if (wanted == exposureCompensation) return;
        exposureCompensation = wanted;
        if (focusOverlay != null) {
            focusOverlay.setExposure(exposureCompensation, exposureRange);
        }
        applyExposureCompensation();
    }

    private int dp(float v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    private void refreshExposureCapabilities(CameraCharacteristics chars) {
        Range<Integer> range = chars.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_RANGE);
        Rational step = chars.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_STEP);
        exposureRange = range != null ? range : new Range<>(0, 0);
        exposureStep = step != null ? step : new Rational(1, 1);
        exposureCompensation = clamp(exposureCompensation,
                exposureRange.getLower(), exposureRange.getUpper());
    }

    private void performMetering(float viewX, float viewY, boolean lockAfterFocus) {
        try {
            CameraCharacteristics chars = field("currentChars", CameraCharacteristics.class);
            CaptureRequest.Builder b = field("repeatingBuilder", CaptureRequest.Builder.class);
            CameraCaptureSession session = field("captureSession", CameraCaptureSession.class);
            Handler cameraHandler = field("cameraHandler", Handler.class);
            if (chars == null || b == null || session == null || preview == null) return;

            refreshExposureCapabilities(chars);
            MeteringRectangle rect = meteringRectangle(chars, b, viewX, viewY);

            Integer maxAf = chars.get(CameraCharacteristics.CONTROL_MAX_REGIONS_AF);
            Integer maxAe = chars.get(CameraCharacteristics.CONTROL_MAX_REGIONS_AE);
            Integer maxAwb = chars.get(CameraCharacteristics.CONTROL_MAX_REGIONS_AWB);

            if (maxAf != null && maxAf > 0) {
                b.set(CaptureRequest.CONTROL_AF_REGIONS, new MeteringRectangle[]{rect});
            }
            if (maxAe != null && maxAe > 0) {
                b.set(CaptureRequest.CONTROL_AE_REGIONS, new MeteringRectangle[]{rect});
            }
            if (maxAwb != null && maxAwb > 0) {
                b.set(CaptureRequest.CONTROL_AWB_REGIONS, new MeteringRectangle[]{rect});
            }

            b.set(CaptureRequest.CONTROL_AE_LOCK, false);
            b.set(CaptureRequest.CONTROL_AWB_LOCK, false);
            applyExposureToBuilder(b);

            int[] afModes = chars.get(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES);
            if (contains(afModes, CaptureRequest.CONTROL_AF_MODE_AUTO)) {
                b.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_AUTO);
            }

            // Cancel the previous AF cycle then trigger a new one at the tapped region.
            b.set(CaptureRequest.CONTROL_AF_TRIGGER, CaptureRequest.CONTROL_AF_TRIGGER_CANCEL);
            session.capture(b.build(), null, cameraHandler);
            b.set(CaptureRequest.CONTROL_AF_TRIGGER, CaptureRequest.CONTROL_AF_TRIGGER_START);
            session.capture(b.build(), null, cameraHandler);
            b.set(CaptureRequest.CONTROL_AF_TRIGGER, CaptureRequest.CONTROL_AF_TRIGGER_IDLE);

            if (lockAfterFocus) {
                // AF AUTO + START remains in the focused/not-focused locked state until CANCEL.
                // AE/AWB have explicit locks.
                b.set(CaptureRequest.CONTROL_AE_LOCK, true);
                b.set(CaptureRequest.CONTROL_AWB_LOCK, true);
            }

            session.setRepeatingRequest(b.build(), null, cameraHandler);
            lastBuilder = b;
            lastSession = session;
        } catch (Exception ignored) {}
    }

    private void applyExposureCompensation() {
        try {
            CaptureRequest.Builder b = field("repeatingBuilder", CaptureRequest.Builder.class);
            CameraCaptureSession session = field("captureSession", CameraCaptureSession.class);
            Handler cameraHandler = field("cameraHandler", Handler.class);
            if (b == null || session == null) return;

            if (focusLocked) b.set(CaptureRequest.CONTROL_AE_LOCK, false);
            applyExposureToBuilder(b);
            session.setRepeatingRequest(b.build(), null, cameraHandler);

            if (focusLocked && cameraHandler != null) {
                cameraHandler.postDelayed(() -> {
                    try {
                        CaptureRequest.Builder rb = field("repeatingBuilder", CaptureRequest.Builder.class);
                        CameraCaptureSession rs = field("captureSession", CameraCaptureSession.class);
                        if (rb != null && rs != null && focusLocked) {
                            rb.set(CaptureRequest.CONTROL_AE_LOCK, true);
                            rs.setRepeatingRequest(rb.build(), null, cameraHandler);
                        }
                    } catch (Exception ignored) {}
                }, 180L);
            }
        } catch (Exception ignored) {}
    }

    private void applyExposureToBuilder(CaptureRequest.Builder b) {
        try {
            b.set(CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION,
                    clamp(exposureCompensation,
                            exposureRange.getLower(), exposureRange.getUpper()));
        } catch (Exception ignored) {}
    }

    private MeteringRectangle meteringRectangle(CameraCharacteristics chars,
                                                CaptureRequest.Builder b,
                                                float viewX,
                                                float viewY) {
        Rect active = chars.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE);
        if (active == null) active = new Rect(0, 0, 4000, 3000);

        Rect crop = b.get(CaptureRequest.SCALER_CROP_REGION);
        if (crop == null) {
            Float zoom = b.get(CaptureRequest.CONTROL_ZOOM_RATIO);
            float z = zoom != null ? Math.max(1f, zoom) : 1f;
            crop = cropForZoom(active, z);
        }

        float nx = clamp01(viewX / Math.max(1f, preview.getWidth()));
        float ny = clamp01(viewY / Math.max(1f, preview.getHeight()));

        Integer sensorOrientation = chars.get(CameraCharacteristics.SENSOR_ORIENTATION);
        int sensor = sensorOrientation != null ? sensorOrientation : 90;
        int displayDegrees = displayRotationDegrees();
        int relative = (sensor - displayDegrees + 360) % 360;

        float sx;
        float sy;
        if (relative == 90) {
            sx = ny;
            sy = 1f - nx;
        } else if (relative == 180) {
            sx = 1f - nx;
            sy = 1f - ny;
        } else if (relative == 270) {
            sx = 1f - ny;
            sy = nx;
        } else {
            sx = nx;
            sy = ny;
        }

        int cx = crop.left + Math.round(sx * crop.width());
        int cy = crop.top + Math.round(sy * crop.height());
        int rw = Math.max(80, Math.round(crop.width() * 0.12f));
        int rh = Math.max(80, Math.round(crop.height() * 0.12f));

        int left = clamp(cx - rw / 2, crop.left, Math.max(crop.left, crop.right - rw));
        int top = clamp(cy - rh / 2, crop.top, Math.max(crop.top, crop.bottom - rh));
        Rect r = new Rect(left, top,
                Math.min(crop.right, left + rw),
                Math.min(crop.bottom, top + rh));
        return new MeteringRectangle(r, METERING_WEIGHT);
    }

    private Rect cropForZoom(Rect active, float zoom) {
        float z = Math.max(1f, zoom);
        int w = Math.max(2, Math.round(active.width() / z));
        int h = Math.max(2, Math.round(active.height() / z));
        int l = active.centerX() - w / 2;
        int t = active.centerY() - h / 2;
        return new Rect(l, t, l + w, t + h);
    }

    private int displayRotationDegrees() {
        int r = getWindowManager().getDefaultDisplay().getRotation();
        if (r == Surface.ROTATION_90) return 90;
        if (r == Surface.ROTATION_180) return 180;
        if (r == Surface.ROTATION_270) return 270;
        return 0;
    }

    private boolean contains(int[] values, int wanted) {
        if (values == null) return false;
        for (int value : values) if (value == wanted) return true;
        return false;
    }

    private int clamp(int v, int lo, int hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    private float clamp01(float v) {
        return Math.max(0f, Math.min(1f, v));
    }

    @SuppressWarnings("unchecked")
    private <T> T field(String name, Class<T> type) throws Exception {
        Field f = MainActivity.class.getDeclaredField(name);
        f.setAccessible(true);
        return (T) f.get(this);
    }

    @Override protected void onDestroy() {
        ui.removeCallbacks(longPressRunnable);
        ui.removeCallbacks(sessionSyncRunnable);
        super.onDestroy();
    }

    private final class FocusOverlay extends View {
        private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint sunPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private float x;
        private float y;
        private boolean locked;
        private int ev;
        private Range<Integer> evRange = new Range<>(0, 0);

        FocusOverlay(FocusActivity activity) {
            super(activity);
            setClickable(false);
            setFocusable(false);
            stroke.setStyle(Paint.Style.STROKE);
            stroke.setStrokeWidth(dp(2));
            stroke.setStrokeCap(Paint.Cap.SQUARE);
            sunPaint.setStyle(Paint.Style.STROKE);
            sunPaint.setStrokeWidth(dp(2));
        }

        void showAt(float px, float py, boolean isLocked, int exposure, Range<Integer> range) {
            x = px;
            y = py;
            locked = isLocked;
            ev = exposure;
            evRange = range != null ? range : new Range<>(0, 0);
            invalidate();
        }

        void setExposure(int exposure, Range<Integer> range) {
            ev = exposure;
            evRange = range != null ? range : new Range<>(0, 0);
            invalidate();
        }

        boolean isExposureHandleHit(float px, float py) {
            if (getVisibility() != View.VISIBLE) return false;
            float hx = sunX();
            float hy = sunY();
            float dx = px - hx;
            float dy = py - hy;
            return dx * dx + dy * dy <= dp(34) * dp(34);
        }

        private float boxHalf() { return dp(48); }
        private float sunX() { return Math.min(getWidth() - dp(28), x + boxHalf() + dp(31)); }

        private float sunY() {
            int low = evRange.getLower();
            int high = evRange.getUpper();
            if (low == high) return y;
            float p = (ev - low) / (float) (high - low);
            return y + dp(43) - p * dp(86);
        }

        @Override protected void onDraw(Canvas c) {
            super.onDraw(c);
            int color = locked ? 0xFFFFC928 : Color.WHITE;
            stroke.setColor(color);
            sunPaint.setColor(color);

            float half = boxHalf();
            float cx = Math.max(half + dp(8), Math.min(getWidth() - half - dp(55), x));
            float cy = Math.max(half + dp(8), Math.min(getHeight() - half - dp(8), y));
            x = cx;
            y = cy;

            float l = cx - half;
            float r = cx + half;
            float t = cy - half;
            float b = cy + half;
            float corner = dp(19);

            // iPhone/Xiaomi-style separated corner focus box.
            c.drawLine(l, t, l + corner, t, stroke);
            c.drawLine(l, t, l, t + corner, stroke);
            c.drawLine(r, t, r - corner, t, stroke);
            c.drawLine(r, t, r, t + corner, stroke);
            c.drawLine(l, b, l + corner, b, stroke);
            c.drawLine(l, b, l, b - corner, stroke);
            c.drawLine(r, b, r - corner, b, stroke);
            c.drawLine(r, b, r, b - corner, stroke);

            float sx = sunX();
            float top = cy - dp(49);
            float bottom = cy + dp(49);
            c.drawLine(sx, top, sx, bottom, sunPaint);

            float sy = sunY();
            float radius = dp(8);
            c.drawCircle(sx, sy, radius, sunPaint);
            for (int i = 0; i < 8; i++) {
                double a = Math.PI * 2.0 * i / 8.0;
                float x1 = sx + (float) Math.cos(a) * dp(12);
                float y1 = sy + (float) Math.sin(a) * dp(12);
                float x2 = sx + (float) Math.cos(a) * dp(17);
                float y2 = sy + (float) Math.sin(a) * dp(17);
                c.drawLine(x1, y1, x2, y2, sunPaint);
            }
        }
    }
}
