package dev.serika.camerasilencer;

import android.app.Service;
import android.content.Context;

interface GuardSession {
    GuardObserver observer();
    void setUpdateListener(Runnable listener);
    Class<?> activityClass();
    boolean startService(Service service);
    int restartMode();
    void serviceStopped(Context context);
    void decision(Context context, boolean camera, boolean foregroundCamera,
            boolean access, String foreground, boolean attempt, boolean previous);
    String notificationTitle(Context context, boolean camera, boolean attempt, String usualTitle);
    String notificationText(Context context, String usualText);
}
