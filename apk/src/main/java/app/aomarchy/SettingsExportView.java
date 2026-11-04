package app.aomarchy;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

import app.aomarchy.data.SettingsIO;
import app.aomarchy.i18n.T;
import app.aomarchy.ui.Toasts;
import app.aomarchy.ui.Ui;

/**
 * 设置 → 配置备份: export the console's settings to a JSON file, or restore them from one, through the system
 * document picker (SAF — no storage permission).
 *
 * <p>Instance API for the settings host (SettingsPage), same contract as the other rebuilt settings pages:
 * <pre>
 *   SettingsExportView exporter = SettingsExportView.create(activity);
 *   // put exporter.view() into the scrollable sheet (it is the page body: cards only, no title bar)
 *   // forward Activity results: if (exporter.onActivityResult(request, result, data)) return;
 *   // when the sheet is dismissed: exporter.dispose();
 * </pre>
 * Everything runs off the UI thread; the host is only touched again on the UI thread, and never after
 * {@link #dispose()}.
 */
public final class SettingsExportView {
    /** Request codes used with {@link Activity#startActivityForResult}; the host just forwards all results. */
    public static final int REQ_EXPORT = 71;
    public static final int REQ_IMPORT = 72;
    /** A settings document is a few KB; refuse anything huge instead of loading it into memory. */
    private static final int MAX_IMPORT_BYTES = 1 << 20;

    private final Activity host;
    private final LinearLayout root;
    private final TextView exportStatus;
    private final TextView importStatus;
    private boolean busy;
    private boolean disposed;

    /** Builds the page for one settings host. */
    public static SettingsExportView create(Activity host) {
        return new SettingsExportView(host);
    }

    private SettingsExportView(Activity host) {
        this.host = host;
        root = Ui.column(host);

        LinearLayout export = Ui.card(host, tr("card.export_settings", "导出配置"));
        export.addView(Ui.text(host, tr("settings.export_desc",
                "把控制台的全部设置写成 JSON 文件。不包含 Linux 系统、应用或备份文件。"),
                11, Ui.FAINT, false));
        export.addView(Ui.gap(host, 8));
        export.addView(Ui.button(host, tr("btn.export_settings", "导出配置…"), true, v -> startExport()));
        exportStatus = statusLine();
        export.addView(exportStatus);
        root.addView(export);

        LinearLayout restore = Ui.card(host, tr("card.import_settings", "导入配置"));
        restore.addView(Ui.text(host, tr("settings.import_desc",
                "从导出的 JSON 文件恢复设置；同名键会被覆盖，部分设置需重新进入桌面或重启 App 生效。"),
                11, Ui.FAINT, false));
        restore.addView(Ui.gap(host, 8));
        restore.addView(Ui.button(host, tr("btn.import_settings", "导入配置…"), false, v -> startImport()));
        importStatus = statusLine();
        restore.addView(importStatus);
        root.addView(restore);
    }

    /** The page body: a vertical stack of cards, ready to drop into the host's scrollable sheet. */
    public View view() {
        return root;
    }

    /** Call from the host's onActivityResult first; true means this view consumed the result. */
    public boolean onActivityResult(int requestCode, int resultCode, Intent data) {
        if (requestCode != REQ_EXPORT && requestCode != REQ_IMPORT)
            return false;
        if (disposed)
            return true;
        Uri uri = resultCode == Activity.RESULT_OK && data != null ? data.getData() : null;
        if (uri == null) {
            if (resultCode == Activity.RESULT_OK)
                toast(T.t("toast.no_file")); // odd provider answer; an explicit cancel stays silent
            return true;
        }
        if (requestCode == REQ_EXPORT)
            exportTo(uri);
        else
            importFrom(uri);
        return true;
    }

    /** The settings sheet is closing: stop touching the host (any running copy/import still completes). */
    public void dispose() {
        disposed = true;
    }

    // ---- export --------------------------------------------------------------------------------------------------

