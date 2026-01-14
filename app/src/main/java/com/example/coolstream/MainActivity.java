package com.example.coolstream; // adjust to your package

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.Switch;
import android.widget.TextView;

import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

/**
 * Minimal Activity:
 * - requests CAMERA and POST_NOTIFICATIONS (API 33+) at runtime
 * - starts/stops CameraService via intents
 * - toggles front/back camera preference
 */
public class MainActivity extends Activity {
    private static final int REQ_CAMERA     = 101;
    private static final int REQ_NOTIF      = 102;

    private Switch switchCamera;
    private Button btnStart, btnStop;
    private TextView tvStatus;

    @Override
    protected void onCreate(Bundle s) {
        super.onCreate(s);
        setContentView(R.layout.activity_main);

        switchCamera = findViewById(R.id.switch_camera);
        btnStart = findViewById(R.id.btn_start_server);
        btnStop = findViewById(R.id.btn_stop_server);
        tvStatus = findViewById(R.id.tv_status);

        // initialize UI state
        switchCamera.setChecked(false); // default: back camera
        updateStatusText();

        switchCamera.setOnCheckedChangeListener((buttonView, isChecked) -> {
            // inform service about camera preference
            Intent i = new Intent(this, CameraService.class);
            i.setAction(CameraService.ACTION_SET_CAMERA);
            i.putExtra(CameraService.EXTRA_USE_FRONT, isChecked);
            // startService is fine for sending control intents
            startService(i);
            updateStatusText();
        });

        btnStart.setOnClickListener(v -> {
            // ensure permissions then start service (server)
            ensurePermissionsAndStart();
        });

        btnStop.setOnClickListener(v -> {
            Intent i = new Intent(this, CameraService.class);
            i.setAction(CameraService.ACTION_STOP_SERVER);
            startService(i);
            // update text (service remains running but server stopped)
            updateStatusText();
        });

        // Kick off permission flow (do not automatically start the service here)
        if (!hasCameraPermission()) {
            ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.CAMERA}, REQ_CAMERA);
        } else {
            // camera granted; ensure notification permission (if needed) before starting service
            ensureNotificationPermissionIfNeeded();
        }
    }

    // Called when Start Server button pressed (or when we have permissions)
    private void ensurePermissionsAndStart() {
        if (!hasCameraPermission()) {
            ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.CAMERA}, REQ_CAMERA);
            return;
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !hasNotificationPermission()) {
            ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.POST_NOTIFICATIONS}, REQ_NOTIF);
            return;
        }
        // All required permissions are granted -> start the service (server)
        Intent i = new Intent(this, CameraService.class);
        i.setAction(CameraService.ACTION_START_SERVER);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(i);
        } else {
            startService(i);
        }
        updateStatusText();
    }

    private boolean hasCameraPermission() {
        return ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED;
    }

    private boolean hasNotificationPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return true;
        return ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED;
    }

    private void ensureNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !hasNotificationPermission()) {
            ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.POST_NOTIFICATIONS}, REQ_NOTIF);
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ_CAMERA) {
            boolean ok = grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED;
            if (!ok) {
                // camera denied -> cannot proceed, close activity
                finish();
                return;
            }
            // camera granted -> check notification permission next (if needed)
            ensureNotificationPermissionIfNeeded();
        } else if (requestCode == REQ_NOTIF) {
            // whether granted or not, proceed to let user start server.
            // On some OEMs startForeground may still fail if notification permission is denied,
            // but we attempted to request it. For now we simply continue.
            // You can warn the user here if needed.
        }
        // update UI status in case permissions changed
        updateStatusText();
    }

    private void updateStatusText() {
        boolean srv = CameraService.isServerRunning();
        boolean camActive = CameraService.isCameraActive();
        String cam = switchCamera.isChecked() ? "front" : "back";
        String txt = "Service: running, server: " + (srv ? "running" : "stopped") + ", camera: " + cam + (camActive ? " (active)" : "");
        tvStatus.setText(txt);
    }

    @Override
    protected void onResume() {
        super.onResume();
        // refresh status every resume
        updateStatusText();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
    }
}