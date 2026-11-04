package app.aomarchy.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.text.InputType;
import android.view.HapticFeedbackConstants;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.inputmethod.BaseInputConnection;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputConnection;

import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;

import app.aomarchy.i18n.T;

/**
 * A small terminal emulator for terminal mode: the pty's bytes go through a VT100/ANSI parser into a character grid,
 * drawn character by character. It covers what a shell and full-screen programs (vim, top, less) need: cursor motion,
 * erase, scroll regions, insert/delete lines, 16/256 colours, the alternate screen and a soft keyboard connection.
 * Beyond the grid it keeps scrollback history (drag up), drag selection (long press) and a word-free copy/paste path.
 */
public class TerminalView extends View {
    private static final int DEFAULT_FG = 0xFFD8DEE9;
    private static final int DEFAULT_BG = 0xFF0A0C10;
    private static final int CURSOR = 0xFF7C8CFF;
    private static final int SELECTION = 0xFF33417E;

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint bgPaint = new Paint();
    private int cellW = 1, cellH = 1;
    private int cols = 80, rows = 24;

    // Grid: one character per cell + fg/bg colour, and a parallel grid for the alternate screen (vim, less).
    private char[] text = new char[cols * rows];
    private int[] fg = new int[cols * rows];
    private int[] bg = new int[cols * rows];
    private char[] altText;
    private int[] altFg;
    private int[] altBg;
    private boolean altScreen;

    private int cx, cy; // cursor
    private int savedX, savedY;
    private int scrollTop, scrollBottom;
    private int curFg = DEFAULT_FG, curBg = DEFAULT_BG;
    private boolean bold, wrapPending = true, cursorVisible = true;

    private FileInputStream in;
    private FileOutputStream out;
    private ParcelFileDescriptor master;
    private Thread reader;
    private int pid = -1;
    private volatile boolean running;

    // Scrollback: history lines live in a ring buffer; the grid itself behaves like the last "rows" lines of the total.
    private static final int MAX_HISTORY = 3000;
    private char[] hText = new char[0];
    private int[] hFg = new int[0];
    private int[] hBg = new int[0];
    private int hCap, hCount, hHead;
    private int scrollOffset; // lines scrolled up from the bottom, 0 = following the output

    // Selection, in absolute line coordinates (history lines first, then the grid rows).
    private int selLine0 = -1, selCol0, selLine1, selCol1;
    private boolean selecting, hasSelection, longPressFired, touchMoved;
    private int nStartLine, nStartCol, nEndLine, nEndCol;

    private float fontSp = 13;
    private int touchSlop;
    private float downX, downY, lastTouchY;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable longPress = this::beginSelection;

    /** Terminal -> activity callbacks: selection changed, a tap on the grid, the scroll position changed. */
    public interface Listener {
        void onSelectionChanged(boolean hasSelection);

        void onTapTerminal();

        void onScrolledChanged(boolean scrolled);
    }

    private Listener listener;

    public void setListener(Listener l) {
        listener = l;
    }

    // Parser state.
    private static final int ST_NORMAL = 0, ST_ESC = 1, ST_CSI = 2, ST_OSC = 3;
    private int state = ST_NORMAL;
    private final int[] params = new int[8];
    private int nParams;
    private int privateMark;
    private char intermediate;

    public TerminalView(Context c) {
        super(c);
        T.lang(c); // the terminal's own notices follow the console's ui_lang (the activity may not have called init)
        setFocusable(true);
        setFocusableInTouchMode(true);
        setBackgroundColor(DEFAULT_BG);
        touchSlop = ViewConfiguration.get(c).getScaledTouchSlop();
        paint.setTypeface(Typeface.MONOSPACE);
        applyTextMetrics(cellPixels(fontSp));
        reset();
    }

    private float dp(float v) {
        return v * getResources().getDisplayMetrics().density;
    }

    private float cellPixels(float valueSp) {
        return valueSp * getResources().getDisplayMetrics().scaledDensity;
    }

    private void applyTextMetrics(float sizePx) {
        paint.setTextSize(sizePx);
        Paint.FontMetrics fm = paint.getFontMetrics();
        cellH = (int) Math.ceil(fm.bottom - fm.top) + (int) Math.ceil(dp(2));
        cellW = Math.max(1, (int) Math.ceil(paint.measureText("M")));
    }

    public float getFontSp() {
        return fontSp;
    }

    /** Changes the font size: the grid is rebuilt for the new cell metrics and the shell gets a resize. */
    public void setFontSp(float valueSp) {
        valueSp = Math.max(9f, Math.min(24f, valueSp));
        if (Math.abs(valueSp - fontSp) < 0.01f)
            return;
        fontSp = valueSp;
        applyTextMetrics(cellPixels(fontSp));
        applyDimensions(getWidth(), getHeight());
        if (getHeight() > 0 && pid > 0)
            writeLocal("\r\n" + T.t("term.font_size", (int) Math.round(valueSp)) + "\r\n");
    }

