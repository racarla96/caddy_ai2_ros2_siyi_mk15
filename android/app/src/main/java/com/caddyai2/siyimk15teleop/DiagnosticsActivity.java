package com.caddyai2.siyimk15teleop;

import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.Button;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

import com.caddyai2.siyimk15teleop.diagnostics.DiagnosticsState;
import com.caddyai2.siyimk15teleop.sdk.FirmwareVersion;
import com.caddyai2.siyimk15teleop.sdk.SdkFrame;
import com.caddyai2.siyimk15teleop.sdk.SdkFrameParser;
import com.caddyai2.siyimk15teleop.sdk.SdkSerialLink;
import com.caddyai2.siyimk15teleop.util.FullscreenHelper;

import java.io.IOException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * The raw protocol/link diagnostics screen — frame counters, unknown-frame tally, the raw
 * 16-channel dump, the SIYI Datalink SDK (ttyHS0) one-shot test, and the scrolling event log.
 * Split out of {@code MainActivity} (2026-09-14) so the main screen only shows what a field
 * operator needs at a glance; this is for whoever's actually debugging the link.
 *
 * <p>Reads {@link DiagnosticsState} — the same singleton {@code MainActivity} writes to from
 * the serial read thread — via a short polling loop while this activity is visible, rather
 * than a cross-activity push callback (see that class's Javadoc for why).
 */
public class DiagnosticsActivity extends AppCompatActivity {

    private static final long POLL_INTERVAL_MS = 300;

    private final DiagnosticsState diagnostics = DiagnosticsState.getInstance();
    private final Handler handler = new Handler(Looper.getMainLooper());

    private TextView connectionDetailText;
    private TextView channelsText;
    private TextView statsText;
    private TextView unknownFramesText;
    private TextView logText;
    private ScrollView logScrollView;
    private Button sdkTestButton;

    private static final long SDK_TEST_TIMEOUT_MS = 3000;
    private final AtomicBoolean sdkTestInProgress = new AtomicBoolean(false);

    private final Runnable pollTask = new Runnable() {
        @Override
        public void run() {
            refresh();
            handler.postDelayed(this, POLL_INTERVAL_MS);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        FullscreenHelper.apply(this);
        setContentView(R.layout.activity_diagnostics);

        connectionDetailText = findViewById(R.id.connectionDetailText);
        channelsText = findViewById(R.id.channelsText);
        statsText = findViewById(R.id.statsText);
        unknownFramesText = findViewById(R.id.unknownFramesText);
        logText = findViewById(R.id.logText);
        logScrollView = findViewById(R.id.logScrollView);
        sdkTestButton = findViewById(R.id.sdkTestButton);
        sdkTestButton.setOnClickListener(v ->
                runSdkTest("FirmwareVersion", FirmwareVersion.encodeRequest()));
        findViewById(R.id.backButton).setOnClickListener(v -> finish());
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) {
            FullscreenHelper.apply(this);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        handler.post(pollTask);
    }

    @Override
    protected void onPause() {
        super.onPause();
        handler.removeCallbacks(pollTask);
    }

    private void refresh() {
        DiagnosticsState.Snapshot s = diagnostics.snapshot();
        connectionDetailText.setText(s.connectionDetail);
        channelsText.setText(s.rawChannelsText);
        statsText.setText(getString(R.string.stats_format, s.validFrames, s.crcErrors, s.resyncs));
        unknownFramesText.setText(getString(R.string.unknown_frames_format,
                s.unknownFramesSummary == null ? getString(R.string.unknown_frames_none) : s.unknownFramesSummary));
        logText.setText(s.logText);
        logScrollView.post(() -> logScrollView.fullScroll(View.FOCUS_DOWN));
    }

    // ---- SIYI Datalink SDK (ttyHS0) one-shot diagnostic ---------------------------------

    /**
     * Opens {@link SdkSerialLink#DEVICE_PATH}, sends one pre-encoded SDK request, and logs
     * whatever comes back (or times out after {@link #SDK_TEST_TIMEOUT_MS}).
     *
     * <p>Plain blocking Java I/O has no per-call read timeout, so the timeout is enforced
     * by a watchdog thread that closes the link out from under a blocked {@code read()} —
     * that unblocks it with an {@link IOException}, which this method distinguishes from a
     * genuine I/O error via {@link #sdkTestInProgress}/the {@code timedOut} flag below.
     */
    private void runSdkTest(String requestLabel, byte[] requestBytes) {
        if (!sdkTestInProgress.compareAndSet(false, true)) {
            return; // a test is already running
        }
        runOnUiThread(() -> sdkTestButton.setEnabled(false));
        diagnostics.appendLog("[SDK] Abriendo " + SdkSerialLink.DEVICE_PATH + "...");

        SdkSerialLink link = new SdkSerialLink();
        AtomicReference<SdkFrame> received = new AtomicReference<>();
        AtomicBoolean timedOut = new AtomicBoolean(false);
        SdkFrameParser parser = new SdkFrameParser(new SdkFrameParser.Listener() {
            @Override
            public void onFrame(SdkFrame frame) {
                received.compareAndSet(null, frame);
            }

            @Override
            public void onResync() {
                diagnostics.appendLog("[SDK] resync");
            }

            @Override
            public void onCrcError(int cmdId) {
                diagnostics.appendLog("[SDK] crc_error cmdId=0x" + Integer.toHexString(cmdId));
            }
        });

        Thread watchdog = new Thread(() -> {
            try {
                Thread.sleep(SDK_TEST_TIMEOUT_MS);
            } catch (InterruptedException e) {
                return; // worker finished first; nothing to do
            }
            if (received.get() == null) {
                timedOut.set(true);
                try {
                    link.close(); // unblocks a worker stuck in a blocking read()
                } catch (IOException ignored) {
                }
            }
        }, "sdk-test-watchdog");
        watchdog.setDaemon(true);

        Thread worker = new Thread(() -> {
            try {
                link.open();
                link.send(requestBytes);
                diagnostics.appendLog("[SDK] Petición " + requestLabel + " enviada, esperando...");
                byte[] buf = new byte[256];
                while (received.get() == null) {
                    int n = link.read(buf);
                    if (n < 0) {
                        break; // EOF
                    }
                    if (n > 0) {
                        parser.feed(buf, 0, n);
                    }
                }
            } catch (IOException e) {
                if (!timedOut.get()) {
                    diagnostics.appendLog("[SDK] Error: " + e.getMessage());
                }
            } finally {
                watchdog.interrupt();
                try {
                    link.close();
                } catch (IOException ignored) {
                }
                SdkFrame frame = received.get();
                if (frame != null) {
                    diagnostics.appendLog("[SDK] " + describeSdkFrame(frame));
                } else if (timedOut.get()) {
                    diagnostics.appendLog("[SDK] Sin respuesta en " + SDK_TEST_TIMEOUT_MS + "ms");
                }
                runOnUiThread(() -> sdkTestButton.setEnabled(true));
                sdkTestInProgress.set(false);
            }
        }, "sdk-test");

        watchdog.start();
        worker.start();
    }

    private static String describeSdkFrame(SdkFrame frame) {
        if (frame.cmdId == FirmwareVersion.CMD_ID) {
            try {
                return FirmwareVersion.decode(frame).toString();
            } catch (IllegalArgumentException e) {
                return "Frame 0x47 con payload inesperado: " + frame;
            }
        }
        return "Frame recibido: " + frame;
    }
}
