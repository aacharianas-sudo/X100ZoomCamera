package com.anas.x100zoom;

import android.Manifest;
import android.app.Activity;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.SurfaceTexture;
import android.graphics.drawable.GradientDrawable;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CameraMetadata;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.params.OutputConfiguration;
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
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.PopupMenu;
import android.widget.TextView;
import android.widget.Toast;

import java.io.FileDescriptor;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Set;

public class MainActivity extends Activity {
    private static final int REQ_PERMS = 77;
    private static final Size UHD = new Size(3840, 2160);
    private static final Size FHD = new Size(1920, 1080);
    private static final float UI_MIN_ZOOM = 0.6f;
    private static final float UI_MAX_ZOOM = 50.0f;
    private static final float TELE_HANDOFF_ZOOM = 3.0f;

    private TextureView textureView;
    private ImageView transitionOverlay;
    private TextView zoomLiveView;
    private TextView modeBadge;
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
    private CameraCharacteristics teleCharacteristics;
    private String logicalCameraId;
    private String telePhysicalId;
    private float logicalMinZoom = 0.6f;
    private float logicalMaxZoom = 10.0f;
    private float teleMinZoom = 1.0f;
    private float teleMaxZoom = 10.0f;
    private float requestedUiZoom = 1.0f;
    private boolean activeTele = false;
    private boolean routeSwitchInProgress = false;
    private boolean supportsHevc = false;
    private int sensorOrientation = 90;

    private Size selectedSize = UHD;
    private int selectedFps = 60;

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

