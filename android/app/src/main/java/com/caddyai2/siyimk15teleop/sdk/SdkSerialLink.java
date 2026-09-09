package com.caddyai2.siyimk15teleop.sdk;

import java.io.Closeable;
import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.util.concurrent.TimeUnit;

/**
 * Minimal bidirectional link to {@link #DEVICE_PATH}, for the request/response SIYI
 * Datalink SDK protocol (see {@link SdkFrame}) — unlike
 * {@code com.caddyai2.siyimk15teleop.serial.SiyiSerialReader} (read-only, passive
 * {@code ttyHS1}), this protocol needs to *write* a request before anything comes
 * back, hence {@link RandomAccessFile} in {@code "rw"} mode instead of a plain
 * {@code FileInputStream}.
 *
 * <p><b>Unlike {@code SiyiSerialReader}'s {@code ttyHS1}, this port is not reliably left
 * in a usable state by the vendor app.</b> Confirmed on real hardware (2026-09-10) with
 * "Datalink → Connection → UART" already selected in the "SIYI TX" app: {@code ttyHS0}
 * was still sitting at its 9600-baud idle default, and critically had {@code iuclc}
 * (lowercases any incoming byte in the uppercase-ASCII-letter range, {@code 0x41-0x5A})
 * and {@code ixon}/{@code ixoff}/{@code ixany} (software XON/XOFF flow control, which can
 * eat in-band bytes) all *on* — a {@code CMD_ID 0x47} round trip sent through that config
 * came back with its {@code 0x55} sync byte and {@code 0x47} CMD_ID silently corrupted to
 * {@code 0x75}/{@code 0x67} (both are exactly the "flip bit 0x20" that {@code iuclc} does
 * to an ASCII letter — {@code 'U'->'u'}, {@code 'G'->'g'}). So {@link #open()} configures
 * the port itself via {@code stty} (see {@link #sttyArgs}) rather than trusting external
 * state, the same way it would need to if opening the node cold with nothing having
 * touched it at all.
 *
 * <p>Deliberately minimal beyond that (open/read/write/close only, no background thread or
 * framing) — callers own the read loop and {@link SdkFrameParser}, since the two
 * current use cases (a one-shot request/response diagnostic, and a future
 * continuously-streamed {@code CMD_ID 0x42} subscription) want different threading.
 */
public final class SdkSerialLink implements Closeable {

    public static final String DEVICE_PATH = "/dev/ttyHS0";
    public static final int BAUD_RATE = 115200;

    private static final String STTY_BIN = "/system/bin/stty";
    private static final long STTY_TIMEOUT_MS = 2000;

    private final String devicePath;
    private RandomAccessFile file;

    public SdkSerialLink() {
        this(DEVICE_PATH);
    }

    public SdkSerialLink(String devicePath) {
        this.devicePath = devicePath;
    }

    /**
     * The {@code stty} argv that puts {@code devicePath} into the state this protocol
     * needs: {@link #BAUD_RATE} 8N1, raw mode, and the four flags whose on-device defaults
     * were found to corrupt frame bytes (see this class's Javadoc) explicitly turned off.
     * A static, args-in/args-out method (rather than inline in {@link #configurePort})
     * purely so it's unit-testable without actually running a process — see
     * {@code SdkSerialLinkTest}.
     */
    static String[] sttyArgs(String devicePath) {
        return new String[] {
                STTY_BIN, "-F", devicePath, String.valueOf(BAUD_RATE),
                "cs8", "-parenb", "-cstopb", "raw", "-echo",
                "-iuclc", "-ixon", "-ixoff", "-ixany", "-inpck", "-istrip", "-icrnl"
        };
    }

    public void open() throws IOException {
        configurePort();
        File device = new File(devicePath);
        if (!device.exists()) {
            throw new IOException("No such device: " + devicePath);
        }
        file = new RandomAccessFile(device, "rw");
    }

    /** Runs {@code stty} with {@link #sttyArgs} before the device node is opened. */
    private void configurePort() throws IOException {
        try {
            Process p = new ProcessBuilder(sttyArgs(devicePath))
                    .redirectErrorStream(true)
                    .start();
            boolean finished = p.waitFor(STTY_TIMEOUT_MS, TimeUnit.MILLISECONDS);
            if (!finished) {
                p.destroyForcibly();
                throw new IOException("stty timed out configuring " + devicePath);
            }
            if (p.exitValue() != 0) {
                throw new IOException("stty exited " + p.exitValue() + " configuring " + devicePath);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted while configuring " + devicePath, e);
        }
    }

    public void send(byte[] frame) throws IOException {
        file.write(frame);
    }

    /** Blocking read, same contract as {@link java.io.InputStream#read(byte[])}. */
    public int read(byte[] buf) throws IOException {
        return file.read(buf);
    }

    /**
     * Closes the underlying file. Safe to call from a different thread than the one
     * blocked in {@link #read}, as a way to force that blocked call to return (with
     * an {@link IOException}) — used by callers implementing a read timeout, since
     * plain blocking Java I/O has no built-in per-call timeout.
     */
    @Override
    public void close() throws IOException {
        if (file != null) {
            file.close();
        }
    }
}
