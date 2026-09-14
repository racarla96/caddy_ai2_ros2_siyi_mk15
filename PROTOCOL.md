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
- On top of the service being alive, the RCU only *streams* `0x20/0x01`
  channel frames after something has asked it to at least once — empirically,
  opening the vendor app's own channel-data screen. It then keeps streaming
  indefinitely regardless of what's in the Android foreground afterward (see
  "Confirmed end-to-end on real hardware" further down) — so this is a
  one-time kickstart per RCU connection/boot, not a standing requirement, but
  it is still a real, unautomated dependency today.

### `/dev/ttyHS0` vs `/dev/ttyHS1` — two unrelated interfaces, easy to conflate

This project investigated both over its lifetime; they're easy to confuse
since both ultimately talk to the same underlying radio-control unit. They
are **not** two views of the same data — different physical purpose,
different protocol, different one talks to whom:

| | `/dev/ttyHS1` (this doc, 230400 baud) | `/dev/ttyHS0` (115200 baud) |
|---|---|---|
| **Purpose** | *Internal* link: the handset's own Android SoC talking to the joystick/switch/dial microcontroller built into the same handset | *External* interface: the manual's documented "SIYI Datalink SDK" (section 4.8), meant for an outboard companion computer to talk to the handset over a cable/USB/Bluetooth |
| **Protocol** | `AA 0A 02`-framed, **not** published by SIYI — entirely reverse-engineered by this project (this whole document) | `55 66`-framed, officially documented, with its own `CMD_ID` catalog — see `SDK_COMMANDS.md` |
| **Activation** | Automatic as soon as `biz.siyi.remotecontrol`'s service is running (see above) — it's traffic internal to the handset | Only active once "Datalink → Connection → UART" is picked by hand in the vendor app's own UI — it's meant to be exposed outward |
| **Used by this app?** | **Yes — this is what `SiyiSerialReader` reads.** Natural choice since this app runs *inside* the handset's own Android, not as an external device. | No. Investigated in parallel (see `SDK_COMMANDS.md`) because it looked like the "official" path — 9 of 10 read commands work, but the one that mattered (`0x42`, request channel data) never responded. Turned out to be a dead end for this project's purposes, at least so far; `ttyHS1` is what's actually delivering real data. |

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
> **Trigger identified by decompile (2026-09-11), not yet captured on the
> wire**: traced the exact call chain from `ChannelViewModel`'s constructor
> (which is what runs the instant that screen opens) down through
> `RcuServiceController.D2()` (reference-counted: only acts on the *first*
> subscriber, `"current count: N"` in the app's own logcat — matches what we
> saw live) → `biz.siyi.pilot.rcuservice.binder.b5.Y1()` →
> `h3.a.b(boolean)`, which builds a `t5.e` request exactly like every other
> command in `SDK_COMMANDS.md`:
> ```java
> eVarU.f(new byte[]{z10 ? (byte) 1 : (byte) 0});  // payload: 1 byte, bool
> eVarU.f11153d = true;   // need_ack
> eVarU.f11160k = 16;     // dest = RCU
> eVarU.f11161l = 1;      // CMD_ID = 0x01
> ```
> Called with `true` on the first subscriber (starts streaming) — confirmed
> **symmetric**: `RcuServiceController.N()`/`b5.N()` (the teardown path,
> `ChannelViewModel.clear()`) calls the identical `h3.a.b(false)` when the
> last subscriber leaves, i.e. the same `CMD_ID 0x01` with payload `[0x00]`
> to stop it. This matches what was observed live: streaming kept going
> because nothing had called the `false` side yet (the vendor app was
> merely backgrounded, its `ChannelViewModel` never torn down).
>
> **Bonus, resolves an old mystery**: the very first raw `/dev/ttyHS1`
> capture this project ever did (2026-09-07/08 update above, `type=0x0c,
> sub_id=0x3e`) is a *different* command from the same class —
> `h3.a.a(boolean)` sends `CMD_ID 0x3E` (62 decimal), an unrelated toggle,
> not channel streaming. That capture was never going to find the channel
> trigger; it was catching some other feature being exercised. (The same
> file also has `c(ImageTransFrequencyBand)` → `CMD_ID 0x70`/dest=20, and
> `e(int, boolean, boolean)` → `CMD_ID 0x84`/"setMultiAirUnitMode" — noted
> for completeness, not chased further.)
>
> **Confirmed on the wire (2026-09-11, hardware session)**: ran the plan
> above for real. Cleared logcat, started a background `adb shell timeout
> 90 cat /dev/ttyHS1 > hs1_raw.bin` capture plus a parallel host-side
> `adb logcat -v time`, then opened SIYI TX's channel-data screen three
> times (the app got restarted between opens, visible as new `WriteTask`
> PIDs — 2614, then 6210, 6386). Found the write-direction frame this app
> sends for `CMD_ID 0x01` — note this is a **different sync/header** than
> the `AA 0A 02` read-direction frame format documented above; the app's
> own *outgoing* (write) frames on `ttyHS1` use `AA 09 02` instead:
> ```
> AA 09 02 01 <ctrHi> <ctrLo> <01=start|00=stop> D0 10 10 01 01 <CRC16 LE>
> ```
> Five real, CRC-16/XMODEM-verified (same algorithm as `Crc16.java`, over
> every byte except the trailing 2) examples captured via `WriteTask`:
> ```
> start: AA 09 02 01 F3 17 01 D0 10 10 01 01 7C 34
> start: AA 09 02 01 F3 1C 01 D0 10 10 01 00 72 6F
> start: AA 09 02 01 F3 1D 01 D0 10 10 01 01 32 C7
> stop:  AA 09 02 01 F3 15 00 D0 10 10 01 01 3F 11
> stop:  AA 09 02 01 F3 17 00 D0 10 10 01 01 DC 71
> ```
> Byte-by-byte: `AA 09 02` sync (fixed for this write-frame kind — distinct
> from the read-direction `AA 0A 02`/heartbeat-write `AA 09 02 00...`
> sync); byte 3 = `01` marks a "command, ack requested" write (heartbeats,
> also captured in the same session, use `AA 09 02 00 ...` — byte 3 `00`
> — and don't carry this field); bytes 4-5 = a 2-byte running counter
> (doesn't reset across the enable/disable pair, only across app
> restarts — exact meaning not pinned down, doesn't need to be to
> replicate this); **byte 6 is the payload — `0x01` to start streaming,
> `0x00` to stop**, cleanly split across all 5 samples along the observed
> open/close timeline; bytes 7-9 `D0 10 10` and byte 10 `01` fixed
> (plausibly encoding `dest=16`/`CMD_ID=1` from the `t5.e` layer, though
> the exact sub-byte mapping wasn't reverse-engineered further since it's
> not needed to replicate the write); byte 11 is `01` in 4/5 samples,
> `00` in one — unexplained, likely inconsequential (didn't correlate
> with anything); last 2 bytes = CRC16 (LE). Raw capture over the same
> window showed 2661 valid `0x20/0x01` channel frames (0 CRC errors) —
> streaming was genuinely live throughout, confirming this write-frame
> family is the right one and not a red herring.
>
> **Implemented (2026-09-11, same day), on-device tested, still not confirmed
> working end-to-end**: `protocol/ChannelStreamControl.java` builds this
> frame (unit-tested byte-for-byte against the real captures above,
> `ChannelStreamControlTest`); `SiyiSerialReader` sends it on every connect.
> Getting a reliable trigger from our own app took several rounds of
> on-device iteration, across **4 full power-cycles** (not `adb reboot` —
> see below):
> 1. **The RCU is a separate chip from the Android SoC and keeps its own
>    streaming state across an Android-only `adb reboot`.** Discovered when
>    a "cold" test right after `adb reboot` showed channel frames already
>    flowing before anything had triggered them that "boot" — the RCU had
>    simply never lost the state from before the reboot. A genuine cold
>    test needs a full power-cycle (power off, wait, power on).
>    `biz.siyi.remotecontrol:rcuservice` auto-starts at boot on its own
>    (confirms `ttyHS1` to 230400 baud immediately) without needing its UI
>    ever opened — a true cold, power-cycled state shows *only* heartbeat
>    (`type=0x01 sub_id=0x60`) traffic, no `0x20/0x01` channel frames, until
>    something sends the start command.
> 2. Sending the start frame **once**, via `RandomAccessFile.write()` on the
>    same file the reader already has open, did not trigger streaming on a
>    genuine cold boot — plausibly lost a race against `rcuservice`'s own
>    ~1Hz heartbeat write on the same device node (no arbitration between
>    concurrent writers on a raw serial port).
>    A tight burst of 5 such writes 300ms apart, sent before the read loop
>    even started, **also failed** on a fresh cold boot.
> 3. Retried with 5 writes spaced 3s apart (~12s total), running
>    concurrently with the read loop instead of blocking it (stopping early
>    the moment a real channel frame is observed) — confirmed via added
>    logging that all 5 attempts genuinely executed on schedule with no
>    exception or hang, and this **also failed** to trigger streaming on
>    yet another cold boot.
> 4. Meanwhile, manually resending the identical frame bytes via separate
>    `adb shell "printf ... > /dev/ttyHS1"` invocations (confirmed
>    byte-correct with `od`) **did** trigger streaming, more than once,
>    including as a retried burst. Checked and ruled out as the explanation
>    for the Java-vs-shell gap: `/dev/ttyHS1` is world-writable
>    (`crwxrwxrwx system:system`) and this device's SELinux is in
>    **permissive** mode, so neither DAC permissions nor the vendor
>    process's higher-privilege `system_app`/`system` UID (vs. this app's
>    plain `untrusted_app`) blocks anything. **The actual root cause of the
>    Java-vs-shell gap was not found** — candidates not yet tested:
>    `RandomAccessFile`'s `open()` flags subtly affecting tty line
>    discipline on this driver, Android-specific I/O buffering, or
>    something else. Given 4 power-cycles already spent in one session, the
>    pragmatic fix taken was to stop guessing and reproduce the recipe
>    already proven to work: `SiyiSerialReader.sendStartFrameViaShell` now
>    shells out to the same `printf ... > /dev/ttyHS1` invocation (via
>    `ProcessBuilder`) instead of writing through the open file, still
>    retried up to 5×/3s apart and stopping early on a real channel frame.
>
> **Update 2026-09-14: shell-based version tested cold — still failed at
> first, real root cause found (not the Java-vs-shell gap), now fixed and
> confirmed working end-to-end.** Ran the queued plan on a fresh power-cycle:
> the shell-exec version fired all 5 retries cleanly (confirmed via logcat,
> no errors/timeouts) but triggered **zero** channel frames over a ~90s
> window. Isolated the cause one variable at a time, entirely outside the
> app (raw `adb shell printf` sends, so the Java-vs-shell execution path
> itself is not the confound): the write-direction **counter field (bytes
> 4-5) is not an opaque nonce the RCU accepts unconditionally**, contrary to
> what was assumed above ("exact meaning not pinned down, doesn't need to
> be to replicate this" — turned out it does matter). On a cold boot,
> sending the identical start frame with a millisecond-clock-derived counter
> (e.g. `0x8254`, what the app was generating) got silence; the same frame
> with a real vendor-captured counter (`0xF317`) triggered streaming
> immediately — confirmed as the very first and only command sent that
> boot (ruling out "just needs a second attempt somehow" as an alternative
> explanation, tested on a separate power-cycle specifically to rule that
> out). The exact acceptance rule (bit pattern? some internal RCU sequence
> window?) was **not** further reverse-engineered — not spent bisecting it
> given the hardware-cycle cost already spent this session (4 power-cycles
> across two sessions total). **Fix**: `SiyiSerialReader` now cycles
> through two known-good real captured counters (`0xF317`, `0xF31D`, both
> already byte-verified in `ChannelStreamControlTest`) instead of a freshly
> computed clock value. **Confirmed working on real hardware, cold boot,
> app-only** (one more power-cycle, install the fixed APK, launch, don't
> touch the vendor app at all): `frames válidos` climbed to 2676 with 0 CRC
> errors/0 resyncs, 16 real channel values decoded, `Twist` computed
> correctly (sticks centered → `linear.x=0.00 angular.z=0.00`). **This
> closes the README "Runtime requirements" dependency for good** — the app
> no longer needs a human to have opened SIYI TX's channel screen at all.
>
> **Also found, separately, not yet explained**: sending a "stop" frame
> (`payload=0x00`, real captured counter) to an already-streaming RCU did
> not observably stop it in this session's testing (checked via a follow-up
> raw capture — channel frames kept flowing). Not a blocker for the
> start-trigger goal (this app never needs to stop streaming today), but
> means `ChannelStreamControl.encodeStop()` and the vendor app's own
> decompiled stop path are unconfirmed on the wire — worth real
> investigation if a future feature needs to actually silence the channel
> stream (e.g. to hand control back cleanly), rather than assuming the
> decompile-derived symmetry holds.
>
> A quicker gotcha hit along the way, unrelated to the counter bug: the
> MK15's screen dozes fast enough that it can pause background Java threads
> mid-test (`Thread.sleep`-based retry timing stretched from ~12s to 40s+
> wall-clock with no code change) — `adb shell svc power stayon true` before
> a timed on-device test avoids this false negative.

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

