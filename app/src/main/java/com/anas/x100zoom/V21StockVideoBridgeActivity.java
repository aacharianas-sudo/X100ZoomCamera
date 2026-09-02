package com.anas.x100zoom;

import android.graphics.Color;
import android.graphics.SurfaceTexture;
import android.graphics.drawable.GradientDrawable;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraMetadata;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.CaptureResult;
import android.hardware.camera2.TotalCaptureResult;
import android.hardware.camera2.params.OutputConfiguration;
import android.hardware.camera2.params.SessionConfiguration;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.media.MediaRecorder;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Range;
import android.util.Size;
import android.view.Surface;
import android.view.TextureView;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * V21 stock-video bridge.
 *
 * The original vivo X100 Camera.apk contains vendor controls that are not used
 * by normal Android Camera2 apps. V21 keeps our independent package but probes
 * the public Camera2 request-key table for those exact stock tag names.
 *
 * If a tag is publicly exposed, V21 uses the real Key object returned by the
 * HAL and type-probes it on an unsubmitted builder before applying it. This
 * avoids hardcoding private Java classes from the stock APK.
 */
public class V21StockVideoBridgeActivity extends V20VideoOnlyActivity {
    private static final int ACCENT = 0xFFFFD129;
    private static final Size FHD = new Size(1920, 1080);
    private static final Size UHD = new Size(3840, 2160);
    private static final Size PREVIEW = new Size(1280, 720);

    private static final String[] STOCK_TAGS = new String[]{
            "vivo.control.videoFrameRate",
            "vivo.control.video.fps",
            "vivo.control.video60FPS",
            "vivo.record.fps",
            "vivo.control.videoResolution",
            "vivo.control.videoMode",
            "vivo.video.mode",
            "vivo.control.sat.enable",
            "vivo.control.initSATRatio",
            "vivo.control.zoom_ratio",
            "com.vivo.MultiCameraSATSelectSessionId"
    };

    private final Handler ui21 = new Handler(Looper.getMainLooper());
    private boolean installed21;
    private boolean vendorRecording21;
    private boolean vendorStarting21;
    private Surface previewSurface21;
    private TextView bridgeStatus21;
    private TextView capabilityNote21;
    private Button shutter21;
    private TextureView preview21;
    private Map<Integer, TextView> fpsButtons21;
    private CameraCaptureSession lastSession21;
    private CaptureRequest.Builder lastBuilder21;
    private int lastBridgeCount21 = -1;

    private long sensorStart21;
    private long sensorLast21;
    private int sensorFrames21;
    private volatile float measuredSensorFps21;

    private final CameraCaptureSession.CaptureCallback fpsCallback21 =
            new CameraCaptureSession.CaptureCallback() {
        @Override public void onCaptureCompleted(CameraCaptureSession session,
                                                  CaptureRequest request,
                                                  TotalCaptureResult result) {
            Long ts = result.get(CaptureResult.SENSOR_TIMESTAMP);
            if (ts == null || ts <= 0L || ts == sensorLast21) return;
            sensorLast21 = ts;
            if (sensorStart21 == 0L) {
                sensorStart21 = ts;
                sensorFrames21 = 1;
                return;
            }
            sensorFrames21++;
            long span = ts - sensorStart21;
            if (span >= 900_000_000L && sensorFrames21 > 2) {
                measuredSensorFps21 = (sensorFrames21 - 1) * 1_000_000_000f / span;
                sensorStart21 = ts;
                sensorFrames21 = 1;
            }
        }
    };