    private void startExport() {
        if (busy || disposed)
            return;
        Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT)
                .addCategory(Intent.CATEGORY_OPENABLE)
                .setType(SettingsIO.mimeType())
                .putExtra(Intent.EXTRA_TITLE, SettingsIO.suggestedName());
        try {
            host.startActivityForResult(intent, REQ_EXPORT);
        } catch (RuntimeException e) {
            reportExportFailure(e);
        }
    }

    private void exportTo(Uri target) {
        busy = true;
        setStatus(exportStatus, tr("toast.exporting", "正在导出 %s…", SettingsIO.suggestedName()));
        new Thread(() -> {
            try {
                String json = SettingsIO.exportJson(host);
                OutputStream out = host.getContentResolver().openOutputStream(target);
                if (out == null)
                    throw new IOException(tr("settings_io.write_failed", "无法写入目标文件"));
                try {
                    out.write(json.getBytes(StandardCharsets.UTF_8));
                } finally {
                    out.close();
                }
                final String name = displayName(target, SettingsIO.suggestedName());
                host.runOnUiThread(() -> {
                    busy = false;
                    if (disposed)
                        return;
                    String message = tr("settings.exported", "已导出 %s", name);
                    setStatus(exportStatus, message);
                    toast(message);
                });
            } catch (Exception e) {
                host.runOnUiThread(() -> {
                    busy = false;
                    if (disposed)
                        return;
                    reportExportFailure(e);
                });
            }
        }, "settings-export").start();
    }

    private void reportExportFailure(Exception e) {
        String message = T.t("toast.export_failed", describe(e));
        setStatus(exportStatus, message);
        toast(message);
    }

    // ---- import --------------------------------------------------------------------------------------------------

    private void startImport() {
        if (busy || disposed)
            return;
        // Some providers label .json as text/plain or octet-stream, so accept those too.
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT)
                .addCategory(Intent.CATEGORY_OPENABLE)
                .setType("*/*")
                .putExtra(Intent.EXTRA_MIME_TYPES, new String[] {
                        SettingsIO.mimeType(), "text/plain", "application/octet-stream"});
        try {
            host.startActivityForResult(intent, REQ_IMPORT);
        } catch (RuntimeException e) {
            reportImportFailure(e);
        }
    }

    private void importFrom(Uri source) {
        busy = true;
        setStatus(importStatus, tr("settings.importing", "正在导入…"));
        new Thread(() -> {
            try {
                String json = readAll(host.getContentResolver().openInputStream(source));
                final int count = SettingsIO.importJson(host, json);
                host.runOnUiThread(() -> {
                    busy = false;
                    if (disposed)
                        return;
                    String message = tr("settings.imported",
                            "已导入 %s 项设置；部分设置需重新进入桌面或重启 App 生效", count);
                    setStatus(importStatus, message);
                    toast(message);
                });
            } catch (Exception e) {
                host.runOnUiThread(() -> {
                    busy = false;
                    if (disposed)
                        return;
                    reportImportFailure(e);
                });
            }
        }, "settings-import").start();
    }

    private void reportImportFailure(Exception e) {
        String message = tr("settings.import_failed", "导入失败：%s", describe(e));
        setStatus(importStatus, message);
        toast(message);
    }

    /** Reads the picked document as UTF-8, with a size guard. */
    private static String readAll(InputStream input) throws IOException {
        if (input == null)
            throw new IOException(tr("settings_io.read_failed", "无法读取文件"));
        try (InputStream in = input) {
            ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            byte[] chunk = new byte[1 << 16];
            int total = 0;
            int n;
            while ((n = in.read(chunk)) > 0) {
                total += n;
                if (total > MAX_IMPORT_BYTES)
                    throw new IOException(tr("settings_io.too_large", "文件过大（超过 1 MB）"));
                buffer.write(chunk, 0, n);
            }
            return new String(buffer.toByteArray(), StandardCharsets.UTF_8);
        }
    }

    /** The document's display name (the user may rename the file in the picker), else the suggested one. */
    private String displayName(Uri uri, String fallback) {
        try (android.database.Cursor cursor = host.getContentResolver().query(uri,
                new String[] {android.provider.OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) {
                int column = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME);
                if (column >= 0 && !cursor.isNull(column))
                    return cursor.getString(column);
            }
        } catch (RuntimeException ignored) {
            // provider without display names: the suggested name is good enough
        }
        return fallback;
    }

    // ---- small shared helpers ------------------------------------------------------------------------------------

    private TextView statusLine() {
        TextView view = Ui.text(host, "", 12, Ui.DIM, false);
        view.setPadding(0, Ui.dp(host, 8), 0, 0);
        return view;
    }

    private void setStatus(TextView view, String text) {
        if (!disposed)
            view.setText(text);
    }

    private void toast(String text) {
        if (!disposed)
            Toasts.show(host, text);
    }

    private static String describe(Exception e) {
        String message = e.getMessage();
        return message == null || message.trim().isEmpty() ? e.toString() : message;
    }

    /**
     * T key that T.java may not have yet: the Lead adds new keys centrally, so until then the Chinese fallback keeps
     * the UI readable — and once the key lands it is used without touching this file.
     */
    private static String tr(String key, String fallback, Object... args) {
        String text = T.t(key, args);
        if (!text.equals(key))
            return text;
        text = fallback;
        for (Object arg : args) {
            int at = text.indexOf("%s");
            if (at < 0)
                break;
            text = text.substring(0, at) + arg + text.substring(at + 2);
        }
        return text;
    }
}
