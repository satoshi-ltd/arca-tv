package com.satoshilimited.tv;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.os.Bundle;
import android.view.KeyEvent;
import android.view.View;

public class OnboardingActivity extends Activity {
    private static final int WELCOME = 0, HUB = 1, CITY = 2, DONE = 3;
    private OnboardingView view;

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Watchdog.install(this);
        Ui.init(this);
        view = new OnboardingView(this);
        setContentView(view);
    }

    @Override protected void onResume() {
        super.onResume();
        view.resume();
    }

    @Override public boolean dispatchKeyEvent(KeyEvent event) {
        if (view.onKey(event)) return true;
        return super.dispatchKeyEvent(event);
    }

    static final class OnboardingView extends View {
        private final FocusRing focusRing = new FocusRing(this);
        private static final int INK = 0xFFF2F2F2, MUTE = 0xC2FFFFFF, WASH = 0xFF7FB893;
        private static final int FIELD = -1, PRIMARY = 0, SECONDARY = 1;

        private final OnboardingActivity activity;
        private final Backdrop backdrop = new Backdrop();
        private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG), stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF rect = new RectF(), tmp = new RectF();
        private int page = WELCOME, focus = PRIMARY;
        private boolean okDown;
        private int w, h;
        private float k = 1;
        private Header header;
        private Paint titlePaint, subPaint, pillPaint;

        OnboardingView(OnboardingActivity a) {
            super(a);
            activity = a;
            stroke.setStyle(Paint.Style.STROKE);
            setFocusable(true);
            setBackgroundColor(0xFF000000);
        }

        void resume() {
            if (page == HUB && ArcaHub.connected(getContext())) advance();
            backdrop.showWash("onboarding", WASH, 0.5f);
            invalidate();
        }

        @Override protected void onSizeChanged(int width, int height, int oldWidth, int oldHeight) {
            w = width;
            h = height;
            k = w / 1920f;
            header = new Header(k);
            titlePaint = Ui.text(Ui.light, 64 * k, INK);
            titlePaint.setTextAlign(Paint.Align.CENTER);
            subPaint = Ui.text(Ui.regular, Ui.BODY * k, MUTE);
            subPaint.setTextAlign(Paint.Align.CENTER);
            pillPaint = Ui.text(Ui.medium, 28 * k, INK);
            pillPaint.setTextAlign(Paint.Align.CENTER);
        }

        private String title() {
            switch (page) {
                case WELCOME: return "Your TV, calmer.";
                case CITY: return "Where are you?";
                case HUB: return "Connect your hub";
                default: return "You're set.";
            }
        }

        private String subtitle() {
            switch (page) {
                case WELCOME: return "No ads. Your apps up front. Pick up where you left off.";
                case CITY: return "So the weather matches this TV.";
                case HUB: return "Unlocks arca Photos and arca Music on this TV.";
                default: return "Change anything later in Settings.";
            }
        }

        private String primaryLabel() {
            switch (page) {
                case WELCOME: return "Continue";
                case HUB: return "Connect now";
                case CITY: return "Continue";
                default: return "Go to Home";
            }
        }

        private boolean hasSecondary() {
            return page == CITY || page == HUB;
        }

        private void pillBox(int which, RectF out) {
            boolean two = hasSecondary();
            float wPrimary = 320, wSecondary = 320, gap = 24;
            float total = two ? wPrimary + gap + wSecondary : wPrimary;
            float left = (1920 - total) / 2 + (which == SECONDARY ? wPrimary + gap : 0);
            float width = which == SECONDARY ? wSecondary : wPrimary;
            float top = page == CITY ? 714 : 680;
            out.set(left * k, top * k, (left + width) * k, (top + 72) * k);
        }

        private void fieldBox(RectF out) {
            float width = 760, left = (1920 - width) / 2;
            out.set(left * k, 606 * k, (left + width) * k, 678 * k);
        }

        @Override protected void onDraw(Canvas c) {
            if (w == 0) return;
            backdrop.draw(c, w, h, 1f);
            fill.setColor(0x4D0A0A0A);
            c.drawRect(0, 0, w, h, fill);

            if (page == DONE) drawCheck(c, 960 * k, 300 * k);
            else if (page == WELCOME) header.drawMarkCentered(c, 960 * k, 300 * k, 120 * k);

            c.drawText(title(), 960 * k, page == WELCOME ? 470 * k : 480 * k, titlePaint);
            drawWrapped(c, subtitle(), 960 * k, 544 * k, 1100 * k);

            if (page == CITY) {
                fieldBox(tmp);
                Glass.plate(c, tmp, tmp.height() / 2, k, fill, stroke);
                boolean hasCity = !Prefs.city().isEmpty();
                pillPaint.setColor(hasCity ? INK : 0x80FFFFFF);
                pillPaint.setTextAlign(Paint.Align.LEFT);
                c.drawText(hasCity ? Prefs.city() : "Search for a city", tmp.left + 32 * k, tmp.centerY() + Ui.halfCap(pillPaint), pillPaint);
                pillPaint.setTextAlign(Paint.Align.CENTER);
                if (focus == FIELD) focusRing.focus(c, tmp, tmp.height() / 2, "controls", k, fill, stroke);
            }

            pillBox(PRIMARY, rect);
            fill.setColor(INK);
            c.drawRoundRect(rect, 36 * k, 36 * k, fill);
            pillPaint.setColor(0xFF141414);
            c.drawText(primaryLabel(), rect.centerX(), rect.centerY() + Ui.halfCap(pillPaint), pillPaint);
            if (focus == PRIMARY) focusRing.focus(c, rect, 36 * k, "controls", k, fill, stroke);

            if (hasSecondary()) {
                pillBox(SECONDARY, rect);
                Glass.plate(c, rect, 36 * k, k, fill, stroke);
                pillPaint.setColor(INK);
                c.drawText("Skip for now", rect.centerX(), rect.centerY() + Ui.halfCap(pillPaint), pillPaint);
                if (focus == SECONDARY) focusRing.focus(c, rect, 36 * k, "controls", k, fill, stroke);
            }

            boolean skip = citySkippable();
            float dotY = 840 * k, step = 28 * k, startX = 960 * k - step * (skip ? 1f : 1.5f);
            for (int i = 0, dot = 0; i <= DONE; i++) {
                if (skip && i == CITY) continue;
                fill.setColor(i == page ? INK : 0x40FFFFFF);
                c.drawCircle(startX + dot++ * step, dotY, (i == page ? 6 : 5) * k, fill);
            }

            if (backdrop.active()) postInvalidateDelayed(50);
        }

        private void drawWrapped(Canvas c, String text, float cx, float y, float maxWidth) {
            String[] words = text.split(" ");
            StringBuilder line = new StringBuilder();
            float lineY = y;
            for (String word : words) {
                String next = line.length() == 0 ? word : line + " " + word;
                if (subPaint.measureText(next) > maxWidth && line.length() > 0) {
                    c.drawText(line.toString(), cx, lineY, subPaint);
                    line = new StringBuilder(word);
                    lineY += 40 * k;
                } else line = new StringBuilder(next);
            }
            if (line.length() > 0) c.drawText(line.toString(), cx, lineY, subPaint);
        }

        private void drawCheck(Canvas c, float cx, float cy) {
            fill.setColor(0x33FFFFFF);
            c.drawCircle(cx, cy, 56 * k, fill);
            Icons.draw(getContext(), c, R.drawable.icon_check, cx, cy, 52 * k, INK);
        }

        private boolean citySkippable() {
            return !Config.CITY.trim().isEmpty();
        }

        private void retreat() {
            if (page == WELCOME) return;
            page--;
            if (page == CITY && citySkippable()) page--;
            focus = page == CITY ? FIELD : PRIMARY;
            invalidate();
        }

        private void advance() {
            if (page == DONE) {
                Prefs.setOnboardingDone();
                activity.startActivity(new Intent(getContext(), HomeActivity.class));
                activity.finish();
                return;
            }
            page++;
            if (page == CITY && citySkippable()) page++;
            focus = page == CITY ? FIELD : PRIMARY;
            invalidate();
        }

        private void press() {
            if (focus == FIELD) {
                activity.startActivity(new Intent(getContext(), LocationActivity.class));
            } else if (focus == PRIMARY) {
                if (page == HUB && !ArcaHub.connected(getContext())) {
                    activity.startActivity(new Intent(getContext(), HubActivity.class));
                } else {
                    advance();
                }
            } else {
                advance();
            }
        }

        boolean onKey(KeyEvent e) {
            boolean down = e.getAction() == KeyEvent.ACTION_DOWN;
            focusRing.key(e);
            switch (e.getKeyCode()) {
                case KeyEvent.KEYCODE_DPAD_LEFT:
                    if (down && focus != FIELD && hasSecondary()) focus = PRIMARY;
                    break;
                case KeyEvent.KEYCODE_DPAD_RIGHT:
                    if (down && focus != FIELD && hasSecondary()) focus = SECONDARY;
                    break;
                case KeyEvent.KEYCODE_DPAD_UP:
                    if (down && page == CITY && focus != FIELD) focus = FIELD;
                    break;
                case KeyEvent.KEYCODE_DPAD_DOWN:
                    if (down && page == CITY && focus == FIELD) focus = PRIMARY;
                    break;
                case KeyEvent.KEYCODE_DPAD_CENTER:
                case KeyEvent.KEYCODE_ENTER:
                    if (down) {
                        if (e.getRepeatCount() == 0) okDown = true;
                    } else {
                        if (okDown && !e.isCanceled()) press();
                        okDown = false;
                    }
                    break;
                case KeyEvent.KEYCODE_BACK:
                    if (down && e.getRepeatCount() == 0) retreat();
                    return true;
                default:
                    return false;
            }
            invalidate();
            return true;
        }
    }
}
