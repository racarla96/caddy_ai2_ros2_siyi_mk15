package com.caddyai2.siyimk15teleop;

import android.content.Context;
import android.content.Intent;
import android.net.wifi.WifiManager;
import android.os.Bundle;
import android.view.View;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

import com.caddyai2.siyimk15teleop.config.TeleopConfig;
import com.caddyai2.siyimk15teleop.diagnostics.DiagnosticsState;
import com.caddyai2.siyimk15teleop.kinematics.BicycleTwistComputer;
import com.caddyai2.siyimk15teleop.protocol.ChannelMapper;
import com.caddyai2.siyimk15teleop.protocol.DecodedFrame;
import com.caddyai2.siyimk15teleop.ros2.SteeringReferencePublisher;
import com.caddyai2.siyimk15teleop.serial.SiyiSerialReader;
import com.caddyai2.siyimk15teleop.util.FullscreenHelper;

import java.util.Locale;

/**
 * Wires the serial reader -&gt; frame parser -&gt; channel mapper -&gt; bicycle
 * kinematics -&gt; ROS 2 Twist publisher pipeline, and shows the live driving status.
 *
 * All the actual protocol/kinematics logic lives in plain-Java, unit-testable
 * classes; this activity is just wiring + a WiFi multicast lock (required for
 * Fast-DDS SPDP/SEDP discovery on most Android WiFi stacks) + UI updates.
 *
 * <p>Raw protocol diagnostics (frame counters, unknown-frame tally, channel dump, the
 * SIYI Datalink SDK one-shot test, the event log) live in {@link DiagnosticsActivity} instead
 * of on this screen (2026-09-14) — this screen only shows what a field operator needs at a
 * glance: connection/profile status and the live command. Both activities read/write the same
 * {@link DiagnosticsState} singleton.
 *
 * <p>{@link #onStart()} rebuilds both the per-profile {@link #twistComputers} and
 * {@link #cmdVelPublisher} from the current {@link TeleopConfig} every time
 * (not just once in {@code onCreate}) so that returning here from
 * {@link SettingsActivity} — which always stops/restarts this activity —
 * picks up whatever was just saved without needing a full app relaunch.
 */
public class MainActivity extends AppCompatActivity implements SiyiSerialReader.Listener {

    private TeleopConfig config;
    private ChannelMapper channelMapper;
    private final DiagnosticsState diagnostics = DiagnosticsState.getInstance();

    // One computer per TeleopConfig driving profile (index matches ChannelMapper's CH7
    // three-way-switch bucket, see TeleopConfig's Javadoc) — rebuilt from config in onStart(),
    // selected live per-frame in onFrame() by the switch's current position.
    private BicycleTwistComputer[] twistComputers = new BicycleTwistComputer[TeleopConfig.PROFILE_COUNT];
    private TeleopConfig.Profile[] profiles = new TeleopConfig.Profile[TeleopConfig.PROFILE_COUNT];
    private int activeProfileIndex = -1;
    private SteeringReferencePublisher cmdVelPublisher;
    private SiyiSerialReader serialReader;
    private WifiManager.MulticastLock multicastLock;

    private View connectionDot;
    private TextView connectionText;
    private View profileDot;
    private TextView profileText;
    private TextView linearXText;
    private TextView angularZText;