    private static final class LensInfo {
        final String id;
        final float focal;
        LensInfo(String id, float focal) {
            this.id = id;
            this.focal = focal;
        }
    }

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
        root.addView(textureView, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));

        transitionOverlay = new ImageView(this);
        transitionOverlay.setScaleType(ImageView.ScaleType.FIT_XY);
        transitionOverlay.setVisibility(View.GONE);
        transitionOverlay.setBackgroundColor(Color.BLACK);
        root.addView(transitionOverlay, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));

        modeBadge = new TextView(this);
        modeBadge.setText("4K\n60");
        modeBadge.setTextColor(Color.WHITE);
        modeBadge.setTextSize(12f);
        modeBadge.setGravity(Gravity.CENTER);
        modeBadge.setTypeface(null, android.graphics.Typeface.BOLD);
        modeBadge.setPadding(dp(10), dp(5), dp(10), dp(5));
        modeBadge.setBackground(rounded(0x77000000, 10));
        modeBadge.setOnClickListener(this::showVideoModeMenu);
        FrameLayout.LayoutParams modeLp = new FrameLayout.LayoutParams(dp(64), dp(52));
        modeLp.gravity = Gravity.TOP | Gravity.START;
        modeLp.leftMargin = dp(16);
        modeLp.topMargin = dp(14);
        root.addView(modeBadge, modeLp);

        zoomLiveView = new TextView(this);
        zoomLiveView.setText("1X");
        zoomLiveView.setTextColor(Color.WHITE);
        zoomLiveView.setTextSize(30f);
        zoomLiveView.setTypeface(null, android.graphics.Typeface.BOLD);
        zoomLiveView.setGravity(Gravity.CENTER);
        zoomLiveView.setShadowLayer(6f, 0f, 1f, Color.BLACK);
        FrameLayout.LayoutParams zoomLiveLp = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT);
        zoomLiveLp.gravity = Gravity.TOP | Gravity.CENTER_HORIZONTAL;
        zoomLiveLp.topMargin = dp(20);
        root.addView(zoomLiveView, zoomLiveLp);

        TextView settings = new TextView(this);
        settings.setText("⚙");
        settings.setTextColor(Color.WHITE);
        settings.setTextSize(25f);
        settings.setGravity(Gravity.CENTER);
        settings.setBackground(rounded(0x44000000, 20));
        FrameLayout.LayoutParams settingsLp = new FrameLayout.LayoutParams(dp(46), dp(46));
        settingsLp.gravity = Gravity.TOP | Gravity.END;
        settingsLp.rightMargin = dp(14);
        settingsLp.topMargin = dp(16);
        root.addView(settings, settingsLp);

        LinearLayout controls = new LinearLayout(this);
        controls.setOrientation(LinearLayout.VERTICAL);
        controls.setGravity(Gravity.CENTER_HORIZONTAL);
        controls.setPadding(dp(14), dp(10), dp(14), dp(20));
        controls.setBackgroundColor(0x44000000);

        LinearLayout zoomRow = new LinearLayout(this);
        zoomRow.setGravity(Gravity.CENTER_VERTICAL);

        TextView minus = makeZoomEdgeButton("−");
        minus.setOnClickListener(v -> nudgeZoom(-1));
        zoomRow.addView(minus, new LinearLayout.LayoutParams(dp(48), dp(60)));

        zoomSlider = new ZoomSliderView(this);
        zoomSlider.setZoom(requestedUiZoom);
        zoomRow.addView(zoomSlider, new LinearLayout.LayoutParams(0, dp(68), 1f));

        TextView plus = makeZoomEdgeButton("+");
        plus.setOnClickListener(v -> nudgeZoom(1));
        zoomRow.addView(plus, new LinearLayout.LayoutParams(dp(48), dp(60)));

        controls.addView(zoomRow, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(70)));

        timerView = new TextView(this);
        timerView.setText("00:00");
        timerView.setTextColor(Color.WHITE);
        timerView.setTextSize(14f);
        timerView.setGravity(Gravity.CENTER);
        timerView.setVisibility(View.INVISIBLE);
        controls.addView(timerView, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(28)));

        FrameLayout recordWrap = new FrameLayout(this);
        LinearLayout.LayoutParams rwLp = new LinearLayout.LayoutParams(dp(108), dp(108));
        rwLp.topMargin = dp(2);
        controls.addView(recordWrap, rwLp);

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

    private void showVideoModeMenu(View anchor) {
        if (recording || recordingStarting) {
            toast("Stop recording before changing video mode.");
            return;
        }
        PopupMenu menu = new PopupMenu(this, anchor);
        menu.getMenu().add("1080P 30 FPS");
        menu.getMenu().add("1080P 60 FPS");
        menu.getMenu().add("4K 30 FPS");
        menu.getMenu().add("4K 60 FPS");
        menu.setOnMenuItemClickListener(item -> {
            String s = item.getTitle().toString();
            if (s.startsWith("1080P")) selectedSize = FHD; else selectedSize = UHD;
            selectedFps = s.contains("60") ? 60 : 30;
            updateModeBadge();
            if (cameraHandler != null && cameraDevice != null) cameraHandler.post(this::startPreviewSession);
            return true;
        });
        menu.show();
    }

    private void updateModeBadge() {
        String res = selectedSize.equals(UHD) ? "4K" : "1080P";
        modeBadge.setText(res + "\n" + selectedFps);
    }

    private void nudgeZoom(int direction) {
        float step;
        if (requestedUiZoom < 3f) step = 0.1f;
        else if (requestedUiZoom < 10f) step = 0.2f;
        else step = 1.0f;
        setDesiredZoom(requestedUiZoom + direction * step, true);
    }

    private String formatLiveZoom(float z) {
        if (Math.abs(z - Math.round(z)) < 0.001f) return String.format(Locale.US, "%.0fX", z);
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
        cameraThread = new HandlerThread("X100CameraV4");
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
        @Override public void onSurfaceTextureSizeChanged(SurfaceTexture surface, int width, int height) {
            applyExtraPreviewCrop();
        }
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
            findTelePhysicalCamera();
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
                    if (cap == CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_LOGICAL_MULTI_CAMERA) score += 1000f;
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
        supportsHevc = hasEncoder("video/hevc");
    }

    private void findTelePhysicalCamera() {
        telePhysicalId = null;
        teleCharacteristics = null;
        try {
            Set<String> ids = logicalCharacteristics.getPhysicalCameraIds();
            List<LensInfo> infos = new ArrayList<>();
            for (String id : ids) {
                CameraCharacteristics c = cameraManager.getCameraCharacteristics(id);
                float[] focals = c.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS);
                if (focals != null && focals.length > 0) infos.add(new LensInfo(id, focals[0]));
            }
            infos.sort(Comparator.comparingDouble(a -> a.focal));
            if (!infos.isEmpty()) {
                telePhysicalId = infos.get(infos.size() - 1).id;
                teleCharacteristics = cameraManager.getCameraCharacteristics(telePhysicalId);
                Range<Float> tr = teleCharacteristics.get(CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE);
                if (tr != null) {
                    teleMinZoom = Math.max(1f, tr.getLower());
                    teleMaxZoom = tr.getUpper();
                } else {
                    Float max = teleCharacteristics.get(CameraCharacteristics.SCALER_AVAILABLE_MAX_DIGITAL_ZOOM);
                    teleMinZoom = 1f;
                    teleMaxZoom = max != null ? max : 10f;
                }
            }
        } catch (Exception e) {
            telePhysicalId = null;
            teleCharacteristics = null;
        }
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
            for (String type : info.getSupportedTypes()) if (type.equalsIgnoreCase(mime)) return true;
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
            closeSessionOnly();
            SurfaceTexture st = textureView.getSurfaceTexture();
            if (st == null) return;
            st.setDefaultBufferSize(1920, 1080);
            Surface preview = new Surface(st);
            repeatingBuilder = cameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
            repeatingBuilder.addTarget(preview);
            configureCommonRequest(repeatingBuilder, false);
            List<Surface> outputs = new ArrayList<>();
            outputs.add(preview);
            createSession(outputs, false);
        } catch (Exception e) {
            routeSwitchInProgress = false;
            showError("Preview setup: " + e.getMessage());
        }
    }

    private void startRecording() {
        if (cameraDevice == null || recording || recordingStarting) return;
        try {
            if (!modeLooksSupported(selectedSize, selectedFps)) {
                toast("This mode is not advertised by Camera2; trying it anyway.");
            }
            closeSessionOnly();
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
            createSession(outputs, true);
        } catch (Exception e) {
            recordingStarting = false;
            showError("Record setup: " + e.getMessage());
            safeResetRecorder();
            startPreviewSession();
        }
    }

    private boolean modeLooksSupported(Size size, int fps) {
        if (logicalCharacteristics == null) return false;
        StreamConfigurationMap map = logicalCharacteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
        if (map == null || !containsSize(map.getOutputSizes(MediaRecorder.class), size)) return false;
        Range<Integer>[] ranges = logicalCharacteristics.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES);
        if (ranges == null) return true;
        for (Range<Integer> r : ranges) if (r.getLower() <= fps && r.getUpper() >= fps) return true;
        return false;
    }

    private void createSession(List<Surface> outputs, boolean startRecorderAfterConfigure) throws CameraAccessException {
        CameraCaptureSession.StateCallback callback = new CameraCaptureSession.StateCallback() {
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
                        });
                    }
                    routeSwitchInProgress = false;
                    runOnUiThread(() -> {
                        applyExtraPreviewCrop();
                        finishTransitionOverlay();
                    });
                    reconcileDesiredRoute();
                } catch (Exception e) {
                    routeSwitchInProgress = false;
                    if (startRecorderAfterConfigure) {
                        recordingStarting = false;
                        safeResetRecorder();
                    }
                    showError("Session start: " + e.getMessage());
                }
            }

            @Override public void onConfigureFailed(CameraCaptureSession session) {
                routeSwitchInProgress = false;
                runOnUiThread(MainActivity.this::finishTransitionOverlay);
                if (activeTele) {
                    activeTele = false;
                    toast("Telephoto route was rejected in this video mode. Try 4K30 or 1080P60.");
                } else {
                    toast("Camera HAL rejected this session.");
                }
                if (recordingStarting) {
                    recordingStarting = false;
                    safeResetRecorder();
                }
                if (!recording) startPreviewSession();
            }
        };

        if (activeTele && telePhysicalId != null) {
            try {
                List<OutputConfiguration> configs = new ArrayList<>();
                for (Surface s : outputs) {
                    OutputConfiguration oc = new OutputConfiguration(s);
                    oc.setPhysicalCameraId(telePhysicalId);
                    configs.add(oc);
                }
                cameraDevice.createCaptureSessionByOutputConfigurations(configs, callback, cameraHandler);
                return;
            } catch (Exception e) {
                activeTele = false;
                toast("Physical tele route unavailable; falling back to logical camera.");
            }
        }
        cameraDevice.createCaptureSession(outputs, callback, cameraHandler);
    }

    private void setDesiredZoom(float uiZoom, boolean fromButtons) {
        float z = Math.max(UI_MIN_ZOOM, Math.min(UI_MAX_ZOOM, uiZoom));
        if (z < 10f) z = Math.round(z * 10f) / 10f;
        else z = Math.round(z * 2f) / 2f;
        requestedUiZoom = z;

        zoomLiveView.setText(formatLiveZoom(z));
        if (zoomSlider != null && fromButtons) zoomSlider.setZoom(z);
        applyExtraPreviewCrop();

        boolean shouldTele = z >= TELE_HANDOFF_ZOOM && telePhysicalId != null;
        if (shouldTele != activeTele && cameraDevice != null) {
            if (!routeSwitchInProgress) {
                activeTele = shouldTele;
                routeSwitchInProgress = true;
                prepareTransitionOverlay();
                if (cameraHandler != null) cameraHandler.post(this::rebuildSessionForRouteSwitch);
            }
        } else {
            scheduleZoomApply();
        }
    }

    private void reconcileDesiredRoute() {
        boolean shouldTele = requestedUiZoom >= TELE_HANDOFF_ZOOM && telePhysicalId != null;
        if (shouldTele != activeTele && !routeSwitchInProgress && cameraHandler != null) {
            runOnUiThread(this::prepareTransitionOverlay);
            activeTele = shouldTele;
            routeSwitchInProgress = true;
            cameraHandler.post(this::rebuildSessionForRouteSwitch);
        }
    }

    private void rebuildSessionForRouteSwitch() {
        if (cameraDevice == null || !textureView.isAvailable()) {
            routeSwitchInProgress = false;
            return;
        }
        try {
            closeSessionOnly();
            SurfaceTexture st = textureView.getSurfaceTexture();
            if (st == null) throw new IllegalStateException("Preview surface unavailable");
            st.setDefaultBufferSize(1920, 1080);
            Surface preview = new Surface(st);

            if (recording && recorder != null) {
                Surface recordSurface = recorder.getSurface();
                repeatingBuilder = cameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_RECORD);
                repeatingBuilder.addTarget(preview);
                repeatingBuilder.addTarget(recordSurface);
                configureCommonRequest(repeatingBuilder, true);
                List<Surface> outputs = new ArrayList<>();
                outputs.add(preview);
                outputs.add(recordSurface);
                createSession(outputs, false);
            } else {
                repeatingBuilder = cameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
                repeatingBuilder.addTarget(preview);
                configureCommonRequest(repeatingBuilder, false);
                List<Surface> outputs = new ArrayList<>();
                outputs.add(preview);
                createSession(outputs, false);
            }
        } catch (Exception e) {
            routeSwitchInProgress = false;
            runOnUiThread(this::finishTransitionOverlay);
            showError("Lens handoff: " + e.getMessage());
        }
    }

    private void prepareTransitionOverlay() {
        if (textureView == null || transitionOverlay == null || !textureView.isAvailable()) return;
        try {
            Bitmap b = textureView.getBitmap();
            if (b != null) {
                transitionOverlay.animate().cancel();
                transitionOverlay.setImageBitmap(b);
                transitionOverlay.setAlpha(1f);
                transitionOverlay.setVisibility(View.VISIBLE);
            }
        } catch (Exception ignored) {}
    }

    private void finishTransitionOverlay() {
        if (transitionOverlay == null || transitionOverlay.getVisibility() != View.VISIBLE) return;
        transitionOverlay.postDelayed(() -> transitionOverlay.animate()
                .alpha(0f)
                .setDuration(220)
                .withEndAction(() -> {
                    transitionOverlay.setVisibility(View.GONE);
                    transitionOverlay.setImageDrawable(null);
                    transitionOverlay.setAlpha(1f);
                }).start(), 90);
    }

    private void scheduleZoomApply() {
        if (cameraHandler == null) return;
        cameraHandler.removeCallbacks(applyZoomRunnable);
        cameraHandler.postDelayed(applyZoomRunnable, 8);
    }

    private void applyZoomToRepeatingRequest() {
        if (repeatingBuilder == null || captureSession == null || logicalCharacteristics == null) return;
        try {
            setZoomOnBuilder(repeatingBuilder);
            captureSession.setRepeatingRequest(repeatingBuilder.build(), null, cameraHandler);
        } catch (Exception e) {
            showError("Zoom: " + e.getMessage());
        }
    }

    private void configureCommonRequest(CaptureRequest.Builder b, boolean forVideo) {
        b.set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO);
        b.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO);
        Range<Integer> fps = chooseFpsRange(selectedFps);
        if (fps != null) b.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, fps);
        enableBestStabilization(b);
        setZoomOnBuilder(b);
    }

    private void setZoomOnBuilder(CaptureRequest.Builder b) {
        if (!activeTele || telePhysicalId == null || teleCharacteristics == null) {
            float z = Math.max(logicalMinZoom, Math.min(requestedUiZoom, logicalMaxZoom));
            Range<Float> range = logicalCharacteristics.get(CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE);
            if (range != null) {
                b.set(CaptureRequest.CONTROL_ZOOM_RATIO, z);
            } else {
                Rect active = logicalCharacteristics.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE);
                if (active != null) b.set(CaptureRequest.SCALER_CROP_REGION, cropForZoom(active, Math.max(1f, z)));
            }
            return;
        }

        float desiredTeleZoom = Math.max(1f, requestedUiZoom / TELE_HANDOFF_ZOOM);
        float hardwareTeleZoom = Math.max(teleMinZoom, Math.min(desiredTeleZoom, teleMaxZoom));

        Range<Float> logicalRange = logicalCharacteristics.get(CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE);
        if (logicalRange != null) {
            float neutral = Math.max(logicalRange.getLower(), Math.min(1f, logicalRange.getUpper()));
            b.set(CaptureRequest.CONTROL_ZOOM_RATIO, neutral);
        }

        boolean physicalApplied = false;
        try {
            List<CaptureRequest.Key<?>> keys = logicalCharacteristics.getAvailablePhysicalCameraRequestKeys();
            if (keys != null && keys.contains(CaptureRequest.CONTROL_ZOOM_RATIO)) {
                b.setPhysicalCameraKey(CaptureRequest.CONTROL_ZOOM_RATIO, hardwareTeleZoom, telePhysicalId);
                physicalApplied = true;
            } else if (keys != null && keys.contains(CaptureRequest.SCALER_CROP_REGION)) {
                Rect active = teleCharacteristics.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE);
                if (active != null) {
                    b.setPhysicalCameraKey(CaptureRequest.SCALER_CROP_REGION,
                            cropForZoom(active, hardwareTeleZoom), telePhysicalId);
                    physicalApplied = true;
                }
            }
        } catch (Exception ignored) {}

        if (!physicalApplied) {
            Range<Float> range = logicalCharacteristics.get(CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE);
            if (range != null) {
                float z = Math.max(range.getLower(), Math.min(hardwareTeleZoom, range.getUpper()));
                b.set(CaptureRequest.CONTROL_ZOOM_RATIO, z);
            }
        }
    }

    private Rect cropForZoom(Rect active, float zoom) {
        float z = Math.max(1f, zoom);
        int w = Math.max(2, Math.round(active.width() / z));
        int h = Math.max(2, Math.round(active.height() / z));
        int left = active.centerX() - w / 2;
        int top = active.centerY() - h / 2;
        return new Rect(left, top, left + w, top + h);
    }

    private Range<Integer> chooseFpsRange(int wanted) {
        if (logicalCharacteristics == null) return null;
        Range<Integer>[] ranges = logicalCharacteristics.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES);
        if (ranges == null) return null;
        Range<Integer> best = null;
        for (Range<Integer> r : ranges) {
            if (r.getLower() <= wanted && r.getUpper() >= wanted) {
                if (r.getLower() == wanted && r.getUpper() == wanted) return r;
                if (best == null || r.getLower() > best.getLower()) best = r;
            }
        }
        return best;
    }

    private void enableBestStabilization(CaptureRequest.Builder b) {
        if (logicalCharacteristics == null) return;
        int[] eis = logicalCharacteristics.get(CameraCharacteristics.CONTROL_AVAILABLE_VIDEO_STABILIZATION_MODES);
        if (contains(eis, CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_PREVIEW_STABILIZATION)) {
            b.set(CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE,
                    CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_PREVIEW_STABILIZATION);
        } else if (contains(eis, CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_ON)) {
            b.set(CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE,
                    CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_ON);
        }

        int[] ois = logicalCharacteristics.get(CameraCharacteristics.LENS_INFO_AVAILABLE_OPTICAL_STABILIZATION);
        if (contains(ois, CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE_ON)) {
            try {
                b.set(CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE,
                        CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE_ON);
            } catch (Exception ignored) {}
        }

        if (activeTele && telePhysicalId != null && teleCharacteristics != null) {
            try {
                int[] teleOis = teleCharacteristics.get(CameraCharacteristics.LENS_INFO_AVAILABLE_OPTICAL_STABILIZATION);
                List<CaptureRequest.Key<?>> keys = logicalCharacteristics.getAvailablePhysicalCameraRequestKeys();
                if (contains(teleOis, CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE_ON) &&
                        keys != null && keys.contains(CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE)) {
                    b.setPhysicalCameraKey(CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE,
                            CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE_ON, telePhysicalId);
                }
            } catch (Exception ignored) {}
        }
    }

    private boolean contains(int[] values, int wanted) {
        if (values == null) return false;
        for (int v : values) if (v == wanted) return true;
        return false;
    }

    private void applyExtraPreviewCrop() {
        if (textureView == null || textureView.getWidth() <= 0 || textureView.getHeight() <= 0) return;
        float maxEquivalent = Math.max(TELE_HANDOFF_ZOOM, TELE_HANDOFF_ZOOM * teleMaxZoom);
        float extra = 1f;
        if (requestedUiZoom > maxEquivalent) extra = requestedUiZoom / maxEquivalent;
        Matrix m = new Matrix();
        if (extra > 1.001f) {
            m.postScale(extra, extra, textureView.getWidth() / 2f, textureView.getHeight() / 2f);
        }
        textureView.setTransform(m);
    }

    private void prepareRecorder() throws IOException {
        recorder = new MediaRecorder();
        recorder.setAudioSource(MediaRecorder.AudioSource.MIC);
        recorder.setVideoSource(MediaRecorder.VideoSource.SURFACE);
        recorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4);
        recorder.setVideoEncoder(supportsHevc ? MediaRecorder.VideoEncoder.HEVC : MediaRecorder.VideoEncoder.H264);
        recorder.setVideoSize(selectedSize.getWidth(), selectedSize.getHeight());
        recorder.setVideoFrameRate(selectedFps);
        int bitrate;
        if (selectedSize.equals(UHD)) bitrate = selectedFps >= 60 ? 100_000_000 : 70_000_000;
        else bitrate = selectedFps >= 60 ? 45_000_000 : 30_000_000;
        recorder.setVideoEncodingBitRate(bitrate);
        recorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC);
        recorder.setAudioSamplingRate(48_000);
        recorder.setAudioEncodingBitRate(192_000);
        recorder.setOrientationHint(computeOrientationHint());

        ContentValues values = new ContentValues();
        String res = selectedSize.equals(UHD) ? "4K" : "1080P";
        values.put(MediaStore.Video.Media.DISPLAY_NAME,
                "X100_" + res + selectedFps + "_" + System.currentTimeMillis() + ".mp4");
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

    private void closeSessionOnly() {
        if (captureSession != null) {
            try { captureSession.close(); } catch (Exception ignored) {}
            captureSession = null;
        }
        repeatingBuilder = null;
    }

    private void closeCamera() {
        if (recording || recordingStarting) stopRecording();
        closeSessionOnly();
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
        runOnUiThread(() -> Toast.makeText(this, s, Toast.LENGTH_LONG).show());
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

    private class ZoomSliderView extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private float zoom = 1f;

        ZoomSliderView(Context context) {
            super(context);
            setLayerType(View.LAYER_TYPE_SOFTWARE, null);
        }

        void setZoom(float z) {
            zoom = Math.max(UI_MIN_ZOOM, Math.min(UI_MAX_ZOOM, z));
            invalidate();
        }

        @Override protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            float left = dp(18);
            float right = getWidth() - dp(18);
            float cy = getHeight() / 2f;
            float pos = zoomToPosition(zoom);
            float x = left + pos * (right - left);

            paint.setStrokeWidth(dp(2));
            paint.setColor(0xDDFFFFFF);
            canvas.drawLine(left, cy, right, cy, paint);

            paint.setColor(0xFFFFD54F);
            paint.setStrokeWidth(dp(3));
            canvas.drawLine(left, cy, x, cy, paint);

            float[] ticks = {0.6f, 1f, 2f, 3f, 10f, 30f, 50f};
            paint.setColor(0xCCFFFFFF);
            paint.setStrokeWidth(dp(1));
            for (float t : ticks) {
                float tx = left + zoomToPosition(t) * (right - left);
                canvas.drawLine(tx, cy - dp(7), tx, cy + dp(7), paint);
            }

            paint.setStyle(Paint.Style.FILL);
            paint.setColor(0xFFEEEEEE);
            canvas.drawCircle(x, cy, dp(18), paint);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(dp(2));
            paint.setColor(Color.WHITE);
            canvas.drawCircle(x, cy, dp(21), paint);
            paint.setStyle(Paint.Style.FILL);
        }

        @Override public boolean onTouchEvent(MotionEvent event) {
            if (event.getAction() == MotionEvent.ACTION_DOWN || event.getAction() == MotionEvent.ACTION_MOVE) {
                float left = dp(18);
                float right = getWidth() - dp(18);
                float p = (event.getX() - left) / Math.max(1f, right - left);
                p = Math.max(0f, Math.min(1f, p));
                float z = positionToZoom(p);
                setZoom(z);
                setDesiredZoom(z, false);
                return true;
            }
            return event.getAction() == MotionEvent.ACTION_UP || super.onTouchEvent(event);
        }

        private float zoomToPosition(float z) {
            z = Math.max(UI_MIN_ZOOM, Math.min(UI_MAX_ZOOM, z));
            if (z <= 1f) return ((z - 0.6f) / 0.4f) * 0.15f;
            if (z <= 3f) return 0.15f + ((z - 1f) / 2f) * 0.30f;
            if (z <= 10f) return 0.45f + ((z - 3f) / 7f) * 0.25f;
            return 0.70f + ((z - 10f) / 40f) * 0.30f;
        }

        private float positionToZoom(float p) {
            if (p <= 0.15f) return 0.6f + (p / 0.15f) * 0.4f;
            if (p <= 0.45f) return 1f + ((p - 0.15f) / 0.30f) * 2f;
            if (p <= 0.70f) return 3f + ((p - 0.45f) / 0.25f) * 7f;
            return 10f + ((p - 0.70f) / 0.30f) * 40f;
        }
    }
}
