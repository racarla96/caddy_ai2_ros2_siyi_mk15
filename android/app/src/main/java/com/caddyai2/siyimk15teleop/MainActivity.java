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

import java.util.concurrent.atomic.AtomicLong;

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

    private final AtomicLong validFrames = new AtomicLong();
    private final AtomicLong crcErrors = new AtomicLong();
    private final AtomicLong resyncs = new AtomicLong();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        statusText = findViewById(R.id.statusText);
        channelsText = findViewById(R.id.channelsText);
        twistText = findViewById(R.id.twistText);
        statsText = findViewById(R.id.statsText);

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
        // Expected in normal operation (buttons/telemetry frames); no counter needed.
    }

    @Override
    public void onCrcError(int type, int subId) {
        crcErrors.incrementAndGet();
        runOnUiThread(this::updateStats);
    }

    private void updateStats() {
        statsText.setText(getString(R.string.stats_format,
                validFrames.get(), crcErrors.get(), resyncs.get()));
    }
}
