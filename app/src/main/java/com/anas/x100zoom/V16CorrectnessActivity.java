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
 * V16 correctness pass based on real vivo X100 tests.
 *
 * - Real bounded photo preview viewports for 1:1 / 4:3 / 16:9.
 * - Full is edge-to-edge; outside bounded ratios is real black, not a translucent mask.
 * - Legacy 4K/FPS badge and V12 ratio-frame artifacts are removed.
 * - Closed settings sheet is fully detached so its shadow cannot remain.
 * - Optional persistent camera settings.
 * - Constant FPS is validated against Camera2 stream min-frame-duration.
 * - FPS is supplied at capture-session creation through SessionConfiguration.
 * - Final encoded MP4 average FPS is measured from sample timestamps after saving.
 */
public class V16CorrectnessActivity extends V15VideoUiActivity {
    private static final int ACCENT = 0xFFFFD129;
    private static final int PANEL = 0xFF050505;
    private static final int TILE = 0xFF292929;
    private static final Size PREVIEW = new Size(1920, 1080);
    private static final String PREFS = "x100_camera_prefs";
    private static final String PRESERVE = "preserve_settings";

    private final Handler ui16 = new Handler(Looper.getMainLooper());
    private SharedPreferences prefs16;

    private boolean installed16;
    private boolean lastSettingsOpen16;
    private boolean lastPhoto16;
    private String lastRatio16 = "";
    private float lastZoom16 = -1f;
    private long lastSavePrefsMs;
    private long lastModeCheckMs;
    private boolean warnedFallback16;

    private FrameLayout root16;
    private FrameLayout top16;
    private FrameLayout bottom16;
    private FrameLayout settings16;
    private LinearLayout videoPanel16;
    private TextureView preview16;
    private View transition16;
    private View grid16;
    private View ratioMask16;
    private TextView preserve16;
    private TextView fpsStatus16;

    private TextView v1080;
    private TextView v4k;
    private TextView v30;
    private TextView v60;
    private TextView vGrid;
    private TextView vFocus;
    private TextView vStab;
    private TextView vFlash;

    private long sensorStartNs;
    private int sensorFrames;
    private volatile float measuredSensorFps;
    private volatile float lastEncodedFps;

    private final CameraCaptureSession.CaptureCallback sensorFpsCallback =
            new CameraCaptureSession.CaptureCallback() {
        @Override public void onCaptureCompleted(CameraCaptureSession session,
                                                  CaptureRequest request,
                                                  TotalCaptureResult result) {
            Long timestamp = result.get(CaptureResult.SENSOR_TIMESTAMP);
            if (timestamp == null || timestamp <= 0L) return;
            if (sensorStartNs == 0L) {
                sensorStartNs = timestamp;
                sensorFrames = 1;
                return;
            }
            sensorFrames++;
            long span = timestamp - sensorStartNs;
            if (span >= 900_000_000L && sensorFrames > 2) {
                measuredSensorFps = (sensorFrames - 1) * 1_000_000_000f / span;
                sensorStartNs = timestamp;
                sensorFrames = 1;
            }
        }
    };