    private final Runnable watcher21 = new Runnable() {
        @Override public void run() {
            if (!installed21) {
                tryInstall21();
            } else {
                applyBridgeToPreview21();
                updateBridgeStatus21();
            }
            ui21.postDelayed(this, 650L);
        }
    };

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        ui21.postDelayed(watcher21, 260L);
    }

    @Override protected void onPause() {
        if (vendorRecording21 || vendorStarting21) stopVendorRecording21(false);
        super.onPause();
    }

    @Override protected void onDestroy() {
        if (vendorRecording21 || vendorStarting21) stopVendorRecording21(false);
        ui21.removeCallbacksAndMessages(null);
        super.onDestroy();
    }

    @SuppressWarnings("unchecked")
    private void tryInstall21() {
        if (!exactBool21(V20VideoOnlyActivity.class, "installed20")) return;

        shutter21 = exactField21(MainActivity.class, "recordButton", Button.class);
        preview21 = exactField21(MainActivity.class, "textureView", TextureView.class);
        capabilityNote21 = exactField21(V17HighSpeedActivity.class, "capabilityNote17", TextView.class);
        fpsButtons21 = exactField21(V17HighSpeedActivity.class, "fpsButtons17", Map.class);
        LinearLayout panel = exactField21(V15VideoUiActivity.class, "videoPanel", LinearLayout.class);

        if (shutter21 == null || preview21 == null || fpsButtons21 == null || panel == null) return;

        bridgeStatus21 = new TextView(this);
        bridgeStatus21.setTextSize(10.5f);
        bridgeStatus21.setTextColor(0xFFBDBDBD);
        bridgeStatus21.setText("Vivo stock bridge: probing vendor video controls…");
        bridgeStatus21.setPadding(dp21(8), dp21(3), dp21(8), dp21(6));
        bridgeStatus21.setGravity(android.view.Gravity.CENTER);
        panel.addView(bridgeStatus21, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp21(34)));

        installShutter21();
        installed21 = true;
        updateBridgeStatus21();
    }

    private void installShutter21() {
        shutter21.setOnClickListener(v -> {
            if (vendorRecording21 || vendorStarting21) {
                stopVendorRecording21(true);
                return;
            }
            if (exactBool21(V17HighSpeedActivity.class, "highSpeedRecording17")) {
                invokeExact21(V17HighSpeedActivity.class, "stopHighSpeed17",
                        new Class[]{boolean.class}, true);
                return;
            }
            if (bool21("recording") || bool21("recordingStarting")) {
                invokeExact21(V16CorrectnessActivity.class, "stopVerifiedRecording16", new Class[]{});
                return;
            }

            int hs = exactInt21(V17HighSpeedActivity.class, "highSpeedChoice17", 0);
            if (hs >= 120) {
                invokeExact21(V17HighSpeedActivity.class, "startHighSpeed17", new Class[]{});
                return;
            }

            int fps = int21("selectedFps", 30);
            if (fps >= 50 && exposedStockTagCount21(currentChars21()) > 0) {
                startVendorRecording21();
            } else {
                invokeExact21(V19Real60Activity.class, "startNormalRecording19", new Class[]{});
            }
        });
    }

    private void applyBridgeToPreview21() {
        if (vendorRecording21 || vendorStarting21) return;
        if (bool21("recording") || bool21("recordingStarting")) return;
        if (exactBool21(V17HighSpeedActivity.class, "highSpeedRecording17")) return;

        CaptureRequest.Builder b = field21("repeatingBuilder", CaptureRequest.Builder.class);
        CameraCaptureSession s = field21("captureSession", CameraCaptureSession.class);
        CameraCharacteristics chars = currentChars21();
        Handler h = field21("cameraHandler", Handler.class);
        if (b == null || s == null || chars == null || h == null) return;

        if (b == lastBuilder21 && s == lastSession21) return;
        lastBuilder21 = b;
        lastSession21 = s;

        int applied = applyStockTags21(b, chars, int21("selectedFps", 30), selectedSize21(), false);
        if (applied <= 0) return;
        try { s.setRepeatingRequest(b.build(), null, h); } catch (Exception ignored) {}
    }

    private void updateBridgeStatus21() {
        CameraCharacteristics chars = currentChars21();
        int count = exposedStockTagCount21(chars);
        if (count == lastBridgeCount21) return;
        lastBridgeCount21 = count;

        if (bridgeStatus21 != null) {
            if (count > 0) {
                bridgeStatus21.setText("Vivo stock bridge: " + count + " / " + STOCK_TAGS.length +
                        " extracted video/SAT tags exposed by this lens");
                bridgeStatus21.setTextColor(ACCENT);
            } else {
                bridgeStatus21.setText("Vivo stock bridge: 0 stock video/SAT tags exposed to this third-party package");
                bridgeStatus21.setTextColor(0xFFFF8A80);
            }
        }

        if (capabilityNote21 != null && count > 0) {
            capabilityNote21.setText("Stock APK bridge active • " + count +
                    " Vivo vendor controls visible • 50/60 fps recording will submit those stock tags and verify the MP4.");
        }
    }

    private int exposedStockTagCount21(CameraCharacteristics chars) {
        if (chars == null) return 0;
        List<CaptureRequest.Key<?>> keys = chars.getAvailableCaptureRequestKeys();
        if (keys == null) return 0;
        int found = 0;
        for (String wanted : STOCK_TAGS) {
            for (CaptureRequest.Key<?> k : keys) {
                if (wanted.equals(k.getName())) {
                    found++;
                    break;
                }
            }
        }
        return found;
    }

    private void startVendorRecording21() {
        CameraDevice camera = field21("cameraDevice", CameraDevice.class);
        CameraCharacteristics chars = currentChars21();
        Handler h = field21("cameraHandler", Handler.class);
        Size size = selectedSize21();
        int fps = int21("selectedFps", 30);

        if (camera == null || chars == null || h == null || preview21 == null || !preview21.isAvailable()) {
            toast21("Camera is not ready.");
            return;
        }

        Range<Integer> range = bestRange21(chars, fps);
        if (range == null) {
            toast21("Android Camera2 does not expose an AE range reaching " + fps + " fps on this active lens.");
            return;
        }

        try {
            invokeAny21("prepareRecorder", new Class[]{});
            MediaRecorder recorder = field21("recorder", MediaRecorder.class);
            if (recorder == null) throw new IllegalStateException("Recorder preparation failed");

            vendorStarting21 = true;
            setBoolean21("recordingStarting", true);
            invokeAny21("closeSessionOnly", new Class[]{});

            SurfaceTexture st = preview21.getSurfaceTexture();
            if (st == null) throw new IllegalStateException("Preview surface unavailable");
            Size p = choosePreview21(chars);
            st.setDefaultBufferSize(p.getWidth(), p.getHeight());
            previewSurface21 = new Surface(st);
            Surface recordSurface = recorder.getSurface();

            CaptureRequest.Builder b = camera.createCaptureRequest(CameraDevice.TEMPLATE_RECORD);
            b.addTarget(previewSurface21);
            b.addTarget(recordSurface);
            b.set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO);
            b.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, range);

            int[] af = chars.get(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES);
            if (contains21(af, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO)) {
                b.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO);
            }

            // Prefer stock-like high-frame-rate behavior: EIS off, OIS kept.
            if (fps >= 50) {
                try {
                    b.set(CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE,
                            CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_OFF);
                } catch (Exception ignored) {}
                int[] ois = chars.get(CameraCharacteristics.LENS_INFO_AVAILABLE_OPTICAL_STABILIZATION);
                if (contains21(ois, CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE_ON)) {
                    try {
                        b.set(CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE,
                                CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE_ON);
                    } catch (Exception ignored) {}
                }
            } else {
                invokeAny21("enableBestStabilization",
                        new Class[]{CaptureRequest.Builder.class, CameraCharacteristics.class}, b, chars);
            }

            invokeAny21("setZoomOnBuilder", new Class[]{CaptureRequest.Builder.class}, b);
            int applied = applyStockTags21(b, chars, fps, size, true);

            sensorStart21 = 0L;
            sensorLast21 = 0L;
            sensorFrames21 = 0;
            measuredSensorFps21 = 0f;

            List<OutputConfiguration> outputs = new ArrayList<>();
            outputs.add(new OutputConfiguration(previewSurface21));
            outputs.add(new OutputConfiguration(recordSurface));

            SessionConfiguration config = new SessionConfiguration(
                    SessionConfiguration.SESSION_REGULAR,
                    outputs,
                    command -> h.post(command),
                    new CameraCaptureSession.StateCallback() {
                        @Override public void onConfigured(CameraCaptureSession session) {
                            try {
                                setObject21("captureSession", session);
                                setObject21("repeatingBuilder", b);
                                session.setRepeatingRequest(b.build(), fpsCallback21, h);
                                recorder.start();

                                vendorStarting21 = false;
                                vendorRecording21 = true;
                                setBoolean21("recordingStarting", false);
                                setBoolean21("recording", true);
                                setLong21("recordStartedAtMs", System.currentTimeMillis());

                                int tags = applied;
                                runOnUiThread(() -> Toast.makeText(
                                        V21StockVideoBridgeActivity.this,
                                        "Vivo stock bridge recording • " + fps + " fps request • " +
                                                tags + " stock tags applied",
                                        Toast.LENGTH_LONG).show());
                            } catch (Exception e) {
                                failVendor21("Vivo bridge start failed: " + e.getMessage());
                            }
                        }

                        @Override public void onConfigureFailed(CameraCaptureSession session) {
                            failVendor21("Vivo HAL rejected the stock-tag video session.");
                        }
                    });

            camera.createCaptureSession(config);
        } catch (Exception e) {
            failVendor21("Vivo bridge setup failed: " + e.getMessage());
        }
    }

    private void stopVendorRecording21(boolean restart) {
        Uri saved = field21("outputUri", Uri.class);
        float sensor = measuredSensorFps21;

        vendorRecording21 = false;
        vendorStarting21 = false;

        if (bool21("recording") || bool21("recordingStarting")) {
            invokeAny21("stopRecording", new Class[]{});
        } else {
            invokeAny21("safeResetRecorder", new Class[]{});
            if (restart) {
                Handler h = field21("cameraHandler", Handler.class);
                if (h != null) h.post(() -> invokeAny21("startPreviewSession", new Class[]{}));
            }
        }

        if (previewSurface21 != null) {
            try { previewSurface21.release(); } catch (Exception ignored) {}
            previewSurface21 = null;
        }

        if (saved != null) {
            invokeExact21(V16CorrectnessActivity.class, "verifyEncodedFps16",
                    new Class[]{Uri.class, float.class}, saved, sensor);
        }
    }

    private void failVendor21(String text) {
        vendorRecording21 = false;
        vendorStarting21 = false;
        setBoolean21("recording", false);
        setBoolean21("recordingStarting", false);
        invokeAny21("safeResetRecorder", new Class[]{});
        if (previewSurface21 != null) {
            try { previewSurface21.release(); } catch (Exception ignored) {}
            previewSurface21 = null;
        }
        Handler h = field21("cameraHandler", Handler.class);
        if (h != null) h.post(() -> invokeAny21("startPreviewSession", new Class[]{}));
        toast21(text);
    }

    /**
     * Uses only Key objects actually returned by the camera HAL. For each key,
     * try likely stock value representations on the unsubmitted Builder. A wrong
     * Java type throws before submission; the next candidate is then attempted.
     */
    @SuppressWarnings({"rawtypes", "unchecked"})
    private int applyStockTags21(CaptureRequest.Builder b, CameraCharacteristics chars,
                                 int fps, Size size, boolean recording) {
        List<CaptureRequest.Key<?>> keys = chars == null ? null : chars.getAvailableCaptureRequestKeys();
        if (keys == null) return 0;

        Map<String, Object[]> values = new LinkedHashMap<>();
        values.put("vivo.control.videoFrameRate", numericCandidates21(fps));
        values.put("vivo.control.video.fps", numericCandidates21(fps));
        values.put("vivo.control.video60FPS", numericCandidates21(fps == 60 ? 1 : 0));
        values.put("vivo.record.fps", numericCandidates21(fps));
        values.put("vivo.control.videoResolution", numericCandidates21(size.getHeight()));
        values.put("vivo.control.videoMode", numericCandidates21(1));
        values.put("vivo.video.mode", numericCandidates21(1));
        values.put("vivo.control.sat.enable", numericCandidates21(1));

        float zoom = float21("requestedUiZoom", 1f);
        values.put("vivo.control.initSATRatio", floatCandidates21(zoom));
        values.put("vivo.control.zoom_ratio", floatCandidates21(zoom));
        values.put("com.vivo.MultiCameraSATSelectSessionId", numericCandidates21(recording ? 1 : 0));

        int applied = 0;
        for (Map.Entry<String, Object[]> entry : values.entrySet()) {
            CaptureRequest.Key key = null;
            for (CaptureRequest.Key<?> k : keys) {
                if (entry.getKey().equals(k.getName())) {
                    key = (CaptureRequest.Key) k;
                    break;
                }
            }
            if (key == null) continue;

            for (Object candidate : entry.getValue()) {
                try {
                    b.set(key, candidate);
                    applied++;
                    break;
                } catch (Throwable ignored) {}
            }
        }
        return applied;
    }

    private Object[] numericCandidates21(int v) {
        return new Object[]{
                Integer.valueOf(v),
                Byte.valueOf((byte) v),
                Long.valueOf(v),
                Float.valueOf(v),
                new int[]{v},
                new byte[]{(byte) v}
        };
    }

    private Object[] floatCandidates21(float v) {
        return new Object[]{
                Float.valueOf(v),
                Double.valueOf(v),
                Integer.valueOf(Math.round(v * 100f)),
                new float[]{v}
        };
    }

    private Range<Integer> bestRange21(CameraCharacteristics chars, int fps) {
        Range<Integer>[] ranges = chars == null ? null :
                chars.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES);
        if (ranges == null) return null;
        Range<Integer> best = null;
        for (Range<Integer> r : ranges) {
            if (r.getLower() > fps || r.getUpper() < fps) continue;
            if (r.getLower() == fps && r.getUpper() == fps) return r;
            if (best == null || r.getLower() > best.getLower()) best = r;
        }
        return best;
    }

    private Size choosePreview21(CameraCharacteristics chars) {
        StreamConfigurationMap map = chars == null ? null :
                chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
        Size[] sizes = map == null ? null : map.getOutputSizes(SurfaceTexture.class);
        if (containsSize21(sizes, PREVIEW)) return PREVIEW;
        if (containsSize21(sizes, FHD)) return FHD;
        if (sizes != null && sizes.length > 0) return sizes[0];
        return PREVIEW;
    }

    private CameraCharacteristics currentChars21() {
        return field21("currentChars", CameraCharacteristics.class);
    }

    private Size selectedSize21() {
        Object o = field21("selectedSize", Object.class);
        return o instanceof Size ? (Size) o : UHD;
    }

    private boolean contains21(int[] values, int wanted) {
        if (values == null) return false;
        for (int v : values) if (v == wanted) return true;
        return false;
    }

    private boolean containsSize21(Size[] values, Size wanted) {
        if (values == null || wanted == null) return false;
        for (Size v : values) if (wanted.equals(v)) return true;
        return false;
    }

    private int dp21(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    private void toast21(String s) {
        runOnUiThread(() -> Toast.makeText(this, s, Toast.LENGTH_LONG).show());
    }

    @SuppressWarnings("unchecked")
    private <T> T exactField21(Class<?> owner, String name, Class<T> type) {
        try {
            Field f = owner.getDeclaredField(name);
            f.setAccessible(true);
            Object v = f.get(this);
            return v == null ? null : (T) v;
        } catch (Exception e) {
            return null;
        }
    }

    private boolean exactBool21(Class<?> owner, String name) {
        try {
            Field f = owner.getDeclaredField(name);
            f.setAccessible(true);
            return f.getBoolean(this);
        } catch (Exception e) {
            return false;
        }
    }

    private int exactInt21(Class<?> owner, String name, int fallback) {
        try {
            Field f = owner.getDeclaredField(name);
            f.setAccessible(true);
            return f.getInt(this);
        } catch (Exception e) {
            return fallback;
        }
    }

    private Object invokeExact21(Class<?> owner, String name, Class<?>[] types, Object... args) {
        try {
            Method m = owner.getDeclaredMethod(name, types);
            m.setAccessible(true);
            return m.invoke(this, args);
        } catch (Exception e) {
            return null;
        }
    }

    @SuppressWarnings("unchecked")
    private <T> T field21(String name, Class<T> type) {
        Class<?> c = getClass();
        while (c != null) {
            try {
                Field f = c.getDeclaredField(name);
                f.setAccessible(true);
                Object v = f.get(this);
                return v == null ? null : (T) v;
            } catch (NoSuchFieldException e) {
                c = c.getSuperclass();
            } catch (Exception e) {
                return null;
            }
        }
        return null;
    }

    private boolean bool21(String name) {
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

    private int int21(String name, int fallback) {
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

    private float float21(String name, float fallback) {
        Class<?> c = getClass();
        while (c != null) {
            try {
                Field f = c.getDeclaredField(name);
                f.setAccessible(true);
                return f.getFloat(this);
            } catch (NoSuchFieldException e) {
                c = c.getSuperclass();
            } catch (Exception e) {
                return fallback;
            }
        }
        return fallback;
    }

    private void setBoolean21(String name, boolean value) {
        setPrimitive21(name, 1, value, 0, 0L, null);
    }

    private void setLong21(String name, long value) {
        setPrimitive21(name, 3, false, 0, value, null);
    }

    private void setObject21(String name, Object value) {
        setPrimitive21(name, 4, false, 0, 0L, value);
    }

    private void setPrimitive21(String name, int kind, boolean b, int i, long l, Object o) {
        Class<?> c = getClass();
        while (c != null) {
            try {
                Field f = c.getDeclaredField(name);
                f.setAccessible(true);
                if (kind == 1) f.setBoolean(this, b);
                else if (kind == 2) f.setInt(this, i);
                else if (kind == 3) f.setLong(this, l);
                else f.set(this, o);
                return;
            } catch (NoSuchFieldException e) {
                c = c.getSuperclass();
            } catch (Exception e) {
                return;
            }
        }
    }

    private Object invokeAny21(String name, Class<?>[] types, Object... args) {
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
