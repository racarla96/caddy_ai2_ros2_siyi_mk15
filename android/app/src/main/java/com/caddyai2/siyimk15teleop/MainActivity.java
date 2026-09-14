package com.caddyai2.siyimk15teleop;

import android.content.Context;
import android.content.Intent;
import android.net.wifi.WifiManager;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

import com.caddyai2.siyimk15teleop.config.TeleopConfig;
import com.caddyai2.siyimk15teleop.kinematics.BicycleTwistComputer;
import com.caddyai2.siyimk15teleop.protocol.ChannelMapper;
import com.caddyai2.siyimk15teleop.protocol.DecodedFrame;
import com.caddyai2.siyimk15teleop.ros2.SteeringReferencePublisher;
import com.caddyai2.siyimk15teleop.sdk.ChannelData;
import com.caddyai2.siyimk15teleop.sdk.FirmwareVersion;
import com.caddyai2.siyimk15teleop.sdk.SdkFrame;
import com.caddyai2.siyimk15teleop.sdk.SdkFrameParser;
import com.caddyai2.siyimk15teleop.sdk.SdkSerialLink;
import com.caddyai2.siyimk15teleop.serial.SiyiSerialReader;

import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.Comparator;
import java.util.Date;
import java.util.Deque;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

/**
 * Wires the serial reader -&gt; frame parser -&gt; channel mapper -&gt; bicycle
 * kinematics -&gt; ROS 2 Twist publisher pipeline, and shows live diagnostics.
 *
 * All the actual protocol/kinematics logic lives in plain-Java, unit-testable
 * classes; this activity is just wiring + a WiFi multicast lock (required for
 * Fast-DDS SPDP/SEDP discovery on most Android WiFi stacks) + UI updates.
 *
 * <p>{@link #onStart()} rebuilds both {@link #twistComputer} and
 * {@link #cmdVelPublisher} from the current {@link TeleopConfig} every time
 * (not just once in {@code onCreate}) so that returning here from
 * {@link SettingsActivity} — which always stops/restarts this activity —
 * picks up whatever was just saved without needing a full app relaunch.
 */
public class MainActivity extends AppCompatActivity implements SiyiSerialReader.Listener {

    private TeleopConfig config;
    private ChannelMapper channelMapper;
    // One computer per TeleopConfig driving profile (index matches ChannelMapper's CH7
    // three-way-switch bucket, see TeleopConfig's Javadoc) — rebuilt from config in onStart(),
    // selected live per-frame in onFrame() by the switch's current position.
    private BicycleTwistComputer[] twistComputers = new BicycleTwistComputer[TeleopConfig.PROFILE_COUNT];
    private TeleopConfig.Profile[] profiles = new TeleopConfig.Profile[TeleopConfig.PROFILE_COUNT];
    private int activeProfileIndex = -1;
    private SteeringReferencePublisher cmdVelPublisher;
    private SiyiSerialReader serialReader;
    private WifiManager.MulticastLock multicastLock;

    private TextView statusText;
    private TextView channelsText;
    private TextView twistText;
    private TextView profileText;
    private TextView statsText;
    private TextView unknownFramesText;
    private TextView logText;
    private ScrollView logScrollView;

    private final AtomicLong validFrames = new AtomicLong();
    private final AtomicLong crcErrors = new AtomicLong();
    private final AtomicLong resyncs = new AtomicLong();

    // (type, sub_id) -> count, for frames whose (type, sub_id) isn't in FrameCatalog.
    // Surfaced live in the UI so an unexpected wire format (like the 0x0c/0x3e frames
    // seen in the 2026-09-08 on-device capture) is visible without an adb pull —
    // see PROTOCOL.md's "Open question" note and tools/analyze_capture.py.
    private static final int MAX_UNKNOWN_GROUPS_SHOWN = 5;
    private final Map<Integer, AtomicLong> unknownFrameCounts = new ConcurrentHashMap<>();

