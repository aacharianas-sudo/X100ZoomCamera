package com.anas.x100zoom;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.SurfaceTexture;
import android.graphics.drawable.GradientDrawable;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraConstrainedHighSpeedCaptureSession;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraMetadata;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.CaptureResult;
import android.hardware.camera2.TotalCaptureResult;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.media.MediaCodecInfo;
import android.media.MediaCodecList;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.media.MediaRecorder;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.util.Range;
import android.util.Size;
import android.view.Gravity;
import android.view.Surface;
import android.view.TextureView;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.io.FileDescriptor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * V17: capability-driven video FPS controls.
 *
 * 24/25/30/50/60 use V16's verified constant-FPS regular Camera2 session.
 * 120/240 use CameraConstrainedHighSpeedCaptureSession and the exact
 * high-speed size/FPS combinations advertised by the active camera path.
 *
 * No frame-rate button is enabled only because vivo's stock UI has it: the
 * active public Camera2 path must advertise the combination on this device.
 */
public class V17HighSpeedActivity extends V16CorrectnessActivity {
    private static final int ACCENT = 0xFFFFD129;
    private static final int TILE = 0xFF292929;
    private static final Size FHD = new Size(1920, 1080);
    private static final Size UHD = new Size(3840, 2160);
    private static final int[] FPS_OPTIONS = {24, 25, 30, 50, 60, 120, 240};
    private static final String PREFS = "x100_camera_prefs";
    private static final String K_PRESERVE = "preserve_settings";
    private static final String K_FPS_CHOICE = "v17_video_fps_choice";

    private final Handler ui17 = new Handler(Looper.getMainLooper());
    private final Map<Integer, TextView> fpsButtons17 = new LinkedHashMap<>();

    private SharedPreferences prefs17;
    private boolean installed17;
    private boolean highSpeedRecording17;
    private int highSpeedChoice17;
    private int lastCapabilitySignature17;
    private long lastPrefsSave17;
    private boolean warnedLensFallback17;

    private LinearLayout videoPanel17;
    private LinearLayout fpsRow17;
    private TextView video1080_17;
    private TextView video4k_17;
    private TextView capabilityNote17;
    private TextView fpsStatus17;
    private Button shutter17;
    private TextureView preview17;
    private View zoomStrip17;

    private CameraConstrainedHighSpeedCaptureSession hsSession17;
    private MediaRecorder hsRecorder17;
    private Surface hsPreviewSurface17;
    private Uri hsOutputUri17;
    private ParcelFileDescriptor hsOutputPfd17;
    private long hsSensorStartNs17;
    private long hsLastSensorNs17;
    private int hsSensorFrames17;
    private volatile float hsMeasuredSensorFps17;

    private final CameraCaptureSession.CaptureCallback hsFpsCallback17 =
            new CameraCaptureSession.CaptureCallback() {
        @Override public void onCaptureCompleted(CameraCaptureSession session,
                                                  CaptureRequest request,
                                                  TotalCaptureResult result) {
            Long ts = result.get(CaptureResult.SENSOR_TIMESTAMP);
            if (ts == null || ts <= 0L || ts == hsLastSensorNs17) return;
            hsLastSensorNs17 = ts;
            if (hsSensorStartNs17 == 0L) {
                hsSensorStartNs17 = ts;
                hsSensorFrames17 = 1;
                return;
            }
            hsSensorFrames17++;
            long span = ts - hsSensorStartNs17;
            if (span >= 900_000_000L && hsSensorFrames17 > 2) {
                hsMeasuredSensorFps17 = (hsSensorFrames17 - 1) * 1_000_000_000f / span;
                hsSensorStartNs17 = ts;
                hsSensorFrames17 = 1;
            }
        }
    };

