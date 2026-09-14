package com.caddyai2.siyimk15teleop.util;

import android.app.Activity;
import android.view.View;

/**
 * Hides the status/navigation bars so the app uses the whole screen ("maximizada" —
 * requested 2026-09-14, this runs as a dedicated field-operator tool, not one of several
 * apps a user switches between). Uses the legacy {@code View#setSystemUiVisibility} flags
 * rather than {@code WindowInsetsController} since this app's {@code minSdk} is 26
 * (the controller-based replacement needs API 30+).
 *
 * <p>Call {@link #apply(Activity)} from both {@code onCreate} and {@code onWindowFocusChanged}
 * — the flags get cleared by system UI transitions (e.g. a dialog, or the user swiping from
 * an edge), so this needs reapplying each time the window regains focus, not just once.
 */
public final class FullscreenHelper {

    private FullscreenHelper() {
    }

    public static void apply(Activity activity) {
        View decorView = activity.getWindow().getDecorView();
        decorView.setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                        | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
    }
}
