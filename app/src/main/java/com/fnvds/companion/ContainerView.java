package com.fnvds.companion;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.os.SystemClock;
import android.view.View;
import android.view.ViewParent;

final class ContainerView extends View {
    private static final int BACKGROUND = Color.rgb(5, 10, 8);
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();
    private final Runnable frame = this::redrawFrame;
    private int accentColor;
    private boolean active;
    private long startedAt;
    private float lidCos, lidSin;

    ContainerView(Context context, int color) {
        super(context);
        accentColor = color;
        paint.setStrokeCap(Paint.Cap.ROUND);
        paint.setStrokeJoin(Paint.Join.ROUND);
        setContentDescription("Container open");
    }

    private void redrawFrame() {
        ViewParent parent = getParent();
        if (parent instanceof View view) view.invalidate();
        invalidate();
    }

    void setAccentColor(int color) { accentColor = color; redrawFrame(); }

    void setActive(boolean value) {
        if (active == value) return;
        if (value && !active) startedAt = SystemClock.uptimeMillis();
        active = value;
        removeCallbacks(frame);
        redrawFrame();
    }

    private float ease(float value) {
        float t = Math.max(0, Math.min(1, value));
        return t * t * (3 - 2 * t);
    }

    private float[] point(float x, float y, float z) {
        return new float[]{400 - .96f * x + .68f * y, 365 - .30f * x - .40f * y - .90f * z};
    }

    private float[] lid(float x, float y, float thickness) {
        return point(x, 110 + (y - 110) * lidCos, 110 - (y - 110) * lidSin + thickness);
    }

    private void polygon(Canvas canvas, int fill, int alpha, float width, float[]... points) {
        path.reset();
        path.moveTo(points[0][0], points[0][1]);
        for (int i = 1; i < points.length; i++) path.lineTo(points[i][0], points[i][1]);
        path.close();
        paint.setColor(fill); paint.setAlpha(alpha); paint.setStyle(Paint.Style.FILL);
        canvas.drawPath(path, paint);
        paint.setColor(accentColor); paint.setAlpha(alpha);
        paint.setStyle(Paint.Style.STROKE); paint.setStrokeWidth(width);
        canvas.drawPath(path, paint);
    }

    private void face(Canvas canvas, float[]... points) {
        polygon(canvas, BACKGROUND, 255, 2.5f, points);
        polygon(canvas, accentColor, 43, 2.5f, points);
    }

