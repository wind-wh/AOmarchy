package app.aomarchy.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.os.ParcelFileDescriptor;
import android.text.InputType;
import android.view.KeyEvent;
import android.view.View;
import android.view.inputmethod.BaseInputConnection;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputConnection;

import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;

/**
 * A small terminal emulator for terminal mode: the pty's bytes go through a VT100/ANSI parser into a character grid,
 * drawn character by character. It covers what a shell and full-screen programs (vim, top, less) need: cursor motion,
 * erase, scroll regions, insert/delete lines, 16/256 colours, the alternate screen and a soft keyboard connection.
 */
public class TerminalView extends View {
    private static final int DEFAULT_FG = 0xFFD8DEE9;
    private static final int DEFAULT_BG = 0xFF0A0C10;
    private static final int CURSOR = 0xFF7C8CFF;

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

    // Parser state.
    private static final int ST_NORMAL = 0, ST_ESC = 1, ST_CSI = 2, ST_OSC = 3;
    private int state = ST_NORMAL;
    private final int[] params = new int[8];
    private int nParams;
    private int privateMark;
    private char intermediate;

    public TerminalView(Context c) {
        super(c);
        setFocusable(true);
        setFocusableInTouchMode(true);
        setBackgroundColor(DEFAULT_BG);
        paint.setTypeface(Typeface.MONOSPACE);
        paint.setTextSize(dp(13));
        cellH = (int) Math.ceil(paint.getFontMetrics().bottom - paint.getFontMetrics().top) + (int) Math.ceil(dp(2));
        cellW = Math.max(1, (int) Math.ceil(paint.measureText("M")));
        reset();
    }

    private float dp(float v) {
        return v * getResources().getDisplayMetrics().density;
    }

    /** Starts the pty: argv is the rootfs launcher, env the session environment. */
    public void start(String[] argv, String[] env, String cwd) {
        long packed = app.aomarchy.Native.nativePtyStart(argv, env, cwd, cols, rows);
        if (packed < 0) {
            writeLocal("\r\n[无法启动终端]\r\n");
            return;
        }
        pid = (int) (packed >> 32);
        fd = (int) packed;
        master = ParcelFileDescriptor.adoptFd(fd);
        try {
            in = new FileInputStream(master.getFileDescriptor());
            out = new FileOutputStream(master.getFileDescriptor());
        } catch (RuntimeException e) {
            writeLocal("\r\n[无法连接 pty: " + e.getMessage() + "]\r\n");
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
            post(() -> writeLocal("\r\n[会话已结束]\r\n"));
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
        if (wrapPending && cx >= cols) {
            cx = 0;
            lineFeed();
        }
        wrapPending = false;
        if (cx < 0)
            cx = 0;
        if (cx >= cols)
            cx = cols - 1;
        if (cy < 0)
            cy = 0;
        if (cy >= rows)
            cy = rows - 1;
        int i = cy * cols + cx;
        text[i] = ch;
        fg[i] = bold ? brighten(curFg) : curFg;
        bg[i] = curBg;
        cx++;
        if (cx >= cols)
            wrapPending = true;
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
        cx = cy = 0;
        scrollTop = 0;
        scrollBottom = rows - 1;
        curFg = DEFAULT_FG;
        curBg = DEFAULT_BG;
        bold = false;
        cursorVisible = true;
        clearActive();
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
        int availW = Math.max(1, w - getPaddingLeft() - getPaddingRight());
        int availH = Math.max(1, h - getPaddingTop() - getPaddingBottom());
        int newCols = Math.max(20, availW / cellW);
        int newRows = Math.max(4, availH / cellH);
        if (newCols == cols && newRows == rows)
            return;
        cols = newCols;
        rows = newRows;
        scrollTop = 0;
        scrollBottom = rows - 1;
        reset();
        if (pid > 0)
            app.aomarchy.Native.nativePtyResize((int) (pidPackedFd()), cols, rows);
    }

    private int fd = -1;

    private long pidPackedFd() {
        return ((long) pid << 32) | fd;
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        char[] buf = activeText();
        int[] f = activeFg(), b = activeBg();
        float baseline = -paint.getFontMetrics().top;
        for (int row = 0; row < rows; row++) {
            for (int col = 0; col < cols; col++) {
                int i = row * cols + col;
                int color = b[i];
                if (color != DEFAULT_BG) {
                    bgPaint.setColor(color);
                    canvas.drawRect(getPaddingLeft() + col * cellW, getPaddingTop() + row * cellH,
                            getPaddingLeft() + (col + 1) * cellW, getPaddingTop() + (row + 1) * cellH, bgPaint);
                }
            }
        }
        for (int row = 0; row < rows; row++) {
            for (int col = 0; col < cols; col++) {
                int i = row * cols + col;
                char ch = buf[i];
                if (ch == ' ' || ch == 0)
                    continue;
                paint.setColor(f[i]);
                canvas.drawText(String.valueOf(ch), getPaddingLeft() + col * cellW,
                        getPaddingTop() + row * cellH + baseline, paint);
            }
        }
        if (cursorVisible && cy >= 0 && cy < rows && cx >= 0 && cx < cols && hasWindowFocus()) {
            bgPaint.setColor(CURSOR);
            bgPaint.setAlpha(160);
            canvas.drawRect(getPaddingLeft() + cx * cellW, getPaddingTop() + cy * cellH,
                    getPaddingLeft() + (cx + 1) * cellW, getPaddingTop() + (cy + 1) * cellH, bgPaint);
        }
    }

    /** Writes text to the pty. */
    public void write(String s) {
        if (out == null)
            return;
        try {
            out.write(s.getBytes(StandardCharsets.UTF_8));
            out.flush();
        } catch (IOException ignored) {
            // session ended
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
