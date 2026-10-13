package dev.serika.camerasilencer;

import android.content.Context;

final class AppDependencies {
    static DiagnosticSession session(Context context) {
        return ((DiagnosticApplication) context.getApplicationContext()).session();
    }
}
