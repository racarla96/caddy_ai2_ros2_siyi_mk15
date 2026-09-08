package com.caddyai2.siyimk15teleop;

import android.content.Context;
import android.net.wifi.WifiManager;
import android.os.Bundle;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

import com.caddyai2.siyimk15teleop.config.TeleopConfig;
import com.caddyai2.siyimk15teleop.kinematics.BicycleTwistComputer;
import com.caddyai2.siyimk15teleop.protocol.ChannelMapper;
import com.caddyai2.siyimk15teleop.protocol.DecodedFrame;
import com.caddyai2.siyimk15teleop.ros2.TwistCmdVelPublisher;
import com.caddyai2.siyimk15teleop.serial.SiyiSerialReader;

import java.util.Comparator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

/**
 * Wires the serial reader -&gt; frame parser -&gt; channel mapper -&gt; bicycle
 * kinematics -&gt; ROS 2 Twist publisher pipeline, and shows live diagnostics.
 *
 * All the actual protocol/kinematics logic lives in plain-Java, unit-testable
 * classes; this activity is just wiring + a WiFi multicast lock (required for
 * Fast-DDS SPDP/SEDP discovery on most Android WiFi stacks) + UI updates.
 */
public class MainActivity extends AppCompatActivity implements SiyiSerialReader.Listener {

    private TeleopConfig config;
    private ChannelMapper channelMapper;
    private BicycleTwistComputer twistComputer;
    private TwistCmdVelPublisher cmdVelPublisher;
    private SiyiSerialReader serialReader;
    private WifiManager.MulticastLock multicastLock;

    private TextView statusText;
    private TextView channelsText;
    private TextView twistText;
    private TextView statsText;
    private TextView unknownFramesText;

    private final AtomicLong validFrames = new AtomicLong();
    private final AtomicLong crcErrors = new AtomicLong();
    private final AtomicLong resyncs = new AtomicLong();

    // (type, sub_id) -> count, for frames whose (type, sub_id) isn't in FrameCatalog.
    // Surfaced live in the UI so an unexpected wire format (like the 0x0c/0x3e frames
    // seen in the 2026-09-08 on-device capture) is visible without an adb pull —
    // see PROTOCOL.md's "Open question" note and tools/analyze_capture.py.
    private static final int MAX_UNKNOWN_GROUPS_SHOWN = 5;
    private final Map<Integer, AtomicLong> unknownFrameCounts = new ConcurrentHashMap<>();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        statusText = findViewById(R.id.statusText);
        channelsText = findViewById(R.id.channelsText);
        twistText = findViewById(R.id.twistText);
        statsText = findViewById(R.id.statsText);
        unknownFramesText = findViewById(R.id.unknownFramesText);

        config = new TeleopConfig(this);
        channelMapper = new ChannelMapper();
        rebuildTwistComputer();

        cmdVelPublisher = new TwistCmdVelPublisher(
                config.getTopicName(),
                config.getDomainId(),
                config.getPublishRateHz(),
                config.getLocalStaleTimeoutMs());

        acquireMulticastLock();
    }

    private void rebuildTwistComputer() {
        twistComputer = new BicycleTwistComputer(
                config.getWheelbaseMeters(),
                config.getMaxSteerAngleRad(),
                config.getMaxSpeedMps());
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
        cmdVelPublisher.start();
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
        cmdVelPublisher.stop();
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
        runOnUiThread(() -> statusText.setText(R.string.status_connected));
    }

    @Override
    public void onDisconnected(Throwable error) {
        runOnUiThread(() -> statusText.setText(
                error == null ? getString(R.string.status_disconnected)
                        : getString(R.string.status_error, error.getMessage())));
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
        BicycleTwistComputer.Twist twist = twistComputer.compute(steerNorm, throttleNorm);
        cmdVelPublisher.updateCommand(twist.linearX, twist.angularZ);

        runOnUiThread(() -> {
            channelsText.setText(getString(R.string.channels_format,
                    channels[ChannelMapper.CHANNEL_INDEX_STEER],
                    channels[ChannelMapper.CHANNEL_INDEX_THROTTLE],
                    steerNorm, throttleNorm));
            twistText.setText(getString(R.string.twist_format, twist.linearX, twist.angularZ));
            updateStats();
        });
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
        runOnUiThread(this::updateStats);
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
}
