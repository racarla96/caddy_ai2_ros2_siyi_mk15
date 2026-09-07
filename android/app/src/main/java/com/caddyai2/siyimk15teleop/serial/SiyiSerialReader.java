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
 * kernel driver hands back. That's fine if the internal joystick UART is already
 * configured (by the vendor's own service) to 115200 8N1 and is simply being read
 * here as a second consumer. If step 4 (real on-device testing) shows garbled/no
 * frames on the raw byte level (not just a CRC or framing bug — check with a hex
 * dump first), the fallback is a minimal JNI wrapper around
 * {@code open()+termios ioctl()+read()}, along the lines of android-serialport-api,
 * to force 115200 8N1 raw mode before reading. Left as a TODO hook: see
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
