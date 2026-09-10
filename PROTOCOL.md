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
AA 0A 02 | type(1) | counter(3, LE) | 10 D0 10 | sub_id(1) | payload(N) | CRC16(2, LE)
```

| Field | Size | Notes |
|---|---|---|
| Sync | 3 | Always `AA 0A 02`. |
| `type` | 1 | Frame category — see catalog below. |
| `counter` | 3 | Little-endian free-running counter, not a simple +1 frame index (see correction below), not used for framing. |
| Fixed middle | 3 | Always `10 D0 10`. Never observed to vary. |
| `sub_id` | 1 | Sub-type within `type`. `(type, sub_id)` together select the total frame length. |
| payload | `total_length - 11 - 2` | Frame-specific. |
| CRC16 | 2 | Little-endian, computed over every preceding byte of the frame (sync through end of payload). |

> **Correction, 2026-09-10/11** (superseding the field split originally
> documented here): this was previously described as `seq(2 LE)` followed by
> 4 always-`03 10 D0 10` fixed bytes. That was an artifact of every capture up
> to that point being too short to see the real field roll over — a ~25s raw
> capture correlated against live logcat caught it going from `...0b 10 d0
> 10` to `...0c 10 d0 10` mid-stream, proving it's actually a 3-byte counter
> (increments ~256-512 per frame — looks like a millisecond-ish tick, not a
> frame-by-frame sequence number) and only the trailing 3 bytes are genuinely
> constant. Total header length is unchanged either way (10 bytes before
> `sub_id`), so every total frame length in the catalog below is still
> correct — this only moved where the field boundary falls, and it matters
> because the old, too-narrow "fixed middle" check would wrongly reject real
> frames whose counter's low byte lands outside the range any past short
> capture happened to see. Fixed in `FrameParser`/`TestFrameBuilder`; see
> `FrameParserTest.decodesARealHardwareCapturedChannelFrame` for a golden
> fixture using real captured bytes that catches a regression here.

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

> **Resolved, 2026-09-10/11** (was an open question as of 2026-09-08): early
> raw captures of `/dev/ttyHS1` (bypassing the app) only ever saw a
> heartbeat/status frame, never `0x20/0x01` channel data. The missing
> variable turned out to be simple: **the RCU only streams `0x20/0x01` while
> the vendor app's own "channel data" screen is open** — a raw capture taken
> while nothing in the app was asking for channel values will only ever see
> whatever housekeeping traffic (heartbeats, etc.) flows regardless. Confirmed
> by launching the real app (`biz.siyi.remotecontrol`, i.e. "SIYI TX"),
> capturing `adb logcat` live while navigating to that screen, and — in the
> same session — a raw `/dev/ttyHS1` capture that caught `type=0x20,
> sub_id=0x01, 45 bytes` frames streaming at roughly 50Hz the moment that
> screen was opened, decoding to exactly the 16 channel values the app's own
> logcat (`SIYIRemoteControlParser: parseRcCmd, cmdId:1 data:...`) reported
> at the same time, matching the already-confirmed channel mapping (CH1-4
> joystick, CH5-7 switches, etc.). `FrameCatalog.CHANNELS = (0x20, 0x01, 45)`
> — written back when this catalog was first captured, before the whole SDK
> detour — turned out to be exactly right all along; see
> `FrameParserTest.decodesARealHardwareCapturedChannelFrame` for the real
> bytes as a regression fixture. Also resolved in passing: the earlier
> `type=0x0c, sub_id=0x3e` sighting and the "fixed middle" byte drift across
> sessions (`00`/`03`/`0b`/`0c` different times) are both explained by the
> counter-width correction above, not by firmware/protocol drift.
>
> **Confirmed end-to-end on real hardware (2026-09-10/11, same session)**:
> installed the actual app on the MK15, opened the vendor app's channel
> screen once to trigger streaming, then switched to our app — its own
> `SiyiSerialReader`→`FrameParser` pipeline (with the fix above) processed
> **2287 real frames, 0 CRC errors, 0 resyncs** in one run, confirmed live
> on-screen via the app's own stats readout. Bonus finding: streaming
> **doesn't stop when the triggering vendor screen loses foreground** — it
> kept going for well over a minute with that app backgrounded, so nothing
> needs to keep it open once triggered.
>
> **Still open (low priority — doesn't block anything now)**: what exactly
> *triggers* the RCU to start streaming `0x20/0x01` in the first place isn't
> captured yet — a read-only `cat` of the port only sees MCU→app traffic, not
> whatever request the app itself writes out when that screen opens.
> `RemoteControlService.onCreate()` (decompiled from the real app, see
> `SDK_COMMANDS.md`) constructs `t5.k` — the same class implementing every
> `CMD_ID` in `SDK_COMMANDS.md` — against this exact port/baud, so the likely
> mechanism is a `t5.e`-built request frame, just not necessarily in the
> `55 66`-framed encoding documented in the manual's section 4.8 (this port's
> observed traffic is unmistakably `AA 0A 02`-framed, not `55 66`). Worth
> resolving eventually so a real deployment doesn't depend on a human having
> opened that vendor screen at least once since boot, but since streaming
> persists indefinitely once started, it's not an active blocker. Next step
> if/when picked back up: capture both directions at once (e.g.
> `strace`-style write logging, or correlating `WriteTask` logcat lines
> against a simultaneous raw capture) to catch the actual outgoing request
> frame.

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
2. If the following 3 fixed bytes (`10 D0 10`) don't match, that was a
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

> **Superseded, 2026-09-10/11**: the premise of this section — "`ttyHS1`
> carries only a heartbeat now, channel data must live elsewhere" — turned
> out to be an artifact of not having the vendor app's channel-data screen
> open during that capture, not a real change in what `ttyHS1` carries. See
> the "Resolved" callout above: `ttyHS1`'s `0x20/0x01` channel frames are
> alive and well on current firmware, this project's own `FrameCatalog` was
> right, and the code just had a header-parsing bug. The SDK protocol
> described below is still real and still documented by the manual (and
> `CMD_ID 0x42` on it still doesn't respond — see `SDK_COMMANDS.md` for the
> full, still-unresolved investigation into that) — it just isn't the only
> way to get channel data, and for this project's purposes `ttyHS1` is now
> the more promising path since it's already flowing real data with no
> extra hardware (air unit) required. Kept below for history.

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
- **`CMD_ID 0x42` "Request Channel Data" is now implemented too**
  (`android/.../sdk/ChannelData.java`), still without hardware access —
  encode/decode only, verified against the manual's worked examples the
  same way `FirmwareVersion` was. **Same erratum pattern found again**: the
  manual's worked ACK example for this command also has a CRC16 that
  doesn't check out (computed `0x8be2` vs. printed `0x88ff`), while both
  *request* examples (4Hz and OFF) check out exactly — see `ChannelData`'s
  Javadoc. `ChannelDataTest` decodes that example's payload directly,
  bypassing the frame-level CRC, same workaround as `FirmwareVersionTest`.
  `MainActivity` now has a second "Probar canales" button next to "Probar
  SDK (fw)" (both share one generic `runSdkTest` runner) so this is ready
  to try on-device the moment `ttyHS0` responds to anything.

New code lives under `android/.../sdk/` (`SdkFrame`, `SdkFrameParser`,
`FirmwareVersion`, `ChannelData`), deliberately separate from `protocol/`
(the `ttyHS1` code above) rather than replacing it — until a real capture
on `ttyHS0` confirms this is in fact the live path, both are kept.

**Next steps**: (1) find "Datalink → Connection → UART" in the SIYI TX app
on the handset's touchscreen; (2) redo a raw capture, this time on
`/dev/ttyHS0`; (3) once traffic appears, use "Probar SDK (fw)" first as a
framing sanity check, then "Probar canales" to confirm `CMD_ID 0x42`
end-to-end; (4) if this pans out, `SiyiSerialReader` needs to become
bidirectional (currently read-only `FileInputStream`) and `MainActivity`'s
pipeline needs to target `ttyHS0`/this protocol instead of (or alongside)
`ttyHS1`. **(1)-(3) are now done — see the 2026-09-10 update below.**

## Update 2026-09-10: protocol confirmed live, a real transport bug found and
## fixed, `CMD_ID 0x42` still unresolved

"Datalink → Connection → UART" was found and selected on the touchscreen.
With the handset connected over `adb`, went straight to raw testing against
`/dev/ttyHS0` (bypassing the app) before touching any Java code, to isolate
transport issues from protocol-decode issues.

**Finding 1 — the vendor UI toggle does *not* configure the port.** Even
with "UART" selected, `adb shell stty -F /dev/ttyHS0` still showed the idle
default: **9600 baud**, not the 115200 the manual documents. Unlike
`ttyHS1` (which `biz.siyi.remotecontrol` reliably reconfigures to 230400
once its own service is running), nothing reconfigures `ttyHS0` — whatever
opens it has to set this up itself.

**Finding 2 — the idle default also has flags that silently corrupt frame
bytes.** Full idle `stty -F /dev/ttyHS0` output: `iuclc ixon ixoff ixany`
all *on* (alongside 9600 baud). `iuclc` lowercases any *incoming* byte in
the uppercase-ASCII-letter range (0x41-0x5A) — since it's an input flag, it
only touches bytes the host reads back from the device, not bytes sent to
it. Sent a `CMD_ID 0x47` request (`55 66 01 00 00 00 00 47 66 ec`, byte-for-
byte the same request confirmed correct against the manual's own example)
through this dirty config with `adb shell "printf '\x55...' > /dev/ttyHS0"`
+ a backgrounded `cat /dev/ttyHS0 > file` to capture the reply. The device
answered correctly, but what came back over the corrupted read path was:

```
75 66 02 10 00 01 00 67 04 05 05 68 00 00 00 00 06 02 00 76 00 00 00 00 f3 a3
```

— `0x55` (sync, `'U'`) read back as `0x75` (`'u'`), `0x47` (`CMD_ID`, `'G'`)
read back as `0x67` (`'g'`), both exactly the +0x20 `iuclc` lowercasing bit
flip, both landing on bytes this protocol depends on (STX and CMD_ID).
`ixon`/`ixoff`/`ixany` (software XON/XOFF flow control) were also on and
are a second latent risk — they can drop, not just corrupt, any in-band
byte that collides with the flow-control control codes.

**Finding 3 — with the port correctly configured, the protocol works,
for real, end-to-end.** After `adb shell stty -F /dev/ttyHS0 115200 cs8
-parenb -cstopb raw -echo -iuclc -ixon -ixoff -ixany -inpck -istrip
-icrnl`, the same `CMD_ID 0x47` request/response came back clean and
CRC-valid:

```
Send:     55 66 01 00 00 00 00 47 66 ec
Response: 55 66 02 10 00 02 00 47 04 05 05 68 00 00 00 00 06 02 00 56 00 00 00 00 f3 d1
```

Decodes (per `FirmwareVersion`) to `rcVersion=5.5.4 (product 0x68)`,
`rfVersion=0.0.0 (product 0x00)`, `groundVersion=0.2.6 (product 0x56)`,
`skyVersion=0.0.0 (product 0x00)` — the RC figure matches the vendor app's
*own* logcat output for the same handset (`RemoteControlService:
deviceInfo... rcuVer=5.5.4... rcuModel=68`) captured independently in the
same session, and the response's CRC16 (`0xd1f3`) checks out exactly
against `Crc16`. This is the first confirmed live round trip on this
protocol — port, baud, framing, and CRC are no longer hypothetical.

**Fix landed**: `SdkSerialLink.open()` now runs `stty` itself (see
`sttyArgs()`) before opening the device, rather than assuming the vendor
app already left it usable — see that class's Javadoc for the full
reasoning and `SdkSerialLinkTest` for the regression test pinning the
required flags. Verified the fix against the real dirty-state bug: reset
`/dev/ttyHS0` to `9600 iuclc ixon ixoff ixany` by hand, ran the exact argv
`sttyArgs()` builds, redid the `CMD_ID 0x47` round trip — came back clean
(`55 66 02 10 00 04 00 47 ...`, valid CRC). Driving the actual on-screen
buttons to confirm end-to-end through the app itself wasn't possible this
session (the MK15's screen dozes almost immediately and `uiautomator`
needs it awake and settled — see "Useful diagnostic commands" in the
project memory) — this was validated one layer down, by replicating
`SdkSerialLink`'s exact command instead. Still worth a real button-press
confirmation next time someone's on the device.

**Full command catalog**: every command the manual documents (`0x40`
through `0x4D`), with real on-device test results for each read command
and the manual's own CRC errata cross-checked — see
[`SDK_COMMANDS.md`](SDK_COMMANDS.md). Short version: 9 of 10 read
commands tested respond with valid, CRC-correct data on real hardware;
`0x42` is the only one that doesn't (see below). The 3 write commands
(`0x17`, `0x4A`, `0x4D`) were deliberately not tested — they mutate
persistent state (bind/telemetry baud, live channel mapping, live
channel reverse) on a handset that ends up controlling a real vehicle.

**Still unresolved: `CMD_ID 0x42` "Request Channel Data" gets zero bytes
back.** Sent both example requests from the manual (4Hz — byte-identical
to `55 66 01 01 00 00 00 42 02 b5 c0` — and 100Hz) through the *correctly
configured* port, waited up to 6s, nothing came back at all — not even a
malformed reply, just silence, while `0x47` on the same port in the same
session kept responding correctly. Not a framing/CRC/transport problem
(same link, same session, same config that just proved itself against
`0x47`). Leading hypotheses, not yet distinguished: (a) the manual's own
note under this command — "Enabling RC channel output will affect
telemetry communication as they are using the same port" — hints this
might need a separate enable step beyond just picking "UART" as the
connection type, possibly `CMD_ID 0x17`'s `match`/system-settings command
or a dedicated toggle elsewhere in the SIYI TX app; (b) this firmware
build (product `0x68`, RC version 5.5.4) may simply not implement `0x42`
even though it's in the manual for the product line generally; (c) it may
require a paired air unit / active RF link to have real channel data to
report at all (this desk setup has none — `biz.siyi.pilot` was seen
earlier failing to reach the air-unit video subnet). `MainActivity`'s
"Probar canales" button (added the same session `0x42` was implemented,
see the update above) is ready to retry this the moment any of those
hypotheses gets tested.

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
