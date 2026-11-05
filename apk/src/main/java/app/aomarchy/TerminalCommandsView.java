package app.aomarchy;

import android.app.Activity;
import android.app.AlertDialog;
import android.graphics.Typeface;
import android.text.InputType;
import android.text.TextUtils;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.List;

import app.aomarchy.i18n.T;
import app.aomarchy.ui.FlowRow;
import app.aomarchy.ui.Motion;
import app.aomarchy.ui.Toasts;
import app.aomarchy.ui.Ui;

/**
 * The quick-command panel under the terminal: a header (title + 编辑 / + 新增) and a scrollable row of compact chips.
 *
 * <p>Chips are as wide as their label and wrap to the next row when the panel runs out of width ({@link FlowRow}), so
 * a short command no longer gets a huge equal-width cell. Tapping a chip sends its command; long-pressing one (or
 * tapping the × that appears in edit mode) asks before deleting it; in edit mode a tap edits the entry. Entries live
 * in {@link TerminalPresets} ({@code terminal_presets} in the app preferences).
 */
final class TerminalCommandsView extends LinearLayout {
    /** How chip taps leave the panel: the activity checks the terminal state and appends the Enter. */
    interface Listener {
        void send(String command);
    }

    private final Activity host;
    private final Listener listener;
    private final FlowRow row;
    private final TextView empty;
    private TextView editButton;
    private final ScrollView scroll;
    private boolean editing;

    TerminalCommandsView(Activity host, Listener listener) {
        super(host);
        this.host = host;
        this.listener = listener;
        setOrientation(VERTICAL);
        setBackgroundColor(Ui.SURFACE);

        View divider = new View(host);
        divider.setBackgroundColor(Ui.LINE);
        addView(divider, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Math.max(1, Ui.dp(host, 1))));

