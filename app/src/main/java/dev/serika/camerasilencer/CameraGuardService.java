package dev.serika.camerasilencer;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.hardware.camera2.CameraManager;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.util.Log;

import java.util.HashSet;
import java.util.Set;

public final class CameraGuardService extends Service {
    static final String ACTION_START = "dev.serika.camerasilencer.action.START";
    static final String ACTION_STOP = "dev.serika.camerasilencer.action.STOP";
    static final String ACTION_STATE = "dev.serika.camerasilencer.action.STATE";
    static final String EXTRA_RUNNING = "running";
    static final String EXTRA_SILENCING = "silencing";

    private static final String TAG = "CameraGuardService";
    private static final String CHANNEL_ID = "camera_guard";
    private static final int NOTIFICATION_ID = 1001;
    private static final String STATE_PREFS = "guard_state";

    private final Set<String> unavailableCameraIds = new HashSet<>();
    private HandlerThread callbackThread;
    private Handler callbackHandler;
    private CameraManager cameraManager;
    private AudioSilencer audioSilencer;
    private boolean registered;

    private final CameraManager.AvailabilityCallback availabilityCallback =
            new CameraManager.AvailabilityCallback() {
                @Override
                public void onCameraUnavailable(String cameraId) {
                    synchronized (unavailableCameraIds) {
                        unavailableCameraIds.add(cameraId);
                    }
                    Log.w(TAG, "Camera unavailable: " + cameraId);
                    updateSilencing();
                }

                @Override
                public void onCameraAvailable(String cameraId) {
                    synchronized (unavailableCameraIds) {
                        unavailableCameraIds.remove(cameraId);
                    }
                    Log.w(TAG, "Camera available: " + cameraId);
                    updateSilencing();
                }
            };

    public static void start(Context context) {
        Intent intent = new Intent(context, CameraGuardService.class).setAction(ACTION_START);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(intent);
        } else {
            context.startService(intent);
        }
    }

    public static void stop(Context context) {
        context.startService(new Intent(context, CameraGuardService.class).setAction(ACTION_STOP));
    }

    @Override
    public void onCreate() {
        super.onCreate();
        Log.w(TAG, "Service created");
        createNotificationChannel();
        audioSilencer = new AudioSilencer(this);
        AudioSilencer.restorePersistedSnapshot(this);
        cameraManager = (CameraManager) getSystemService(Context.CAMERA_SERVICE);
        callbackThread = new HandlerThread("camera-availability");
        callbackThread.start();
        callbackHandler = new Handler(callbackThread.getLooper());
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? ACTION_START : intent.getAction();
        Log.w(TAG, "onStartCommand action=" + action);
        if (ACTION_STOP.equals(action)) {
            stopGuard();
            stopSelf();
            return START_NOT_STICKY;
        }

        startForegroundCompat(buildNotification(false));
        startGuard();
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        stopGuard();
        if (callbackThread != null) {
            callbackThread.quitSafely();
            callbackThread = null;
        }
        callbackHandler = null;
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private void startGuard() {
        if (registered || cameraManager == null) {
            publishState();
            return;
        }

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                cameraManager.registerAvailabilityCallback(getMainExecutor(), availabilityCallback);
            } else {
                cameraManager.registerAvailabilityCallback(availabilityCallback, callbackHandler);
            }
            registered = true;
            publishState();
            Log.w(TAG, "Camera availability callback registered");
        } catch (RuntimeException e) {
            Log.e(TAG, "Failed to register camera availability callback", e);
            stopSelf();
        }
    }

    private void stopGuard() {
        if (registered && cameraManager != null) {
            try {
                cameraManager.unregisterAvailabilityCallback(availabilityCallback);
            } catch (RuntimeException e) {
                Log.w(TAG, "Failed to unregister camera callback", e);
            }
        }
        registered = false;

        synchronized (unavailableCameraIds) {
            unavailableCameraIds.clear();
        }
        if (audioSilencer != null) {
            audioSilencer.disable();
        }
        publishState();
    }

    private void updateSilencing() {
        boolean cameraInUse;
        synchronized (unavailableCameraIds) {
            cameraInUse = !unavailableCameraIds.isEmpty();
        }
        Log.w(TAG, "updateSilencing cameraInUse=" + cameraInUse
                + " unavailable=" + unavailableCameraIds);

        if (cameraInUse) {
            audioSilencer.enable();
        } else {
            audioSilencer.disable();
        }

        NotificationManager manager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (manager != null) {
            manager.notify(NOTIFICATION_ID, buildNotification(cameraInUse));
        }
        publishState();
    }

    private void publishState() {
        boolean active = audioSilencer != null && audioSilencer.isActive();
        getSharedPreferences(STATE_PREFS, MODE_PRIVATE)
                .edit()
                .putBoolean(EXTRA_RUNNING, registered)
                .putBoolean(EXTRA_SILENCING, active)
                .apply();

        Intent state = new Intent(ACTION_STATE)
                .setPackage(getPackageName())
                .putExtra(EXTRA_RUNNING, registered)
                .putExtra(EXTRA_SILENCING, active);
        sendBroadcast(state);
    }

    static boolean savedRunning(Context context) {
        return context.getApplicationContext()
                .getSharedPreferences(STATE_PREFS, Context.MODE_PRIVATE)
                .getBoolean(EXTRA_RUNNING, false);
    }

    static boolean savedSilencing(Context context) {
        return context.getApplicationContext()
                .getSharedPreferences(STATE_PREFS, Context.MODE_PRIVATE)
                .getBoolean(EXTRA_SILENCING, false);
    }

    private void startForegroundCompat(Notification notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                    NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            );
        } else {
            startForeground(NOTIFICATION_ID, notification);
        }
    }

    private Notification buildNotification(boolean cameraInUse) {
        Intent openIntent = new Intent(this, MainActivity.class);
        PendingIntent contentIntent = PendingIntent.getActivity(
                this,
                0,
                openIntent,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT
        );

        PendingIntent stopIntent = PendingIntent.getService(
                this,
                1,
                new Intent(this, CameraGuardService.class).setAction(ACTION_STOP),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT
        );

        String title = cameraInUse ? "Camera in use" : "Watching camera use";
        String text = cameraInUse
                ? "Audio is temporarily reduced"
                : "Audio will be reduced only while a camera is active";

        Notification.Builder builder = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? new Notification.Builder(this, CHANNEL_ID)
                : new Notification.Builder(this);

        return builder
                .setSmallIcon(android.R.drawable.ic_menu_camera)
                .setContentTitle(title)
                .setContentText(text)
                .setContentIntent(contentIntent)
                .setOngoing(true)
                .setShowWhen(false)
                .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Stop", stopIntent)
                .build();
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return;
        }

        NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID,
                "Camera guard",
                NotificationManager.IMPORTANCE_LOW
        );
        channel.setDescription("Shows when camera-use audio guarding is active");
        NotificationManager manager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (manager != null) {
            manager.createNotificationChannel(channel);
        }
    }
}
