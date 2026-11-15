package com.fnvds.companion;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.os.SystemClock;
import android.view.View;

final class HolotapeLoadingView extends View {
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Rect textBounds = new Rect();
    private final Drawable device;
    private final Runnable frame = this::invalidate;
    private int accentColor;
    private boolean loading;
    private long startedAt;

    HolotapeLoadingView(Context context, int color) {
        super(context);
        accentColor = color;
        device = context.getDrawable(R.drawable.fnvds_device).mutate();
        device.setTint(color);
        device.setBounds(0, 0, 192, 192);
        paint.setStrokeCap(Paint.Cap.ROUND);
        paint.setTypeface(Typeface.MONOSPACE);
        setContentDescription("Loading save");
    }

    void setAccentColor(int color) {
        accentColor = color;
        device.setTint(color);
        invalidate();
    }

    void setLoading(boolean active) {
        if (active == loading) return;
        if (active && !loading) startedAt = SystemClock.uptimeMillis();
        loading = active;
        removeCallbacks(frame);
        invalidate();
    }

    private void stroke(float width, int color) {
        paint.setColor(color);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(width);
    }

    private void fill(int color) {
        paint.setColor(color);
        paint.setStyle(Paint.Style.FILL);
    }

    private static float smooth(float value) {
        float t = Math.max(0, Math.min(1, value));
        return t * t * (3 - 2 * t);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (!loading) return;
        float scale = Math.min(getWidth() / 192f, getHeight() / 252f);
        if (scale <= 0) return;
        float seconds = (SystemClock.uptimeMillis() - startedAt) / 1000f;
        float insertion = smooth((seconds - 0.7f) / 1.5f);
        // Center the combined visible bounds as the tape enters the device.
        float visibleTop = Math.min(84, insertion * 88);
        canvas.save();
        canvas.translate(getWidth() / 2f - 96.5f * scale,
                getHeight() / 2f - (visibleTop + 227) / 2 * scale);
        canvas.scale(scale, scale);

        canvas.save();
        canvas.translate(0, 60);
        fill(Color.BLACK);
        canvas.drawRoundRect(46, 46, 147, 99, 9, 9, paint);
        canvas.drawRoundRect(57, 112, 136, 161, 10, 10, paint);
        device.draw(canvas);
        canvas.restore();

        // Clip only the moving tape at the insertion point above the top cap.
        canvas.save();
        canvas.clipRect(75, 0, 117, 84);
        canvas.translate(0, insertion * 88);
        fill(accentColor);
        canvas.drawRoundRect(76, 0, 116, 44, 4, 4, paint);
        stroke(1.5f, Color.BLACK);
        canvas.drawRoundRect(76, 0, 116, 44, 4, 4, paint);
        fill(Color.BLACK);
        canvas.drawRect(81, 5, 111, 11, paint);
        drawReel(canvas, 85, 24, seconds * 340);
        drawReel(canvas, 107, 24, -seconds * 340);
        canvas.restore();

        int characters = Math.min(7, Math.max(0, (int) ((seconds - 2.3f) / 0.14f)));
        String text = "LOADING".substring(0, characters);
        fill(accentColor);
        paint.setTextSize(11);
        paint.setTextAlign(Paint.Align.LEFT);
        float textWidth = paint.measureText(text);
        // The upper screen center is (96.5, 72.5), below 60 units of tape space.
        paint.getTextBounds("LOADING", 0, 7, textBounds);
        float baseline = 132.5f - (textBounds.top + textBounds.bottom) / 2f;
        canvas.drawText(text, 96.5f - textWidth / 2, baseline, paint);
        if (seconds > 2.3f && ((int) (seconds * 2)) % 2 == 0) {
            float cursorX = 96.5f + textWidth / 2 + 2;
            canvas.drawRect(cursorX, 128, cursorX + 4, 137, paint);
        }
        canvas.restore();
        if (isShown() && getWindowVisibility() == VISIBLE) {
            removeCallbacks(frame);
            postDelayed(frame, 33);
        }
    }

    private void drawReel(Canvas canvas, float x, float y, float angle) {
        stroke(0.9f, Color.BLACK);
        canvas.drawCircle(x, y, 6, paint);
        canvas.save();
        canvas.rotate(angle, x, y);
        for (int i = 0; i < 3; i++) {
            canvas.drawLine(x, y - 2, x, y - 5.5f, paint);
            canvas.rotate(120, x, y);
        }
        canvas.restore();
    }

    @Override
    protected void onWindowVisibilityChanged(int visibility) {
        super.onWindowVisibilityChanged(visibility);
        if (visibility == VISIBLE) invalidate();
        else removeCallbacks(frame);
    }

    @Override
    protected void onDetachedFromWindow() {
        removeCallbacks(frame);
        super.onDetachedFromWindow();
    }
}
