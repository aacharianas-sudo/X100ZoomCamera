package com.anas.x100zoom;

import android.Manifest;
import android.app.Activity;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.SurfaceTexture;
import android.graphics.drawable.GradientDrawable;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CameraMetadata;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.media.MediaCodecInfo;
import android.media.MediaCodecList;
import android.media.MediaRecorder;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.provider.MediaStore;
import android.util.Range;
import android.util.Size;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.Surface;
import android.view.TextureView;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.io.FileDescriptor;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public class MainActivity extends Activity {
    private static final int REQ_PERMS = 77;
    private static final Size UHD = new Size(3840, 2160);
    private static final int TARGET_FPS = 60;
    private static final int VIDEO_BITRATE = 100_000_000;
    private static final float UI_MIN_ZOOM = 0.6f;
    private static final float UI_MAX_ZOOM = 30.0f;

    private TextureView textureView;
    private TextView zoomLiveView;
    private TextView infoView;
    private TextView timerView;
    private Button recordButton;
    private ZoomSliderView zoomSlider;

    private HandlerThread cameraThread;
    private Handler cameraHandler;
    private CameraManager cameraManager;
    private CameraDevice cameraDevice;
    private CameraCaptureSession captureSession;
    private CaptureRequest.Builder repeatingBuilder;

    private CameraCharacteristics logicalCharacteristics;
    private String logicalCameraId;
    private float logicalMinZoom = 1.0f;
    private float logicalMaxZoom = 10.0f;
    private float requestedUiZoom = 1.0f;
    private boolean supports4k60 = false;
    private boolean supportsHevc = false;
    private int sensorOrientation = 90;

    private MediaRecorder recorder;
    private boolean recording = false;
    private boolean recordingStarting = false;
    private Uri outputUri;
    private android.os.ParcelFileDescriptor outputPfd;
    private long recordStartedAtMs = 0L;

    private final Runnable applyZoomRunnable = this::applyZoomToRepeatingRequest;

    private final Runnable timerRunnable = new Runnable() {
        @Override public void run() {
            if (!recording) return;
            long elapsed = Math.max(0L, System.currentTimeMillis() - recordStartedAtMs) / 1000L;
            timerView.setText(String.format(Locale.US, "%02d:%02d", elapsed / 60L, elapsed % 60L));
            timerView.postDelayed(this, 500L);
        }
    };

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        getWindow().setStatusBarColor(Color.BLACK);
        cameraManager = (CameraManager) getSystemService(Context.CAMERA_SERVICE);
        buildUi();

        if (hasPermissions()) {
            startCameraThread();
            if (textureView.isAvailable()) openLogicalCamera();
        } else {
            requestPermissions(new String[]{Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO}, REQ_PERMS);
        }
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    private GradientDrawable rounded(int color, float radiusDp) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(color);
        d.setCornerRadius(dp((int) radiusDp));
        return d;
    }

    private GradientDrawable circle(int color) {
        GradientDrawable d = new GradientDrawable();
        d.setShape(GradientDrawable.OVAL);
        d.setColor(color);
        return d;
    }

    private void buildUi() {
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);
        root.setClipChildren(true);

        textureView = new TextureView(this);
        textureView.setSurfaceTextureListener(textureListener);
        FrameLayout.LayoutParams previewLp = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT);
        root.addView(textureView, previewLp);

        TextView modeBadge = new TextView(this);
        modeBadge.setText("4K  60");
        modeBadge.setTextColor(Color.WHITE);
        modeBadge.setTextSize(12f);
        modeBadge.setGravity(Gravity.CENTER);
        modeBadge.setTypeface(null, android.graphics.Typeface.BOLD);
        modeBadge.setPadding(dp(10), dp(5), dp(10), dp(5));
        modeBadge.setBackground(rounded(0x66000000, 10));
        FrameLayout.LayoutParams modeLp = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT);
        modeLp.gravity = Gravity.TOP | Gravity.START;
        modeLp.leftMargin = dp(18);
        modeLp.topMargin = dp(22);
        root.addView(modeBadge, modeLp);

        TextView settings = new TextView(this);
        settings.setText("⚙");
        settings.setTextColor(Color.WHITE);
        settings.setTextSize(25f);
        settings.setGravity(Gravity.CENTER);
        settings.setBackground(rounded(0x44000000, 20));
        FrameLayout.LayoutParams settingsLp = new FrameLayout.LayoutParams(dp(46), dp(46));
        settingsLp.gravity = Gravity.TOP | Gravity.END;
        settingsLp.rightMargin = dp(14);
        settingsLp.topMargin = dp(14);
        root.addView(settings, settingsLp);

        zoomLiveView = new TextView(this);
        zoomLiveView.setText("1X");
        zoomLiveView.setTextColor(Color.WHITE);
        zoomLiveView.setTextSize(28f);
        zoomLiveView.setTypeface(null, android.graphics.Typeface.BOLD);
        zoomLiveView.setGravity(Gravity.CENTER);
        zoomLiveView.setShadowLayer(5f, 0f, 1f, Color.BLACK);
        FrameLayout.LayoutParams zoomLiveLp = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT);
        zoomLiveLp.gravity = Gravity.TOP | Gravity.CENTER_HORIZONTAL;
        zoomLiveLp.topMargin = dp(22);
        root.addView(zoomLiveView, zoomLiveLp);

        infoView = new TextView(this);
        infoView.setTextColor(0xFFE6E6E6);
        infoView.setTextSize(11f);
        infoView.setGravity(Gravity.CENTER);
        infoView.setPadding(dp(10), dp(5), dp(10), dp(5));
        infoView.setBackground(rounded(0x77000000, 10));
        infoView.setVisibility(View.GONE);
        FrameLayout.LayoutParams infoLp = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT);
        infoLp.gravity = Gravity.TOP | Gravity.CENTER_HORIZONTAL;
        infoLp.topMargin = dp(64);
        root.addView(infoView, infoLp);

        LinearLayout controls = new LinearLayout(this);
        controls.setOrientation(LinearLayout.VERTICAL);
        controls.setGravity(Gravity.CENTER_HORIZONTAL);
        controls.setPadding(dp(14), dp(12), dp(14), dp(22));
        controls.setBackgroundColor(0x55000000);

        LinearLayout zoomControlRow = new LinearLayout(this);
        zoomControlRow.setGravity(Gravity.CENTER_VERTICAL);

        TextView minus = makeZoomEdgeButton("−");
        minus.setOnClickListener(v -> nudgeZoom(-1));
        zoomControlRow.addView(minus, new LinearLayout.LayoutParams(dp(48), dp(58)));

        zoomSlider = new ZoomSliderView(this);
        zoomSlider.setZoom(requestedUiZoom);
        zoomControlRow.addView(zoomSlider, new LinearLayout.LayoutParams(0, dp(64), 1f));

        TextView plus = makeZoomEdgeButton("+");
        plus.setOnClickListener(v -> nudgeZoom(1));
        zoomControlRow.addView(plus, new LinearLayout.LayoutParams(dp(48), dp(58)));

        controls.addView(zoomControlRow, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(66)));

        timerView = new TextView(this);
        timerView.setText("00:00");
        timerView.setTextColor(Color.WHITE);
        timerView.setTextSize(14f);
        timerView.setGravity(Gravity.CENTER);
        timerView.setVisibility(View.INVISIBLE);
        controls.addView(timerView, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(30)));

        FrameLayout recordWrap = new FrameLayout(this);
        LinearLayout.LayoutParams recordWrapLp = new LinearLayout.LayoutParams(dp(108), dp(108));
        recordWrapLp.topMargin = dp(2);
        controls.addView(recordWrap, recordWrapLp);

        View outer = new View(this);
        GradientDrawable outerShape = circle(Color.TRANSPARENT);
        outerShape.setStroke(dp(4), Color.WHITE);
        outer.setBackground(outerShape);
        FrameLayout.LayoutParams outerLp = new FrameLayout.LayoutParams(dp(90), dp(90));
        outerLp.gravity = Gravity.CENTER;
        recordWrap.addView(outer, outerLp);

        recordButton = new Button(this);
        recordButton.setText("");
        recordButton.setPadding(0, 0, 0, 0);
        recordButton.setBackground(circle(0xFFFF3B30));
        recordButton.setOnClickListener(v -> {
            if (recording || recordingStarting) stopRecording(); else startRecording();
        });
        FrameLayout.LayoutParams recordLp = new FrameLayout.LayoutParams(dp(72), dp(72));
        recordLp.gravity = Gravity.CENTER;
        recordWrap.addView(recordButton, recordLp);

        TextView videoLabel = new TextView(this);
        videoLabel.setText("VIDEO");
        videoLabel.setTextColor(0xFFFFD54F);
        videoLabel.setTextSize(13f);
        videoLabel.setTypeface(null, android.graphics.Typeface.BOLD);
        videoLabel.setGravity(Gravity.CENTER);
        controls.addView(videoLabel, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(24)));

        FrameLayout.LayoutParams controlsLp = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT);
        controlsLp.gravity = Gravity.BOTTOM;
        root.addView(controls, controlsLp);

        setContentView(root);
    }

    private TextView makeZoomEdgeButton(String text) {
        TextView v = new TextView(this);
        v.setText(text);
        v.setTextColor(Color.WHITE);
        v.setTextSize(34f);
        v.setGravity(Gravity.CENTER);
        v.setShadowLayer(4f, 0f, 1f, Color.BLACK);
        return v;
    }

    private void nudgeZoom(int direction) {
        float step = requestedUiZoom < 10f ? 0.1f : 0.5f;
        setDesiredZoom(requestedUiZoom + direction * step, true);
    }

    private String formatLiveZoom(float z) {
        if (Math.abs(z - Math.round(z)) < 0.001f) {
            return String.format(Locale.US, "%.0fX", z);
        }
        return String.format(Locale.US, "%.1fX", z);
    }

    private boolean hasPermissions() {
        return checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED &&
                checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED;
    }

    @Override public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ_PERMS && hasPermissions()) {
            startCameraThread();
            if (textureView.isAvailable()) openLogicalCamera();
        } else {
            toast("Camera and microphone permissions are required.");
        }
    }

    private void startCameraThread() {
        if (cameraThread != null) return;
        cameraThread = new HandlerThread("X100CameraV3");
        cameraThread.start();
        cameraHandler = new Handler(cameraThread.getLooper());
    }

    private final TextureView.SurfaceTextureListener textureListener = new TextureView.SurfaceTextureListener() {
        @Override public void onSurfaceTextureAvailable(SurfaceTexture surface, int width, int height) {
            if (hasPermissions()) {
                startCameraThread();
                openLogicalCamera();
            }
        }
        @Override public void onSurfaceTextureSizeChanged(SurfaceTexture surface, int width, int height) {}
        @Override public boolean onSurfaceTextureDestroyed(SurfaceTexture surface) { return true; }
        @Override public void onSurfaceTextureUpdated(SurfaceTexture surface) {}
    };

    private void openLogicalCamera() {
        if (cameraDevice != null || cameraHandler == null) return;
        try {
            logicalCameraId = chooseLogicalRearCamera();
            if (logicalCameraId == null) {
                toast("No suitable rear logical camera found.");
                return;
            }
            logicalCharacteristics = cameraManager.getCameraCharacteristics(logicalCameraId);
            Integer orient = logicalCharacteristics.get(CameraCharacteristics.SENSOR_ORIENTATION);
            if (orient != null) sensorOrientation = orient;
            inspectLogicalCapabilities();
            if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) return;
            cameraManager.openCamera(logicalCameraId, cameraStateCallback, cameraHandler);
        } catch (Exception e) {
            showError("Open camera: " + e.getMessage());
        }
    }

    private String chooseLogicalRearCamera() throws CameraAccessException {
        String bestId = null;
        float bestScore = -1f;
        for (String id : cameraManager.getCameraIdList()) {
            CameraCharacteristics c = cameraManager.getCameraCharacteristics(id);
            Integer facing = c.get(CameraCharacteristics.LENS_FACING);
            if (facing == null || facing != CameraCharacteristics.LENS_FACING_BACK) continue;
            float score = 0f;
            int[] caps = c.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES);
            if (caps != null) {
                for (int cap : caps) {
                    if (cap == CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_LOGICAL_MULTI_CAMERA) {
                        score += 1000f;
                    }
                }
            }
            StreamConfigurationMap map = c.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
            if (map != null && containsSize(map.getOutputSizes(MediaRecorder.class), UHD)) score += 500f;
            Range<Float> zr = c.get(CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE);
            if (zr != null) {
                score += zr.getUpper();
                if (zr.getLower() <= 0.6f) score += 100f;
            }
            if (score > bestScore) {
                bestScore = score;
                bestId = id;
            }
        }
        return bestId;
    }

    private void inspectLogicalCapabilities() {
        Range<Float> range = logicalCharacteristics.get(CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE);
        if (range != null) {
            logicalMinZoom = range.getLower();
            logicalMaxZoom = range.getUpper();
        } else {
            Float max = logicalCharacteristics.get(CameraCharacteristics.SCALER_AVAILABLE_MAX_DIGITAL_ZOOM);
            logicalMinZoom = 1f;
            logicalMaxZoom = max != null ? max : 10f;
        }
        supports4k60 = has4k60(logicalCharacteristics);
        supportsHevc = hasEncoder("video/hevc");
        runOnUiThread(() -> {
            if (requestedUiZoom > logicalMaxZoom) showPostCropInfo();
            else infoView.setVisibility(View.GONE);
        });
    }

    private boolean has4k60(CameraCharacteristics c) {
        StreamConfigurationMap map = c.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
        if (map == null || !containsSize(map.getOutputSizes(MediaRecorder.class), UHD)) return false;
        Range<Integer>[] fps = c.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES);
        if (fps != null) {
            for (Range<Integer> r : fps) {
                if (r.getLower() <= TARGET_FPS && r.getUpper() >= TARGET_FPS) return true;
            }
        }
        return true;
    }

    private boolean containsSize(Size[] sizes, Size wanted) {
        if (sizes == null) return false;
        for (Size s : sizes) if (s.equals(wanted)) return true;
        return false;
    }

    private boolean hasEncoder(String mime) {
        MediaCodecList list = new MediaCodecList(MediaCodecList.ALL_CODECS);
        for (MediaCodecInfo info : list.getCodecInfos()) {
            if (!info.isEncoder()) continue;
            for (String type : info.getSupportedTypes()) {
                if (type.equalsIgnoreCase(mime)) return true;
            }
        }
        return false;
    }

    private final CameraDevice.StateCallback cameraStateCallback = new CameraDevice.StateCallback() {
        @Override public void onOpened(CameraDevice camera) {
            cameraDevice = camera;
            startPreviewSession();
        }
        @Override public void onDisconnected(CameraDevice camera) {
            camera.close();
            cameraDevice = null;
        }
        @Override public void onError(CameraDevice camera, int error) {
            camera.close();
            cameraDevice = null;
            showError("Camera error " + error);
        }
    };

    private void startPreviewSession() {
        if (cameraDevice == null || !textureView.isAvailable()) return;
        try {
            closeSession();
            SurfaceTexture st = textureView.getSurfaceTexture();
            if (st == null) return;
            st.setDefaultBufferSize(1920, 1080);
            Surface preview = new Surface(st);
            repeatingBuilder = cameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
            repeatingBuilder.addTarget(preview);
            configureCommonRequest(repeatingBuilder, false);
            List<Surface> outputs = new ArrayList<>();
            outputs.add(preview);
            cameraDevice.createCaptureSession(outputs, sessionCallback(false), cameraHandler);
        } catch (Exception e) {
            showError("Preview setup: " + e.getMessage());
        }
    }

    private CameraCaptureSession.StateCallback sessionCallback(boolean startRecorderAfterConfigure) {
        return new CameraCaptureSession.StateCallback() {
            @Override public void onConfigured(CameraCaptureSession session) {
                if (cameraDevice == null) return;
                captureSession = session;
                try {
                    captureSession.setRepeatingRequest(repeatingBuilder.build(), null, cameraHandler);
                    if (startRecorderAfterConfigure) {
                        recorder.start();
                        recordingStarting = false;
                        recording = true;
                        recordStartedAtMs = System.currentTimeMillis();
                        runOnUiThread(() -> {
                            timerView.setVisibility(View.VISIBLE);
                            timerView.setText("00:00");
                            timerView.removeCallbacks(timerRunnable);
                            timerView.post(timerRunnable);
                            recordButton.setBackground(rounded(0xFFFF3B30, 8));
                        });
                    }
                } catch (Exception e) {
                    if (startRecorderAfterConfigure) {
                        recordingStarting = false;
                        safeResetRecorder();
                    }
                    showError("Session start: " + e.getMessage());
                }
            }

            @Override public void onConfigureFailed(CameraCaptureSession session) {
                if (startRecorderAfterConfigure) {
                    recordingStarting = false;
                    safeResetRecorder();
                }
                showError("Camera HAL rejected the session");
                if (!recording) startPreviewSession();
            }
        };
    }

    private void setDesiredZoom(float zoom, boolean updateSliderPosition) {
        float clamped = Math.max(UI_MIN_ZOOM, Math.min(UI_MAX_ZOOM, zoom));
        clamped = Math.round(clamped * 10f) / 10f;
        requestedUiZoom = clamped;

        zoomLiveView.setText(formatLiveZoom(clamped));
        if (updateSliderPosition && zoomSlider != null) zoomSlider.setZoom(clamped);
        applyPreviewPostCrop();

        if (logicalCharacteristics != null && clamped > logicalMaxZoom + 0.01f) {
            showPostCropInfo();
        } else {
            infoView.setVisibility(View.GONE);
        }

        if (cameraHandler != null) {
            cameraHandler.removeCallbacks(applyZoomRunnable);
            cameraHandler.postDelayed(applyZoomRunnable, 12L);
        }
    }

    private void showPostCropInfo() {
        runOnUiThread(() -> {
            infoView.setText(String.format(Locale.US,
                    "Preview %.1fX • camera HAL %.1fX max", requestedUiZoom, logicalMaxZoom));
            infoView.setVisibility(View.VISIBLE);
        });
    }

    private float directCameraZoom() {
        return Math.max(logicalMinZoom, Math.min(requestedUiZoom, logicalMaxZoom));
    }

    private void applyPreviewPostCrop() {
        float directMax = logicalCharacteristics == null ? 10f : logicalMaxZoom;
        float direct = Math.max(UI_MIN_ZOOM, Math.min(requestedUiZoom, directMax));
        final float extra = requestedUiZoom > direct ? requestedUiZoom / direct : 1f;
        runOnUiThread(() -> {
            textureView.setPivotX(textureView.getWidth() / 2f);
            textureView.setPivotY(textureView.getHeight() / 2f);
            textureView.setScaleX(extra);
            textureView.setScaleY(extra);
        });
    }

    private void applyZoomToRepeatingRequest() {
        if (repeatingBuilder == null || captureSession == null || logicalCharacteristics == null) return;
        try {
            setZoomOnBuilder(repeatingBuilder, directCameraZoom());
            captureSession.setRepeatingRequest(repeatingBuilder.build(), null, cameraHandler);
        } catch (Exception e) {
            showError("Zoom rejected: " + e.getMessage());
        }
    }

    private void configureCommonRequest(CaptureRequest.Builder b, boolean forVideo) {
        b.set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO);
        b.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO);
        if (forVideo) {
            Range<Integer> selected = choose60FpsRange();
            if (selected != null) b.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, selected);
        }
        enableBestStabilization(b);
        setZoomOnBuilder(b, directCameraZoom());
    }

    private void setZoomOnBuilder(CaptureRequest.Builder b, float requested) {
        float z = Math.max(logicalMinZoom, Math.min(requested, logicalMaxZoom));
        Range<Float> range = logicalCharacteristics.get(CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE);
        if (range != null) {
            b.set(CaptureRequest.CONTROL_ZOOM_RATIO, z);
            return;
        }
        android.graphics.Rect active = logicalCharacteristics.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE);
        if (active == null || z <= 1f) return;
        int cropW = Math.max(2, Math.round(active.width() / z));
        int cropH = Math.max(2, Math.round(active.height() / z));
        int left = active.centerX() - cropW / 2;
        int top = active.centerY() - cropH / 2;
        b.set(CaptureRequest.SCALER_CROP_REGION,
                new android.graphics.Rect(left, top, left + cropW, top + cropH));
    }

    private Range<Integer> choose60FpsRange() {
        Range<Integer>[] ranges = logicalCharacteristics.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES);
        if (ranges == null) return null;
        Range<Integer> best = null;
        for (Range<Integer> r : ranges) {
            if (r.getLower() <= 60 && r.getUpper() >= 60) {
                if (best == null) best = r;
                if (r.getLower() == 60 && r.getUpper() == 60) return r;
                if (r.getLower() > best.getLower()) best = r;
            }
        }
        return best;
    }

    private void enableBestStabilization(CaptureRequest.Builder b) {
        int[] eisModes = logicalCharacteristics.get(CameraCharacteristics.CONTROL_AVAILABLE_VIDEO_STABILIZATION_MODES);
        boolean previewStab = contains(eisModes, CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_PREVIEW_STABILIZATION);
        boolean eisOn = contains(eisModes, CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_ON);
        if (previewStab) {
            b.set(CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE,
                    CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_PREVIEW_STABILIZATION);
        } else if (eisOn) {
            b.set(CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE,
                    CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_ON);
        } else {
            int[] oisModes = logicalCharacteristics.get(CameraCharacteristics.LENS_INFO_AVAILABLE_OPTICAL_STABILIZATION);
            if (contains(oisModes, CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE_ON)) {
                b.set(CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE,
                        CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE_ON);
            }
        }
    }

    private boolean contains(int[] values, int wanted) {
        if (values == null) return false;
        for (int v : values) if (v == wanted) return true;
        return false;
    }

    private void startRecording() {
        if (cameraDevice == null || recording || recordingStarting) return;
        if (!supports4k60) toast("4K60 is not clearly advertised; trying Vivo's session anyway.");
        try {
            closeSession();
            prepareRecorder();
            recordingStarting = true;
            SurfaceTexture st = textureView.getSurfaceTexture();
            if (st == null) throw new IllegalStateException("Preview surface unavailable");
            st.setDefaultBufferSize(1920, 1080);
            Surface preview = new Surface(st);
            Surface recordSurface = recorder.getSurface();
            repeatingBuilder = cameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_RECORD);
            repeatingBuilder.addTarget(preview);
            repeatingBuilder.addTarget(recordSurface);
            configureCommonRequest(repeatingBuilder, true);
            List<Surface> outputs = new ArrayList<>();
            outputs.add(preview);
            outputs.add(recordSurface);
            cameraDevice.createCaptureSession(outputs, sessionCallback(true), cameraHandler);
        } catch (Exception e) {
            recordingStarting = false;
            showError("Record setup: " + e.getMessage());
            safeResetRecorder();
            startPreviewSession();
        }
    }

    private void prepareRecorder() throws IOException {
        recorder = new MediaRecorder();
        recorder.setAudioSource(MediaRecorder.AudioSource.MIC);
        recorder.setVideoSource(MediaRecorder.VideoSource.SURFACE);
        recorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4);
        recorder.setVideoEncoder(supportsHevc ? MediaRecorder.VideoEncoder.HEVC : MediaRecorder.VideoEncoder.H264);
        recorder.setVideoSize(UHD.getWidth(), UHD.getHeight());
        recorder.setVideoFrameRate(TARGET_FPS);
        recorder.setVideoEncodingBitRate(VIDEO_BITRATE);
        recorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC);
        recorder.setAudioSamplingRate(48_000);
        recorder.setAudioEncodingBitRate(192_000);
        recorder.setOrientationHint(computeOrientationHint());

        ContentValues values = new ContentValues();
        values.put(MediaStore.Video.Media.DISPLAY_NAME, "X100_4K60_" + System.currentTimeMillis() + ".mp4");
        values.put(MediaStore.Video.Media.MIME_TYPE, "video/mp4");
        values.put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/X100Zoom");
        values.put(MediaStore.Video.Media.IS_PENDING, 1);
        ContentResolver resolver = getContentResolver();
        outputUri = resolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values);
        if (outputUri == null) throw new IOException("Cannot create output video");
        outputPfd = resolver.openFileDescriptor(outputUri, "w");
        if (outputPfd == null) throw new IOException("Cannot open output video");
        FileDescriptor fd = outputPfd.getFileDescriptor();
        recorder.setOutputFile(fd);
        recorder.prepare();
    }

    private int computeOrientationHint() {
        int rotation = getWindowManager().getDefaultDisplay().getRotation();
        int degrees;
        switch (rotation) {
            case Surface.ROTATION_90: degrees = 90; break;
            case Surface.ROTATION_180: degrees = 180; break;
            case Surface.ROTATION_270: degrees = 270; break;
            default: degrees = 0;
        }
        return (sensorOrientation - degrees + 360) % 360;
    }

    private void stopRecording() {
        if (recordingStarting && !recording) {
            recordingStarting = false;
            safeResetRecorder();
            startPreviewSession();
            return;
        }
        if (!recording) return;
        try {
            if (captureSession != null) {
                try { captureSession.stopRepeating(); } catch (Exception ignored) {}
                try { captureSession.abortCaptures(); } catch (Exception ignored) {}
            }
            recorder.stop();
            finalizeOutput(true);
            toast("Saved to Movies/X100Zoom");
        } catch (RuntimeException e) {
            finalizeOutput(false);
            showError("Recording failed: " + e.getMessage());
        } finally {
            recording = false;
            recordingStarting = false;
            runOnUiThread(() -> {
                timerView.removeCallbacks(timerRunnable);
                timerView.setText("00:00");
                timerView.setVisibility(View.INVISIBLE);
                recordButton.setBackground(circle(0xFFFF3B30));
            });
            safeResetRecorder();
            startPreviewSession();
        }
    }

    private void finalizeOutput(boolean keep) {
        try { if (outputPfd != null) outputPfd.close(); } catch (IOException ignored) {}
        outputPfd = null;
        if (outputUri != null) {
            if (keep) {
                ContentValues done = new ContentValues();
                done.put(MediaStore.Video.Media.IS_PENDING, 0);
                getContentResolver().update(outputUri, done, null, null);
            } else {
                getContentResolver().delete(outputUri, null, null);
            }
        }
        outputUri = null;
    }

    private void safeResetRecorder() {
        if (recorder != null) {
            try { recorder.reset(); } catch (Exception ignored) {}
            try { recorder.release(); } catch (Exception ignored) {}
            recorder = null;
        }
        try { if (outputPfd != null) outputPfd.close(); } catch (IOException ignored) {}
        outputPfd = null;
    }

    private void closeSession() {
        if (captureSession != null) {
            try { captureSession.close(); } catch (Exception ignored) {}
            captureSession = null;
        }
        repeatingBuilder = null;
    }

    private void closeCamera() {
        if (recording || recordingStarting) stopRecording();
        closeSession();
        if (cameraDevice != null) {
            cameraDevice.close();
            cameraDevice = null;
        }
        safeResetRecorder();
    }

    private void stopCameraThread() {
        if (cameraThread != null) {
            cameraThread.quitSafely();
            try { cameraThread.join(); } catch (InterruptedException ignored) {}
            cameraThread = null;
            cameraHandler = null;
        }
    }

    private void toast(String s) {
        runOnUiThread(() -> Toast.makeText(this, s, Toast.LENGTH_LONG).show());
    }

    private void showError(String s) {
        runOnUiThread(() -> {
            Toast.makeText(this, s, Toast.LENGTH_LONG).show();
            infoView.setText("ERROR • " + s);
            infoView.setVisibility(View.VISIBLE);
        });
    }

    @Override protected void onResume() {
        super.onResume();
        if (hasPermissions()) {
            startCameraThread();
            if (textureView != null && textureView.isAvailable()) openLogicalCamera();
        }
    }

    @Override protected void onPause() {
        closeCamera();
        stopCameraThread();
        super.onPause();
    }

    private final class ZoomSliderView extends View {
        private final Paint linePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint accentPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint knobFillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint knobStrokePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint tickPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private float zoom = 1f;

        ZoomSliderView(Context context) {
            super(context);
            setLayerType(View.LAYER_TYPE_SOFTWARE, null);
            linePaint.setColor(0xCCFFFFFF);
            linePaint.setStrokeWidth(dp(2));
            linePaint.setStrokeCap(Paint.Cap.ROUND);
            accentPaint.setColor(0xFFFFD54F);
            accentPaint.setStrokeWidth(dp(3));
            accentPaint.setStrokeCap(Paint.Cap.ROUND);
            knobFillPaint.setColor(0x66000000);
            knobStrokePaint.setColor(Color.WHITE);
            knobStrokePaint.setStyle(Paint.Style.STROKE);
            knobStrokePaint.setStrokeWidth(dp(2));
            tickPaint.setColor(0x88FFFFFF);
            tickPaint.setStrokeWidth(dp(1));
        }

        void setZoom(float value) {
            zoom = Math.max(UI_MIN_ZOOM, Math.min(UI_MAX_ZOOM, value));
            invalidate();
        }

        @Override protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            float pad = dp(16);
            float left = pad;
            float right = getWidth() - pad;
            float cy = getHeight() / 2f;
            canvas.drawLine(left, cy, right, cy, linePaint);

            float oneX = xForZoom(1f, left, right);
            float knobX = xForZoom(zoom, left, right);
            canvas.drawLine(Math.min(oneX, knobX), cy, Math.max(oneX, knobX), cy, accentPaint);

            float[] marks = {0.6f, 1f, 2f, 3f, 5f, 10f, 20f, 30f};
            for (float mark : marks) {
                float x = xForZoom(mark, left, right);
                canvas.drawLine(x, cy - dp(5), x, cy + dp(5), tickPaint);
            }

            float r = dp(17);
            canvas.drawCircle(knobX, cy, r, knobFillPaint);
            canvas.drawCircle(knobX, cy, r, knobStrokePaint);
        }

        @Override public boolean onTouchEvent(MotionEvent event) {
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                case MotionEvent.ACTION_MOVE:
                    getParent().requestDisallowInterceptTouchEvent(true);
                    updateFromTouch(event.getX());
                    return true;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    getParent().requestDisallowInterceptTouchEvent(false);
                    updateFromTouch(event.getX());
                    return true;
                default:
                    return super.onTouchEvent(event);
            }
        }

        private void updateFromTouch(float touchX) {
            float left = dp(16);
            float right = Math.max(left + 1f, getWidth() - dp(16));
            float t = (touchX - left) / (right - left);
            t = Math.max(0f, Math.min(1f, t));
            float newZoom = zoomForT(t);
            newZoom = Math.round(newZoom * 10f) / 10f;
            zoom = newZoom;
            invalidate();
            setDesiredZoom(newZoom, false);
        }

        private float xForZoom(float value, float left, float right) {
            return left + tForZoom(value) * (right - left);
        }

        private float tForZoom(float value) {
            float z = Math.max(UI_MIN_ZOOM, Math.min(UI_MAX_ZOOM, value));
            final float pivot = 0.42f;
            if (z <= 1f) {
                return ((z - UI_MIN_ZOOM) / (1f - UI_MIN_ZOOM)) * pivot;
            }
            double p = Math.log(z) / Math.log(UI_MAX_ZOOM);
            return pivot + (float) p * (1f - pivot);
        }

        private float zoomForT(float t) {
            final float pivot = 0.42f;
            if (t <= pivot) {
                return UI_MIN_ZOOM + (t / pivot) * (1f - UI_MIN_ZOOM);
            }
            float p = (t - pivot) / (1f - pivot);
            return (float) Math.exp(Math.log(UI_MAX_ZOOM) * p);
        }
    }
}