    /** Starts the pty: argv is the rootfs launcher, env the session environment. */
    public void start(String[] argv, String[] env, String cwd) {
        long packed = app.aomarchy.Native.nativePtyStart(argv, env, cwd, cols, rows);
        if (packed < 0) {
            writeLocal("\r\n" + T.t("term.start_failed") + "\r\n");
            return;
        }
        pid = (int) (packed >> 32);
        fd = (int) packed;
        master = ParcelFileDescriptor.adoptFd(fd);
        try {
            in = new FileInputStream(master.getFileDescriptor());
            out = new FileOutputStream(master.getFileDescriptor());
        } catch (RuntimeException e) {
            writeLocal("\r\n" + T.t("term.pty_failed", e.getMessage()) + "\r\n");
            return;
        }
        running = true;
        reader = new Thread(this::readLoop, "terminal-reader");
        reader.start();
    }

    private void readLoop() {
        byte[] buf = new byte[8192];
        try {
            int n;
            while ((n = in.read(buf)) > 0) {
                feed(buf, n);
                postInvalidateOnAnimation();
            }
        } catch (IOException ignored) {
            // fd closed
        } finally {
            running = false;
            post(() -> writeLocal("\r\n" + T.t("term.session_ended") + "\r\n"));
        }
    }

    /** UTF-8 decode into the parser (ESC and control characters are ASCII, so decoding first is safe). */
    private void feed(byte[] data, int len) {
        int i = 0;
        while (i < len) {
            int b = data[i] & 0xFF;
            int cp;
            int size;
            if (b < 0x80) {
                cp = b;
                size = 1;
            } else if ((b & 0xE0) == 0xC0) {
                cp = b & 0x1F;
                size = 2;
            } else if ((b & 0xF0) == 0xE0) {
                cp = b & 0x0F;
                size = 3;
            } else if ((b & 0xF8) == 0xF0) {
                cp = b & 0x07;
                size = 4;
            } else {
                i++;
                continue;
            }
            if (i + size > len)
                break; // incomplete sequence at the end of this read
            boolean ok = true;
            for (int k = 1; k < size; k++) {
                int c = data[i + k] & 0xFF;
                if ((c & 0xC0) != 0x80) {
                    ok = false;
                    break;
                }
                cp = (cp << 6) | (c & 0x3F);
            }
            if (!ok) {
                i++;
                continue;
            }
            i += size;
            consume(cp);
        }
    }

    private void consume(int cp) {
        synchronized (this) {
            switch (state) {
                case ST_OSC:
                    if (cp == 0x07 || cp == 0x1b)
                        state = cp == 0x1b ? ST_ESC : ST_NORMAL;
                    return;
                case ST_CSI:
                    if (cp >= '0' && cp <= '9') {
                        if (nParams == 0)
                            nParams = 1;
                        int idx = nParams - 1;
                        params[idx] = Math.min(9999, params[idx] * 10 + (cp - '0'));
                        return;
                    }
                    if (cp == ';') {
                        if (nParams < params.length)
                            nParams++;
                        return;
                    }
                    if (cp == '?' || cp == '>' || cp == '!' || cp == '<') {
                        privateMark = cp;
                        return;
                    }
                    if (cp >= 0x20 && cp <= 0x2F) {
                        intermediate = (char) cp;
                        return;
                    }
                    csi((char) cp);
                    state = ST_NORMAL;
                    intermediate = 0;
                    privateMark = 0;
                    nParams = 0;
                    for (int k = 0; k < params.length; k++)
                        params[k] = 0;
                    return;
                case ST_ESC:
                    state = ST_NORMAL;
                    if (cp == '[') {
                        state = ST_CSI;
                        nParams = 0;
                        for (int k = 0; k < params.length; k++)
                            params[k] = 0;
                    } else if (cp == ']') {
                        state = ST_OSC;
                    } else if (cp == '7') {
                        savedX = cx;
                        savedY = cy;
                    } else if (cp == '8') {
                        cx = savedX;
                        cy = savedY;
                    } else if (cp == '(' || cp == ')' || cp == '#') {
                        intermediate = (char) cp; // charset selection: next char is the set
                    } else if (cp == 'M') {
                        scrollUp(scrollTop);
                    } else if (cp == 'c') {
                        reset();
                    } else if (cp == 'D') {
                        lineFeed();
                    } else if (cp == 'E') {
                        cx = 0;
                        lineFeed();
                    }
                    return;
                default:
                    break;
            }
            if (cp == 0x1b) {
                state = ST_ESC;
            } else if (cp == '\r') {
                cx = 0;
                wrapPending = true;
            } else if (cp == '\n' || cp == 0x0B || cp == 0x0C) {
                lineFeed();
            } else if (cp == '\b') {
                if (cx > 0)
                    cx--;
                wrapPending = true;
            } else if (cp == '\t') {
                cx = Math.min(cols - 1, (cx / 8 + 1) * 8);
                wrapPending = true;
            } else if (cp == 0x07) {
                // bell: nothing audible here
            } else if (cp >= 0x20) {
                put((char) cp);
            }
        }
    }

