package dev.serika.camerasilencer;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.util.Log;

public final class BootReceiver extends BroadcastReceiver {
    private static final String TAG = "BootReceiver";
    private static final String CHANNEL_ID = "boot_rearm";
    private static final int NOTIFICATION_ID = 1002;

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null || !Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())) {
            return;
        }

        CameraGuardService.clearSavedState(context);
        GuardSettings.setManualRunning(context, false);

        if (GuardSettings.shouldPromptOnBoot(context)) {
            Log.w(TAG, "Posting re-arm notification after boot mode="
                    + GuardSettings.getMode(context));
            postRearmNotification(context);
        } else {
            Log.w(TAG, "Skipping boot prompt in manual mode");
        }
    }

    private void postRearmNotification(Context context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                && context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
            Log.w(TAG, "Notification permission missing; user must open app to re-arm");
            return;
        }

        createNotificationChannel(context);

        Intent openIntent = new Intent(context, MainActivity.class)
                .putExtra(MainActivity.EXTRA_REARM_AFTER_BOOT, true)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent contentIntent = PendingIntent.getActivity(
                context,
                NOTIFICATION_ID,
                openIntent,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT
        );

        Notification.Builder builder = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? new Notification.Builder(context, CHANNEL_ID)
                : new Notification.Builder(context);

        Notification notification = builder
                .setSmallIcon(android.R.drawable.ic_menu_camera)
                .setContentTitle("Re-arm Camera Silencer")
                .setContentText("Tap to resume camera guarding after reboot")
                .setContentIntent(contentIntent)
                .setAutoCancel(true)
                .setShowWhen(true)
                .build();

        NotificationManager manager =
                (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        if (manager != null) {
            manager.notify(NOTIFICATION_ID, notification);
        }
    }

    private void createNotificationChannel(Context context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return;
        }

        NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID,
                "Boot re-arm",
                NotificationManager.IMPORTANCE_DEFAULT
        );
        channel.setDescription("Prompts to re-enable camera guarding after device reboot");

        NotificationManager manager =
                (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        if (manager != null) {
            manager.createNotificationChannel(channel);
        }
    }
}
