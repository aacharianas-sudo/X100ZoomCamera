package com.anas.x100zoom;

import android.Manifest;
import android.app.Activity;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.Color;
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
import android.view.Surface;
import android.view.TextureView;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.io.FileDescriptor;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

public class MainActivity extends Activity {
    private static final int REQ_PERMS = 77;
    private static final Size UHD = new Size(3840, 2160);
    private static final int TARGET_FPS = 60;
    private static final int VIDEO_BITRATE = 100_000_000;

    private static final float UI_TELE_NATIVE = 3.0f;
    private static final String LENS_ULTRA = "ULTRA";
    private static final String LENS_MAIN = "MAIN";
    private static final String LENS_TELE = "TELE";

    private TextureView textureView;
    private TextView statusView;
    private TextView timerView;
    private Button recordButton;
    private final Map<Float, Button> zoomButtons = new HashMap<>();

    private HandlerThread cameraThread;
    private Handler cameraHandler;
    private CameraManager cameraManager;
    private CameraDevice cameraDevice;
    private CameraCaptureSession captureSession;
    private CaptureRequest.Builder repeatingBuilder;

    private CameraCharacteristics logicalCharacteristics;
    private String logicalCameraId;
    private String ultraPhysicalId;
    private String mainPhysicalId;
    private String telePhysicalId;
    private String activePhysicalId;
    private String activeLensType = LENS_MAIN;

    private MediaRecorder recorder;
    private boolean recording = false;
    private boolean recordingStarting = false;
    private Uri outputUri;
    private android.os.ParcelFileDescriptor outputPfd;

    private float requestedUiZoom = 1.0f;
    private float logicalMinZoom = 1.0f;
    private float logicalMaxZoom = 10.0f;
    private boolean supports4k60 = false;
    private boolean supportsHevc = false;
    private int sensorOrientation = 90;
    private long recordStartedAtMs = 0L;

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
        final float focalLength;
        LensInfo(String id, float focalLength) {
            this.id = id;
            this.focalLength = focalLength;
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

        textureView = new TextureView(this);
        textureView.setSurfaceTextureListener(textureListener);
        root.addView(textureView, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));

        LinearLayout top = new LinearLayout(this);
        top.setGravity(Gravity.CENTER_VERTICAL);
        top.setPadding(dp(18), dp(12), dp(18), dp(12));
        top.setBackgroundColor(0xAA000000);

        TextView left = new TextView(this);
        left.setText("VIDEO");
        left.setTextColor(0xFFDDDDDD);
        left.setTextSize(13f);
        left.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
        top.addView(left, new LinearLayout.LayoutParams(0, dp(54), 1f));

        TextView modeView = new TextView(this);
        modeView.setText("4K\n60");
        modeView.setTextColor(Color.WHITE);
        modeView.setTextSize(12f);
        modeView.setGravity(Gravity.CENTER);
        modeView.setTypeface(null, android.graphics.Typeface.BOLD);
        modeView.setBackground(rounded(0xAA222222, 8));
        top.addView(modeView, new LinearLayout.LayoutParams(dp(58), dp(48)));

        TextView right = new TextView(this);
        right.setText("⚙");
        right.setTextColor(Color.WHITE);
        right.setTextSize(25f);
        right.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
        top.addView(right, new LinearLayout.LayoutParams(0, dp(54), 1f));