    private void put(char ch) {
        int width = isWide(ch) ? 2 : 1;
        if ((wrapPending && cx + width > cols) || cx + width > cols) {
            cx = 0;
            lineFeed();
        }
        wrapPending = false;
        if (cx < 0)
            cx = 0;
        if (cy < 0)
            cy = 0;
        if (cy >= rows)
            cy = rows - 1;
        int i = cy * cols + cx;
        text[i] = ch;
        fg[i] = bold ? brighten(curFg) : curFg;
        bg[i] = curBg;
        if (width == 2 && cx + 1 < cols) {
            text[i + 1] = 0; // continuation cell of a double-width character
            fg[i + 1] = fg[i];
            bg[i + 1] = curBg;
        }
        cx += width;
        if (cx >= cols)
            wrapPending = true;
    }

    /** East Asian wide characters take two cells; everything else (ASCII, accents) takes one. */
    private static boolean isWide(char c) {
        return (c >= 0x1100 && c <= 0x115F) || (c >= 0x2E80 && c <= 0xA4CF && c != 0x303F)
                || (c >= 0xAC00 && c <= 0xD7A3) || (c >= 0xF900 && c <= 0xFAFF)
                || (c >= 0xFE30 && c <= 0xFE4F) || (c >= 0xFF00 && c <= 0xFF60)
                || (c >= 0xFFE0 && c <= 0xFFE6);
    }

    private static int brighten(int color) {
        int r = Math.min(255, ((color >> 16) & 0xFF) + 40);
        int g = Math.min(255, ((color >> 8) & 0xFF) + 40);
        int b = Math.min(255, (color & 0xFF) + 40);
        return 0xFF000000 | (r << 16) | (g << 8) | b;
    }

    private void lineFeed() {
        if (cy == scrollBottom) {
            scrollUp(scrollTop);
        } else if (cy < rows - 1) {
            cy++;
        }
        wrapPending = true;
    }

    private void scrollUp(int top) {
        char[] buf = activeText();
        int[] f = activeFg(), b = activeBg();
        if (top == 0 && !altScreen)
            rememberTopLine();
        System.arraycopy(buf, (top + 1) * cols, buf, top * cols, (rows - top - 1) * cols);
        System.arraycopy(f, (top + 1) * cols, f, top * cols, (rows - top - 1) * cols);
        System.arraycopy(b, (top + 1) * cols, b, top * cols, (rows - top - 1) * cols);
        int last = (rows - 1) * cols;
        for (int i = 0; i < cols; i++) {
            buf[last + i] = ' ';
            f[last + i] = curFg;
            b[last + i] = curBg;
        }
    }

    private void scrollDown(int top) {
        char[] buf = activeText();
        int[] f = activeFg(), b = activeBg();
        System.arraycopy(buf, top * cols, buf, (top + 1) * cols, (rows - top - 1) * cols);
        System.arraycopy(f, top * cols, f, (top + 1) * cols, (rows - top - 1) * cols);
        System.arraycopy(b, top * cols, b, (top + 1) * cols, (rows - top - 1) * cols);
        for (int i = 0; i < cols; i++) {
            buf[top * cols + i] = ' ';
            f[top * cols + i] = curFg;
            b[top * cols + i] = curBg;
        }
    }

    private char[] activeText() {
        return altScreen ? altText : text;
    }

    private int[] activeFg() {
        return altScreen ? altFg : fg;
    }

    private int[] activeBg() {
        return altScreen ? altBg : bg;
    }

    /** Copies the grid's top row into the scrollback ring before the grid scrolls. */
    private void rememberTopLine() {
        if (hCap == 0 || cols == 0)
            return;
        if (hCount == hCap)
            hHead = (hHead + 1) % hCap;
        else
            hCount++;
        int idx = (hHead + hCount - 1) % hCap;
        int dst = idx * cols;
        System.arraycopy(text, 0, hText, dst, cols);
        System.arraycopy(fg, 0, hFg, dst, cols);
        System.arraycopy(bg, 0, hBg, dst, cols);
        if (scrollOffset > 0) // keep the user's place while output keeps arriving
            scrollOffset = Math.min(scrollOffset + 1, hCount);
    }

