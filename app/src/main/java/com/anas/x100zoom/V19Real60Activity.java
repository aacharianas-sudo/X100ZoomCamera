package com.anas.x100zoom;

import android.Manifest;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.SurfaceTexture;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CameraMetadata;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.params.OutputConfiguration;
import android.hardware.camera2.params.SessionConfiguration;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.media.MediaCodecInfo;
import android.media.MediaCodecList;
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
 * V19: real 60fps attempt policy for vivo X100.
 *
 * Earlier builds required an exact [60,60] CONTROL_AE range and rejected every
 * 60fps mode on devices where vivo exposes only a variable range such as [30,60].
 * V19 allows any AE range whose upper bound reaches the requested normal FPS,
 * asks MediaRecorder for that exact output FPS, uses a lower-bandwidth preview
 * stream for 50/60fps, and verifies the final MP4 timestamps after recording.
 *
 * At >=3x, when the independently opened tele CameraDevice cannot reach 60,
 * V19 tries the official logical-camera -> physical-tele OutputConfiguration
 * route with the same relaxed 60-capable range.
 */
public class V19Real60Activity extends V18LogicalTele60Activity {
    private static final int ACCENT = 0xFFFFD129;
    private static final Size FHD = new Size(1920, 1080);
    private static final Size UHD = new Size(3840, 2160);
    private static final Size PREVIEW_60 = new Size(1280, 720);

    private final Handler ui19 = new Handler(Looper.getMainLooper());
    private boolean installed19;
    private boolean logicalTeleStarting19;
    private boolean logicalTeleRecording19;
    private CameraDevice logicalTeleDevice19;
    private Surface logicalPreviewSurface19;
    private Surface logicalRecordSurface19;
    private Uri logicalOutput19;

    private Map<Integer, TextView> fpsMap19;
    private TextView video1080_19;
    private TextView video4k_19;
    private TextView note19;
    private Button shutter19;
    private TextureView preview19;

