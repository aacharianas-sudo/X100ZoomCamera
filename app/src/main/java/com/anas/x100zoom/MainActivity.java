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
    private static final float UI_MAX_ZOOM = 100.0f;
    private static final float TELE_HANDOFF = 3.0f;

    private TextureView textureView;
    private ImageView transitionOverlay;
    private TextView zoomLiveView;
    private TextView routeView;
    private TextView modeBadge;
    private TextView timerView;
    private Button recordButton;
    private ZoomSliderView zoomSlider;

    private CameraManager cameraManager;
    private HandlerThread cameraThread;
    private Handler cameraHandler;
    private CameraDevice cameraDevice;
    private CameraCaptureSession captureSession;
    private CaptureRequest.Builder repeatingBuilder;

    private String logicalCameraId;
    private String teleCameraId;
    private String currentCameraId;
    private CameraCharacteristics logicalChars;
    private CameraCharacteristics teleChars;
    private CameraCharacteristics currentChars;

    private float logicalMinZoom = 0.6f;
    private float logicalMaxZoom = 10f;
    private float teleMinZoom = 1f;
    private float teleMaxZoom = 10f;
    private float requestedUiZoom = 1f;

    private boolean activeTele = false;
    private boolean routeSwitching = false;
    private boolean teleDirectOpenFailed = false;
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
            if (textureView.isAvailable()) discoverAndOpen();
        } else {
            requestPermissions(new String[]{Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO}, REQ_PERMS);
        }
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    private GradientDrawable rounded(int color, int radiusDp) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(color);
        d.setCornerRadius(dp(radiusDp));
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

        textureView = new TextureView(this);
        textureView.setSurfaceTextureListener(textureListener);
        root.addView(textureView, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));

        transitionOverlay = new ImageView(this);
        transitionOverlay.setScaleType(ImageView.ScaleType.FIT_XY);
        transitionOverlay.setBackgroundColor(Color.BLACK);
        transitionOverlay.setVisibility(View.GONE);
        root.addView(transitionOverlay, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));

        modeBadge = new TextView(this);
        modeBadge.setText("4K\n60");
        modeBadge.setTextColor(Color.WHITE);
        modeBadge.setTextSize(12f);
        modeBadge.setTypeface(null, android.graphics.Typeface.BOLD);
        modeBadge.setGravity(Gravity.CENTER);
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
        FrameLayout.LayoutParams zoomLp = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT);
        zoomLp.gravity = Gravity.TOP | Gravity.CENTER_HORIZONTAL;
        zoomLp.topMargin = dp(18);
        root.addView(zoomLiveView, zoomLp);

        routeView = new TextView(this);
        routeView.setText("MAIN");
        routeView.setTextColor(0xFFD8D8D8);
        routeView.setTextSize(10f);
        routeView.setGravity(Gravity.CENTER);
        routeView.setShadowLayer(4f, 0f, 1f, Color.BLACK);
        FrameLayout.LayoutParams routeLp = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT);
        routeLp.gravity = Gravity.TOP | Gravity.CENTER_HORIZONTAL;
        routeLp.topMargin = dp(58);
        root.addView(routeView, routeLp);

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

        TextView minus = makeEdgeButton("−");
        minus.setOnClickListener(v -> nudgeZoom(-1));
        zoomRow.addView(minus, new LinearLayout.LayoutParams(dp(48), dp(60)));

        zoomSlider = new ZoomSliderView(this);
        zoomSlider.setZoom(1f);
        zoomRow.addView(zoomSlider, new LinearLayout.LayoutParams(0, dp(68), 1f));

        TextView plus = makeEdgeButton("+");
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
        controls.addView(recordWrap, new LinearLayout.LayoutParams(dp(108), dp(108)));

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
        FrameLayout.LayoutParams recLp = new FrameLayout.LayoutParams(dp(72), dp(72));
        recLp.gravity = Gravity.CENTER;
        recordWrap.addView(recordButton, recLp);

        TextView label = new TextView(this);
        label.setText("VIDEO");
        label.setTextColor(0xFFFFD54F);
        label.setTextSize(13f);
        label.setTypeface(null, android.graphics.Typeface.BOLD);
        label.setGravity(Gravity.CENTER);
        controls.addView(label, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(24)));

        FrameLayout.LayoutParams controlsLp = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT);
        controlsLp.gravity = Gravity.BOTTOM;
        root.addView(controls, controlsLp);

        setContentView(root);
    }

    private TextView makeEdgeButton(String s) {
        TextView v = new TextView(this);
        v.setText(s);
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
            selectedSize = s.startsWith("1080P") ? FHD : UHD;
            selectedFps = s.contains("60") ? 60 : 30;
            updateModeBadge();
            if (cameraHandler != null && cameraDevice != null) cameraHandler.post(this::startPreviewSession);
            return true;
        });
        menu.show();
    }

    private void updateModeBadge() {
        modeBadge.setText((selectedSize.equals(UHD) ? "4K" : "1080P") + "\n" + selectedFps);
    }

    private void nudgeZoom(int direction) {
        float step = requestedUiZoom < 3f ? 0.1f : (requestedUiZoom < 10f ? 0.2f : 1f);
        setDesiredZoom(requestedUiZoom + direction * step, true);
    }

    private String formatZoom(float z) {
        if (Math.abs(z - Math.round(z)) < 0.001f) return String.format(Locale.US, "%.0fX", z);
        return String.format(Locale.US, "%.1fX", z);
    }

    private boolean hasPermissions() {
        return checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED &&
                checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED;
    }

    @Override public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(requestCode, permissions, results);
        if (requestCode == REQ_PERMS && hasPermissions()) {
            startCameraThread();
            if (textureView.isAvailable()) discoverAndOpen();
        } else {
            toast("Camera and microphone permissions are required.");
        }
    }

    private void startCameraThread() {
        if (cameraThread != null) return;
        cameraThread = new HandlerThread("X100CameraV5");
        cameraThread.start();
        cameraHandler = new Handler(cameraThread.getLooper());
    }

    private final TextureView.SurfaceTextureListener textureListener = new TextureView.SurfaceTextureListener() {
        @Override public void onSurfaceTextureAvailable(SurfaceTexture s, int w, int h) {
            if (hasPermissions()) {
                startCameraThread();
                discoverAndOpen();
            }
        }
        @Override public void onSurfaceTextureSizeChanged(SurfaceTexture s, int w, int h) {
            applyExtraPreviewCrop();
        }
        @Override public boolean onSurfaceTextureDestroyed(SurfaceTexture s) { return true; }
        @Override public void onSurfaceTextureUpdated(SurfaceTexture s) {}
    };

    private void discoverAndOpen() {
        if (cameraHandler == null || cameraDevice != null) return;
        try {
            logicalCameraId = chooseLogicalRearCamera();
            if (logicalCameraId == null) throw new IllegalStateException("No rear logical camera");
            logicalChars = cameraManager.getCameraCharacteristics(logicalCameraId);
            readLogicalZoomRange();
            findTeleCamera();
            supportsHevc = hasEncoder("video/hevc");
            openRoute(false);
        } catch (Exception e) {
            showError("Camera discovery: " + e.getMessage());
        }
    }

    private String chooseLogicalRearCamera() throws CameraAccessException {
        String best = null;
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
            Range<Float> zr = c.get(CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE);
            if (zr != null) {
                score += zr.getUpper();
                if (zr.getLower() <= 0.6f) score += 100f;
            }
            StreamConfigurationMap map = c.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
            if (map != null && containsSize(map.getOutputSizes(MediaRecorder.class), UHD)) score += 500f;
            if (score > bestScore) {
                bestScore = score;
                best = id;
            }
        }
        return best;
    }

    private void readLogicalZoomRange() {
        Range<Float> zr = logicalChars.get(CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE);
        if (zr != null) {
            logicalMinZoom = zr.getLower();
            logicalMaxZoom = zr.getUpper();
        } else {
            Float max = logicalChars.get(CameraCharacteristics.SCALER_AVAILABLE_MAX_DIGITAL_ZOOM);
            logicalMinZoom = 1f;
            logicalMaxZoom = max != null ? max : 10f;
        }
    }

    private void findTeleCamera() {
        teleCameraId = null;
        teleChars = null;
        try {
            Set<String> ids = logicalChars.getPhysicalCameraIds();
            List<LensInfo> infos = new ArrayList<>();
            for (String id : ids) {
                CameraCharacteristics c = cameraManager.getCameraCharacteristics(id);
                float[] focals = c.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS);
                if (focals != null && focals.length > 0) infos.add(new LensInfo(id, focals[0]));
            }
            infos.sort(Comparator.comparingDouble(a -> a.focal));
            if (!infos.isEmpty()) {
                teleCameraId = infos.get(infos.size() - 1).id;
                teleChars = cameraManager.getCameraCharacteristics(teleCameraId);
                Range<Float> zr = teleChars.get(CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE);
                if (zr != null) {
                    teleMinZoom = Math.max(1f, zr.getLower());
                    teleMaxZoom = zr.getUpper();
                } else {
                    Float max = teleChars.get(CameraCharacteristics.SCALER_AVAILABLE_MAX_DIGITAL_ZOOM);
                    teleMinZoom = 1f;
                    teleMaxZoom = max != null ? max : 10f;
                }
            }
        } catch (Exception e) {
            teleCameraId = null;
            teleChars = null;
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
            for (String t : info.getSupportedTypes()) if (mime.equalsIgnoreCase(t)) return true;
        }
        return false;
    }

    private void openRoute(boolean tele) {
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) return;
        String targetId = tele && teleCameraId != null ? teleCameraId : logicalCameraId;
        CameraCharacteristics targetChars = tele && teleChars != null ? teleChars : logicalChars;

        activeTele = tele && teleCameraId != null;
        currentCameraId = targetId;
        currentChars = targetChars;
        Integer orient = currentChars.get(CameraCharacteristics.SENSOR_ORIENTATION);
        if (orient != null) sensorOrientation = orient;

        runOnUiThread(() -> routeView.setText(activeTele ? "TELE • DIRECT" : "MAIN • LOGICAL"));

        try {
            cameraManager.openCamera(targetId, cameraStateCallback, cameraHandler);
        } catch (Exception e) {
            if (activeTele) {
                teleDirectOpenFailed = true;
                activeTele = false;
                currentCameraId = logicalCameraId;
                currentChars = logicalChars;
                runOnUiThread(() -> {
                    routeView.setText("TELE DIRECT BLOCKED");
                    toast("Vivo blocked direct telephoto CameraDevice. Falling back to main/logical.");
                });
                try {
                    cameraManager.openCamera(logicalCameraId, cameraStateCallback, cameraHandler);
                } catch (Exception e2) {
                    routeSwitching = false;
                    showError("Open fallback camera: " + e2.getMessage());
                }
            } else {
                routeSwitching = false;
                showError("Open camera: " + e.getMessage());
            }
        }
    }

    private final CameraDevice.StateCallback cameraStateCallback = new CameraDevice.StateCallback() {
        @Override public void onOpened(CameraDevice camera) {
            cameraDevice = camera;
            routeSwitching = false;
            startSessionForCurrentState();
        }

        @Override public void onDisconnected(CameraDevice camera) {
            camera.close();
            if (cameraDevice == camera) cameraDevice = null;
        }

        @Override public void onError(CameraDevice camera, int error) {
            camera.close();
            if (cameraDevice == camera) cameraDevice = null;
            if (activeTele) {
                teleDirectOpenFailed = true;
                activeTele = false;
                currentCameraId = logicalCameraId;
                currentChars = logicalChars;
                runOnUiThread(() -> routeView.setText("TELE DIRECT BLOCKED"));
                try {
                    if (checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
                        cameraManager.openCamera(logicalCameraId, this, cameraHandler);
                    }
                } catch (Exception e) {
                    routeSwitching = false;
                    showError("Tele fallback failed: " + e.getMessage());
                }
            } else {
                routeSwitching = false;
                showError("Camera error " + error);
            }
        }
    };

    private void startSessionForCurrentState() {
        if (cameraDevice == null || !textureView.isAvailable()) return;
        if (recording || recordingStarting) startRecordSession(recordingStarting);
        else startPreviewSession();
    }

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
            configureCommonRequest(repeatingBuilder);

            List<Surface> outputs = new ArrayList<>();
            outputs.add(preview);
            createSession(outputs, false);
        } catch (Exception e) {
            routeSwitching = false;
            showError("Preview: " + e.getMessage());
        }
    }

    private void startRecording() {
        if (cameraDevice == null || recording || recordingStarting) return;
        try {
            prepareRecorder();
            recordingStarting = true;
            startRecordSession(true);
        } catch (Exception e) {
            recordingStarting = false;
            safeResetRecorder();
            showError("Record setup: " + e.getMessage());
            startPreviewSession();
        }
    }

    private void startRecordSession(boolean startRecorderAfterConfigure) {
        if (cameraDevice == null || recorder == null || !textureView.isAvailable()) return;
        try {
            closeSessionOnly();
            SurfaceTexture st = textureView.getSurfaceTexture();
            if (st == null) throw new IllegalStateException("Preview surface unavailable");
            st.setDefaultBufferSize(1920, 1080);
            Surface preview = new Surface(st);
            Surface recordSurface = recorder.getSurface();

            repeatingBuilder = cameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_RECORD);
            repeatingBuilder.addTarget(preview);
            repeatingBuilder.addTarget(recordSurface);
            configureCommonRequest(repeatingBuilder);

            List<Surface> outputs = new ArrayList<>();
            outputs.add(preview);
            outputs.add(recordSurface);
            createSession(outputs, startRecorderAfterConfigure);
        } catch (Exception e) {
            if (startRecorderAfterConfigure) recordingStarting = false;
            showError("Record session: " + e.getMessage());
        }
    }

    private void createSession(List<Surface> outputs, boolean startRecorderAfterConfigure) throws CameraAccessException {
        cameraDevice.createCaptureSession(outputs, new CameraCaptureSession.StateCallback() {
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
                    runOnUiThread(() -> {
                        applyExtraPreviewCrop();
                        finishTransitionOverlay();
                    });
                } catch (Exception e) {
                    if (startRecorderAfterConfigure) recordingStarting = false;
                    showError("Session start: " + e.getMessage());
                }
            }

            @Override public void onConfigureFailed(CameraCaptureSession session) {
                routeSwitching = false;
                runOnUiThread(MainActivity.this::finishTransitionOverlay);
                showError("Camera HAL rejected " + (activeTele ? "telephoto" : "main") + " session.");
            }
        }, cameraHandler);
    }

    private void setDesiredZoom(float uiZoom, boolean syncSlider) {
        float z = Math.max(UI_MIN_ZOOM, Math.min(UI_MAX_ZOOM, uiZoom));
        if (z < 10f) z = Math.round(z * 10f) / 10f;
        else z = Math.round(z * 2f) / 2f;
        requestedUiZoom = z;

        zoomLiveView.setText(formatZoom(z));
        if (syncSlider && zoomSlider != null) zoomSlider.setZoom(z);

        boolean shouldTele = z >= TELE_HANDOFF && teleCameraId != null && !teleDirectOpenFailed;
        if (shouldTele != activeTele && !routeSwitching && cameraHandler != null) {
            prepareTransitionOverlay();
            routeSwitching = true;
            cameraHandler.post(() -> switchCameraDevice(shouldTele));
            return;
        }

        applyExtraPreviewCrop();
        scheduleZoomApply();
    }

    private void switchCameraDevice(boolean toTele) {
        try {
            closeSessionOnly();
            if (cameraDevice != null) {
                cameraDevice.close();
                cameraDevice = null;
            }
            openRoute(toTele);
        } catch (Exception e) {
            routeSwitching = false;
            runOnUiThread(this::finishTransitionOverlay);
            showError("Lens switch: " + e.getMessage());
        }
    }

    private void scheduleZoomApply() {
        if (cameraHandler == null) return;
        cameraHandler.removeCallbacks(applyZoomRunnable);
        cameraHandler.postDelayed(applyZoomRunnable, 8);
    }

    private void applyZoomToRepeatingRequest() {
        if (repeatingBuilder == null || captureSession == null || currentChars == null) return;
        try {
            setZoomOnBuilder(repeatingBuilder);
            captureSession.setRepeatingRequest(repeatingBuilder.build(), null, cameraHandler);
        } catch (Exception e) {
            showError("Zoom: " + e.getMessage());
        }
    }

    private void configureCommonRequest(CaptureRequest.Builder b) {
        b.set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO);
        int[] afModes = currentChars.get(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES);
        if (contains(afModes, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO)) {
            b.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO);
        }
        Range<Integer> fps = chooseFpsRange(currentChars, selectedFps);
        if (fps != null) b.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, fps);
        enableBestStabilization(b, currentChars);
        setZoomOnBuilder(b);
    }

    private void setZoomOnBuilder(CaptureRequest.Builder b) {
        if (currentChars == null) return;

        float desired;
        float min;
        float max;

        if (activeTele) {
            desired = Math.max(1f, requestedUiZoom / TELE_HANDOFF);
            min = teleMinZoom;
            max = teleMaxZoom;
        } else {
            desired = requestedUiZoom;
            min = logicalMinZoom;
            max = logicalMaxZoom;
        }

        float hardwareZoom = Math.max(min, Math.min(desired, max));
        Range<Float> range = currentChars.get(CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE);
        if (range != null) {
            float z = Math.max(range.getLower(), Math.min(hardwareZoom, range.getUpper()));
            b.set(CaptureRequest.CONTROL_ZOOM_RATIO, z);
        } else {
            Rect active = currentChars.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE);
            if (active != null) {
                b.set(CaptureRequest.SCALER_CROP_REGION, cropForZoom(active, Math.max(1f, hardwareZoom)));
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

    private Range<Integer> chooseFpsRange(CameraCharacteristics chars, int wanted) {
        Range<Integer>[] ranges = chars.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES);
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

    private void enableBestStabilization(CaptureRequest.Builder b, CameraCharacteristics chars) {
        int[] eis = chars.get(CameraCharacteristics.CONTROL_AVAILABLE_VIDEO_STABILIZATION_MODES);
        if (contains(eis, CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_PREVIEW_STABILIZATION)) {
            b.set(CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE,
                    CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_PREVIEW_STABILIZATION);
        } else if (contains(eis, CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_ON)) {
            b.set(CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE,
                    CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_ON);
        }

        int[] ois = chars.get(CameraCharacteristics.LENS_INFO_AVAILABLE_OPTICAL_STABILIZATION);
        if (contains(ois, CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE_ON)) {
            try {
                b.set(CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE,
                        CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE_ON);
            } catch (Exception ignored) {}
        }
    }

    private boolean contains(int[] values, int wanted) {
        if (values == null) return false;
        for (int v : values) if (v == wanted) return true;
        return false;
    }

    private void applyExtraPreviewCrop() {
        if (textureView == null) return;

        float hardwareUiMax;
        if (activeTele) {
            hardwareUiMax = TELE_HANDOFF * Math.max(1f, teleMaxZoom);
        } else {
            hardwareUiMax = Math.max(1f, logicalMaxZoom);
        }

        float extra = Math.max(1f, requestedUiZoom / hardwareUiMax);
        float cx = textureView.getWidth() / 2f;
        float cy = textureView.getHeight() / 2f;
        Matrix m = new Matrix();
        m.setScale(extra, extra, cx, cy);
        textureView.setTransform(m);
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
                }).start(), 80);
    }

    private void prepareRecorder() throws IOException {
        recorder = new MediaRecorder();
        recorder.setAudioSource(MediaRecorder.AudioSource.MIC);
        recorder.setVideoSource(MediaRecorder.VideoSource.SURFACE);
        recorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4);
        recorder.setVideoEncoder(supportsHevc ? MediaRecorder.VideoEncoder.HEVC : MediaRecorder.VideoEncoder.H264);
        recorder.setVideoSize(selectedSize.getWidth(), selectedSize.getHeight());
        recorder.setVideoFrameRate(selectedFps);
        int videoBitrate;
        if (selectedSize.getWidth() >= 7600) videoBitrate = 180_000_000;
        else if (selectedSize.getWidth() >= 3800) videoBitrate = 100_000_000;
        else if (selectedSize.getWidth() >= 1900) videoBitrate = 40_000_000;
        else videoBitrate = 20_000_000;
        recorder.setVideoEncodingBitRate(videoBitrate);
        recorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC);
        recorder.setAudioSamplingRate(48_000);
        recorder.setAudioEncodingBitRate(192_000);
        recorder.setOrientationHint(computeOrientationHint());

        ContentValues values = new ContentValues();
        String resolutionLabel;
        if (selectedSize.getWidth() >= 7600) resolutionLabel = "8K";
        else if (selectedSize.getWidth() >= 3800) resolutionLabel = "4K";
        else if (selectedSize.getWidth() >= 1900) resolutionLabel = "1080P";
        else if (selectedSize.getWidth() >= 1200) resolutionLabel = "720P";
        else resolutionLabel = selectedSize.getWidth() + "x" + selectedSize.getHeight();
        values.put(MediaStore.Video.Media.DISPLAY_NAME,
                "X100_" + resolutionLabel + "_" + selectedFps + "_" +
                        System.currentTimeMillis() + ".mp4");
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

    private void closeCameraFully() {
        if (recording || recordingStarting) stopRecording();
        closeSessionOnly();
        if (cameraDevice != null) {
            try { cameraDevice.close(); } catch (Exception ignored) {}
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
            if (textureView != null && textureView.isAvailable()) discoverAndOpen();
        }
    }

    @Override protected void onPause() {
        closeCameraFully();
        stopCameraThread();
        super.onPause();
    }

    private final class ZoomSliderView extends View {
        private final Paint linePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint accentPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint knobPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private float zoom = 1f;

        ZoomSliderView(Context c) {
            super(c);
            linePaint.setColor(0xFFE8E8E8);
            linePaint.setStrokeWidth(dp(2));
            accentPaint.setColor(0xFFFFC928);
            accentPaint.setStrokeWidth(dp(3));
            knobPaint.setStyle(Paint.Style.FILL);
            knobPaint.setColor(0xFF7A7A7A);
            textPaint.setColor(Color.WHITE);
            textPaint.setTextAlign(Paint.Align.CENTER);
            textPaint.setTextSize(dp(10));
            setLayerType(View.LAYER_TYPE_SOFTWARE, null);
        }

        void setZoom(float z) {
            zoom = Math.max(UI_MIN_ZOOM, Math.min(UI_MAX_ZOOM, z));
            invalidate();
        }

        @Override protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            float left = dp(10);
            float right = getWidth() - dp(10);
            float y = getHeight() * 0.55f;
            float neutralX = xForZoom(1f, left, right);
            float knobX = xForZoom(zoom, left, right);

            canvas.drawLine(left, y, right, y, linePaint);
            canvas.drawLine(neutralX, y, knobX, y, accentPaint);

            float[] marks = {0.6f, 1f, 2f, 3f, 10f, 30f, 50f};
            for (float m : marks) {
                float x = xForZoom(m, left, right);
                canvas.drawLine(x, y - dp(5), x, y + dp(5), linePaint);
            }

            canvas.drawCircle(knobX, y, dp(15), knobPaint);
            canvas.drawCircle(knobX, y, dp(13), linePaint);

            textPaint.setColor(0xFF111111);
            textPaint.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
            canvas.drawText(formatZoom(zoom), knobX, y + dp(4), textPaint);
        }

        private float xForZoom(float z, float left, float right) {
            float a = (float) Math.log(UI_MIN_ZOOM);
            float b = (float) Math.log(UI_MAX_ZOOM);
            float p = ((float) Math.log(Math.max(UI_MIN_ZOOM, z)) - a) / (b - a);
            return left + p * (right - left);
        }

        private float zoomForX(float x, float left, float right) {
            float p = Math.max(0f, Math.min(1f, (x - left) / (right - left)));
            float a = (float) Math.log(UI_MIN_ZOOM);
            float b = (float) Math.log(UI_MAX_ZOOM);
            return (float) Math.exp(a + p * (b - a));
        }

        @Override public boolean onTouchEvent(MotionEvent event) {
            if (event.getAction() == MotionEvent.ACTION_DOWN ||
                    event.getAction() == MotionEvent.ACTION_MOVE) {
                float left = dp(10);
                float right = getWidth() - dp(10);
                float raw = zoomForX(event.getX(), left, right);
                setDesiredZoom(raw, false);
                setZoom(requestedUiZoom);
                return true;
            }
            return event.getAction() == MotionEvent.ACTION_UP || super.onTouchEvent(event);
        }
    }
}
