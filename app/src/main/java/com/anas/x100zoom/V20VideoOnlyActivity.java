package com.anas.x100zoom;

import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.SurfaceTexture;
import android.graphics.drawable.GradientDrawable;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraMetadata;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.CaptureResult;
import android.hardware.camera2.TotalCaptureResult;
import android.hardware.camera2.params.MeteringRectangle;
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
import android.view.Gravity;
import android.view.Surface;
import android.view.TextureView;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * V20: video-only X100 camera.
 *
 * - Removes Photo from the user-facing UI; the app is dedicated to video.
 * - AUTO focus is center-weighted: persistent AF + AE regions are applied around
 *   the center reticle. Manual focus remains available from the existing focus tile.
 * - A small center reticle is always visible as the exact AF/AE aiming reference.
 * - 30 fps keeps the proven normal path.
 * - 60 fps prefers a MANUAL_SENSOR path which fixes SENSOR_FRAME_DURATION to
 *   1/60 s and derives exposure/ISO from the center-metered preview. This avoids
 *   auto-exposure silently lengthening the frame time in dark scenes.
 * - The final MP4 FPS is still measured after every recording.
 */
public class V20VideoOnlyActivity extends V19Real60Activity {
    private static final int ACCENT = 0xFFFFD129;
    private static final Size FHD = new Size(1920, 1080);
    private static final Size UHD = new Size(3840, 2160);
    private static final Size PREVIEW_60 = new Size(1280, 720);
    private static final long FRAME_60_NS = 16_666_667L;
    private static final long MAX_EXPOSURE_60_NS = 15_500_000L;

    private final Handler ui20 = new Handler(Looper.getMainLooper());

    private boolean installed20;
    private boolean locked60Starting20;
    private boolean locked60Recording20;

    private TextureView preview20;
    private Button shutter20;
    private FrameLayout root20;
    private LinearLayout modeRail20;
    private TextView videoModeButton20;
    private TextView settingsTitle20;
    private TextView videoFocus20;
    private CenterReticleView centerReticle20;
    private Map<Integer, TextView> fpsMap20;
    private TextView video1080_20;
    private TextView video4k_20;
    private TextView note20;

    private CaptureRequest.Builder lastMeteringBuilder20;
    private CameraCaptureSession lastMeteringSession20;
    private long lastMeteringApplyMs20;

    private long lastPreviewExposureNs20 = 8_333_333L;
    private int lastPreviewIso20 = 400;
    private long sensorStartNs20;
    private long sensorLastNs20;
    private int sensorFrames20;
    private volatile float measuredSensorFps20;

    private Surface lockedPreviewSurface20;

    private final CameraCaptureSession.CaptureCallback telemetry20 =
            new CameraCaptureSession.CaptureCallback() {
        @Override public void onCaptureCompleted(CameraCaptureSession session,
                                                  CaptureRequest request,
                                                  TotalCaptureResult result) {
            Long exposure = result.get(CaptureResult.SENSOR_EXPOSURE_TIME);
            Integer iso = result.get(CaptureResult.SENSOR_SENSITIVITY);
            if (exposure != null && exposure > 0L) lastPreviewExposureNs20 = exposure;
            if (iso != null && iso > 0) lastPreviewIso20 = iso;

            Long ts = result.get(CaptureResult.SENSOR_TIMESTAMP);
            if (ts == null || ts <= 0L || ts == sensorLastNs20) return;
            sensorLastNs20 = ts;
            if (sensorStartNs20 == 0L) {
                sensorStartNs20 = ts;
                sensorFrames20 = 1;
                return;
            }
            sensorFrames20++;
            long span = ts - sensorStartNs20;
            if (span >= 900_000_000L && sensorFrames20 > 2) {
                measuredSensorFps20 = (sensorFrames20 - 1) * 1_000_000_000f / span;
                sensorStartNs20 = ts;
                sensorFrames20 = 1;
            }
        }
    };

