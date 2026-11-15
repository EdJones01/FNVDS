package com.fnvds.companion;

import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import java.util.ArrayList;
import java.util.List;

final class BarterSummaryView extends LinearLayout {
    static final class Item {
        final boolean selling;
        final String name;
        final int count;
        final double value;
        Item(boolean selling, String name, int count) {
            this(selling, name, count, 0);
        }
        Item(boolean selling, String name, int count, double value) {
            this.selling = selling; this.name = name; this.count = count;
            this.value = value;
        }
    }
    private int accent;
    private final boolean looting;
    private final TextView merchant, wallets, balance, after, status;
    private final TextView sellTotal, buyTotal;
    private final LinearLayout sellRows, buyRows;
    private final ScrollView sellScroll, buyScroll;
    private String name = "BARTER";
    private int playerCaps, merchantCaps, net;
    private boolean ready;
    private final List<Item> items = new ArrayList<>();

    BarterSummaryView(Context context, int color) {
        this(context, color, false);
    }
    BarterSummaryView(Context context, int color, boolean loot) {
        super(context);
        looting = loot;
        accent = color;
        setOrientation(VERTICAL);
        setPadding(dp(14), dp(12), dp(14), dp(12));
        setClickable(true);
        merchant = text("BARTER", 14);
        addView(merchant, full());
        wallets = text("", 11);
        wallets.setPadding(0, dp(8), 0, dp(12));
        addView(wallets, full());
        LinearLayout columns = new LinearLayout(context);
        columns.setOrientation(HORIZONTAL);
        LinearLayout selling = column(looting ? "DEPOSITED" : "YOU GIVE"),
                buying = column(looting ? "TAKEN" : "YOU GET");
        LinearLayout.LayoutParams left = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1);
        left.rightMargin = dp(10);
        columns.addView(selling, left);
        columns.addView(buying, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1));
        sellScroll = new ScrollView(context); buyScroll = new ScrollView(context);
        sellRows = rows(); buyRows = rows();
        sellScroll.addView(sellRows); buyScroll.addView(buyRows);
        selling.addView(sellScroll, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));
        buying.addView(buyScroll, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));
        sellTotal = text("", 11); buyTotal = text("", 11);
        sellTotal.setPadding(0, dp(8), 0, 0); buyTotal.setPadding(0, dp(8), 0, 0);
        sellTotal.setVisibility(looting ? VISIBLE : GONE); buyTotal.setVisibility(looting ? VISIBLE : GONE);
        selling.addView(sellTotal, full()); buying.addView(buyTotal, full());
        addView(columns, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));
        balance = text("", 16);
        balance.setPadding(0, dp(12), 0, dp(6));
        addView(balance, full());
        after = text("", 12);
        addView(after, full());
        status = text("Waiting for trade details…", 11);
        status.setPadding(0, dp(10), 0, 0);
        addView(status, full());
        setAccentColor(color);
        render();
    }

    private LinearLayout rows() {
        LinearLayout rows = new LinearLayout(getContext()); rows.setOrientation(VERTICAL); return rows;
    }
    private LinearLayout column(String title) {
        LinearLayout column = rows();
        TextView heading = text(title, 12);
        heading.setPadding(0, 0, 0, dp(8));
        column.addView(heading, full());
        return column;
    }
    private TextView text(String value, int size) {
        TextView text = new TextView(getContext());
        text.setText(value); text.setTextSize(size); text.setTextColor(accent);
        text.setTypeface(Typeface.MONOSPACE);
        return text;
    }
    void setAccentColor(int color) {
        accent = color;
        GradientDrawable background = new GradientDrawable();
        background.setColor(Color.rgb(5, 10, 8)); background.setStroke(dp(1), accent);
        setBackground(background);
        recolor(this);
        render();
    }
    private void recolor(ViewGroup parent) {
        for (int i = 0; i < parent.getChildCount(); i++) {
            android.view.View child = parent.getChildAt(i);
            if (child instanceof TextView label) label.setTextColor(accent);
            else if (child instanceof ViewGroup group) recolor(group);
        }
    }
    void clear() { ready = false; items.clear(); render(); }
    void update(String trader, int player, int vendor, int netCaps, List<Item> next) {
        name = trader; playerCaps = player; merchantCaps = vendor; net = netCaps;
        items.clear(); items.addAll(next); ready = true; render();
    }
    private void render() {
        int sellY = sellScroll.getScrollY(), buyY = buyScroll.getScrollY();
        merchant.setText(ready ? name.toUpperCase(java.util.Locale.ROOT) : looting ? "LOOTING" : "BARTER");
        wallets.setText(looting ? ""
                : ready ? "YOU: " + playerCaps + " CAPS    TRADER: " + merchantCaps + " CAPS" : "");
        wallets.setVisibility(wallets.length() == 0 ? GONE : VISIBLE);
        sellRows.removeAllViews(); buyRows.removeAllViews();
        boolean sold = false, bought = false;
        double deposited = 0, taken = 0;
        for (Item item : items) {
            TextView row = text(item.name + "\n× " + item.count
                    + (looting ? " · VAL " + valueText(item.value) : ""), 12);
            row.setPadding(dp(12), dp(9), dp(12), dp(9));
            row.setBackgroundColor(Color.rgb(
                    Math.round(5 + (Color.red(accent) - 5) * .12f),
                    Math.round(10 + (Color.green(accent) - 10) * .12f),
                    Math.round(8 + (Color.blue(accent) - 8) * .12f)));
            LinearLayout.LayoutParams params = full(); params.bottomMargin = dp(4);
            (item.selling ? sellRows : buyRows).addView(row, params);
            if (item.selling) sold = true; else bought = true;
            if (item.selling) deposited += item.value; else taken += item.value;
        }
        if (!sold) sellRows.addView(text(looting ? "Nothing deposited" : "No items offered", 11), full());
        if (!bought) buyRows.addView(text(looting ? "Nothing taken" : "No items selected", 11), full());
        final int payout = Math.min(Math.max(net, 0), merchantCaps);
        long change = net > 0 ? payout : net;
        balance.setText(!ready ? "" : net > 0 ? "YOU RECEIVE " + payout + " CAPS"
                : net < 0 ? "YOU PAY " + -(long)net + " CAPS" : "EVEN TRADE · 0 CAPS");
        after.setText(!ready ? "" : net < -(long)playerCaps ? "AFTER TRADE: —"
                : "AFTER TRADE: " + ((long)playerCaps + change) + " CAPS");
        status.setText(!ready ? "Waiting for trade details…"
                : net < -(long)playerCaps ? "NOT ENOUGH CAPS"
                : net > merchantCaps ? "TRADER CANNOT PAY THE FULL OFFER"
                : "");
        if (looting) {
            sellTotal.setText("TOTAL BASE VALUE\n" + valueText(deposited));
            buyTotal.setText("TOTAL BASE VALUE\n" + valueText(taken));
            balance.setText(ready ? "NET BASE VALUE " + (taken >= deposited ? "+" : "−")
                    + valueText(Math.abs(taken - deposited)) : "");
            after.setText("");
            status.setText(ready ? "" : "Waiting for container details…");
        }
        after.setVisibility(after.length() == 0 ? GONE : VISIBLE);
        status.setVisibility(status.length() == 0 ? GONE : VISIBLE);
        sellScroll.post(() -> sellScroll.scrollTo(0, sellY));
        buyScroll.post(() -> buyScroll.scrollTo(0, buyY));
    }
    private String valueText(double value) {
        return String.format(java.util.Locale.ROOT, Math.abs(value - Math.rint(value)) < .005 ? "%.0f" : "%.1f", value);
    }
    private LinearLayout.LayoutParams full() {
        return new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
    }
    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }
}
