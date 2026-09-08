package com.caddyai2.siyimk15teleop.sdk;

import java.io.Closeable;
import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;

/**
 * Minimal bidirectional link to {@link #DEVICE_PATH}, for the request/response SIYI
 * Datalink SDK protocol (see {@link SdkFrame}) — unlike
 * {@code com.caddyai2.siyimk15teleop.serial.SiyiSerialReader} (read-only, passive
 * {@code ttyHS1}), this protocol needs to *write* a request before anything comes
 * back, hence {@link RandomAccessFile} in {@code "rw"} mode instead of a plain
 * {@code FileInputStream}.
 *
 * <p>Same caveat as {@code SiyiSerialReader}: this opens the node as-is, without
 * calling {@code tcsetattr}. Only relevant once "Datalink → Connection → UART" is
 * selected in the vendor's "SIYI TX" app (see PROTOCOL.md) — that's what's expected
 * to configure {@code ttyHS0} to 115200 8N1, the same way {@code biz.siyi.remotecontrol}
 * configures {@code ttyHS1} to 230400 for the other protocol.
 *
 * <p>Deliberately minimal (open/read/write/close only, no background thread or
 * framing) — callers own the read loop and {@link SdkFrameParser}, since the two
 * current use cases (a one-shot request/response diagnostic, and a future
 * continuously-streamed {@code CMD_ID 0x42} subscription) want different threading.
 */
public final class SdkSerialLink implements Closeable {

    public static final String DEVICE_PATH = "/dev/ttyHS0";

    private final String devicePath;
    private RandomAccessFile file;

    public SdkSerialLink() {
        this(DEVICE_PATH);
    }

    public SdkSerialLink(String devicePath) {
        this.devicePath = devicePath;
    }

    public void open() throws IOException {
        File device = new File(devicePath);
        if (!device.exists()) {
            throw new IOException("No such device: " + devicePath);
        }
        file = new RandomAccessFile(device, "rw");
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