    private void line(Canvas canvas, float width, int alpha, float[]... points) {
        path.reset(); path.moveTo(points[0][0], points[0][1]);
        for (int i = 1; i < points.length; i++) path.lineTo(points[i][0], points[i][1]);
        paint.setColor(accentColor); paint.setAlpha(alpha);
        paint.setStyle(Paint.Style.STROKE); paint.setStrokeWidth(width);
        canvas.drawPath(path, paint);
    }

    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (!active) return;
        float scale = Math.min(getWidth() / 400f, getHeight() / 350f);
        if (scale <= 0) return;
        float t = (SystemClock.uptimeMillis() - startedAt) % 3600L;
        float opening = 0, recoil = 0, squash = 0, tilt = 0;
        if (t >= 480 && t < 900) {
            float u = (t - 480) / 420, q = u - 1;
            opening = 1 + 2.8f * q * q * q + 1.8f * q * q;
            recoil = -7 * (float) Math.sin(Math.PI * u);
            tilt = -.025f * (float) Math.sin(Math.PI * u);
        } else if (t >= 900 && t < 1120) {
            float u = (t - 900) / 220;
            opening = 1 + .035f * (float) Math.sin(u * Math.PI * 2) * (1 - u);
        } else if (t >= 1120 && t < 2200) opening = 1;
        else if (t >= 2200 && t < 2490) {
            float u = (t - 2200) / 290; opening = 1 - u * u * u;
        } else if (t >= 2490 && t < 2870) {
            float u = (t - 2490) / 380;
            opening = .14f * (float) (Math.exp(-4 * u) * Math.abs(Math.sin(u * Math.PI * 2)));
            squash = .055f * (float) (Math.exp(-5 * u) * Math.sin(u * Math.PI * 3));
            tilt = .018f * (float) (Math.exp(-5 * u) * Math.sin(u * Math.PI * 3));
        }
        if (t >= 300 && t < 480) squash = .025f * (float) Math.sin((t - 300) / 180 * Math.PI);
        lidCos = (float) Math.cos(opening * 1.22f);
        lidSin = (float) Math.sin(opening * 1.22f);
        float latch = t < 300 ? 0 : t < 480 ? ease((t - 300) / 180)
                : t < 2870 ? 1 : 1 - ease((t - 2870) / 160);
        canvas.save();
        canvas.translate(getWidth() / 2f, getHeight() / 2f);
        canvas.scale(scale, scale); canvas.translate(-310, -225);
        canvas.translate(310, 365 + recoil); canvas.rotate((float) Math.toDegrees(tilt));
        canvas.scale(1 + squash * .35f, 1 - squash); canvas.translate(-310, -365);
        face(canvas, point(0,110,0), point(260,110,0), point(260,110,110), point(0,110,110));
        polygon(canvas, BACKGROUND,255,2, point(8,8,102), point(252,8,102), point(252,102,102), point(8,102,102));
        line(canvas,1.5f,89, point(14,18,86),point(246,18,86),point(246,94,86));
        face(canvas,point(0,0,0),point(0,110,0),point(0,110,110),point(0,0,110));
        face(canvas,point(0,0,0),point(260,0,0),point(260,0,110),point(0,0,110));
        polygon(canvas,BACKGROUND,255,2,point(9,-.5f,12),point(251,-.5f,12),point(251,-.5f,96),point(9,-.5f,96));
        polygon(canvas,BACKGROUND,255,2,point(-.5f,12,12),point(-.5f,98,12),point(-.5f,98,96),point(-.5f,12,96));
        for (int x = 61; x <= 191; x += 65) {
            line(canvas,5,255,point(x,0,17),point(x,0,91));
            line(canvas,1,115,point(x+5,0,20),point(x+5,0,88));
        }
        for (int x = 210; x <= 233; x += 7) line(canvas,4,255,point(x,-1,35),point(x,-1,77));
        for (int x = 22; x <= 151; x += 129) line(canvas,3,255,point(x,-1,23),point(x,-1,39));
        line(canvas,6,255,point(-3,23,79),point(-3,23,68),point(-3,82,68),point(-3,82,79));
        for (int x = 18; x <= 242; x += 224) for (int z = 21; z <= 87; z += 66) {
            float[] p = point(x,-2,z);
            paint.setStyle(Paint.Style.FILL); paint.setColor(accentColor); paint.setAlpha(255);
            canvas.drawCircle(p[0],p[1],2.1f,paint);
        }
        face(canvas,lid(0,0,0),lid(260,0,0),lid(260,110,0),lid(0,110,0));
        for (int x = 13; x < 250; x += 39) {
            int right = Math.min(x+28,250);
            polygon(canvas,BACKGROUND,255,1.8f,lid(x,9,1),lid(right,9,1),lid(right,101,1),lid(x,101,1));
        }
        polygon(canvas,BACKGROUND,255,2,lid(0,0,0),lid(260,0,0),lid(260,0,-7),lid(0,0,-7));
        for (int x = 28; x <= 226; x += 198) {
            float rise = 10 * latch;
            polygon(canvas,BACKGROUND,255,3,lid(x-7,3,rise+2),lid(x+7,3,rise+2),lid(x+7,17,rise+2),lid(x-7,17,rise+2));
            line(canvas,2,255,lid(x,5,rise+3),lid(x,14,rise+3));
        }
        for (int x = 23; x <= 231; x += 208) line(canvas,5,255,point(x-8,111,111),point(x+8,111,111));
        canvas.restore();
        if (isShown() && getWindowVisibility() == VISIBLE) {
            removeCallbacks(frame); postDelayed(frame, 16);
        }
    }

    @Override protected void onVisibilityChanged(View changedView, int visibility) {
        super.onVisibilityChanged(changedView, visibility);
        if (frame != null) { removeCallbacks(frame); if (active && isShown()) redrawFrame(); }
    }
    @Override protected void onWindowVisibilityChanged(int visibility) {
        super.onWindowVisibilityChanged(visibility);
        if (frame != null) { removeCallbacks(frame); if (visibility == VISIBLE && active) redrawFrame(); }
    }
    @Override protected void onAttachedToWindow() { super.onAttachedToWindow(); if (active) redrawFrame(); }
    @Override protected void onDetachedFromWindow() { removeCallbacks(frame); super.onDetachedFromWindow(); }
}
