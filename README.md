# caddy_ai2_ros2_siyi_mk15

An Android app that turns a SIYI MK15 handset into a ROS 2 teleop joystick for
the `caddy_ai2` platform: it reads the handset's own internal joystick
protocol straight off its serial port, converts steer/throttle into a
bicycle-model `geometry_msgs/Twist`, and publishes it over DDS on the WiFi
link the MK15 already uses for video/telemetry — no ROS 2 install, no
`rclcpp`, no companion computer needed on the handset side.

```
/dev/ttyHS1  →  SiyiSerialReader  →  FrameParser  →  ChannelMapper  →  BicycleTwistComputer  →  TwistCmdVelPublisher
   (UART)         (raw bytes)      (validated frames)  (normalized       (geometry_msgs/Twist)      (DDS, WiFi)
                                                          steer/throttle)
```

See [PROTOCOL.md](PROTOCOL.md) for the reverse-engineered wire format this
reads, and the class Javadocs under
`android/app/src/main/java/com/caddyai2/siyimk15teleop/` for how each stage
works.

## Why this exists

The MK15 ships as a video/telemetry ground station, not a joystick — there's
no vendor-supported way to get its physical stick positions onto a ROS 2
topic. This app runs *on the handset itself* (it's just another Android app,
sideloaded), reads the same internal UART the handset's own firmware uses for
its sticks, and speaks DDS directly over the existing WiFi link to the robot.
The robot-side consumer (`bicycle_cmd_relay` in the sibling
`caddy_ai2_ros2_controllers` repo) is out of scope here except where its
behavior constrains what this app can safely send it — see the known
limitation in PROTOCOL.md.

## Project layout

- `android/` — the Android Studio / Gradle project (module `app`, package
  `com.caddyai2.siyimk15teleop`).
  - `protocol/` — framing, CRC, frame catalog, channel decoding. Plain Java,
    unit-tested on the JVM (no emulator needed).
  - `kinematics/` — `BicycleTwistComputer`: normalized steer/throttle →
    `Twist`.
  - `serial/` — `SiyiSerialReader`: owns the read thread on `/dev/ttyHS1`.
  - `ros2/` — `TwistCmdVelPublisher`: owns the DDS node/publisher and
    republishes at a fixed rate independent of input frame rate.
  - `config/` — `TeleopConfig`: user-adjustable vehicle/transport parameters,
    persisted in `SharedPreferences`.
  - `MainActivity` — wiring + live diagnostics UI (raw channel values,
    computed `Twist`, frame/error counters, an on-screen scrolling event
    log) only; no protocol/math logic of its own.
  - `SettingsActivity` — in-app form for every `TeleopConfig` field.
- `PROTOCOL.md` — the reverse-engineered SIYI MK15 serial protocol.
- `MK15_User_Manual_v1_9_*.pdf`, `UniGCS_prod_mk15_*.apk` — vendor reference
  material kept alongside the repo for reverse-engineering reference; not
  part of the build (untracked — see `.gitignore`).

## Building

```
cd android
./gradlew assembleDebug   # -> app/build/outputs/apk/debug/app-debug.apk
./gradlew testDebugUnitTest   # protocol/kinematics unit tests, plain JVM
```

Requires JDK 17+ and the Android SDK (`local.properties` points at it via
`sdk.dir`, machine-specific — not committed). `jros2-android` (the pure-Java
ROS 2 DDS client this app publishes with) is hosted on IHMC's own Maven
repo, already wired into `settings.gradle.kts`.

Install on the handset:

```
adb install -r android/app/build/outputs/apk/debug/app-debug.apk
```

## Runtime requirements

- Must run **on the MK15 handset** (Android 9 / API 28) — it reads the
  handset's own `/dev/ttyHS1`, not a remote device.
- The handset and the robot's ROS 2 stack must be on the same WiFi network
  and DDS domain (`domain_id`, configurable — see below). Fast-DDS discovery
  (SPDP/SEDP) defaults to multicast, which is why the app holds a
  `WifiManager.MulticastLock` for as long as it's running — without it, many
  Android WiFi stacks silently drop multicast frames and discovery never
  completes.

## Configuration

`TeleopConfig` (SharedPreferences-backed, no rebuild needed to change) —
editable in-app via the "⚙ Ajustes" button on the main screen
(`SettingsActivity`), which validates every field before saving and applies
the new values the moment you return (`MainActivity.onStart()` rebuilds the
kinematics computer and DDS publisher from the current config every time):

| Setting | Default | Meaning |
|---|---|---|
| Wheelbase | 1.65 m | From `caddy_ai2_ros2_controllers`'s `bicycle_to_ackermann_steering_adapter` config — override per-vehicle. |
| Max steer angle | 22.9° (~0.4 rad) | Same source. |
| Max speed | 1.5 m/s | Same source. |
| Topic name | `/siyi_mk15/cmd_vel_raw` | Where the `Twist` is published. |
| DDS domain ID | 0 | Must match the robot's `ROS_DOMAIN_ID`. |
| Publish rate | 50 Hz | Fixed-rate heartbeat, independent of input frame rate. |
| Local stale timeout | 300 ms | If no valid channel frame has updated the command within this window, the app zeros the outgoing `Twist` (protects against a stuck/disconnected serial link — see caveat below). |

The local stale timeout is a *local* safety net only — it covers the
handset's own serial link going stale, not DDS/WiFi packet loss between the
handset and the robot. A cross-network command-loss watchdog has to live on
the robot side, since this process has no way to observe its own messages
being dropped in flight.

## Status / known limitations

- All protocol/kinematics unit tests pass (`./gradlew testDebugUnitTest`);
  `assembleDebug` produces an installable APK. On-device validation against
  real `/dev/ttyHS1` traffic and the actual DDS link is the remaining
  real-hardware step.
- `SiyiSerialReader` opens `/dev/ttyHS1` with a plain `FileInputStream`,
  relying on the vendor's own `biz.siyi.remotecontrol` service having
  already configured the UART (230400 8N1 — see PROTOCOL.md's Transport
  section). If real hardware testing shows garbled bytes at the raw level,
  the fallback is a small JNI `termios`-configuring wrapper — see the
  Javadoc on `SiyiSerialReader.open()`.
- Only the joystick-channel frame type (`0x20/0x01`) is decoded today;
  buttons and extended telemetry frames are parsed (correct length/CRC) but
  ignored. See the frame catalog in PROTOCOL.md to extend this.
- Reverse-steering commands are not recovered correctly by the robot-side
  `bicycle_cmd_relay` — see PROTOCOL.md's "Known limitation" section. This is
  a `bicycle_cmd_relay` fix, not something correctable from this app.
