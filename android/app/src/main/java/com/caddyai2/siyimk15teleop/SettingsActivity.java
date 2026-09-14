package com.caddyai2.siyimk15teleop;

import android.os.Bundle;
import android.widget.EditText;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.caddyai2.siyimk15teleop.config.TeleopConfig;
import com.caddyai2.siyimk15teleop.util.FullscreenHelper;

/**
 * Edits the {@link TeleopConfig} values in place (SharedPreferences-backed, no
 * rebuild needed). Plain "load into EditTexts, validate, write back on save" —
 * no view-model, this is a single-screen settings form for a field-testing tool,
 * not an app with a back stack to preserve state across.
 *
 * <p>Three driving-profile cards (index matches {@code ChannelMapper.CHANNEL_INDEX_PROFILE_SWITCH}'s
 * CH7 three-way-switch bucket — low/mid/high) replace the old single wheelbase/max-steer/max-speed
 * block (2026-09-14): wheelbase is gone entirely (see {@code BicycleTwistComputer}'s Javadoc for
 * why), and max steer/max speed are now one pair per switch position, synced together, instead
 * of one global pair.
 *
 * <p>Changes only take effect once this activity finishes and {@code MainActivity}
 * resumes: {@code onStart()} there rebuilds the kinematics computers and the DDS
 * publisher from the current {@link TeleopConfig} every time, so returning here
 * (even via back) always picks up whatever was last saved.
 */
public class SettingsActivity extends AppCompatActivity {

    private TeleopConfig config;

    private final EditText[] profileNameInputs = new EditText[TeleopConfig.PROFILE_COUNT];
    private final EditText[] profileMaxSteerInputs = new EditText[TeleopConfig.PROFILE_COUNT];
    private final EditText[] profileMaxSpeedInputs = new EditText[TeleopConfig.PROFILE_COUNT];

    private EditText topicNameInput;
    private EditText frameIdInput;
    private EditText domainIdInput;
    private EditText publishRateInput;
    private EditText staleTimeoutInput;

