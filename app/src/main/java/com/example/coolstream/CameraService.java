package com.example.coolstream;


import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.graphics.ImageFormat;
import android.graphics.Rect;
import android.graphics.YuvImage;
import android.hardware.Camera;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.PowerManager;
import android.util.Log;

import java.io.ByteArrayOutputStream;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

public class CameraService extends Service implements Camera.PreviewCallback {
    private static final String TAG = "CameraService";
    private android.graphics.SurfaceTexture previewSurfaceTexture = null;

    // Actions / extras for control intents
    public static final String ACTION_START_SERVER = "com.example.ipcamera.action.START_SERVER";
    public static final String ACTION_STOP_SERVER  = "com.example.ipcamera.action.STOP_SERVER";
    public static final String ACTION_SET_CAMERA  = "com.example.ipcamera.action.SET_CAMERA";
    public static final String EXTRA_USE_FRONT   = "extra_use_front";

    // Idle timeout after last client disconnect (ms)
    private static final long IDLE_TIMEOUT_MS = 30_000L;

    // State flags accessible to UI
    private static volatile boolean serverRunning = false;
    private static volatile boolean cameraActive = false;

    // Camera & frame storage
    private Camera camera;
    private final Object frameLock = new Object();
    private volatile byte[] latestJpeg = null;

    // HTTP MJPEG server
    private MjpegHttpServer server;
    private int cameraId = 0; // chosen camera id
    private boolean useFront = false;

    // client count
    private final AtomicInteger clientCount = new AtomicInteger(0);

    // locks & scheduler
    private PowerManager.WakeLock wakeLock;
    private WifiManager.WifiLock wifiLock;
    private ScheduledExecutorService scheduler;
    private ScheduledFuture<?> idleShutdownFuture;

    // camera thread
    private HandlerThread handlerThread;
    private Handler cameraHandler;

    @Override
    public void onCreate() {
        super.onCreate();
        startForegroundCompat();
        initHelpers();
    }

    private void initHelpers() {
        scheduler = Executors.newSingleThreadScheduledExecutor();
        handlerThread = new HandlerThread("camera-thread");
        handlerThread.start();
        cameraHandler = new Handler(handlerThread.getLooper());
        // don't start MJPEG server here - server starts when ACTION_START_SERVER received
    }

    private void startForegroundCompat() {
        String channelId = "ipcam_channel";
        String channelName = "IP Camera Service";

        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel chan = new NotificationChannel(channelId, channelName, NotificationManager.IMPORTANCE_LOW);
            chan.setShowBadge(false);
            chan.setSound(null, null);
            nm.createNotificationChannel(chan);
        }

        // Prefer your app drawable; fallback to system icon if not found
        int smallIconId = getResources().getIdentifier("ic_camera_notification", "drawable", getPackageName());
        if (smallIconId == 0) smallIconId = android.R.drawable.ic_menu_camera;