    private void csi(char finalChar) {
        int p1 = nParams > 0 ? params[0] : 0;
        int p2 = nParams > 1 ? params[1] : 0;
        switch (finalChar) {
            case 'A':
                cy = Math.max(scrollTop, cy - Math.max(1, p1));
                break;
            case 'B':
                cy = Math.min(scrollBottom, cy + Math.max(1, p1));
                break;
            case 'C':
                cx = Math.min(cols - 1, cx + Math.max(1, p1));
                wrapPending = false;
                break;
            case 'D':
                cx = Math.max(0, cx - Math.max(1, p1));
                wrapPending = false;
                break;
            case 'E':
                cy = Math.min(rows - 1, cy + Math.max(1, p1));
                cx = 0;
                break;
            case 'F':
                cy = Math.max(0, cy - Math.max(1, p1));
                cx = 0;
                break;
            case 'G':
                cx = Math.min(cols - 1, Math.max(1, p1) - 1);
                break;
            case 'd':
                cy = Math.min(rows - 1, Math.max(1, p1) - 1);
                break;
            case 'H':
            case 'f': {
                int row = Math.max(1, p1) - 1;
                int col = nParams > 1 ? Math.max(1, p2) - 1 : 0;
                cy = Math.min(rows - 1, row);
                cx = Math.min(cols - 1, col);
                wrapPending = false;
                break;
            }
            case 'J': {
                char[] buf = activeText();
                int[] f = activeFg(), b = activeBg();
                if (p1 == 0) {
                    for (int i = cy * cols + cx; i < cols * rows; i++) blank(buf, f, b, i);
                } else if (p1 == 1) {
                    for (int i = 0; i <= cy * cols + cx && i < cols * rows; i++) blank(buf, f, b, i);
                } else {
                    for (int i = 0; i < cols * rows; i++) blank(buf, f, b, i);
                }
                break;
            }
            case 'K': {
                char[] buf = activeText();
                int[] f = activeFg(), b = activeBg();
                if (p1 == 0) {
                    for (int i = cy * cols + cx; i < (cy + 1) * cols; i++) blank(buf, f, b, i);
                } else if (p1 == 1) {
                    for (int i = cy * cols; i <= cy * cols + cx; i++) blank(buf, f, b, i);
                } else {
                    for (int i = cy * cols; i < (cy + 1) * cols; i++) blank(buf, f, b, i);
                }
                break;
            }
            case 'm':
                sgr();
                break;
            case 'h':
            case 'l': {
                boolean on = finalChar == 'h';
                if (privateMark == '?' && p1 == 25)
                    cursorVisible = on;
                else if (privateMark == '?' && (p1 == 1049 || p1 == 47 || p1 == 1047))
                    switchAlt(on);
                break;
            }
            case 'r': {
                int top = nParams > 0 ? Math.max(1, p1) - 1 : 0;
                int bottom = nParams > 1 ? Math.min(rows, p2) - 1 : rows - 1;
                scrollTop = Math.min(top, rows - 1);
                scrollBottom = Math.max(scrollTop, bottom);
                cx = 0;
                cy = scrollTop;
                break;
            }
            case 'L':
                if (cy >= scrollTop && cy <= scrollBottom) {
                    for (int k = 0; k < Math.max(1, p1); k++)
                        scrollDown(cy);
                }
                break;
            case 'M':
                if (cy >= scrollTop && cy <= scrollBottom) {
                    for (int k = 0; k < Math.max(1, p1); k++)
                        scrollUp(cy);
                }
                break;
            case 'S':
                for (int k = 0; k < Math.max(1, p1); k++) scrollUp(scrollTop);
                break;
            case 'T':
                for (int k = 0; k < Math.max(1, p1); k++) scrollDown(scrollTop);
                break;
            case '@': {
                char[] buf = activeText();
                int[] f = activeFg(), b = activeBg();
                int rowStart = cy * cols;
                int n = Math.max(1, p1);
                System.arraycopy(buf, rowStart + cx, buf, rowStart + cx + n, cols - cx - n);
                System.arraycopy(f, rowStart + cx, f, rowStart + cx + n, cols - cx - n);
                System.arraycopy(b, rowStart + cx, b, rowStart + cx + n, cols - cx - n);
                for (int i = 0; i < n; i++) blank(buf, f, b, rowStart + cx + i);
                break;
            }
            case 'P': {
                char[] buf = activeText();
                int[] f = activeFg(), b = activeBg();
                int rowStart = cy * cols;
                int n = Math.max(1, p1);
                System.arraycopy(buf, rowStart + cx + n, buf, rowStart + cx, cols - cx - n);
                System.arraycopy(f, rowStart + cx + n, f, rowStart + cx, cols - cx - n);
                System.arraycopy(b, rowStart + cx + n, b, rowStart + cx, cols - cx - n);
                for (int i = 0; i < n; i++) blank(buf, f, b, rowStart + cols - 1 - i);
                break;
            }
            case 'X': {
                char[] buf = activeText();
                int[] f = activeFg(), b = activeBg();
                int start = cy * cols + cx;
                for (int i = 0; i < Math.max(1, p1) && start + i < cols * rows; i++) blank(buf, f, b, start + i);
                break;
            }
            default:
                break;
        }
    }

