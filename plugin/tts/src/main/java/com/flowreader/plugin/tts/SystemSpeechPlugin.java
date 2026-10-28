package com.flowreader.plugin.tts;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;
import android.speech.tts.Voice;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;
import com.flowreader.plugin.api.SpeechPlugin;
import java.util.Locale;

/** Bundled system-TTS implementation; the host only creates it after explicit enablement and use. */
public final class SystemSpeechPlugin implements SpeechPlugin {
    private final Handler main = new Handler(Looper.getMainLooper());
    private TextToSpeech engine;
    private Listener listener;
    private boolean ready;
    private boolean playing;
    private boolean released;
    private String initializationError;
    private String text = "";
    private int cursor;
    private int chunkStart;
    private int chunkEnd;
    private long generation;
    private String activeId;
    private float rate = 1f;
    private TextView statusView;
    private Button playButton;
    private Button dataButton;
    private boolean chinese;
    private Context app;
    private Locale speechLocale;
    private String playbackError;
    private String engineLabel = "";
    private long initializationGeneration;
    private Button retryButton;
    private TextView engineView;
    private boolean initialized;
    private int playbackErrorCode = TextToSpeech.SUCCESS;

    @Override public void attach(Context context, Listener callback) {
        listener = callback;
        app = context.getApplicationContext();
        chinese = context.getResources().getConfiguration().getLocales().get(0).getLanguage().equals("zh");
        speechLocale = context.getResources().getConfiguration().getLocales().get(0);
        initialize(app.getSharedPreferences("speech_engine", Context.MODE_PRIVATE).getString("engine", null));
    }

