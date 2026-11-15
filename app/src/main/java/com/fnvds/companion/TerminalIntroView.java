package com.fnvds.companion;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.os.SystemClock;
import android.view.View;

import java.util.ArrayDeque;

/** Terminal startup display. Status changes and animation frames run on the UI thread. */
final class TerminalIntroView extends View {
    static final String WAITING_FOR_GAME = "WAITING FOR NEW VEGAS";
    static final String WAITING_FOR_SAVE = "WAITING FOR LOADED SAVE";
    static final String CONNECTED = "CONNECTED";

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final Typeface mono = Typeface.create(Typeface.MONOSPACE, Typeface.NORMAL);
    private final Typeface wordmark = Typeface.create(Typeface.DEFAULT, Typeface.BOLD_ITALIC);
    private final Runnable onConnected;
    private final String[] introText;
    private final int[] visibleCharacters;
    private final ArrayDeque<String> pendingStatuses = new ArrayDeque<>();
    private int accent;
    private int introLine;
    private String statusTarget = WAITING_FOR_GAME;
    private String requestedStatus = WAITING_FOR_GAME;
    private String statusText = "";
    private boolean backspacing;
    private boolean completed;
    private long nextCharacterAt;
    private long holdUntil;
    private boolean pluginConnected;
    private String pluginVersion;

    private final Runnable frame = new Runnable() {
        @Override public void run() {
            if (!isAttachedToWindow() || !isShown() || getWindowVisibility() != VISIBLE) return;
            long now = SystemClock.uptimeMillis();
            if (now >= nextCharacterAt) advance(now);
            invalidate();
            postDelayed(this, 16);
        }
    };

    TerminalIntroView(Context context, int color, String version, Runnable onConnected) {
        super(context);
        accent = color;
        this.onConnected = onConnected;
        introText = new String[]{"FNVDS", "DUAL SCREEN COMPANION", "WELCOME, COURIER.", "APP: " + version};
        visibleCharacters = new int[introText.length];
        setContentDescription("FNVDS startup. " + WAITING_FOR_GAME);
        setClickable(true);
    }

    void setAccentColor(int color) {
        accent = color;
        invalidate();
    }

    void setPluginConnection(boolean connected) {
        pluginConnected = connected;
        if (!connected) pluginVersion = null;
        invalidate();
    }

    void setPluginVersion(String version) {
        if (pluginConnected && version.equals(pluginVersion)) return;
        pluginConnected = true;
        pluginVersion = version;
        invalidate();
    }

    void setStatus(String status) {
        if (requestedStatus.equals(status)) return;
        requestedStatus = status;
        completed = false;
        if (!CONNECTED.equals(status)) {
            // Never finish the intro for an earlier READY after a disconnect or save change.
            pendingStatuses.removeIf(CONNECTED::equals);
            if (!WAITING_FOR_SAVE.equals(status)) pendingStatuses.clear();
            if (CONNECTED.equals(statusTarget)) {
                backspacing = true;
                holdUntil = 0;
            }
        }
        if (!status.equals(pendingStatuses.peekLast())
                && !(pendingStatuses.isEmpty() && status.equals(statusTarget))) {
            pendingStatuses.addLast(status);
        }
        nextCharacterAt = Math.min(nextCharacterAt, SystemClock.uptimeMillis());
        setContentDescription("FNVDS startup. " + status);
    }

    private void advance(long now) {
        if (introLine < introText.length) {
            if (visibleCharacters[introLine] < introText[introLine].length()) {
                visibleCharacters[introLine]++;
                nextCharacterAt = now + 22;
            } else {
                introLine++;
                nextCharacterAt = now + 110;
            }
            return;
        }
        if (backspacing) {
            if (!statusText.isEmpty()) {
                statusText = statusText.substring(0, statusText.length() - 1);
                nextCharacterAt = now + 12;
            } else {
                if (!pendingStatuses.isEmpty()) statusTarget = pendingStatuses.removeFirst();
                backspacing = false;
                nextCharacterAt = now + 90;
            }
            return;
        }
        if (statusText.length() < statusTarget.length()) {
            statusText = statusTarget.substring(0, statusText.length() + 1);
            nextCharacterAt = now + 26;
            if (statusText.equals(statusTarget)) {
                holdUntil = now + (CONNECTED.equals(statusTarget) ? 550 : 320);
            }
            return;
        }
        if (now < holdUntil) {
            nextCharacterAt = holdUntil;
            return;
        }
        if (!pendingStatuses.isEmpty()) {
            backspacing = true;
            nextCharacterAt = now + 12;
        } else if (!completed && CONNECTED.equals(statusTarget)
                && CONNECTED.equals(requestedStatus)) {
            completed = true;
            onConnected.run();
        }
    }

