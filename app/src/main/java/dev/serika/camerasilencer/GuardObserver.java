package dev.serika.camerasilencer;

import android.media.AudioManager;
import android.media.AudioTrack;
import android.media.audiofx.AudioEffect;

interface GuardObserver {
    void event(String component, String detail);
    void failure(String component, Throwable error);
    void effect(String name, AudioEffect effect, int result);
    void effectReleased(String name);
    void stream(AudioManager manager, int stream, String phase);
    long token();
    void track(long token, AudioTrack track, int volumeResult);
    boolean write(long token, int result);
    void trackStopped(long token, boolean requested);
    void trackFailure(long token, Throwable error);
}
