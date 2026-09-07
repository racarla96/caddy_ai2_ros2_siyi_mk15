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
- Baud/framing: 115200 8N1, already configured by the vendor's own service on
  that UART; the app opens the node as a second reader (see
  `SiyiSerialReader`) rather than configuring it itself.

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