    // Scrolling event log: connect/disconnect + the *first* time each unknown (type,
    // sub_id) shows up (counts for repeats already live in unknownFrameCounts above —
    // logging every repeat here would just spam the window during a bad capture).
    private static final int MAX_LOG_LINES = 200;
    private final Deque<String> logLines = new ArrayDeque<>();
    private final Set<Integer> seenUnknownTypes = ConcurrentHashMap.newKeySet();
    private final SimpleDateFormat logTimeFormat = new SimpleDateFormat("HH:mm:ss", Locale.getDefault());

    // On-demand diagnostics for the SIYI Datalink SDK protocol (ttyHS0, see PROTOCOL.md's
    // "a second, documented protocol exists" section) -- one-shot request/response, not a
    // persistent background link like SiyiSerialReader/ttyHS1, since ttyHS0 traffic depends
    // on a still-undiscovered "Datalink -> Connection -> UART" toggle in the vendor's own
    // app and there's nothing to passively listen to yet. Both buttons share one in-flight
    // flag/watchdog path (runSdkTest) since they'd otherwise race to open the same device.
    private static final long SDK_TEST_TIMEOUT_MS = 3000;
    private Button sdkTestButton;
    private Button sdkChannelsTestButton;
    private final AtomicBoolean sdkTestInProgress = new AtomicBoolean(false);

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        statusText = findViewById(R.id.statusText);
        channelsText = findViewById(R.id.channelsText);
        twistText = findViewById(R.id.twistText);
        profileText = findViewById(R.id.profileText);
        statsText = findViewById(R.id.statsText);
        unknownFramesText = findViewById(R.id.unknownFramesText);
        logText = findViewById(R.id.logText);
        logScrollView = findViewById(R.id.logScrollView);
        findViewById(R.id.settingsButton).setOnClickListener(
                v -> startActivity(new Intent(this, SettingsActivity.class)));
        sdkTestButton = findViewById(R.id.sdkTestButton);
        sdkTestButton.setOnClickListener(v ->
                runSdkTest("FirmwareVersion", FirmwareVersion.encodeRequest()));
        sdkChannelsTestButton = findViewById(R.id.sdkChannelsTestButton);
        sdkChannelsTestButton.setOnClickListener(v ->
                runSdkTest("ChannelData", ChannelData.encodeRequest(ChannelData.Frequency.HZ_10)));

        config = new TeleopConfig(this);
        channelMapper = new ChannelMapper();

