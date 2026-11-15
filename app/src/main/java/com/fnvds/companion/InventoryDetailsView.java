package com.fnvds.companion;

import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

final class InventoryDetailsView extends LinearLayout {
    private LinearLayout stats;
    private final List<TextView> labels = new ArrayList<>();
    private final List<TextView> values = new ArrayList<>();
    private final List<TextView> actions = new ArrayList<>();
    private TextView modButton;
    private int color;
    private String displayedText;
    private boolean displayedMod;
    private Runnable hotkeyAction;
    private Runnable modAction;
    private Runnable dropAction;
    private boolean displayedDrop;

    InventoryDetailsView(Context context, int color) {
        super(context);
        this.color = color;
        setOrientation(HORIZONTAL);
        setGravity(Gravity.TOP);
        setPadding(dp(12), dp(10), dp(12), dp(10));
        setAccentColor(color);
    }

    private void ensureContent() {
        if (stats != null) return;
        Context context = getContext();
        stats = new LinearLayout(context);
        stats.setOrientation(VERTICAL);
        LayoutParams statsParams = new LayoutParams(0, LayoutParams.WRAP_CONTENT, 1);
        statsParams.rightMargin = dp(12);
        addView(stats, statsParams);
        LinearLayout buttons = new LinearLayout(context);
        buttons.setOrientation(VERTICAL);
        buttons.setGravity(Gravity.TOP | Gravity.RIGHT);
        LayoutParams buttonRow = new LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT);
        addView(buttons, buttonRow);
        for (String name : new String[]{"DROP", "HOTKEY", "MOD"}) {
            TextView button = text(name, 10, true);
            button.setGravity(Gravity.CENTER);
            button.setPadding(dp(12), dp(6), dp(12), dp(6));
            button.setMinHeight(dp(32));
            button.setAlpha(.45f);
            button.setEnabled(false);
            button.setContentDescription(name + ", unavailable");
            LayoutParams params = new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT);
            params.topMargin = actions.isEmpty() ? 0 : dp(6);
            buttons.addView(button, params);
            actions.add(button);
        }
        modButton = actions.get(2);
        updateHotkeyButton();
        updateModButton();
        updateDropButton();
    }

    void setDropAction(Runnable action) {
        dropAction = action;
        updateDropButton();
    }

    private void updateDropButton() {
        if (actions.isEmpty()) return;
        TextView button = actions.get(0);
        boolean enabled = displayedDrop && dropAction != null;
        button.setEnabled(enabled);
        button.setAlpha(enabled ? 1 : .45f);
        button.setContentDescription(enabled ? "Drop one item" : "Drop, unavailable");
        button.setOnClickListener(enabled ? view -> dropAction.run() : null);
    }

    void setModAction(Runnable action) {
        modAction = action;
        updateModButton();
    }

    private void updateModButton() {
        if (modButton == null) return;
        boolean enabled = modAction != null && displayedMod;
        modButton.setEnabled(enabled);
        modButton.setAlpha(enabled ? 1 : .45f);
        modButton.setContentDescription(enabled ? "Modify weapon" : "Mod, unavailable");
        modButton.setOnClickListener(enabled ? view -> modAction.run() : null);
    }

    void setHotkeyAction(Runnable action) {
        hotkeyAction = action;
        updateHotkeyButton();
    }

    private void updateHotkeyButton() {
        if (actions.size() < 2) return;
        TextView button = actions.get(1);
        button.setEnabled(hotkeyAction != null);
        button.setAlpha(hotkeyAction != null ? 1 : .45f);
        button.setContentDescription(hotkeyAction != null ? "Assign hotkey" : "Hotkey, unavailable");
        button.setOnClickListener(hotkeyAction == null ? null : view -> hotkeyAction.run());
    }

    private int dp(float value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private TextView text(String value, int size, boolean bold) {
        TextView text = new TextView(getContext());
        text.setText(value);
        text.setTextSize(size);
        text.setTypeface(Typeface.create(Typeface.MONOSPACE, bold ? Typeface.BOLD : Typeface.NORMAL));
        text.setTextColor(color);
        return text;
    }

    boolean setDetails(String text, boolean canMod, boolean canDrop) {
        if (text.equals(displayedText) && displayedMod == canMod && displayedDrop == canDrop) return false;
        ensureContent();
        displayedText = text;
        displayedMod = canMod;
        displayedDrop = canDrop;
        stats.removeAllViews();
        labels.clear();
        values.clear();
        for (String line : text.split("\n")) {
            String[] fields = line.split("\t", 2);
            LinearLayout row = new LinearLayout(getContext());
            row.setGravity(Gravity.TOP);
            if (fields.length == 2) {
                TextView label = text(fields[0], 9, true);
                labels.add(label);
                LayoutParams labelParams = new LayoutParams(dp(88), LayoutParams.WRAP_CONTENT);
                labelParams.rightMargin = dp(8);
                row.addView(label, labelParams);
            }
            TextView value = text(fields[fields.length - 1], 11, false);
            values.add(value);
            row.addView(value, new LayoutParams(0, LayoutParams.WRAP_CONTENT, 1));
            LayoutParams rowParams = new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT);
            rowParams.bottomMargin = dp(5);
            stats.addView(row, rowParams);
        }
        modButton.setVisibility(canMod ? View.VISIBLE : View.GONE);
        updateModButton();
        updateDropButton();
        setAccentColor(color);
        return true;
    }

    void setAccentColor(int color) {
        this.color = color;
        for (TextView label : labels) label.setTextColor(color);
        for (TextView value : values) value.setTextColor(color);
        for (TextView action : actions) {
            action.setTextColor(color);
            GradientDrawable background = new GradientDrawable();
            background.setColor(Color.rgb(
                    Math.round(5 + (Color.red(color) - 5) * .22f),
                    Math.round(10 + (Color.green(color) - 10) * .22f),
                    Math.round(8 + (Color.blue(color) - 8) * .22f)));
            action.setBackground(background);
        }
        GradientDrawable background = new GradientDrawable();
        background.setColor(Color.rgb(5, 10, 8));
        background.setStroke(dp(1), Color.argb(90, Color.red(color), Color.green(color), Color.blue(color)));
        setBackground(background);
    }
}
