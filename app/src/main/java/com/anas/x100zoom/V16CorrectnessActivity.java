package com.anas.x100zoom;

import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.SurfaceTexture;
import android.graphics.drawable.GradientDrawable;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.CaptureResult;
import android.hardware.camera2.TotalCaptureResult;
import android.hardware.camera2.params.OutputConfiguration;
import android.hardware.camera2.params.SessionConfiguration;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.media.MediaExtractor;
import android.media.MediaFormat;
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

/**
 * V16 correctness pass.
 *
 * Fixes device-test regressions rather than adding decorative features:
 *  - physically bounded Photo preview viewports for 1:1 / 4:3 / 16:9
 *  - Full returns to edge-to-edge preview; outside a bounded ratio is true black,
 *    not a translucent/blurred copy of the camera preview
 *  - old V12 ratio mask and legacy 4K/FPS badge are removed at their source views
 *  - settings sheet is force-detached after its closing animation so no shadow remains
 *  - optional persistent camera settings through SharedPreferences
 *  - constant-rate recording validates Camera2 stream minimum frame duration
 *  - the requested FPS is supplied as a SessionConfiguration session parameter
 *  - the final MP4 is measured with MediaExtractor and reports its actual average FPS
 */
public class V16CorrectnessActivity extends V15VideoUiActivity {
    private static final int ACCENT = 0xFFFFD129;
    private static final int PANEL = 0xFF050505;
    private static final Size PREVIEW_SIZE = new Size(1920, 1080);
    private static final String PREFS = "x100_camera_prefs";
    private static final String K_PRESERVE = "preserve_settings";

    private final Handler v16 = new Handler(Looper.getMainLooper());
    private SharedPreferences prefs;

    private boolean installed16 = false;
    private boolean restored16 = false;
    private boolean lastSettings16 = false;
    private boolean lastPhoto16 = false;
    private String lastRatio16 = "";
    private float lastZoom16 = -1f;
    private long lastSaveMs = 0L;
    private long lastModeValidationMs = 0L;
    private boolean warnedModeFallback = false;

    private FrameLayout root16;
    private FrameLayout settingsSheet16;
    private FrameLayout topChrome16;
    private FrameLayout bottomChrome16;
    private LinearLayout videoPanel16;
    private TextureView preview16;
    private View transitionOverlay16;
    private View gridOverlay16;
    private View ratioFrame16;
    private TextView preserveButton16;
    private TextView fpsStatus16;

    private TextView video1080_16;
    private TextView video4k_16;
    private TextView fps30_16;
    private TextView fps60_16;

    private long sensorWindowStartNs = 0L;
    private int sensorFrames = 0;
    private volatile float measuredSensorFps = 0f;
    private volatile float lastEncodedFps = 0f;

    private final CameraCaptureSession.CaptureCallback fpsCallback16 =
            new CameraCaptureSession.CaptureCallback() {
        @Override public void onCaptureCompleted(CameraCaptureSession session,
                                                  CaptureRequest request,
                                                  TotalCaptureResult result) {
            Long ts = result.get(CaptureResult.SENSOR_TIMESTAMP);
            if (ts == null || ts <= 0L) return;
            if (sensorWindowStartNs == 0L) {
                sensorWindowStartNs = ts;
                sensorFrames = 1;
                return;
            }
            sensorFrames++;
            long span = ts - sensorWindowStartNs;
            if (span >= 900_000_000L && sensorFrames > 2) {
                measuredSensorFps = (sensorFrames - 1) * 1_000_000_000f / span;
                sensorWindowStartNs = ts;
                sensorFrames = 1;
            }
        }
    };