    private final Runnable watcher20 = new Runnable() {
        @Override public void run() {
            if (!installed20) {
                tryInstall20();
            } else {
                forceVideoOnly20();
                invokeExact20(V16CorrectnessActivity.class, "removeLegacyArtifacts", new Class[]{});
                invokeExact20(V16CorrectnessActivity.class, "syncSettings16", new Class[]{});
                invokeExact20(V19Real60Activity.class, "syncNormalControls19", new Class[]{});
                patchVideoControls20();
                applyCenterMetering20(false);
                updateReticle20();
            }
            ui20.postDelayed(this, 120L);
        }
    };

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        ui20.postDelayed(watcher20, 180L);
    }

    @Override protected void onPause() {
        if (locked60Starting20 || locked60Recording20) stopLocked60_20(false);
        super.onPause();
    }

    @Override protected void onDestroy() {
        if (locked60Starting20 || locked60Recording20) stopLocked60_20(false);
        ui20.removeCallbacksAndMessages(null);
        super.onDestroy();
    }

    @SuppressWarnings("unchecked")
    private void tryInstall20() {
        if (!exactBool20(V19Real60Activity.class, "installed19")) return;

        Handler parent = exactField20(V19Real60Activity.class, "ui19", Handler.class);
        if (parent != null) parent.removeCallbacksAndMessages(null);

        preview20 = exactField20(MainActivity.class, "textureView", TextureView.class);
        shutter20 = exactField20(MainActivity.class, "recordButton", Button.class);
        root20 = exactField20(X300UltraUiActivity.class, "cameraRoot", FrameLayout.class);
        modeRail20 = exactField20(X300UltraUiActivity.class, "modeRail", LinearLayout.class);
        videoModeButton20 = exactField20(X300UltraUiActivity.class, "videoModeButton", TextView.class);
        settingsTitle20 = exactField20(X300UltraUiActivity.class, "settingsTitle", TextView.class);
        videoFocus20 = exactField20(V15VideoUiActivity.class, "videoFocus", TextView.class);
        fpsMap20 = exactField20(V17HighSpeedActivity.class, "fpsButtons17", Map.class);
        video1080_20 = exactField20(V17HighSpeedActivity.class, "video1080_17", TextView.class);
        video4k_20 = exactField20(V17HighSpeedActivity.class, "video4k_17", TextView.class);
        note20 = exactField20(V17HighSpeedActivity.class, "capabilityNote17", TextView.class);

        if (preview20 == null || shutter20 == null || root20 == null || fpsMap20 == null) return;

        forceVideoOnly20();
        installCenterReticle20();
        replaceControls20();
        applyCenterMetering20(true);

        installed20 = true;
        patchVideoControls20();
    }

    private void forceVideoOnly20() {
        setBoolean20("photoMode", false);

        TextView photo = exactField20(X300UltraUiActivity.class, "photoModeButton", TextView.class);
        if (photo != null) photo.setVisibility(View.GONE);

        if (modeRail20 != null) {
            modeRail20.setGravity(Gravity.CENTER);
            for (int i = 0; i < modeRail20.getChildCount(); i++) {
                View child = modeRail20.getChildAt(i);
                if (child instanceof TextView) {
                    String text = ((TextView) child).getText().toString();
                    if ("Photo".equalsIgnoreCase(text) || "More".equalsIgnoreCase(text)) {
                        child.setVisibility(View.GONE);
                    }
                }
            }
        }

        if (videoModeButton20 != null) {
            videoModeButton20.setVisibility(View.VISIBLE);
            videoModeButton20.setText("Video");
            videoModeButton20.setTextColor(ACCENT);
            videoModeButton20.setAlpha(1f);
        }

        if (settingsTitle20 != null) {
            settingsTitle20.setText("Video settings  ›");
            settingsTitle20.setTextSize(14f);
        }
    }

    private void installCenterReticle20() {
        if (centerReticle20 != null) return;
        centerReticle20 = new CenterReticleView();
        centerReticle20.setClickable(false);
        centerReticle20.setFocusable(false);
        centerReticle20.setContentDescription("Center autofocus and exposure point");
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(dp20(34), dp20(34));
        lp.gravity = Gravity.CENTER;
        root20.addView(centerReticle20, lp);
    }

    private void replaceControls20() {
        for (Map.Entry<Integer, TextView> entry : fpsMap20.entrySet()) {
            final int fps = entry.getKey();
            entry.getValue().setOnClickListener(v -> selectFps20(fps));
        }
        if (video1080_20 != null) video1080_20.setOnClickListener(v -> selectResolution20(false));
        if (video4k_20 != null) video4k_20.setOnClickListener(v -> selectResolution20(true));

        shutter20.setOnClickListener(v -> {
            if (locked60Starting20 || locked60Recording20) {
                stopLocked60_20(true);
                return;
            }
            if (exactBool20(V17HighSpeedActivity.class, "highSpeedRecording17")) {
                invokeExact20(V17HighSpeedActivity.class, "stopHighSpeed17", new Class[]{boolean.class}, true);
                return;
            }
            if (bool20("recording") || bool20("recordingStarting")) {
                invokeExact20(V16CorrectnessActivity.class, "stopVerifiedRecording16", new Class[]{});
                return;
            }

            int hs = exactInt20(V17HighSpeedActivity.class, "highSpeedChoice17", 0);
            if (hs >= 120) {
                invokeExact20(V17HighSpeedActivity.class, "startHighSpeed17", new Class[]{});
                return;
            }

            int fps = int20("selectedFps", 30);
            if (fps == 60 && canLocked60_20(currentChars20(), selectedSize20())) {
                startLocked60_20();
            } else {
                invokeExact20(V19Real60Activity.class, "startNormalRecording19", new Class[]{});
            }
        });
    }

    private void selectFps20(int fps) {
        if (busy20()) return;
        if (fps == 60) {
            CameraCharacteristics chars = currentChars20();
            Size size = selectedSize20();
            if (canLocked60_20(chars, size)) {
                setInt20("selectedFps", 60);
                setExactInt20(V17HighSpeedActivity.class, "highSpeedChoice17", 0);
                invokeExact20(V17HighSpeedActivity.class, "saveChoice17", new Class[]{});
                Handler h = field20("cameraHandler", Handler.class);
                if (h != null) h.post(() -> invokeAny20("startPreviewSession", new Class[]{}));
                patchVideoControls20();
                Toast.makeText(this, "60 fps locked-sensor mode selected.", Toast.LENGTH_SHORT).show();
                return;
            }
        }
        invokeExact20(V19Real60Activity.class, "selectFps19", new Class[]{int.class}, fps);
    }

    private void selectResolution20(boolean fourK) {
        if (busy20()) return;
        Size wanted = fourK ? UHD : FHD;
        int hs = exactInt20(V17HighSpeedActivity.class, "highSpeedChoice17", 0);
        if (hs >= 120) {
            invokeExact20(V19Real60Activity.class, "selectResolution19", new Class[]{boolean.class}, fourK);
            return;
        }

        if (int20("selectedFps", 30) == 60 && canLocked60_20(currentChars20(), wanted)) {
            setObject20("selectedSize", wanted);
            invokeExact20(V17HighSpeedActivity.class, "saveChoice17", new Class[]{});
            Handler h = field20("cameraHandler", Handler.class);
            if (h != null) h.post(() -> invokeAny20("startPreviewSession", new Class[]{}));
            patchVideoControls20();
            return;
        }
        invokeExact20(V19Real60Activity.class, "selectResolution19", new Class[]{boolean.class}, fourK);
    }

    private void patchVideoControls20() {
        if (fpsMap20 == null) return;
        CameraCharacteristics chars = currentChars20();
        Size size = selectedSize20();
        int selected = exactInt20(V17HighSpeedActivity.class, "highSpeedChoice17", 0);
        if (selected < 120) selected = int20("selectedFps", 30);

        TextView sixty = fpsMap20.get(60);
        if (sixty != null) {
            boolean enabled = canLocked60_20(chars, size);
            styleChoice20(sixty, selected == 60, enabled);
        }

        if (videoFocus20 != null) {
            boolean manual = bool20("manualMode");
            videoFocus20.setText(manual ? "Focus\nManual" : "Center\nAF + AE");
        }

        if (note20 != null && selected == 60) {
            if (canManualSensor20(chars)) {
                note20.setText("60 fps uses fixed 16.67 ms sensor frames. Exposure is learned from the center AF/AE point, then clamped to the 60 fps frame time.");
            } else {
                note20.setText("This lens does not expose MANUAL_SENSOR. 60 fps cannot be hard-locked on this Camera2 path.");
            }
        }
    }

    private void styleChoice20(TextView view, boolean selected, boolean enabled) {
        view.setEnabled(enabled);
        view.setAlpha(enabled ? 1f : 0.28f);
        view.setTextColor(selected && enabled ? Color.BLACK : (enabled ? Color.WHITE : 0xFF777777));
        view.setBackground(selected && enabled ? rounded20(ACCENT, 8) : null);
    }

    private GradientDrawable rounded20(int color, int radiusDp) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(color);
        d.setCornerRadius(dp20(radiusDp));
        return d;
    }

    private void updateReticle20() {
        if (centerReticle20 == null) return;
        centerReticle20.setActive(!bool20("manualMode"));
        centerReticle20.invalidate();
    }

    /**
     * Keep AUTO video focus/exposure weighted at the center. The point is
     * intentionally invariant under zoom, so active-array center is correct.
     */
    private void applyCenterMetering20(boolean force) {
        if (locked60Starting20 || locked60Recording20) return;
        if (exactBool20(V17HighSpeedActivity.class, "highSpeedRecording17")) return;
        if (bool20("manualMode")) return;

        CaptureRequest.Builder b = field20("repeatingBuilder", CaptureRequest.Builder.class);
        CameraCaptureSession s = field20("captureSession", CameraCaptureSession.class);
        CameraCharacteristics chars = currentChars20();
        Handler camera = field20("cameraHandler", Handler.class);
        if (b == null || s == null || chars == null || camera == null) return;

        long now = android.os.SystemClock.elapsedRealtime();
        boolean changed = b != lastMeteringBuilder20 || s != lastMeteringSession20;
        if (!force && !changed && now - lastMeteringApplyMs20 < 850L) return;
        lastMeteringBuilder20 = b;
        lastMeteringSession20 = s;
        lastMeteringApplyMs20 = now;

        try {
            Rect active = chars.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE);
            if (active == null) return;

            Integer maxAf = chars.get(CameraCharacteristics.CONTROL_MAX_REGIONS_AF);
            Integer maxAe = chars.get(CameraCharacteristics.CONTROL_MAX_REGIONS_AE);

            int[] afModes = chars.get(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES);
            if (contains20(afModes, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO)) {
                b.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO);
            }

            if (maxAf != null && maxAf > 0) {
                b.set(CaptureRequest.CONTROL_AF_REGIONS,
                        new MeteringRectangle[]{centerRegion20(active, 0.14f, MeteringRectangle.METERING_WEIGHT_MAX)});
            }

            Integer aeMode = b.get(CaptureRequest.CONTROL_AE_MODE);
            if (aeMode == null || aeMode != CaptureRequest.CONTROL_AE_MODE_OFF) {
                b.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON);
                b.set(CaptureRequest.CONTROL_AE_LOCK, false);
                if (maxAe != null && maxAe > 0) {
                    b.set(CaptureRequest.CONTROL_AE_REGIONS,
                            new MeteringRectangle[]{centerRegion20(active, 0.24f, MeteringRectangle.METERING_WEIGHT_MAX)});
                }
            }

            s.setRepeatingRequest(b.build(), telemetry20, camera);
        } catch (Exception ignored) {}
    }

    private MeteringRectangle centerRegion20(Rect active, float fraction, int weight) {
        int w = Math.max(16, Math.round(active.width() * fraction));
        int h = Math.max(16, Math.round(active.height() * fraction));
        int left = clamp20(active.centerX() - w / 2, active.left, Math.max(active.left, active.right - w));
        int top = clamp20(active.centerY() - h / 2, active.top, Math.max(active.top, active.bottom - h));
        return new MeteringRectangle(new Rect(left, top, left + w, top + h), weight);
    }

    private boolean canLocked60_20(CameraCharacteristics chars, Size size) {
        if (chars == null || size == null) return false;
        if (!canManualSensor20(chars)) return false;
        StreamConfigurationMap map = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
        if (map == null || !containsSize20(map.getOutputSizes(MediaRecorder.class), size)) return false;
        return encoderSupports20(size, 60);
    }

    private boolean canManualSensor20(CameraCharacteristics chars) {
        int[] caps = chars == null ? null : chars.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES);
        return contains20(caps, CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_SENSOR);
    }

    private boolean encoderSupports20(Size size, int fps) {
        String[] types = {"video/hevc", "video/avc"};
        for (MediaCodecInfo info : new MediaCodecList(MediaCodecList.ALL_CODECS).getCodecInfos()) {
            if (!info.isEncoder()) continue;
            for (String wanted : types) {
                boolean match = false;
                for (String type : info.getSupportedTypes()) {
                    if (wanted.equalsIgnoreCase(type)) { match = true; break; }
                }
                if (!match) continue;
                try {
                    MediaCodecInfo.VideoCapabilities caps =
                            info.getCapabilitiesForType(wanted).getVideoCapabilities();
                    if (caps != null && caps.areSizeAndRateSupported(size.getWidth(), size.getHeight(), fps)) {
                        return true;
                    }
                } catch (Exception ignored) {}
            }
        }
        return false;
    }

    private void startLocked60_20() {
        CameraDevice camera = field20("cameraDevice", CameraDevice.class);
        CameraCharacteristics chars = currentChars20();
        Handler cameraHandler = field20("cameraHandler", Handler.class);
        Size size = selectedSize20();

        if (camera == null || chars == null || cameraHandler == null || preview20 == null ||
                !preview20.isAvailable() || !canLocked60_20(chars, size)) {
            Toast.makeText(this, "Locked 60 fps is unavailable on this active lens/resolution.", Toast.LENGTH_LONG).show();
            return;
        }

        try {
            ExposurePlan20 plan = buildExposurePlan20(chars);
            invokeAny20("prepareRecorder", new Class[]{});
            MediaRecorder recorder = field20("recorder", MediaRecorder.class);
            if (recorder == null) throw new IllegalStateException("Recorder preparation failed");

            locked60Starting20 = true;
            setBoolean20("recordingStarting", true);
            invokeAny20("closeSessionOnly", new Class[]{});

            SurfaceTexture st = preview20.getSurfaceTexture();
            if (st == null) throw new IllegalStateException("Preview unavailable");
            Size p = choosePreview20(chars);
            st.setDefaultBufferSize(p.getWidth(), p.getHeight());
            lockedPreviewSurface20 = new Surface(st);
            Surface recordSurface = recorder.getSurface();

            CaptureRequest.Builder b = camera.createCaptureRequest(CameraDevice.TEMPLATE_RECORD);
            b.addTarget(lockedPreviewSurface20);
            b.addTarget(recordSurface);

            b.set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO);
            b.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_OFF);
            b.set(CaptureRequest.SENSOR_FRAME_DURATION, FRAME_60_NS);
            b.set(CaptureRequest.SENSOR_EXPOSURE_TIME, plan.exposureNs);
            b.set(CaptureRequest.SENSOR_SENSITIVITY, plan.iso);
            b.set(CaptureRequest.CONTROL_AWB_MODE, CaptureRequest.CONTROL_AWB_MODE_AUTO);

            Range<Integer> range60 = bestRange20(chars, 60);
            if (range60 != null) b.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, range60);

            int[] afModes = chars.get(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES);
            if (contains20(afModes, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO)) {
                b.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO);
            }

            Rect active = chars.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE);
            Integer maxAf = chars.get(CameraCharacteristics.CONTROL_MAX_REGIONS_AF);
            if (active != null && maxAf != null && maxAf > 0) {
                b.set(CaptureRequest.CONTROL_AF_REGIONS,
                        new MeteringRectangle[]{centerRegion20(active, 0.14f, MeteringRectangle.METERING_WEIGHT_MAX)});
            }

            invokeAny20("setZoomOnBuilder", new Class[]{CaptureRequest.Builder.class}, b);
            configure60Stabilization20(b, chars);

            boolean flash = bool20("flashEnabled");
            if (flash && Boolean.TRUE.equals(chars.get(CameraCharacteristics.FLASH_INFO_AVAILABLE))) {
                try { b.set(CaptureRequest.FLASH_MODE, CaptureRequest.FLASH_MODE_TORCH); } catch (Exception ignored) {}
            }

            List<OutputConfiguration> outputs = new ArrayList<>();
            outputs.add(new OutputConfiguration(lockedPreviewSurface20));
            outputs.add(new OutputConfiguration(recordSurface));

            sensorStartNs20 = 0L;
            sensorLastNs20 = 0L;
            sensorFrames20 = 0;
            measuredSensorFps20 = 0f;

            SessionConfiguration config = new SessionConfiguration(
                    SessionConfiguration.SESSION_REGULAR,
                    outputs,
                    command -> cameraHandler.post(command),
                    new CameraCaptureSession.StateCallback() {
                        @Override public void onConfigured(CameraCaptureSession session) {
                            try {
                                setObject20("captureSession", session);
                                setObject20("repeatingBuilder", b);
                                session.setRepeatingRequest(b.build(), telemetry20, cameraHandler);
                                recorder.start();
                                locked60Starting20 = false;
                                locked60Recording20 = true;
                                setBoolean20("recordingStarting", false);
                                setBoolean20("recording", true);
                                setLong20("recordStartedAtMs", System.currentTimeMillis());

                                runOnUiThread(() -> Toast.makeText(
                                        V20VideoOnlyActivity.this,
                                        String.format(Locale.US,
                                                "Locked 60 fps • 1/%d s • ISO %d • center AF/AE",
                                                Math.max(1, Math.round(1_000_000_000f / plan.exposureNs)),
                                                plan.iso),
                                        Toast.LENGTH_SHORT).show());
                            } catch (Exception e) {
                                failLocked60_20("60 fps start failed: " + e.getMessage());
                            }
                        }

                        @Override public void onConfigureFailed(CameraCaptureSession session) {
                            failLocked60_20("Vivo HAL rejected the locked 60 fps session.");
                        }
                    });

            config.setSessionParameters(b.build());
            camera.createCaptureSession(config);
        } catch (Exception e) {
            failLocked60_20("Locked 60 fps setup failed: " + e.getMessage());
        }
    }

    private ExposurePlan20 buildExposurePlan20(CameraCharacteristics chars) {
        Range<Long> expRange = chars.get(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE);
        Range<Integer> isoRange = chars.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE);

        long sourceExp = Math.max(1L, lastPreviewExposureNs20);
        int sourceIso = Math.max(50, lastPreviewIso20);
        long targetExp = Math.min(sourceExp, MAX_EXPOSURE_60_NS);

        if (expRange != null) {
            targetExp = Math.max(expRange.getLower(), Math.min(targetExp, expRange.getUpper()));
            targetExp = Math.min(targetExp, MAX_EXPOSURE_60_NS);
        }

        double compensation = sourceExp / (double) Math.max(1L, targetExp);
        int targetIso = (int) Math.round(sourceIso * compensation);
        if (isoRange != null) {
            targetIso = Math.max(isoRange.getLower(), Math.min(targetIso, isoRange.getUpper()));
        } else {
            targetIso = Math.max(50, Math.min(targetIso, 12800));
        }

        return new ExposurePlan20(targetExp, targetIso);
    }

    private void configure60Stabilization20(CaptureRequest.Builder b, CameraCharacteristics chars) {
        try {
            b.set(CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE,
                    CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_OFF);
        } catch (Exception ignored) {}

        int[] ois = chars.get(CameraCharacteristics.LENS_INFO_AVAILABLE_OPTICAL_STABILIZATION);
        if (contains20(ois, CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE_ON)) {
            try {
                b.set(CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE,
                        CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE_ON);
            } catch (Exception ignored) {}
        }
    }

    private Size choosePreview20(CameraCharacteristics chars) {
        StreamConfigurationMap map = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
        Size[] sizes = map == null ? null : map.getOutputSizes(SurfaceTexture.class);
        if (containsSize20(sizes, PREVIEW_60)) return PREVIEW_60;
        if (containsSize20(sizes, FHD)) return FHD;
        if (sizes != null && sizes.length > 0) {
            Size best = null;
            for (Size s : sizes) {
                float ratio = s.getWidth() / (float) s.getHeight();
                if (Math.abs(ratio - 16f / 9f) > 0.04f || s.getWidth() > 1920) continue;
                if (best == null || Math.abs(s.getWidth() - 1280) < Math.abs(best.getWidth() - 1280)) best = s;
            }
            if (best != null) return best;
            return sizes[0];
        }
        return PREVIEW_60;
    }

    private Range<Integer> bestRange20(CameraCharacteristics chars, int fps) {
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

    private void stopLocked60_20(boolean restartPreview) {
        Uri saved = field20("outputUri", Uri.class);
        float sensor = measuredSensorFps20;

        locked60Starting20 = false;
        locked60Recording20 = false;

        if (bool20("recording") || bool20("recordingStarting")) {
            invokeAny20("stopRecording", new Class[]{});
        } else {
            invokeAny20("safeResetRecorder", new Class[]{});
            if (restartPreview) {
                Handler h = field20("cameraHandler", Handler.class);
                if (h != null) h.post(() -> invokeAny20("startPreviewSession", new Class[]{}));
            }
        }

        if (lockedPreviewSurface20 != null) {
            try { lockedPreviewSurface20.release(); } catch (Exception ignored) {}
            lockedPreviewSurface20 = null;
        }

        if (saved != null) {
            invokeExact20(V16CorrectnessActivity.class, "verifyEncodedFps16",
                    new Class[]{Uri.class, float.class}, saved, sensor);
        }
    }

    private void failLocked60_20(String message) {
        locked60Starting20 = false;
        locked60Recording20 = false;
        setBoolean20("recordingStarting", false);
        setBoolean20("recording", false);
        invokeAny20("safeResetRecorder", new Class[]{});
        if (lockedPreviewSurface20 != null) {
            try { lockedPreviewSurface20.release(); } catch (Exception ignored) {}
            lockedPreviewSurface20 = null;
        }
        Handler h = field20("cameraHandler", Handler.class);
        if (h != null) h.post(() -> invokeAny20("startPreviewSession", new Class[]{}));
        runOnUiThread(() -> Toast.makeText(this, message, Toast.LENGTH_LONG).show());
    }

    private boolean busy20() {
        return locked60Starting20 || locked60Recording20 ||
                bool20("recording") || bool20("recordingStarting") ||
                exactBool20(V17HighSpeedActivity.class, "highSpeedRecording17");
    }

    private CameraCharacteristics currentChars20() {
        return field20("currentChars", CameraCharacteristics.class);
    }

    private Size selectedSize20() {
        Object o = field20("selectedSize", Object.class);
        return o instanceof Size ? (Size) o : FHD;
    }

    private int dp20(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private int clamp20(int value, int min, int max) {
        return Math.max(min, Math.min(value, max));
    }

    private boolean contains20(int[] values, int wanted) {
        if (values == null) return false;
        for (int v : values) if (v == wanted) return true;
        return false;
    }

    private boolean containsSize20(Size[] values, Size wanted) {
        if (values == null || wanted == null) return false;
        for (Size v : values) if (wanted.equals(v)) return true;
        return false;
    }

    private static final class ExposurePlan20 {
        final long exposureNs;
        final int iso;
        ExposurePlan20(long exposureNs, int iso) {
            this.exposureNs = exposureNs;
            this.iso = iso;
        }
    }

    private final class CenterReticleView extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private boolean active = true;

        CenterReticleView() {
            super(V20VideoOnlyActivity.this);
            setLayerType(View.LAYER_TYPE_SOFTWARE, null);
        }

        void setActive(boolean value) { active = value; }

        @Override protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            float cx = getWidth() / 2f;
            float cy = getHeight() / 2f;
            int ring = active ? 0xD9FFFFFF : 0x88FFFFFF;
            int dot = active ? ACCENT : 0xFF999999;

            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(dp20(1));
            paint.setColor(ring);
            canvas.drawCircle(cx, cy, dp20(8), paint);

            paint.setStyle(Paint.Style.FILL);
            paint.setColor(dot);
            canvas.drawCircle(cx, cy, dp20(2.2f), paint);
        }
    }

    private int dp20(float value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    @SuppressWarnings("unchecked")
    private <T> T exactField20(Class<?> owner, String name, Class<T> type) {
        try {
            Field f = owner.getDeclaredField(name);
            f.setAccessible(true);
            Object v = f.get(this);
            return v == null ? null : (T) v;
        } catch (Exception e) {
            return null;
        }
    }

    private boolean exactBool20(Class<?> owner, String name) {
        try {
            Field f = owner.getDeclaredField(name);
            f.setAccessible(true);
            return f.getBoolean(this);
        } catch (Exception e) {
            return false;
        }
    }

    private int exactInt20(Class<?> owner, String name, int fallback) {
        try {
            Field f = owner.getDeclaredField(name);
            f.setAccessible(true);
            return f.getInt(this);
        } catch (Exception e) {
            return fallback;
        }
    }

    private void setExactInt20(Class<?> owner, String name, int value) {
        try {
            Field f = owner.getDeclaredField(name);
            f.setAccessible(true);
            f.setInt(this, value);
        } catch (Exception ignored) {}
    }

    private Object invokeExact20(Class<?> owner, String name, Class<?>[] types, Object... args) {
        try {
            Method m = owner.getDeclaredMethod(name, types);
            m.setAccessible(true);
            return m.invoke(this, args);
        } catch (Exception e) {
            return null;
        }
    }

    @SuppressWarnings("unchecked")
    private <T> T field20(String name, Class<T> type) {
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

    private boolean bool20(String name) {
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

    private int int20(String name, int fallback) {
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

    private void setBoolean20(String name, boolean value) {
        setPrimitive20(name, 1, value, 0, 0L, null);
    }

    private void setInt20(String name, int value) {
        setPrimitive20(name, 2, false, value, 0L, null);
    }

    private void setLong20(String name, long value) {
        setPrimitive20(name, 3, false, 0, value, null);
    }

    private void setObject20(String name, Object value) {
        setPrimitive20(name, 4, false, 0, 0L, value);
    }

    private void setPrimitive20(String name, int kind, boolean b, int i, long l, Object o) {
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

    private Object invokeAny20(String name, Class<?>[] types, Object... args) {
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