    private final Runnable watcher16 = new Runnable() {
        @Override public void run() {
            removeLegacyArtifacts();
            if (!installed16) {
                tryInstall16();
            } else {
                syncSettings16();
                syncPhotoViewport16();
                syncVideoSettings16();

                long now = android.os.SystemClock.elapsedRealtime();
                if (now - lastSavePrefsMs > 500L) {
                    lastSavePrefsMs = now;
                    savePreferences16();
                }
                if (now - lastModeCheckMs > 650L) {
                    lastModeCheckMs = now;
                    validateCurrentVideoMode16();
                    if (!bool16("photoMode")) invoke16("applyVideoStabilization", new Class[]{});
                }
            }
            ui16.postDelayed(this, 60L);
        }
    };

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        prefs16 = getSharedPreferences(PREFS, MODE_PRIVATE);
        restorePreferences16();
        ui16.postDelayed(watcher16, 30L);
    }

    @Override protected void onDestroy() {
        savePreferences16();
        ui16.removeCallbacksAndMessages(null);
        super.onDestroy();
    }

    private int dp16(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private GradientDrawable rounded16(int color, int radius) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(color);
        d.setCornerRadius(dp16(radius));
        return d;
    }

    /** Exact-class lookup avoids the duplicate modeBadge field names in older layers. */
    @SuppressWarnings("unchecked")
    private <T> T exactField16(Class<?> owner, String name, Class<T> type) {
        try {
            Field f = owner.getDeclaredField(name);
            f.setAccessible(true);
            Object value = f.get(this);
            return value == null ? null : (T) value;
        } catch (Exception e) {
            return null;
        }
    }

    private Boolean exactBoolean16(Class<?> owner, String name) {
        try {
            Field f = owner.getDeclaredField(name);
            f.setAccessible(true);
            return f.getBoolean(this);
        } catch (Exception e) {
            return false;
        }
    }

    private void removeLegacyArtifacts() {
        View mainBadge = exactField16(MainActivity.class, "modeBadge", View.class);
        if (mainBadge != null) {
            mainBadge.setVisibility(View.GONE);
            mainBadge.setAlpha(0f);
            if (mainBadge.getParent() instanceof ViewGroup) {
                try { ((ViewGroup) mainBadge.getParent()).removeView(mainBadge); } catch (Exception ignored) {}
            }
        }

        View oldTop = exactField16(V12CameraActivity.class, "photoTopRow", View.class);
        if (oldTop != null) oldTop.setVisibility(View.GONE);
        ratioMask16 = exactField16(V12CameraActivity.class, "ratioFrame", View.class);
        if (ratioMask16 != null) {
            ratioMask16.setVisibility(View.GONE);
            ratioMask16.setAlpha(0f);
        }
    }

    private void tryInstall16() {
        if (!Boolean.TRUE.equals(exactBoolean16(V15VideoUiActivity.class, "installed15"))) return;

        root16 = field16("cameraRoot", FrameLayout.class);
        top16 = field16("topChrome", FrameLayout.class);
        bottom16 = field16("bottomChrome", FrameLayout.class);
        settings16 = field16("settingsSheet", FrameLayout.class);
        videoPanel16 = exactField16(V15VideoUiActivity.class, "videoPanel", LinearLayout.class);
        preview16 = exactField16(MainActivity.class, "textureView", TextureView.class);
        transition16 = exactField16(MainActivity.class, "transitionOverlay", View.class);
        grid16 = exactField16(CameraChromeActivity.class, "gridOverlay", View.class);

        if (root16 == null || top16 == null || bottom16 == null ||
                settings16 == null || videoPanel16 == null || preview16 == null) return;

        // V12 is obsolete once the X300/V16 UI is active; its repeated sync caused ghosts.
        Handler oldV12 = exactField16(V12CameraActivity.class, "v12Ui", Handler.class);
        if (oldV12 != null) oldV12.removeCallbacksAndMessages(null);

        // V16 owns video-mode support state, including stream timing, not just AE ranges.
        Handler oldV15 = exactField16(V15VideoUiActivity.class, "v15", Handler.class);
        if (oldV15 != null) oldV15.removeCallbacksAndMessages(null);

        v1080 = exactField16(V15VideoUiActivity.class, "video1080", TextView.class);
        v4k = exactField16(V15VideoUiActivity.class, "video4k", TextView.class);
        v30 = exactField16(V15VideoUiActivity.class, "fps30", TextView.class);
        v60 = exactField16(V15VideoUiActivity.class, "fps60", TextView.class);
        vGrid = exactField16(V15VideoUiActivity.class, "videoGrid", TextView.class);
        vFocus = exactField16(V15VideoUiActivity.class, "videoFocus", TextView.class);
        vStab = exactField16(V15VideoUiActivity.class, "videoStab", TextView.class);
        vFlash = exactField16(V15VideoUiActivity.class, "videoFlash", TextView.class);

        if (v1080 != null) v1080.setOnClickListener(v -> selectResolution16(false));
        if (v4k != null) v4k.setOnClickListener(v -> selectResolution16(true));
        if (v30 != null) v30.setOnClickListener(v -> selectFps16(30));
        if (v60 != null) v60.setOnClickListener(v -> selectFps16(60));

        installPreserveToggle16();
        installEncodedFpsStatus16();
        replaceShutter16();
        restorePreferences16();
        applyRestoredVisualState16();

        lastPhoto16 = bool16("photoMode");
        lastSettingsOpen16 = bool16("settingsOpen");
        lastRatio16 = string16("photoRatio", "4:3");
        installed16 = true;
        syncPhotoViewport16();
        syncVideoSettings16();
    }

    private void installPreserveToggle16() {
        preserve16 = new TextView(this);
        preserve16.setGravity(Gravity.CENTER);
        preserve16.setTextSize(10.5f);
        preserve16.setTypeface(null, android.graphics.Typeface.BOLD);
        preserve16.setOnClickListener(v -> {
            boolean enabled = !prefs16.getBoolean(PRESERVE, true);
            prefs16.edit().putBoolean(PRESERVE, enabled).apply();
            if (!enabled) clearStoredCameraValues16();
            updatePreserveToggle16();
            Toast.makeText(this,
                    enabled ? "Settings will be preserved." : "Settings will reset on later launches.",
                    Toast.LENGTH_SHORT).show();
        });
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(dp16(148), dp16(40));
        lp.gravity = Gravity.TOP | Gravity.CENTER_HORIZONTAL;
        lp.topMargin = dp16(14);
        settings16.addView(preserve16, lp);
        updatePreserveToggle16();
    }

    private void updatePreserveToggle16() {
        if (preserve16 == null) return;
        boolean enabled = prefs16.getBoolean(PRESERVE, true);
        preserve16.setText(enabled ? "Preserve settings  ON" : "Preserve settings  OFF");
        preserve16.setTextColor(enabled ? Color.BLACK : Color.WHITE);
        preserve16.setBackground(rounded16(enabled ? ACCENT : TILE, 11));
    }

    private void installEncodedFpsStatus16() {
        fpsStatus16 = new TextView(this);
        fpsStatus16.setText("Last encoded file: — fps");
        fpsStatus16.setTextSize(10f);
        fpsStatus16.setTextColor(0xFF999999);
        fpsStatus16.setGravity(Gravity.CENTER);
        videoPanel16.addView(fpsStatus16, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp16(26)));
    }

    private void syncSettings16() {
        boolean open = bool16("settingsOpen");
        boolean photo = bool16("photoMode");

        if (settings16 != null) {
            if (open) {
                settings16.setBackgroundColor(PANEL);
                settings16.setVisibility(View.VISIBLE);
            } else if (lastSettingsOpen16) {
                ui16.postDelayed(this::forceSettingsGone16, 220L);
            }
        }

        if (videoPanel16 != null) videoPanel16.setVisibility(open && !photo ? View.VISIBLE : View.GONE);
        LinearLayout basePhoto = findBaseSettingsContent16();
        if (basePhoto != null) basePhoto.setVisibility(open && photo ? View.VISIBLE : View.GONE);
        if (preserve16 != null) preserve16.setVisibility(open ? View.VISIBLE : View.GONE);

        lastSettingsOpen16 = open;
    }

    private void forceSettingsGone16() {
        if (bool16("settingsOpen")) return;
        if (settings16 != null) {
            settings16.animate().cancel();
            settings16.setAlpha(0f);
            settings16.setScaleX(1f);
            settings16.setScaleY(1f);
            settings16.setTranslationY(-dp16(24));
            settings16.setVisibility(View.GONE);
        }
        View dismiss = exactField16(V14PolishActivity.class, "settingsDismissLayer", View.class);
        if (dismiss != null) dismiss.setVisibility(View.GONE);
        if (videoPanel16 != null) videoPanel16.setVisibility(View.GONE);
    }

    private LinearLayout findBaseSettingsContent16() {
        if (settings16 == null) return null;
        for (int i = 0; i < settings16.getChildCount(); i++) {
            View child = settings16.getChildAt(i);
            if (child instanceof LinearLayout && child != videoPanel16) {
                LinearLayout layout = (LinearLayout) child;
                if (layout.getOrientation() == LinearLayout.VERTICAL) return layout;
            }
        }
        return null;
    }

    /** Real preview geometry: the TextureView itself has the selected aspect ratio. */
    private void syncPhotoViewport16() {
        if (root16 == null || preview16 == null || root16.getWidth() <= 0 || root16.getHeight() <= 0) return;
        boolean photo = bool16("photoMode");
        String ratio = string16("photoRatio", "4:3");
        float zoom = float16("requestedUiZoom", 1f);

        if (photo != lastPhoto16 || !ratio.equals(lastRatio16)) {
            lastPhoto16 = photo;
            lastRatio16 = ratio;
            applyViewportGeometry16(photo, ratio);
        }

        if (photo) {
            if (Math.abs(zoom - lastZoom16) > 0.002f || true) {
                lastZoom16 = zoom;
                applyPhotoTransform16(zoom);
            }
        }
    }

    private void applyViewportGeometry16(boolean photo, String ratio) {
        int rootW = root16.getWidth();
        int rootH = root16.getHeight();
        int width = rootW;
        int height = rootH;
        int top = 0;

        if (photo && !"Full".equalsIgnoreCase(ratio)) {
            float wh;
            if ("1:1".equals(ratio)) wh = 1f;
            else if ("16:9".equals(ratio)) wh = 9f / 16f;
            else wh = 3f / 4f;

            height = Math.round(width / wh);
            int usableTop = top16.getHeight();
            int usableBottom = rootH - bottom16.getHeight();
            int usableHeight = Math.max(1, usableBottom - usableTop);
            top = height <= usableHeight
                    ? usableTop + (usableHeight - height) / 2
                    : Math.max(0, (rootH - height) / 2);
        }

        setViewportLp16(preview16, width, height, top);
        setViewportLp16(transition16, width, height, top);
        setViewportLp16(grid16, width, height, top);
        root16.setBackgroundColor(Color.BLACK);
        if (ratioMask16 != null) ratioMask16.setVisibility(View.GONE);
    }

    private void setViewportLp16(View view, int width, int height, int top) {
        if (view == null) return;
        FrameLayout.LayoutParams lp = view.getLayoutParams() instanceof FrameLayout.LayoutParams
                ? (FrameLayout.LayoutParams) view.getLayoutParams()
                : new FrameLayout.LayoutParams(width, height);
        lp.width = width;
        lp.height = height;
        lp.gravity = Gravity.TOP | Gravity.CENTER_HORIZONTAL;
        lp.topMargin = top;
        lp.bottomMargin = 0;
        view.setLayoutParams(lp);
    }

    /** Maintain source aspect instead of stretching a 16:9 preview into 4:3 or 1:1. */
    private void applyPhotoTransform16(float requestedZoom) {
        if (preview16.getWidth() <= 0 || preview16.getHeight() <= 0) return;
        float vw = preview16.getWidth();
        float vh = preview16.getHeight();

        // Existing preview buffer is 1920x1080 landscape -> 1080x1920 in portrait.
        float sx = vw / 1080f;
        float sy = vh / 1920f;
        float fill = Math.max(sx, sy);
        float correctX = fill / Math.max(0.0001f, sx);
        float correctY = fill / Math.max(0.0001f, sy);

        boolean tele = bool16("activeTele");
        float hardwareMax = tele
                ? 3f * Math.max(1f, float16("teleMaxZoom", 10f))
                : Math.max(1f, float16("logicalMaxZoom", 10f));
        float extra = Math.max(1f, requestedZoom / hardwareMax);

        Matrix m = new Matrix();
        m.setScale(correctX * extra, correctY * extra, vw / 2f, vh / 2f);
        preview16.setTransform(m);
    }

    private void replaceShutter16() {
        Button record = exactField16(MainActivity.class, "recordButton", Button.class);
        if (record == null) return;
        record.setOnClickListener(v -> {
            if (bool16("photoMode")) {
                invoke16("capturePhoto", new Class[]{});
            } else if (bool16("recording") || bool16("recordingStarting")) {
                stopVerifiedRecording16();
            } else {
                startVerifiedRecording16();
            }
        });
    }

    /** Create the RECORD session with FPS as a session parameter, not only a later request. */
    private void startVerifiedRecording16() {
        CameraDevice camera = field16("cameraDevice", CameraDevice.class);
        CameraCharacteristics chars = field16("currentChars", CameraCharacteristics.class);
        Handler cameraHandler = field16("cameraHandler", Handler.class);
        Object selected = object16("selectedSize");
        Size size = selected instanceof Size ? (Size) selected : new Size(3840, 2160);
        int fps = int16("selectedFps", 30);

        if (camera == null || chars == null || cameraHandler == null || preview16 == null || !preview16.isAvailable()) {
            toast16("Camera is not ready yet.");
            return;
        }
        if (!supportsConstantMode16(chars, size, fps)) {
            toast16(modeFailure16(chars, size, fps));
            return;
        }

        try {
            invoke16("prepareRecorder", new Class[]{});
            MediaRecorder recorder = field16("recorder", MediaRecorder.class);
            if (recorder == null) throw new IllegalStateException("Recorder preparation failed");

            setBoolean16("recordingStarting", true);
            invoke16("closeSessionOnly", new Class[]{});

            SurfaceTexture surfaceTexture = preview16.getSurfaceTexture();
            if (surfaceTexture == null) throw new IllegalStateException("Preview surface unavailable");
            surfaceTexture.setDefaultBufferSize(PREVIEW.getWidth(), PREVIEW.getHeight());
            Surface previewSurface = new Surface(surfaceTexture);
            Surface recordSurface = recorder.getSurface();

            CaptureRequest.Builder builder = camera.createCaptureRequest(CameraDevice.TEMPLATE_RECORD);
            builder.addTarget(previewSurface);
            builder.addTarget(recordSurface);
            invoke16("configureCommonRequest", new Class[]{CaptureRequest.Builder.class}, builder);

            Range<Integer> fixed = exactRange16(chars, fps);
            if (fixed == null) throw new IllegalStateException("No fixed " + fps + " fps range");
            builder.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, fixed);

            List<OutputConfiguration> outputs = new ArrayList<>();
            outputs.add(new OutputConfiguration(previewSurface));
            outputs.add(new OutputConfiguration(recordSurface));

            SessionConfiguration sessionConfig = new SessionConfiguration(
                    SessionConfiguration.SESSION_REGULAR,
                    outputs,
                    command -> cameraHandler.post(command),
                    new CameraCaptureSession.StateCallback() {
                        @Override public void onConfigured(CameraCaptureSession session) {
                            try {
                                setObject16("captureSession", session);
                                setObject16("repeatingBuilder", builder);
                                sensorStartNs = 0L;
                                sensorFrames = 0;
                                measuredSensorFps = 0f;
                                session.setRepeatingRequest(builder.build(), sensorFpsCallback, cameraHandler);
                                recorder.start();
                                setBoolean16("recordingStarting", false);
                                setBoolean16("recording", true);
                                setLong16("recordStartedAtMs", System.currentTimeMillis());
                            } catch (Exception e) {
                                failRecordingStart16("Recording start failed: " + e.getMessage());
                            }
                        }

                        @Override public void onConfigureFailed(CameraCaptureSession session) {
                            failRecordingStart16("Vivo HAL rejected this constant-FPS session.");
                        }
                    });

            sessionConfig.setSessionParameters(builder.build());
            camera.createCaptureSession(sessionConfig);
        } catch (Exception e) {
            failRecordingStart16("Recording setup failed: " + e.getMessage());
        }
    }

    private void failRecordingStart16(String message) {
        setBoolean16("recordingStarting", false);
        setBoolean16("recording", false);
        invoke16("safeResetRecorder", new Class[]{});
        Handler handler = field16("cameraHandler", Handler.class);
        if (handler != null) handler.post(() -> invoke16("startPreviewSession", new Class[]{}));
        toast16(message);
    }

    private void stopVerifiedRecording16() {
        Uri saved = field16("outputUri", Uri.class);
        float sensor = measuredSensorFps;
        invoke16("stopRecording", new Class[]{});
        if (saved != null) verifyEncodedFps16(saved, sensor);
    }

    private void verifyEncodedFps16(Uri uri, float sensorFps) {
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
                    long first = -1L;
                    long last = -1L;
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

            float result = encoded;
            lastEncodedFps = result;
            runOnUiThread(() -> {
                if (fpsStatus16 != null && result > 0f) {
                    fpsStatus16.setText(String.format(Locale.US, "Last encoded file: %.1f fps", result));
                }
                if (result > 0f) {
                    Toast.makeText(this,
                            String.format(Locale.US, "Saved video: %.1f fps encoded • camera %.1f fps", result, sensorFps),
                            Toast.LENGTH_LONG).show();
                }
            });
        }, "X100FpsVerifier").start();
    }

    /**
     * Exact AE FPS is not enough: every configured output stream must also have a
     * minimum frame duration fast enough to physically deliver that FPS.
     */
    private boolean supportsConstantMode16(CameraCharacteristics chars, Size size, int fps) {
        if (chars == null || size == null) return false;
        StreamConfigurationMap map = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
        if (map == null || !containsSize16(map.getOutputSizes(MediaRecorder.class), size)) return false;
        if (exactRange16(chars, fps) == null) return false;

        long recordMin = minDuration16(map, MediaRecorder.class, size);
        long previewMin = containsSize16(map.getOutputSizes(SurfaceTexture.class), PREVIEW)
                ? minDuration16(map, SurfaceTexture.class, PREVIEW) : 0L;
        long worst = Math.max(recordMin, previewMin);
        long needed = Math.round(1_000_000_000d / fps);
        return worst <= 0L || worst <= needed + 750_000L;
    }

    private String modeFailure16(CameraCharacteristics chars, Size size, int fps) {
        StreamConfigurationMap map = chars == null ? null
                : chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
        if (map == null || !containsSize16(map.getOutputSizes(MediaRecorder.class), size)) {
            return "This active camera path does not expose that recording resolution.";
        }
        if (exactRange16(chars, fps) == null) {
            return "This active camera path does not expose fixed " + fps + " fps.";
        }
        long min = minDuration16(map, MediaRecorder.class, size);
        if (min > 0L) {
            float max = 1_000_000_000f / min;
            return String.format(Locale.US,
                    "This Camera2 stream is physically limited to about %.1f fps at this resolution.", max);
        }
        return "This Camera2 stream cannot guarantee " + fps + " fps.";
    }

    private long minDuration16(StreamConfigurationMap map, Class<?> outputClass, Size size) {
        try { return map.getOutputMinFrameDuration(outputClass, size); }
        catch (Exception e) { return 0L; }
    }

    private Range<Integer> exactRange16(CameraCharacteristics chars, int fps) {
        if (chars == null) return null;
        Range<Integer>[] ranges = chars.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES);
        if (ranges == null) return null;
        for (Range<Integer> range : ranges) {
            if (range.getLower() == fps && range.getUpper() == fps) return range;
        }
        return null;
    }

    private boolean containsSize16(Size[] values, Size wanted) {
        if (values == null) return false;
        for (Size value : values) if (wanted.equals(value)) return true;
        return false;
    }

    private void validateCurrentVideoMode16() {
        if (bool16("photoMode") || bool16("recording") || bool16("recordingStarting")) return;
        CameraCharacteristics chars = field16("currentChars", CameraCharacteristics.class);
        Object selected = object16("selectedSize");
        if (!(selected instanceof Size) || chars == null) return;
        Size size = (Size) selected;
        int fps = int16("selectedFps", 30);

        if (supportsConstantMode16(chars, size, fps)) {
            warnedFallback16 = false;
            return;
        }
        if (fps == 60 && supportsConstantMode16(chars, size, 30)) {
            setInt16("selectedFps", 30);
            if (!warnedFallback16) {
                warnedFallback16 = true;
                toast16("60 fps is not physically exposed for this stream. Switched to verified 30 fps.");
            }
        }
    }

    private void selectResolution16(boolean fourK) {
        if (bool16("recording") || bool16("recordingStarting")) return;
        CameraCharacteristics chars = field16("currentChars", CameraCharacteristics.class);
        Size wanted = fourK ? new Size(3840, 2160) : new Size(1920, 1080);
        int fps = int16("selectedFps", 30);
        if (!supportsConstantMode16(chars, wanted, fps)) {
            if (supportsConstantMode16(chars, wanted, 30)) fps = 30;
            else if (supportsConstantMode16(chars, wanted, 60)) fps = 60;
            else {
                toast16("That resolution is not exposed at a verified constant FPS.");
                return;
            }
        }
        applyVideoMode16(wanted, fps);
    }

    private void selectFps16(int fps) {
        if (bool16("recording") || bool16("recordingStarting")) return;
        CameraCharacteristics chars = field16("currentChars", CameraCharacteristics.class);
        Object selected = object16("selectedSize");
        Size size = selected instanceof Size ? (Size) selected : new Size(3840, 2160);
        if (!supportsConstantMode16(chars, size, fps)) {
            toast16(modeFailure16(chars, size, fps));
            return;
        }
        applyVideoMode16(size, fps);
    }

    private void applyVideoMode16(Size size, int fps) {
        setObject16("selectedSize", size);
        setInt16("selectedFps", fps);
        Handler handler = field16("cameraHandler", Handler.class);
        if (handler != null) handler.post(() -> invoke16("startPreviewSession", new Class[]{}));
        savePreferences16();
    }

    private void syncVideoSettings16() {
        boolean photo = bool16("photoMode");
        boolean settingsOpen = bool16("settingsOpen");
        if (videoPanel16 != null) videoPanel16.setVisibility(settingsOpen && !photo ? View.VISIBLE : View.GONE);
        if (photo || v1080 == null) return;

        CameraCharacteristics chars = field16("currentChars", CameraCharacteristics.class);
        Object selected = object16("selectedSize");
        Size size = selected instanceof Size ? (Size) selected : new Size(3840, 2160);
        int fps = int16("selectedFps", 30);
        boolean fourK = size.getWidth() >= 3800;

        styleChoice16(v1080, !fourK, supportsConstantMode16(chars, new Size(1920, 1080), fps));
        styleChoice16(v4k, fourK, supportsConstantMode16(chars, new Size(3840, 2160), fps));
        styleChoice16(v30, fps == 30, supportsConstantMode16(chars, size, 30));
        styleChoice16(v60, fps == 60, supportsConstantMode16(chars, size, 60));

        boolean gridEnabled = bool16("gridEnabled");
        boolean manual = bool16("manualMode");
        boolean flash = bool16("flashEnabled");
        boolean stabilization = bool16("videoStabilizationEnabled");
        styleTile16(vGrid, gridEnabled, true);
        if (vFocus != null) vFocus.setText(manual ? "Focus\nManual" : "Focus\nAuto");
        styleTile16(vFocus, manual, true);
        styleTile16(vFlash, flash, true);
        styleTile16(vStab, stabilization, true);

        if (fpsStatus16 != null && lastEncodedFps > 0f) {
            fpsStatus16.setText(String.format(Locale.US, "Last encoded file: %.1f fps", lastEncodedFps));
        }
    }

    private void styleChoice16(TextView view, boolean selected, boolean enabled) {
        if (view == null) return;
        view.setEnabled(enabled);
        view.setAlpha(enabled ? 1f : 0.32f);
        view.setTextColor(selected && enabled ? Color.BLACK : (enabled ? Color.WHITE : 0xFF777777));
        view.setBackground(selected && enabled ? rounded16(ACCENT, 8) : null);
    }

    private void styleTile16(TextView view, boolean active, boolean enabled) {
        if (view == null) return;
        view.setEnabled(enabled);
        view.setAlpha(enabled ? 1f : 0.40f);
        view.setTextColor(active && enabled ? Color.BLACK : Color.WHITE);
        view.setBackground(rounded16(active && enabled ? ACCENT : TILE, 10));
    }

    private void restorePreferences16() {
        if (prefs16 == null || !prefs16.getBoolean(PRESERVE, true)) return;
        setObject16("photoRatio", prefs16.getString("photo_ratio", "4:3"));
        setBoolean16("maxPhotoMode", prefs16.getBoolean("photo_max", false));
        setInt16("photoTimerSeconds", prefs16.getInt("photo_timer", 0));
        setBoolean16("gridEnabled", prefs16.getBoolean("grid", false));
        setBoolean16("manualMode", prefs16.getBoolean("manual_focus", false));
        setBoolean16("flashEnabled", prefs16.getBoolean("flash", false));
        setBoolean16("videoStabilizationEnabled", prefs16.getBoolean("video_stabilization", true));
        setObject16("selectedSize", prefs16.getBoolean("video_4k", true)
                ? new Size(3840, 2160) : new Size(1920, 1080));
        setInt16("selectedFps", prefs16.getInt("video_fps", 30));
    }

    private void applyRestoredVisualState16() {
        boolean gridEnabled = bool16("gridEnabled");
        View gridView = exactField16(CameraChromeActivity.class, "gridOverlay", View.class);
        if (gridView != null) gridView.setVisibility(gridEnabled ? View.VISIBLE : View.GONE);
        invoke16("updateGridButton", new Class[]{});
        updatePreserveToggle16();
    }

    private void savePreferences16() {
        if (prefs16 == null || !prefs16.getBoolean(PRESERVE, true)) return;
        Object selected = object16("selectedSize");
        boolean fourK = selected instanceof Size && ((Size) selected).getWidth() >= 3800;
        prefs16.edit()
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

    private void clearStoredCameraValues16() {
        prefs16.edit()
                .remove("photo_ratio").remove("photo_max").remove("photo_timer")
                .remove("grid").remove("manual_focus").remove("flash")
                .remove("video_stabilization").remove("video_4k").remove("video_fps")
                .apply();
    }

    private void toast16(String text) {
        runOnUiThread(() -> Toast.makeText(this, text, Toast.LENGTH_LONG).show());
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

    private Object object16(String name) {
        return field16(name, Object.class);
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
        Object value = object16(name);
        return value instanceof String ? (String) value : fallback;
    }

    private void setBoolean16(String name, boolean value) {
        setPrimitive16(name, value, 0, 0L, null, 1);
    }

    private void setInt16(String name, int value) {
        setPrimitive16(name, false, value, 0L, null, 2);
    }

    private void setLong16(String name, long value) {
        setPrimitive16(name, false, 0, value, null, 3);
    }

    private void setObject16(String name, Object value) {
        setPrimitive16(name, false, 0, 0L, value, 4);
    }

    private void setPrimitive16(String name, boolean b, int i, long l, Object object, int kind) {
        Class<?> c = getClass();
        while (c != null) {
            try {
                Field f = c.getDeclaredField(name);
                f.setAccessible(true);
                if (kind == 1) f.setBoolean(this, b);
                else if (kind == 2) f.setInt(this, i);
                else if (kind == 3) f.setLong(this, l);
                else f.set(this, object);
                return;
            } catch (NoSuchFieldException e) { c = c.getSuperclass(); }
            catch (Exception e) { return; }
        }
    }

    private Object invoke16(String name, Class<?>[] parameterTypes, Object... args) {
        Class<?> c = getClass();
        while (c != null) {
            try {
                Method m = c.getDeclaredMethod(name, parameterTypes);
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
