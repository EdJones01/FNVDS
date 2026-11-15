package com.fnvds.companion;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.os.SystemClock;
import android.view.View;

final class CraftingBenchView extends View {
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path head = new Path();
    private final Runnable frame = this::invalidate;
    private int accent;
    private boolean active;
    private long started;

    CraftingBenchView(Context context, int color) {
        super(context);
        accent = color;
        head.moveTo(-38, -113);
        head.lineTo(27, -113);
        head.lineTo(37, -106);
        head.lineTo(29, -89);
        head.lineTo(-38, -89);
        head.close();
        setContentDescription("Crafting: hammer working at a bench");
    }

    void setAccentColor(int color) {
        accent = color;
        invalidate();
    }

    void setActive(boolean value) {
        if (active == value) return;
        active = value;
        if (value) started = SystemClock.uptimeMillis();
        removeCallbacks(frame);
        invalidate();
    }

    private static float ease(float value) {
        float x = Math.max(0, Math.min(1, value));
        return x * x * (3 - 2 * x);
    }

    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (!active || getWidth() <= 0 || getHeight() <= 0) return;
        float phase = ((SystemClock.uptimeMillis() - started) % 2700L) / 2700f;
        float angle;
        if (phase < .32f) angle = -.40f + .75f * ease(phase / .32f);
        else if (phase < .46f) angle = .35f - 1.31f * ease((phase - .32f) / .14f);
        else if (phase < .56f) angle = -.96f + .045f * (float)Math.sin((phase - .46f) / .10f * Math.PI * 2);
        else angle = -.96f + .56f * ease((phase - .56f) / .44f);

        float scale = Math.min(getWidth() / 360f, getHeight() / 230f);
        int save = canvas.save();
        // Centre the stationary bench (77..323) while the hammer swings above it.
        canvas.translate(getWidth() / 2f - 200 * scale, getHeight() / 2f - 250 * scale);
        canvas.scale(scale, scale);
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(accent);
        canvas.drawRoundRect(77, 279, 323, 292, 3, 3, paint);
        canvas.drawRoundRect(93, 292, 106, 320, 2, 2, paint);
        canvas.drawRoundRect(294, 292, 307, 320, 2, 2, paint);
        paint.setColor(Color.rgb(5, 10, 8));
        canvas.drawCircle(95, 285.5f, 2, paint);
        canvas.drawCircle(305, 285.5f, 2, paint);

        int tool = canvas.save();
        canvas.translate(300, 299);
        canvas.rotate((float)Math.toDegrees(angle));
        paint.setColor(accent);
        canvas.drawRoundRect(-6, -108, 6, 3, 5, 5, paint);
        canvas.drawPath(head, paint);
        paint.setColor(Color.rgb(5, 10, 8));
        canvas.drawRoundRect(-2, -72, 2, -20, 2, 2, paint);
        canvas.restoreToCount(tool);
        canvas.restoreToCount(save);
        if (isShown() && getWindowVisibility() == VISIBLE) {
            removeCallbacks(frame);
            postOnAnimation(frame);
        }
    }

    @Override protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        if (active) invalidate();
    }

    @Override protected void onDetachedFromWindow() {
        removeCallbacks(frame);
        super.onDetachedFromWindow();
    }

    @Override protected void onVisibilityChanged(View changedView, int visibility) {
        super.onVisibilityChanged(changedView, visibility);
        if (frame != null) {
            removeCallbacks(frame);
            if (active && isShown()) invalidate();
        }
    }

    @Override protected void onWindowVisibilityChanged(int visibility) {
        super.onWindowVisibilityChanged(visibility);
        if (frame != null) {
            removeCallbacks(frame);
            if (active && visibility == VISIBLE) invalidate();
        }
    }
}