        addView(header(), new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        scroll = new ScrollView(host);
        LinearLayout body = Ui.column(host);
        body.setPadding(0, 0, 0, Ui.dp(host, 4));

        row = new FlowRow(host); // gap is 8dp both ways: a chip is never stretched, it wraps to the next line
        body.addView(row, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        empty = Ui.text(host, TerminalPresets.text("panel.empty", "还没有快捷命令，点「+ 新增」添加一条"),
                12, Ui.FAINT, false);
        empty.setPadding(Ui.dp(host, 4), Ui.dp(host, 12), Ui.dp(host, 4), 0);
        body.addView(empty, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        scroll.addView(body, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        addView(scroll, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        rebuild();
    }

    private LinearLayout header() {
        LinearLayout head = Ui.row(host);
        head.setPadding(Ui.dp(host, 2), Ui.dp(host, 8), Ui.dp(host, 2), Ui.dp(host, 2));
        // The title owns the leftover width and wraps instead of squeezing the buttons out: at very large system font
        // sizes a fixed-width title + right-aligned buttons would clip "编辑" / "+ 新增" off the panel.
        TextView title = Ui.text(host, TerminalPresets.text("panel.title", "快捷命令"), 15, Ui.TEXT, true);
        title.setSingleLine(false);
        head.addView(title, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        editButton = Ui.text(host, TerminalPresets.text("panel.edit", "编辑"), 13, Ui.ACCENT, false);
        editButton.setSingleLine(false);
        editButton.setPadding(Ui.dp(host, 10), Ui.dp(host, 4), Ui.dp(host, 10), Ui.dp(host, 4));
        editButton.setOnClickListener(v -> toggleEditing());
        head.addView(editButton);

        TextView add = Ui.text(host, TerminalPresets.text("panel.add", "+ 新增"), 13, Ui.ACCENT, false);
        add.setSingleLine(false);
        add.setPadding(Ui.dp(host, 10), Ui.dp(host, 4), Ui.dp(host, 4), Ui.dp(host, 4));
        add.setOnClickListener(v -> presetDialog(-1));
        head.addView(add);
        return head;
    }

    /** Orientation changes reflow the FlowRow by themselves; this rebuilds the copy (and is what the activity calls). */
    void updateColumns() {
        rebuild();
    }

    private void toggleEditing() {
        editing = !editing;
        editButton.setText(TerminalPresets.text(editing ? "panel.done" : "panel.edit",
                editing ? "完成" : "编辑"));
        editButton.setTextColor(editing ? Ui.MINT : Ui.ACCENT);
        rebuild();
    }

    private void rebuild() {
        if (row == null)
            return;
        row.removeAllViews();
        List<TerminalPresets.Preset> presets = TerminalPresets.load(host);
        empty.setVisibility(presets.isEmpty() ? VISIBLE : GONE);
        for (int i = 0; i < presets.size(); i++) {
            // WRAP_CONTENT + a 40dp minimum inside chip(): the height becomes max(40dp, label height + 12dp), so a
            // very large system font grows the chip instead of clipping the label.
            row.addView(chip(presets.get(i), i),
                    new ViewGroup.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                            ViewGroup.LayoutParams.WRAP_CONTENT));
        }
    }

    /** A compact chip: at least 40dp tall (grows with the font), 12dp side padding, 13sp label, width = label width. */
    private View chip(final TerminalPresets.Preset preset, final int index) {
        LinearLayout cell = Ui.row(host);
        cell.setBackground(Ui.surface(host, 12));
        cell.setPadding(Ui.dp(host, 12), Ui.dp(host, 6), Ui.dp(host, 12), Ui.dp(host, 6));
        cell.setMinimumHeight(Ui.dp(host, 40));
        Motion.pressable(cell);

        final TextView name = Ui.text(host, preset.name, 13, Ui.TEXT, false);
        name.setSingleLine(true);
        name.setEllipsize(TextUtils.TruncateAt.END);
        cell.addView(name);

        if (editing) {
            // Edit mode: the delete target sits inline after the label (no absolute corner overlay to size against).
            TextView remove = Ui.text(host, "×", 13, Ui.WARN, true);
            remove.setPadding(Ui.dp(host, 10), 0, 0, 0);
            remove.setContentDescription(T.t("btn.delete"));
            remove.setOnClickListener(v -> confirmDelete(index));
            cell.addView(remove);
        }

        cell.setOnClickListener(v -> {
            if (editing)
                presetDialog(index); // edit mode: tap opens the entry for renaming/rewriting
            else
                listener.send(preset.cmd);
        });
        cell.setOnLongClickListener(v -> {
            confirmDelete(index);
            return true;
        });
        return cell;
    }

    private void confirmDelete(final int index) {
        List<TerminalPresets.Preset> presets = TerminalPresets.load(host);
        if (index < 0 || index >= presets.size())
            return;
        final TerminalPresets.Preset preset = presets.get(index);
        new AlertDialog.Builder(host)
                .setTitle(TerminalPresets.text("panel.delete_title", "删除快捷命令？"))
                .setMessage(TerminalPresets.text("panel.delete_message", "将从快捷面板移除「%s」。", preset.name))
                .setNegativeButton(T.t("btn.cancel"), null)
                .setPositiveButton(T.t("btn.delete"), (dialog, which) -> {
                    List<TerminalPresets.Preset> current = TerminalPresets.load(host);
                    if (index < current.size()) {
                        current.remove(index);
                        TerminalPresets.save(host, current);
                        rebuild();
                    }
                })
                .show();
    }

    /** {@code index < 0}: add a new preset; otherwise edit the entry at that position. */
    private void presetDialog(final int index) {
        final List<TerminalPresets.Preset> presets = TerminalPresets.load(host);
        final TerminalPresets.Preset existing = index >= 0 && index < presets.size() ? presets.get(index) : null;

        LinearLayout box = Ui.column(host);
        box.setPadding(Ui.dp(host, 20), Ui.dp(host, 8), Ui.dp(host, 20), 0);

        final EditText name = new EditText(host);
        name.setHint(TerminalPresets.text("panel.name_hint", "名称"));
        name.setInputType(InputType.TYPE_CLASS_TEXT);
        name.setSingleLine(true);
        if (existing != null)
            name.setText(existing.name);
        box.addView(name, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        final EditText command = new EditText(host);
        command.setHint(TerminalPresets.text("panel.cmd_hint", "命令"));
        command.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        command.setTypeface(Typeface.MONOSPACE);
        command.setSingleLine(true);
        if (existing != null)
            command.setText(existing.cmd);
        box.addView(command, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        final AlertDialog dialog = new AlertDialog.Builder(host)
                .setTitle(TerminalPresets.text(index >= 0 ? "panel.edit_title" : "panel.add_title",
                        index >= 0 ? "编辑快捷命令" : "新增快捷命令"))
                .setView(box)
                .setNegativeButton(T.t("btn.cancel"), null)
                .setPositiveButton(T.t("btn.save"), null)
                .create();
        dialog.show();
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            String newName = name.getText().toString().trim();
            String newCommand = command.getText().toString().trim();
            if (newName.isEmpty() || newCommand.isEmpty()) {
                Toasts.show(host, TerminalPresets.text("panel.invalid", "名称和命令都不能为空"));
                return;
            }
            List<TerminalPresets.Preset> current = TerminalPresets.load(host);
            TerminalPresets.Preset updated = new TerminalPresets.Preset(newName, newCommand);
            if (index >= 0 && index < current.size())
                current.set(index, updated);
            else
                current.add(updated);
            TerminalPresets.save(host, current);
            dialog.dismiss();
            rebuild();
        });
    }
}