        FrameLayout.LayoutParams topLp = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, dp(78));
        topLp.gravity = Gravity.TOP;
        root.addView(top, topLp);

        statusView = new TextView(this);
        statusView.setTextColor(Color.WHITE);
        statusView.setTextSize(12f);
        statusView.setPadding(dp(10), dp(6), dp(10), dp(6));
        statusView.setGravity(Gravity.CENTER);
        statusView.setBackground(rounded(0x77000000, 12));
        statusView.setText("Starting camera…");
        FrameLayout.LayoutParams statusLp = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT);
        statusLp.gravity = Gravity.TOP | Gravity.CENTER_HORIZONTAL;
        statusLp.topMargin = dp(88);
        root.addView(statusView, statusLp);

        LinearLayout bottom = new LinearLayout(this);
        bottom.setOrientation(LinearLayout.VERTICAL);
        bottom.setGravity(Gravity.CENTER_HORIZONTAL);
        bottom.setPadding(dp(10), dp(12), dp(10), dp(24));
        bottom.setBackgroundColor(0xAA000000);

        HorizontalScrollView scroll = new HorizontalScrollView(this);
        scroll.setHorizontalScrollBarEnabled(false);
        scroll.setFillViewport(true);
        LinearLayout zoomRow = new LinearLayout(this);
        zoomRow.setGravity(Gravity.CENTER);
        zoomRow.setPadding(dp(8), 0, dp(8), 0);

        float[] zooms = {0.6f, 1f, 2f, 3f, 5f, 10f, 20f, 30f};
        for (float z : zooms) {
            Button b = new Button(this);
            b.setAllCaps(false);
            b.setText(formatZoom(z));
            b.setTextSize(14f);
            b.setTextColor(z == 1f ? 0xFFFFD54F : Color.WHITE);
            b.setPadding(0, 0, 0, 0);
            b.setMinWidth(0);
            b.setMinimumWidth(0);
            b.setMinHeight(0);
            b.setMinimumHeight(0);
            b.setBackground(circle(z == 1f ? 0xAA332C00 : 0x66000000));
            b.setOnClickListener(v -> setDesiredZoom(z));
            LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(dp(52), dp(52));
            blp.setMargins(dp(3), 0, dp(3), 0);
            zoomRow.addView(b, blp);
            zoomButtons.put(z, b);
        }
        scroll.addView(zoomRow, new HorizontalScrollView.LayoutParams(
                HorizontalScrollView.LayoutParams.WRAP_CONTENT, dp(58)));
        bottom.addView(scroll, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(62)));

        timerView = new TextView(this);
        timerView.setText("00:00");
        timerView.setTextColor(Color.WHITE);
        timerView.setTextSize(14f);
        timerView.setGravity(Gravity.CENTER);
        timerView.setVisibility(View.INVISIBLE);
        bottom.addView(timerView, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(28)));

        FrameLayout recordWrap = new FrameLayout(this);
        LinearLayout.LayoutParams rwl = new LinearLayout.LayoutParams(dp(104), dp(104));
        rwl.topMargin = dp(2);
        bottom.addView(recordWrap, rwl);

        View outer = new View(this);
        GradientDrawable outerShape = circle(Color.TRANSPARENT);
        outerShape.setStroke(dp(4), Color.WHITE);
        outer.setBackground(outerShape);
        FrameLayout.LayoutParams outerLp = new FrameLayout.LayoutParams(dp(88), dp(88));
        outerLp.gravity = Gravity.CENTER;
        recordWrap.addView(outer, outerLp);

        recordButton = new Button(this);
        recordButton.setText("");
        recordButton.setBackground(circle(0xFFFF453A));
        recordButton.setOnClickListener(v -> {
            if (recording || recordingStarting) stopRecording(); else startRecording();
        });
        FrameLayout.LayoutParams recLp = new FrameLayout.LayoutParams(dp(72), dp(72));
        recLp.gravity = Gravity.CENTER;
        recordWrap.addView(recordButton, recLp);

        TextView hint = new TextView(this);
        hint.setText("0.6× ultra  •  1×–2× main  •  3×+ tele");
        hint.setTextColor(0xFFBDBDBD);
        hint.setTextSize(11f);
        hint.setGravity(Gravity.CENTER);
        bottom.addView(hint, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(24)));

        FrameLayout.LayoutParams bottomLp = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT);
        bottomLp.gravity = Gravity.BOTTOM;
        root.addView(bottom, bottomLp);
        setContentView(root);
    }

    private String formatZoom(float z) {
        if (Math.abs(z - Math.round(z)) < 0.01f) return String.format(Locale.US, "%.0f×", z);
        return String.format(Locale.US, "%.1f×", z);
    }

    private void updateZoomButtons() {
        runOnUiThread(() -> {
            for (Map.Entry<Float, Button> e : zoomButtons.entrySet()) {
                boolean selected = Math.abs(e.getKey() - requestedUiZoom) < 0.01f;
                e.getValue().setTextColor(selected ? 0xFFFFD54F : Color.WHITE);
                e.getValue().setBackground(circle(selected ? 0xAA332C00 : 0x66000000));
            }
        });
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
        cameraThread = new HandlerThread("X100CameraV2");
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
            classifyPhysicalLenses();
            activeLensType = LENS_MAIN;
            activePhysicalId = mainPhysicalId;
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
            if (zr != null) score += zr.getUpper();
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
    }

    private void classifyPhysicalLenses() {
        ultraPhysicalId = null;
        mainPhysicalId = null;
        telePhysicalId = null;
        List<LensInfo> lensInfos = new ArrayList<>();
        Set<String> physical = logicalCharacteristics.getPhysicalCameraIds();
        for (String pid : physical) {
            try {
                CameraCharacteristics pc = cameraManager.getCameraCharacteristics(pid);
                float[] focals = pc.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS);
                float focal = (focals != null && focals.length > 0) ? focals[0] : Float.NaN;
                if (!Float.isNaN(focal)) lensInfos.add(new LensInfo(pid, focal));
            } catch (Exception ignored) {}
        }
        lensInfos.sort(Comparator.comparingDouble(a -> a.focalLength));
        if (lensInfos.size() >= 3) {
            ultraPhysicalId = lensInfos.get(0).id;
            mainPhysicalId = lensInfos.get(lensInfos.size() / 2).id;
            telePhysicalId = lensInfos.get(lensInfos.size() - 1).id;
        } else if (lensInfos.size() == 2) {
            mainPhysicalId = lensInfos.get(0).id;
            telePhysicalId = lensInfos.get(1).id;
        } else if (lensInfos.size() == 1) {
            mainPhysicalId = lensInfos.get(0).id;
        }
        final String mapText = "IDs U:" + safeId(ultraPhysicalId) + " M:" + safeId(mainPhysicalId) + " T:" + safeId(telePhysicalId);
        runOnUiThread(() -> statusView.setText("1× MAIN • " + mapText));
    }

    private String safeId(String id) {
        return id == null ? "?" : id;
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
            closeSession();
            SurfaceTexture st = textureView.getSurfaceTexture();
            if (st == null) return;
            st.setDefaultBufferSize(1920, 1080);
            Surface preview = new Surface(st);
            repeatingBuilder = cameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
            repeatingBuilder.addTarget(preview);
            configureCommonRequest(repeatingBuilder, false);
            createSession(Collections.singletonList(preview), false, false);
        } catch (Exception e) {
            showError("Preview setup: " + e.getMessage());
        }
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
            createSession(outputs, true, true);
        } catch (Exception e) {
            recordingStarting = false;
            showError("Record setup: " + e.getMessage());
            safeResetRecorder();
            startPreviewSession();
        }
    }

    private void createSession(List<Surface> outputs, boolean forVideo, boolean startRecorderAfterConfigure) throws CameraAccessException {
        final CameraCaptureSession.StateCallback[] holder = new CameraCaptureSession.StateCallback[1];
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
                    updateStatus();
                } catch (Exception e) {
                    if (startRecorderAfterConfigure) {
                        recordingStarting = false;
                        safeResetRecorder();
                    }
                    showError("Session start: " + e.getMessage());
                }
            }

            @Override public void onConfigureFailed(CameraCaptureSession session) {
                if (activePhysicalId != null) {
                    String failed = activePhysicalId;
                    activePhysicalId = null;
                    toast("Physical lens " + failed + " rejected; retrying logical camera.");
                    try {
                        createNormalSession(outputs, holder[0]);
                    } catch (Exception e) {
                        failSession(forVideo, startRecorderAfterConfigure, e.getMessage());
                    }
                } else {
                    failSession(forVideo, startRecorderAfterConfigure, "Camera HAL rejected the session");
                }
            }
        };
        holder[0] = callback;

        if (activePhysicalId != null) {
            try {
                List<OutputConfiguration> configs = new ArrayList<>();
                for (Surface s : outputs) {
                    OutputConfiguration oc = new OutputConfiguration(s);
                    oc.setPhysicalCameraId(activePhysicalId);
                    configs.add(oc);
                }
                cameraDevice.createCaptureSessionByOutputConfigurations(configs, callback, cameraHandler);
                return;
            } catch (Exception e) {
                toast("Physical route unavailable: " + e.getClass().getSimpleName());
            }
        }
        createNormalSession(outputs, callback);
    }

    private void createNormalSession(List<Surface> outputs, CameraCaptureSession.StateCallback callback) throws CameraAccessException {
        cameraDevice.createCaptureSession(outputs, callback, cameraHandler);
    }

    private void failSession(boolean forVideo, boolean wasStartingRecorder, String reason) {
        if (wasStartingRecorder) {
            recordingStarting = false;
            safeResetRecorder();
        }
        showError((forVideo ? "Video" : "Preview") + " session failed: " + reason);
        if (!recording) startPreviewSession();
    }

    private void rebuildSessionForLensSwitch() {
        if (cameraDevice == null) return;
        try {
            closeSession();
            SurfaceTexture st = textureView.getSurfaceTexture();
            if (st == null) return;
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
                createSession(outputs, true, false);
            } else {
                repeatingBuilder = cameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
                repeatingBuilder.addTarget(preview);
                configureCommonRequest(repeatingBuilder, false);
                createSession(Collections.singletonList(preview), false, false);
            }
        } catch (Exception e) {
            showError("Lens switch: " + e.getMessage());
        }
    }

    private void setDesiredZoom(float uiZoom) {
        requestedUiZoom = uiZoom;
        String targetLens = lensForZoom(uiZoom);
        String targetPhysical = physicalIdForLens(targetLens);
        boolean lensChanged = !targetLens.equals(activeLensType) || !sameId(targetPhysical, activePhysicalId);

        activeLensType = targetLens;
        activePhysicalId = targetPhysical;
        updateZoomButtons();
        updateStatus();

        if (lensChanged && cameraDevice != null) {
            cameraHandler.post(this::rebuildSessionForLensSwitch);
        } else {
            applyInternalZoom();
        }
    }

    private boolean sameId(String a, String b) {
        return a == null ? b == null : a.equals(b);
    }

    private String lensForZoom(float z) {
        if (z < 0.9f) return LENS_ULTRA;
        if (z < 3.0f) return LENS_MAIN;
        return LENS_TELE;
    }

    private String physicalIdForLens(String lens) {
        if (LENS_ULTRA.equals(lens)) return ultraPhysicalId;
        if (LENS_TELE.equals(lens)) return telePhysicalId;
        return mainPhysicalId;
    }

    private float internalZoomForCurrentLens() {
        float internal;
        if (LENS_ULTRA.equals(activeLensType)) {
            internal = 1.0f;
        } else if (LENS_TELE.equals(activeLensType)) {
            internal = requestedUiZoom / UI_TELE_NATIVE;
        } else {
            internal = requestedUiZoom;
        }
        return Math.max(1.0f, Math.min(internal, logicalMaxZoom));
    }

    private void applyInternalZoom() {
        if (repeatingBuilder == null || captureSession == null || logicalCharacteristics == null) return;
        try {
            setInternalZoomOnBuilder(repeatingBuilder, internalZoomForCurrentLens());
            captureSession.setRepeatingRequest(repeatingBuilder.build(), null, cameraHandler);
            updateStatus();
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
        setInternalZoomOnBuilder(b, internalZoomForCurrentLens());
    }

    private void setInternalZoomOnBuilder(CaptureRequest.Builder b, float internalZoom) {
        float z = Math.max(logicalMinZoom, Math.min(internalZoom, logicalMaxZoom));
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

    private void updateStatus() {
        final float internal = internalZoomForCurrentLens();
        final String pid = activePhysicalId == null ? "logical" : "ID " + activePhysicalId;
        final String text = formatZoom(requestedUiZoom) + "  " + activeLensType + " • " + pid +
                " • sensor crop " + String.format(Locale.US, "%.2f×", internal);
        runOnUiThread(() -> statusView.setText(text));
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
            statusView.setText("ERROR • " + s);
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
}
