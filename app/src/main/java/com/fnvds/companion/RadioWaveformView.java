package com.fnvds.companion;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.os.SystemClock;
import android.view.View;

final class RadioWaveformView extends View {
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path wave = new Path();
    private final Runnable frame = () -> {
        invalidate();
        scheduleFrame();
    };

    RadioWaveformView(Context context, int color) {
        super(context);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(getResources().getDisplayMetrics().density * 1.7f);
        paint.setStrokeCap(Paint.Cap.ROUND);
        paint.setStrokeJoin(Paint.Join.ROUND);
        setAccentColor(color);
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
    }

    void setAccentColor(int color) {
        paint.setColor(color);
        invalidate();
    }

    private void scheduleFrame() {
        removeCallbacks(frame);
        if (isAttachedToWindow() && isShown() && getWindowVisibility() == VISIBLE) {
            postOnAnimation(frame);
        }
    }

    @Override protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        scheduleFrame();
    }

    @Override protected void onDetachedFromWindow() {
        removeCallbacks(frame);
        super.onDetachedFromWindow();
    }

    @Override protected void onVisibilityChanged(View changedView, int visibility) {
        super.onVisibilityChanged(changedView, visibility);
        if (frame != null) scheduleFrame();
    }

    @Override protected void onWindowVisibilityChanged(int visibility) {
        super.onWindowVisibilityChanged(visibility);
        if (frame != null) scheduleFrame();
    }

    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        int width = getWidth();
        if (width <= 0) return;
        // A shared periodic clock preserves phase even when a real station change rebuilds this view.
        double phase = (SystemClock.uptimeMillis() % 9600L) * (Math.PI * 2 / 9600.0);
        wave.rewind();
        // Decorative motion indicates a tuned station; it does not sample game audio.
        int steps = Math.min(width, 360);
        for (int i = 0; i <= steps; i++) {
            double u = i / (double) steps;
            double a = .5 + .5 * Math.cos(u * Math.PI * 2 - phase);
            double b = .5 + .5 * Math.cos(u * Math.PI * 4 - phase * 2 + 1.2);
            double a2 = a * a, b2 = b * b;
            double envelope = .055 + .55 * a2 * a2 + .45 * b2 * b2 * b2;
            double oscillation = Math.sin(u * 75 - phase * 10)
                    + .19 * Math.sin(u * 136 + phase * 4);
            float x = (float) u * width;
            float y = getHeight() / 2f + (float) (oscillation * envelope * getHeight() * .36);
            if (i == 0) wave.moveTo(x, y); else wave.lineTo(x, y);
        }
        canvas.drawPath(wave, paint);
    }
}