    private void blank(char[] buf, int[] f, int[] b, int i) {
        buf[i] = ' ';
        f[i] = curFg;
        b[i] = curBg;
    }

    private void sgr() {
        if (nParams == 0) {
            curFg = DEFAULT_FG;
            curBg = DEFAULT_BG;
            bold = false;
            return;
        }
        for (int i = 0; i < nParams; i++) {
            int p = params[i];
            if (p == 0) {
                curFg = DEFAULT_FG;
                curBg = DEFAULT_BG;
                bold = false;
            } else if (p == 1) {
                bold = true;
            } else if (p == 22) {
                bold = false;
            } else if (p >= 30 && p <= 37) {
                curFg = ansi(p - 30);
            } else if (p >= 90 && p <= 97) {
                curFg = ansi(p - 90 + 8);
            } else if (p == 39) {
                curFg = DEFAULT_FG;
            } else if (p >= 40 && p <= 47) {
                curBg = ansi(p - 40);
            } else if (p >= 100 && p <= 107) {
                curBg = ansi(p - 100 + 8);
            } else if (p == 49) {
                curBg = DEFAULT_BG;
            } else if (p == 38 || p == 48) {
                int color = -1;
                if (i + 2 < nParams && params[i + 1] == 5) {
                    color = xterm256(params[i + 2]);
                    i += 2;
                } else if (i + 4 < nParams && params[i + 1] == 2) {
                    color = 0xFF000000 | (params[i + 2] << 16) | (params[i + 3] << 8) | params[i + 4];
                    i += 4;
                }
                if (color != -1) {
                    if (p == 38)
                        curFg = color;
                    else
                        curBg = color;
                }
            }
        }
    }

    private static final int[] ANSI = {
            0xFF3B4252, 0xFFBF616A, 0xFFA3BE8C, 0xFFEBCB8B,
            0xFF81A1C1, 0xFFB48EAD, 0xFF88C0D0, 0xFFE5E9F0,
            0xFF4C566A, 0xFFD08770, 0xFF8FBCBB, 0xFFFFD580,
            0xFF9DB8E8, 0xFFC99BD6, 0xFF9FD8E8, 0xFFFFFFFF};

    private static int ansi(int i) {
        return ANSI[Math.max(0, Math.min(15, i))];
    }

    private static int xterm256(int n) {
        if (n < 16)
            return ansi(n);
        if (n >= 232) {
            int v = 8 + (n - 232) * 10;
            return 0xFF000000 | (v << 16) | (v << 8) | v;
        }
        int i = n - 16;
        int r = i / 36, g = (i % 36) / 6, b = i % 6;
        int[] steps = {0, 95, 135, 175, 215, 255};
        return 0xFF000000 | (steps[r] << 16) | (steps[g] << 8) | steps[b];
    }

    private void switchAlt(boolean on) {
        if (on == altScreen)
            return;
        if (on) {
            if (altText == null || altText.length != cols * rows) {
                altText = new char[cols * rows];
                altFg = new int[cols * rows];
                altBg = new int[cols * rows];
            }
            altScreen = true;
            clearActive();
        } else {
            altScreen = false;
        }
        cx = 0;
        cy = 0;
    }

    private void clearActive() {
        char[] buf = activeText();
        int[] f = activeFg(), b = activeBg();
        for (int i = 0; i < cols * rows; i++) {
            buf[i] = ' ';
            f[i] = curFg;
            b[i] = curBg;
        }
    }

    private void reset() {
        text = new char[cols * rows];
        fg = new int[cols * rows];
        bg = new int[cols * rows];
        altText = null;
        altFg = null;
        altBg = null;
        altScreen = false;
        // History lines are stored with the column count they were written at, so a resize starts a fresh one.
        hCap = MAX_HISTORY;
        hText = new char[hCap * cols];
        hFg = new int[hCap * cols];
        hBg = new int[hCap * cols];
        hCount = 0;
        hHead = 0;
        scrollOffset = 0;
        clearSelection();
        cx = cy = 0;
        scrollTop = 0;
        scrollBottom = rows - 1;
        curFg = DEFAULT_FG;
        curBg = DEFAULT_BG;
        bold = false;
        cursorVisible = true;
        clearActive();
    }

