# caddy_ai2_ros2_siyi_mk15

An Android app that turns a SIYI MK15 handset into a ROS 2 teleop joystick for
the `caddy_ai2` platform: it reads the handset's own internal joystick
protocol straight off its serial port, converts steer/throttle into a
bicycle-model steering reference, and publishes it over DDS on the WiFi
link the MK15 already uses for video/telemetry — no ROS 2 install, no
`rclcpp`, no companion computer needed on the handset side.

```
/dev/ttyHS1  →  SiyiSerialReader  →  FrameParser  →  ChannelMapper  →  BicycleTwistComputer  →  SteeringReferencePublisher
   (UART)         (raw bytes)      (validated frames)  (normalized       (geometry_msgs/TwistStamped)      (DDS, WiFi)
                                                          steer/throttle)
```

See [PROTOCOL.md](PROTOCOL.md) for the reverse-engineered wire format this
reads, and the class Javadocs under
`android/app/src/main/java/com/caddyai2/siyimk15teleop/` for how each stage
works. The handset actually exposes two unrelated serial interfaces,
`/dev/ttyHS0` and `/dev/ttyHS1` — see PROTOCOL.md's "`/dev/ttyHS0` vs
`/dev/ttyHS1`" table for which is which and why this app uses `ttyHS1`.

## Why this exists

The MK15 ships as a video/telemetry ground station, not a joystick — there's
no vendor-supported way to get its physical stick positions onto a ROS 2
topic. This app runs *on the handset itself* (it's just another Android app,
sideloaded), reads the same internal UART the handset's own firmware uses for
its sticks, and speaks DDS directly over the existing WiFi link to the robot.

**Robot-side target (changed 2026-09-14)**: this app publishes directly to a
`ros2_controllers` [`steering_controllers_library`](https://control.ros.org/rolling/doc/ros2_controllers/steering_controllers_library/doc/userdoc.html)
controller's `<controller_name>/reference` topic (`geometry_msgs/TwistStamped`),
**not** the sibling `caddy_ai2_ros2_controllers` repo's `bicycle_cmd_relay`
anymore — see `BicycleTwistComputer`'s Javadoc for why (that controller's
`atan`-based steering-angle recovery both allows commanding the steering
joint while stationary, via a small "creep" reference speed, and — as a
side effect — fixes the reverse-steering sign bug `bicycle_cmd_relay`'s own
`atan2`-based recovery had). `TeleopConfig`'s `topic_name`/`frame_id` must
be set to match the real controller instance on the robot.

## Project layout

- `android/` — the Android Studio / Gradle project (module `app`, package
  `com.caddyai2.siyimk15teleop`).
  - `protocol/` — framing, CRC, frame catalog, channel decoding. Plain Java,
    unit-tested on the JVM (no emulator needed).
  - `kinematics/` — `BicycleTwistComputer`: normalized steer/throttle →
    `Twist`.
  - `serial/` — `SiyiSerialReader`: owns the read thread on `/dev/ttyHS1`.
  - `ros2/` — `SteeringReferencePublisher`: owns the DDS node/publisher and
    republishes `TwistStamped` at a fixed rate independent of input frame rate.
  - `config/` — `TeleopConfig`: user-adjustable vehicle/transport parameters,
    persisted in `SharedPreferences`.
  - `MainActivity` — wiring + live diagnostics UI (raw channel values,
    computed `Twist`, frame/error counters, an on-screen scrolling event
    log) only; no protocol/math logic of its own.
  - `SettingsActivity` — in-app form for every `TeleopConfig` field.
