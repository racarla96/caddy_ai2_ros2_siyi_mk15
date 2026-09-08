# SIYI MK15 internal joystick-channel protocol

Reverse-engineered from `/dev/ttyHS1` traffic on the MK15 handset itself
(Android 9 / API 28, verified via `adb shell getprop ro.build.version.sdk`).
This is **not** a SIYI-published spec — it's the empirical result of capturing
and decoding the byte stream the handset's own internal UART carries from its
joystick hardware to its Android side. Treat every value below as "observed",
not "documented by the vendor".

The implementation lives under
`android/app/src/main/java/com/caddyai2/siyimk15teleop/protocol/`; the
[README](README.md) covers the app around it.

## Transport

- Device node: `/dev/ttyHS1`, `crwxrwxrwx`, owned by `system:system` (checked
  with `adb shell ls -la /dev/ttyHS1`) — readable without root.
- Baud/framing: **230400 8N1** — not the 115200 originally assumed from the
  vendor manual. Confirmed by raw on-device capture
  (`adb shell stty -F /dev/ttyHS1`) with the vendor's own service running.
  The app opens the node as a second reader (see `SiyiSerialReader`) rather
  than configuring it itself.
- **Depends on `biz.siyi.remotecontrol`**: the UART only carries traffic
  while that vendor package (and the `biz.siyi.remotecontrol:mcuservice`
  process it spawns) is running — it's the one that reconfigures the port to
  230400 baud. Without it, `/dev/ttyHS1` sits at its idle default of 9600
  baud and is **silent** (0 bytes, not garbage). So on a fresh device, "no
  frames at all" most likely means that service hasn't started yet, not a
  transport bug — check with `adb shell ps -A | grep -i siyi` and, if
  needed, `adb shell am start -n biz.siyi.remotecontrol/.ui.SplashActivity`.

## Frame layout

No explicit length field. Every frame:

```
AA 0A 02 | type(1) | seq(2, LE) | 03 10 D0 10 | sub_id(1) | payload(N) | CRC16(2, LE)
```

| Field | Size | Notes |
|---|---|---|
| Sync | 3 | Always `AA 0A 02`. |
| `type` | 1 | Frame category — see catalog below. |
| `seq` | 2 | Little-endian sequence counter, free-running, not used for framing. |
| Fixed middle | 4 | Always `03 10 D0 10`. Never observed to vary. |
| `sub_id` | 1 | Sub-type within `type`. `(type, sub_id)` together select the total frame length. |
| payload | `total_length - 11 - 2` | Frame-specific. |
| CRC16 | 2 | Little-endian, computed over every preceding byte of the frame (sync through end of payload). |

Total frame length is **not** carried in the frame itself — it's looked up
from a fixed `(type, sub_id) -> total_length` table (the "catalog") built by
capturing each frame type once and measuring it. A frame whose `(type,
sub_id)` isn't in the catalog cannot be decoded (parser resyncs past it, see
below).

## CRC

CRC-16/XMODEM: polynomial `0x1021`, initial value `0x0000`, not reflected, no
final XOR. Computed over the whole frame excluding the trailing 2-byte CRC
field itself, transmitted little-endian. Implementation: `Crc16.java`.

## Frame catalog

| `type` | `sub_id` | Total length | Contents | Decoded? |
|---|---|---|---|---|
| `0x20` | `0x01` | 45 bytes | 16 × `uint16` LE joystick channels | Yes — the only frame type the app acts on |
| `0x10` | `0x07` | 29 bytes | Button/switch state | No — parsed as a valid frame, payload unused |
| `0x60` | `0x0f` | 109 bytes | Extended telemetry | No — parsed as a valid frame, payload unused |

> **Open question (as of 2026-09-08):** a follow-up raw capture (bypassing
> the app, straight off `/dev/ttyHS1` with `biz.siyi.remotecontrol` running)
> saw **only** `type=0x0c, sub_id=0x3e`, fixed 25-byte frames with a
> different fixed-middle (`00 10 D0 10`, not `03 10 D0 10`) — CRC-valid on
> every frame, but not a `(type, sub_id)` in the table above, and no
> `0x20/0x01` frame appeared at all in that window. Not yet resolved whether
> this is a firmware-version difference (a firmware update was in progress
> at time of writing), a missing "enable channel passthrough" step in the
> vendor UI, or genuine drift from when this table was first captured. See
> `tools/analyze_capture.py` for redoing this diagnostic, and the project
> memory notes for the full investigation log. Until resolved, treat this
> table as unconfirmed against current firmware.

