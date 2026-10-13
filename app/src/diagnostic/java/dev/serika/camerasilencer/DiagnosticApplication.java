package dev.serika.camerasilencer;

import android.app.Application;

public final class DiagnosticApplication extends Application {
    private DiagnosticSession session;

    synchronized DiagnosticSession session() {
        if (session == null) session = new DiagnosticSession(this);
        return session;
    }
}