    private final Runnable watcher16 = new Runnable() {
        @Override public void run() {
            cleanupLegacyViews();
            if (installed16) {
                syncSettingsResidue();
                syncTruePhotoViewport();
                syncVideoControls16();
                long now = android.os.SystemClock.elapsedRealtime();
                if (now - lastSaveMs >= 500L) {
                    lastSaveMs = now;
                    persistCurrentSettings();
                }
                if (now - lastModeValidationMs >= 650L) {
                    lastModeValidationMs = now;
                    validateSelectedVideoMode();
                }
            } else {
                tryInstallV16();
            }
            v16.postDelayed(this, 55L);
        }
    };

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        // The fields already exist before the delayed legacy UI layers install.
        // Restore early, then restore once again when all V15 controls exist.
        restoreSettingsValues();
        v16.postDelayed(watcher16, 35L);
    }

    @Override protected void onDestroy() {
        persistCurrentSettings();
        v16.removeCallbacksAndMessages(null);
        super.onDestroy();
    }

    private int dp16(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    private GradientDrawable pill16(int color, int radius) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(color);
        d.setCornerRadius(dp16(radius));
        return d;
    }

    /** Stop old debug chrome from ever showing through the new camera UI. */
    private void cleanupLegacyViews() {
        View legacyBadge = exactField(MainActivity.class, "modeBadge", View.class);
        if (legacyBadge != null) {
            legacyBadge.setVisibility(View.GONE);
            legacyBadge.setAlpha(0f);
            if (legacyBadge.getParent() instanceof ViewGroup) {
                try { ((ViewGroup) legacyBadge.getParent()).removeView(legacyBadge); } catch (Exception ignored) {}
            }
        }

        View oldPhotoTop = exactField(V12CameraActivity.class, "photoTopRow", View.class);
        if (oldPhotoTop != null) oldPhotoTop.setVisibility(View.GONE);
        View oldRatioButton = exactField(V12CameraActivity.class, "v12RatioButton", View.class);
        if (oldRatioButton != null) oldRatioButton.setVisibility(View.GONE);

        ratioFrame16 = exactField(V12CameraActivity.class, "ratioFrame", View.class);
        if (ratioFrame16 != null) {
            ratioFrame16.setVisibility(View.GONE);
            ratioFrame16.setAlpha(0f);
        }
    }

    private void tryInstallV16() {
        Boolean installed15 = exactBoolean(V15VideoUiActivity.class, "installed15");
        if (!Boolean.TRUE.equals(installed15)) return;

        root16 = field16("cameraRoot", FrameLayout.class);
        settingsSheet16 = field16("settingsSheet", FrameLayout.class);
        topChrome16 = field16("topChrome", FrameLayout.class);
        bottomChrome16 = field16("bottomChrome", FrameLayout.class);
        videoPanel16 = exactField(V15VideoUiActivity.class, "videoPanel", LinearLayout.class);
        preview16 = exactField(MainActivity.class, "textureView", TextureView.class);
        transitionOverlay16 = exactField(MainActivity.class, "transitionOverlay", View.class);
        gridOverlay16 = exactField(CameraChromeActivity.class, "gridOverlay", View.class);

        if (root16 == null || settingsSheet16 == null || preview16 == null ||
                topChrome16 == null || bottomChrome16 == null) return;

        // V12's watcher is no longer needed and is one source of stale ratio chrome.
        Handler v12Handler = exactField(V12CameraActivity.class, "v12Ui", Handler.class);
        if (v12Handler != null) v12Handler.removeCallbacksAndMessages(null);

        // V15 created the dedicated video controls. V16 owns their state from here
        // so weaker "exact AE range only" validation cannot overwrite stream timing checks.
        Handler v15Handler = exactField(V15VideoUiActivity.class, "v15", Handler.class);
        if (v15Handler != null) v15Handler.removeCallbacksAndMessages(null);

        video1080_16 = exactField(V15VideoUiActivity.class, "video1080", TextView.class);
        video4k_16 = exactField(V15VideoUiActivity.class, "video4k", TextView.class);
        fps30_16 = exactField(V15VideoUiActivity.class, "fps30", TextView.class);
        fps60_16 = exactField(V15VideoUiActivity.class, "fps60", TextView.class);

        if (video1080_16 != null) video1080_16.setOnClickListener(v -> selectResolution16(false));
        if (video4k_16 != null) video4k_16.setOnClickListener(v -> selectResolution16(true));
        if (fps30_16 != null) fps30_16.setOnClickListener(v -> selectFps16(30));
        if (fps60_16 != null) fps60_16.setOnClickListener(v -> selectFps16(60));

        installPreserveSetting();
        installFpsStatus();
        replaceLegacyShutter();
        restoreSettingsValues();
        applyRestoredUiState();

        lastPhoto16 = bool16("photoMode");
        lastSettings16 = bool16("settingsOpen");
        lastRatio16 = string16("photoRatio", "4:3");
        installed16 = true;
        restored16 = true;
        syncTruePhotoViewport();
        syncVideoControls16();
    }

    private void installPreserveSetting() {
        preserveButton16 = new TextView(this);
        preserveButton16.setTextSize(11f);
        preserveButton16.setTypeface(null, android.graphics.Typeface.BOLD);
        preserveButton16.setGravity(Gravity.CENTER);
        preserveButton16.setPadding(dp16(8), 0, dp16(8), 0);
        preserveButton16.setOnClickListener(v -> {
            boolean next = !prefs.getBoolean(K_PRESERVE, true);
            prefs.edit().putBoolean(K_PRESERVE, next).apply();
            if (!next) clearStoredCameraValues();
            updatePreserveButton();
            Toast.makeText(this,
                    next ? "Camera settings will be preserved." : "Camera settings will reset on future launches.",
                    Toast.LENGTH_SHORT).show();
        });
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(dp16(150), dp16(42));
        lp.gravity = Gravity.TOP | Gravity.CENTER_HORIZONTAL;
        lp.topMargin = dp16(14);
        settingsSheet16.addView(preserveButton16, lp);
        updatePreserveButton();
    }

    private void updatePreserveButton() {
        if (preserveButton16 == null) return;
        boolean on = prefs.getBoolean(K_PRESERVE, true);
        preserveButton16.setText(on ? "Preserve settings  ON" : "Preserve settings  OFF");
        preserveButton16.setTextColor(on ? Color.BLACK : Color.WHITE);
        preserveButton16.setBackground(pill16(on ? ACCENT : 0xFF292929, 12));
    }

    private void installFpsStatus() {
        if (videoPanel16 == null) return;
        fpsStatus16 = new TextView(this);
        fpsStatus16.setText("Last encoded file: — fps");
        fpsStatus16.setTextColor(0xFF9F9F9F);
        fpsStatus16.setTextSize(10f);
        fpsStatus16.setGravity(Gravity.CENTER);
        videoPanel16.addView(fpsStatus16, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp16(28)));
    }

    private void syncSettingsResidue() {
        boolean open = bool16("settingsOpen");
        boolean photo = bool16("photoMode");

        if (settingsSheet16 != null) {
            if (open) {
                settingsSheet16.setVisibility(View.VISIBLE);
                settingsSheet16.setBackgroundColor(PANEL);
            } else if (lastSettings16 && !open) {
                // Let the inherited close animation finish, then hard-reset every
                // visual property. This removes the persistent shadow/ghost layer.
                v16.postDelayed(this::forceSettingsCompletelyGone, 220L);
            }
        }

        if (videoPanel16 != null) {
            videoPanel16.setVisibility(open && !photo ? View.VISIBLE : View.GONE);
        }

        // Parent photo settings content remains for Photo only; hide it completely in Video.
        LinearLayout baseContent = findSettingsBaseContent();
        if (baseContent != null) baseContent.setVisibility(open && photo ? View.VISIBLE : View.GONE);

        if (preserveButton16 != null) preserveButton16.setVisibility(open ? View.VISIBLE : View.GONE);

        lastSettings16 = open;
    }

    private void forceSettingsCompletelyGone() {
        if (bool16("settingsOpen")) return;
        if (settingsSheet16 != null) {
            settingsSheet16.animate().cancel();
            settingsSheet16.setAlpha(0f);
            settingsSheet16.setScaleX(1f);
            settingsSheet16.setScaleY(1f);
            settingsSheet16.setTranslationY(-dp16(24));
            settingsSheet16.setVisibility(View.GONE);
        }
        View dismiss = exactField(V14PolishActivity.class, "settingsDismissLayer", View.class);
        if (dismiss != null) dismiss.setVisibility(View.GONE);
        if (videoPanel16 != null) videoPanel16.setVisibility(View.GONE);
    }

    private LinearLayout findSettingsBaseContent() {
        if (settingsSheet16 == null) return null;
        for (int i = 0; i < settingsSheet16.getChildCount(); i++) {
            View v = settingsSheet16.getChildAt(i);
            if (v instanceof LinearLayout && v != videoPanel16) {
                LinearLayout l = (LinearLayout) v;
                if (l.getOrientation() == LinearLayout.VERTICAL) return l;
            }
        }
        return null;
    }

    /**
     * Replace V12's translucent framing mask with a real TextureView viewport.
     * Areas outside the requested ratio are the root's true black background.
     */
    private void syncTruePhotoViewport() {
        if (preview16 == null || root16 == null || root16.getWidth() <= 0 || root16.getHeight() <= 0) return;
        boolean photo = bool16("photoMode");
        String ratio = string16("photoRatio", "4:3");
        float zoom = float16("requestedUiZoom", 1f);

        if (photo != lastPhoto16 || !ratio.equals(lastRatio16)) {
            lastPhoto16 = photo;
            lastRatio16 = ratio;
            applyViewportGeometry(photo, ratio);
        }
        if (Math.abs(zoom - lastZoom16) > 0.002f || photo) {
            lastZoom16 = zoom;
            applyAspectAndZoomTransform(photo, ratio, zoom);
        }
    }

    private void applyViewportGeometry(boolean photo, String ratio) {
        int rootW = root16.getWidth();
        int rootH = root16.getHeight();
        if (rootW <= 0 || rootH <= 0) return;

        int width = rootW;
        int height = rootH;
        int top = 0;

        if (photo && !"Full".equalsIgnoreCase(ratio)) {
            float portraitWH;
            if ("1:1".equals(ratio)) portraitWH = 1f;
            else if ("16:9".equals(ratio)) portraitWH = 9f / 16f;
            else portraitWH = 3f / 4f;

            height = Math.round(width / portraitWH);
            int usableTop = topChrome16 != null ? topChrome16.getHeight() : 0;
            int usableBottom = rootH - (bottomChrome16 != null ? bottomChrome16.getHeight() : 0);
            int usableH = Math.max(1, usableBottom - usableTop);
            if (height <= usableH) top = usableTop + (usableH - height) / 2;
            else top = Math.max(0, (rootH - height) / 2);
        }

        applyFrameLayout(preview16, width, height, top);
        applyFrameLayout(transitionOverlay16, width, height, top);
        applyFrameLayout(gridOverlay16, width, height, top);

        root16.setBackgroundColor(Color.BLACK);
        if (ratioFrame16 != null) ratioFrame16.setVisibility(View.GONE);
    }

    private void applyFrameLayout(View v, int width, int height, int top) {
        if (v == null) return;
        ViewGroup.LayoutParams raw = v.getLayoutParams();
        FrameLayout.LayoutParams lp = raw instanceof FrameLayout.LayoutParams
                ? (FrameLayout.LayoutParams) raw
                : new FrameLayout.LayoutParams(width, height);
        lp.width = width;
        lp.height = height;
        lp.gravity = Gravity.TOP | Gravity.CENTER_HORIZONTAL;
        lp.topMargin = top;
        lp.bottomMargin = 0;
        v.setLayoutParams(lp);
    }

    /** Correct the 16:9 preview buffer to the physical viewport without stretching. */
    private void applyAspectAndZoomTransform(boolean photo, String ratio, float requestedZoom) {
        if (preview16.getWidth() <= 0 || preview16.getHeight() <= 0) return;
        float viewW = preview16.getWidth();
        float viewH = preview16.getHeight();

        // MainActivity's current preview producer is 1920x1080 landscape. In portrait
        // presentation that is effectively 1080x1920.
        float sourceW = 1080f;
        float sourceH = 1920f;
        float sx = viewW / sourceW;
        float sy = viewH / sourceH;
        float fill = Math.max(sx, sy);
        float aspectX = fill / Math.max(0.0001f, sx);
        float aspectY = fill / Math.max(0.0001f, sy);

        float hardwareUiMax;
        boolean activeTele = bool16("activeTele");
        if (activeTele) hardwareUiMax = 3f * Math.max(1f, float16("teleMaxZoom", 10f));
        else hardwareUiMax = Math.max(1f, float16("logicalMaxZoom", 10f));
        float extra = Math.max(1f, requestedZoom / hardwareUiMax);

        Matrix matrix = new Matrix();
        matrix.setScale(aspectX * extra, aspectY * extra, viewW / 2f, viewH / 2f);
        preview16.setTransform(matrix);
    }

    private void replaceLegacyShutter() {
        Button old = exactField(MainActivity.class, "recordButton", Button.class);
        if (old == null) return;
        old.setOnClickListener(v -> {
            if (bool16("photoMode")) {
                invoke16("capturePhoto", new Class[]{});
                return;
            }
            if (bool16("recording") || bool16("recordingStarting")) {
                stopVerifiedRecording();
            } else {
                startVerifiedRecording();
            }
        });
    }

    private void startVerifiedRecording() {
        CameraDevice camera = field16("cameraDevice", CameraDevice.class);
        CameraCharacteristics chars = field16("currentChars", CameraCharacteristics.class);
        Handler cameraHandler = field16("cameraHandler", Handler.class);
        TextureView texture = exactField(MainActivity.class, "textureView", TextureView.class);
        Object sizeObj = object16("selectedSize");
        Size size = sizeObj instanceof Size ? (Size) sizeObj : new Size(3840, 2160);
        int fps = int16("selectedFps", 30);

        if (camera == null || chars == null || cameraHandler == null || texture == null || !texture.isAvailable()) {
            Toast.makeText(this, "Camera is not ready yet.", Toast.LENGTH_SHORT).show();
            return;
        }
        if (!supportsTrueConstantMode(chars, size, fps)) {
            Toast.makeText(this,
                    modeFailureText(chars, size, fps), Toast.LENGTH_LONG).show();
            return;
        }

        try {
            invoke16("prepareRecorder", new Class[]{});
            MediaRecorder recorder = field16("recorder", MediaRecorder.class);
            if (recorder == null) throw new IllegalStateException("Recorder was not prepared");

            setBoolean16("recordingStarting", true);
            invoke16("closeSessionOnly", new Class[]{});

            SurfaceTexture st = texture.getSurfaceTexture();
            if (st == null) throw new IllegalStateException("Preview surface unavailable");
            st.setDefaultBufferSize(PREVIEW_SIZE.getWidth(), PREVIEW_SIZE.getHeight());
            Surface previewSurface = new Surface(st);
            Surface recorderSurface = recorder.getSurface();

            CaptureRequest.Builder builder = camera.createCaptureRequest(CameraDevice.TEMPLATE_RECORD);
            builder.addTarget(previewSurface);
            builder.addTarget(recorderSurface);
            invoke16("configureCommonRequest", new Class[]{CaptureRequest.Builder.class}, builder);

            Range<Integer> exact = exactFpsRange(chars, fps);
            if (exact == null) throw new IllegalStateException("No fixed " + fps + " fps range");
            builder.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, exact);

            List<OutputConfiguration> outputs = new ArrayList<>();
            outputs.add(new OutputConfiguration(previewSurface));
            outputs.add(new OutputConfiguration(recorderSurface));

            SessionConfiguration config = new SessionConfiguration(
                    SessionConfiguration.SESSION_REGULAR,
                    outputs,
                    command -> cameraHandler.post(command),
                    new CameraCaptureSession.StateCallback() {
                        @Override public void onConfigured(CameraCaptureSession session) {
                            if (field16("cameraDevice", CameraDevice.class) == null) return;
                            try {
                                setObject16("captureSession", session);
                                setObject16("repeatingBuilder", builder);
                                sensorWindowStartNs = 0L;
                                sensorFrames = 0;
                                measuredSensorFps = 0f;
                                session.setRepeatingRequest(builder.build(), fpsCallback16, cameraHandler);
                                recorder.start();
                                setBoolean16("recordingStarting", false);
                                setBoolean16("recording", true);
                                setLong16("recordStartedAtMs", System.currentTimeMillis());
                                runOnUiThread(() -> Toast.makeText(V16CorrectnessActivity.this,
                                        (size.getWidth() >= 3800 ? "4K " : "1080P ") + fps + " recording",
                                        Toast.LENGTH_SHORT).show());
                            } catch (Exception e) {
                                failRecordingStart("Session start: " + e.getMessage());
                            }
                        }

                        @Override public void onConfigureFailed(CameraCaptureSession session) {
                            failRecordingStart("Vivo HAL rejected the " + fps + " fps recording session.");
                        }
                    });

            // FPS is a session parameter on modern Camera2. Vivo can choose the
            // sensor/ISP mode before stream startup instead of receiving it too late.
            config.setSessionParameters(builder.build());
            camera.createCaptureSession(config);
        } catch (Exception e) {
            failRecordingStart("Recording setup: " + e.getMessage());
        }
    }

    private void failRecordingStart(String message) {
        setBoolean16("recordingStarting", false);
        setBoolean16("recording", false);
        invoke16("safeResetRecorder", new Class[]{});
        Handler handler = field16("cameraHandler", Handler.class);
        if (handler != null) handler.post(() -> invoke16("startPreviewSession", new Class[]{}));
        runOnUiThread(() -> Toast.makeText(this, message, Toast.LENGTH_LONG).show());
    }

    private void stopVerifiedRecording() {
        Uri saved = field16("outputUri", Uri.class);
        float sensorFps = measuredSensorFps;
        invoke16("stopRecording", new Class[]{});
        if (saved != null) measureEncodedFile(saved, sensorFps);
    }

    /** Measure actual timestamps in the final MP4 instead of trusting a UI label. */
    private void measureEncodedFile(Uri uri, float sensorFps) {
        new Thread(() -> {
            MediaExtractor extractor = new MediaExtractor();
            float encoded = 0f;
            try {
                extractor.setDataSource(this, uri, null);
                int videoTrack = -1;
                for (int i = 0; i < extractor.getTrackCount(); i++) {
                    MediaFormat f = extractor.getTrackFormat(i);
                    String mime = f.getString(MediaFormat.KEY_MIME);
                    if (mime != null && mime.startsWith("video/")) {
                        videoTrack = i;
                        break;
                    }
                }
                if (videoTrack >= 0) {
                    extractor.selectTrack(videoTrack);
                    long firstUs = -1L, lastUs = -1L;
                    int frames = 0;
                    while (true) {
                        long t = extractor.getSampleTime();
                        if (t < 0L) break;
                        if (firstUs < 0L) firstUs = t;
                        lastUs = t;
                        frames++;
                        if (!extractor.advance()) break;
                    }
                    if (frames > 1 && lastUs > firstUs) {
                        encoded = (frames - 1) * 1_000_000f / (lastUs - firstUs);
                    }
                }
            } catch (Exception ignored) {
            } finally {
                try { extractor.release(); } catch (Exception ignored) {}
            }
            final float finalEncoded = encoded;
            lastEncodedFps = finalEncoded;
            runOnUiThread(() -> {
                if (fpsStatus16 != null && finalEncoded > 0f) {
                    fpsStatus16.setText(String.format(Locale.US,
                            "Last encoded file: %.1f fps", finalEncoded));
                }
                if (finalEncoded > 0f) {
                    Toast.makeText(this, String.format(Locale.US,
                            "Saved video: %.1f fps encoded • camera %.1f fps",
                            finalEncoded, sensorFps), Toast.LENGTH_LONG).show();
                }
            });
        }, "X100FpsVerifier").start();
    }

    private boolean supportsTrueConstantMode(CameraCharacteristics chars, Size size, int fps) {
        if (chars == null || size == null) return false;
        StreamConfigurationMap map = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
        if (map == null || !containsSize(map.getOutputSizes(MediaRecorder.class), size)) return false;
        if (exactFpsRange(chars, fps) == null) return false;

        long recordingMin = minDuration(map, MediaRecorder.class, size);
        long previewMin = containsSize(map.getOutputSizes(SurfaceTexture.class), PREVIEW_SIZE)
                ? minDuration(map, SurfaceTexture.class, PREVIEW_SIZE) : 0L;
        long worst = Math.max(recordingMin, previewMin);
        long required = Math.round(1_000_000_000d / fps);
        // 0 means the HAL did not publish a limiting duration for this output.
        return worst <= 0L || worst <= required + 750_000L;
    }

    private String modeFailureText(CameraCharacteristics chars, Size size, int fps) {
        StreamConfigurationMap map = chars != null
                ? chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP) : null;
        if (map == null || !containsSize(map.getOutputSizes(MediaRecorder.class), size)) {
            return "This active X100 camera path does not expose that recording resolution.";
        }
        if (exactFpsRange(chars, fps) == null) {
            return "This active X100 camera path does not expose fixed " + fps + " fps.";
        }
        long min = minDuration(map, MediaRecorder.class, size);
        if (min > 0L) {
            float max = 1_000_000_000f / min;
            return String.format(Locale.US,
                    "This Camera2 stream is limited to about %.1f fps at this resolution, so %d fps is disabled.",
                    max, fps);
        }
        return "The active Camera2 stream cannot guarantee " + fps + " fps.";
    }

    private long minDuration(StreamConfigurationMap map, Class<?> klass, Size size) {
        try { return map.getOutputMinFrameDuration(klass, size); }
        catch (Exception ignored) { return 0L; }
    }

    private Range<Integer> exactFpsRange(CameraCharacteristics chars, int fps) {
        if (chars == null) return null;
        Range<Integer>[] ranges = chars.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES);
        if (ranges == null) return null;
        for (Range<Integer> r : ranges) {
            if (r.getLower() == fps && r.getUpper() == fps) return r;
        }
        return null;
    }

    private void validateSelectedVideoMode() {
        if (bool16("photoMode") || bool16("recording") || bool16("recordingStarting")) return;
        CameraCharacteristics chars = field16("currentChars", CameraCharacteristics.class);
        Object sizeObj = object16("selectedSize");
        if (!(sizeObj instanceof Size) || chars == null) return;
        Size size = (Size) sizeObj;
        int fps = int16("selectedFps", 30);
        if (supportsTrueConstantMode(chars, size, fps)) {
            warnedModeFallback = false;
            return;
        }

        if (fps == 60 && supportsTrueConstantMode(chars, size, 30)) {
            setInt16("selectedFps", 30);
            if (!warnedModeFallback) {
                warnedModeFallback = true;
                Toast.makeText(this,
                        "60 fps is not physically exposed for this Camera2 stream. Using real 30 fps instead.",
                        Toast.LENGTH_LONG).show();
            }
            syncVideoControls16();
        }
    }

    private void selectResolution16(boolean fourK) {
        if (bool16("recording") || bool16("recordingStarting")) return;
        CameraCharacteristics chars = field16("currentChars", CameraCharacteristics.class);
        Size wanted = fourK ? new Size(3840, 2160) : new Size(1920, 1080);
        int fps = int16("selectedFps", 30);
        if (!supportsTrueConstantMode(chars, wanted, fps)) {
            if (supportsTrueConstantMode(chars, wanted, 30)) fps = 30;
            else if (supportsTrueConstantMode(chars, wanted, 60)) fps = 60;
            else {
                Toast.makeText(this, "That recording resolution is not exposed at a verified constant FPS.",
                        Toast.LENGTH_SHORT).show();
                return;
            }
        }
        applyVideoMode16(wanted, fps);
    }

    private void selectFps16(int fps) {
        if (bool16("recording") || bool16("recordingStarting")) return;
        CameraCharacteristics chars = field16("currentChars", CameraCharacteristics.class);
        Object sizeObj = object16("selectedSize");
        Size size = sizeObj instanceof Size ? (Size) sizeObj : new Size(3840, 2160);
        if (!supportsTrueConstantMode(chars, size, fps)) {
            Toast.makeText(this, modeFailureText(chars, size, fps), Toast.LENGTH_LONG).show();
            return;
        }
        applyVideoMode16(size, fps);
    }

    private void applyVideoMode16(Size size, int fps) {
        setObject16("selectedSize", size);
        setInt16("selectedFps", fps);
        Handler h = field16("cameraHandler", Handler.class);
        if (h != null) h.post(() -> invoke16("startPreviewSession", new Class[]{}));
        syncVideoControls16();
        persistCurrentSettings();
    }

    private void syncVideoControls16() {
        if (video1080_16 == null || bool16("photoMode")) return;
        CameraCharacteristics chars = field16("currentChars", CameraCharacteristics.class);
        Object sizeObj = object16("selectedSize");
        Size current = sizeObj instanceof Size ? (Size) sizeObj : new Size(3840, 2160);
        int fps = int16("selectedFps", 30);
        boolean fourK = current.getWidth() >= 3800;

        styleVideoChoice(video1080_16, !fourK,
                supportsTrueConstantMode(chars, new Size(1920, 1080), fps));
        styleVideoChoice(video4k_16, fourK,
                supportsTrueConstantMode(chars, new Size(3840, 2160), fps));
        styleVideoChoice(fps30_16, fps == 30,
                supportsTrueConstantMode(chars, current, 30));
        styleVideoChoice(fps60_16, fps == 60,
                supportsTrueConstantMode(chars, current, 60));

        if (fpsStatus16 != null && lastEncodedFps > 0f) {
            fpsStatus16.setText(String.format(Locale.US,
                    "Last encoded file: %.1f fps", lastEncodedFps));
        }
    }

    private void styleVideoChoice(TextView view, boolean selected, boolean enabled) {
        if (view == null) return;
        view.setEnabled(enabled);
        view.setAlpha(enabled ? 1f : 0.32f);
        view.setTextColor(selected && enabled ? Color.BLACK : (enabled ? Color.WHITE : 0xFF777777));
        view.setBackground(selected && enabled ? pill16(ACCENT, 8) : null);
    }

    private boolean containsSize(Size[] sizes, Size wanted) {
        if (sizes == null) return false;
        for (Size s : sizes) if (wanted.equals(s)) return true;
        return false;
    }

    private void restoreSettingsValues() {
        if (prefs == null || !prefs.getBoolean(K_PRESERVE, true)) return;
        setObject16("photoRatio", prefs.getString("photo_ratio", "4:3"));
        setBoolean16("maxPhotoMode", prefs.getBoolean("photo_max", false));
        setInt16("photoTimerSeconds", prefs.getInt("photo_timer", 0));
        setBoolean16("gridEnabled", prefs.getBoolean("grid", false));
        setBoolean16("manualMode", prefs.getBoolean("manual_focus", false));
        setBoolean16("flashEnabled", prefs.getBoolean("flash", false));
        setBoolean16("videoStabilizationEnabled", prefs.getBoolean("video_stabilization", true));
        boolean fourK = prefs.getBoolean("video_4k", true);
        setObject16("selectedSize", fourK ? new Size(3840, 2160) : new Size(1920, 1080));
        setInt16("selectedFps", prefs.getInt("video_fps", 30));
    }

    private void applyRestoredUiState() {
        boolean grid = bool16("gridEnabled");
        View grid = exactField(CameraChromeActivity.class, "gridOverlay", View.class);
        if (grid != null) grid.setVisibility(grid ? View.VISIBLE : View.GONE);
        invoke16("updateGridButton", new Class[]{});
        invoke16("updateFlashButton", new Class[]{boolean.class},
                Boolean.TRUE.equals(currentFlashAvailable()));
        updatePreserveButton();
    }

    private Boolean currentFlashAvailable() {
        CameraCharacteristics chars = field16("currentChars", CameraCharacteristics.class);
        if (chars == null) return false;
        return chars.get(CameraCharacteristics.FLASH_INFO_AVAILABLE);
    }

    private void persistCurrentSettings() {
        if (prefs == null || !prefs.getBoolean(K_PRESERVE, true)) return;
        Object sizeObj = object16("selectedSize");
        boolean fourK = sizeObj instanceof Size && ((Size) sizeObj).getWidth() >= 3800;
        prefs.edit()
                .putString("photo_ratio", string16("photoRatio", "4:3"))
                .putBoolean("photo_max", bool16("maxPhotoMode"))
                .putInt("photo_timer", int16("photoTimerSeconds", 0))
                .putBoolean("grid", bool16("gridEnabled"))
                .putBoolean("manual_focus", bool16("manualMode"))
                .putBoolean("flash", bool16("flashEnabled"))
                .putBoolean("video_stabilization", bool16("videoStabilizationEnabled"))
                .putBoolean("video_4k", fourK)
                .putInt("video_fps", int16("selectedFps", 30))
                .apply();
    }

    private void clearStoredCameraValues() {
        prefs.edit()
                .remove("photo_ratio").remove("photo_max").remove("photo_timer")
                .remove("grid").remove("manual_focus").remove("flash")
                .remove("video_stabilization").remove("video_4k").remove("video_fps")
                .apply();
    }

    @SuppressWarnings("unchecked")
    private <T> T exactField(Class<?> owner, String name, Class<T> type) {
        try {
            Field f = owner.getDeclaredField(name);
            f.setAccessible(true);
            Object value = f.get(this);
            return value == null ? null : (T) value;
        } catch (Exception e) {
            return null;
        }
    }

    private Boolean exactBoolean(Class<?> owner, String name) {
        try {
            Field f = owner.getDeclaredField(name);
            f.setAccessible(true);
            return f.getBoolean(this);
        } catch (Exception e) {
            return false;
        }
    }

    @SuppressWarnings("unchecked")
    private <T> T field16(String name, Class<T> type) {
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

    private boolean bool16(String name) {
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

    private int int16(String name, int fallback) {
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

    private long long16(String name, long fallback) {
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

    private float float16(String name, float fallback) {
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

    private String string16(String name, String fallback) {
        Object o = object16(name);
        return o instanceof String ? (String) o : fallback;
    }

    private Object object16(String name) {
        Class<?> c = getClass();
        while (c != null) {
            try {
                Field f = c.getDeclaredField(name);
                f.setAccessible(true);
                return f.get(this);
            } catch (NoSuchFieldException e) { c = c.getSuperclass(); }
            catch (Exception e) { return null; }
        }
        return null;
    }

    private void setBoolean16(String name, boolean value) {
        Class<?> c = getClass();
        while (c != null) {
            try {
                Field f = c.getDeclaredField(name);
                f.setAccessible(true);
                f.setBoolean(this, value);
                return;
            } catch (NoSuchFieldException e) { c = c.getSuperclass(); }
            catch (Exception e) { return; }
        }
    }

    private void setInt16(String name, int value) {
        Class<?> c = getClass();
        while (c != null) {
            try {
                Field f = c.getDeclaredField(name);
                f.setAccessible(true);
                f.setInt(this, value);
                return;
            } catch (NoSuchFieldException e) { c = c.getSuperclass(); }
            catch (Exception e) { return; }
        }
    }

    private void setLong16(String name, long value) {
        Class<?> c = getClass();
        while (c != null) {
            try {
                Field f = c.getDeclaredField(name);
                f.setAccessible(true);
                f.setLong(this, value);
                return;
            } catch (NoSuchFieldException e) { c = c.getSuperclass(); }
            catch (Exception e) { return; }
        }
    }

    private void setObject16(String name, Object value) {
        Class<?> c = getClass();
        while (c != null) {
            try {
                Field f = c.getDeclaredField(name);
                f.setAccessible(true);
                f.set(this, value);
                return;
            } catch (NoSuchFieldException e) { c = c.getSuperclass(); }
            catch (Exception e) { return; }
        }
    }

    private Object invoke16(String name, Class<?>[] types, Object... args) {
        Class<?> c = getClass();
        while (c != null) {
            try {
                Method m = c.getDeclaredMethod(name, types);
                m.setAccessible(true);
                return m.invoke(this, args);
            } catch (NoSuchMethodException e) { c = c.getSuperclass(); }
            catch (Exception e) { return null; }
        }
        return null;
    }
}