    private void clearSelection() {
        selLine0 = -1;
        hasSelection = false;
        selecting = false;
        if (listener != null)
            listener.onSelectionChanged(false);
    }

    /** Local echo of messages (errors), so the view is never just black when the pty cannot start. */
    public void writeLocal(String s) {
        synchronized (this) {
            for (char ch : s.toCharArray()) {
                if (ch == '\n')
                    consumeRaw('\n');
                else if (ch == '\r')
                    consumeRaw('\r');
                else
                    consumeRaw(ch);
            }
            invalidate();
        }
    }

    private void consumeRaw(int cp) {
        if (cp == '\r')
            cx = 0;
        else if (cp == '\n')
            lineFeed();
        else if (cp >= 0x20)
            put((char) cp);
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        applyDimensions(w, h);
    }

    /** Fits the grid to the view: cols/rows from the cell metrics, then the shell is told about the new size. */
    private void applyDimensions(int w, int h) {
        if (w <= 0 || h <= 0)
            return;
        int availW = Math.max(1, w - getPaddingLeft() - getPaddingRight());
        int availH = Math.max(1, h - getPaddingTop() - getPaddingBottom());
        cols = Math.max(20, availW / cellW);
        rows = Math.max(4, availH / cellH);
        scrollTop = 0;
        scrollBottom = rows - 1;
        reset();
        if (pid > 0)
            app.aomarchy.Native.nativePtyResize((int) pidPackedFd(), cols, rows);
        invalidate();
    }

    private int fd = -1;

    private long pidPackedFd() {
        return ((long) pid << 32) | fd;
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float baseline = -paint.getFontMetrics().top;
        if (scrollOffset > hCount)
            scrollOffset = hCount;
        int topAbs = hCount - scrollOffset; // absolute line shown at the top of the view
        normalizeSelection();
        boolean selActive = hasSelection || (selecting && selLine0 >= 0);
        for (int vr = 0; vr < rows; vr++) {
            int abs = topAbs + vr;
            char[] lt;
            int[] lf, lb;
            int base;
            if (abs < hCount) {
                base = ((hHead + abs) % hCap) * cols;
                lt = hText;
                lf = hFg;
                lb = hBg;
            } else {
                int row = abs - hCount;
                if (row >= rows)
                    continue;
                base = row * cols;
                lt = text;
                lf = fg;
                lb = bg;
            }
            for (int col = 0; col < cols; col++) {
                int i = base + col;
                float left = getPaddingLeft() + col * cellW;
                float top = getPaddingTop() + vr * cellH;
                int color = lb[i];
                boolean inSel = selActive && isSelected(abs, col);
                if (inSel) {
                    bgPaint.setColor(SELECTION);
                    canvas.drawRect(left, top, left + cellW, top + cellH, bgPaint);
                } else if (color != DEFAULT_BG) {
                    bgPaint.setColor(color);
                    canvas.drawRect(left, top, left + cellW, top + cellH, bgPaint);
                }
                char ch = lt[i];
                if (ch == ' ' || ch == 0)
                    continue;
                paint.setColor(lf[i]);
                canvas.drawText(String.valueOf(ch), left, top + baseline, paint);
            }
        }
        int cursorRow = cy + hCount - topAbs;
        if (cursorVisible && scrollOffset == 0 && cursorRow >= 0 && cursorRow < rows && cx >= 0 && cx < cols
                && hasWindowFocus()) {
            bgPaint.setColor(CURSOR);
            bgPaint.setAlpha(160);
            canvas.drawRect(getPaddingLeft() + cx * cellW, getPaddingTop() + cursorRow * cellH,
                    getPaddingLeft() + (cx + 1) * cellW, getPaddingTop() + (cursorRow + 1) * cellH, bgPaint);
        }
        // A slim indicator while the view is not following the output.
        if (scrollOffset > 0) {
            bgPaint.setColor(CURSOR);
            bgPaint.setAlpha(90);
            canvas.drawRect(getWidth() - dp(3), getPaddingTop(), getWidth(), getHeight() - getPaddingBottom(), bgPaint);
        }
    }

    private boolean isSelected(int abs, int col) {
        if (nStartLine < 0)
            return false;
        if (abs < nStartLine || abs > nEndLine)
            return false;
        if (abs == nStartLine && abs == nEndLine)
            return col >= nStartCol && col <= nEndCol;
        if (abs == nStartLine)
            return col >= nStartCol;
        if (abs == nEndLine)
            return col <= nEndCol;
        return true;
    }

    /** Normalises the anchor/focus pair into the start and end line/column of the selection. */
    private void normalizeSelection() {
        nStartLine = -1;
        if (selLine0 < 0)
            return;
        boolean forward = selLine0 < selLine1 || (selLine0 == selLine1 && selCol0 <= selCol1);
        nStartLine = forward ? selLine0 : selLine1;
        nStartCol = forward ? selCol0 : selCol1;
        nEndLine = forward ? selLine1 : selLine0;
        nEndCol = forward ? selCol1 : selCol0;
    }

