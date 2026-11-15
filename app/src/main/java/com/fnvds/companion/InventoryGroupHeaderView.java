package com.fnvds.companion;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Typeface;
import android.text.Layout;
import android.text.StaticLayout;
import android.text.TextPaint;
import android.util.TypedValue;
import android.view.View;
import android.view.accessibility.AccessibilityNodeInfo;

final class InventoryGroupHeaderView extends View {
    private final String title;
    private final String countText;
    private final int count;
    private final TextPaint titlePaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final Paint countPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint rulePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint chevronPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path chevron = new Path();
    private StaticLayout titleLayout;
    private float countLeft;
    private float ruleRight;
    private float titleWidth;
    private boolean collapsed;

    InventoryGroupHeaderView(Context context, String title, int count, int color) {
        super(context);
        this.title = title;
        this.count = count;
        countText = count < 10 ? "0" + count : Integer.toString(count);
        titlePaint.setTypeface(Typeface.create(Typeface.MONOSPACE, Typeface.BOLD));
        titlePaint.setTextSize(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 13,
                getResources().getDisplayMetrics()));
        countPaint.setTypeface(Typeface.MONOSPACE);
        countPaint.setTextSize(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 12,
                getResources().getDisplayMetrics()));
        chevronPaint.setStyle(Paint.Style.STROKE);
        chevronPaint.setStrokeWidth(dp(2));
        chevronPaint.setStrokeCap(Paint.Cap.SQUARE);
        chevronPaint.setStrokeJoin(Paint.Join.MITER);
        rulePaint.setStrokeWidth(dp(1));
        setAccentColor(color);
        setClickable(true);
        setFocusable(true);
        setCollapsed(false);
    }

    private float dp(float value) {
        return value * getResources().getDisplayMetrics().density;
    }

    void setAccentColor(int color) {
        titlePaint.setColor(color);
        countPaint.setColor(color);
        rulePaint.setColor(color);
        rulePaint.setAlpha(170);
        chevronPaint.setColor(color);
        invalidate();
    }

    void setCollapsed(boolean value) {
        collapsed = value;
        setContentDescription(title + ", " + count + " items, "
                + (collapsed ? "collapsed. Tap to show." : "expanded. Tap to hide."));
        invalidate();
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int width = resolveSize(Math.round(dp(320)), widthMeasureSpec);
        countLeft = width - dp(36) - countPaint.measureText(countText);
        ruleRight = countLeft - dp(12);
        int availableTitleWidth = Math.max(1, (int) (ruleRight - dp(48)));
        titleLayout = StaticLayout.Builder.obtain(title, 0, title.length(), titlePaint,
                        availableTitleWidth)
                .setIncludePad(false)
                .setHyphenationFrequency(Layout.HYPHENATION_FREQUENCY_NONE)
                .build();
        titleWidth = 0;
        for (int i = 0; i < titleLayout.getLineCount(); i++) {
            titleWidth = Math.max(titleWidth, titleLayout.getLineWidth(i));
        }
        int height = Math.max(Math.round(dp(44)), titleLayout.getHeight() + Math.round(dp(8)));
        setMeasuredDimension(width, resolveSize(height, heightMeasureSpec));
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (titleLayout == null) return;
        float centerY = getHeight() / 2f;
        canvas.save();
        canvas.translate(dp(12), (getHeight() - titleLayout.getHeight()) / 2f);
        titleLayout.draw(canvas);
        canvas.restore();
        float ruleLeft = dp(24) + titleWidth;
        if (ruleRight > ruleLeft) canvas.drawLine(ruleLeft, centerY, ruleRight, centerY, rulePaint);
        float baseline = centerY - (countPaint.ascent() + countPaint.descent()) / 2f;
        canvas.drawText(countText, countLeft, baseline, countPaint);
        float centerX = getWidth() - dp(18);
        chevron.reset();
        if (collapsed) {
            chevron.moveTo(centerX - dp(2), centerY - dp(4));
            chevron.lineTo(centerX + dp(2), centerY);
            chevron.lineTo(centerX - dp(2), centerY + dp(4));
        } else {
            chevron.moveTo(centerX - dp(4), centerY - dp(2));
            chevron.lineTo(centerX, centerY + dp(2));
            chevron.lineTo(centerX + dp(4), centerY - dp(2));
        }
        canvas.drawPath(chevron, chevronPaint);
    }

    @Override
    public void onInitializeAccessibilityNodeInfo(AccessibilityNodeInfo info) {
        super.onInitializeAccessibilityNodeInfo(info);
        info.setClassName("android.widget.Button");
    }
}
