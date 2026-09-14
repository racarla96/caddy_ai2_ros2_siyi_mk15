package com.caddyai2.siyimk15teleop.util;

import android.app.Activity;
import android.view.View;
import android.view.WindowManager;

/**
 * Hides the status/navigation bars so the app uses the whole screen ("maximizada" —
 * requested 2026-09-14, this runs as a dedicated field-operator tool, not one of several
 * apps a user switches between). Uses the legacy {@code View#setSystemUiVisibility} flags
 * rather than {@code WindowInsetsController} since this app's {@code minSdk} is 26
 * (the controller-based replacement needs API 30+).
 *
 * <p>Also sets {@code FLAG_KEEP_SCREEN_ON} (2026-09-14 — root-caused a real bug: the user
 * reported the app "freezes after a few minutes and stops publishing". Not a crash —
 * {@code MainActivity.onStop()} deliberately stops {@code SiyiSerialReader}/
 * {@code SteeringReferencePublisher}, and Android calls {@code onStop()} the moment the
 * screen times out from inactivity and dims/locks, which on an idle handset with nobody
 * touching the screen happens within a couple of minutes by default. From the operator's
 * point of view that's indistinguishable from a hang: the UI stops updating and the robot
 * stops receiving commands, with no crash or error shown. Keeping the screen on for as
 * long as any of this app's activities is in the foreground prevents that timeout from
 * ever firing in the first place). This is a deliberate trade-off for a dedicated
 * field-operator device, same rationale as the fullscreen/immersive choice above — it
 * does trade battery life for never silently losing the link.
 *
 * <p>Call {@link #apply(Activity)} from both {@code onCreate} and {@code onWindowFocusChanged}
 * — the fullscreen flags get cleared by system UI transitions (e.g. a dialog, or the user
 * swiping from an edge), so this needs reapplying each time the window regains focus, not
 * just once. {@code FLAG_KEEP_SCREEN_ON} itself doesn't get cleared the same way, but
 * reapplying it here too is harmless and keeps this a single call site to remember.
 */
public final class FullscreenHelper {

    private FullscreenHelper() {
    }

    public static void apply(Activity activity) {
        activity.getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

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
