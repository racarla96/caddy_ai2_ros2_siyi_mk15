package com.caddyai2.siyimk15teleop;

import android.os.Bundle;
import android.widget.EditText;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.caddyai2.siyimk15teleop.config.TeleopConfig;

/**
 * Edits the {@link TeleopConfig} values in place (SharedPreferences-backed, no
 * rebuild needed). Plain "load into EditTexts, validate, write back on save" —
 * no view-model, this is a single-screen settings form for a field-testing tool,
 * not an app with a back stack to preserve state across.
 *
 * <p>Changes only take effect once this activity finishes and {@code MainActivity}
 * resumes: {@code onStart()} there rebuilds the kinematics computer and the DDS
 * publisher from the current {@link TeleopConfig} every time, so returning here
 * (even via back) always picks up whatever was last saved.
 */
public class SettingsActivity extends AppCompatActivity {

    private TeleopConfig config;

    private EditText wheelbaseInput;
    private EditText maxSteerInput;
    private EditText maxSpeedInput;
    private EditText topicNameInput;
    private EditText domainIdInput;
    private EditText publishRateInput;
    private EditText staleTimeoutInput;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_settings);
        config = new TeleopConfig(this);

        wheelbaseInput = findViewById(R.id.wheelbaseInput);
        maxSteerInput = findViewById(R.id.maxSteerInput);
        maxSpeedInput = findViewById(R.id.maxSpeedInput);
        topicNameInput = findViewById(R.id.topicNameInput);
        domainIdInput = findViewById(R.id.domainIdInput);
        publishRateInput = findViewById(R.id.publishRateInput);
        staleTimeoutInput = findViewById(R.id.staleTimeoutInput);

        loadCurrentValues();

        findViewById(R.id.saveButton).setOnClickListener(v -> save());
        findViewById(R.id.cancelButton).setOnClickListener(v -> finish());
        findViewById(R.id.restoreDefaultsButton).setOnClickListener(v -> restoreDefaults());
    }

    private void loadCurrentValues() {
        wheelbaseInput.setText(String.valueOf(config.getWheelbaseMeters()));
        maxSteerInput.setText(String.valueOf(config.getMaxSteerAngleDeg()));
        maxSpeedInput.setText(String.valueOf(config.getMaxSpeedMps()));
        topicNameInput.setText(config.getTopicName());
        domainIdInput.setText(String.valueOf(config.getDomainId()));
        publishRateInput.setText(String.valueOf(config.getPublishRateHz()));
        staleTimeoutInput.setText(String.valueOf(config.getLocalStaleTimeoutMs()));
    }

    private void restoreDefaults() {
        wheelbaseInput.setText(String.valueOf(TeleopConfig.DEFAULT_WHEELBASE_M));
        maxSteerInput.setText(String.valueOf(TeleopConfig.DEFAULT_MAX_STEER_DEG));
        maxSpeedInput.setText(String.valueOf(TeleopConfig.DEFAULT_MAX_SPEED_MPS));
        topicNameInput.setText(TeleopConfig.DEFAULT_TOPIC_NAME);
        domainIdInput.setText(String.valueOf(TeleopConfig.DEFAULT_DOMAIN_ID));
        publishRateInput.setText(String.valueOf(TeleopConfig.DEFAULT_PUBLISH_RATE_HZ));
        staleTimeoutInput.setText(String.valueOf(TeleopConfig.DEFAULT_LOCAL_STALE_TIMEOUT_MS));
        Toast.makeText(this, R.string.settings_defaults_loaded, Toast.LENGTH_SHORT).show();
    }

    /** Parses + range-checks every field before writing any of them, so a save never half-applies. */
    private void save() {
        try {
            double wheelbase = requirePositive(parseDouble(wheelbaseInput), "wheelbase");
            double maxSteerDeg = requireInRange(parseDouble(maxSteerInput), 1, 90, "max_steer_deg");
            double maxSpeed = requirePositive(parseDouble(maxSpeedInput), "max_speed");
            String topic = requireNonBlank(topicNameInput.getText().toString(), "topic");
            int domainId = requireNonNegative(parseInt(domainIdInput), "domain_id");
            int publishRate = (int) requirePositive(parseInt(publishRateInput), "publish_rate_hz");
            long staleTimeout = (long) requireNonNegative(parseLong(staleTimeoutInput), "stale_timeout_ms");

            config.setWheelbaseMeters(wheelbase);
            config.setMaxSteerAngleDeg(maxSteerDeg);
            config.setMaxSpeedMps(maxSpeed);
            config.setTopicName(topic);
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
