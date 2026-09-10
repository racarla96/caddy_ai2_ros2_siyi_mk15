package com.caddyai2.siyimk15teleop.protocol;

import java.util.Arrays;

/**
 * Streaming parser for the SIYI MK15 internal joystick-channel protocol.
 *
 * Framing (see PROTOCOL.md at the repo root):
 * <pre>
 *   AA 0A 02 | type(1) | counter(3 LE) | 10 D0 10 | sub_id(1) | payload | CRC16_LE(2)
 * </pre>
 *
 * <p><b>2026-09-10/11 correction</b>: earlier versions of this parser (and
 * PROTOCOL.md) described the field after {@code type} as a 2-byte {@code seq}
 * followed by 4 always-`03 10 D0 10` fixed bytes. That was wrong — it was an
 * artifact of every capture up to that point being short enough that the
 * field's low byte never rolled over. A ~25s raw capture correlated against
 * live logcat (`docs/logs/unigcs_live_logcat_2026-09-10.txt`, not committed)
 * caught it rolling from `...0b 10 d0 10` to `...0c 10 d0 10` mid-capture:
 * it's really a 3-byte little-endian counter (increments by ~256-512 per
 * frame, not 1 — looks like a millisecond-ish tick, not a frame index) and
 * only the trailing 3 bytes (`10 D0 10`) are genuinely constant. Total header
 * length is unchanged (10 bytes before `sub_id` either way), so
 * {@link FrameCatalog}'s total frame lengths are still correct — only the
 * field split within the header moved by one byte.
 *
 * There is no explicit length field: the total frame length is looked up from
 * {@link FrameCatalog} by (type, sub_id). Bytes arrive in arbitrary chunks from
 * the serial port, so this class owns an internal buffer and re-syncs on any
 * mismatch (wrong fixed bytes, unknown type/sub_id, or bad CRC) by advancing
 * one byte at a time rather than trusting a single 3-byte sync match.
 *
 * Not thread-safe: feed() must always be called from the same thread (the
 * serial reader thread). Callbacks run synchronously on that same thread.
 */
public final class FrameParser {

    /** Fixed bytes always present right after the 3-byte sync + type + 3-byte counter. */
    private static final byte[] FIXED_MIDDLE = {0x10, (byte) 0xD0, 0x10};
    private static final int HEADER_LEN_BEFORE_SUBID = 3 /*sync*/ + 1 /*type*/ + 3 /*counter*/ + 3 /*fixed*/;
    private static final int HEADER_LEN = HEADER_LEN_BEFORE_SUBID + 1 /*sub_id*/;
    private static final int CRC_LEN = 2;

    public interface Listener {
        void onFrame(DecodedFrame frame);

        /** A 3-byte sync matched but the rest of the header didn't check out. */
        default void onResync() {
        }

        /** Header was well-formed but (type, sub_id) isn't in {@link FrameCatalog}. */
        default void onUnknownFrame(int type, int subId) {
        }

        /** Frame length was known and reached, but the trailing CRC16 didn't match. */
        default void onCrcError(int type, int subId) {
        }
    }

    private final Listener listener;
    private byte[] buffer = new byte[512];
    private int len = 0; // valid bytes in buffer[0, len)

    public FrameParser(Listener listener) {
        this.listener = listener;
    }

    /** Appends newly-read bytes and extracts as many complete frames as possible. */
    public void feed(byte[] data, int offset, int count) {
        ensureCapacity(len + count);
        System.arraycopy(data, offset, buffer, len, count);
        len += count;
        parseAvailable();
        compact();
    }

    private void parseAvailable() {
        int cursor = 0;
        while (true) {
            int sync = findSync(cursor);
            if (sync < 0) {
                cursor = Math.max(0, len - 2); // keep a possible partial sync for next feed()
                break;
            }
            if (len - sync < HEADER_LEN) {
                cursor = sync; // wait for more data
                break;
            }
            if (!matchesFixedMiddle(sync)) {
                listener.onResync();
                cursor = sync + 1;
                continue;
            }

            int type = buffer[sync + 3] & 0xFF;
            int seq = (buffer[sync + 4] & 0xFF) | ((buffer[sync + 5] & 0xFF) << 8) | ((buffer[sync + 6] & 0xFF) << 16);
            int subId = buffer[sync + HEADER_LEN_BEFORE_SUBID] & 0xFF;

            FrameCatalog.Entry entry = FrameCatalog.lookup(type, subId);
            if (entry == null) {
                listener.onUnknownFrame(type, subId);
                cursor = sync + 1;
                continue;
            }

            int total = entry.totalLength;
            if (len - sync < total) {
                cursor = sync; // wait for the rest of this frame
                break;
            }

            int crcOffset = sync + total - CRC_LEN;
            int computed = Crc16.compute(buffer, sync, total - CRC_LEN);
            int received = Crc16.readLittleEndian(buffer, crcOffset);
            if (computed != received) {
                listener.onCrcError(type, subId);
                cursor = sync + 1; // don't trust "total" blindly; resync byte-by-byte
                continue;
            }

            int payloadLen = total - HEADER_LEN - CRC_LEN;
            byte[] payload = Arrays.copyOfRange(buffer, sync + HEADER_LEN, sync + HEADER_LEN + payloadLen);
            listener.onFrame(new DecodedFrame(type, subId, seq, payload));
            cursor = sync + total;
        }
        System.arraycopy(buffer, cursor, buffer, 0, len - cursor);
        len -= cursor;
    }

    private boolean matchesFixedMiddle(int sync) {
        for (int i = 0; i < FIXED_MIDDLE.length; i++) {
            if (buffer[sync + 3 + 1 + 3 + i] != FIXED_MIDDLE[i]) {
                return false;
            }
        }
        return true;
    }

    /** Finds the next `AA 0A 02` sync sequence at or after {@code from}, or -1. */
    private int findSync(int from) {
        for (int i = from; i + 3 <= len; i++) {
            if ((buffer[i] & 0xFF) == 0xAA && (buffer[i + 1] & 0xFF) == 0x0A && (buffer[i + 2] & 0xFF) == 0x02) {
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

    private void compact() {
        // parseAvailable() already shifts unconsumed bytes to the front on every call.
        // Retained data is always bounded by either a partial header (<= HEADER_LEN
        // bytes) or a full known frame (<= 109 bytes, see FrameCatalog), so the
        // buffer never grows unbounded even under a continuous stream of garbage —
        // this is just a hook kept for future tuning, nothing to do here today.
    }
}