    private final Runnable watcher19 = new Runnable() {
        @Override public void run() {
            if (!installed19) tryInstall19();
            else {
                invokeExact19(V16CorrectnessActivity.class, "removeLegacyArtifacts", new Class[]{});
                invokeExact19(V16CorrectnessActivity.class, "syncSettings16", new Class[]{});
                invokeExact19(V16CorrectnessActivity.class, "syncPhotoViewport16", new Class[]{});
                syncNormalControls19();
            }
            ui19.postDelayed(this, 90L);
        }
    };

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        ui19.postDelayed(watcher19, 160L);
    }

    @Override protected void onPause() {
        if (logicalTeleStarting19 || logicalTeleRecording19) stopLogicalTele19(false);
        super.onPause();
    }

    @Override protected void onDestroy() {
        if (logicalTeleStarting19 || logicalTeleRecording19) stopLogicalTele19(false);
        ui19.removeCallbacksAndMessages(null);
        super.onDestroy();
    }

    @SuppressWarnings("unchecked")
    private void tryInstall19() {
        if (!exactBool19(V18LogicalTele60Activity.class, "installed18")) return;

        Handler parent = exactField19(V18LogicalTele60Activity.class, "ui18", Handler.class);
        if (parent != null) parent.removeCallbacksAndMessages(null);

        fpsMap19 = exactField19(V17HighSpeedActivity.class, "fpsButtons17", Map.class);
        video1080_19 = exactField19(V17HighSpeedActivity.class, "video1080_17", TextView.class);
        video4k_19 = exactField19(V17HighSpeedActivity.class, "video4k_17", TextView.class);
        note19 = exactField19(V17HighSpeedActivity.class, "capabilityNote17", TextView.class);
        shutter19 = exactField19(MainActivity.class, "recordButton", Button.class);
        preview19 = exactField19(MainActivity.class, "textureView", TextureView.class);
        if (fpsMap19 == null || shutter19 == null || preview19 == null) return;

        for (Map.Entry<Integer, TextView> entry : fpsMap19.entrySet()) {
            final int fps = entry.getKey();
            entry.getValue().setOnClickListener(v -> selectFps19(fps));
        }
        if (video1080_19 != null) video1080_19.setOnClickListener(v -> selectResolution19(false));
        if (video4k_19 != null) video4k_19.setOnClickListener(v -> selectResolution19(true));
        installShutter19();

        installed19 = true;
        syncNormalControls19();
    }

    private void installShutter19() {
        shutter19.setOnClickListener(v -> {
            if (bool19("photoMode")) {
                invokeAny19("capturePhoto", new Class[]{});
                return;
            }
            if (logicalTeleStarting19 || logicalTeleRecording19) {
                stopLogicalTele19(true);
                return;
            }
            if (exactBool19(V17HighSpeedActivity.class, "highSpeedRecording17")) {
                invokeExact19(V17HighSpeedActivity.class, "stopHighSpeed17", new Class[]{boolean.class}, true);
                return;
            }
            if (bool19("recording") || bool19("recordingStarting")) {
                invokeExact19(V16CorrectnessActivity.class, "stopVerifiedRecording16", new Class[]{});
                return;
            }

            int highSpeed = exactInt19(V17HighSpeedActivity.class, "highSpeedChoice17", 0);
            if (highSpeed >= 120) {
                invokeExact19(V17HighSpeedActivity.class, "startHighSpeed17", new Class[]{});
                return;
            }

            int fps = selectedFps19();
            Size size = selectedSize19();
            if (fps == 60 && isTeleUi19() && !supportsNormalAttempt19(currentChars19(), size, 60)
                    && logicalTeleCandidate19(size, 60)) {
                startLogicalTele19();
            } else {
                startNormalRecording19();
            }
        });
    }

    private void selectFps19(int fps) {
        if (isBusy19()) return;
        if (fps >= 120) {
            invokeExact19(V17HighSpeedActivity.class, "selectFps17", new Class[]{int.class}, fps);
            return;
        }
        Size size = selectedSize19();
        CameraCharacteristics current = currentChars19();
        boolean allowed = supportsNormalAttempt19(current, size, fps);
        if (!allowed && fps == 60 && isTeleUi19()) allowed = logicalTeleCandidate19(size, 60);
        if (!allowed) {
            toast19("This camera path does not advertise a " + fps + " fps-capable range at this resolution.");
            return;
        }
        setInt19("selectedFps", fps);
        setExactInt19(V17HighSpeedActivity.class, "highSpeedChoice17", 0);
        invokeExact19(V17HighSpeedActivity.class, "saveChoice17", new Class[]{});
        Handler h = field19("cameraHandler", Handler.class);
        if (h != null) h.post(() -> invokeAny19("startPreviewSession", new Class[]{}));
        syncNormalControls19();
    }

    private void selectResolution19(boolean fourK) {
        if (isBusy19()) return;
        Size wanted = fourK ? UHD : FHD;
        int hs = exactInt19(V17HighSpeedActivity.class, "highSpeedChoice17", 0);
        if (hs >= 120) {
            invokeExact19(V17HighSpeedActivity.class, "selectResolution17", new Class[]{boolean.class}, fourK);
            return;
        }
        int fps = selectedFps19();
        boolean allowed = supportsNormalAttempt19(currentChars19(), wanted, fps);
        if (!allowed && fps == 60 && isTeleUi19()) allowed = logicalTeleCandidate19(wanted, 60);
        if (!allowed) {
            toast19((fourK ? "4K" : "1080P") + " is not advertised for the selected FPS on this path.");
            return;
        }
        setObject19("selectedSize", wanted);
        invokeExact19(V17HighSpeedActivity.class, "saveChoice17", new Class[]{});
        Handler h = field19("cameraHandler", Handler.class);
        if (h != null) h.post(() -> invokeAny19("startPreviewSession", new Class[]{}));
        syncNormalControls19();
    }

    private void syncNormalControls19() {
        if (!installed19 || bool19("photoMode") || fpsMap19 == null) return;
        int hs = exactInt19(V17HighSpeedActivity.class, "highSpeedChoice17", 0);
        Size size = selectedSize19();
        CameraCharacteristics current = currentChars19();
        int selected = hs >= 120 ? hs : selectedFps19();

        for (Map.Entry<Integer, TextView> e : fpsMap19.entrySet()) {
            int fps = e.getKey();
            boolean enabled;
            if (fps >= 120) {
                Object result = invokeExact19(V17HighSpeedActivity.class, "supportsHighSpeed17",
                        new Class[]{CameraCharacteristics.class, Size.class, int.class}, current, size, fps);
                enabled = result instanceof Boolean && (Boolean) result;
            } else {
                enabled = supportsNormalAttempt19(current, size, fps);
                if (!enabled && fps == 60 && isTeleUi19()) enabled = logicalTeleCandidate19(size, 60);
            }
            styleChoice19(e.getValue(), selected == fps, enabled);
        }

        boolean fourK = size.getWidth() >= 3800;
        if (video1080_19 != null) {
            boolean enabled = hs >= 120 ? highSpeedSupports19(FHD, hs)
                    : supportsResolution19(FHD, selectedFps19());
            styleChoice19(video1080_19, !fourK, enabled);
        }
        if (video4k_19 != null) {
            boolean enabled = hs >= 120 ? highSpeedSupports19(UHD, hs)
                    : supportsResolution19(UHD, selectedFps19());
            styleChoice19(video4k_19, fourK, enabled);
        }

        if (note19 != null && hs < 120) {
            int fps = selectedFps19();
            Range<Integer> r = bestRange19(current, fps);
            if (fps == 60 && isTeleUi19() && r == null && logicalTeleCandidate19(size, 60)) {
                note19.setText("60 fps: direct tele does not advertise it; recording will try logical camera → physical tele and verify the saved MP4.");
            } else if (r != null) {
                note19.setText("Requested " + fps + " fps using Camera2 range " + r.getLower() + "–" + r.getUpper()
                        + ". Final MP4 FPS is measured after recording.");
            }
        }
    }

    private boolean supportsResolution19(Size size, int fps) {
        if (supportsNormalAttempt19(currentChars19(), size, fps)) return true;
        return fps == 60 && isTeleUi19() && logicalTeleCandidate19(size, fps);
    }

    private boolean highSpeedSupports19(Size size, int fps) {
        Object result = invokeExact19(V17HighSpeedActivity.class, "supportsHighSpeed17",
                new Class[]{CameraCharacteristics.class, Size.class, int.class}, currentChars19(), size, fps);
        return result instanceof Boolean && (Boolean) result;
    }

    private void styleChoice19(TextView view, boolean selected, boolean enabled) {
        if (view == null) return;
        view.setEnabled(enabled);
        view.setAlpha(enabled ? 1f : 0.28f);
        view.setTextColor(selected && enabled ? Color.BLACK : (enabled ? Color.WHITE : 0xFF777777));
        view.setBackground(selected && enabled ? rounded19(ACCENT, 8) : null);
    }

    private android.graphics.drawable.GradientDrawable rounded19(int color, int radius) {
        android.graphics.drawable.GradientDrawable d = new android.graphics.drawable.GradientDrawable();
        d.setColor(color);
        d.setCornerRadius(Math.round(radius * getResources().getDisplayMetrics().density));
        return d;
    }

    private boolean supportsNormalAttempt19(CameraCharacteristics chars, Size size, int fps) {
        if (chars == null || size == null || fps <= 0 || fps >= 120) return false;
        StreamConfigurationMap map = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
        if (map == null || !containsSize19(map.getOutputSizes(MediaRecorder.class), size)) return false;
        if (bestRange19(chars, fps) == null) return false;
        return encoderSupports19(size, fps);
    }

    private Range<Integer> bestRange19(CameraCharacteristics chars, int fps) {
        Range<Integer>[] ranges = chars == null ? null
                : chars.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES);
        if (ranges == null) return null;
        Range<Integer> best = null;
        for (Range<Integer> r : ranges) {
            if (r.getLower() > fps || r.getUpper() < fps) continue;
            if (r.getLower() == fps && r.getUpper() == fps) return r;
            if (r.getUpper() == fps) {
                if (best == null || best.getUpper() != fps || r.getLower() > best.getLower()) best = r;
            } else if (best == null || (best.getUpper() != fps && r.getLower() > best.getLower())) {
                best = r;
            }
        }
        return best;
    }

    private boolean encoderSupports19(Size size, int fps) {
        String[] types = {"video/hevc", "video/avc"};
        for (MediaCodecInfo info : new MediaCodecList(MediaCodecList.ALL_CODECS).getCodecInfos()) {
            if (!info.isEncoder()) continue;
            for (String wanted : types) {
                boolean match = false;
                for (String type : info.getSupportedTypes()) if (wanted.equalsIgnoreCase(type)) { match = true; break; }
                if (!match) continue;
                try {
                    MediaCodecInfo.VideoCapabilities v = info.getCapabilitiesForType(wanted).getVideoCapabilities();
                    if (v != null && v.areSizeAndRateSupported(size.getWidth(), size.getHeight(), fps)) return true;
                } catch (Exception ignored) {}
            }
        }
        return false;
    }

    private void startNormalRecording19() {
        CameraDevice camera = field19("cameraDevice", CameraDevice.class);
        CameraCharacteristics chars = currentChars19();
        Handler cameraHandler = field19("cameraHandler", Handler.class);
        Size size = selectedSize19();
        int fps = selectedFps19();
        Range<Integer> range = bestRange19(chars, fps);
        if (camera == null || chars == null || cameraHandler == null || range == null || !preview19.isAvailable()) {
            toast19("Camera/FPS route is not ready.");
            return;
        }
        if (!supportsNormalAttempt19(chars, size, fps)) {
            toast19("This public Camera2 path cannot attempt " + size.getWidth() + "×" + size.getHeight() + " at " + fps + " fps.");
            return;
        }

        try {
            invokeAny19("prepareRecorder", new Class[]{});
            MediaRecorder recorder = field19("recorder", MediaRecorder.class);
            if (recorder == null) throw new IllegalStateException("Recorder preparation failed");
            setBoolean19("recordingStarting", true);
            invokeAny19("closeSessionOnly", new Class[]{});

            SurfaceTexture st = preview19.getSurfaceTexture();
            if (st == null) throw new IllegalStateException("Preview surface unavailable");
            Size previewSize = choosePreview19(chars, fps);
            st.setDefaultBufferSize(previewSize.getWidth(), previewSize.getHeight());
            Surface previewSurface = new Surface(st);
            Surface recordSurface = recorder.getSurface();

            CaptureRequest.Builder builder = camera.createCaptureRequest(CameraDevice.TEMPLATE_RECORD);
            builder.addTarget(previewSurface);
            builder.addTarget(recordSurface);
            builder.set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO);
            builder.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, range);
            int[] af = chars.get(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES);
            if (containsInt19(af, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO)) {
                builder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO);
            }
            invokeAny19("setZoomOnBuilder", new Class[]{CaptureRequest.Builder.class}, builder);
            configureStabilizationForFps19(builder, chars, fps);

            List<OutputConfiguration> outputs = new ArrayList<>();
            outputs.add(new OutputConfiguration(previewSurface));
            outputs.add(new OutputConfiguration(recordSurface));

            CameraCaptureSession.CaptureCallback fpsCallback = exactField19(
                    V16CorrectnessActivity.class, "sensorFpsCallback", CameraCaptureSession.CaptureCallback.class);
            SessionConfiguration config = new SessionConfiguration(SessionConfiguration.SESSION_REGULAR,
                    outputs, command -> cameraHandler.post(command), new CameraCaptureSession.StateCallback() {
                @Override public void onConfigured(CameraCaptureSession session) {
                    try {
                        setObject19("captureSession", session);
                        setObject19("repeatingBuilder", builder);
                        setLongExact19(V16CorrectnessActivity.class, "sensorStartNs", 0L);
                        setIntExact19(V16CorrectnessActivity.class, "sensorFrames", 0);
                        setFloatExact19(V16CorrectnessActivity.class, "measuredSensorFps", 0f);
                        session.setRepeatingRequest(builder.build(), fpsCallback, cameraHandler);
                        recorder.start();
                        setBoolean19("recordingStarting", false);
                        setBoolean19("recording", true);
                        setLong19("recordStartedAtMs", System.currentTimeMillis());
                        runOnUiThread(() -> Toast.makeText(V19Real60Activity.this,
                                "Recording " + fps + " fps request • Camera2 " + range.getLower() + "–" + range.getUpper(),
                                Toast.LENGTH_SHORT).show());
                    } catch (Exception e) {
                        failNormal19("Recording start failed: " + e.getMessage());
                    }
                }
                @Override public void onConfigureFailed(CameraCaptureSession session) {
                    failNormal19("Vivo HAL rejected this " + fps + " fps stream combination.");
                }
            });
            config.setSessionParameters(builder.build());
            camera.createCaptureSession(config);
        } catch (Exception e) {
            failNormal19("Recording setup failed: " + e.getMessage());
        }
    }

    private void failNormal19(String text) {
        setBoolean19("recordingStarting", false);
        setBoolean19("recording", false);
        invokeAny19("safeResetRecorder", new Class[]{});
        Handler h = field19("cameraHandler", Handler.class);
        if (h != null) h.post(() -> invokeAny19("startPreviewSession", new Class[]{}));
        toast19(text);
    }

    private Size choosePreview19(CameraCharacteristics chars, int fps) {
        StreamConfigurationMap map = chars == null ? null : chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
        if (map == null) return fps >= 50 ? PREVIEW_60 : FHD;
        Size[] sizes = map.getOutputSizes(SurfaceTexture.class);
        if (sizes == null) return fps >= 50 ? PREVIEW_60 : FHD;
        if (fps < 50 && containsSize19(sizes, FHD)) return FHD;
        if (containsSize19(sizes, PREVIEW_60)) return PREVIEW_60;

        Size best = null;
        for (Size s : sizes) {
            float ratio = s.getWidth() / (float) s.getHeight();
            if (Math.abs(ratio - 16f / 9f) > 0.03f) continue;
            if (s.getWidth() > 1920) continue;
            if (best == null || Math.abs(s.getWidth() - 1280) < Math.abs(best.getWidth() - 1280)) best = s;
        }
        return best != null ? best : (sizes.length > 0 ? sizes[0] : PREVIEW_60);
    }

    private void configureStabilizationForFps19(CaptureRequest.Builder b, CameraCharacteristics chars, int fps) {
        if (fps >= 50) {
            try { b.set(CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE,
                    CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_OFF); } catch (Exception ignored) {}
            int[] ois = chars.get(CameraCharacteristics.LENS_INFO_AVAILABLE_OPTICAL_STABILIZATION);
            if (containsInt19(ois, CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE_ON)) {
                try { b.set(CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE,
                        CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE_ON); } catch (Exception ignored) {}
            }
        } else {
            invokeAny19("enableBestStabilization",
                    new Class[]{CaptureRequest.Builder.class, CameraCharacteristics.class}, b, chars);
        }
    }

    private boolean logicalTeleCandidate19(Size size, int fps) {
        CameraCharacteristics logical = field19("logicalChars", CameraCharacteristics.class);
        String teleId = field19("teleCameraId", String.class);
        if (logical == null || teleId == null || !logical.getPhysicalCameraIds().contains(teleId)) return false;
        return supportsNormalAttempt19(logical, size, fps);
    }

    private void startLogicalTele19() {
        if (logicalTeleStarting19 || logicalTeleRecording19) return;
        CameraManager manager = field19("cameraManager", CameraManager.class);
        Handler cameraHandler = field19("cameraHandler", Handler.class);
        String logicalId = field19("logicalCameraId", String.class);
        String teleId = field19("teleCameraId", String.class);
        CameraCharacteristics logical = field19("logicalChars", CameraCharacteristics.class);
        Size size = selectedSize19();
        int fps = selectedFps19();
        Range<Integer> range = bestRange19(logical, fps);
        if (manager == null || cameraHandler == null || logicalId == null || teleId == null || logical == null
                || range == null || preview19 == null || !preview19.isAvailable()) {
            toast19("Logical tele 60 route is not ready.");
            return;
        }
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            toast19("Camera permission is required.");
            return;
        }

        logicalTeleStarting19 = true;
        try {
            invokeAny19("prepareTransitionOverlay", new Class[]{});
            invokeAny19("prepareRecorder", new Class[]{});
            logicalOutput19 = field19("outputUri", Uri.class);
            invokeAny19("closeSessionOnly", new Class[]{});
            CameraDevice old = field19("cameraDevice", CameraDevice.class);
            if (old != null) try { old.close(); } catch (Exception ignored) {}
            setObject19("cameraDevice", null);

            manager.openCamera(logicalId, new CameraDevice.StateCallback() {
                @Override public void onOpened(CameraDevice camera) {
                    logicalTeleDevice19 = camera;
                    setObject19("cameraDevice", camera);
                    setObject19("currentCameraId", logicalId);
                    setObject19("currentChars", logical);
                    setBoolean19("activeTele", true);
                    buildLogicalTeleSession19(camera, logical, teleId, size, fps, range, cameraHandler);
                }
                @Override public void onDisconnected(CameraDevice camera) {
                    try { camera.close(); } catch (Exception ignored) {}
                    failLogical19("Logical camera disconnected.");
                }
                @Override public void onError(CameraDevice camera, int error) {
                    try { camera.close(); } catch (Exception ignored) {}
                    failLogical19("Logical camera error " + error + ".");
                }
            }, cameraHandler);
        } catch (Exception e) {
            failLogical19("Logical tele setup failed: " + e.getMessage());
        }
    }

    private void buildLogicalTeleSession19(CameraDevice camera, CameraCharacteristics logical, String teleId,
                                           Size size, int fps, Range<Integer> range, Handler cameraHandler) {
        try {
            MediaRecorder recorder = field19("recorder", MediaRecorder.class);
            if (recorder == null) throw new IllegalStateException("Recorder was not prepared");
            SurfaceTexture st = preview19.getSurfaceTexture();
            if (st == null) throw new IllegalStateException("Preview unavailable");
            Size pSize = choosePreview19(logical, fps);
            st.setDefaultBufferSize(pSize.getWidth(), pSize.getHeight());
            logicalPreviewSurface19 = new Surface(st);
            logicalRecordSurface19 = recorder.getSurface();

            OutputConfiguration previewOut = new OutputConfiguration(logicalPreviewSurface19);
            previewOut.setPhysicalCameraId(teleId);
            OutputConfiguration recordOut = new OutputConfiguration(logicalRecordSurface19);
            recordOut.setPhysicalCameraId(teleId);

            Set<String> physical = new HashSet<>();
            physical.add(teleId);
            CaptureRequest.Builder builder = camera.createCaptureRequest(CameraDevice.TEMPLATE_RECORD, physical);
            builder.addTarget(logicalPreviewSurface19);
            builder.addTarget(logicalRecordSurface19);
            builder.set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO);
            builder.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, range);
            int[] af = logical.get(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES);
            if (containsInt19(af, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO)) {
                builder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO);
            }
            configureStabilizationForFps19(builder, logical, fps);

            if (hasPhysicalKey19(logical, CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE)) {
                builder.setPhysicalCameraKey(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, range, teleId);
            }
            if (hasPhysicalKey19(logical, CaptureRequest.CONTROL_ZOOM_RATIO)) {
                float teleZoom = Math.max(1f, float19("requestedUiZoom", 3f) / 3f);
                CameraCharacteristics tele = field19("teleChars", CameraCharacteristics.class);
                Range<Float> z = tele == null ? null : tele.get(CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE);
                if (z != null) teleZoom = Math.max(z.getLower(), Math.min(teleZoom, z.getUpper()));
                builder.setPhysicalCameraKey(CaptureRequest.CONTROL_ZOOM_RATIO, teleZoom, teleId);
            }

            List<OutputConfiguration> outputs = new ArrayList<>();
            outputs.add(previewOut);
            outputs.add(recordOut);
            CameraCaptureSession.CaptureCallback fpsCallback = exactField19(
                    V16CorrectnessActivity.class, "sensorFpsCallback", CameraCaptureSession.CaptureCallback.class);
            SessionConfiguration config = new SessionConfiguration(SessionConfiguration.SESSION_REGULAR,
                    outputs, command -> cameraHandler.post(command), new CameraCaptureSession.StateCallback() {
                @Override public void onConfigured(CameraCaptureSession session) {
                    try {
                        setObject19("captureSession", session);
                        setObject19("repeatingBuilder", builder);
                        setLongExact19(V16CorrectnessActivity.class, "sensorStartNs", 0L);
                        setIntExact19(V16CorrectnessActivity.class, "sensorFrames", 0);
                        setFloatExact19(V16CorrectnessActivity.class, "measuredSensorFps", 0f);
                        session.setRepeatingRequest(builder.build(), fpsCallback, cameraHandler);
                        recorder.start();
                        logicalTeleStarting19 = false;
                        logicalTeleRecording19 = true;
                        setBoolean19("recordingStarting", false);
                        setBoolean19("recording", true);
                        setLong19("recordStartedAtMs", System.currentTimeMillis());
                        runOnUiThread(() -> Toast.makeText(V19Real60Activity.this,
                                "Tele 60 request via logical → physical tele • range " + range.getLower() + "–" + range.getUpper(),
                                Toast.LENGTH_SHORT).show());
                    } catch (Exception e) {
                        failLogical19("Logical tele recording start failed: " + e.getMessage());
                    }
                }
                @Override public void onConfigureFailed(CameraCaptureSession session) {
                    failLogical19("Vivo HAL rejected logical → tele " + fps + " fps session.");
                }
            });
            config.setSessionParameters(builder.build());
            camera.createCaptureSession(config);
        } catch (Exception e) {
            failLogical19("Logical tele session failed: " + e.getMessage());
        }
    }

    private void stopLogicalTele19(boolean restart) {
        Uri saved = logicalOutput19 != null ? logicalOutput19 : field19("outputUri", Uri.class);
        float sensor = exactFloat19(V16CorrectnessActivity.class, "measuredSensorFps", 0f);
        boolean keep = false;
        try {
            CameraCaptureSession session = field19("captureSession", CameraCaptureSession.class);
            if (session != null) {
                try { session.stopRepeating(); } catch (Exception ignored) {}
                try { session.abortCaptures(); } catch (Exception ignored) {}
            }
            MediaRecorder recorder = field19("recorder", MediaRecorder.class);
            if (logicalTeleRecording19 && recorder != null) {
                recorder.stop();
                keep = true;
            }
        } catch (Exception e) {
            toast19("Tele recording failed: " + e.getMessage());
        } finally {
            invokeAny19("finalizeOutput", new Class[]{boolean.class}, keep);
            setBoolean19("recording", false);
            setBoolean19("recordingStarting", false);
            logicalTeleStarting19 = false;
            logicalTeleRecording19 = false;
            invokeAny19("safeResetRecorder", new Class[]{});
            setObject19("captureSession", null);
            if (logicalTeleDevice19 != null) try { logicalTeleDevice19.close(); } catch (Exception ignored) {}
            logicalTeleDevice19 = null;
            setObject19("cameraDevice", null);
            if (logicalPreviewSurface19 != null) try { logicalPreviewSurface19.release(); } catch (Exception ignored) {}
            logicalPreviewSurface19 = null;
            logicalRecordSurface19 = null;
        }
        if (keep && saved != null) {
            invokeExact19(V16CorrectnessActivity.class, "verifyEncodedFps16",
                    new Class[]{Uri.class, float.class}, saved, sensor);
        }
        if (restart) {
            Handler h = field19("cameraHandler", Handler.class);
            if (h != null) h.postDelayed(() -> invokeAny19("openRoute", new Class[]{boolean.class}, isTeleUi19()), 180L);
        }
    }

    private void failLogical19(String text) {
        logicalTeleStarting19 = false;
        logicalTeleRecording19 = false;
        setBoolean19("recording", false);
        setBoolean19("recordingStarting", false);
        invokeAny19("finalizeOutput", new Class[]{boolean.class}, false);
        invokeAny19("safeResetRecorder", new Class[]{});
        if (logicalTeleDevice19 != null) try { logicalTeleDevice19.close(); } catch (Exception ignored) {}
        logicalTeleDevice19 = null;
        setObject19("cameraDevice", null);
        Handler h = field19("cameraHandler", Handler.class);
        if (h != null) h.postDelayed(() -> invokeAny19("openRoute", new Class[]{boolean.class}, isTeleUi19()), 180L);
        toast19(text);
    }

    private boolean hasPhysicalKey19(CameraCharacteristics logical, CaptureRequest.Key<?> key) {
        List<CaptureRequest.Key<?>> keys = logical == null ? null : logical.getAvailablePhysicalCameraRequestKeys();
        if (keys == null) return false;
        for (CaptureRequest.Key<?> k : keys) if (key.equals(k)) return true;
        return false;
    }

    private boolean isBusy19() {
        return logicalTeleStarting19 || logicalTeleRecording19 || bool19("recording") || bool19("recordingStarting")
                || exactBool19(V17HighSpeedActivity.class, "highSpeedRecording17");
    }

    private boolean isTeleUi19() {
        return bool19("activeTele") || float19("requestedUiZoom", 1f) >= 3f;
    }

    private int selectedFps19() { return int19("selectedFps", 30); }
    private Size selectedSize19() {
        Object o = field19("selectedSize", Object.class);
        return o instanceof Size ? (Size) o : FHD;
    }
    private CameraCharacteristics currentChars19() { return field19("currentChars", CameraCharacteristics.class); }

    private boolean containsSize19(Size[] sizes, Size wanted) {
        if (sizes == null) return false;
        for (Size s : sizes) if (wanted.equals(s)) return true;
        return false;
    }
    private boolean containsInt19(int[] values, int wanted) {
        if (values == null) return false;
        for (int v : values) if (v == wanted) return true;
        return false;
    }

    private void toast19(String text) {
        runOnUiThread(() -> Toast.makeText(this, text, Toast.LENGTH_LONG).show());
    }

    @SuppressWarnings("unchecked")
    private <T> T exactField19(Class<?> owner, String name, Class<T> type) {
        try {
            Field f = owner.getDeclaredField(name); f.setAccessible(true);
            Object v = f.get(this); return v == null ? null : (T) v;
        } catch (Exception e) { return null; }
    }
    private boolean exactBool19(Class<?> owner, String name) {
        try { Field f = owner.getDeclaredField(name); f.setAccessible(true); return f.getBoolean(this); }
        catch (Exception e) { return false; }
    }
    private int exactInt19(Class<?> owner, String name, int fallback) {
        try { Field f = owner.getDeclaredField(name); f.setAccessible(true); return f.getInt(this); }
        catch (Exception e) { return fallback; }
    }
    private float exactFloat19(Class<?> owner, String name, float fallback) {
        try { Field f = owner.getDeclaredField(name); f.setAccessible(true); return f.getFloat(this); }
        catch (Exception e) { return fallback; }
    }
    private void setExactInt19(Class<?> owner, String name, int value) {
        try { Field f = owner.getDeclaredField(name); f.setAccessible(true); f.setInt(this, value); }
        catch (Exception ignored) {}
    }
    private void setLongExact19(Class<?> owner, String name, long value) {
        try { Field f = owner.getDeclaredField(name); f.setAccessible(true); f.setLong(this, value); }
        catch (Exception ignored) {}
    }
    private void setIntExact19(Class<?> owner, String name, int value) {
        try { Field f = owner.getDeclaredField(name); f.setAccessible(true); f.setInt(this, value); }
        catch (Exception ignored) {}
    }
    private void setFloatExact19(Class<?> owner, String name, float value) {
        try { Field f = owner.getDeclaredField(name); f.setAccessible(true); f.setFloat(this, value); }
        catch (Exception ignored) {}
    }
    private Object invokeExact19(Class<?> owner, String name, Class<?>[] types, Object... args) {
        try { Method m = owner.getDeclaredMethod(name, types); m.setAccessible(true); return m.invoke(this, args); }
        catch (Exception e) { return null; }
    }

    @SuppressWarnings("unchecked")
    private <T> T field19(String name, Class<T> type) {
        Class<?> c = getClass();
        while (c != null) {
            try { Field f = c.getDeclaredField(name); f.setAccessible(true); Object v = f.get(this); return v == null ? null : (T) v; }
            catch (NoSuchFieldException e) { c = c.getSuperclass(); }
            catch (Exception e) { return null; }
        }
        return null;
    }
    private boolean bool19(String name) {
        Class<?> c = getClass();
        while (c != null) {
            try { Field f = c.getDeclaredField(name); f.setAccessible(true); return f.getBoolean(this); }
            catch (NoSuchFieldException e) { c = c.getSuperclass(); }
            catch (Exception e) { return false; }
        }
        return false;
    }
    private int int19(String name, int fallback) {
        Class<?> c = getClass();
        while (c != null) {
            try { Field f = c.getDeclaredField(name); f.setAccessible(true); return f.getInt(this); }
            catch (NoSuchFieldException e) { c = c.getSuperclass(); }
            catch (Exception e) { return fallback; }
        }
        return fallback;
    }
    private float float19(String name, float fallback) {
        Class<?> c = getClass();
        while (c != null) {
            try { Field f = c.getDeclaredField(name); f.setAccessible(true); return f.getFloat(this); }
            catch (NoSuchFieldException e) { c = c.getSuperclass(); }
            catch (Exception e) { return fallback; }
        }
        return fallback;
    }
    private void setBoolean19(String name, boolean value) { setPrimitive19(name, 1, value, 0, 0L, null); }
    private void setInt19(String name, int value) { setPrimitive19(name, 2, false, value, 0L, null); }
    private void setLong19(String name, long value) { setPrimitive19(name, 3, false, 0, value, null); }
    private void setObject19(String name, Object value) { setPrimitive19(name, 4, false, 0, 0L, value); }
    private void setPrimitive19(String name, int kind, boolean b, int i, long l, Object o) {
        Class<?> c = getClass();
        while (c != null) {
            try {
                Field f = c.getDeclaredField(name); f.setAccessible(true);
                if (kind == 1) f.setBoolean(this, b); else if (kind == 2) f.setInt(this, i);
                else if (kind == 3) f.setLong(this, l); else f.set(this, o); return;
            } catch (NoSuchFieldException e) { c = c.getSuperclass(); }
            catch (Exception e) { return; }
        }
    }
    private Object invokeAny19(String name, Class<?>[] types, Object... args) {
        Class<?> c = getClass();
        while (c != null) {
            try { Method m = c.getDeclaredMethod(name, types); m.setAccessible(true); return m.invoke(this, args); }
            catch (NoSuchMethodException e) { c = c.getSuperclass(); }
            catch (Exception e) { return null; }
        }
        return null;
    }
}
