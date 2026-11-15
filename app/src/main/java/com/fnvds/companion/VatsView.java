package com.fnvds.companion;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.SystemClock;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.List;
import java.util.Locale;

final class VatsView extends LinearLayout {
    interface Actions { void send(int action, long token); }
    record Part(long token, int chance, int condition, int queued, boolean selected, boolean enabled, String name) { }
    record State(long session, boolean ready, long targetId, int targetIndex, int targets,
                 float ap, float maxAp, float hp, float maxHp, float damage, float queuedDamage,
                 float cost, int queued, String target, String weapon, String ammo, List<Part> parts) { }

    private static final int BACKGROUND = Color.rgb(5, 10, 8);
    private final Actions actions;
    private final TextView title, weapon, target, health, detail, ap, execute, undo, previous, next, status, exit;
    private final LinearLayout grid;
    private final Meter hpMeter, apMeter;
    private int color;
    private State state;

    VatsView(Context context, int color, Actions actions) {
        super(context);
        this.color = color;
        this.actions = actions;
        setOrientation(VERTICAL);
        setPadding(dp(12), dp(10), dp(12), dp(10));
        setBackgroundColor(BACKGROUND);
        setClickable(true);
        LinearLayout header = row();
        title = text("V.A.T.S.", 17, true);
        header.addView(title, weighted());
        weapon = text("", 10, false);
        weapon.setGravity(Gravity.RIGHT);
        header.addView(weapon, weighted());
        addView(header, full());
        LinearLayout targets = row();
        previous = action("‹", 3);
        targets.addView(previous, new LayoutParams(dp(40), dp(40)));
        target = text("TARGET", 13, true);
        target.setGravity(Gravity.CENTER);
        targets.addView(target, weighted());
        next = action("›", 4);
        targets.addView(next, new LayoutParams(dp(40), dp(40)));
        LayoutParams targetParams = full(); targetParams.topMargin = dp(6);
        addView(targets, targetParams);
        health = text("", 10, false);
        health.setPadding(0, dp(6), 0, dp(3));
        addView(health, full());
        hpMeter = new Meter(context); addView(hpMeter, new LayoutParams(LayoutParams.MATCH_PARENT, dp(5)));
        ScrollView scroll = new ScrollView(context);
        scroll.setFillViewport(false);
        grid = new LinearLayout(context); grid.setOrientation(VERTICAL);
        scroll.addView(grid, new ScrollView.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT));
        LayoutParams scrollParams = new LayoutParams(LayoutParams.MATCH_PARENT, 0, 1);
        scrollParams.topMargin = dp(8); scrollParams.bottomMargin = dp(6);
        addView(scroll, scrollParams);
        status = text("", 11, false); status.setGravity(Gravity.CENTER);
        addView(status, full());
        detail = text("SELECT A BODY PART", 10, false);
        detail.setPadding(0, dp(3), 0, dp(3));
        addView(detail, full());
        ap = text("", 11, true); ap.setPadding(0, dp(5), 0, dp(3));
        addView(ap, full());
        apMeter = new Meter(context); addView(apMeter, new LayoutParams(LayoutParams.MATCH_PARENT, dp(7)));
        LinearLayout buttons = row();
        exit = action("EXIT", 7); undo = action("UNDO", 5); execute = action("EXECUTE", 6);
        for (TextView button : new TextView[]{exit, undo, execute}) {
            LayoutParams params = new LayoutParams(0, dp(42), button == execute ? 1.5f : 1);
            if (button != exit) params.leftMargin = dp(6);
            buttons.addView(button, params);
        }
        LayoutParams footer = full(); footer.topMargin = dp(8);
        addView(buttons, footer);
    }

    private int dp(float v) { return Math.round(v * getResources().getDisplayMetrics().density); }
    private LayoutParams full() { return new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT); }
    private LayoutParams weighted() { return new LayoutParams(0, LayoutParams.WRAP_CONTENT, 1); }
    private LinearLayout row() { LinearLayout row = new LinearLayout(getContext()); row.setGravity(Gravity.CENTER_VERTICAL); return row; }
    private TextView text(String value, int size, boolean bold) {
        TextView view = new TextView(getContext()); view.setText(value); view.setTextSize(size);
        view.setTypeface(Typeface.MONOSPACE, bold ? Typeface.BOLD : Typeface.NORMAL); view.setTextColor(color);
        return view;
    }
    private GradientDrawable background(boolean selected) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(Color.argb(selected ? 55 : 22, Color.red(color), Color.green(color), Color.blue(color)));
        drawable.setStroke(dp(1), selected ? color : Color.argb(100, Color.red(color), Color.green(color), Color.blue(color)));
        return drawable;
    }
    private TextView action(String label, int action) {
        TextView button = text(label, 11, true); button.setGravity(Gravity.CENTER);
        button.setPadding(dp(6), dp(4), dp(6), dp(4)); button.setBackground(background(false));
        button.setOnClickListener(view -> {
            if (state == null || !state.ready()) return;
            actions.send(action, 0);
        });
        return button;
    }
    private String number(float value) { return Float.isFinite(value) ? String.format(Locale.ROOT, "%.0f", value) : "—"; }
    private void enabled(TextView view, boolean enabled) { view.setEnabled(enabled); view.setAlpha(enabled ? 1 : .35f); }

    void update(State incoming) {
        if (incoming.equals(state)) return;
        state = incoming;
        render();
    }
    void setAccentColor(int color) { this.color = color; if (state != null) render(); }
    private void render() {
        for (TextView text : new TextView[]{title, weapon, target, health, detail, ap, execute, undo, previous, next, status, exit}) text.setTextColor(color);
        for (int i = 0; i < getChildCount(); ++i) {
            View child = getChildAt(i);
            if (child instanceof LinearLayout row) for (int j = 0; j < row.getChildCount(); ++j) {
                View item = row.getChildAt(j);
                if (item instanceof TextView && item.hasOnClickListeners()) item.setBackground(background(false));
            }
        }
        weapon.setText(state.weapon() + ("—".equals(state.ammo()) ? "" : "\n" + state.ammo()));
        target.setText(state.target() + (state.targets() > 1 ? "\n" + state.targetIndex() + " / " + state.targets() : ""));
        health.setText("HP " + number(state.hp()) + "/" + number(state.maxHp())
                + (state.queuedDamage() > 0 ? "   QUEUED DMG " + number(state.queuedDamage()) : ""));
        hpMeter.set(state.hp(), state.maxHp(), state.queuedDamage());
        ap.setText("AP " + number(state.ap()) + " / " + number(state.maxAp()) + "     " + state.queued() + " QUEUED");
        apMeter.set(state.ap(), state.maxAp(), 0);
        grid.removeAllViews();
        Part selected = null;
        LinearLayout currentRow = null;
        int index = 0;
        for (Part part : state.parts()) {
            if (part.selected()) selected = part;
            if (index % 2 == 0) {
                currentRow = row(); LayoutParams rowParams = full(); rowParams.bottomMargin = dp(5); grid.addView(currentRow, rowParams);
            }
            LinearLayout tile = new LinearLayout(getContext()); tile.setOrientation(VERTICAL);
            tile.setGravity(Gravity.CENTER_VERTICAL); tile.setPadding(dp(8), dp(5), dp(8), dp(5));
            tile.setBackground(background(part.selected())); tile.setMinimumHeight(dp(58));
            LinearLayout line = row();
            TextView name = text(part.name(), 11, true); line.addView(name, weighted());
            TextView chance = text(part.chance() + "%", 15, true); line.addView(chance);
            tile.addView(line, full());
            QueueMark mark = new QueueMark(getContext());
            mark.setText(part.queued() > 0 ? String.join(" ", java.util.Collections.nCopies(part.queued(), "X")) : "");
            mark.setGravity(Gravity.CENTER);
            mark.setContentDescription(part.queued() + " attacks queued");
            tile.addView(mark, full());
            LinearLayout condition = row();
            TextView label = text(part.condition() == 0 ? "CRIPPLED" : "CND", 8, false);
            condition.addView(label);
            Meter meter = new Meter(getContext()); meter.set(part.condition(), 1000, 0);
            LayoutParams meterParams = new LayoutParams(0, dp(3), 1); meterParams.leftMargin = dp(5);
            condition.addView(meter, meterParams); tile.addView(condition, full());
            tile.setAlpha(state.ready() ? 1 : .4f);
            tile.setContentDescription(part.name() + ", " + part.chance() + "% hit chance, " + part.queued() + " queued");
            tile.setOnClickListener(view -> {
                if (!state.ready()) return;
                for (Part current : state.parts()) {
                    if (current.token() != part.token()) continue;
                    if (!current.selected()) actions.send(1, current.token());
                    else if (current.enabled() && state.ap() + .001f >= state.cost()) actions.send(2, current.token());
                    break;
                }
            });
            LayoutParams tileParams = new LayoutParams(0, LayoutParams.WRAP_CONTENT, 1);
            if (index % 2 != 0) tileParams.leftMargin = dp(5);
            currentRow.addView(tile, tileParams); index++;
        }
        if (index % 2 != 0 && currentRow != null) currentRow.addView(new View(getContext()), weighted());
        status.setVisibility(state.ready() && !state.parts().isEmpty() ? GONE : VISIBLE);
        status.setText(state.parts().isEmpty() && state.ready() ? "NO TARGETABLE BODY PARTS" : "V.A.T.S. IN PROGRESS");
        detail.setText(selected == null ? "SELECT A BODY PART" : selected.name() + "\nDMG " + number(state.damage()) + " · AP " + number(state.cost()));
        apMeter.setPreview(state.ready() && selected != null && selected.enabled()
                && state.ap() + .001f >= state.cost() ? state.cost() : 0);
        enabled(previous, state.ready() && state.targets() > 1); enabled(next, state.ready() && state.targets() > 1);
        enabled(undo, state.ready() && state.queued() > 0); enabled(execute, state.ready() && state.queued() > 0);
        enabled(exit, state.ready());
        execute.setText(state.queued() > 0 ? "EXECUTE " + state.queued() : "EXECUTE");
    }

    private final class QueueMark extends TextView {
        QueueMark(Context context) {
            super(context); setTextColor(color); setTextSize(11); setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        }
        @Override protected void onDraw(Canvas canvas) {
            int alpha = (SystemClock.uptimeMillis() % 900) < 450 ? 255 : 65;
            int tint = Color.argb(alpha, Color.red(color), Color.green(color), Color.blue(color));
            if (getCurrentTextColor() != tint) setTextColor(tint);
            super.onDraw(canvas);
            if (length() > 0 && isShown()) postInvalidateDelayed(80);
        }
    }
    private final class Meter extends View {
        private final Paint paint = new Paint();
        private float value, maximum = 1, predicted;
        private float preview;
        Meter(Context context) { super(context); }
        void set(float value, float maximum, float predicted) {
            this.value = Math.max(0, value); this.maximum = Math.max(1, maximum); this.predicted = Math.max(0, predicted); invalidate();
        }
        void setPreview(float cost) { preview = Math.max(0, Math.min(value, cost)); invalidate(); }
        private int pulseAlpha() {
            double phase = (SystemClock.uptimeMillis() % 900) * (2 * Math.PI / 900);
            return 45 + (int) Math.round(210 * (.5 + .5 * Math.cos(phase)));
        }
        @Override protected void onDraw(Canvas canvas) {
            float end = Math.min(1, value / maximum) * getWidth();
            float after = Math.min(1, Math.max(0, value - predicted) / maximum) * getWidth();
            float previewStart = Math.min(1, Math.max(0, value - preview) / maximum) * getWidth();
            paint.setColor(color); paint.setAlpha(35); canvas.drawRect(0, 0, getWidth(), getHeight(), paint);
            if (preview > 0) {
                paint.setAlpha(255); canvas.drawRect(0, 0, previewStart, getHeight(), paint);
                paint.setAlpha(pulseAlpha());
                canvas.drawRect(previewStart, 0, end, getHeight(), paint);
                if (isShown()) postInvalidateOnAnimation();
            } else {
                paint.setAlpha(255); canvas.drawRect(0, 0, after, getHeight(), paint);
                paint.setAlpha(pulseAlpha());
                canvas.drawRect(after, 0, end, getHeight(), paint);
                if (predicted > 0 && isShown()) postInvalidateOnAnimation();
            }
        }
    }
}
