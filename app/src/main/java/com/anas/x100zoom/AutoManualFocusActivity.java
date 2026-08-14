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
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.Surface;
import android.view.TextureView;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.TextView;

import java.lang.reflect.Field;

/**
 * V7 focus controller.
 *
 * Default = AUTO: continuous video autofocus.
 * MANUAL = tap the preview to focus at that point.
 * MANUAL long press ~= 2 seconds = AF + AE + AWB lock.
 * Exposure compensation can be dragged beside the focus square.
 */
public class AutoManualFocusActivity extends MainActivity {
    private static final long LOCK_HOLD_MS = 1800L;
    private static final int METERING_WEIGHT = MeteringRectangle.METERING_WEIGHT_MAX;

    private final Handler ui = new Handler(Looper.getMainLooper());

    private TextureView preview;
    private FocusOverlay focusOverlay;
    private TextView lockBanner;
    private TextView focusModeButton;

    private boolean manualMode = false;
    private boolean focusLocked = false;
    private boolean longPressTriggered = false;
    private boolean exposureDragging = false;

    private float downX;
    private float downY;
    private float focusX = -1f;
    private float focusY = -1f;
    private float exposureDragStartY;
    private int exposureDragStart;
    private int exposureCompensation = 0;
    private Range<Integer> exposureRange = new Range<>(0, 0);

    private CaptureRequest.Builder lastBuilder;
    private CameraCaptureSession lastSession;

    private final Runnable longPressRunnable = () -> {
        if (!manualMode) return;
        longPressTriggered = true;
        focusLocked = true;
        focusX = downX;
        focusY = downY;
        showFocusUi(focusX, focusY, true);
        performManualMetering(focusX, focusY, true);
        showLockBanner();
    };

