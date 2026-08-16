package com.anas.x100zoom;

import android.Manifest;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.SurfaceTexture;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CameraMetadata;
import android.hardware.camera2.CaptureRequest;
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
import android.view.View;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * V18: real-device tele 60 fps investigation + settings header cleanup.
 *
 * The vivo X100 stock camera can record configurations that the independently
 * opened physical tele CameraDevice does not advertise to third-party Camera2.
 * V18 therefore keeps 60 fps selectable at >=3x when the logical rear camera
 * advertises the requested 60 fps mode, then attempts the official logical
 * multi-camera route with preview + recorder OutputConfigurations pinned to the
 * tele physical camera ID.
 *
 * This is not a fake 60-fps label: before recorder.start(), V18 asks
 * CameraDevice.isSessionConfigurationSupported when available and the actual
 * Camera2 HAL still gets the final say during createCaptureSession.
 */
public class V18LogicalTele60Activity extends V17HighSpeedActivity {
    private static final int ACCENT = 0xFFFFD129;
    private static final Size FHD = new Size(1920, 1080);
    private static final Size UHD = new Size(3840, 2160);

    private final Handler ui18 = new Handler(Looper.getMainLooper());
    private boolean installed18;
    private boolean logicalTeleRecording18;
    private boolean logicalTeleStarting18;
    private CameraDevice logicalTeleDevice18;
    private Surface previewSurface18;
    private Surface recordSurface18;
    private Uri logicalTeleOutput18;
    private int lastTeleUiSignature18;

    private TextView settingsTitle18;
    private TextView preserve18;
    private TextView capabilityNote18;
    private TextView fps60_18;
    private TextView video1080_18;
    private TextView video4k_18;
    private Button shutter18;
    private TextureView preview18;

