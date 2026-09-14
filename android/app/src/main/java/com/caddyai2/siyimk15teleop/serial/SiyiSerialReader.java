package com.caddyai2.siyimk15teleop.serial;

import android.util.Log;

import com.caddyai2.siyimk15teleop.protocol.ChannelStreamControl;
import com.caddyai2.siyimk15teleop.protocol.DecodedFrame;
import com.caddyai2.siyimk15teleop.protocol.FrameParser;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Reads raw bytes from {@code /dev/ttyHS1} and feeds them to a {@link FrameParser}; also
 * sends the "start streaming" control frame ({@link ChannelStreamControl}, see
 * {@link #startStreamingRequester}) repeatedly on every connect, so the RCU begins sending
 * {@link com.caddyai2.siyimk15teleop.protocol.FrameCatalog#CHANNELS} frames without depending
 * on a human having opened the vendor "SIYI TX" app's channel-data screen at least once since
 * boot (see PROTOCOL.md's "Confirmed on the wire" section for how that frame was captured and
 * reverse-engineered). <b>Reading and writing use two different I/O paths</b>: reads go
 * through the {@link RandomAccessFile} opened by {@link #open()} as always, but the start
 * frame is sent by shelling out to {@code printf ... > devicePath} (see
 * {@link #sendStartFrameViaShell}) rather than writing through that same file — see the
 * "On-device findings" note below for why.
 *
 * <p><b>On-device findings (2026-09-11)</b>: power-cycled (not just `adb reboot`'d — the RCU
 * turned out to be a separate chip that keeps its streaming state across an Android-only
 * reboot) the MK15 <b>four times</b> to test this cold, confirming only heartbeat traffic on
 * `ttyHS1` beforehand each time (nothing had opened the vendor app's channel screen that
 * boot):
 * <ol>
 *   <li>A single {@link RandomAccessFile#write} attempt did not trigger streaming — plausibly
 *       lost a race against {@code rcuservice}'s own ~1Hz heartbeat write on the same device
 *       node, with no arbitration between writers.</li>
 *   <li>A tight burst of 5 {@code RandomAccessFile} writes 300ms apart (before the read loop
 *       even started) also failed.</li>
 *   <li>5 {@code RandomAccessFile} writes 3s apart (~12s window), running concurrently with
 *       the read loop, <b>also</b> failed — ruling out "burst too short" as the explanation.
 *       Confirmed via added logging that all 5 attempts genuinely executed, 3s apart, with no
 *       exception and no thread hang — so the write call itself wasn't silently failing.</li>
 *   <li>Meanwhile, manually repeating the identical frame bytes via separate
 *       {@code adb shell "printf ... > /dev/ttyHS1"} invocations — each spawning its own
 *       process, opening, writing, and closing its own file descriptor — worked, more than
 *       once, including once as a single retried burst.</li>
 * </ol>
 * Ruled out as an explanation for the Java-vs-shell gap: {@code /dev/ttyHS1} is world-writable
 * ({@code crwxrwxrwx system:system}) and this device's SELinux is in <b>permissive</b> mode
 * (`getenforce` → `Permissive`), so neither DAC permissions nor the vendor process's
 * higher-privilege {@code system_app}/{@code system} UID (vs. this app's plain
 * {@code untrusted_app}/{@code u0_a98}) blocks anything. <b>The actual root cause of the gap
 * between {@code RandomAccessFile.write()} and a shelled-out {@code printf} was not found</b>
 * — candidates not yet tested: different {@code open()} flags subtly changing tty line
 * discipline behavior on this driver, some Android-specific buffering in
 * {@code RandomAccessFile}, or something else entirely. Given time pressure across 4
 * power-cycles in one session, the pragmatic fix taken was to stop guessing and just reproduce
 * the recipe already proven to work: {@link #sendStartFrameViaShell} shells out to the same
 * {@code printf} invocation instead of writing through the open file.
 *
 * <p><b>On-device findings (2026-09-14)</b>: the shell-based version above was tested cold
 * (real power-cycles) and initially <b>still failed</b> — all 5 retries executed cleanly
 * (confirmed via logcat, no errors) but triggered zero channel frames over a ~90s window.
 * Root cause found by isolating one variable at a time, entirely outside the app (raw
 * {@code adb shell printf} sends, bypassing Java/{@code ProcessBuilder} to rule out an
 * execution-path difference): the write-direction <b>counter field is not an opaque nonce
 * the RCU accepts unconditionally</b>, contradicting the original (2026-09-11) belief. On a
 * cold boot, sending the identical start frame with a millisecond-clock-derived counter
 * (e.g. {@code 0x8254}) got silence, while the same frame with a real vendor-captured counter
 * (e.g. {@code 0xF317}) triggered streaming immediately — confirmed as the very first and only
 * command sent that boot, ruling out "just needs a second attempt" as an alternative
 * explanation. The exact acceptance rule (bit pattern? some internal RCU sequence window?)
 * was <b>not</b> further reverse-engineered — time/hardware-cycle cost across this session's
 * power-cycles wasn't spent bisecting it. Pragmatic fix: {@link #startStreamingRequester} now
 * cycles through {@link #KNOWN_GOOD_START_COUNTERS} (real captured values, byte-verified in
 * {@code ChannelStreamControlTest}) instead of a freshly computed clock value. <b>Also found,
 * separately</b>: sending a "stop" frame (even with a real captured counter) did not observably
 * stop an already-streaming RCU in this session's testing — not yet explained, noted as an
 * open question, not a blocker for the start-trigger goal. <b>This counter fix itself has not
 * yet been validated on hardware as the actual installed app</b> (only as a manual byte-level
 * replication of what the fixed code now sends) — that's the next concrete step.
 *
 * <p>The device node ships {@code crwxrwxrwx}, owned by {@code system:system} (verified
 * with {@code adb shell ls -la /dev/ttyHS1}), so it is opened here with a plain
 * {@link RandomAccessFile} in {@code "rw"} mode — no JNI/root needed to *open* it, and no
 * {@code stty} reconfiguration either (unlike {@code SdkSerialLink}/{@code ttyHS0}): capture
 * has only ever been done with the vendor's own service already having configured this port.
 *
 * <p><b>Baud rate caveat:</b> a plain {@code File}-backed I/O on Android never calls
 * {@code tcsetattr}/{@code cfsetispeed} — it just reads/writes whatever bytes the kernel
 * driver hands back. That's fine <i>as long as</i> the vendor's own service,
 * {@code biz.siyi.remotecontrol} (and its {@code rcuservice} process, formerly
 * {@code mcuservice}), is running: on-device capture (`adb shell cat /dev/ttyHS1`) confirmed
 * that service is what configures the internal joystick UART, and it does so at
 * <b>230400 8N1</b> — not 115200 as originally assumed from the vendor manual. Without that
 * service running, the port sits at its idle default (9600 baud, per
 * {@code stty -F /dev/ttyHS1}) and produces zero bytes, not garbage — so "no frames at all"
 * on a fresh device most likely means that service hasn't started yet, not a baud mismatch.
 * If on-device testing shows garbled frames on the raw byte level (not just a CRC or framing
 * bug — check with a hex dump first) even with that service confirmed running, the fallback
 * is a minimal JNI wrapper around {@code open()+termios ioctl()+read()}, along the lines of
 * android-serialport-api, to force 230400 8N1 raw mode before reading. Left as a TODO hook:
 * see {@link #open()}.
 */
public final class SiyiSerialReader {

    private static final String TAG = "SiyiSerialReader";
    public static final String DEVICE_PATH = "/dev/ttyHS1";
    private static final int READ_CHUNK = 256;

    public interface Listener extends FrameParser.Listener {
        void onConnected();

        void onDisconnected(Throwable error);
    }

    private final Listener listener;
    private final String devicePath;
    private volatile boolean running = false;
    private Thread readThread;

    public SiyiSerialReader(Listener listener) {
        this(listener, DEVICE_PATH);
    }

    public SiyiSerialReader(Listener listener, String devicePath) {
        this.listener = listener;
        this.devicePath = devicePath;
    }

    public synchronized void start() {
        if (running) {
            return;
        }
        running = true;
        readThread = new Thread(this::runLoop, "siyi-serial-read");
        readThread.start();
    }

    public synchronized void stop() {
        running = false;
        if (readThread != null) {
            readThread.interrupt();
            readThread = null;
        }
    }

    private void runLoop() {
        AtomicBoolean channelFrameSeen = new AtomicBoolean(false);
        FrameParser parser = new FrameParser(new FrameParser.Listener() {
            @Override
            public void onFrame(DecodedFrame frame) {
                if (frame.isChannelFrame()) {
                    channelFrameSeen.set(true); // tells startStreamingRequester to stop retrying
                }
                listener.onFrame(frame);
            }

            @Override
            public void onResync() {
                listener.onResync();
            }

            @Override
            public void onUnknownFrame(int type, int subId) {
                listener.onUnknownFrame(type, subId);
            }

            @Override
            public void onCrcError(int type, int subId) {
                listener.onCrcError(type, subId);
            }
        });
        Thread starter = null;
        try (RandomAccessFile port = open()) {
            listener.onConnected();
            starter = startStreamingRequester(channelFrameSeen);
            byte[] buf = new byte[READ_CHUNK];
            while (running) {
                int n = port.read(buf);
                if (n < 0) {
                    throw new IOException("EOF on " + devicePath);
                }
                if (n > 0) {
                    parser.feed(buf, 0, n);
                }
            }
            listener.onDisconnected(null);
        } catch (IOException e) {
            Log.e(TAG, "Serial read failed on " + devicePath, e);
            listener.onDisconnected(e);
        } finally {
            if (starter != null) {
                starter.interrupt();
            }
        }
    }

    // See this class's Javadoc for why this retries at all (a single write wasn't enough to
    // reliably beat rcuservice's own heartbeat write for the wire) and why it shells out to
    // printf instead of writing through the already-open `port` (a plain
    // RandomAccessFile.write() of the exact same bytes, retried up to 5x/3s apart, was tested
    // on three separate cold boots and never once triggered streaming — while the identical
    // bytes sent via a separate `adb shell "printf ... > /dev/ttyHS1"` process did, more than
    // once. The two paths were never fully reconciled; shelling out here just reproduces the
    // one that's actually confirmed to work on this hardware).
    private static final int START_FRAME_MAX_ATTEMPTS = 5;
    private static final long START_FRAME_RETRY_DELAY_MS = 3000;
    private static final long START_FRAME_SHELL_TIMEOUT_MS = 2000;

    /**
     * Spawns a daemon thread that sends {@link ChannelStreamControl#encodeStart} up to
     * {@link #START_FRAME_MAX_ATTEMPTS} times, {@link #START_FRAME_RETRY_DELAY_MS} apart,
     * stopping as soon as {@code channelFrameSeen} goes true (set by the read loop's
     * {@link FrameParser.Listener#onFrame} the moment a real channel frame arrives) or the
     * reader is {@link #stop}ped. Runs concurrently with the read loop, started right after
     * it, rather than blocking it — the retry window (up to
     * {@code (MAX_ATTEMPTS-1) * DELAY} ≈ 12s) is long enough that blocking on it before
     * reading would needlessly delay every normal reconnect.
     */
    // See ChannelStreamControl's Javadoc and this file's 2026-09-14 on-device findings below:
    // the write-direction "counter" field is NOT an opaque nonce the RCU accepts unconditionally
    // (a millisecond-clock-derived value was tried here originally and repeatedly failed to
    // trigger streaming on real hardware) — only values matching real vendor-captured samples
    // have been confirmed to work. Cycle through the two distinct real captured "start" counters
    // from ChannelStreamControlTest rather than inventing a new value.
    private static final int[] KNOWN_GOOD_START_COUNTERS = {0xF317, 0xF31D};

    private Thread startStreamingRequester(AtomicBoolean channelFrameSeen) {
        Thread thread = new Thread(() -> {
            for (int attempt = 0; attempt < START_FRAME_MAX_ATTEMPTS; attempt++) {
                if (!running || channelFrameSeen.get()) {
                    return;
                }
                int counter = KNOWN_GOOD_START_COUNTERS[attempt % KNOWN_GOOD_START_COUNTERS.length];
                sendStartFrameViaShell(counter, attempt + 1);
                if (attempt < START_FRAME_MAX_ATTEMPTS - 1) {
                    try {
                        Thread.sleep(START_FRAME_RETRY_DELAY_MS);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                }
            }
        }, "siyi-serial-start-retry");
        thread.setDaemon(true);
        thread.start();
        return thread;
    }

    /**
     * Encodes one start frame and writes it to {@link #devicePath} by shelling out to
     * {@code printf ... > devicePath} (see this class's Javadoc for why: writing the same
     * bytes through Java's own {@link RandomAccessFile} was tested and did not reliably
     * trigger streaming, while this exact recipe — a separate process opening, writing, and
     * closing the device node — did, repeatedly, in manual on-device testing). The counter
     * field is <b>not</b> an opaque nonce the RCU accepts unconditionally — see this class's
     * 2026-09-14 on-device findings below — so {@code counter} here must be one of
     * {@link #KNOWN_GOOD_START_COUNTERS}, not a freshly invented value.
     */
    private void sendStartFrameViaShell(int counter, int attemptNumber) {
        String shellCommand = "printf '" + toPrintfHex(ChannelStreamControl.encodeStart(counter))
                + "' > " + devicePath;
        try {
            Process process = new ProcessBuilder("sh", "-c", shellCommand)
                    .redirectErrorStream(true)
                    .start();
            boolean finished = process.waitFor(START_FRAME_SHELL_TIMEOUT_MS, TimeUnit.MILLISECONDS);
            if (!finished) {
                process.destroyForcibly();
                Log.w(TAG, "printf write to " + devicePath + " timed out (attempt " + attemptNumber + ")");
                return;
            }
            if (process.exitValue() != 0) {
                Log.w(TAG, "printf write to " + devicePath + " exited " + process.exitValue()
                        + " (attempt " + attemptNumber + ")");
                return;
            }
            Log.i(TAG, "Sent channel-stream start frame via shell (attempt " + attemptNumber
                    + "/" + START_FRAME_MAX_ATTEMPTS + ", counter=0x" + Integer.toHexString(counter)
                    + ") on " + devicePath);
        } catch (IOException e) {
            Log.w(TAG, "Failed to spawn printf write to " + devicePath + " (attempt " + attemptNumber + ")", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** Renders {@code frame} as a {@code printf}-compatible {@code \xHH}-per-byte string. */
    private static String toPrintfHex(byte[] frame) {
        StringBuilder sb = new StringBuilder(frame.length * 4);
        for (byte b : frame) {
            sb.append(String.format(Locale.ROOT, "\\x%02x", b & 0xFF));
        }
        return sb.toString();
    }

    /**
     * Opens the device node for reading and writing. Plain Java I/O today; swap for a JNI
     * (termios-configuring) implementation here if raw open() proves insufficient on real
     * hardware — everything else in this class is agnostic to that choice.
     */
    private RandomAccessFile open() throws IOException {
        File device = new File(devicePath);
        if (!device.exists()) {
            throw new IOException("No such device: " + devicePath);
        }
        return new RandomAccessFile(device, "rw");
    }
}