- `PROTOCOL.md` — the reverse-engineered SIYI MK15 serial protocol.
- `SDK_COMMANDS.md` — full command-by-command catalog of the official SIYI
  Datalink SDK protocol (manual section 4.8), with real on-device test
  results for every read command.
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
- **Depends on the vendor app's background service, but no longer on its
  UI** (confirmed on real hardware, 2026-09-10 through 2026-09-14):
  1. `biz.siyi.remotecontrol`'s `:rcuservice` background process must be
     running — `/dev/ttyHS1` sits idle at 9600 baud and carries nothing at
     all otherwise (this is the vendor service that reconfigures it to
     230400 8N1). It auto-starts at boot on its own; nothing to do here.
  2. ~~Someone has to open that app's own channel-data screen at least
     once~~ — **no longer true**. This app now sends the channel-stream
     "start" trigger itself on every connect (`SiyiSerialReader`/
     `ChannelStreamControl`, confirmed working cold-boot-to-streaming on
     real hardware with the vendor app's UI never touched — see
     PROTOCOL.md's 2026-09-14 update). Streaming, once started, keeps going
     indefinitely even if this app is later killed/restarted.

## Configuration

`TeleopConfig` (SharedPreferences-backed, no rebuild needed to change) —
editable in-app via the "⚙ Ajustes" button on the main screen
(`SettingsActivity`), which validates every field before saving and applies
the new values the moment you return (`MainActivity.onStart()` rebuilds the
kinematics computers and DDS publisher from the current config every time).

**Driving profiles (2026-09-14)**: wheelbase is no longer a setting at all
(see `BicycleTwistComputer`'s Javadoc for why) — instead, max steer angle and
max speed are grouped into **3 profiles**, selected live by the MK15's CH7
three-position switch (one of its 3-position switches, see
`ChannelMapper.CHANNEL_INDEX_PROFILE_SWITCH`). One switch position picks
both values together, synced, for a given scenario:

| Profile (CH7 position) | Default name | Default max steer | Default max speed |
|---|---|---|---|
| 0 (low) | Maniobra | 22.9° | 0.5 m/s |
| 1 (mid) | Normal | 22.9° | 1.5 m/s |
| 2 (high) | Transporte | 15.0° | 2.5 m/s |

All 6 numbers (and the 3 names) are independently editable per-vehicle in
Settings — the defaults above are starting points, not measured/required
values. The active profile is shown live on the main screen and logged on
every switch transition.

| Other setting | Default | Meaning |
|---|---|---|
| Topic name | `/bicycle_steering_controller/reference` | The target `steering_controllers_library` controller's reference topic — **must be set to match the real controller instance name on the robot**, this default is a placeholder. |
| Frame ID | `base_link` | `header.frame_id` on the published `TwistStamped`. |
| DDS domain ID | 0 | Must match the robot's `ROS_DOMAIN_ID`. |
| Publish rate | 50 Hz | Fixed-rate heartbeat, independent of input frame rate. |
| Local stale timeout | 300 ms | If no valid channel frame has updated the command within this window, the app zeros the outgoing reference (protects against a stuck/disconnected serial link — see caveat below). |

The local stale timeout is a *local* safety net only — it covers the
handset's own serial link going stale, not DDS/WiFi packet loss between the
handset and the robot. A cross-network command-loss watchdog has to live on
the robot side, since this process has no way to observe its own messages
being dropped in flight.

## Status / known limitations

- **Full on-device protocol validation done (2026-09-10/11)**: ran the real
  app on the real MK15 — `SiyiSerialReader` → `FrameParser` (with the header
  fix below) → `ChannelMapper` → `BicycleTwistComputer` →
  the DDS publisher processed **2287 real hardware frames, 0 CRC
  errors, 0 resyncs** in a single session.
- **App now self-triggers channel streaming from a cold boot (2026-09-14),
  confirmed on real hardware**: no longer depends on a human having opened
  the vendor app's channel-data screen. Full power-cycle → install → launch
  → app alone reached **2676 valid frames, 0 CRC errors, 0 resyncs**,
  `Twist` computed correctly, vendor app UI never touched. See PROTOCOL.md's
  2026-09-14 update for the bug that blocked this (the write-direction
  "counter" field isn't an opaque nonce — only certain values are accepted)
  and the fix. Once started, streaming keeps going regardless of what's in
  the Android foreground afterward. The one remaining real-hardware
  milestone for the whole project is confirming the DDS reference reaches
  the companion ROS 2 robot stack over WiFi — everything upstream of that is
  now validated end-to-end, app-only, from a cold boot.
- **Switched the robot-side target from `bicycle_cmd_relay` to
  `steering_controllers_library` directly (2026-09-14, not yet validated on
  real hardware/robot)**: `SteeringReferencePublisher` now publishes
  `geometry_msgs/TwistStamped` to a `<controller_name>/reference` topic
  instead of a plain `Twist` to `bicycle_cmd_relay`. `BicycleTwistComputer`
  was reworked to match that controller's `atan`-based (not `atan2`-based)
  steering-angle recovery — see its Javadoc. This both enables positioning
  the steering joint while stationary (via a small constant "creep"
  reference speed, since the message format has no way to say "point the
  wheel, don't move") and fixes the reverse-steering sign bug below as a
  side effect. Built and unit-tested only so far — needs on-device
  validation against a real `steering_controllers_library` instance next.
- All protocol/kinematics unit tests pass (`./gradlew testDebugUnitTest`);
  `assembleDebug` produces an installable APK. `FrameParser`'s header
  layout is validated against a real hardware-captured `0x20/0x01` frame
  (`FrameParserTest.decodesARealHardwareCapturedChannelFrame`) — this
  caught and fixed a real bug (see PROTOCOL.md's 2026-09-10/11 correction)
  that would have silently rejected every real channel frame the app ever
  saw, confirmed by the on-device run above.
- `SiyiSerialReader` opens `/dev/ttyHS1` with a plain `FileInputStream`,
  relying on the vendor's own `biz.siyi.remotecontrol` service having
  already configured the UART (230400 8N1 — see PROTOCOL.md's Transport
  section). If real hardware testing shows garbled bytes at the raw level,
  the fallback is a small JNI `termios`-configuring wrapper — see the
  Javadoc on `SiyiSerialReader.open()`.
- Only the joystick-channel frame type (`0x20/0x01`) is decoded today;
  buttons and extended telemetry frames are parsed (correct length/CRC) but
  ignored. See the frame catalog in PROTOCOL.md to extend this.
- ~~Reverse-steering commands are not recovered correctly by the robot-side
  `bicycle_cmd_relay`~~ — **moot now that this app targets
  `steering_controllers_library` instead** (2026-09-14): that controller's
  `atan`-based recovery is sign-preserving for reverse, unlike
  `bicycle_cmd_relay`'s `atan2`-based one. Still true if something in this
  project ever goes back to publishing to `bicycle_cmd_relay` — see
  PROTOCOL.md's "Known limitation" section for the original writeup.