## Known limitation (historical, `bicycle_cmd_relay` target only): reverse
## steering recovery — superseded 2026-09-14

Not a protocol issue — a downstream one, but documented here because
`BicycleTwistComputer` (the code that turns normalized steer/throttle from
this protocol into a `geometry_msgs/Twist`) exists specifically to feed
whatever robot-side controller consumes it.

**This limitation applied only to `bicycle_cmd_relay`** (in
`caddy_ai2_ros2_controllers`), which recovered a steering angle from a
received Twist via `angle = atan2(wheelbase * angular_z, linear_x)`.
`atan2(y, x)` only ranges over `(-π/2, π/2)` when `x > 0`. So whenever
`linear_x < 0` (reverse), `bicycle_cmd_relay` could not recover the
originally intended steering angle: it reconstructed one with magnitude near
`π` and the **opposite** sign of what was commanded (e.g.
steer-left-while-reversing recovered as a large steer-right), which then
saturated to `max_steer_angle` downstream. No choice of `angular_z` on the
transmitting side made it exact for `linear_x < 0` — a property of
`bicycle_cmd_relay`'s own `atan2`-based recovery, not something the
transmitting side could fix.

**Superseded 2026-09-14**: this project's robot-side target changed to
`ros2_controllers`' `steering_controllers_library`, published to directly
(see README's "Robot-side target" note and `SteeringReferencePublisher`).
That controller's `SteeringKinematics::convert_twist_to_steering_angle`
recovers the angle via plain `std::atan(angular_z * wheelbase / linear_x)`
— **not** `atan2` — which is odd/sign-preserving, so the *ratio*
`angular_z/linear_x` this app publishes keeps the same sign for
`linear_x < 0` too (see
`BicycleTwistComputerTest.reverseWithSteerKeepsTheSameSteerRatio`,
which replaced the old `reverseWithSteerDoesNotRoundTripCleanly` regression
test documenting the bug above). This limitation only resurfaces if
something in this project goes back to publishing to `bicycle_cmd_relay`.

**Further update, same day**: `BicycleTwistComputer` no longer tries to know
or match the robot's real `wheelbase` at all (agreed with the user — see
its Javadoc and `TeleopConfig`'s driving-profile Javadoc). It publishes
`angular_z` proportional to the joystick's steer fraction directly
(`linear_x * tan(steerNorm * maxSteerAngleRad)`, no `/wheelbase` term), so
the angle the robot actually recovers via its own `wheelbase` is only exact
if that value happens to be `1` — otherwise it's a monotonic, proportional
response from center to full lock, not an exact angle match. This was a
deliberate simplification, not an oversight: exact recovery would require
this app to know the robot's real wheelbase (previously a user-editable
setting, now removed entirely along with the parameter).
