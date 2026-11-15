package com.fnvds.companion;

import android.content.Context;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import java.util.function.IntConsumer;

final class GameKeyboardView extends FrameLayout {
    private final IntConsumer input;
    private final LinearLayout card, keyboard, entry;
    private final View entryRule;
    private final TextView prompt, preview, done, closeConsole;
    private boolean consoleMode;
    private int accent, panel, background;
    private boolean shift = true, numbers, symbols;

    GameKeyboardView(Context context, int accent, int background, IntConsumer input) {
        super(context);
        this.input = input;
        this.background = background;
        this.accent = accent;
        panel = blend(background, accent, .12f);
        setBackgroundColor(background);
        setClickable(true);
        setPadding(dp(12), dp(12), dp(12), dp(12));
        ScrollView scroll = new ScrollView(context);
        scroll.setFillViewport(true);
        addView(scroll, new FrameLayout.LayoutParams(-1, -1));
        card = column();
        card.setPadding(dp(12), dp(12), dp(12), dp(12));
        scroll.addView(card, new ScrollView.LayoutParams(-1, -1));
        entry = column();
        entry.setPadding(dp(10), dp(12), dp(10), dp(8));
        prompt = text("ENTER TEXT", 12);
        prompt.setPadding(0, 0, 0, dp(8));
        entry.addView(prompt, new LinearLayout.LayoutParams(-1, -2));
        preview = text("|", 24);
        preview.setSingleLine(true);
        preview.setEllipsize(TextUtils.TruncateAt.START);
        preview.setAutoSizeTextTypeUniformWithConfiguration(16, 24, 1,
                android.util.TypedValue.COMPLEX_UNIT_SP);
        preview.setContentDescription("Current game text and cursor");
        entry.addView(preview, new LinearLayout.LayoutParams(-1, dp(38)));
        card.addView(entry, new LinearLayout.LayoutParams(-1, -2));
        entryRule = new View(context);
        card.addView(entryRule, new LinearLayout.LayoutParams(-1, dp(1)));
        card.addView(new View(context), new LinearLayout.LayoutParams(-1, 0, 1));
        keyboard = column();
        LinearLayout.LayoutParams keys = new LinearLayout.LayoutParams(-1, -2);
        keys.topMargin = dp(12);
        card.addView(keyboard, keys);
        done = key("CONFIRM", () -> input.accept(5));
        LinearLayout.LayoutParams finish = new LinearLayout.LayoutParams(-1, dp(36));
        finish.topMargin = dp(8);
        card.addView(done, finish);
        closeConsole = key("CLOSE CONSOLE", () -> input.accept(7));
        closeConsole.setVisibility(GONE);
        LinearLayout.LayoutParams closeParams = new LinearLayout.LayoutParams(-1, dp(32));
        closeParams.topMargin = dp(4);
        card.addView(closeConsole, closeParams);
        setAccentColor(accent);
    }

    void beginSession() {
        shift = true; numbers = false; symbols = false;
        buildKeys();
    }
    void update(String title, String value, boolean canAccept) {
        boolean isConsole = "CONSOLE".equals(title);
        if (consoleMode != isConsole) {
            consoleMode = isConsole;
            done.setText(isConsole ? "ENTER" : "CONFIRM");
            closeConsole.setVisibility(isConsole ? VISIBLE : GONE);
            buildKeys();
        }
        if (!title.contentEquals(prompt.getText())) prompt.setText(title);
        if (!value.contentEquals(preview.getText())) preview.setText(value);
        done.setEnabled(canAccept);
        done.setAlpha(canAccept ? 1f : .35f);
    }
    void setAccentColor(int color) {
        accent = color;
        panel = blend(background, color, .12f);
        card.setBackground(box(background));
        prompt.setTextColor(color);
        preview.setTextColor(color);
        entry.setBackgroundColor(panel);
        entryRule.setBackgroundColor(color);
        closeConsole.setTextColor(color);
        closeConsole.setBackground(box(blend(background, color, .18f), .75f));
        done.setTextColor(color);
        done.setBackground(box(blend(background, color, .26f), 1f));
        buildKeys();
    }
    private void typeCharacter(char letter) {
        input.accept(letter);
        if (letter >= 'A' && letter <= 'Z') {
            shift = false;
            buildKeys();
        }
    }

