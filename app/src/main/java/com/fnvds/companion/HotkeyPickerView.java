package com.fnvds.companion;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.text.Layout;
import android.text.StaticLayout;
import android.text.TextPaint;
import android.text.TextUtils;
import android.view.MotionEvent;
import android.view.View;

import java.util.Arrays;
import java.util.function.IntConsumer;

final class HotkeyPickerView extends View {
    private static final int[] SLOTS = {1, 3, 4, 5, 6, 7, 8};
    private static final float[] X = {88, 232, 88, 232, 88, 160, 232};
    private static final float[] Y = {70, 70, 166, 166, 262, 262, 262};
    private static final float[] ARROW_X = {32, 288, 32, 288, 32, 160, 288};
    private static final float[] ARROW_Y = {30, 30, 166, 166, 304, 310, 304};
    private static final float[] ANGLE = {-45, 45, -90, 90, -135, 180, 135};
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint namePaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final Path arrow = new Path();
    private final RectF box = new RectF();
    private final String[] names = new String[8];
    private final StaticLayout[] nameLayouts = new StaticLayout[7];
    private final IntConsumer selected;
    private int color;
    private int pressed = -1;

    HotkeyPickerView(Context context, int color, IntConsumer selected) {
        super(context);
        this.color = color;
        this.selected = selected;
        Arrays.fill(names, "");
        paint.setTypeface(Typeface.MONOSPACE);
        namePaint.setTypeface(Typeface.create(Typeface.MONOSPACE, Typeface.BOLD));
        namePaint.setTextSize(9 * getResources().getDisplayMetrics().scaledDensity
                / getResources().getDisplayMetrics().density);
        namePaint.setColor(color);
        setClickable(true);
        setFocusable(true);
        updateNames(new String[8]);
    }

    void updateNames(String[] updated) {
        boolean changed = nameLayouts[0] == null;
        for (int i = 0; i < names.length; ++i)
            if (!names[i].equals(updated[i] == null ? "" : updated[i])) changed = true;
        if (!changed) return;
        StringBuilder description = new StringBuilder("Choose a hotkey. ");
        for (int i = 0; i < names.length; ++i) names[i] = updated[i] == null ? "" : updated[i];
        for (int i = 0; i < SLOTS.length; ++i) {
            String name = names[SLOTS[i] - 1];
            int width = i < 4 ? 104 : 68;
            nameLayouts[i] = StaticLayout.Builder.obtain(name, 0, name.length(), namePaint, width)
                    .setAlignment(Layout.Alignment.ALIGN_CENTER).setIncludePad(false)
                    .setMaxLines(3).setEllipsize(TextUtils.TruncateAt.END).build();
            description.append(SLOTS[i]).append(": ").append(name.isEmpty() ? "empty" : name).append(". ");
        }
        setContentDescription(description);
        invalidate();
    }

    @Override protected void onMeasure(int widthSpec, int heightSpec) {
        int width = resolveSize(Math.round(320 * getResources().getDisplayMetrics().density), widthSpec);
        int height = Math.round(width * 330f / 320f);
        setMeasuredDimension(width, resolveSize(height, heightSpec));
    }

    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        int save = canvas.save();
        canvas.scale(getWidth() / 320f, getHeight() / 330f);
        for (int i = 0; i < SLOTS.length; ++i) {
            boolean occupied = !names[SLOTS[i] - 1].isEmpty();
            box.set(X[i] - 25, Y[i] - 25, X[i] + 25, Y[i] + 25);
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(Color.rgb(5, 10, 8));
            canvas.drawRect(box, paint);
            if (occupied) {
                int clip = canvas.save();
                canvas.clipRect(box);
                paint.setColor(color);
                paint.setAlpha(100);
                paint.setStrokeWidth(1);
                for (float x = box.left - 50; x < box.right; x += 7)
                    canvas.drawLine(x, box.bottom, x + 50, box.top, paint);
                canvas.restoreToCount(clip);
            }
            if (pressed == i) {
                paint.setColor(color);
                paint.setAlpha(40);
                canvas.drawRect(box, paint);
            }
            paint.setColor(color);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(1.5f);
            canvas.drawRect(box, paint);
            paint.setStyle(Paint.Style.FILL);
            paint.setTextSize(14);
            String number = Integer.toString(SLOTS[i]);
            float numberWidth = paint.measureText(number);
            paint.setColor(Color.rgb(5, 10, 8));
            canvas.drawRect(X[i] - numberWidth / 2 - 3, Y[i] - 10,
                    X[i] + numberWidth / 2 + 3, Y[i] + 10, paint);
            paint.setColor(color);
            canvas.drawText(number, X[i] - numberWidth / 2,
                    Y[i] - (paint.ascent() + paint.descent()) / 2, paint);
            if (occupied) {
                StaticLayout name = nameLayouts[i];
                int label = canvas.save();
                canvas.translate(X[i] - name.getWidth() / 2f, box.top - 5 - name.getHeight());
                name.draw(canvas);
                canvas.restoreToCount(label);
            }
            int direction = canvas.save();
            canvas.translate(ARROW_X[i], ARROW_Y[i]);
            canvas.rotate(ANGLE[i]);
            arrow.reset();
            arrow.moveTo(0, -10); arrow.lineTo(-8, 4); arrow.lineTo(8, 4); arrow.close();
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(1.6f);
            paint.setStrokeJoin(Paint.Join.ROUND);
            canvas.drawPath(arrow, paint);
            canvas.drawLine(-8, 9, 8, 9, paint);
            canvas.restoreToCount(direction);
        }
        canvas.restoreToCount(save);
    }

    private int hit(float x, float y) {
        x *= 320f / Math.max(1, getWidth());
        y *= 330f / Math.max(1, getHeight());
        for (int i = 0; i < SLOTS.length; ++i)
            if (Math.abs(x - X[i]) <= 28 && Math.abs(y - Y[i]) <= 28) return i;
        return -1;
    }

    @Override public boolean onTouchEvent(MotionEvent event) {
        int slot = hit(event.getX(), event.getY());
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                pressed = slot;
                invalidate();
                return true;
            case MotionEvent.ACTION_MOVE:
                if (slot != pressed) pressed = -1;
                invalidate();
                return true;
            case MotionEvent.ACTION_UP:
                int chosen = pressed;
                pressed = -1;
                invalidate();
                if (chosen >= 0 && chosen == slot) {
                    performClick();
                    selected.accept(SLOTS[chosen]);
                }
                return true;
            case MotionEvent.ACTION_CANCEL:
                pressed = -1;
                invalidate();
                return true;
            default: return super.onTouchEvent(event);
        }
    }

    @Override public boolean performClick() {
        super.performClick();
        return true;
    }
}
