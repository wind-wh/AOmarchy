package app.aomarchy;

import android.app.Activity;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import app.aomarchy.ui.TerminalView;
import app.aomarchy.ui.Ui;

/**
 * Terminal mode: one pty with a login shell inside the rootfs, no compositor, no desktop. This is the low-resource way
 * to use the Linux side (about a fifth of the desktop's memory, and it starts in a couple of seconds).
 */
public class TerminalActivity extends Activity {
    private TerminalView terminal;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        Prefs.init(this);
        getWindow().addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        if (android.os.Build.VERSION.SDK_INT >= 30) {
            getWindow().setDecorFitsSystemWindows(false);
            WindowInsetsController c = getWindow().getInsetsController();
            if (c != null) {
                c.hide(WindowInsets.Type.statusBars() | WindowInsets.Type.navigationBars());
                c.setSystemBarsBehavior(WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
            }
        }

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Ui.BG);

        terminal = new TerminalView(this);
        root.addView(terminal, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(Ui.dp(this, 12), Ui.dp(this, 8), Ui.dp(this, 12), Ui.dp(this, 8));
        GradientDrawable barBg = new GradientDrawable();
        barBg.setColor(0xCC14171F);
        barBg.setCornerRadius(Ui.dp(this, 14));
        bar.setBackground(barBg);

        TextView back = action("‹ 控制台", Ui.ACCENT, v -> finish());
        bar.addView(back);
        bar.addView(new View(this), new LinearLayout.LayoutParams(0, 1, 1f));
        TextView kb = action("键盘", Ui.DIM, v -> toggleKeyboard());
        bar.addView(kb);
        bar.addView(Ui.space(this, 6));
        TextView restart = action("重启", Ui.DIM, v -> recreate());
        bar.addView(restart);

        FrameLayout.LayoutParams blp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.TOP | Gravity.END);
        blp.topMargin = Ui.dp(this, 10);
        blp.rightMargin = Ui.dp(this, 10);
        root.addView(bar, blp);
        setContentView(root);

        if (!SessionService.rootfsReady(this)) {
            terminal.writeLocal("系统还没安装。回到控制台，在「系统」里装好 AOmarchy 再进终端。\r\n");
        } else {
            terminal.start(SessionService.ptyArgv(this, "exec bash -l"), SessionService.ptyEnv(this),
                    SessionService.ptyCwd(this).getPath());
        }
        terminal.requestFocus();
    }

    private TextView action(String label, int color, View.OnClickListener onClick) {
        TextView t = Ui.text(this, label, 13, color, false);
        t.setPadding(Ui.dp(this, 8), Ui.dp(this, 6), Ui.dp(this, 8), Ui.dp(this, 6));
        t.setOnClickListener(onClick);
        return t;
    }

    private void toggleKeyboard() {
        android.view.inputmethod.InputMethodManager imm =
                (android.view.inputmethod.InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
        if (imm == null)
            return;
        terminal.requestFocus();
        imm.toggleSoftInput(android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT, 0);
    }

    @Override
    protected void onDestroy() {
        if (terminal != null)
            terminal.stop();
        super.onDestroy();
    }
}
