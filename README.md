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
anymore — see `BicycleTwistComputer`'s Javadoc for why. `steer` and
`throttle` are published fully independently (`angular_z` from the steer
stick alone, `linear_x` from the throttle stick alone, no cross-term
between them — settled after live on-device testing showed an earlier
wheelbase/ratio-based coupling made `angular_z` look like "not working"
whenever the vehicle was stopped). `TeleopConfig`'s `topic_name`/`frame_id`
must be set to match the real controller instance on the robot.

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
  - `diagnostics/` — `DiagnosticsState`: thread-safe singleton (frame
    counters, unknown-frame tally, event log) that `MainActivity` writes to
    from the serial read thread and `DiagnosticsActivity` polls and
    displays — the two screens' only coupling.
  - `util/` — `FullscreenHelper`: the immersive/fullscreen System UI flags
    shared by all three activities.
  - `MainActivity` — wiring + the live driving status (connection, active
    profile, current `linear.x`/`angular.z`) only; no protocol/math logic
    or raw protocol dump of its own (2026-09-14 — see `DiagnosticsActivity`).
  - `DiagnosticsActivity` — the raw link/protocol screen: frame counters,
    unknown-frame tally, the 16-channel dump, the SIYI Datalink SDK
    (`ttyHS0`) one-shot firmware-version test, and the scrolling event log.
    Reached from `MainActivity` via a button; not part of the normal
    operating flow.
  - `SettingsActivity` — in-app form for every `TeleopConfig` field
    (the 3 driving profiles, then transport settings).
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
  instead of a plain `Twist` to `bicycle_cmd_relay`.
- **`BicycleTwistComputer` publishes steer/throttle fully independently
  (2026-09-14, settled after 3 rounds of live on-device testing)**:
  `angular_z` comes from the steer stick alone, `linear_x` from the
  throttle stick alone — no cross-term. An earlier revision tried to make
  `angular_z` exactly recoverable via that controller's own
  `atan(angular_z*wheelbase/linear_x)` formula (a small "creep" `linear_x`
  substituted at zero throttle so the ratio stayed defined), but that made
  `angular_z` numerically tiny and look broken whenever the vehicle was
  stopped — confirmed live (`linear.x=0.02 angular.z=0.01` while holding
  full steer lock, throttle centered). The user's call: drop the coupling
  entirely, at the cost of no longer exactly satisfying that controller's
  ratio-based angle recovery at zero throttle. See
  `BicycleTwistComputer`'s Javadoc.
- **UI overhaul (2026-09-14), verified live on real hardware**: fixed a
  real crash (`CalledFromWrongThreadException` — a profile-change log line
  touched views from the serial read thread instead of via
  `runOnUiThread()`, confirmed via on-device logcat) that hit every time
  the CH7 switch changed position. Also replaced the original flat
  monospace-dump screen with a dark, card-based layout (status/profile
  merged into one card, live `linear.x`/`angular.z` tiles), split the raw
  protocol diagnostics out into their own `DiagnosticsActivity` screen,
  and made all three activities run fullscreen/immersive. See
  `DiagnosticsState`'s Javadoc for how the two screens share live data.
- **Fixed the app silently pausing after a few minutes (2026-09-14)**:
  `MainActivity.onStop()` deliberately stops `SiyiSerialReader`/
  `SteeringReferencePublisher` — and Android calls `onStop()` the moment
  the screen times out from inactivity and dims/locks, which happens
  within a couple of minutes on an idle handset by default. From the
  operator's side that looked exactly like a freeze: the UI stopped
  updating and the robot stopped receiving commands, no error shown.
  `FullscreenHelper` (already applied by all three activities) now also
  sets `FLAG_KEEP_SCREEN_ON`, so the timeout never fires while this app
  is in the foreground. Verified live: forced `screen_off_timeout` down
  to 8s, screen stayed on and the app stayed "Conectado" past 12s.
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
- **Reverse-steering sign, revisited (2026-09-14)**: the original
  `bicycle_cmd_relay`/`atan2` version of this bug (see PROTOCOL.md's
  "Known limitation" section) no longer applies since this app doesn't
  target `bicycle_cmd_relay` anymore — but making `angular_z`/`linear_x`
  fully independent (this same update) means `steering_controllers_library`'s
  own `atan(angular_z*wheelbase/linear_x)` recovery can flip the recovered
  steering direction's sign when reversing, since `linear_x`'s sign no
  longer cancels against `angular_z`'s the way the earlier ratio-coupled
  design guaranteed. Not yet tested against a real controller instance —
  worth checking once the robot is available, since this is a different,
  newly-introduced trade-off from the original bug, not the same one.

## Acknowledgments

This app's entire ROS 2 DDS layer — the pure-Java client that lets an
Android app publish/subscribe without `rclcpp`, a ROS 2 install, or a
companion computer — is [`jros2`](https://github.com/ihmcrobotics/jros2)
by [IHMC Robotics](https://github.com/ihmcrobotics), used here via its
`jros2-android` artifact (`us.ihmc:jros2-android:1.5.1`, see
`android/app/build.gradle.kts`). `SteeringReferencePublisher` (`ros2/`)
is a thin wrapper around `us.ihmc.jros2.ROS2Node`/`ROS2Publisher`; the
`geometry_msgs`/`std_msgs`/`builtin_interfaces` message classes it uses
are jros2's own generated bindings. None of this project's DDS
functionality would exist without that library.