    private final Runnable watcher17 = new Runnable() {
        @Override public void run() {
            if (!installed17) {
                tryInstall17();
            } else {
                // V17 owns V16's periodic loop so the 30/60-only validator cannot
                // overwrite a valid 120/240 high-speed selection.
                invoke17("removeLegacyArtifacts", new Class[]{});
                invoke17("syncSettings16", new Class[]{});
                invoke17("syncPhotoViewport16", new Class[]{});

                if (!highSpeedRecording17 && highSpeedChoice17 == 0) {
                    invoke17("validateCurrentVideoMode16", new Class[]{});
                    if (!bool17("photoMode")) invoke17("applyVideoStabilization", new Class[]{});
                }

                validateChoiceForCurrentPath17();
                syncControls17();

                long now = android.os.SystemClock.elapsedRealtime();
                if (now - lastPrefsSave17 > 550L) {
                    lastPrefsSave17 = now;
                    invoke17("savePreferences16", new Class[]{});
                    saveChoice17();
                }
            }
            ui17.postDelayed(this, 65L);
        }
    };

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        prefs17 = getSharedPreferences(PREFS, MODE_PRIVATE);
        if (prefs17.getBoolean(K_PRESERVE, true)) {
            int saved = prefs17.getInt(K_FPS_CHOICE, -1);
            if (saved >= 120) highSpeedChoice17 = saved;
            else if (saved > 0) setInt17("selectedFps", saved);
        }
        ui17.postDelayed(watcher17, 80L);
    }

    @Override protected void onPause() {
        if (highSpeedRecording17) stopHighSpeed17(false);
        super.onPause();
    }

    @Override protected void onDestroy() {
        if (highSpeedRecording17) stopHighSpeed17(false);
        releaseHighSpeedResources17();
        saveChoice17();
        ui17.removeCallbacksAndMessages(null);
        super.onDestroy();
    }

    private int dp17(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    private GradientDrawable rounded17(int color, int radius) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(color);
        d.setCornerRadius(dp17(radius));
        return d;
    }

    private void tryInstall17() {
        if (!Boolean.TRUE.equals(exactBoolean17(V16CorrectnessActivity.class, "installed16"))) return;

        videoPanel17 = exactField17(V15VideoUiActivity.class, "videoPanel", LinearLayout.class);
        video1080_17 = exactField17(V15VideoUiActivity.class, "video1080", TextView.class);
        video4k_17 = exactField17(V15VideoUiActivity.class, "video4k", TextView.class);
        TextView old30 = exactField17(V15VideoUiActivity.class, "fps30", TextView.class);
        TextView old60 = exactField17(V15VideoUiActivity.class, "fps60", TextView.class);
        shutter17 = exactField17(MainActivity.class, "recordButton", Button.class);
        preview17 = exactField17(MainActivity.class, "textureView", TextureView.class);
        zoomStrip17 = field17("zoomStrip", View.class);
        fpsStatus17 = exactField17(V16CorrectnessActivity.class, "fpsStatus16", TextView.class);

        if (videoPanel17 == null || video1080_17 == null || video4k_17 == null ||
                old30 == null || shutter17 == null || preview17 == null) return;

        Handler v16Handler = exactField17(V16CorrectnessActivity.class, "ui16", Handler.class);
        if (v16Handler != null) v16Handler.removeCallbacksAndMessages(null);

        if (old30.getParent() instanceof LinearLayout) {
            fpsRow17 = (LinearLayout) old30.getParent();
            fpsRow17.removeAllViews();
            fpsRow17.setPadding(dp17(1), dp17(1), dp17(1), dp17(1));
            for (int fps : FPS_OPTIONS) {
                TextView button = makeFpsButton17(fps);
                fpsButtons17.put(fps, button);
                fpsRow17.addView(button, new LinearLayout.LayoutParams(0, dp17(52), 1f));
            }
        }
        if (old60 != null && old60.getParent() instanceof ViewGroup) {
            try { ((ViewGroup) old60.getParent()).removeView(old60); } catch (Exception ignored) {}
        }

        video1080_17.setOnClickListener(v -> selectResolution17(false));
        video4k_17.setOnClickListener(v -> selectResolution17(true));

        capabilityNote17 = findTextStarting17(videoPanel17, "60 fps is selectable");
        if (capabilityNote17 != null) {
            capabilityNote17.setText("24–60 use verified constant Camera2 timing. 120/240 use the X100 high-speed Camera2 session when exposed by the active lens/resolution.");
            capabilityNote17.setTextSize(9.5f);
        }

        replaceShutter17();
        installed17 = true;
        syncControls17();
    }

    private TextView makeFpsButton17(int fps) {
        TextView t = new TextView(this);
        t.setText(String.valueOf(fps));
        t.setTextSize(fps >= 100 ? 10.5f : 11.5f);
        t.setTypeface(null, android.graphics.Typeface.BOLD);
        t.setGravity(Gravity.CENTER);
        t.setTextColor(Color.WHITE);
        t.setClickable(true);
        t.setFocusable(true);
        t.setOnClickListener(v -> selectFps17(fps));
        return t;
    }

    private void replaceShutter17() {
        shutter17.setOnClickListener(v -> {
            if (bool17("photoMode")) {
                invoke17("capturePhoto", new Class[]{});
                return;
            }
            if (highSpeedRecording17) {
                stopHighSpeed17(true);
                return;
            }
            if (bool17("recording") || bool17("recordingStarting")) {
                invoke17("stopVerifiedRecording16", new Class[]{});
                return;
            }
            if (highSpeedChoice17 >= 120) startHighSpeed17();
            else invoke17("startVerifiedRecording16", new Class[]{});
        });
    }

    private void selectFps17(int fps) {
        if (highSpeedRecording17 || bool17("recording") || bool17("recordingStarting")) return;
        CameraCharacteristics chars = field17("currentChars", CameraCharacteristics.class);
        Size size = selectedSize17();
        if (chars == null) return;

        if (fps >= 120) {
            if (!supportsHighSpeed17(chars, size, fps)) {
                toast17(highSpeedFailure17(chars, size, fps));
                return;
            }
            highSpeedChoice17 = fps;
            // Keep MainActivity on a harmless normal preview FPS. The constrained
            // high-speed range is installed only when recording begins.
            setInt17("selectedFps", 30);
            warnedLensFallback17 = false;
        } else {
            if (!supportsNormal17(chars, size, fps)) {
                toast17(normalFailure17(chars, size, fps));
                return;
            }
            highSpeedChoice17 = 0;
            setInt17("selectedFps", fps);
            Handler camera = field17("cameraHandler", Handler.class);
            if (camera != null) camera.post(() -> invoke17("startPreviewSession", new Class[]{}));
            warnedLensFallback17 = false;
        }
        saveChoice17();
        syncControls17();
    }

    private void selectResolution17(boolean fourK) {
        if (highSpeedRecording17 || bool17("recording") || bool17("recordingStarting")) return;
        CameraCharacteristics chars = field17("currentChars", CameraCharacteristics.class);
        if (chars == null) return;
        Size wanted = fourK ? UHD : FHD;

        if (highSpeedChoice17 >= 120) {
            if (!supportsHighSpeed17(chars, wanted, highSpeedChoice17)) {
                toast17((fourK ? "4K" : "1080P") + " is not exposed at " + highSpeedChoice17 + " fps on this active camera path.");
                return;
            }
        } else {
            int fps = int17("selectedFps", 30);
            if (!supportsNormal17(chars, wanted, fps)) {
                toast17(normalFailure17(chars, wanted, fps));
                return;
            }
        }

        setObject17("selectedSize", wanted);
        Handler camera = field17("cameraHandler", Handler.class);
        if (camera != null) camera.post(() -> invoke17("startPreviewSession", new Class[]{}));
        saveChoice17();
        syncControls17();
    }

    private void validateChoiceForCurrentPath17() {
        if (bool17("photoMode") || highSpeedRecording17 || bool17("recordingStarting")) return;
        CameraCharacteristics chars = field17("currentChars", CameraCharacteristics.class);
        if (chars == null) return;
        Size size = selectedSize17();

        if (highSpeedChoice17 >= 120) {
            if (supportsHighSpeed17(chars, size, highSpeedChoice17)) {
                warnedLensFallback17 = false;
                return;
            }
            highSpeedChoice17 = 0;
            if (supportsNormal17(chars, size, 30)) setInt17("selectedFps", 30);
            if (!warnedLensFallback17) {
                warnedLensFallback17 = true;
                toast17("High-speed FPS is not exposed on this lens/resolution. Switched to verified 30 fps.");
            }
            saveChoice17();
            return;
        }

        int fps = int17("selectedFps", 30);
        if (supportsNormal17(chars, size, fps)) {
            warnedLensFallback17 = false;
            return;
        }
        if (supportsNormal17(chars, size, 30)) {
            setInt17("selectedFps", 30);
            if (!warnedLensFallback17) {
                warnedLensFallback17 = true;
                toast17(fps + " fps is not exposed on this lens/resolution. Switched to verified 30 fps.");
            }
            saveChoice17();
        }
    }

    private void syncControls17() {
        if (!installed17 || bool17("photoMode")) return;
        CameraCharacteristics chars = field17("currentChars", CameraCharacteristics.class);
        Size size = selectedSize17();
        int normalFps = int17("selectedFps", 30);
        int selected = highSpeedChoice17 >= 120 ? highSpeedChoice17 : normalFps;

        for (Map.Entry<Integer, TextView> entry : fpsButtons17.entrySet()) {
            int fps = entry.getKey();
            boolean supported = chars != null && (fps >= 120
                    ? supportsHighSpeed17(chars, size, fps)
                    : supportsNormal17(chars, size, fps));
            styleChoice17(entry.getValue(), selected == fps, supported);
        }

        boolean is4k = size.getWidth() >= 3800;
        boolean support1080 = chars != null && (highSpeedChoice17 >= 120
                ? supportsHighSpeed17(chars, FHD, highSpeedChoice17)
                : supportsNormal17(chars, FHD, normalFps));
        boolean support4k = chars != null && (highSpeedChoice17 >= 120
                ? supportsHighSpeed17(chars, UHD, highSpeedChoice17)
                : supportsNormal17(chars, UHD, normalFps));
        styleChoice17(video1080_17, !is4k, support1080);
        styleChoice17(video4k_17, is4k, support4k);

        if (capabilityNote17 != null) {
            if (highSpeedChoice17 >= 120) {
                capabilityNote17.setText(highSpeedChoice17 + " fps high-speed • real constrained Camera2 session • zoom/lens controls lock while recording • audio off in V17 high-speed mode");
            } else {
                capabilityNote17.setText("24–60 use verified constant Camera2 timing. 120/240 appear only when the active camera path advertises a constrained high-speed combination.");
            }
        }
    }

    private void styleChoice17(TextView view, boolean selected, boolean enabled) {
        if (view == null) return;
        view.setEnabled(enabled);
        view.setAlpha(enabled ? 1f : 0.28f);
        view.setTextColor(selected && enabled ? Color.BLACK : (enabled ? Color.WHITE : 0xFF777777));
        view.setBackground(selected && enabled ? rounded17(ACCENT, 8) : null);
    }

    private boolean supportsNormal17(CameraCharacteristics chars, Size size, int fps) {
        Object result = invokeExact17(V16CorrectnessActivity.class,
                "supportsConstantMode16",
                new Class[]{CameraCharacteristics.class, Size.class, int.class},
                chars, size, fps);
        return result instanceof Boolean && (Boolean) result;
    }

    private String normalFailure17(CameraCharacteristics chars, Size size, int fps) {
        Object result = invokeExact17(V16CorrectnessActivity.class,
                "modeFailure16",
                new Class[]{CameraCharacteristics.class, Size.class, int.class},
                chars, size, fps);
        return result instanceof String ? (String) result : "That constant FPS mode is not exposed by this camera path.";
    }

    private boolean supportsHighSpeed17(CameraCharacteristics chars, Size size, int fps) {
        return findHighSpeedRange17(chars, size, fps) != null;
    }

    private Range<Integer> findHighSpeedRange17(CameraCharacteristics chars, Size size, int fps) {
        if (chars == null || size == null || fps < 120) return null;
        int[] caps = chars.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES);
        if (!containsInt17(caps, CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_CONSTRAINED_HIGH_SPEED_VIDEO)) return null;
        StreamConfigurationMap map = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
        if (map == null || !containsSize17(map.getHighSpeedVideoSizes(), size)) return null;
        Range<Integer>[] ranges;
        try { ranges = map.getHighSpeedVideoFpsRangesFor(size); }
        catch (Exception e) { return null; }
        if (ranges == null) return null;

        Range<Integer> fallback = null;
        for (Range<Integer> range : ranges) {
            if (range.getUpper() != fps) continue;
            if (range.getLower() == fps) return range;
            if (range.getLower() <= fps && (fallback == null || range.getLower() > fallback.getLower())) {
                fallback = range;
            }
        }
        return fallback;
    }

    private String highSpeedFailure17(CameraCharacteristics chars, Size size, int fps) {
        if (chars == null) return "Camera is not ready.";
        int[] caps = chars.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES);
        if (!containsInt17(caps, CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_CONSTRAINED_HIGH_SPEED_VIDEO)) {
            return "This active camera path does not expose Android constrained high-speed video.";
        }
        StreamConfigurationMap map = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
        if (map == null || !containsSize17(map.getHighSpeedVideoSizes(), size)) {
            return size.getWidth() + "×" + size.getHeight() + " is not an exposed high-speed size on this lens.";
        }
        return fps + " fps is not advertised for this high-speed size/lens.";
    }

    private void startHighSpeed17() {
        if (highSpeedChoice17 < 120 || highSpeedRecording17 || bool17("recording") || bool17("recordingStarting")) return;
        CameraDevice camera = field17("cameraDevice", CameraDevice.class);
        CameraCharacteristics chars = field17("currentChars", CameraCharacteristics.class);
        Handler cameraHandler = field17("cameraHandler", Handler.class);
        Size size = selectedSize17();
        int fps = highSpeedChoice17;
        Range<Integer> range = findHighSpeedRange17(chars, size, fps);

        if (camera == null || chars == null || cameraHandler == null || !preview17.isAvailable()) {
            toast17("Camera is not ready yet.");
            return;
        }
        if (range == null) {
            toast17(highSpeedFailure17(chars, size, fps));
            return;
        }

        EncoderConfig17 encoder = findEncoder17(size, fps);
        if (encoder == null) {
            toast17("Camera2 exposes " + fps + " fps, but no public H.264/HEVC encoder advertises this size/rate.");
            return;
        }

        try {
            prepareHighSpeedRecorder17(size, fps, encoder);
            invoke17("closeSessionOnly", new Class[]{});

            SurfaceTexture st = preview17.getSurfaceTexture();
            if (st == null) throw new IllegalStateException("Preview surface unavailable");
            st.setDefaultBufferSize(size.getWidth(), size.getHeight());
            hsPreviewSurface17 = new Surface(st);
            Surface recordSurface = hsRecorder17.getSurface();

            CaptureRequest.Builder request = camera.createCaptureRequest(CameraDevice.TEMPLATE_RECORD);
            request.addTarget(hsPreviewSurface17);
            request.addTarget(recordSurface);
            request.set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO);
            request.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, range);

            int[] af = chars.get(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES);
            if (containsInt17(af, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO)) {
                request.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO);
            }
            invoke17("setZoomOnBuilder", new Class[]{CaptureRequest.Builder.class}, request);

            List<Surface> outputs = Arrays.asList(hsPreviewSurface17, recordSurface);
            setBoolean17("recordingStarting", true);
            camera.createConstrainedHighSpeedCaptureSession(outputs,
                    new CameraCaptureSession.StateCallback() {
                        @Override public void onConfigured(CameraCaptureSession session) {
                            if (!(session instanceof CameraConstrainedHighSpeedCaptureSession)) {
                                failHighSpeedStart17("Camera returned a non-high-speed session.");
                                return;
                            }
                            try {
                                hsSession17 = (CameraConstrainedHighSpeedCaptureSession) session;
                                List<CaptureRequest> burst = hsSession17.createHighSpeedRequestList(request.build());
                                hsSensorStartNs17 = 0L;
                                hsLastSensorNs17 = 0L;
                                hsSensorFrames17 = 0;
                                hsMeasuredSensorFps17 = 0f;
                                hsSession17.setRepeatingBurst(burst, hsFpsCallback17, cameraHandler);
                                hsRecorder17.start();
                                highSpeedRecording17 = true;
                                setBoolean17("recordingStarting", false);
                                setBoolean17("recording", true);
                                setLong17("recordStartedAtMs", System.currentTimeMillis());
                                setZoomLocked17(true);
                                runOnUiThread(() -> Toast.makeText(V17HighSpeedActivity.this,
                                        fps + " fps high-speed recording • zoom/lens locked",
                                        Toast.LENGTH_SHORT).show());
                            } catch (Exception e) {
                                failHighSpeedStart17("High-speed start failed: " + e.getMessage());
                            }
                        }

                        @Override public void onConfigureFailed(CameraCaptureSession session) {
                            failHighSpeedStart17("Vivo HAL rejected the advertised high-speed session.");
                        }
                    }, cameraHandler);
        } catch (Exception e) {
            failHighSpeedStart17("High-speed setup failed: " + e.getMessage());
        }
    }

    private void failHighSpeedStart17(String message) {
        highSpeedRecording17 = false;
        setBoolean17("recording", false);
        setBoolean17("recordingStarting", false);
        finalizeHighSpeedOutput17(false);
        releaseHighSpeedResources17();
        setZoomLocked17(false);
        Handler camera = field17("cameraHandler", Handler.class);
        if (camera != null) camera.post(() -> invoke17("startPreviewSession", new Class[]{}));
        toast17(message);
    }

    private void stopHighSpeed17(boolean restartPreview) {
        if (!highSpeedRecording17 && hsRecorder17 == null) return;
        Uri saved = hsOutputUri17;
        float sensor = hsMeasuredSensorFps17;
        boolean keep = false;
        try {
            if (hsSession17 != null) {
                try { hsSession17.stopRepeating(); } catch (Exception ignored) {}
                try { hsSession17.abortCaptures(); } catch (Exception ignored) {}
            }
            if (hsRecorder17 != null) hsRecorder17.stop();
            keep = true;
        } catch (Exception e) {
            toast17("High-speed recording failed: " + e.getMessage());
        } finally {
            finalizeHighSpeedOutput17(keep);
            highSpeedRecording17 = false;
            setBoolean17("recording", false);
            setBoolean17("recordingStarting", false);
            setObject17("captureSession", null);
            releaseHighSpeedResources17();
            setZoomLocked17(false);
            if (restartPreview) {
                Handler camera = field17("cameraHandler", Handler.class);
                if (camera != null) camera.postDelayed(() -> invoke17("startPreviewSession", new Class[]{}), 100L);
            }
        }
        if (keep && saved != null) verifyHighSpeedFile17(saved, sensor);
    }

    private void prepareHighSpeedRecorder17(Size size, int fps, EncoderConfig17 encoder) throws Exception {
        releaseHighSpeedResources17();
        hsRecorder17 = new MediaRecorder();
        hsRecorder17.setVideoSource(MediaRecorder.VideoSource.SURFACE);
        hsRecorder17.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4);
        hsRecorder17.setVideoEncoder(encoder.mediaRecorderEncoder);
        hsRecorder17.setVideoSize(size.getWidth(), size.getHeight());
        hsRecorder17.setVideoFrameRate(fps);
        hsRecorder17.setVideoEncodingBitRate(encoder.bitrate);
        Object orientation = invoke17("computeOrientationHint", new Class[]{});
        if (orientation instanceof Integer) hsRecorder17.setOrientationHint((Integer) orientation);

        ContentValues values = new ContentValues();
        values.put(android.provider.MediaStore.Video.Media.DISPLAY_NAME,
                "X100_" + (size.getWidth() >= 3800 ? "4K" : "1080P") + "_" + fps + "FPS_HS_" + System.currentTimeMillis() + ".mp4");
        values.put(android.provider.MediaStore.Video.Media.MIME_TYPE, "video/mp4");
        values.put(android.provider.MediaStore.Video.Media.RELATIVE_PATH, Environment.DIRECTORY_DCIM + "/Camera");
        values.put(android.provider.MediaStore.Video.Media.IS_PENDING, 1);

        ContentResolver resolver = getContentResolver();
        hsOutputUri17 = resolver.insert(android.provider.MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values);
        if (hsOutputUri17 == null) throw new IllegalStateException("Cannot create high-speed output file");
        hsOutputPfd17 = resolver.openFileDescriptor(hsOutputUri17, "w");
        if (hsOutputPfd17 == null) throw new IllegalStateException("Cannot open high-speed output file");
        FileDescriptor fd = hsOutputPfd17.getFileDescriptor();
        hsRecorder17.setOutputFile(fd);
        hsRecorder17.prepare();
    }

    private EncoderConfig17 findEncoder17(Size size, int fps) {
        String[] preferred = {"video/avc", "video/hevc"};
        for (String mime : preferred) {
            for (MediaCodecInfo info : new MediaCodecList(MediaCodecList.ALL_CODECS).getCodecInfos()) {
                if (!info.isEncoder()) continue;
                boolean typeMatch = false;
                for (String type : info.getSupportedTypes()) {
                    if (mime.equalsIgnoreCase(type)) { typeMatch = true; break; }
                }
                if (!typeMatch) continue;
                try {
                    MediaCodecInfo.CodecCapabilities caps = info.getCapabilitiesForType(mime);
                    MediaCodecInfo.VideoCapabilities video = caps.getVideoCapabilities();
                    if (video == null || !video.areSizeAndRateSupported(size.getWidth(), size.getHeight(), fps)) continue;
                    int wanted = size.getWidth() >= 3800 ? 160_000_000 : (fps >= 240 ? 100_000_000 : 70_000_000);
                    Range<Integer> bitrate = video.getBitrateRange();
                    int clamped = Math.max(bitrate.getLower(), Math.min(wanted, bitrate.getUpper()));
                    return new EncoderConfig17(
                            "video/hevc".equals(mime) ? MediaRecorder.VideoEncoder.HEVC : MediaRecorder.VideoEncoder.H264,
                            clamped);
                } catch (Exception ignored) {}
            }
        }
        return null;
    }

    private void finalizeHighSpeedOutput17(boolean keep) {
        try { if (hsOutputPfd17 != null) hsOutputPfd17.close(); } catch (Exception ignored) {}
        hsOutputPfd17 = null;
        if (hsOutputUri17 != null) {
            try {
                if (keep) {
                    ContentValues done = new ContentValues();
                    done.put(android.provider.MediaStore.Video.Media.IS_PENDING, 0);
                    getContentResolver().update(hsOutputUri17, done, null, null);
                } else {
                    getContentResolver().delete(hsOutputUri17, null, null);
                }
            } catch (Exception ignored) {}
        }
        if (!keep) hsOutputUri17 = null;
    }

    private void releaseHighSpeedResources17() {
        if (hsSession17 != null) {
            try { hsSession17.close(); } catch (Exception ignored) {}
            hsSession17 = null;
        }
        if (hsRecorder17 != null) {
            try { hsRecorder17.reset(); } catch (Exception ignored) {}
            try { hsRecorder17.release(); } catch (Exception ignored) {}
            hsRecorder17 = null;
        }
        if (hsPreviewSurface17 != null) {
            try { hsPreviewSurface17.release(); } catch (Exception ignored) {}
            hsPreviewSurface17 = null;
        }
    }

    private void verifyHighSpeedFile17(Uri uri, float sensorFps) {
        final Uri verifyUri = uri;
        hsOutputUri17 = null;
        new Thread(() -> {
            MediaExtractor extractor = new MediaExtractor();
            float encoded = 0f;
            try {
                extractor.setDataSource(this, verifyUri, null);
                int track = -1;
                for (int i = 0; i < extractor.getTrackCount(); i++) {
                    MediaFormat f = extractor.getTrackFormat(i);
                    String mime = f.getString(MediaFormat.KEY_MIME);
                    if (mime != null && mime.startsWith("video/")) { track = i; break; }
                }
                if (track >= 0) {
                    extractor.selectTrack(track);
                    long first = -1L, last = -1L;
                    int frames = 0;
                    while (true) {
                        long t = extractor.getSampleTime();
                        if (t < 0L) break;
                        if (first < 0L) first = t;
                        last = t;
                        frames++;
                        if (!extractor.advance()) break;
                    }
                    if (frames > 1 && last > first) encoded = (frames - 1) * 1_000_000f / (last - first);
                }
            } catch (Exception ignored) {
            } finally {
                try { extractor.release(); } catch (Exception ignored) {}
            }

            final float result = encoded;
            runOnUiThread(() -> {
                if (fpsStatus17 != null && result > 0f) {
                    fpsStatus17.setText(String.format(Locale.US, "Last encoded file: %.1f fps", result));
                }
                invoke17("refreshLatestMedia", new Class[]{});
                if (result > 0f) {
                    Toast.makeText(this,
                            String.format(Locale.US, "High-speed saved: %.1f fps encoded • camera %.1f fps", result, sensorFps),
                            Toast.LENGTH_LONG).show();
                } else {
                    Toast.makeText(this, "High-speed video saved to DCIM/Camera.", Toast.LENGTH_LONG).show();
                }
            });
        }, "X100HighSpeedVerifier").start();
    }

    private void setZoomLocked17(boolean locked) {
        if (zoomStrip17 != null) {
            setEnabledRecursive17(zoomStrip17, !locked);
            zoomStrip17.setAlpha(locked ? 0.45f : 1f);
        }
    }

    private void setEnabledRecursive17(View view, boolean enabled) {
        view.setEnabled(enabled);
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) setEnabledRecursive17(group.getChildAt(i), enabled);
        }
    }

    private Size selectedSize17() {
        Object value = field17("selectedSize", Object.class);
        return value instanceof Size ? (Size) value : FHD;
    }

    private boolean containsSize17(Size[] sizes, Size wanted) {
        if (sizes == null || wanted == null) return false;
        for (Size size : sizes) if (wanted.equals(size)) return true;
        return false;
    }

    private boolean containsInt17(int[] values, int wanted) {
        if (values == null) return false;
        for (int value : values) if (value == wanted) return true;
        return false;
    }

    private void saveChoice17() {
        if (prefs17 == null) return;
        if (!prefs17.getBoolean(K_PRESERVE, true)) {
            prefs17.edit().remove(K_FPS_CHOICE).apply();
            return;
        }
        int choice = highSpeedChoice17 >= 120 ? highSpeedChoice17 : int17("selectedFps", 30);
        prefs17.edit().putInt(K_FPS_CHOICE, choice).apply();
    }

    private TextView findTextStarting17(View root, String prefix) {
        if (root == null) return null;
        if (root instanceof TextView) {
            CharSequence text = ((TextView) root).getText();
            if (text != null && text.toString().startsWith(prefix)) return (TextView) root;
        }
        if (root instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) root;
            for (int i = 0; i < group.getChildCount(); i++) {
                TextView found = findTextStarting17(group.getChildAt(i), prefix);
                if (found != null) return found;
            }
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    private <T> T exactField17(Class<?> owner, String name, Class<T> type) {
        try {
            Field field = owner.getDeclaredField(name);
            field.setAccessible(true);
            Object value = field.get(this);
            return value == null ? null : (T) value;
        } catch (Exception e) {
            return null;
        }
    }

    private Boolean exactBoolean17(Class<?> owner, String name) {
        try {
            Field field = owner.getDeclaredField(name);
            field.setAccessible(true);
            return field.getBoolean(this);
        } catch (Exception e) {
            return false;
        }
    }

    @SuppressWarnings("unchecked")
    private <T> T field17(String name, Class<T> type) {
        Class<?> c = getClass();
        while (c != null) {
            try {
                Field field = c.getDeclaredField(name);
                field.setAccessible(true);
                Object value = field.get(this);
                return value == null ? null : (T) value;
            } catch (NoSuchFieldException e) {
                c = c.getSuperclass();
            } catch (Exception e) {
                return null;
            }
        }
        return null;
    }

    private boolean bool17(String name) {
        Class<?> c = getClass();
        while (c != null) {
            try {
                Field field = c.getDeclaredField(name);
                field.setAccessible(true);
                return field.getBoolean(this);
            } catch (NoSuchFieldException e) { c = c.getSuperclass(); }
            catch (Exception e) { return false; }
        }
        return false;
    }

    private int int17(String name, int fallback) {
        Class<?> c = getClass();
        while (c != null) {
            try {
                Field field = c.getDeclaredField(name);
                field.setAccessible(true);
                return field.getInt(this);
            } catch (NoSuchFieldException e) { c = c.getSuperclass(); }
            catch (Exception e) { return fallback; }
        }
        return fallback;
    }

    private void setBoolean17(String name, boolean value) { setPrimitive17(name, value, 0, 0L, null, 1); }
    private void setInt17(String name, int value) { setPrimitive17(name, false, value, 0L, null, 2); }
    private void setLong17(String name, long value) { setPrimitive17(name, false, 0, value, null, 3); }
    private void setObject17(String name, Object value) { setPrimitive17(name, false, 0, 0L, value, 4); }

    private void setPrimitive17(String name, boolean b, int i, long l, Object o, int kind) {
        Class<?> c = getClass();
        while (c != null) {
            try {
                Field field = c.getDeclaredField(name);
                field.setAccessible(true);
                if (kind == 1) field.setBoolean(this, b);
                else if (kind == 2) field.setInt(this, i);
                else if (kind == 3) field.setLong(this, l);
                else field.set(this, o);
                return;
            } catch (NoSuchFieldException e) { c = c.getSuperclass(); }
            catch (Exception e) { return; }
        }
    }

    private Object invoke17(String name, Class<?>[] types, Object... args) {
        Class<?> c = getClass();
        while (c != null) {
            try {
                Method method = c.getDeclaredMethod(name, types);
                method.setAccessible(true);
                return method.invoke(this, args);
            } catch (NoSuchMethodException e) {
                c = c.getSuperclass();
            } catch (Exception e) {
                return null;
            }
        }
        return null;
    }

    private Object invokeExact17(Class<?> owner, String name, Class<?>[] types, Object... args) {
        try {
            Method method = owner.getDeclaredMethod(name, types);
            method.setAccessible(true);
            return method.invoke(this, args);
        } catch (Exception e) {
            return null;
        }
    }

    private void toast17(String text) {
        runOnUiThread(() -> Toast.makeText(this, text, Toast.LENGTH_LONG).show());
    }

    private static final class EncoderConfig17 {
        final int mediaRecorderEncoder;
        final int bitrate;
        EncoderConfig17(int mediaRecorderEncoder, int bitrate) {
            this.mediaRecorderEncoder = mediaRecorderEncoder;
            this.bitrate = bitrate;
        }
    }
}