    // Matches the dot colors used in SettingsActivity's profile cards (green/amber/blue for
    // CH7 left/center/right).
    private static final int[] PROFILE_DOT_DRAWABLES = {
            R.drawable.dot_profile_low, R.drawable.dot_profile_mid, R.drawable.dot_profile_high
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        FullscreenHelper.apply(this);
        setContentView(R.layout.activity_main);

        connectionDot = findViewById(R.id.connectionDot);
        connectionText = findViewById(R.id.connectionText);
        profileDot = findViewById(R.id.profileDot);
        profileText = findViewById(R.id.profileText);
        linearXText = findViewById(R.id.linearXText);
        angularZText = findViewById(R.id.angularZText);
        findViewById(R.id.settingsButton).setOnClickListener(
                v -> startActivity(new Intent(this, SettingsActivity.class)));
        findViewById(R.id.diagnosticsButton).setOnClickListener(
                v -> startActivity(new Intent(this, DiagnosticsActivity.class)));

        config = new TeleopConfig(this);
        channelMapper = new ChannelMapper();

        acquireMulticastLock();
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) {
            FullscreenHelper.apply(this);
        }
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
        diagnostics.appendLog(String.format(Locale.getDefault(),
                "Config: topic=%s frame=%s domain=%d rate=%dHz",
                config.getTopicName(), config.getFrameId(), config.getDomainId(),
                config.getPublishRateHz()));
        for (int i = 0; i < TeleopConfig.PROFILE_COUNT; i++) {
            diagnostics.appendLog(String.format(Locale.getDefault(),
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
        diagnostics.setConnectionDetail("Conectado a " + SiyiSerialReader.DEVICE_PATH);
        diagnostics.appendLog("Conectado a " + SiyiSerialReader.DEVICE_PATH);
        runOnUiThread(() -> {
            connectionText.setText(R.string.status_connected_short);
            tintDot(connectionDot, R.color.status_connected);
        });
    }

    @Override
    public void onDisconnected(Throwable error) {
        String detail = "Desconectado" + (error == null ? "" : ": " + error);
        diagnostics.setConnectionDetail(detail);
        diagnostics.appendLog(detail);
        runOnUiThread(() -> {
            connectionText.setText(error == null
                    ? getString(R.string.status_disconnected_short)
                    : getString(R.string.status_error_short));
            tintDot(connectionDot, error == null ? R.color.status_disconnected : R.color.status_error);
        });
    }

    /** Tints a dot indicator's drawable background. Must be called on the UI thread. */
    private void tintDot(View dot, int colorRes) {
        dot.getBackground().mutate().setTint(getColor(colorRes));
    }

    @Override
    public void onFrame(DecodedFrame frame) {
        if (!frame.isChannelFrame()) {
            return; // buttons/telemetry frames: not decoded yet, see FrameCatalog.
        }
        diagnostics.recordValidFrame();

        int[] channels = frame.decodeChannels();
        double steerNorm = channelMapper.normalizeSteer(channels);
        double throttleNorm = channelMapper.normalizeThrottle(channels);
        diagnostics.setRawChannelsText(formatAllChannels(channels)
                + String.format(Locale.getDefault(),
                "\nnorm: steer(CH1)=%.2f  throttle(CH3)=%.2f", steerNorm, throttleNorm));

        int profileIndex = channelMapper.profileSwitchPosition(channels);
        if (profileIndex != activeProfileIndex) {
            activeProfileIndex = profileIndex;
            TeleopConfig.Profile p = profiles[profileIndex];
            diagnostics.appendLog(String.format(Locale.getDefault(),
                    "Perfil activo: %s (maxSteer=%.1f° maxSpeed=%.2fm/s, CH7)",
                    p.name, p.maxSteerAngleDeg, p.maxSpeedMps));
        }
        BicycleTwistComputer.Twist twist = twistComputers[profileIndex].compute(steerNorm, throttleNorm);
        cmdVelPublisher.updateCommand(twist.linearX, twist.angularZ);

        runOnUiThread(() -> {
            linearXText.setText(getString(R.string.value_mps, twist.linearX));
            angularZText.setText(getString(R.string.value_radps, twist.angularZ));
            profileText.setText(profiles[profileIndex].name);
            profileDot.setBackgroundResource(PROFILE_DOT_DRAWABLES[profileIndex]);
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
        diagnostics.recordResync();
    }

    @Override
    public void onUnknownFrame(int type, int subId) {
        // Some unknown frames are expected in normal operation (e.g. buttons/telemetry
        // frames not yet added to FrameCatalog), but a *dominant* unknown group can also
        // mean the channel frame we expect just isn't showing up on this firmware/mode
        // (see PROTOCOL.md's "Open question" note) -- tallied and shown in DiagnosticsActivity
        // so that's visible on-device without a separate adb capture + tools/analyze_capture.py.
        boolean firstTimeSeen = diagnostics.recordUnknownFrame(type, subId);
        if (firstTimeSeen) {
            diagnostics.appendLog(String.format(Locale.getDefault(),
                    "Nuevo tipo de frame desconocido: type=0x%02x sub_id=0x%02x", type, subId));
        }
    }

    @Override
    public void onCrcError(int type, int subId) {
        diagnostics.recordCrcError();
    }
}
