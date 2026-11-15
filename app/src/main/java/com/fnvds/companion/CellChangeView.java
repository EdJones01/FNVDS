package com.fnvds.companion;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.os.SystemClock;
import android.view.View;

final class CellChangeView extends View {
    private static final int BACKGROUND = Color.rgb(5, 10, 8);
    private static final float[][] LEAF = {
            {.1f, 0}, {.9f, 0}, {1, .06f}, {1, .94f},
            {.9f, 1}, {.1f, 1}, {0, .94f}, {0, .06f}
    };
    private static final float[][] INSET = {
            {.15f, .1f}, {.85f, .1f}, {.9f, .15f}, {.9f, .85f},
            {.85f, .9f}, {.15f, .9f}, {.1f, .85f}, {.1f, .15f}
    };
    private static final float[][] RIVETS = {{.1f, .18f}, {.1f, .82f}, {.9f, .18f}, {.9f, .82f}};
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path framePath = new Path();
    private final Path leafPath = new Path();
    private final Path wheelPath = new Path();
    private final Path solePath = new Path();
    private final Runnable frame = this::invalidate;
    private int accentColor;
    private boolean active;
    private long startedAt;
    private float openCos = 1, openSin;

    CellChangeView(Context context, int color) {
        super(context);
        accentColor = color;
        paint.setStrokeCap(Paint.Cap.ROUND);
        paint.setStrokeJoin(Paint.Join.ROUND);
        framePath.moveTo(119, 26);
        framePath.lineTo(201, 26);
        framePath.lineTo(213, 38);
        framePath.lineTo(213, 162);
        framePath.lineTo(201, 174);
        framePath.lineTo(119, 174);
        framePath.lineTo(107, 162);
        framePath.lineTo(107, 38);
        framePath.close();
        solePath.moveTo(-2.4f, -6);
        solePath.cubicTo(-5.4f, -6, -5.6f, -2, -4, 1);
        solePath.lineTo(-3, 3);
        solePath.quadTo(0, 4, 2, 2);
        solePath.lineTo(4, -2);
        solePath.quadTo(4, -6, 0, -6);
        solePath.close();
        setContentDescription("Entering a new area");
    }

    void setAccentColor(int color) {
        accentColor = color;
        invalidate();
    }

    void setActive(boolean value) {
        if (active == value) return;
        if (value && !active) startedAt = SystemClock.uptimeMillis();
        active = value;
        removeCallbacks(frame);
        invalidate();
    }

    private static float ease(float value) {
        float t = Math.max(0, Math.min(1, value));
        return t * t * (3 - 2 * t);
    }

    private void stroke(float width) {
        paint.setColor(accentColor);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(width);
    }

    private void fill(int color) {
        paint.setColor(color);
        paint.setStyle(Paint.Style.FILL);
    }

    private float x(float u) {
        return 207 - 94 * (1 - u) * openCos;
    }

    private float y(float u, float v) {
        return 32 + 136 * v + (2 * v - 1) * (1 - u) * 10 * openSin;
    }

    private void leafPolygon(float[][] vertices) {
        leafPath.reset();
        leafPath.moveTo(x(vertices[0][0]), y(vertices[0][0], vertices[0][1]));
        for (int i = 1; i < vertices.length; i++) {
            leafPath.lineTo(x(vertices[i][0]), y(vertices[i][0], vertices[i][1]));
        }
        leafPath.close();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (!active) return;
        float scale = Math.min(getWidth() / 320f, getHeight() / 240f);
        if (scale <= 0) return;
        float t = (SystemClock.uptimeMillis() - startedAt) % 4800L / 1000f;
        float open = ease((t - .45f) / .7f) * (1 - ease((t - 3.65f) / .6f));
        double angle = Math.toRadians(78 * open);
        openCos = (float) Math.cos(angle);
        openSin = (float) Math.sin(angle);
        canvas.save();
        canvas.translate((getWidth() - 320 * scale) / 2, (getHeight() - 240 * scale) / 2);
        canvas.scale(scale, scale);
        stroke(3.2f);
        canvas.drawPath(framePath, paint);

        // Footprints are behind the door leaf, so the closed hatch hides those inside it.
        for (int i = 0; i < 8; i++) {
            float age = t - (1.3f + i * .23f);
            float opacity = ease(age / .1f) * (1 - ease((age - .32f) / .42f));
            if (opacity <= 0) continue;
            boolean right = i % 2 != 0;
            float footScale = .85f - i * .045f;
            canvas.save();
            canvas.translate(160 + (right ? 8 : -8), 220 - i * 10);
            canvas.rotate(right ? 8 : -8);
            canvas.scale(right ? footScale : -footScale, footScale);
            fill(accentColor);
            paint.setAlpha(Math.round(255 * opacity));
            canvas.drawPath(solePath, paint);
            canvas.drawRoundRect(-2.8f, 4.5f, 2.2f, 7.7f, 1, 1, paint);
            canvas.restore();
        }

        leafPolygon(LEAF);
        fill(BACKGROUND);
        canvas.drawPath(leafPath, paint);
        fill(accentColor);
        paint.setAlpha(36);
        canvas.drawPath(leafPath, paint);
        stroke(1.8f);
        canvas.drawPath(leafPath, paint);
        leafPolygon(INSET);
        stroke(1.4f);
        canvas.drawPath(leafPath, paint);

        wheelPath.reset();
        for (int i = 0; i < 40; i++) {
            double a = i * Math.PI * 2 / 40;
            float u = .5f + (float) Math.cos(a) * .18f;
            float v = .5f + (float) Math.sin(a) * .13f;
            if (i == 0) wheelPath.moveTo(x(u), y(u, v));
            else wheelPath.lineTo(x(u), y(u, v));
        }
        wheelPath.close();
        fill(BACKGROUND);
        canvas.drawPath(wheelPath, paint);
        stroke(1.8f);
        canvas.drawPath(wheelPath, paint);
        float wheelAngle = ease((t - .15f) / .5f) * 100;
        stroke(1.4f);
        for (int i = 0; i < 4; i++) {
            double a = Math.toRadians(wheelAngle + i * 90);
            float c = (float) Math.cos(a), s = (float) Math.sin(a);
            canvas.drawLine(x(.5f + c * .055f), y(.5f + c * .055f, .5f + s * .04f),
                    x(.5f + c * .17f), y(.5f + c * .17f, .5f + s * .122f), paint);
        }
        fill(accentColor);
        for (float[] rivet : RIVETS) {
            canvas.drawCircle(x(rivet[0]), y(rivet[0], rivet[1]), 1.4f, paint);
        }
        canvas.restore();
        if (isShown() && getWindowVisibility() == VISIBLE) {
            removeCallbacks(frame);
            postDelayed(frame, 33);
        }
    }

    @Override
    protected void onVisibilityChanged(View changedView, int visibility) {
        super.onVisibilityChanged(changedView, visibility);
        if (frame != null) {
            removeCallbacks(frame);
            if (active && isShown()) invalidate();
        }
    }

    @Override
    protected void onWindowVisibilityChanged(int visibility) {
        super.onWindowVisibilityChanged(visibility);
        if (frame != null) {
            removeCallbacks(frame);
            if (visibility == VISIBLE && active) invalidate();
        }
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        if (active) invalidate();
    }

    @Override
    protected void onDetachedFromWindow() {
        removeCallbacks(frame);
        super.onDetachedFromWindow();
    }
}
