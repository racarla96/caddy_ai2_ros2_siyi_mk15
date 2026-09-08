package com.caddyai2.siyimk15teleop.sdk;

import com.caddyai2.siyimk15teleop.protocol.Crc16;

import java.util.Arrays;

/**
 * Streaming parser for the SIYI Datalink SDK protocol (see {@link SdkFrame}).
 *
 * Simpler than {@code com.caddyai2.siyimk15teleop.protocol.FrameParser}: {@code
 * Data_len} is carried in the frame itself, so there's no length catalog to
 * maintain. Still resyncs byte-at-a-time on any mismatch (wrong sync, an
 * implausible {@code Data_len}, or a bad CRC) for the same reason as that parser:
 * without re-validating, a coincidental two-byte {@code 0x55 0x66} match inside
 * unrelated bytes could desync the stream for good.
 *
 * Not thread-safe: {@link #feed} must always be called from the same thread.
 * Callbacks run synchronously on that thread.
 */
public final class SdkFrameParser {

    private static final int STX_HI = 0x55;
    private static final int STX_LO = 0x66;
    private static final int HEADER_LEN = 8; // STX(2)+CTRL(1)+Data_len(2)+SEQ(2)+CMD_ID(1)
    private static final int CRC_LEN = 2;

    // No real SDK payload comes close to this (the largest known ACK, all-channel-mapping,
    // is well under 100 bytes) -- a Data_len above it means the "sync" was a coincidental
    // 0x55 0x66 match inside other traffic, not treating it as one keeps a garbage byte
    // from stalling the parser waiting for bytes that will never arrive.
    private static final int MAX_PLAUSIBLE_DATA_LEN = 512;

    public interface Listener {
        void onFrame(SdkFrame frame);

        /** A 2-byte sync matched but the frame that followed didn't check out (implausible length). */
        default void onResync() {
        }

        /** Frame length looked plausible and was reached, but the trailing CRC16 didn't match. */
        default void onCrcError(int cmdId) {
        }
    }

    private final Listener listener;
    private byte[] buffer = new byte[512];
    private int len = 0;

    public SdkFrameParser(Listener listener) {
        this.listener = listener;
    }

    public void feed(byte[] data, int offset, int count) {
        ensureCapacity(len + count);
        System.arraycopy(data, offset, buffer, len, count);
        len += count;
        parseAvailable();
    }

    private void parseAvailable() {
        int cursor = 0;
        while (true) {
            int sync = findSync(cursor);
            if (sync < 0) {
                cursor = Math.max(0, len - 1); // keep a possible partial sync for next feed()
                break;
            }
            if (len - sync < HEADER_LEN) {
                cursor = sync; // wait for more data
                break;
            }

            int dataLen = (buffer[sync + 3] & 0xFF) | ((buffer[sync + 4] & 0xFF) << 8);
            if (dataLen > MAX_PLAUSIBLE_DATA_LEN) {
                listener.onResync();
                cursor = sync + 1;
                continue;
            }

            int total = HEADER_LEN + dataLen + CRC_LEN;
            if (len - sync < total) {
                cursor = sync; // wait for the rest of this frame
                break;
            }

            int crcOffset = sync + total - CRC_LEN;
            int computed = Crc16.compute(buffer, sync, total - CRC_LEN);
            int received = (buffer[crcOffset] & 0xFF) | ((buffer[crcOffset + 1] & 0xFF) << 8);
            int cmdId = buffer[sync + 7] & 0xFF;
            if (computed != received) {
                listener.onCrcError(cmdId);
                cursor = sync + 1; // don't trust dataLen blindly; resync byte-by-byte
                continue;
            }

            int ctrl = buffer[sync + 2] & 0xFF;
            int seq = (buffer[sync + 5] & 0xFF) | ((buffer[sync + 6] & 0xFF) << 8);
            byte[] payload = Arrays.copyOfRange(buffer, sync + HEADER_LEN, sync + HEADER_LEN + dataLen);
            listener.onFrame(new SdkFrame(ctrl, seq, cmdId, payload));
            cursor = sync + total;
        }
        System.arraycopy(buffer, cursor, buffer, 0, len - cursor);
        len -= cursor;
    }

    /** Finds the next `55 66` sync sequence at or after {@code from}, or -1. */
    private int findSync(int from) {
        for (int i = from; i + 2 <= len; i++) {
            if ((buffer[i] & 0xFF) == STX_HI && (buffer[i + 1] & 0xFF) == STX_LO) {
                return i;
            }
        }
        return -1;
    }

    private void ensureCapacity(int needed) {
        if (needed > buffer.length) {
            buffer = Arrays.copyOf(buffer, Math.max(needed, buffer.length * 2));
        }
    }
}
