package com.caddyai2.siyimk15teleop.serial;

import android.util.Log;

import com.caddyai2.siyimk15teleop.protocol.DecodedFrame;
import com.caddyai2.siyimk15teleop.protocol.FrameParser;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;

/**
 * Reads raw bytes from {@code /dev/ttyHS1} and feeds them to a {@link FrameParser}.
 *
 * <p>The device node ships {@code crwxrwxrwx}, owned by {@code system:system} (verified
 * with {@code adb shell ls -la /dev/ttyHS1}), so it is opened here with a plain
 * {@link FileInputStream} — no JNI/root needed to *open* it.
 *
 * <p><b>Baud rate caveat:</b> a plain {@code File}/{@code FileInputStream} on Android
 * never calls {@code tcsetattr}/{@code cfsetispeed} — it just reads whatever bytes the
 * kernel driver hands back. That's fine <i>as long as</i> the vendor's own service,
 * {@code biz.siyi.remotecontrol} (and its {@code mcuservice} process), is running:
 * on-device capture (`adb shell cat /dev/ttyHS1`) confirmed that service is what
 * configures the internal joystick UART, and it does so at <b>230400 8N1</b> — not
 * 115200 as originally assumed from the vendor manual. Without that service running,
 * the port sits at its idle default (9600 baud, per {@code stty -F /dev/ttyHS1}) and
 * produces zero bytes, not garbage — so "no frames at all" on a fresh device most
 * likely means that service hasn't started yet, not a baud mismatch. This reader is
 * simply a second consumer of whatever framing the vendor service already applied. If
 * on-device testing shows garbled frames on the raw byte level (not just a CRC or
 * framing bug — check with a hex dump first) even with that service confirmed
 * running, the fallback is a minimal JNI wrapper around
 * {@code open()+termios ioctl()+read()}, along the lines of android-serialport-api,
 * to force 230400 8N1 raw mode before reading. Left as a TODO hook: see
 * {@link #open()}.
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
        FrameParser parser = new FrameParser(listener);
        try (InputStream in = open()) {
            listener.onConnected();
            byte[] buf = new byte[READ_CHUNK];
            while (running) {
                int n = in.read(buf);
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
        }
    }

    /**
     * Opens the device node for reading. Plain Java I/O today; swap for a JNI
     * (termios-configuring) implementation here if raw open() proves insufficient
     * on real hardware — everything else in this class is agnostic to that choice.
     */
    private InputStream open() throws IOException {
        File device = new File(devicePath);
        if (!device.exists()) {
            throw new IOException("No such device: " + devicePath);
        }
        return new FileInputStream(device);
    }
}