    @Override protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        removeCallbacks(frame);
        post(frame);
    }

    @Override protected void onVisibilityChanged(View changedView, int visibility) {
        super.onVisibilityChanged(changedView, visibility);
        if (frame == null) return;
        removeCallbacks(frame);
        if (visibility == VISIBLE && isAttachedToWindow()) post(frame);
    }

    @Override protected void onWindowVisibilityChanged(int visibility) {
        super.onWindowVisibilityChanged(visibility);
        if (frame == null) return;
        removeCallbacks(frame);
        if (visibility == VISIBLE && isAttachedToWindow() && isShown()) post(frame);
    }

    @Override protected void onDetachedFromWindow() {
        removeCallbacks(frame);
        super.onDetachedFromWindow();
    }

    private void text(Canvas canvas, String text, float x, float y, float size,
                      Typeface typeface, int color) {
        paint.setStyle(Paint.Style.FILL);
        paint.setTypeface(typeface);
        paint.setTextSize(size);
        paint.setColor(color);
        canvas.drawText(text, x, y, paint);
    }

    private void introText(Canvas canvas, int line, float x, float y, float size,
                           Typeface typeface, int color) {
        text(canvas, introText[line].substring(0, visibleCharacters[line]),
                x, y, size, typeface, color);
    }

    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        canvas.drawColor(Color.rgb(5, 10, 8));
        float scale = Math.min(getWidth() / 1448f, getHeight() / 1086f);
        if (scale <= 0) return;
        float offsetX = (getWidth() - 1448 * scale) / 2;
        float offsetY = (getHeight() - 1086 * scale) / 2;
        // Extend the frame to the actual view bounds without stretching the text.
        float left = -offsetX / scale;
        float top = -offsetY / scale;
        float right = (getWidth() - offsetX) / scale;
        float bottom = (getHeight() - offsetY) / scale;
        canvas.save();
        canvas.translate(offsetX, offsetY);
        canvas.scale(scale, scale);

        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(3);
        paint.setColor(accent);
        canvas.drawRect(left + 17, top + 17, right - 17, bottom - 17, paint);
        int cream = Color.rgb(255, 245, 220);
        introText(canvas, 0, 80, 438, 225, wordmark, cream);
        introText(canvas, 1, 82, 508, 44, mono, accent);
        paint.setColor(accent);
        paint.setStrokeWidth(7);
        if (visibleCharacters[1] > 0) canvas.drawLine(82, 571, 295, 571, paint);
        introText(canvas, 2, 90, 660, 43, mono, accent);
        paint.setTypeface(mono);
        paint.setTextSize(32);
        float versionX = right - 58 - paint.measureText(introText[3]);
        float versionY = bottom - 56;
        introText(canvas, 3, versionX, versionY, 32, mono, accent);
        if (visibleCharacters[3] > 0) {
            String pluginLabel = "PLUGIN: " + (!pluginConnected ? "NOT CONNECTED"
                    : pluginVersion == null ? "NOT REPORTED" : pluginVersion);
            paint.setTypeface(mono);
            paint.setTextSize(32);
            text(canvas, pluginLabel, right - 58 - paint.measureText(pluginLabel),
                    versionY - 44, 32, mono, accent);
        }
        text(canvas, statusText, 90, 727, 43, mono, accent);

        if (introLine >= introText.length) {
            if ((SystemClock.uptimeMillis() / 450) % 2 == 0) {
                float cursorX = 90 + paint.measureText(statusText);
                canvas.drawRect(cursorX + 8, 690, cursorX + 29, 734, paint);
            }
        } else {
            float[] x = {80, 82, 90, versionX};
            float[] y = {438, 508, 660, versionY};
            float[] size = {225, 44, 43, 32};
            paint.setTypeface(introLine == 0 ? wordmark : mono);
            paint.setTextSize(size[introLine]);
            float cursorX = x[introLine] + paint.measureText(
                    introText[introLine].substring(0, visibleCharacters[introLine]));
            paint.setColor(accent);
            canvas.drawRect(cursorX + 5, y[introLine] - size[introLine] * 0.8f,
                    cursorX + 5 + size[introLine] * 0.45f, y[introLine] + 5, paint);
        }
        paint.setColor(Color.argb(16, 0, 0, 0));
        paint.setStrokeWidth(1);
        for (float y = top + 22; y < bottom - 17; y += 5) {
            canvas.drawLine(left + 22, y, right - 22, y, paint);
        }
        canvas.restore();
    }
}