    // Per-profile view IDs, indexed 0..PROFILE_COUNT-1 — see activity_settings.xml
    // (profile0NameInput, profile1NameInput, profile2NameInput, ...).
    private static final int[] PROFILE_NAME_IDS = {
            R.id.profile0NameInput, R.id.profile1NameInput, R.id.profile2NameInput
    };
    private static final int[] PROFILE_MAX_STEER_IDS = {
            R.id.profile0MaxSteerInput, R.id.profile1MaxSteerInput, R.id.profile2MaxSteerInput
    };
    private static final int[] PROFILE_MAX_SPEED_IDS = {
            R.id.profile0MaxSpeedInput, R.id.profile1MaxSpeedInput, R.id.profile2MaxSpeedInput
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        FullscreenHelper.apply(this);
        setContentView(R.layout.activity_settings);
        config = new TeleopConfig(this);

        for (int i = 0; i < TeleopConfig.PROFILE_COUNT; i++) {
            profileNameInputs[i] = findViewById(PROFILE_NAME_IDS[i]);
            profileMaxSteerInputs[i] = findViewById(PROFILE_MAX_STEER_IDS[i]);
            profileMaxSpeedInputs[i] = findViewById(PROFILE_MAX_SPEED_IDS[i]);
        }
        topicNameInput = findViewById(R.id.topicNameInput);
        frameIdInput = findViewById(R.id.frameIdInput);
        domainIdInput = findViewById(R.id.domainIdInput);
        publishRateInput = findViewById(R.id.publishRateInput);
        staleTimeoutInput = findViewById(R.id.staleTimeoutInput);

        loadCurrentValues();

        findViewById(R.id.saveButton).setOnClickListener(v -> save());
        findViewById(R.id.cancelButton).setOnClickListener(v -> finish());
        findViewById(R.id.restoreDefaultsButton).setOnClickListener(v -> restoreDefaults());
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) {
            FullscreenHelper.apply(this);
        }
    }

    private void loadCurrentValues() {
        for (int i = 0; i < TeleopConfig.PROFILE_COUNT; i++) {
            TeleopConfig.Profile p = config.getProfile(i);
            profileNameInputs[i].setText(p.name);
            profileMaxSteerInputs[i].setText(String.valueOf(p.maxSteerAngleDeg));
            profileMaxSpeedInputs[i].setText(String.valueOf(p.maxSpeedMps));
        }
        topicNameInput.setText(config.getTopicName());
        frameIdInput.setText(config.getFrameId());
        domainIdInput.setText(String.valueOf(config.getDomainId()));
        publishRateInput.setText(String.valueOf(config.getPublishRateHz()));
        staleTimeoutInput.setText(String.valueOf(config.getLocalStaleTimeoutMs()));
    }

    private void restoreDefaults() {
        for (int i = 0; i < TeleopConfig.PROFILE_COUNT; i++) {
            profileNameInputs[i].setText(TeleopConfig.DEFAULT_PROFILE_NAMES[i]);
            profileMaxSteerInputs[i].setText(String.valueOf(TeleopConfig.DEFAULT_PROFILE_MAX_STEER_DEG[i]));
            profileMaxSpeedInputs[i].setText(String.valueOf(TeleopConfig.DEFAULT_PROFILE_MAX_SPEED_MPS[i]));
        }
        topicNameInput.setText(TeleopConfig.DEFAULT_TOPIC_NAME);
        frameIdInput.setText(TeleopConfig.DEFAULT_FRAME_ID);
        domainIdInput.setText(String.valueOf(TeleopConfig.DEFAULT_DOMAIN_ID));
        publishRateInput.setText(String.valueOf(TeleopConfig.DEFAULT_PUBLISH_RATE_HZ));
        staleTimeoutInput.setText(String.valueOf(TeleopConfig.DEFAULT_LOCAL_STALE_TIMEOUT_MS));
        Toast.makeText(this, R.string.settings_defaults_loaded, Toast.LENGTH_SHORT).show();
    }

    /** Parses + range-checks every field before writing any of them, so a save never half-applies. */
    private void save() {
        try {
            String[] names = new String[TeleopConfig.PROFILE_COUNT];
            double[] maxSteerDegs = new double[TeleopConfig.PROFILE_COUNT];
            double[] maxSpeeds = new double[TeleopConfig.PROFILE_COUNT];
            for (int i = 0; i < TeleopConfig.PROFILE_COUNT; i++) {
                names[i] = requireNonBlank(profileNameInputs[i].getText().toString(), "perfil " + (i + 1) + " nombre");
                maxSteerDegs[i] = requireInRange(parseDouble(profileMaxSteerInputs[i]), 1, 90,
                        "perfil " + (i + 1) + " max_steer_deg");
                maxSpeeds[i] = requirePositive(parseDouble(profileMaxSpeedInputs[i]), "perfil " + (i + 1) + " max_speed");
            }
            String topic = requireNonBlank(topicNameInput.getText().toString(), "topic");
            String frameId = requireNonBlank(frameIdInput.getText().toString(), "frame_id");
            int domainId = requireNonNegative(parseInt(domainIdInput), "domain_id");
            int publishRate = (int) requirePositive(parseInt(publishRateInput), "publish_rate_hz");
            long staleTimeout = (long) requireNonNegative(parseLong(staleTimeoutInput), "stale_timeout_ms");

            for (int i = 0; i < TeleopConfig.PROFILE_COUNT; i++) {
                config.setProfile(i, names[i], maxSteerDegs[i], maxSpeeds[i]);
            }
            config.setTopicName(topic);
            config.setFrameId(frameId);
            config.setDomainId(domainId);
            config.setPublishRateHz(publishRate);
            config.setLocalStaleTimeoutMs(staleTimeout);

            Toast.makeText(this, R.string.settings_saved, Toast.LENGTH_SHORT).show();
            finish();
        } catch (IllegalArgumentException e) {
            Toast.makeText(this, e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private static double parseDouble(EditText field) {
        try {
            return Double.parseDouble(field.getText().toString().trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Valor no numérico: " + field.getText());
        }
    }

    private static int parseInt(EditText field) {
        try {
            return Integer.parseInt(field.getText().toString().trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Valor entero inválido: " + field.getText());
        }
    }

    private static long parseLong(EditText field) {
        try {
            return Long.parseLong(field.getText().toString().trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Valor entero inválido: " + field.getText());
        }
    }

    private static double requirePositive(double value, String name) {
        if (value <= 0) {
            throw new IllegalArgumentException(name + " debe ser > 0");
        }
        return value;
    }

    private static int requireNonNegative(int value, String name) {
        if (value < 0) {
            throw new IllegalArgumentException(name + " debe ser >= 0");
        }
        return value;
    }

    private static double requireNonNegative(long value, String name) {
        if (value < 0) {
            throw new IllegalArgumentException(name + " debe ser >= 0");
        }
        return value;
    }

    private static double requireInRange(double value, double min, double max, String name) {
        if (value < min || value > max) {
            throw new IllegalArgumentException(name + " debe estar entre " + min + " y " + max);
        }
        return value;
    }

    private static String requireNonBlank(String value, String name) {
        String trimmed = value.trim();
        if (trimmed.isEmpty()) {
            throw new IllegalArgumentException(name + " no puede estar vacío");
        }
        return trimmed;
    }
}