    private final Runnable sessionSyncRunnable = new Runnable() {
        @Override public void run() {
            try {
                CaptureRequest.Builder b = field("repeatingBuilder", CaptureRequest.Builder.class);
                CameraCaptureSession s = field("captureSession", CameraCaptureSession.class);
                if (b != null && s != null && (b != lastBuilder || s != lastSession)) {
                    lastBuilder = b;
                    lastSession = s;
                    if (!manualMode) {
                        restoreContinuousAutoFocus();
                    } else if (focusLocked && focusX >= 0f && focusY >= 0f) {
                        performManualMetering(focusX, focusY, true);
                    }
                }
            } catch (Exception ignored) {}
            ui.postDelayed(this, 300L);
        }
    };

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        installFocusUi();
        ui.post(sessionSyncRunnable);
    }

    @Override protected void onDestroy() {
        ui.removeCallbacksAndMessages(null);
        super.onDestroy();
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private int dp(float value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private void installFocusUi() {
        try {
            preview = field("textureView", TextureView.class);
            if (preview == null) return;

            FrameLayout root = (FrameLayout) preview.getParent();
            if (root == null) return;

            focusOverlay = new FocusOverlay(this);
            focusOverlay.setVisibility(View.GONE);
            focusOverlay.setClickable(false);
            focusOverlay.setFocusable(false);
            FrameLayout.LayoutParams overlayLp = new FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT);
            root.addView(focusOverlay, Math.min(1, root.getChildCount()), overlayLp);

            lockBanner = new TextView(this);
            lockBanner.setText("Focus, exposure, and white balance locked");
            lockBanner.setTextColor(Color.WHITE);
            lockBanner.setTextSize(15f);
            lockBanner.setGravity(Gravity.CENTER);
            lockBanner.setPadding(dp(14), dp(8), dp(14), dp(8));
            GradientDrawable bannerBg = new GradientDrawable();
            bannerBg.setColor(0xCC292929);
            bannerBg.setCornerRadius(dp(6));
            lockBanner.setBackground(bannerBg);
            lockBanner.setVisibility(View.GONE);
            FrameLayout.LayoutParams bannerLp = new FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                    FrameLayout.LayoutParams.WRAP_CONTENT);
            bannerLp.gravity = Gravity.TOP | Gravity.CENTER_HORIZONTAL;
            bannerLp.topMargin = dp(82);
            root.addView(lockBanner, bannerLp);

            focusModeButton = new TextView(this);
            focusModeButton.setText("AF  AUTO");
            focusModeButton.setTextColor(Color.WHITE);
            focusModeButton.setTextSize(12f);
            focusModeButton.setGravity(Gravity.CENTER);
            focusModeButton.setTypeface(null, android.graphics.Typeface.BOLD);
            focusModeButton.setPadding(dp(12), dp(6), dp(12), dp(6));
            focusModeButton.setBackground(modeButtonBackground(false));
            focusModeButton.setOnClickListener(v -> setManualMode(!manualMode));
            FrameLayout.LayoutParams modeLp = new FrameLayout.LayoutParams(dp(92), dp(38));
            modeLp.gravity = Gravity.TOP | Gravity.END;
            modeLp.rightMargin = dp(14);
            modeLp.topMargin = dp(70);
            root.addView(focusModeButton, modeLp);

            preview.setOnTouchListener(this::handlePreviewTouch);

            // Start in true continuous autofocus.
            ui.postDelayed(this::restoreContinuousAutoFocus, 500L);
        } catch (Exception ignored) {}
    }

    private GradientDrawable modeButtonBackground(boolean manual) {
        GradientDrawable d = new GradientDrawable();
        d.setCornerRadius(dp(12));
        d.setColor(manual ? 0xCC7A6500 : 0x88000000);
        d.setStroke(dp(1), manual ? 0xFFFFD54F : 0x66FFFFFF);
        return d;
    }

    private void setManualMode(boolean enabled) {
        manualMode = enabled;
        focusLocked = false;
        longPressTriggered = false;
        exposureDragging = false;
        ui.removeCallbacks(longPressRunnable);

        if (focusOverlay != null) focusOverlay.setVisibility(View.GONE);
        if (lockBanner != null) lockBanner.setVisibility(View.GONE);

        if (focusModeButton != null) {
            focusModeButton.setText(enabled ? "AF  MANUAL" : "AF  AUTO");
            focusModeButton.setTextColor(enabled ? 0xFFFFD54F : Color.WHITE);
            focusModeButton.setBackground(modeButtonBackground(enabled));
        }

        if (!enabled) {
            exposureCompensation = 0;
            restoreContinuousAutoFocus();
        }
    }

    private boolean handlePreviewTouch(View v, MotionEvent event) {
        if (!manualMode) {
            // AUTO mode intentionally leaves the preview to continuous AF.
            return false;
        }

        float x = event.getX();
        float y = event.getY();

        switch (event.getActionMasked()) {
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
                    if (lockBanner != null) lockBanner.setVisibility(View.GONE);
                    focusX = x;
                    focusY = y;
                    showFocusUi(x, y, false);
                    performManualMetering(x, y, false);
                }
                return true;

            case MotionEvent.ACTION_CANCEL:
                ui.removeCallbacks(longPressRunnable);
                exposureDragging = false;
                return true;
        }
        return true;
    }

    private void restoreContinuousAutoFocus() {
        if (manualMode) return;
        try {
            CameraCharacteristics chars = field("currentChars", CameraCharacteristics.class);
            CaptureRequest.Builder b = field("repeatingBuilder", CaptureRequest.Builder.class);
            CameraCaptureSession session = field("captureSession", CameraCaptureSession.class);
            Handler cameraHandler = field("cameraHandler", Handler.class);
            if (chars == null || b == null || session == null) return;

            int[] afModes = chars.get(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES);
            b.set(CaptureRequest.CONTROL_AF_TRIGGER, CaptureRequest.CONTROL_AF_TRIGGER_CANCEL);
            try { session.capture(b.build(), null, cameraHandler); } catch (Exception ignored) {}
            b.set(CaptureRequest.CONTROL_AF_TRIGGER, CaptureRequest.CONTROL_AF_TRIGGER_IDLE);

            if (contains(afModes, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO)) {
                b.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO);
            } else if (contains(afModes, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)) {
                b.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE);
            }

            try { b.set(CaptureRequest.CONTROL_AF_REGIONS, null); } catch (Exception ignored) {}
            try { b.set(CaptureRequest.CONTROL_AE_REGIONS, null); } catch (Exception ignored) {}
            try { b.set(CaptureRequest.CONTROL_AWB_REGIONS, null); } catch (Exception ignored) {}
            try { b.set(CaptureRequest.CONTROL_AE_LOCK, false); } catch (Exception ignored) {}
            try { b.set(CaptureRequest.CONTROL_AWB_LOCK, false); } catch (Exception ignored) {}
            try { b.set(CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION, 0); } catch (Exception ignored) {}

            session.setRepeatingRequest(b.build(), null, cameraHandler);
            lastBuilder = b;
            lastSession = session;
        } catch (Exception ignored) {}
    }

    private void performManualMetering(float viewX, float viewY, boolean lockAfterFocus) {
        if (!manualMode) return;
        try {
            CameraCharacteristics chars = field("currentChars", CameraCharacteristics.class);
            CaptureRequest.Builder b = field("repeatingBuilder", CaptureRequest.Builder.class);
            CameraCaptureSession session = field("captureSession", CameraCaptureSession.class);
            Handler cameraHandler = field("cameraHandler", Handler.class);
            if (chars == null || b == null || session == null || preview == null) return;

            refreshExposureCapabilities(chars);
            MeteringRectangle region = meteringRectangle(chars, b, viewX, viewY);

            Integer maxAf = chars.get(CameraCharacteristics.CONTROL_MAX_REGIONS_AF);
            Integer maxAe = chars.get(CameraCharacteristics.CONTROL_MAX_REGIONS_AE);
            Integer maxAwb = chars.get(CameraCharacteristics.CONTROL_MAX_REGIONS_AWB);

            if (maxAf != null && maxAf > 0) {
                b.set(CaptureRequest.CONTROL_AF_REGIONS, new MeteringRectangle[]{region});
            }
            if (maxAe != null && maxAe > 0) {
                b.set(CaptureRequest.CONTROL_AE_REGIONS, new MeteringRectangle[]{region});
            }
            if (maxAwb != null && maxAwb > 0) {
                b.set(CaptureRequest.CONTROL_AWB_REGIONS, new MeteringRectangle[]{region});
            }

            try { b.set(CaptureRequest.CONTROL_AE_LOCK, false); } catch (Exception ignored) {}
            try { b.set(CaptureRequest.CONTROL_AWB_LOCK, false); } catch (Exception ignored) {}
            applyExposureToBuilder(b);

            int[] afModes = chars.get(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES);
            if (contains(afModes, CaptureRequest.CONTROL_AF_MODE_AUTO)) {
                b.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_AUTO);
            }

            b.set(CaptureRequest.CONTROL_AF_TRIGGER, CaptureRequest.CONTROL_AF_TRIGGER_CANCEL);
            session.capture(b.build(), null, cameraHandler);
            b.set(CaptureRequest.CONTROL_AF_TRIGGER, CaptureRequest.CONTROL_AF_TRIGGER_START);
            session.capture(b.build(), null, cameraHandler);
            b.set(CaptureRequest.CONTROL_AF_TRIGGER, CaptureRequest.CONTROL_AF_TRIGGER_IDLE);

            if (lockAfterFocus) {
                try { b.set(CaptureRequest.CONTROL_AE_LOCK, true); } catch (Exception ignored) {}
                try { b.set(CaptureRequest.CONTROL_AWB_LOCK, true); } catch (Exception ignored) {}
            }

            session.setRepeatingRequest(b.build(), null, cameraHandler);
            lastBuilder = b;
            lastSession = session;
        } catch (Exception ignored) {}
    }

    private void refreshExposureCapabilities(CameraCharacteristics chars) {
        Range<Integer> range = chars.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_RANGE);
        exposureRange = range != null ? range : new Range<>(0, 0);
        exposureCompensation = clamp(exposureCompensation,
                exposureRange.getLower(), exposureRange.getUpper());
    }

    private void updateExposureFromDrag(float y) {
        int low = exposureRange.getLower();
        int high = exposureRange.getUpper();
        if (low == high) return;

        int delta = Math.round((exposureDragStartY - y) / dp(22f));
        int wanted = clamp(exposureDragStart + delta, low, high);
        if (wanted == exposureCompensation) return;

        exposureCompensation = wanted;
        if (focusOverlay != null) focusOverlay.setExposure(exposureCompensation, exposureRange);
        applyExposureCompensation();
    }

    private void applyExposureCompensation() {
        if (!manualMode) return;
        try {
            CaptureRequest.Builder b = field("repeatingBuilder", CaptureRequest.Builder.class);
            CameraCaptureSession session = field("captureSession", CameraCaptureSession.class);
            Handler cameraHandler = field("cameraHandler", Handler.class);
            if (b == null || session == null) return;

            boolean wasLocked = focusLocked;
            if (wasLocked) {
                try { b.set(CaptureRequest.CONTROL_AE_LOCK, false); } catch (Exception ignored) {}
            }
            applyExposureToBuilder(b);
            session.setRepeatingRequest(b.build(), null, cameraHandler);

            if (wasLocked && cameraHandler != null) {
                cameraHandler.postDelayed(() -> {
                    try {
                        CaptureRequest.Builder rb = field("repeatingBuilder", CaptureRequest.Builder.class);
                        CameraCaptureSession rs = field("captureSession", CameraCaptureSession.class);
                        if (rb != null && rs != null && focusLocked && manualMode) {
                            try { rb.set(CaptureRequest.CONTROL_AE_LOCK, true); } catch (Exception ignored) {}
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

    private void showFocusUi(float x, float y, boolean locked) {
        if (focusOverlay == null) return;
        focusOverlay.showAt(x, y, locked, exposureCompensation, exposureRange);
        focusOverlay.setVisibility(View.VISIBLE);
        focusOverlay.animate().cancel();
        focusOverlay.setAlpha(1f);

        if (!locked) {
            focusOverlay.postDelayed(() -> {
                if (manualMode && !focusLocked && focusOverlay != null) {
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

        Integer orientation = chars.get(CameraCharacteristics.SENSOR_ORIENTATION);
        int sensor = orientation != null ? orientation : 90;
        int relative = (sensor - displayRotationDegrees() + 360) % 360;

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
        Rect region = new Rect(left, top,
                Math.min(crop.right, left + rw),
                Math.min(crop.bottom, top + rh));
        return new MeteringRectangle(region, METERING_WEIGHT);
    }

    private Rect cropForZoom(Rect active, float zoom) {
        float z = Math.max(1f, zoom);
        int w = Math.max(2, Math.round(active.width() / z));
        int h = Math.max(2, Math.round(active.height() / z));
        int left = active.centerX() - w / 2;
        int top = active.centerY() - h / 2;
        return new Rect(left, top, left + w, top + h);
    }

    private int displayRotationDegrees() {
        int rotation = getWindowManager().getDefaultDisplay().getRotation();
        if (rotation == Surface.ROTATION_90) return 90;
        if (rotation == Surface.ROTATION_180) return 180;
        if (rotation == Surface.ROTATION_270) return 270;
        return 0;
    }

    private boolean contains(int[] values, int wanted) {
        if (values == null) return false;
        for (int value : values) if (value == wanted) return true;
        return false;
    }

    private int clamp(int value, int low, int high) {
        return Math.max(low, Math.min(high, value));
    }

    private float clamp01(float value) {
        return Math.max(0f, Math.min(1f, value));
    }

    @SuppressWarnings("unchecked")
    private <T> T field(String name, Class<T> type) throws Exception {
        Class<?> c = getClass();
        while (c != null) {
            try {
                Field f = c.getDeclaredField(name);
                f.setAccessible(true);
                Object value = f.get(this);
                return value == null ? null : (T) value;
            } catch (NoSuchFieldException ignored) {
                c = c.getSuperclass();
            }
        }
        return null;
    }

    private final class FocusOverlay extends View {
        private final Paint squarePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint sunPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private float x = -1f;
        private float y = -1f;
        private boolean locked = false;
        private int exposure = 0;
        private Range<Integer> range = new Range<>(0, 0);
        private float sunX;
        private float sunY;

        FocusOverlay(AutoManualFocusActivity context) {
            super(context);
            squarePaint.setStyle(Paint.Style.STROKE);
            squarePaint.setStrokeWidth(dp(2));
            sunPaint.setStyle(Paint.Style.STROKE);
            sunPaint.setStrokeWidth(dp(2));
        }

        void showAt(float px, float py, boolean isLocked, int exposureValue, Range<Integer> exposureRange) {
            x = px;
            y = py;
            locked = isLocked;
            exposure = exposureValue;
            range = exposureRange != null ? exposureRange : new Range<>(0, 0);
            invalidate();
        }

        void setExposure(int value, Range<Integer> exposureRange) {
            exposure = value;
            range = exposureRange != null ? exposureRange : new Range<>(0, 0);
            invalidate();
        }

        boolean isExposureHandleHit(float px, float py) {
            if (getVisibility() != View.VISIBLE || x < 0f) return false;
            float dx = px - sunX;
            float dy = py - sunY;
            return dx * dx + dy * dy <= dp(34) * dp(34);
        }

        @Override protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            if (x < 0f || y < 0f) return;

            int color = locked ? 0xFFFFC928 : Color.WHITE;
            squarePaint.setColor(color);
            sunPaint.setColor(color);

            float half = dp(42);
            float corner = dp(16);
            float left = Math.max(dp(8), x - half);
            float top = Math.max(dp(8), y - half);
            float right = Math.min(getWidth() - dp(8), x + half);
            float bottom = Math.min(getHeight() - dp(8), y + half);

            // Broken-corner focus square like the reference camera UI.
            canvas.drawLine(left, top, left + corner, top, squarePaint);
            canvas.drawLine(left, top, left, top + corner, squarePaint);
            canvas.drawLine(right, top, right - corner, top, squarePaint);
            canvas.drawLine(right, top, right, top + corner, squarePaint);
            canvas.drawLine(left, bottom, left + corner, bottom, squarePaint);
            canvas.drawLine(left, bottom, left, bottom - corner, squarePaint);
            canvas.drawLine(right, bottom, right - corner, bottom, squarePaint);
            canvas.drawLine(right, bottom, right, bottom - corner, squarePaint);

            sunX = Math.min(getWidth() - dp(24), right + dp(38));
            float low = range.getLower();
            float high = range.getUpper();
            float p = high > low ? (exposure - low) / (high - low) : 0.5f;
            sunY = bottom - p * Math.max(dp(20), bottom - top);
            sunY = Math.max(top, Math.min(bottom, sunY));

            canvas.drawLine(sunX, top, sunX, bottom, sunPaint);
            canvas.drawCircle(sunX, sunY, dp(8), sunPaint);
            for (int i = 0; i < 8; i++) {
                double angle = i * Math.PI / 4.0;
                float x1 = sunX + (float) Math.cos(angle) * dp(12);
                float y1 = sunY + (float) Math.sin(angle) * dp(12);
                float x2 = sunX + (float) Math.cos(angle) * dp(17);
                float y2 = sunY + (float) Math.sin(angle) * dp(17);
                canvas.drawLine(x1, y1, x2, y2, sunPaint);
            }
        }
    }
}
