package com.fnvds.companion;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.drawable.AnimationDrawable;
import android.graphics.drawable.Drawable;
import android.view.View;

final class PlayerDeathView extends View {
    private final Paint screenPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Drawable device;
    private final AnimationDrawable heart;
    private boolean active;

    PlayerDeathView(Context context, int color) {
        super(context);
        device = context.getDrawable(R.drawable.fnvds_device).mutate();
        device.setBounds(0, 0, 192, 192);
        heart = (AnimationDrawable) context.getDrawable(R.drawable.heart_death_animation).mutate();
        heart.setBounds(0, 0, 384, 384);
        heart.setCallback(this);
        screenPaint.setColor(Color.BLACK);
        setAccentColor(color);
        setContentDescription("Player has died");
    }

    void setAccentColor(int color) {
        device.setTint(color);
        heart.setTint(color);
        invalidate();
    }

    void setActive(boolean value) {
        if (active == value) return;
        active = value;
        heart.stop();
        heart.selectDrawable(0);
        updatePlayback();
        invalidate();
    }

    private void updatePlayback() {
        if (active && isShown() && getWindowVisibility() == VISIBLE) {
            if (!heart.isRunning()) heart.start();
        } else {
            heart.stop();
        }
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (!active) return;
        float scale = Math.min(getWidth() / 192f, getHeight() / 192f);
        if (scale <= 0) return;
        canvas.save();
        canvas.translate(getWidth() / 2f - 96.5f * scale,
                getHeight() / 2f - 95.5f * scale);
        canvas.scale(scale, scale);
        canvas.drawRoundRect(46, 46, 147, 99, 9, 9, screenPaint);
        canvas.drawRoundRect(57, 112, 136, 161, 10, 10, screenPaint);
        device.draw(canvas);

        canvas.save();
        canvas.clipRect(46, 46, 147, 99);
        canvas.translate(96.5f, 72.5f);
        // Use the same fixed reference bounds for every frame, preserving its growth and split.
        float heartScale = 53f * 0.62f / 206f;
        canvas.scale(heartScale, heartScale);
        canvas.translate(-193, -192);
        heart.draw(canvas);
        canvas.restore();
        canvas.restore();
    }

    @Override
    protected boolean verifyDrawable(Drawable who) {
        return who == heart || super.verifyDrawable(who);
    }

    @Override
    public void invalidateDrawable(Drawable who) {
        if (who == heart) {
            // Drawable bounds do not include the canvas transforms used to position the heart.
            invalidate();
        } else {
            super.invalidateDrawable(who);
        }
    }

    @Override
    protected void onWindowVisibilityChanged(int visibility) {
        super.onWindowVisibilityChanged(visibility);
        if (heart != null) updatePlayback();
    }

    @Override
    protected void onVisibilityChanged(View changedView, int visibility) {
        super.onVisibilityChanged(changedView, visibility);
        if (heart != null) updatePlayback();
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        updatePlayback();
    }

    @Override
    protected void onDetachedFromWindow() {
        heart.stop();
        unscheduleDrawable(heart);
        super.onDetachedFromWindow();
    }
}