    private final Runnable watcher18 = new Runnable() {
        @Override public void run() {
            if (!installed18) {
                tryInstall18();
            } else {
                // V17's own loop is stopped after install because its direct-physical
                // validator automatically demotes tele 60 -> 30 before this fallback
                // route has a chance to run.
                invoke18(V16CorrectnessActivity.class, "removeLegacyArtifacts", new Class[]{});
                invoke18(V16CorrectnessActivity.class, "syncSettings16", new Class[]{});
                invoke18(V16CorrectnessActivity.class, "syncPhotoViewport16", new Class[]{});

                boolean photo = bool18("photoMode");
                boolean hsRecording = exactBool18(V17HighSpeedActivity.class, "highSpeedRecording17");
                int hsChoice = exactInt18(V17HighSpeedActivity.class, "highSpeedChoice17", 0);

                if (!photo && !logicalTeleRecording18 && !logicalTeleStarting18 && !hsRecording) {
                    boolean special60 = selectedFps18() == 60 && isTeleUi18() && logicalTele60Candidate18(selectedSize18());
                    if (!special60 && hsChoice == 0) {
                        invoke18(V17HighSpeedActivity.class, "validateChoiceForCurrentPath17", new Class[]{});
                    }
                    invoke18(V17HighSpeedActivity.class, "syncControls17", new Class[]{});
                    patchTele60Controls18();
                }
            }
            ui18.postDelayed(this, 80L);
        }
    };

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        ui18.postDelayed(watcher18, 120L);
    }

    @Override protected void onPause() {
        if (logicalTeleRecording18 || logicalTeleStarting18) stopLogicalTele60_18(false);
        super.onPause();
    }

    @Override protected void onDestroy() {
        if (logicalTeleRecording18 || logicalTeleStarting18) stopLogicalTele60_18(false);
        ui18.removeCallbacksAndMessages(null);
        super.onDestroy();
    }

    private int dp18(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    @SuppressWarnings("unchecked")
    private void tryInstall18() {
        if (!exactBool18(V17HighSpeedActivity.class, "installed17")) return;

        Handler v17Handler = exactField18(V17HighSpeedActivity.class, "ui17", Handler.class);
        if (v17Handler != null) v17Handler.removeCallbacksAndMessages(null);

        settingsTitle18 = exactField18(X300UltraUiActivity.class, "settingsTitle", TextView.class);
        preserve18 = exactField18(V16CorrectnessActivity.class, "preserve16", TextView.class);
        capabilityNote18 = exactField18(V17HighSpeedActivity.class, "capabilityNote17", TextView.class);
        video1080_18 = exactField18(V17HighSpeedActivity.class, "video1080_17", TextView.class);
        video4k_18 = exactField18(V17HighSpeedActivity.class, "video4k_17", TextView.class);
        shutter18 = exactField18(MainActivity.class, "recordButton", Button.class);
        preview18 = exactField18(MainActivity.class, "textureView", TextureView.class);

        Map<Integer, TextView> fpsMap = exactField18(V17HighSpeedActivity.class, "fpsButtons17", Map.class);
        if (fpsMap != null) fps60_18 = fpsMap.get(60);

        fixSettingsHeader18();
        replaceModeListeners18(fpsMap);
        replaceShutter18();
        installed18 = true;
        patchTele60Controls18();
    }

    private void fixSettingsHeader18() {
        if (settingsTitle18 != null) {
            settingsTitle18.setTextSize(14.5f);
            FrameLayout.LayoutParams lp = settingsTitle18.getLayoutParams() instanceof FrameLayout.LayoutParams
                    ? (FrameLayout.LayoutParams) settingsTitle18.getLayoutParams() : null;
            if (lp != null) {
                lp.width = dp18(142);
                lp.rightMargin = dp18(10);
                settingsTitle18.setLayoutParams(lp);
            }
        }
        if (preserve18 != null) {
            FrameLayout.LayoutParams lp = preserve18.getLayoutParams() instanceof FrameLayout.LayoutParams
                    ? (FrameLayout.LayoutParams) preserve18.getLayoutParams() : null;
            if (lp != null) {
                lp.width = dp18(132);
                preserve18.setLayoutParams(lp);
            }
            preserve18.setTextSize(9.3f);
        }
    }

    private void replaceModeListeners18(Map<Integer, TextView> fpsMap) {
        if (fpsMap != null) {
            for (Map.Entry<Integer, TextView> e : fpsMap.entrySet()) {
                final int fps = e.getKey();
                e.getValue().setOnClickListener(v -> selectFps18(fps));
            }
        }
        if (video1080_18 != null) video1080_18.setOnClickListener(v -> selectResolution18(false));
        if (video4k_18 != null) video4k_18.setOnClickListener(v -> selectResolution18(true));
    }

    private void replaceShutter18() {
        if (shutter18 == null) return;
        shutter18.setOnClickListener(v -> {
            if (bool18("photoMode")) {
                invokeAny18("capturePhoto", new Class[]{});
                return;
            }
            if (logicalTeleRecording18 || logicalTeleStarting18) {
                stopLogicalTele60_18(true);
                return;
            }
            if (exactBool18(V17HighSpeedActivity.class, "highSpeedRecording17")) {
                invoke18(V17HighSpeedActivity.class, "stopHighSpeed17", new Class[]{boolean.class}, true);
                return;
            }
            if (bool18("recording") || bool18("recordingStarting")) {
                invoke18(V16CorrectnessActivity.class, "stopVerifiedRecording16", new Class[]{});
                return;
            }
            int hsChoice = exactInt18(V17HighSpeedActivity.class, "highSpeedChoice17", 0);
            if (hsChoice >= 120) {
                invoke18(V17HighSpeedActivity.class, "startHighSpeed17", new Class[]{});
                return;
            }

            if (selectedFps18() == 60 && isTeleUi18() && !directCurrent60_18(selectedSize18())
                    && logicalTele60Candidate18(selectedSize18())) {
                startLogicalTele60_18();
            } else {
                invoke18(V16CorrectnessActivity.class, "startVerifiedRecording16", new Class[]{});
            }
        });
    }

    private void selectFps18(int fps) {
        if (logicalTeleRecording18 || logicalTeleStarting18 || bool18("recording") || bool18("recordingStarting")) return;
        if (fps != 60) {
            invoke18(V17HighSpeedActivity.class, "selectFps17", new Class[]{int.class}, fps);
            return;
        }

        CameraCharacteristics current = field18("currentChars", CameraCharacteristics.class);
        Size size = selectedSize18();
        if (supportsConstant18(current, size, 60)) {
            invoke18(V17HighSpeedActivity.class, "selectFps17", new Class[]{int.class}, 60);
            return;
        }
        if (isTeleUi18() && logicalTele60Candidate18(size)) {
            setInt18("selectedFps", 60);
            setExactInt18(V17HighSpeedActivity.class, "highSpeedChoice17", 0);
            invoke18(V17HighSpeedActivity.class, "saveChoice17", new Class[]{});
            patchTele60Controls18();
            Toast.makeText(this, "60 fps will use the logical-camera telephoto route.", Toast.LENGTH_SHORT).show();
            return;
        }
        invoke18(V17HighSpeedActivity.class, "selectFps17", new Class[]{int.class}, 60);
    }

    private void selectResolution18(boolean fourK) {
        if (logicalTeleRecording18 || logicalTeleStarting18 || bool18("recording") || bool18("recordingStarting")) return;
        Size wanted = fourK ? UHD : FHD;
        int fps = selectedFps18();
        if (fps == 60 && isTeleUi18() && !directCurrent60_18(wanted) && logicalTele60Candidate18(wanted)) {
            setObject18("selectedSize", wanted);
            invoke18(V17HighSpeedActivity.class, "saveChoice17", new Class[]{});
            patchTele60Controls18();
            return;
        }
        invoke18(V17HighSpeedActivity.class, "selectResolution17", new Class[]{boolean.class}, fourK);
    }

    private void patchTele60Controls18() {
        if (fps60_18 == null || bool18("photoMode")) return;
        boolean special = isTeleUi18() && logicalTele60Candidate18(selectedSize18());
        boolean direct = directCurrent60_18(selectedSize18());
        boolean enabled = direct || special;
        boolean selected = selectedFps18() == 60 && exactInt18(V17HighSpeedActivity.class, "highSpeedChoice17", 0) < 120;
        fps60_18.setEnabled(enabled);
        fps60_18.setAlpha(enabled ? 1f : 0.30f);
        fps60_18.setTextColor(selected && enabled ? Color.BLACK : (enabled ? Color.WHITE : 0xFF777777));
        fps60_18.setBackground(selected && enabled ? rounded18(ACCENT, 8) : null);

        if (capabilityNote18 != null) {
            int sig = (special ? 1 : 0) * 10000 + selectedFps18() * 10 + (selectedSize18().getWidth() >= 3800 ? 1 : 0);
            if (sig != lastTeleUiSignature18) {
                lastTeleUiSignature18 = sig;
                if (special) {
                    capabilityNote18.setText("Tele 60 fps: V18 will force the physical tele lens through the logical multi-camera session and verify whether Vivo accepts it.");
                } else {
                    capabilityNote18.setText("24–60 use verified Camera2 timing. 120/240 appear only for advertised constrained high-speed combinations.");
                }
            }
        }
    }

    private android.graphics.drawable.GradientDrawable rounded18(int color, int radius) {
        android.graphics.drawable.GradientDrawable d = new android.graphics.drawable.GradientDrawable();
        d.setColor(color);
        d.setCornerRadius(dp18(radius));
        return d;
    }

    private boolean isTeleUi18() {
        return bool18("activeTele") || float18("requestedUiZoom", 1f) >= 3f;
    }

    private int selectedFps18() {
        return int18("selectedFps", 30);
    }

    private Size selectedSize18() {
        Object o = field18("selectedSize", Object.class);
        return o instanceof Size ? (Size) o : FHD;
    }

    private boolean directCurrent60_18(Size size) {
        return supportsConstant18(field18("currentChars", CameraCharacteristics.class), size, 60);
    }

    private boolean logicalTele60Candidate18(Size size) {
        CameraCharacteristics logical = field18("logicalChars", CameraCharacteristics.class);
        String teleId = field18("teleCameraId", String.class);
        if (logical == null || teleId == null || !logical.getPhysicalCameraIds().contains(teleId)) return false;
        return supportsConstant18(logical, size, 60);
    }

    private boolean supportsConstant18(CameraCharacteristics chars, Size size, int fps) {
        if (chars == null || size == null) return false;
        StreamConfigurationMap map = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
        if (map == null || !containsSize18(map.getOutputSizes(MediaRecorder.class), size)) return false;
        Range<Integer> fixed = exactRange18(chars, fps);
        if (fixed == null) return false;
        long min = 0L;
        try { min = map.getOutputMinFrameDuration(MediaRecorder.class, size); } catch (Exception ignored) {}
        long needed = Math.round(1_000_000_000d / fps);
        return min <= 0L || min <= needed + 750_000L;
    }

    private Range<Integer> exactRange18(CameraCharacteristics chars, int fps) {
        Range<Integer>[] ranges = chars == null ? null
                : chars.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES);
        if (ranges == null) return null;
        for (Range<Integer> r : ranges) if (r.getLower() == fps && r.getUpper() == fps) return r;
        return null;
    }

    private boolean containsSize18(Size[] sizes, Size wanted) {
        if (sizes == null) return false;
        for (Size s : sizes) if (wanted.equals(s)) return true;
        return false;
    }

    private boolean hasPhysicalKey18(CameraCharacteristics logical, CaptureRequest.Key<?> wanted) {
        List<CaptureRequest.Key<?>> keys = logical == null ? null : logical.getAvailablePhysicalCameraRequestKeys();
        if (keys == null) return false;
        for (CaptureRequest.Key<?> key : keys) if (wanted.equals(key)) return true;
        return false;
    }

    private void startLogicalTele60_18() {
        if (logicalTeleStarting18 || logicalTeleRecording18) return;
        CameraManager manager = field18("cameraManager", CameraManager.class);
        Handler cameraHandler = field18("cameraHandler", Handler.class);
        String logicalId = field18("logicalCameraId", String.class);
        String teleId = field18("teleCameraId", String.class);
        CameraCharacteristics logical = field18("logicalChars", CameraCharacteristics.class);
        Size size = selectedSize18();
        if (manager == null || cameraHandler == null || logicalId == null || teleId == null || logical == null || preview18 == null) {
            toast18("Logical tele camera route is not ready.");
            return;
        }
        if (!logicalTele60Candidate18(size)) {
            toast18("The logical rear camera does not expose this 60 fps resolution.");
            return;
        }
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            toast18("Camera permission is required.");
            return;
        }

        logicalTeleStarting18 = true;
        try {
            invokeAny18("prepareTransitionOverlay", new Class[]{});
            invokeAny18("prepareRecorder", new Class[]{});
            logicalTeleOutput18 = field18("outputUri", Uri.class);
            invokeAny18("closeSessionOnly", new Class[]{});
            CameraDevice old = field18("cameraDevice", CameraDevice.class);
            if (old != null) {
                try { old.close(); } catch (Exception ignored) {}
                setObject18("cameraDevice", null);
            }
            manager.openCamera(logicalId, new CameraDevice.StateCallback() {
                @Override public void onOpened(CameraDevice camera) {
                    logicalTeleDevice18 = camera;
                    setObject18("cameraDevice", camera);
                    setObject18("currentCameraId", logicalId);
                    setObject18("currentChars", logical);
                    setBoolean18("activeTele", true);
                    buildLogicalTele60Session18(camera, logical, teleId, size, cameraHandler);
                }

                @Override public void onDisconnected(CameraDevice camera) {
                    try { camera.close(); } catch (Exception ignored) {}
                    failLogicalTele60_18("Logical camera disconnected.");
                }

                @Override public void onError(CameraDevice camera, int error) {
                    try { camera.close(); } catch (Exception ignored) {}
                    failLogicalTele60_18("Logical tele 60 camera error " + error + ".");
                }
            }, cameraHandler);
        } catch (Exception e) {
            failLogicalTele60_18("Tele 60 setup failed: " + e.getMessage());
        }
    }

    private void buildLogicalTele60Session18(CameraDevice camera, CameraCharacteristics logical,
                                              String teleId, Size size, Handler cameraHandler) {
        try {
            MediaRecorder recorder = field18("recorder", MediaRecorder.class);
            if (recorder == null) throw new IllegalStateException("Recorder was not prepared");
            SurfaceTexture st = preview18.getSurfaceTexture();
            if (st == null) throw new IllegalStateException("Preview surface unavailable");
            st.setDefaultBufferSize(FHD.getWidth(), FHD.getHeight());
            previewSurface18 = new Surface(st);
            recordSurface18 = recorder.getSurface();

            OutputConfiguration previewOutput = new OutputConfiguration(previewSurface18);
            previewOutput.setPhysicalCameraId(teleId);
            OutputConfiguration recorderOutput = new OutputConfiguration(recordSurface18);
            recorderOutput.setPhysicalCameraId(teleId);

            Set<String> physicalIds = new HashSet<>();
            physicalIds.add(teleId);
            CaptureRequest.Builder builder = camera.createCaptureRequest(CameraDevice.TEMPLATE_RECORD, physicalIds);
            builder.addTarget(previewSurface18);
            builder.addTarget(recordSurface18);
            builder.set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO);
            Range<Integer> fixed60 = exactRange18(logical, 60);
            if (fixed60 == null) throw new IllegalStateException("Logical camera lost fixed 60 fps range");
            builder.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, fixed60);

            int[] af = logical.get(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES);
            if (containsInt18(af, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO)) {
                builder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO);
            }
            invokeAny18("enableBestStabilization", new Class[]{CaptureRequest.Builder.class, CameraCharacteristics.class}, builder, logical);

            if (hasPhysicalKey18(logical, CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE)) {
                builder.setPhysicalCameraKey(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, fixed60, teleId);
            }
            if (hasPhysicalKey18(logical, CaptureRequest.CONTROL_ZOOM_RATIO)) {
                float teleZoom = Math.max(1f, float18("requestedUiZoom", 3f) / 3f);
                CameraCharacteristics teleChars = field18("teleChars", CameraCharacteristics.class);
                Range<Float> zr = teleChars == null ? null : teleChars.get(CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE);
                if (zr != null) teleZoom = Math.max(zr.getLower(), Math.min(teleZoom, zr.getUpper()));
                builder.setPhysicalCameraKey(CaptureRequest.CONTROL_ZOOM_RATIO, teleZoom, teleId);
            }

            List<OutputConfiguration> outputs = new ArrayList<>();
            outputs.add(previewOutput);
            outputs.add(recorderOutput);
            SessionConfiguration config = new SessionConfiguration(
                    SessionConfiguration.SESSION_REGULAR,
                    outputs,
                    command -> cameraHandler.post(command),
                    new CameraCaptureSession.StateCallback() {
                        @Override public void onConfigured(CameraCaptureSession session) {
                            try {
                                setObject18("captureSession", session);
                                setObject18("repeatingBuilder", builder);
                                session.setRepeatingRequest(builder.build(), null, cameraHandler);
                                recorder.start();
                                logicalTeleStarting18 = false;
                                logicalTeleRecording18 = true;
                                setBoolean18("recordingStarting", false);
                                setBoolean18("recording", true);
                                setLong18("recordStartedAtMs", System.currentTimeMillis());
                                runOnUiThread(() -> {
                                    invokeAny18("finishTransitionOverlay", new Class[]{});
                                    Toast.makeText(V18LogicalTele60Activity.this,
                                            "Telephoto 60 fps via logical multi-camera route", Toast.LENGTH_SHORT).show();
                                });
                            } catch (Exception e) {
                                failLogicalTele60_18("Logical tele 60 start failed: " + e.getMessage());
                            }
                        }

                        @Override public void onConfigureFailed(CameraCaptureSession session) {
                            failLogicalTele60_18("Vivo HAL rejected logical→tele 60 fps session.");
                        }
                    });
            config.setSessionParameters(builder.build());

            boolean supported = true;
            try { supported = camera.isSessionConfigurationSupported(config); }
            catch (UnsupportedOperationException ignored) {}
            if (!supported) {
                throw new IllegalStateException("Android reports logical→tele 60 fps stream combination unsupported");
            }
            setBoolean18("recordingStarting", true);
            camera.createCaptureSession(config);
        } catch (Exception e) {
            failLogicalTele60_18("Logical tele 60 session failed: " + e.getMessage());
        }
    }

    private void stopLogicalTele60_18(boolean restartCamera) {
        Uri saved = logicalTeleOutput18 != null ? logicalTeleOutput18 : field18("outputUri", Uri.class);
        boolean keep = false;
        try {
            CameraCaptureSession session = field18("captureSession", CameraCaptureSession.class);
            if (session != null) {
                try { session.stopRepeating(); } catch (Exception ignored) {}
                try { session.abortCaptures(); } catch (Exception ignored) {}
            }
            MediaRecorder recorder = field18("recorder", MediaRecorder.class);
            if (logicalTeleRecording18 && recorder != null) {
                recorder.stop();
                keep = true;
            }
        } catch (Exception e) {
            toast18("Tele 60 recording failed: " + e.getMessage());
        } finally {
            invokeAny18("finalizeOutput", new Class[]{boolean.class}, keep);
            setBoolean18("recording", false);
            setBoolean18("recordingStarting", false);
            logicalTeleRecording18 = false;
            logicalTeleStarting18 = false;
            invokeAny18("safeResetRecorder", new Class[]{});
            setObject18("captureSession", null);
            if (logicalTeleDevice18 != null) {
                try { logicalTeleDevice18.close(); } catch (Exception ignored) {}
                logicalTeleDevice18 = null;
            }
            setObject18("cameraDevice", null);
            releaseSurfaces18();
        }

        if (keep && saved != null) {
            invoke18(V16CorrectnessActivity.class, "verifyEncodedFps16",
                    new Class[]{Uri.class, float.class}, saved, 0f);
        }
        if (restartCamera) restoreTeleRoute18();
    }

    private void failLogicalTele60_18(String message) {
        logicalTeleRecording18 = false;
        logicalTeleStarting18 = false;
        setBoolean18("recording", false);
        setBoolean18("recordingStarting", false);
        invokeAny18("finalizeOutput", new Class[]{boolean.class}, false);
        invokeAny18("safeResetRecorder", new Class[]{});
        if (logicalTeleDevice18 != null) {
            try { logicalTeleDevice18.close(); } catch (Exception ignored) {}
            logicalTeleDevice18 = null;
        }
        setObject18("cameraDevice", null);
        releaseSurfaces18();
        restoreTeleRoute18();
        toast18(message);
    }

    private void restoreTeleRoute18() {
        Handler cameraHandler = field18("cameraHandler", Handler.class);
        if (cameraHandler == null) return;
        cameraHandler.postDelayed(() -> {
            setBoolean18("activeTele", false);
            invokeAny18("openRoute", new Class[]{boolean.class}, true);
        }, 140L);
    }

    private void releaseSurfaces18() {
        if (previewSurface18 != null) {
            try { previewSurface18.release(); } catch (Exception ignored) {}
            previewSurface18 = null;
        }
        // MediaRecorder owns recordSurface18; releasing the recorder is sufficient.
        recordSurface18 = null;
        logicalTeleOutput18 = null;
    }

    private boolean containsInt18(int[] values, int wanted) {
        if (values == null) return false;
        for (int v : values) if (v == wanted) return true;
        return false;
    }

    private void toast18(String text) {
        runOnUiThread(() -> Toast.makeText(this, text, Toast.LENGTH_LONG).show());
    }

    @SuppressWarnings("unchecked")
    private <T> T exactField18(Class<?> owner, String name, Class<T> type) {
        try {
            Field f = owner.getDeclaredField(name);
            f.setAccessible(true);
            Object value = f.get(this);
            return value == null ? null : (T) value;
        } catch (Exception e) {
            return null;
        }
    }

    private boolean exactBool18(Class<?> owner, String name) {
        try {
            Field f = owner.getDeclaredField(name);
            f.setAccessible(true);
            return f.getBoolean(this);
        } catch (Exception e) {
            return false;
        }
    }

    private int exactInt18(Class<?> owner, String name, int fallback) {
        try {
            Field f = owner.getDeclaredField(name);
            f.setAccessible(true);
            return f.getInt(this);
        } catch (Exception e) {
            return fallback;
        }
    }

    private void setExactInt18(Class<?> owner, String name, int value) {
        try {
            Field f = owner.getDeclaredField(name);
            f.setAccessible(true);
            f.setInt(this, value);
        } catch (Exception ignored) {}
    }

    @SuppressWarnings("unchecked")
    private <T> T field18(String name, Class<T> type) {
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

    private boolean bool18(String name) {
        Class<?> c = getClass();
        while (c != null) {
            try {
                Field f = c.getDeclaredField(name);
                f.setAccessible(true);
                return f.getBoolean(this);
            } catch (NoSuchFieldException e) { c = c.getSuperclass(); }
            catch (Exception e) { return false; }
        }
        return false;
    }

    private int int18(String name, int fallback) {
        Class<?> c = getClass();
        while (c != null) {
            try {
                Field f = c.getDeclaredField(name);
                f.setAccessible(true);
                return f.getInt(this);
            } catch (NoSuchFieldException e) { c = c.getSuperclass(); }
            catch (Exception e) { return fallback; }
        }
        return fallback;
    }

    private long long18(String name, long fallback) {
        Class<?> c = getClass();
        while (c != null) {
            try {
                Field f = c.getDeclaredField(name);
                f.setAccessible(true);
                return f.getLong(this);
            } catch (NoSuchFieldException e) { c = c.getSuperclass(); }
            catch (Exception e) { return fallback; }
        }
        return fallback;
    }

    private float float18(String name, float fallback) {
        Class<?> c = getClass();
        while (c != null) {
            try {
                Field f = c.getDeclaredField(name);
                f.setAccessible(true);
                return f.getFloat(this);
            } catch (NoSuchFieldException e) { c = c.getSuperclass(); }
            catch (Exception e) { return fallback; }
        }
        return fallback;
    }

    private void setBoolean18(String name, boolean value) { setField18(name, value); }
    private void setInt18(String name, int value) { setField18(name, value); }
    private void setLong18(String name, long value) { setField18(name, value); }
    private void setObject18(String name, Object value) { setField18(name, value); }

    private void setField18(String name, Object value) {
        Class<?> c = getClass();
        while (c != null) {
            try {
                Field f = c.getDeclaredField(name);
                f.setAccessible(true);
                if (value instanceof Boolean) f.setBoolean(this, (Boolean) value);
                else if (value instanceof Integer) f.setInt(this, (Integer) value);
                else if (value instanceof Long) f.setLong(this, (Long) value);
                else f.set(this, value);
                return;
            } catch (NoSuchFieldException e) { c = c.getSuperclass(); }
            catch (Exception e) { return; }
        }
    }

    private Object invokeAny18(String name, Class<?>[] types, Object... args) {
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

    private Object invoke18(Class<?> owner, String name, Class<?>[] types, Object... args) {
        try {
            Method m = owner.getDeclaredMethod(name, types);
            m.setAccessible(true);
            return m.invoke(this, args);
        } catch (Exception e) {
            return null;
        }
    }
}
