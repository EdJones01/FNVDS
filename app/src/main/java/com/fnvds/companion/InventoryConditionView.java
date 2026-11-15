package com.fnvds.companion;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Typeface;
import android.util.TypedValue;
import android.view.View;

final class InventoryConditionView extends View {
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final int condition;
    private final boolean modified;
    private final int hotkey;
    private final String percentage;
    private final Path wrench = new Path();

    InventoryConditionView(Context context, int condition, boolean modified, int hotkey, int color) {
        super(context);
        this.condition = condition;
        this.modified = modified;
        this.hotkey = hotkey;
        percentage = condition < 0 ? "—" : Math.round(condition / 10f) + "%";
        paint.setTypeface(Typeface.MONOSPACE);
        paint.setTextSize(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 9,
                getResources().getDisplayMetrics()));
        setAccentColor(color);
        setContentDescription((condition < 0 ? "No condition" : "Condition " + percentage)
                + (modified ? ", weapon mods attached" : "")
                + (hotkey > 0 ? ", hotkey " + hotkey : ""));
    }

    private float dp(float value) {
        return value * getResources().getDisplayMetrics().density;
    }

    void setAccentColor(int color) {
        paint.setColor(color);
        invalidate();
    }

    @Override protected void onMeasure(int widthSpec, int heightSpec) {
        setMeasuredDimension(resolveSize(Math.round(dp(220)), widthSpec),
                resolveSize(Math.round(dp(20)), heightSpec));
    }

    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float center = getHeight() / 2f;
        float baseline = center - (paint.ascent() + paint.descent()) / 2f;
        float badgesWidth = (modified ? dp(22) : 0) + (hotkey > 0 ? dp(24) : 0);
        float barLeft = paint.measureText("CND") + dp(6);
        float barWidth = Math.min(dp(108), Math.max(dp(12),
                getWidth() - barLeft - dp(36) - badgesWidth));
        float barRight = barLeft + barWidth;
        paint.setStyle(Paint.Style.FILL);
        canvas.drawText("CND", 0, baseline, paint);
        if (condition >= 0) {
            paint.setAlpha(45);
            canvas.drawRect(barLeft, center - dp(2), barRight, center + dp(2), paint);
            paint.setAlpha(255);
            canvas.drawRect(barLeft, center - dp(2),
                    barLeft + barWidth * Math.max(0, Math.min(condition, 1000)) / 1000f,
                    center + dp(2), paint);
            paint.setStrokeWidth(dp(1));
            canvas.drawLine(barLeft, center - dp(4), barLeft, center + dp(4), paint);
            canvas.drawLine(barRight, center - dp(4), barRight, center + dp(4), paint);
            canvas.drawLine(barLeft + barWidth * .75f, center - dp(4),
                    barLeft + barWidth * .75f, center + dp(4), paint);
        }
        canvas.drawText(percentage, barRight + dp(5), baseline, paint);
        float badgeX = getWidth() - badgesWidth;
        if (modified) {
            int save = canvas.save();
            canvas.translate(badgeX + dp(2), center - dp(8));
            canvas.scale(dp(1), dp(1));
            wrench.reset();
            wrench.moveTo(10, 1); wrench.lineTo(9, 5); wrench.lineTo(12, 7);
            wrench.lineTo(15, 5); wrench.lineTo(14, 10); wrench.lineTo(10, 11);
            wrench.lineTo(5, 16); wrench.lineTo(1, 12); wrench.lineTo(6, 7);
            wrench.lineTo(6, 3); wrench.close();
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(1.25f);
            paint.setStrokeJoin(Paint.Join.ROUND);
            canvas.drawPath(wrench, paint);
            canvas.restoreToCount(save);
            badgeX += dp(22);
        }
        if (hotkey > 0) {
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(dp(1));
            canvas.drawRoundRect(badgeX + dp(2), center - dp(7), badgeX + dp(19),
                    center + dp(7), dp(2), dp(2), paint);
            paint.setStyle(Paint.Style.FILL);
            String number = Integer.toString(hotkey);
            canvas.drawText(number, badgeX + dp(10.5f) - paint.measureText(number) / 2,
                    baseline, paint);
        }
        paint.setStyle(Paint.Style.FILL);
        paint.setAlpha(255);
    }
}
