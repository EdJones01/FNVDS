package com.fnvds.companion;

import android.app.Dialog;
import android.content.Context;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;

final class MapSearchDialog extends Dialog {
    record Location(long id, String name, float x, float y, boolean discovered) {}

    private final int accent, secondary, panel;
    private final Runnable sound;
    private final Consumer<Location> select;
    private final List<Location> locations = new ArrayList<>();
    private final StringBuilder query = new StringBuilder();
    private final TextView queryView, countView;
    private final LinearLayout results, keyboard;
    private final ScrollView resultScroll;
    private boolean numbers;

    MapSearchDialog(Context context, int accent, int secondary, int panel, int background,
                    Runnable sound, Consumer<Location> select) {
        super(context);
        this.accent = accent;
        this.secondary = secondary;
        this.panel = panel;
        this.sound = sound;
        this.select = select;
        LinearLayout card = column();
        card.setPadding(dp(10), dp(10), dp(10), dp(10));
        card.setBackground(box(background));
        LinearLayout heading = new LinearLayout(context);
        heading.setGravity(Gravity.CENTER_VERTICAL);
        heading.addView(text("SEARCH LOCATIONS", 12, true), new LinearLayout.LayoutParams(0, dp(32), 1));
        heading.addView(key("CLOSE", () -> dismiss()), new LinearLayout.LayoutParams(dp(58), dp(30)));
        card.addView(heading, matchWrap());

        queryView = text("", 13, true);
        queryView.setGravity(Gravity.CENTER_VERTICAL);
        queryView.setSingleLine(true);
        queryView.setEllipsize(TextUtils.TruncateAt.START);
        queryView.setPadding(dp(8), 0, dp(8), 0);
        queryView.setBackground(box(background));
        queryView.setContentDescription("Location search text");
        LinearLayout.LayoutParams queryParams = new LinearLayout.LayoutParams(-1, dp(36));
        queryParams.topMargin = dp(8);
        card.addView(queryView, queryParams);
        countView = text("", 9, false);
        card.addView(countView, new LinearLayout.LayoutParams(-1, dp(24)));

        results = column();
        resultScroll = new ScrollView(context);
        resultScroll.setFillViewport(true);
        resultScroll.addView(results, new ScrollView.LayoutParams(-1, -2));
        card.addView(resultScroll, new LinearLayout.LayoutParams(-1, 0, 1));

        keyboard = column();
        LinearLayout.LayoutParams keyboardParams = matchWrap();
        keyboardParams.topMargin = dp(8);
        card.addView(keyboard, keyboardParams);
        buildKeyboard();
        setContentView(card);
        setCanceledOnTouchOutside(true);
        // Touch keys require no editable field or window focus; keep gamepad input in the game.
        if (getWindow() != null) {
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE);
            getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN);
            getWindow().setBackgroundDrawableResource(android.R.color.transparent);
        }
        filterResults();
    }

    @Override public void show() {
        super.show();
        if (getWindow() != null) {
            var metrics = getContext().getResources().getDisplayMetrics();
            getWindow().setLayout(Math.min(metrics.widthPixels - dp(20), dp(440)),
                    Math.min(metrics.heightPixels - dp(24), dp(500)));
        }
    }

    void updateLocations(List<Location> latest) {
        List<Location> sorted = new ArrayList<>(latest);
        sorted.sort(Comparator.comparing(Location::name, String.CASE_INSENSITIVE_ORDER)
                .thenComparingLong(Location::id));
        if (locations.equals(sorted)) return;
        locations.clear();
        locations.addAll(sorted);
        filterResults();
    }

    private void filterResults() {
        queryView.setText(query.length() == 0 ? "TYPE A LOCATION…" : query + " ▌");
        queryView.setTextColor(query.length() == 0 ? secondary : accent);
        results.removeAllViews();
        String needle = query.toString().trim().toLowerCase(Locale.ROOT);
        int count = 0;
        for (Location location : locations) {
            if (!location.name().toLowerCase(Locale.ROOT).contains(needle)) continue;
            ++count;
            LinearLayout row = column();
            row.setPadding(dp(8), dp(7), dp(8), dp(7));
            row.setBackgroundColor(panel);
            row.setMinimumHeight(dp(44));
            TextView name = text(location.name(), 12, true);
            name.setMaxLines(2);
            name.setEllipsize(TextUtils.TruncateAt.END);
            row.addView(name, matchWrap());
            row.addView(text(location.discovered() ? "DISCOVERED" : "KNOWN", 8, false), matchWrap());
            row.setContentDescription(location.name());
            row.setOnClickListener(view -> {
                sound.run();
                select.accept(location);
                dismiss();
            });
            LinearLayout.LayoutParams params = matchWrap();
            params.bottomMargin = dp(4);
            results.addView(row, params);
        }
        countView.setText(getContext().getResources().getQuantityString(R.plurals.map_search_location_count, count, count));
        if (count == 0) {
            TextView empty = text(locations.isEmpty() ? "NO KNOWN LOCATIONS" : "NO MATCHING LOCATIONS", 11, false);
            empty.setGravity(Gravity.CENTER);
            results.addView(empty, new LinearLayout.LayoutParams(-1, dp(60)));
        }
        resultScroll.scrollTo(0, 0);
    }

    private void buildKeyboard() {
        keyboard.removeAllViews();
        String[] rows = numbers ? new String[]{"1234567890", "-'&().,!?/"}
                : new String[]{"QWERTYUIOP", "ASDFGHJKL", "ZXCVBNM"};
        for (String letters : rows) {
            LinearLayout row = new LinearLayout(getContext());
            row.setGravity(Gravity.CENTER);
            float inset = (10 - letters.length()) / 2f;
            if (inset > 0) row.addView(new View(getContext()), new LinearLayout.LayoutParams(0, 1, inset));
            for (int i = 0; i < letters.length(); ++i) {
                String letter = letters.substring(i, i + 1);
                row.addView(key(letter, () -> append(letter)), keyParams(1));
            }
            if (inset > 0) row.addView(new View(getContext()), new LinearLayout.LayoutParams(0, 1, inset));
            keyboard.addView(row, matchWrap());
        }
        LinearLayout actions = new LinearLayout(getContext());
        actions.addView(key(numbers ? "ABC" : "123", () -> { numbers = !numbers; buildKeyboard(); }), keyParams(1.5f));
        actions.addView(key("SPACE", () -> append(" ")), keyParams(4));
        TextView delete = key("DEL", () -> {
            if (query.length() > 0) query.deleteCharAt(query.length() - 1);
            filterResults();
        });
        delete.setContentDescription("Delete last character");
        delete.setOnLongClickListener(view -> { sound.run(); query.setLength(0); filterResults(); return true; });
        actions.addView(delete, keyParams(1.5f));
        actions.addView(key("CLR", () -> { query.setLength(0); filterResults(); }), keyParams(1.5f));
        keyboard.addView(actions, matchWrap());
    }

    private void append(String value) {
        if (query.length() >= 64) return;
        query.append(value);
        filterResults();
    }

    private TextView key(String caption, Runnable action) {
        TextView key = text(caption, caption.length() == 1 ? 14 : 10, true);
        key.setGravity(Gravity.CENTER);
        key.setPadding(0, 0, 0, 0);
        key.setBackground(box(panel));
        key.setOnClickListener(view -> { sound.run(); action.run(); });
        return key;
    }

    private TextView text(String value, int size, boolean bright) {
        TextView text = new TextView(getContext());
        text.setText(value);
        text.setTextSize(size);
        text.setTextColor(bright ? accent : secondary);
        text.setTypeface(Typeface.MONOSPACE);
        text.setGravity(Gravity.CENTER_VERTICAL);
        return text;
    }

    private GradientDrawable box(int fill) {
        GradientDrawable box = new GradientDrawable();
        box.setColor(fill);
        box.setStroke(dp(1), accent);
        return box;
    }

    private LinearLayout column() {
        LinearLayout column = new LinearLayout(getContext());
        column.setOrientation(LinearLayout.VERTICAL);
        return column;
    }

    private LinearLayout.LayoutParams keyParams(float weight) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, dp(34), weight);
        params.setMargins(dp(2), dp(2), dp(2), dp(2));
        return params;
    }

    private LinearLayout.LayoutParams matchWrap() { return new LinearLayout.LayoutParams(-1, -2); }
    private int dp(float value) { return Math.round(value * getContext().getResources().getDisplayMetrics().density); }
}
