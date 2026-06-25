package dev.serika.camerasilencer;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.KeyguardManager;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ServiceInfo;
import android.hardware.camera2.CameraManager;
import android.media.AudioManager;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;

import java.util.HashSet;
import java.util.Set;

public final class CameraGuardService extends Service {
    static final String ACTION_START = "dev.serika.camerasilencer.action.START";
    static final String ACTION_START_FOR_CURRENT_MODE =
            "dev.serika.camerasilencer.action.START_FOR_CURRENT_MODE";
    static final String ACTION_STOP = "dev.serika.camerasilencer.action.STOP";
    static final String ACTION_STATE = "dev.serika.camerasilencer.action.STATE";
    static final String EXTRA_RUNNING = "running";
    static final String EXTRA_SILENCING = "silencing";
    static final String EXTRA_MODE = "mode";
    static final String EXTRA_CAMERA_IN_USE = "camera_in_use";
    static final String EXTRA_RINGER_SILENT = "ringer_silent";

    private static final String TAG = "CameraGuardService";
    private static final String CHANNEL_ID = "camera_guard";
    private static final int NOTIFICATION_ID = 1001;
    private static final String STATE_PREFS = "guard_state";
    private static final long SCREEN_ON_CAMERA_GRACE_MS = 2500L;

    private final Set<String> unavailableCameraIds = new HashSet<>();
    private Handler mainHandler;
    private HandlerThread callbackThread;
    private Handler callbackHandler;
    private CameraManager cameraManager;
    private AudioManager audioManager;
    private KeyguardManager keyguardManager;
    private AudioSilencer audioSilencer;
    private boolean registered;
    private boolean ringerReceiverRegistered;
    private boolean screenReceiverRegistered;
    private long lastScreenOnElapsedMillis = Long.MIN_VALUE;

    private final Runnable delayedSilencingUpdate = new Runnable() {
        @Override
        public void run() {
            updateSilencing();
        }
    };

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