    /** Writes text to the pty. Any input snaps the view back to the live output. */
    public void write(String s) {
        if (scrollOffset != 0) {
            scrollOffset = 0;
            notifyScrolled();
            invalidate();
        }
        if (out == null)
            return;
        try {
            out.write(s.getBytes(StandardCharsets.UTF_8));
            out.flush();
        } catch (IOException ignored) {
            // session ended
        }
    }

    public boolean isScrolled() {
        return scrollOffset > 0;
    }

    public void scrollToBottom() {
        if (scrollOffset == 0)
            return;
        scrollOffset = 0;
        notifyScrolled();
        invalidate();
    }

    private void notifyScrolled() {
        if (listener != null)
            listener.onScrolledChanged(scrollOffset > 0);
    }

    // ---- scrollback, selection and copy ---------------------------------------------------------------------

    /** Absolute line index (history first) under a y coordinate. */
    private int lineAt(float y) {
        int topAbs = hCount - scrollOffset;
        int line = topAbs + (int) ((y - getPaddingTop()) / cellH);
        return Math.max(0, Math.min(hCount + rows - 1, line));
    }

    private int colAt(float x) {
        return Math.max(0, Math.min(cols - 1, (int) ((x - getPaddingLeft()) / cellW)));
    }

    /** Text of one absolute line with trailing blanks trimmed; null when the line has no content. */
    private String lineText(int abs) {
        char[] lt;
        int base;
        if (abs < hCount) {
            base = ((hHead + abs) % hCap) * cols;
            lt = hText;
        } else {
            int row = abs - hCount;
            if (row < 0 || row >= rows)
                return null;
            base = row * cols;
            lt = text;
        }
        StringBuilder sb = new StringBuilder(cols);
        for (int c = 0; c < cols; c++) {
            char ch = lt[base + c];
            sb.append(ch == 0 ? ' ' : ch);
        }
        int end = sb.length();
        while (end > 0 && sb.charAt(end - 1) == ' ')
            end--;
        return end == 0 ? null : sb.substring(0, end);
    }

    /** The selected text, or null when nothing is selected. Selection is inclusive on both ends. */
    public String selectedText() {
        if (!hasSelection || selLine0 < 0)
            return null;
        normalizeSelection();
        StringBuilder sb = new StringBuilder();
        for (int line = nStartLine; line <= nEndLine; line++) {
            String raw = lineText(line);
            if (raw == null)
                raw = "";
            int from = line == nStartLine ? Math.min(nStartCol, raw.length()) : 0;
            int to = line == nEndLine ? Math.min(nEndCol + 1, raw.length()) : raw.length();
            if (to < from)
                to = from;
            if (sb.length() > 0)
                sb.append('\n');
            sb.append(raw, from, to);
        }
        return sb.toString();
    }

    /** The visible grid as text, used when copying without a selection. */
    public String screenText() {
        int topAbs = hCount - scrollOffset;
        StringBuilder sb = new StringBuilder();
        int lastContent = -1;
        String[] lines = new String[rows];
        for (int r = 0; r < rows; r++) {
            lines[r] = lineText(topAbs + r);
            if (lines[r] != null)
                lastContent = r;
        }
        for (int r = 0; r <= lastContent; r++) {
            if (r > 0)
                sb.append('\n');
            if (lines[r] != null)
                sb.append(lines[r]);
        }
        return sb.toString();
    }

    public boolean hasSelection() {
        return hasSelection;
    }

    private void beginSelection() {
        longPressFired = true;
        int line = lineAt(downY);
        int col = colAt(downX);
        selLine0 = line;
        selCol0 = col;
        selLine1 = line;
        selCol1 = col;
        selecting = true;
        hasSelection = false;
        performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
        invalidate();
    }

    private void extendSelection(float x, float y) {
        selLine1 = lineAt(y);
        selCol1 = colAt(x);
        hasSelection = selLine1 != selLine0 || selCol1 != selCol0;
        invalidate();
    }

