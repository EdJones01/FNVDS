package com.fnvds.companion;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.view.View;

final class StatusBarView extends View {
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private int color;
    private float fraction;
    private boolean healthWarningEnabled, lowHealth;
    private final Runnable frame = () -> {
        if (getParent() instanceof View parent) parent.invalidate();
        invalidate();
    };

    StatusBarView(Context context, int color) { super(context); this.color = color; }
    void setAccentColor(int color) { this.color = color; invalidate(); }
    void setHealthWarningEnabled(boolean enabled) { healthWarningEnabled = enabled; invalidate(); }
    void setValues(String current, String maximum) {
        fraction = 0;
        lowHealth = false;
        try {
            float value = Float.parseFloat(current), max = Float.parseFloat(maximum);
            if (Float.isFinite(value) && Float.isFinite(max) && max > 0 && value >= 0) {
                fraction = Math.max(0, Math.min(1, value / max));
                lowHealth = value < max * .2f;
            }
        } catch (NumberFormatException ignored) { }
        setContentDescription(current + " / " + maximum);
        removeCallbacks(frame);
        if (getParent() instanceof View parent) parent.invalidate();
        invalidate();
    }
    @Override protected void onDraw(Canvas canvas) {
        boolean warning = healthWarningEnabled && lowHealth;
        int ink = warning ? Color.rgb(235, 65, 65) : color;
        int alpha = warning ? 100 + (int) Math.round(155 * (.5 + .5 * Math.cos(
                android.os.SystemClock.uptimeMillis() % 1400 * (2 * Math.PI / 1400)))) : 255;
        paint.setColor(Color.argb(warning ? alpha * 40 / 255 : 40,
                Color.red(ink), Color.green(ink), Color.blue(ink)));
        canvas.drawRect(0, 0, getWidth(), getHeight(), paint);
        paint.setColor(ink);
        paint.setAlpha(alpha);
        canvas.drawRect(0, 0, getWidth() * fraction, getHeight(), paint);
        removeCallbacks(frame);
        if (warning && isAttachedToWindow() && isShown() && getWindowVisibility() == VISIBLE)
            postOnAnimation(frame);
    }
    @Override protected void onDetachedFromWindow() {
        removeCallbacks(frame);
        super.onDetachedFromWindow();
    }
    @Override protected void onVisibilityChanged(View changedView, int visibility) {
        super.onVisibilityChanged(changedView, visibility);
        if (frame != null) removeCallbacks(frame);
        if (visibility == VISIBLE) invalidate();
    }
    @Override protected void onWindowVisibilityChanged(int visibility) {
        super.onWindowVisibilityChanged(visibility);
        if (frame != null) removeCallbacks(frame);
        if (visibility == VISIBLE) invalidate();
    }
}
