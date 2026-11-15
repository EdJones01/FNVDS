package com.fnvds.companion;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.os.SystemClock;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewParent;
import android.widget.TextView;

final class NavigationKeyView extends TextView {
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Runnable frame = this::redrawFrame;
    private boolean selected;
    private int accent, background;

    NavigationKeyView(Context context) {
        super(context);
        setTypeface(Typeface.MONOSPACE);
        setGravity(Gravity.CENTER);
        setSingleLine(true);
        setIncludeFontPadding(false);
        setPadding(dp(4), dp(4), dp(4), dp(8));
        setAutoSizeTextTypeUniformWithConfiguration(9, 11, 1, TypedValue.COMPLEX_UNIT_SP);
        setClickable(true);
        setFocusable(false);
        setBackground(new Drawable() {
            @Override public void draw(Canvas canvas) { drawKey(canvas); }
            @Override public void setAlpha(int alpha) { }
            @Override public void setColorFilter(ColorFilter filter) { }
            @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
        });
    }

    void setStyle(boolean selected, int accent, int background) {
        this.selected = selected;
        this.accent = accent;
        this.background = background;
        setSelected(selected);
        setTextColor(selected ? accent : mix(background, accent, .8f));
        removeCallbacks(frame);
        redrawFrame();
    }

    private int mix(int from, int to, float amount) {
        return Color.rgb(Math.round(Color.red(from) + (Color.red(to) - Color.red(from)) * amount),
                Math.round(Color.green(from) + (Color.green(to) - Color.green(from)) * amount),
                Math.round(Color.blue(from) + (Color.blue(to) - Color.blue(from)) * amount));
    }

    private void redrawFrame() {
        ViewParent parent = getParent();
        if (parent instanceof View view) view.invalidate();
        invalidate();
    }

    private void drawKey(Canvas canvas) {
        float unit = getResources().getDisplayMetrics().density;
        float left = unit, top = unit, right = getWidth() - unit, bottom = getHeight() - unit;
        float wave = (float) (.5 + .5 * Math.cos(SystemClock.uptimeMillis() % 1800 * (2 * Math.PI / 1800)));
        float fill = isPressed() ? .24f : selected ? .15f + .025f * wave : .05f;
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(mix(background, accent, fill));
        canvas.drawRoundRect(left, top, right, bottom, unit * 2, unit * 2, paint);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(unit);
        paint.setColor(accent);
        paint.setAlpha(selected ? 220 + Math.round(35 * wave) : 140);
        canvas.drawRoundRect(left, top, right, bottom, unit * 2, unit * 2, paint);
        paint.setStyle(Paint.Style.FILL);
        if (selected) {
            paint.setColor(accent);
            paint.setAlpha(18 + Math.round(10 * wave));
            canvas.drawRect(getWidth() * .26f, getHeight() - unit * 10,
                    getWidth() * .74f, getHeight() - unit * 4, paint);
        }
        paint.setColor(accent);
        paint.setAlpha(selected ? 220 + Math.round(35 * wave) : 46);
        canvas.drawRect(getWidth() * .28f, getHeight() - unit * 8,
                getWidth() * .72f, getHeight() - unit * 6, paint);
        paint.setAlpha(255);
        if (selected && isShown() && getWindowVisibility() == VISIBLE) {
            removeCallbacks(frame);
            postDelayed(frame, 33);
        }
    }

    @Override protected void drawableStateChanged() { super.drawableStateChanged(); invalidate(); }
    @Override protected void onVisibilityChanged(View view, int visibility) {
        super.onVisibilityChanged(view, visibility);
        if (frame != null) { removeCallbacks(frame); if (isShown()) redrawFrame(); }
    }
    @Override protected void onWindowVisibilityChanged(int visibility) {
        super.onWindowVisibilityChanged(visibility);
        if (frame != null) { removeCallbacks(frame); if (visibility == VISIBLE) redrawFrame(); }
    }
    @Override protected void onAttachedToWindow() { super.onAttachedToWindow(); redrawFrame(); }
    @Override protected void onDetachedFromWindow() { removeCallbacks(frame); super.onDetachedFromWindow(); }
    private int dp(float value) { return Math.round(value * getResources().getDisplayMetrics().density); }
}