    private void finishSelection() {
        selecting = false;
        hasSelection = selLine0 >= 0 && (selLine1 != selLine0 || selCol1 != selCol0);
        if (listener != null)
            listener.onSelectionChanged(hasSelection);
        invalidate();
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                downX = e.getX();
                downY = e.getY();
                lastTouchY = downY;
                longPressFired = false;
                touchMoved = false;
                handler.postDelayed(longPress, 380);
                return true;
            case MotionEvent.ACTION_MOVE:
                if (longPressFired) {
                    extendSelection(e.getX(), e.getY());
                    return true;
                }
                if (!touchMoved
                        && (Math.abs(e.getX() - downX) > touchSlop || Math.abs(e.getY() - downY) > touchSlop)) {
                    touchMoved = true;
                    handler.removeCallbacks(longPress);
                }
                if (touchMoved && !altScreen && hCount > 0) {
                    float dy = lastTouchY - e.getY();
                    int lines = (int) (dy / cellH);
                    if (lines != 0) {
                        lastTouchY -= lines * cellH;
                        int next = Math.max(0, Math.min(hCount, scrollOffset + lines));
                        if (next != scrollOffset) {
                            scrollOffset = next;
                            notifyScrolled();
                            invalidate();
                        }
                    }
                }
                return true;
            case MotionEvent.ACTION_UP:
                handler.removeCallbacks(longPress);
                if (longPressFired) {
                    finishSelection();
                } else if (!touchMoved) {
                    if (isScrolled()) {
                        scrollToBottom();
                    } else if (hasSelection) {
                        clearSelection();
                        invalidate();
                    } else if (listener != null) {
                        listener.onTapTerminal();
                    }
                }
                return true;
            case MotionEvent.ACTION_CANCEL:
                handler.removeCallbacks(longPress);
                if (longPressFired)
                    finishSelection();
                return true;
            default:
                return super.onTouchEvent(e);
        }
    }

    public void stop() {
        running = false;
        try {
            if (in != null)
                in.close();
        } catch (IOException ignored) {
        }
        if (master != null) {
            try {
                master.close();
            } catch (IOException ignored) {
            }
            master = null;
        }
        if (fd >= 0) {
            app.aomarchy.Native.nativePtyClose(fd);
            fd = -1;
        }
        if (pid > 0) {
            try {
                android.system.Os.kill(pid, android.system.OsConstants.SIGTERM);
            } catch (Exception ignored) {
            }
            pid = -1;
        }
    }

    public boolean isRunning() {
        return running;
    }

    // ---- input ---------------------------------------------------------------------------------------------

    @Override
    public boolean onCheckIsTextEditor() {
        return true;
    }

    @Override
    public InputConnection onCreateInputConnection(EditorInfo outAttrs) {
        // Plain text, not a password variation: MIUI switches to its full-screen security keyboard for password
        // editors, and that window sat over the terminal as a black sheet.
        outAttrs.inputType = InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
                | InputType.TYPE_TEXT_FLAG_MULTI_LINE;
        outAttrs.imeOptions = EditorInfo.IME_ACTION_NONE | EditorInfo.IME_FLAG_NO_FULLSCREEN
                | EditorInfo.IME_FLAG_NO_EXTRACT_UI;
        return new BaseInputConnection(this, true) {
            @Override
            public boolean commitText(CharSequence text, int newCursorPosition) {
                write(text.toString());
                return true;
            }

            @Override
            public boolean sendKeyEvent(KeyEvent event) {
                if (event.getAction() == KeyEvent.ACTION_DOWN) {
                    handleKey(event.getKeyCode(), event);
                    return true;
                }
                return true;
            }
        };
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        return handleKey(keyCode, event);
    }

    private boolean handleKey(int keyCode, KeyEvent event) {
        switch (keyCode) {
            case KeyEvent.KEYCODE_ENTER:
                write("\r");
                return true;
            case KeyEvent.KEYCODE_DEL:
                write("\u007f");
                return true;
            case KeyEvent.KEYCODE_ESCAPE:
                write("\u001b");
                return true;
            case KeyEvent.KEYCODE_TAB:
                write("\t");
                return true;
            case KeyEvent.KEYCODE_DPAD_UP:
                write("\u001b[A");
                return true;
            case KeyEvent.KEYCODE_DPAD_DOWN:
                write("\u001b[B");
                return true;
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                write("\u001b[C");
                return true;
            case KeyEvent.KEYCODE_DPAD_LEFT:
                write("\u001b[D");
                return true;
            case KeyEvent.KEYCODE_MOVE_HOME:
                write("\u001b[H");
                return true;
            case KeyEvent.KEYCODE_MOVE_END:
                write("\u001b[F");
                return true;
            default:
                if (event.getUnicodeChar() != 0 && (event.getMetaState() & KeyEvent.META_CTRL_ON) == 0) {
                    write(String.valueOf((char) event.getUnicodeChar()));
                    return true;
                }
                if ((event.getMetaState() & KeyEvent.META_CTRL_ON) != 0) {
                    int c = keyCode;
                    if (c >= KeyEvent.KEYCODE_A && c <= KeyEvent.KEYCODE_Z) {
                        write(String.valueOf((char) (c - KeyEvent.KEYCODE_A + 1)));
                        return true;
                    }
                }
                return false;
        }
    }
}