        acquireMulticastLock();
    }

    private void rebuildTwistComputers() {
        for (int i = 0; i < TeleopConfig.PROFILE_COUNT; i++) {
            profiles[i] = config.getProfile(i);
            twistComputers[i] = new BicycleTwistComputer(
                    profiles[i].maxSteerAngleRad(),
                    profiles[i].maxSpeedMps);
        }
        activeProfileIndex = -1; // force the first onFrame to log the initial profile
    }

    private void acquireMulticastLock() {
        WifiManager wifi = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
        if (wifi != null) {
            multicastLock = wifi.createMulticastLock("siyi_mk15_teleop_mcast");
            multicastLock.setReferenceCounted(true);
            multicastLock.acquire();
        }
    }

    @Override
    protected void onStart() {
        super.onStart();

        rebuildTwistComputers();
        cmdVelPublisher = new SteeringReferencePublisher(
                config.getTopicName(),
                config.getFrameId(),
                config.getDomainId(),
                config.getPublishRateHz(),
                config.getLocalStaleTimeoutMs());
        cmdVelPublisher.start();
        appendLog(String.format(Locale.getDefault(),
                "Config: topic=%s frame=%s domain=%d rate=%dHz",
                config.getTopicName(), config.getFrameId(), config.getDomainId(),
                config.getPublishRateHz()));
        for (int i = 0; i < TeleopConfig.PROFILE_COUNT; i++) {
            appendLog(String.format(Locale.getDefault(),
                    "  Perfil %d (%s): maxSteer=%.1f° maxSpeed=%.2fm/s",
                    i, profiles[i].name, profiles[i].maxSteerAngleDeg, profiles[i].maxSpeedMps));
        }

        serialReader = new SiyiSerialReader(this);
        serialReader.start();
    }

    @Override
    protected void onStop() {
        super.onStop();
        if (serialReader != null) {
            serialReader.stop();
            serialReader = null;
        }
        if (cmdVelPublisher != null) {
            cmdVelPublisher.stop();
            cmdVelPublisher = null;
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (multicastLock != null && multicastLock.isHeld()) {
            multicastLock.release();
        }
    }

    // ---- SiyiSerialReader.Listener / FrameParser.Listener ------------------------------

    @Override
    public void onConnected() {
        runOnUiThread(() -> {
            statusText.setText(R.string.status_connected);
            appendLog("Conectado a " + SiyiSerialReader.DEVICE_PATH);
        });
    }

    @Override
    public void onDisconnected(Throwable error) {
        runOnUiThread(() -> {
            statusText.setText(error == null ? getString(R.string.status_disconnected)
                    : getString(R.string.status_error, error.getMessage()));
            appendLog("Desconectado" + (error == null ? "" : ": " + error));
        });
    }

    @Override
    public void onFrame(DecodedFrame frame) {
        if (!frame.isChannelFrame()) {
            return; // buttons/telemetry frames: not decoded yet, see FrameCatalog.
        }
        validFrames.incrementAndGet();

        int[] channels = frame.decodeChannels();
        double steerNorm = channelMapper.normalizeSteer(channels);
        double throttleNorm = channelMapper.normalizeThrottle(channels);

        int profileIndex = channelMapper.profileSwitchPosition(channels);
        if (profileIndex != activeProfileIndex) {
            activeProfileIndex = profileIndex;
            TeleopConfig.Profile p = profiles[profileIndex];
            appendLog(String.format(Locale.getDefault(),
                    "Perfil activo: %s (maxSteer=%.1f° maxSpeed=%.2fm/s, CH7)",
                    p.name, p.maxSteerAngleDeg, p.maxSpeedMps));
        }
        BicycleTwistComputer.Twist twist = twistComputers[profileIndex].compute(steerNorm, throttleNorm);
        cmdVelPublisher.updateCommand(twist.linearX, twist.angularZ);

        runOnUiThread(() -> {
            channelsText.setText(getString(R.string.channels_format,
                    formatAllChannels(channels), steerNorm, throttleNorm));
            twistText.setText(getString(R.string.twist_format, twist.linearX, twist.angularZ));
            profileText.setText(getString(R.string.profile_format, profiles[profileIndex].name));
            updateStats();
        });
    }

    private static final int CHANNELS_PER_LOG_LINE = 8;

    /** All 16 raw channels, e.g. for spotting which index moves under a given stick
     * during on-device diagnosis (not just the CH1/CH3 this app currently acts on).
     * Wrapped to {@link #CHANNELS_PER_LOG_LINE} fields per line to fit the screen. */
    private static String formatAllChannels(int[] channels) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < channels.length; i++) {
            if (i > 0) {
                sb.append(i % CHANNELS_PER_LOG_LINE == 0 ? '\n' : ' ');
            }
            sb.append(String.format(Locale.getDefault(), "CH%-2d=%4d", i + 1, channels[i]));
        }
        return sb.toString();
    }

    @Override
    public void onResync() {
        resyncs.incrementAndGet();
        runOnUiThread(this::updateStats);
    }

    @Override
    public void onUnknownFrame(int type, int subId) {
        // Some unknown frames are expected in normal operation (e.g. buttons/telemetry
        // frames not yet added to FrameCatalog), but a *dominant* unknown group can also
        // mean the channel frame we expect just isn't showing up on this firmware/mode
        // (see PROTOCOL.md's "Open question" note) -- tallied and shown live so that's
        // visible on-device without a separate adb capture + tools/analyze_capture.py run.
        int key = ((type & 0xFF) << 8) | (subId & 0xFF);
        unknownFrameCounts.computeIfAbsent(key, k -> new AtomicLong()).incrementAndGet();
        boolean firstTimeSeen = seenUnknownTypes.add(key);
        runOnUiThread(() -> {
            if (firstTimeSeen) {
                appendLog(String.format(Locale.getDefault(),
                        "Nuevo tipo de frame desconocido: type=0x%02x sub_id=0x%02x", type, subId));
            }
            updateStats();
        });
    }

    @Override
    public void onCrcError(int type, int subId) {
        crcErrors.incrementAndGet();
        runOnUiThread(this::updateStats);
    }

    private void updateStats() {
        statsText.setText(getString(R.string.stats_format,
                validFrames.get(), crcErrors.get(), resyncs.get()));
        unknownFramesText.setText(getString(R.string.unknown_frames_format,
                summarizeUnknownFrames()));
    }

    /** Top unknown (type, sub_id) groups by count, e.g. "type=0x0c sub_id=0x3e x121". */
    private String summarizeUnknownFrames() {
        if (unknownFrameCounts.isEmpty()) {
            return getString(R.string.unknown_frames_none);
        }
        return unknownFrameCounts.entrySet().stream()
                .sorted(Comparator.comparingLong((Map.Entry<Integer, AtomicLong> e) -> e.getValue().get()).reversed())
                .limit(MAX_UNKNOWN_GROUPS_SHOWN)
                .map(e -> String.format("type=0x%02x sub_id=0x%02x x%d",
                        (e.getKey() >> 8) & 0xFF, e.getKey() & 0xFF, e.getValue().get()))
                .collect(Collectors.joining("\n"));
    }

    // ---- SIYI Datalink SDK (ttyHS0) one-shot diagnostic ---------------------------------

    /**
     * Opens {@link SdkSerialLink#DEVICE_PATH}, sends one pre-encoded SDK request, and logs
     * whatever comes back (or times out after {@link #SDK_TEST_TIMEOUT_MS}). Shared by both
     * on-screen SDK test buttons ({@link FirmwareVersion}, {@link ChannelData}) — same
     * open/send/watchdog dance either way, only the request bytes and log label differ.
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
        sdkTestButton.setEnabled(false);
        sdkChannelsTestButton.setEnabled(false);
        appendLog("[SDK] Abriendo " + SdkSerialLink.DEVICE_PATH + "...");

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
                runOnUiThread(() -> appendLog("[SDK] resync"));
            }

            @Override
            public void onCrcError(int cmdId) {
                runOnUiThread(() -> appendLog(
                        "[SDK] crc_error cmdId=0x" + Integer.toHexString(cmdId)));
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
                runOnUiThread(() -> appendLog("[SDK] Petición " + requestLabel + " enviada, esperando..."));
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
                    runOnUiThread(() -> appendLog("[SDK] Error: " + e.getMessage()));
                }
            } finally {
                watchdog.interrupt();
                try {
                    link.close();
                } catch (IOException ignored) {
                }
                SdkFrame frame = received.get();
                runOnUiThread(() -> {
                    if (frame != null) {
                        appendLog("[SDK] " + describeSdkFrame(frame));
                    } else if (timedOut.get()) {
                        appendLog("[SDK] Sin respuesta en " + SDK_TEST_TIMEOUT_MS + "ms");
                    }
                    sdkTestButton.setEnabled(true);
                    sdkChannelsTestButton.setEnabled(true);
                    sdkTestInProgress.set(false);
                });
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
        if (frame.cmdId == ChannelData.CMD_ID) {
            try {
                return ChannelData.decode(frame).toString();
            } catch (IllegalArgumentException e) {
                return "Frame 0x42 con payload inesperado: " + frame;
            }
        }
        return "Frame recibido: " + frame;
    }

    /** Appends one line to the on-screen event log. Must be called on the UI thread. */
    private void appendLog(String line) {
        logLines.addLast(logTimeFormat.format(new Date()) + "  " + line);
        while (logLines.size() > MAX_LOG_LINES) {
            logLines.removeFirst();
        }
        logText.setText(String.join("\n", logLines));
        logScrollView.post(() -> logScrollView.fullScroll(View.FOCUS_DOWN));
    }
}
