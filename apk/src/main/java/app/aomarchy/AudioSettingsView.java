package app.aomarchy;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import app.aomarchy.ui.Ui;

/**
 * 设置 → 音频: the audio half of the old "连接" category page, which is the only part of it that still applies (the
 * socket path / root switches belonged to the Termux-era transport and are gone). Values are read again by
 * MainActivity.applyAudioLatency() when a desktop session starts.
 */
public final class AudioSettingsView {
    /** The presets MainActivity forwards to native (0 = the engine's default). */
    private static final int[] LATENCY_MS = {0, 1, 3, 5, 10, 20};

    private AudioSettingsView() {
    }

    /** Builds the page body. Never returns null. */
    public static View build(Activity host) {
        SharedPreferences prefs = host.getSharedPreferences(Prefs.NAME, Context.MODE_PRIVATE);

        LinearLayout body = Ui.column(host);

        LinearLayout keepalive = Ui.card(host, "音频保活");
        keepalive.addView(Ui.text(host, "桌面会话在后台时不让音频通道休眠，外放和麦克风不会每次重新起振。",
                12, Ui.DIM, false));
        keepalive.addView(Ui.gap(host, 4));
        keepalive.addView(Ui.toggleRow(host, "保持音频通道",
                "关闭可省一点电；通话或录音时建议开启",
                prefs.getBoolean(MainActivity.KEY_AUDIO_KEEPALIVE, false),
                on -> prefs.edit().putBoolean(MainActivity.KEY_AUDIO_KEEPALIVE, on).apply()));
        body.addView(keepalive);

        LinearLayout latency = Ui.card(host, "音频延迟");
        latency.addView(Ui.text(host, "越低越跟手，太高会明显对不上画面；改完下次进桌面生效。", 12, Ui.DIM, false));
        latency.addView(Ui.gap(host, 4));
        latency.addView(latencyRow(host, prefs, "扬声器", MainActivity.KEY_SPEAKER_LATENCY_MS));
        latency.addView(Ui.divider(host));
        latency.addView(latencyRow(host, prefs, "麦克风", MainActivity.KEY_MIC_LATENCY_MS));
        body.addView(latency);

        return body;
    }

    /** A label + current value row; the value TextView is updated in place after a choice, no page rebuild. */
    private static LinearLayout latencyRow(Activity host, SharedPreferences prefs, String label, String key) {
        final TextView value = Ui.text(host, latencyLabel(prefs.getInt(key, 0)), 13, Ui.DIM, false);
        LinearLayout row = Ui.row(host);
        row.setPadding(0, Ui.dp(host, 7), 0, Ui.dp(host, 7));
        LinearLayout texts = Ui.column(host);
        texts.addView(Ui.text(host, label, 14, Ui.TEXT, false));
        texts.addView(Ui.text(host, "毫秒；0 = 系统默认", 11, Ui.FAINT, false));
        row.addView(texts, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        row.addView(value);
        row.addView(Ui.space(host, 6));
        row.addView(Ui.text(host, "›", 15, Ui.FAINT, false));
        row.setOnClickListener(v -> {
            String[] names = new String[LATENCY_MS.length];
            int checked = 0;
            for (int i = 0; i < LATENCY_MS.length; i++) {
                names[i] = latencyLabel(LATENCY_MS[i]);
                if (LATENCY_MS[i] == prefs.getInt(key, 0))
                    checked = i;
            }
            new android.app.AlertDialog.Builder(host)
                    .setTitle(label + "延迟")
                    .setSingleChoiceItems(names, checked, (d, which) -> {
                        prefs.edit().putInt(key, LATENCY_MS[which]).apply();
                        value.setText(latencyLabel(LATENCY_MS[which]));
                        d.dismiss();
                    })
                    .setNegativeButton("取消", null)
                    .show();
        });
        row.setClickable(true);
        return row;
    }

    private static String latencyLabel(int ms) {
        return ms == 0 ? "系统默认" : ms + " ms";
    }
}