        Notification.Builder nb;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            nb = new Notification.Builder(this, channelId);
        } else {
            nb = new Notification.Builder(this);
        }

        nb.setContentTitle("IP Camera service")
                .setContentText("Ready (server stopped)")
                .setSmallIcon(smallIconId)   // **important**
                .setOngoing(true);

        // startForeground must be called while activity has notification permission on Android 13+
        try {
            startForeground(1, nb.build());
        } catch (Exception e) {
            // log to help debugging (don't crash the app here)
            Log.e(TAG, "startForeground failed", e);
            // fallback: try notify (best-effort)
            try { nm.notify(1, nb.build()); } catch (Exception ignored) {}
        }
    }



    // Intent control handling
    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null) return START_STICKY;
        String action = intent.getAction();
        if (action == null) return START_STICKY;

        switch (action) {
            case ACTION_START_SERVER:
                startMjpegServer();
                break;
            case ACTION_STOP_SERVER:
                stopMjpegServer();
                break;
            case ACTION_SET_CAMERA:
                boolean useFrontNow = intent.getBooleanExtra(EXTRA_USE_FRONT, false);
                setUseFrontCamera(useFrontNow);
                break;
            default:
                // ignore unknown
        }
        return START_STICKY;
    }

    // Start MJPEG server (listening). Does not open camera until a client connects.
    private synchronized void startMjpegServer() {
        if (serverRunning) return;
        server = new MjpegHttpServer(8080, this::getLatestJpeg);
        server.setClientListener(new MjpegHttpServer.ClientListener() {
            @Override public void onClientConnected() { clientConnected(); }
            @Override public void onClientDisconnected() { clientDisconnected(); }
        });
        server.start();
        serverRunning = true;
        updateNotification("Server running on port 8080");
        Log.i(TAG, "MJPEG server started");
    }

    // Stop listener. If you want immediate termination of existing clients, see note below.
    private synchronized void stopMjpegServer() {
        if (!serverRunning) return;
        if (server != null) {
            server.shutdown();
            server = null;
        }
        serverRunning = false;
        updateNotification("Server stopped");
        Log.i(TAG, "MJPEG server stopped");
        // stop camera immediately when server stopped
        scheduleCameraShutdownImmediate();
    }

    private void updateNotification(String text) {
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        String channelId = "ipcam_channel";

        // Ensure small icon present (same logic as startForegroundCompat)
        int smallIconId = getResources().getIdentifier("ic_camera_notification", "drawable", getPackageName());
        if (smallIconId == 0) smallIconId = android.R.drawable.ic_menu_camera;

        Notification.Builder nb;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            nb = new Notification.Builder(this, channelId);
        } else {
            nb = new Notification.Builder(this);
        }

        nb.setContentTitle("IP Camera service")
                .setContentText(text)
                .setSmallIcon(smallIconId)
                .setOngoing(true);

        try {
            nm.notify(1, nb.build());
        } catch (Exception e) {
            Log.e(TAG, "notify failed", e);
        }
    }


    // Called when a client connects to MJPEG server
    private void clientConnected() {
        int prev = clientCount.getAndIncrement();
        Log.i(TAG, "clientConnected -> count now " + (prev + 1));
        if (prev == 0) {
            cancelIdleShutdown();
            startPreviewAsync();
        }
    }

    // Called when a client disconnects
    private void clientDisconnected() {
        int now = clientCount.decrementAndGet();
        if (now < 0) clientCount.set(0);
        Log.i(TAG, "clientDisconnected -> count now " + Math.max(now, 0));
        if (now <= 0) scheduleIdleShutdown();
    }

    private void startPreviewAsync() {
        cameraHandler.post(() -> openCameraAndStartPreviewWithId(cameraId));
    }

    private void openCameraAndStartPreviewWithId(int camId) {
        if (camera != null) return;
        try {
            Log.i(TAG, "Opening camera id=" + camId);
            acquireLocks();
            camera = Camera.open(camId);

            // Create and attach a dummy SurfaceTexture to satisfy camera drivers that require a surface.
            try {
                if (previewSurfaceTexture == null) previewSurfaceTexture = new android.graphics.SurfaceTexture(0);
                camera.setPreviewTexture(previewSurfaceTexture);
            } catch (Exception e) {
                Log.w(TAG, "setPreviewTexture failed (driver may require SurfaceHolder): " + e);
                // continue — some devices may accept preview callbacks without texture
            }

            Camera.Parameters p = camera.getParameters();
            Camera.Size best = choosePreviewSize(p);
            p.setPreviewSize(best.width, best.height);
            p.setPreviewFormat(ImageFormat.NV21);
            camera.setParameters(p);

            int bufSize = best.width * best.height * ImageFormat.getBitsPerPixel(ImageFormat.NV21) / 8;
            byte[] buffer = new byte[bufSize];
            camera.addCallbackBuffer(buffer);
            camera.setPreviewCallbackWithBuffer(this);
            camera.startPreview();

            // log actual preview size (driver may adjust)
            Camera.Size actual = camera.getParameters().getPreviewSize();
            Log.i(TAG, "Camera preview started: " + actual.width + "x" + actual.height);
            cameraActive = true;
        } catch (Exception e) {
            Log.e(TAG, "Camera open/startPreview failed", e);
            releaseLocks();
            cameraActive = false;
            // cleanup if partially opened
            try {
                if (camera != null) {
                    camera.setPreviewCallbackWithBuffer(null);
                    camera.stopPreview();
                    camera.release();
                    camera = null;
                }
            } catch (Exception ignored) {}
        }
    }


    private Camera.Size choosePreviewSize(Camera.Parameters p) {
        for (Camera.Size s : p.getSupportedPreviewSizes()) {
            if (s.width == 640 && s.height == 480) return s;
        }
        // fallback to first supported
        return p.getSupportedPreviewSizes().get(0);
    }

    @Override
    public void onPreviewFrame(byte[] data, Camera camera) {
        Camera.Size s = camera.getParameters().getPreviewSize();
        try {
            YuvImage yuv = new YuvImage(data, ImageFormat.NV21, s.width, s.height, null);
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            yuv.compressToJpeg(new Rect(0,0,s.width,s.height), 60, baos);
            byte[] jpeg = baos.toByteArray();
            synchronized (frameLock) { latestJpeg = jpeg; }
        } catch (Exception e) {
            Log.e(TAG, "preview->jpeg failed", e);
        } finally {
            // recycle buffer
            try { camera.addCallbackBuffer(data); } catch (Exception ignored) {}
        }
    }

    private byte[] getLatestJpeg() {
        synchronized (frameLock) { return latestJpeg; }
    }

    private void scheduleIdleShutdown() {
        cancelIdleShutdown();
        idleShutdownFuture = scheduler.schedule(() -> {
            if (clientCount.get() == 0) {
                Log.i(TAG, "Idle timeout reached, stopping camera");
                cameraHandler.post(this::stopPreviewInternal);
            } else {
                Log.i(TAG, "Idle shutdown cancelled: clients reconnected");
            }
        }, IDLE_TIMEOUT_MS, TimeUnit.MILLISECONDS);
    }

    private void cancelIdleShutdown() {
        if (idleShutdownFuture != null && !idleShutdownFuture.isDone()) {
            idleShutdownFuture.cancel(false);
            idleShutdownFuture = null;
        }
    }

    // immediate shutdown (used when server stopped)
    private void scheduleCameraShutdownImmediate() {
        cancelIdleShutdown();
        cameraHandler.post(this::stopPreviewInternal);
    }

    private void stopPreviewInternal() {
        if (camera == null) return;
        try {
            camera.setPreviewCallbackWithBuffer(null);
            camera.stopPreview();
            camera.release();
        } catch (Exception e) { /* ignore */ }
        camera = null;
        latestJpeg = null;
        cameraActive = false;

        // release dummy surfaceTexture
        try {
            if (previewSurfaceTexture != null) {
                previewSurfaceTexture.release();
                previewSurfaceTexture = null;
            }
        } catch (Exception ignored) {}

        releaseLocks();
        Log.i(TAG, "Camera stopped");
    }


    private void acquireLocks() {
        try {
            PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
            if (wakeLock == null) {
                wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "ipcam:wake");
                wakeLock.setReferenceCounted(false);
            }
            if (!wakeLock.isHeld()) wakeLock.acquire(10*60*1000L);

            WifiManager wm = (WifiManager) getApplicationContext().getSystemService(WIFI_SERVICE);
            if (wifiLock == null) {
                wifiLock = wm.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "ipcam:wifi");
                wifiLock.setReferenceCounted(false);
            }
            if (!wifiLock.isHeld()) wifiLock.acquire();
        } catch (Exception e) {
            Log.w(TAG, "Failed to acquire locks: " + e);
        }
    }

    private void releaseLocks() {
        try { if (wakeLock != null && wakeLock.isHeld()) wakeLock.release(); } catch (Exception ignored) {}
        try { if (wifiLock != null && wifiLock.isHeld()) wifiLock.release(); } catch (Exception ignored) {}
    }

    // switch camera preference; restarts preview if active
    private void setUseFrontCamera(boolean useFrontNow) {
        this.useFront = useFrontNow;
        int id = findCameraId(useFrontNow);
        if (id != cameraId) {
            cameraId = id;
            Log.i(TAG, "cameraId changed to " + cameraId + " (useFront=" + useFrontNow + ")");
            if (cameraActive) restartCameraPreviewWithNewId();
        }
    }

    private int findCameraId(boolean front) {
        int cameraCount = Camera.getNumberOfCameras();
        Camera.CameraInfo info = new Camera.CameraInfo();
        for (int i = 0; i < cameraCount; i++) {
            Camera.getCameraInfo(i, info);
            if (front && info.facing == Camera.CameraInfo.CAMERA_FACING_FRONT) return i;
            if (!front && info.facing == Camera.CameraInfo.CAMERA_FACING_BACK) return i;
        }
        return 0;
    }

    private void restartCameraPreviewWithNewId() {
        cameraHandler.post(() -> {
            stopPreviewInternal();
            try { Thread.sleep(200); } catch (InterruptedException ignored) {}
            openCameraAndStartPreviewWithId(cameraId);
        });
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        try {
            stopMjpegServer();
        } catch (Exception ignored) {}
        cancelIdleShutdown();
        try { if (scheduler != null) scheduler.shutdownNow(); } catch (Exception ignored) {}
        cameraHandler.post(this::stopPreviewInternal);
        try { if (handlerThread != null) handlerThread.quitSafely(); } catch (Exception ignored) {}
        releaseLocks();
    }

    @Override
    public IBinder onBind(Intent intent) { return null; }

    // helpers for UI
    public static boolean isServerRunning() { return serverRunning; }
    public static boolean isCameraActive() { return cameraActive; }
}