    private void initialize(String selectedEngine) {
        invalidate();
        if (engine != null) engine.shutdown();
        ready = false;
        initialized = false;
        playing = false;
        initializationError = null;
        playbackError = null;
        playbackErrorCode = TextToSpeech.SUCCESS;
        final long initialization = ++initializationGeneration;
        report(null);
        engine = new TextToSpeech(app, result -> main.post(() -> {
            if (released || engine == null || initialization != initializationGeneration) return;
            String name = selectedEngine == null ? engine.getDefaultEngine() : selectedEngine;
            engineLabel = name == null ? "" : name;
            java.util.List<TextToSpeech.EngineInfo> engines = engine.getEngines();
            if (engines != null) for (TextToSpeech.EngineInfo info : engines) if (info.name.equals(name)) engineLabel = info.label + " · " + name;
            if (result != TextToSpeech.SUCCESS) {
                playing = false;
                initializationError = chinese ? "系统语音服务连接失败，请选择可用引擎后重试" : "Speech service unavailable; choose an engine and retry";
                report(initializationError);
                return;
            }
            initialized = true;
            configureLanguage(speechLocale);
            if (!ready) playing = false;
            engine.setAudioAttributes(new android.media.AudioAttributes.Builder()
                .setUsage(android.media.AudioAttributes.USAGE_MEDIA)
                .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SPEECH).build());
            engine.setSpeechRate(rate);
            report(initializationError);
            if (ready && playing) speakChunk();
        }), selectedEngine);
        engine.setOnUtteranceProgressListener(new UtteranceProgressListener() {
            @Override public void onStart(String id) { }
            @Override public void onDone(String id) {
                main.post(() -> {
                    if (!isCurrent(id)) return;
                    cursor = chunkEnd;
                    if (cursor < text.length()) speakChunk();
                    else { playing = false; report(null); }
                });
            }
            @Override public void onError(String id) {
                onError(id, TextToSpeech.ERROR);
            }
            @Override public void onError(String id, int code) {
                main.post(() -> {
                    if (!isCurrent(id)) return;
                    playing = false;
                    playbackErrorCode = code;
                    playbackError = describeError(code);
                    android.util.Log.w("YejianSpeech", "engine=" + engineLabel + ", locale=" + speechLocale + ", error=" + code);
                    report(playbackError);
                });
            }
            @Override public void onRangeStart(String id, int start, int end, int frame) {
                main.post(() -> { if (isCurrent(id)) cursor = Math.min(chunkEnd, chunkStart + start); });
            }
        });
    }

    private void configureLanguage(Locale locale) {
        int language = engine.setLanguage(locale);
        ready = language >= TextToSpeech.LANG_AVAILABLE;
        speechLocale = locale;
        java.util.Set<Voice> voices = engine.getVoices();
        Voice preferred = null;
        if (voices != null) for (Voice voice : voices) {
            if (!voice.getLocale().getLanguage().equals(locale.getLanguage()) || voice.isNetworkConnectionRequired()) continue;
            if (voice.getFeatures() != null && voice.getFeatures().contains(TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED)) continue;
            if (preferred == null || voice.getLocale().equals(locale) && !preferred.getLocale().equals(locale)
                || voice.getLocale().equals(preferred.getLocale()) && voice.getQuality() > preferred.getQuality()) preferred = voice;
        }
        if (preferred != null && engine.setVoice(preferred) == TextToSpeech.SUCCESS) {
            ready = true;
            speechLocale = preferred.getLocale();
        }
        initializationError = ready ? null : (chinese ? "当前引擎未安装或不支持“" + locale.getDisplayLanguage(Locale.SIMPLIFIED_CHINESE) + "”语音，请选择其他引擎或安装语音数据" : "This engine has no installed voice for " + locale.getDisplayLanguage(Locale.ENGLISH));
    }

    private String describeError(int code) {
        if (!chinese) return "Speech failed (" + code + "); retry or choose another system engine";
        switch (code) {
            case TextToSpeech.ERROR_NETWORK: return "当前语音需要联网，但网络请求失败（" + code + "）；可切换本地语音引擎";
            case TextToSpeech.ERROR_NETWORK_TIMEOUT: return "语音网络请求超时（" + code + "）；可切换本地语音引擎";
            case TextToSpeech.ERROR_NOT_INSTALLED_YET: return "语音数据尚未安装（" + code + "），请安装后重试";
            case TextToSpeech.ERROR_OUTPUT: return "音频输出失败（" + code + "），请检查媒体音量和蓝牙输出后重试";
            case TextToSpeech.ERROR_SERVICE: return "系统语音服务异常（" + code + "），请重试或更换语音引擎";
            case TextToSpeech.ERROR_SYNTHESIS: return "语音合成失败（" + code + "），请重试或更换语音引擎";
            case TextToSpeech.ERROR_INVALID_REQUEST: return "语音引擎不接受当前请求（" + code + "），请更换引擎";
            default: return "系统语音朗读失败（" + code + "），请重试或选择其他引擎";
        }
    }

    private boolean isCurrent(String id) { return !released && playing && id != null && id.equals(activeId); }

    @Override public void speak(String content) {
        if (released || content.trim().isEmpty()) return;
        boolean hasHan = content.codePoints().limit(2000).anyMatch(code -> Character.UnicodeScript.of(code) == Character.UnicodeScript.HAN);
        if (hasHan && !speechLocale.getLanguage().equals("zh")) {
            if (initialized) configureLanguage(Locale.SIMPLIFIED_CHINESE);
            else speechLocale = Locale.SIMPLIFIED_CHINESE;
        }
        if (initializationError != null) { playing = false; report(initializationError); return; }
        invalidate();
        playbackError = null;
        playbackErrorCode = TextToSpeech.SUCCESS;
        text = content;
        cursor = 0;
        playing = true;
        report(null);
        if (ready) speakChunk();
    }

    private void speakChunk() {
        if (released || !ready || !playing) return;
        chunkStart = cursor;
        // 手机厂商的引擎对接近系统上限的长中文请求兼容性不一致；按短句块连续播放。
        int limit = Math.min(600, TextToSpeech.getMaxSpeechInputLength());
        chunkEnd = Math.min(text.length(), cursor + limit);
        if (chunkEnd < text.length()) {
            int minimum = cursor + limit / 2;
            for (int end = chunkEnd; end > minimum; end--) {
                if ("。！？；\n.!?;".indexOf(text.charAt(end - 1)) >= 0) { chunkEnd = end; break; }
            }
        }
        if (chunkEnd < text.length() && Character.isHighSurrogate(text.charAt(chunkEnd - 1))) chunkEnd--;
        activeId = "yejian-" + generation + "-" + chunkStart;
        if (engine.speak(text.substring(chunkStart, chunkEnd), TextToSpeech.QUEUE_FLUSH, null, activeId) == TextToSpeech.ERROR) {
            playing = false;
            playbackError = chinese ? "语音引擎拒绝朗读请求，请重试或选择其他引擎" : "Speech engine rejected the request; retry or choose another engine";
            report(playbackError);
        }
    }

    private void invalidate() {
        generation++;
        activeId = null;
        if (engine != null) engine.stop();
    }

    @Override public void pause() {
        invalidate();
        playing = false;
        report(null);
    }

    @Override public void stop() {
        pause();
        cursor = 0;
        text = "";
        report(null);
    }

    @Override public void shutdown() {
        pause();
        released = true;
        ready = false;
        if (engine != null) engine.shutdown();
        engine = null;
        statusView = null;
        playButton = null;
        dataButton = null;
        retryButton = null;
        engineView = null;
        listener = null;
    }

    private void report(String error) {
        if (error == null) error = playbackError;
        if (error == null && !ready) error = initializationError;
        if (listener != null) listener.onState(ready, playing, error);
        if (statusView != null) {
            statusView.setText(error != null ? error : !ready ? (chinese ? "正在准备系统语音…" : "Preparing system speech…")
                : playing ? (chinese ? "正在朗读" : "Reading aloud")
                : text.isEmpty() ? (chinese ? "准备就绪" : "Ready") : (chinese ? "已暂停" : "Paused"));
        }
        if (playButton != null) {
            playButton.setEnabled(ready);
            playButton.setText(playing ? (chinese ? "暂停" : "Pause")
                : text.isEmpty() ? (chinese ? "开始朗读" : "Read aloud") : (chinese ? "继续朗读" : "Resume"));
        }
        if (dataButton != null) dataButton.setVisibility(!ready && initializationError != null || playbackErrorCode == TextToSpeech.ERROR_NOT_INSTALLED_YET ? View.VISIBLE : View.GONE);
        if (retryButton != null) retryButton.setVisibility(error != null ? View.VISIBLE : View.GONE);
        if (engineView != null) engineView.setText((chinese ? "当前引擎 · " : "Engine · ") + (engineLabel.isEmpty() ? (chinese ? "系统默认" : "System default") : engineLabel));
    }

    @Override public void detachControls() {
        statusView = null;
        playButton = null;
        dataButton = null;
        retryButton = null;
        engineView = null;
    }

    @Override public View createControls(Context context, int foreground, int secondary, int accent, Runnable requestContent) {
        int spacing = Math.round(12 * context.getResources().getDisplayMetrics().density);
        LinearLayout root = new LinearLayout(context);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(0, spacing, 0, spacing);
        statusView = new TextView(context);
        statusView.setTextSize(16);
        statusView.setTextColor(foreground);
        root.addView(statusView);
        TextView speed = new TextView(context);
        speed.setTextColor(secondary);
        speed.setTextSize(14);
        speed.setPadding(0, spacing, 0, 0);
        speed.setText(String.format(Locale.ROOT, chinese ? "语速 · %.1f 倍" : "Speed · %.1fx", rate));
        root.addView(speed);
        SeekBar slider = new SeekBar(context);
        slider.setMax(15);
        slider.setProgress(Math.round((rate - .5f) * 10));
        slider.setProgressTintList(android.content.res.ColorStateList.valueOf(accent));
        slider.setThumbTintList(android.content.res.ColorStateList.valueOf(accent));
        slider.setContentDescription(chinese ? "朗读语速" : "Speech speed");
        slider.setMinimumHeight(spacing * 4);
        slider.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar bar, int value, boolean user) {
                rate = .5f + value / 10f;
                if (engine != null) engine.setSpeechRate(rate);
                speed.setText(String.format(Locale.ROOT, chinese ? "语速 · %.1f 倍" : "Speed · %.1fx", rate));
            }
            @Override public void onStartTrackingTouch(SeekBar bar) { }
            @Override public void onStopTrackingTouch(SeekBar bar) { }
        });
        root.addView(slider);
        LinearLayout buttons = new LinearLayout(context);
        playButton = new Button(context);
        playButton.setTextColor(accent);
        playButton.setMinHeight(spacing * 4);
        styleButton(playButton, accent, spacing);
        playButton.setOnClickListener(view -> {
            if (playing) pause();
            else if (text.isEmpty()) requestContent.run();
            else {
                if (cursor >= text.length()) cursor = 0;
                playbackError = null;
                playing = true;
                report(null);
                if (ready) speakChunk();
            }
        });
        LinearLayout.LayoutParams playLayout = new LinearLayout.LayoutParams(0, -2, 1);
        playLayout.setMarginEnd(spacing / 2);
        buttons.addView(playButton, playLayout);
        Button stop = new Button(context);
        stop.setText(chinese ? "停止" : "Stop");
        stop.setTextColor(foreground);
        stop.setMinHeight(spacing * 4);
        styleButton(stop, secondary, spacing);
        stop.setOnClickListener(view -> stop());
        buttons.addView(stop, new LinearLayout.LayoutParams(0, -2, 1));
        root.addView(buttons);
        dataButton = new Button(context);
        dataButton.setText(chinese ? "安装系统语音数据" : "Install system speech data");
        dataButton.setTextColor(accent);
        dataButton.setMinHeight(spacing * 4);
        styleButton(dataButton, secondary, spacing);
        dataButton.setOnClickListener(view -> {
            try {
                context.startActivity(new android.content.Intent(TextToSpeech.Engine.ACTION_INSTALL_TTS_DATA).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK));
            } catch (android.content.ActivityNotFoundException missing) {
                try {
                    context.startActivity(new android.content.Intent(android.provider.Settings.ACTION_SETTINGS).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK));
                } catch (RuntimeException blocked) {
                    report(chinese ? "请在手机设置中安装系统语音数据" : "Install speech data in your phone settings");
                }
            } catch (RuntimeException blocked) {
                report(chinese ? "请在手机设置中安装系统语音数据" : "Install speech data in your phone settings");
            }
        });
        root.addView(dataButton);
        retryButton = new Button(context);
        retryButton.setText(chinese ? "重新连接语音引擎" : "Reconnect speech engine");
        retryButton.setTextColor(accent);
        retryButton.setMinHeight(spacing * 4);
        styleButton(retryButton, secondary, spacing);
        retryButton.setOnClickListener(view -> initialize(app.getSharedPreferences("speech_engine", Context.MODE_PRIVATE).getString("engine", null)));
        root.addView(retryButton);
        engineView = new TextView(context);
        engineView.setTextSize(12);
        engineView.setTextColor(secondary);
        engineView.setPadding(0, spacing, 0, spacing / 2);
        root.addView(engineView);
        Button choose = new Button(context);
        choose.setText(chinese ? "选择语音引擎" : "Choose speech engine");
        choose.setTextColor(accent);
        choose.setMinHeight(spacing * 4);
        styleButton(choose, secondary, spacing);
        choose.setOnClickListener(view -> {
            java.util.List<TextToSpeech.EngineInfo> engines = engine == null ? null : engine.getEngines();
            if (engines == null || engines.isEmpty()) {
                report(chinese ? "手机未发现系统语音引擎，请先在系统设置安装" : "No system speech engine is installed");
                return;
            }
            String[] labels = new String[engines.size()];
            for (int i = 0; i < engines.size(); i++) labels[i] = engines.get(i).label;
            new android.app.AlertDialog.Builder(context).setTitle(chinese ? "选择系统语音引擎" : "Choose speech engine")
                .setItems(labels, (dialog, index) -> {
                    String selected = engines.get(index).name;
                    app.getSharedPreferences("speech_engine", Context.MODE_PRIVATE).edit().putString("engine", selected).apply();
                    initialize(selected);
                }).show();
        });
        LinearLayout.LayoutParams chooseLayout = new LinearLayout.LayoutParams(-1, -2);
        chooseLayout.topMargin = spacing;
        root.addView(choose, chooseLayout);
        Button settings = new Button(context);
        settings.setText(chinese ? "系统语音设置" : "System speech settings");
        settings.setTextColor(accent);
        settings.setMinHeight(spacing * 4);
        styleButton(settings, secondary, spacing);
        settings.setOnClickListener(view -> {
            // 尝试语音设置入口；厂商不提供时退到无障碍设置，并保留页面内引擎选择。
            try {
                context.startActivity(new android.content.Intent("com.android.settings.TTS_SETTINGS").addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK));
            } catch (RuntimeException unavailable) {
                try { context.startActivity(new android.content.Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)); }
                catch (RuntimeException blocked) { report(chinese ? "请在手机设置中搜索“文字转语音”" : "Search for text-to-speech in phone settings"); }
            }
        });
        LinearLayout.LayoutParams settingsLayout = new LinearLayout.LayoutParams(-1, -2);
        settingsLayout.topMargin = spacing;
        root.addView(settings, settingsLayout);
        report(null);
        return root;
    }

    private static void styleButton(Button button, int color, int spacing) {
        button.setAllCaps(false);
        button.setTextSize(14);
        int textColor = button.getCurrentTextColor();
        button.setTextColor(new android.content.res.ColorStateList(
            new int[][] { new int[] { android.R.attr.state_enabled }, new int[] { } },
            new int[] { textColor, (textColor & 0x00ffffff) | 0x66000000 }));
        android.graphics.drawable.GradientDrawable outline = new android.graphics.drawable.GradientDrawable();
        outline.setColor(android.graphics.Color.TRANSPARENT);
        outline.setCornerRadius(spacing * 2f / 3f);
        outline.setStroke(Math.max(1, spacing / 12), color);
        button.setBackground(new android.graphics.drawable.RippleDrawable(
            android.content.res.ColorStateList.valueOf((color & 0x00ffffff) | 0x22000000), outline, null));
    }
}
