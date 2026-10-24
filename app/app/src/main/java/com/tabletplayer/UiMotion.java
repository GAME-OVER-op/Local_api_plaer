package com.tabletplayer;

import android.app.Activity;
import android.os.Build;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.LayoutAnimationController;
import android.view.animation.AnimationUtils;
import android.widget.AbsListView;

/** Shared visual motion helpers. Keeps the UI consistent without adding a heavy UI dependency. */
public final class UiMotion {
    private static final long ENTER_MS = 300L;
    private static final long PRESS_MS = 110L;

    private UiMotion() {}

    public static void configureWindow(Activity activity) {
        if (activity == null) return;
        Window window = activity.getWindow();
        final boolean dark = App.isDark(activity);
        if (Build.VERSION.SDK_INT >= 21) {
            try {
                int status = activity.getResources().getColor(!dark && Build.VERSION.SDK_INT < 23 ? R.color.primary_dark : R.color.bg);
                int navigation = activity.getResources().getColor(!dark && Build.VERSION.SDK_INT < 26 ? R.color.primary_dark : R.color.bg);
                window.setStatusBarColor(status);
                window.setNavigationBarColor(navigation);
            } catch (Throwable ignored) {}
        }
        if (Build.VERSION.SDK_INT >= 23) {
            View decor = window.getDecorView();
            int flags = decor.getSystemUiVisibility();
            if (dark) flags &= ~View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
            else flags |= View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
            if (Build.VERSION.SDK_INT >= 26) {
                if (dark) flags &= ~View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
                else flags |= View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
            }
            decor.setSystemUiVisibility(flags);
        }
    }

    public static void enter(View root) {
        if (root == null) return;
        root.animate().cancel();
        root.setAlpha(0f);
        root.setTranslationY(dp(root, 14));
        root.post(() -> root.animate()
                .alpha(1f)
                .translationY(0f)
                .setDuration(ENTER_MS)
                .setInterpolator(new DecelerateInterpolator(1.7f))
                .start());
    }

    public static void reveal(View view, long delayMs) {
        if (view == null) return;
        view.animate().cancel();
        view.setAlpha(0f);
        view.setTranslationY(dp(view, 10));
        view.post(() -> view.animate().alpha(1f).translationY(0f)
                .setStartDelay(Math.max(0L, delayMs))
                .setDuration(260L)
                .setInterpolator(new DecelerateInterpolator(1.6f)).start());
    }

    public static void press(View view) {
        if (view == null) return;
        view.setOnTouchListener((v, event) -> {
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    v.animate().cancel();
                    v.animate().scaleX(0.965f).scaleY(0.965f).alpha(0.90f).setDuration(PRESS_MS).start();
                    break;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    v.animate().cancel();
                    v.animate().scaleX(1f).scaleY(1f).alpha(1f).setDuration(150L)
                            .setInterpolator(new DecelerateInterpolator(1.8f)).start();
                    break;
                default:
                    break;
            }
            return false;
        });
    }

    public static void pressAll(View... views) {
        if (views == null) return;
        for (View v : views) press(v);
    }

    public static void animateList(AbsListView list) {
        if (list == null) return;
        try {
            LayoutAnimationController controller = AnimationUtils.loadLayoutAnimation(list.getContext(), R.anim.layout_anim);
            list.setLayoutAnimation(controller);
            list.scheduleLayoutAnimation();
        } catch (Throwable ignored) {}
    }

    public static void fadeVisible(View view, boolean visible) {
        if (view == null) return;
        view.setTag(visible);
        view.animate().cancel();
        if (visible) {
            if (view.getVisibility() != View.VISIBLE) {
                view.setAlpha(0f);
                view.setScaleX(0.97f);
                view.setScaleY(0.97f);
                view.setVisibility(View.VISIBLE);
            }
            view.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(180L)
                    .setInterpolator(new DecelerateInterpolator(1.8f)).start();
        } else if (view.getVisibility() == View.VISIBLE) {
            view.animate().alpha(0f).scaleX(0.98f).scaleY(0.98f).setDuration(140L)
                    .withEndAction(() -> {
                        if (!Boolean.FALSE.equals(view.getTag())) return;
                        view.setVisibility(View.GONE);
                        view.setAlpha(1f);
                        view.setScaleX(1f);
                        view.setScaleY(1f);
                    }).start();
        }
    }

    public static void cascade(ViewGroup group) {
        if (group == null) return;
        int shown = 0;
        for (int i = 0; i < group.getChildCount(); i++) {
            View child = group.getChildAt(i);
            if (child.getVisibility() != View.VISIBLE) continue;
            reveal(child, Math.min(180L, shown * 34L));
            shown++;
        }
    }

    private static float dp(View view, float value) {
        return value * view.getResources().getDisplayMetrics().density;
    }
}
