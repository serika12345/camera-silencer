package dev.serika.camerasilencer;

import android.app.Service;
import android.content.Context;
import android.media.AudioManager;
import android.media.AudioTrack;
import android.media.audiofx.AudioEffect;

final class AppDependencies {
    private static final GuardObserver OBSERVER = new GuardObserver() {
        public void event(String component, String detail) { }
        public void failure(String component, Throwable error) { }
        public void effect(String name, AudioEffect effect, int result) { }
        public void effectReleased(String name) { }
        public void stream(AudioManager manager, int stream, String phase) { }
        public long token() { return 0; }
        public void track(long token, AudioTrack track, int volumeResult) { }
        public boolean write(long token, int result) { return true; }
        public void trackStopped(long token, boolean requested) { }
        public void trackFailure(long token, Throwable error) { }
    };
    private static final GuardSession SESSION = new GuardSession() {
        public void setUpdateListener(Runnable listener) { }
        public GuardObserver observer() { return OBSERVER; }
        public Class<?> activityClass() { return MainActivity.class; }
        public boolean startService(Service service) { return true; }
        public int restartMode() { return Service.START_STICKY; }
        public void serviceStopped(Context context) { }
        public void decision(Context context, boolean camera, boolean foregroundCamera,
                boolean access, String foreground, boolean attempt, boolean previous) { }
        public String notificationTitle(Context context, boolean camera, boolean attempt, String title) {
            return title;
        }
        public String notificationText(Context context, String text) { return text; }
    };
    static GuardSession session(Context context) { return SESSION; }
}