    private final BroadcastReceiver ringerModeReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            Log.w(TAG, "Ringer mode changed");
            updateSilencing();
        }
    };

    private final BroadcastReceiver screenReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (Intent.ACTION_SCREEN_ON.equals(intent.getAction())) {
                lastScreenOnElapsedMillis = SystemClock.elapsedRealtime();
                Log.w(TAG, "Screen turned on");
                scheduleSilencingUpdate(SCREEN_ON_CAMERA_GRACE_MS + 50L);
                updateSilencing();
            }
        }
    };

    public static void start(Context context) {
        GuardSettings.setMode(context, GuardSettings.MODE_MANUAL);
        GuardSettings.setManualRunning(context, true);
        startServiceCompat(context, ACTION_START);
    }

    static void startForCurrentMode(Context context) {
        if (GuardSettings.shouldRunService(context)) {
            startServiceCompat(context, ACTION_START_FOR_CURRENT_MODE);
        }
    }

    public static void stop(Context context) {
        GuardSettings.setMode(context, GuardSettings.MODE_MANUAL);
        GuardSettings.setManualRunning(context, false);
        context.startService(new Intent(context, CameraGuardService.class).setAction(ACTION_STOP));
    }

    private static void startServiceCompat(Context context, String action) {
        Intent intent = new Intent(context, CameraGuardService.class).setAction(action);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(intent);
        } else {
            context.startService(intent);
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        Log.w(TAG, "Service created");
        createNotificationChannel();
        mainHandler = new Handler(Looper.getMainLooper());
        audioSilencer = new AudioSilencer(this);
        AudioSilencer.restorePersistedSnapshot(this);
        cameraManager = (CameraManager) getSystemService(Context.CAMERA_SERVICE);
        audioManager = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
        keyguardManager = (KeyguardManager) getSystemService(Context.KEYGUARD_SERVICE);
        callbackThread = new HandlerThread("camera-availability");
        callbackThread.start();
        callbackHandler = new Handler(callbackThread.getLooper());
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? ACTION_START : intent.getAction();
        Log.w(TAG, "onStartCommand action=" + action);
        if (ACTION_STOP.equals(action)) {
            GuardSettings.setMode(this, GuardSettings.MODE_MANUAL);
            GuardSettings.setManualRunning(this, false);
            stopGuard();
            stopSelf();
            return START_NOT_STICKY;
        }

        if (!GuardSettings.shouldRunService(this)) {
            stopGuard();
            stopSelf();
            return START_NOT_STICKY;
        }

        startForegroundCompat(buildNotification(false, false));
        startGuard();
        updateSilencing();
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
        if (mainHandler != null) {
            mainHandler.removeCallbacks(delayedSilencingUpdate);
            mainHandler = null;
        }
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
            registerRingerModeReceiver();
            registerScreenReceiver();
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
        unregisterRingerModeReceiver();
        unregisterScreenReceiver();
        if (mainHandler != null) {
            mainHandler.removeCallbacks(delayedSilencingUpdate);
        }

        synchronized (unavailableCameraIds) {
            unavailableCameraIds.clear();
        }
        if (audioSilencer != null) {
            audioSilencer.shutdown();
        }
        publishState();
    }

    private void updateSilencing() {
        boolean cameraInUse = isCameraInUse();
        boolean shouldSilenceForMode = shouldSilenceForCurrentMode();
        boolean deferScreenOnCamera = cameraInUse
                && shouldSilenceForMode
                && audioSilencer != null
                && !audioSilencer.isActive()
                && shouldDeferScreenOnCameraUse();
        boolean shouldSilence = cameraInUse && shouldSilenceForMode && !deferScreenOnCamera;
        Log.w(TAG, "updateSilencing cameraInUse=" + cameraInUse
                + " shouldSilence=" + shouldSilence
                + " deferScreenOnCamera=" + deferScreenOnCamera
                + " mode=" + GuardSettings.getMode(this)
                + " unavailable=" + unavailableCameraIds);

        if (shouldSilence) {
            audioSilencer.enable();
        } else {
            audioSilencer.disable();
        }

        NotificationManager manager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (manager != null) {
            manager.notify(NOTIFICATION_ID, buildNotification(cameraInUse, shouldSilence));
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
                .putExtra(EXTRA_SILENCING, active)
                .putExtra(EXTRA_MODE, GuardSettings.getMode(this))
                .putExtra(EXTRA_CAMERA_IN_USE, isCameraInUse())
                .putExtra(EXTRA_RINGER_SILENT, isRingerSilent());
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

    static void clearSavedState(Context context) {
        context.getApplicationContext()
                .getSharedPreferences(STATE_PREFS, Context.MODE_PRIVATE)
                .edit()
                .putBoolean(EXTRA_RUNNING, false)
                .putBoolean(EXTRA_SILENCING, false)
                .apply();
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

    private Notification buildNotification(boolean cameraInUse, boolean silencing) {
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

        String modeLabel = GuardSettings.labelFor(GuardSettings.getMode(this));
        String title = silencing
                ? "Silencing camera audio"
                : cameraInUse ? "Camera in use" : "Watching camera use";
        String text;
        if (silencing) {
            text = "Audio is temporarily reduced";
        } else if (cameraInUse && GuardSettings.MODE_FOLLOW_RINGER.equals(GuardSettings.getMode(this))) {
            text = "Ringer is audible; audio is unchanged";
        } else {
            text = "Mode: " + modeLabel;
        }

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

    private void registerRingerModeReceiver() {
        if (ringerReceiverRegistered) {
            return;
        }

        IntentFilter filter = new IntentFilter(AudioManager.RINGER_MODE_CHANGED_ACTION);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(ringerModeReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(ringerModeReceiver, filter);
        }
        ringerReceiverRegistered = true;
    }

    private void unregisterRingerModeReceiver() {
        if (!ringerReceiverRegistered) {
            return;
        }

        try {
            unregisterReceiver(ringerModeReceiver);
        } catch (RuntimeException e) {
            Log.d(TAG, "Ringer receiver unregister failed", e);
        }
        ringerReceiverRegistered = false;
    }

    private void registerScreenReceiver() {
        if (screenReceiverRegistered) {
            return;
        }

        IntentFilter filter = new IntentFilter(Intent.ACTION_SCREEN_ON);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(screenReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(screenReceiver, filter);
        }
        screenReceiverRegistered = true;
    }

    private void unregisterScreenReceiver() {
        if (!screenReceiverRegistered) {
            return;
        }

        try {
            unregisterReceiver(screenReceiver);
        } catch (RuntimeException e) {
            Log.d(TAG, "Screen receiver unregister failed", e);
        }
        screenReceiverRegistered = false;
    }

    private boolean shouldDeferScreenOnCameraUse() {
        long elapsed = SystemClock.elapsedRealtime() - lastScreenOnElapsedMillis;
        if (elapsed < 0 || elapsed >= SCREEN_ON_CAMERA_GRACE_MS || !isKeyguardLocked()) {
            return false;
        }

        scheduleSilencingUpdate(SCREEN_ON_CAMERA_GRACE_MS - elapsed + 50L);
        return true;
    }

    private void scheduleSilencingUpdate(long delayMillis) {
        if (mainHandler == null) {
            return;
        }

        mainHandler.removeCallbacks(delayedSilencingUpdate);
        mainHandler.postDelayed(delayedSilencingUpdate, Math.max(0L, delayMillis));
    }

    private boolean isKeyguardLocked() {
        if (keyguardManager == null) {
            return false;
        }

        try {
            return keyguardManager.isKeyguardLocked();
        } catch (RuntimeException e) {
            Log.d(TAG, "isKeyguardLocked failed", e);
            return false;
        }
    }

    private boolean shouldSilenceForCurrentMode() {
        String mode = GuardSettings.getMode(this);
        if (GuardSettings.MODE_ALWAYS.equals(mode)) {
            return true;
        }
        if (GuardSettings.MODE_FOLLOW_RINGER.equals(mode)) {
            return isRingerSilent();
        }
        return GuardSettings.isManualRunning(this);
    }

    private boolean isCameraInUse() {
        synchronized (unavailableCameraIds) {
            return !unavailableCameraIds.isEmpty();
        }
    }

    private boolean isRingerSilent() {
        if (audioManager == null) {
            return false;
        }

        try {
            int ringerMode = audioManager.getRingerMode();
            return ringerMode == AudioManager.RINGER_MODE_SILENT
                    || ringerMode == AudioManager.RINGER_MODE_VIBRATE;
        } catch (RuntimeException e) {
            Log.d(TAG, "getRingerMode failed", e);
            return false;
        }
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