    private void buildKeys() {
        keyboard.removeAllViews();
        String[] rows = symbols ? new String[]{"!@#$%^&*()", "_+-=[]{}", ";:'\".,?/"}
                : numbers ? new String[]{"1234567890", "-'/.,!?&()"}
                : shift ? new String[]{"QWERTYUIOP", "ASDFGHJKL", "ZXCVBNM"}
                : new String[]{"qwertyuiop", "asdfghjkl", "zxcvbnm"};
        boolean alphabet = !numbers && !symbols;
        for (int index = 0; index < rows.length; ++index) {
            String letters = rows[index];
            LinearLayout row = new LinearLayout(getContext());
            boolean lastAlphabetRow = alphabet && index == 2;
            if (lastAlphabetRow) {
                TextView shiftKey = key(shift ? "SHIFT ↑" : "SHIFT", () -> { shift = !shift; buildKeys(); });
                shiftKey.setBackground(box(blend(background, accent, shift ? .22f : .11f), shift ? 1f : .35f));
                row.addView(shiftKey, params(1.5f));
            }
            float inset = alphabet && index == 1 ? .4f : 0;
            if (inset > 0) row.addView(new View(getContext()), params(inset));
            for (int i = 0; i < letters.length(); ++i) {
                final char letter = letters.charAt(i);
                row.addView(key(String.valueOf(letter), () -> typeCharacter(letter)), params(1));
            }
            if (inset > 0) row.addView(new View(getContext()), params(inset));
            if (lastAlphabetRow) row.addView(backspaceKey(), params(1.5f));
            keyboard.addView(row);
        }
        LinearLayout actions = new LinearLayout(getContext());
        actions.addView(key(numbers || symbols ? "ABC" : "123", () -> {
            numbers = !(numbers || symbols); symbols = false; buildKeys();
        }), params(1.5f));
        actions.addView(key("SPACE", () -> input.accept(32)), params(4));
        actions.addView(key("#+=", () -> { symbols = !symbols; numbers = false; buildKeys(); }), params(1.5f));
        if (!alphabet) actions.addView(backspaceKey(), params(1.5f));
        keyboard.addView(actions);
        LinearLayout editing = new LinearLayout(getContext());
        editing.addView(key("←", () -> input.accept(2)), params(1, 28));
        editing.addView(key("→", () -> input.accept(3)), params(1, 28));
        if (consoleMode) {
            TextView history = key("↑", () -> input.accept(8));
            history.setContentDescription("Previous console command");
            editing.addView(history, params(1, 28));
        }
        editing.addView(key("CLEAR", () -> input.accept(4)), params(1, 28));
        keyboard.addView(editing);
    }

    private TextView backspaceKey() {
        TextView delete = key("⌫", () -> input.accept(1));
        delete.setContentDescription("Backspace. Hold to delete the next character.");
        delete.setOnLongClickListener(view -> { input.accept(6); return true; });
        return delete;
    }

    private TextView key(String caption, Runnable action) {
        TextView key = text(caption, caption.length() == 1 ? 15 : 9);
        key.setGravity(Gravity.CENTER);
        key.setBackground(box(blend(background, accent, .11f), .35f));
        key.setPadding(0, 0, 0, 0);
        key.setOnClickListener(view -> action.run());
        return key;
    }
    private TextView text(String value, int size) {
        TextView text = new TextView(getContext());
        text.setText(value); text.setTextSize(size); text.setTextColor(accent);
        text.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        text.setGravity(Gravity.CENTER_VERTICAL);
        return text;
    }
    private LinearLayout column() {
        LinearLayout column = new LinearLayout(getContext());
        column.setOrientation(LinearLayout.VERTICAL);
        return column;
    }
    private LinearLayout.LayoutParams params(float weight) { return params(weight, 36); }
    private LinearLayout.LayoutParams params(float weight, int height) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, dp(height), weight);
        params.setMargins(dp(2), dp(2), dp(2), dp(2));
        return params;
    }
    private GradientDrawable box(int fill) { return box(fill, .75f); }
    private GradientDrawable box(int fill, float opacity) {
        GradientDrawable box = new GradientDrawable();
        box.setColor(fill);
        int stroke = android.graphics.Color.argb(Math.round(opacity * 255),
                android.graphics.Color.red(accent), android.graphics.Color.green(accent),
                android.graphics.Color.blue(accent));
        box.setStroke(dp(1), stroke);
        return box;
    }
    private static int blend(int background, int color, float amount) {
        return android.graphics.Color.rgb(
                (int)(android.graphics.Color.red(background) * (1-amount) + android.graphics.Color.red(color) * amount),
                (int)(android.graphics.Color.green(background) * (1-amount) + android.graphics.Color.green(color) * amount),
                (int)(android.graphics.Color.blue(background) * (1-amount) + android.graphics.Color.blue(color) * amount));
    }
    private int dp(float value) { return Math.round(value * getResources().getDisplayMetrics().density); }
}
