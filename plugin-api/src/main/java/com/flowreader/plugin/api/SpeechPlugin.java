package com.flowreader.plugin.api;

import android.content.Context;
import android.view.View;

/** Resource-free interface for the bundled, opt-in speech engine and playback controls. */
public interface SpeechPlugin {
    int API_VERSION = 1;

    interface Listener {
        void onState(boolean ready, boolean speaking, String error);
    }

    void attach(Context context, Listener listener);
    void speak(String text);
    void pause();
    void stop();
    void shutdown();
    View createControls(Context context, int foreground, int secondary, int accent, Runnable requestContent);
    void detachControls();
}
