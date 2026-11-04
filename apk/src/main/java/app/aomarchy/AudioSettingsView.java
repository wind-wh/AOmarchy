package app.aomarchy;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

import app.aomarchy.i18n.T;
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

        LinearLayout keepalive = Ui.card(host, T.t("card.audio_keepalive"));
        keepalive.addView(Ui.text(host, T.t("audio.keepalive_desc"), 12, Ui.DIM, false));
        keepalive.addView(Ui.gap(host, 4));
        keepalive.addView(toggleRow(host, T.t("audio.keepalive_toggle"),
                T.t("audio.keepalive_caption"),
                prefs.getBoolean(MainActivity.KEY_AUDIO_KEEPALIVE, false),
                on -> prefs.edit().putBoolean(MainActivity.KEY_AUDIO_KEEPALIVE, on).apply()));
        body.addView(keepalive);

        LinearLayout latency = Ui.card(host, T.t("card.audio_latency"));
        latency.addView(Ui.text(host, T.t("audio.latency_desc"), 12, Ui.DIM, false));
        latency.addView(Ui.gap(host, 4));
        latency.addView(latencyRow(host, prefs, T.t("label.speaker"), MainActivity.KEY_SPEAKER_LATENCY_MS));
        latency.addView(Ui.divider(host));
        latency.addView(latencyRow(host, prefs, T.t("label.microphone"), MainActivity.KEY_MIC_LATENCY_MS));
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
        texts.addView(Ui.text(host, T.t("audio.latency_unit"), 11, Ui.FAINT, false));
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
                    .setTitle(T.t("audio.latency_title", label))
                    .setSingleChoiceItems(names, checked, (d, which) -> {
                        prefs.edit().putInt(key, LATENCY_MS[which]).apply();
                        value.setText(latencyLabel(LATENCY_MS[which]));
                        d.dismiss();
                    })
                    .setNegativeButton(T.t("btn.cancel"), null)
                    .show();
        });
        row.setClickable(true);
        return row;
    }

    private static String latencyLabel(int ms) {
        return ms == 0 ? T.t("audio.system_default") : ms + " ms";
    }

    /**
     * The {@code Ui.toggleRow} shape with a localized On/Off pill: {@code Ui.toggleRow} hardcodes the Chinese
     * 开启/关闭 labels and Ui.java is outside this task's write scope.
     */
    private static LinearLayout toggleRow(Activity host, String label, String caption, boolean value,
            Ui.OnToggle onChange) {
        LinearLayout r = Ui.row(host);
        r.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        r.setPadding(0, Ui.dp(host, 7), 0, Ui.dp(host, 7));

        LinearLayout texts = Ui.column(host);
        texts.addView(Ui.text(host, label, 14, Ui.TEXT, false));
        if (caption != null && !caption.isEmpty())
            texts.addView(Ui.text(host, caption, 11, Ui.FAINT, false));
        r.addView(texts, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        final boolean[] on = {value};
        TextView pill = Ui.text(host, on[0] ? T.t("state.on") : T.t("state.off"), 13,
                on[0] ? Ui.ACCENT : Ui.DIM, true);
        pill.setPadding(Ui.dp(host, 10), Ui.dp(host, 5), Ui.dp(host, 10), Ui.dp(host, 5));
        pill.setBackground(Ui.surface(host, 30));
        r.addView(pill);
        r.setOnClickListener(v -> {
            on[0] = !on[0];
            pill.setText(on[0] ? T.t("state.on") : T.t("state.off"));
            pill.setTextColor(on[0] ? Ui.ACCENT : Ui.DIM);
            onChange.onToggle(on[0]);
        });
        r.setClickable(true);
        return r;
    }
}