`0x20/0x01` is observed to be the large majority (~85%) of traffic — the
handset streams joystick state continuously regardless of whether it's
changing. Any other `(type, sub_id)` pair is unknown to the app; those bytes
are skipped one at a time until the next valid sync is found (see
"Resync policy" below). Extending the catalog (e.g. to decode buttons) just
means adding an `Entry` to `FrameCatalog` and a case in
`MainActivity.onFrame`/a new listener callback — the parser itself doesn't
change.

## Channel payload (`0x20/0x01`)

32-byte payload: 16 consecutive `uint16` little-endian values, one per
joystick channel, in the order the handset radio numbers them (CH1..CH16 at
array indices 0..15). Raw range is approximately **1000–2000**, center
**1500** (a standard PWM-style joystick encoding, unrelated to the serial
baud rate).

Confirmed channel assignments (found by moving one physical control at a
time and watching which array index moved):

| Channel | Array index | Control | Meaning |
|---|---|---|---|
| CH1 | 0 | Aileron / J1 | Steering (lateral) |
| CH3 | 2 | J3 | Throttle |

All other indices are read but currently ignored. **CH2 showed 1500–1651
crosstalk when only CH1 was moved** — a few percent of full range bleeding
into the mechanically/electrically adjacent channel — which is why
`ChannelMapper` always applies a deadzone (default ±20 raw units around
1500) before normalizing: without it, that crosstalk would misread as a real
~0.3 command on whatever channel it leaked into. CH2 itself is never
consumed in production; this is just the empirical justification for the
deadzone default (see `ChannelMapperTest`).

Normalization (`ChannelMapper.normalize`): deadzone-clipped, then linear from
the deadzone edge to the physical extreme, output in `[-1, 1]`, continuous
(no jump) at the deadzone boundary. Sign convention: increasing raw value ⇒
positive normalized output; `BicycleTwistComputer` treats positive steer as
"left" (REP-103), matching the sign the robot-side
`bicycle_to_ackermann_steering_adapter` expects.

## Resync policy

The parser (`FrameParser`) never trusts a single match blindly:

1. Scans for the 3-byte sync `AA 0A 02`.
2. If the following 4 fixed bytes (`03 10 D0 10`) don't match, that was a
   false-positive sync inside other traffic — advance one byte and rescan
   (`onResync()`).
3. If `(type, sub_id)` isn't in the catalog, the frame can't be sized —
   advance one byte and rescan (`onUnknownFrame()`).
4. Once a full frame's worth of bytes has arrived, verify the CRC. On
   mismatch, don't trust the catalog length either — advance one byte and
   rescan rather than skipping the whole (possibly wrong) frame length
   (`onCrcError()`).

Advancing one byte at a time (rather than jumping past an entire assumed
frame) is deliberate: it's the only way to recover byte alignment after
serial glitches without a length field to double-check against. It's proven
in `FrameParserTest`, including a case with a stray `AA 0A 02` inside
unrelated noise, immediately followed by one genuine frame.

## Update 2026-09-09: a second, *documented* protocol exists — likely the real path

On-device investigation of the "Open question" above (after the firmware/app
update to `biz.siyi.remotecontrol` 3.1.6) found that `/dev/ttyHS1` now only
carries a 14-byte heartbeat (`type=0x01, sub_id=0x60`, 1-byte payload,
~1/sec) — confirmed by the vendor's *own* logcat output
(`SIYIRemoteControlParser: parseRcCmd, cmdId:60 data:00`), not just our
parser. No channel data at all appeared on that port in this session.

Meanwhile, `MK15_User_Manual_v1_9.pdf` section 4.8 ("SIYI Datalink SDK")
documents a completely different, **official, request/response protocol**
on a **different port**, seemingly built exactly for this use case:

- Port: **`/dev/ttyHS0`** (not `ttyHS1`) at **115200 baud**.
- Only active once **"Datalink → Connection → UART"** is selected in the
  vendor's own "SIYI TX" app (`biz.siyi.remotecontrol`) — not yet located
  in that app's UI (it renders without an accessibility tree, so needs
  someone on the physical touchscreen, not `adb`/`uiautomator`).
- Framing: `STX(2)=0x55 0x66 | CTRL(1) | Data_len(2 LE) | SEQ(2 LE) |
  CMD_ID(1) | DATA(Data_len) | CRC16(2 LE)`. `Data_len` is carried
  explicitly — no length catalog needed, unlike the `ttyHS1` protocol above.
- CRC16: **the same algorithm** already implemented in `Crc16.java` (poly
  `0x1021`, init 0, not reflected, no final XOR) — the manual's own C
  reference in section 4.8.4 matches it exactly. One CRC engine, reused
  across both protocols.
- **`CMD_ID 0x42` "Request Channel Data"** is the actual goal: request a
  frequency (0=off .. 7=100Hz), get back 16 × `int16_t` channels (default
  range ~1050–1950, close to but not identical to the 1000–2000 assumed
  for `ttyHS1` — needs re-confirming once real ACKs are captured).
- Implementation started with **`CMD_ID 0x47` "Request Firmware Version"**
  instead (see `android/.../sdk/FirmwareVersion.java`) — no request
  payload, simplest possible round-trip to validate framing + CRC on real
  hardware before building anything that depends on it.
- **Manual erratum found while implementing this**: the worked ACK example
  for `CMD_ID 0x47` in section 4.8.2 has a CRC16 that doesn't check out
  against the algorithm documented two pages later (computed `0x616d` vs.
  the example's printed `0x216d` — low byte matches, high byte doesn't,
  looks like a transcription slip). The *request* half of that same
  example checks out exactly, and so does every byte of live-hardware
  traffic CRC-verified earlier in this investigation, so the algorithm as
  implemented is trusted over the one erroneous example byte pair — see
  `FirmwareVersion`'s Javadoc.

New code lives under `android/.../sdk/` (`SdkFrame`, `SdkFrameParser`,
`FirmwareVersion`), deliberately separate from `protocol/` (the `ttyHS1`
code above) rather than replacing it — until a real capture on `ttyHS0`
confirms this is in fact the live path, both are kept.

**Next steps**: (1) find "Datalink → Connection → UART" in the SIYI TX app
on the handset's touchscreen; (2) redo a raw capture, this time on
`/dev/ttyHS0`; (3) once traffic appears, send the `FirmwareVersion` request
first as a framing sanity check, then implement `CMD_ID 0x42` the same way;
(4) if this pans out, `SiyiSerialReader` needs to become bidirectional
(currently read-only `FileInputStream`) and `MainActivity`'s pipeline needs
to target `ttyHS0`/this protocol instead of (or alongside) `ttyHS1`.

## Known limitation: `bicycle_cmd_relay` reverse steering recovery

Not a protocol issue — a downstream one, but documented here because
`BicycleTwistComputer` (the code that turns normalized steer/throttle from
this protocol into a `geometry_msgs/Twist`) exists specifically to feed it.

The robot-side `bicycle_cmd_relay` (in `caddy_ai2_ros2_controllers`) recovers
a steering angle from a received Twist via
`angle = atan2(wheelbase * angular_z, linear_x)`. `atan2(y, x)` only ranges
over `(-π/2, π/2)` when `x > 0`. So whenever `linear_x < 0` (reverse),
`bicycle_cmd_relay` cannot recover the originally intended steering angle: it
reconstructs one with magnitude near `π` and the **opposite** sign of what
was commanded (e.g. steer-left-while-reversing recovers as a large
steer-right), which then saturates to `max_steer_angle` downstream.

This is a property of `bicycle_cmd_relay`'s own `atan2`-based recovery — no
choice of `angular_z` on the transmitting side makes it exact for `linear_x <
0`. `BicycleTwistComputerTest.reverseWithSteerDoesNotRoundTripCleanly` pins
down the exact behavior (sign flip, magnitude `> π/2`) as a regression test,
so a future fix on the `bicycle_cmd_relay` side (e.g. switching to `atan`, or
an explicit sign-aware formula) has something to satisfy. Until then: reverse
steering on the real vehicle will not behave as commanded.
