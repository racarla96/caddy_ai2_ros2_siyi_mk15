package com.caddyai2.siyimk15teleop.protocol;

import java.util.HashMap;
import java.util.Map;

/**
 * Registry of known (type, sub_id) -&gt; total frame length pairs.
 *
 * The framing has no explicit length field, so the parser must know the total
 * frame length up front to know where the trailing CRC16 lives. This table is
 * the empirical catalog from PROTOCOL.md. Unknown (type, sub_id) combinations
 * cannot be decoded; {@link FrameParser} resynchronizes past them.
 */
final class FrameCatalog {

    static final class Entry {
        final int type;
        final int subId;
        final int totalLength;

        Entry(int type, int subId, int totalLength) {
            this.type = type;
            this.subId = subId;
            this.totalLength = totalLength;
        }
    }

    // type=0x20 sub_id=0x01: 16 x uint16 LE channels, ~85% of traffic — the one we decode.
    static final Entry CHANNELS = new Entry(0x20, 0x01, 45);
    // type=0x10 sub_id=0x07: button/switch state, not decoded yet.
    static final Entry BUTTONS = new Entry(0x10, 0x07, 29);
    // type=0x60 sub_id=0x0f: extended telemetry, not decoded yet.
    static final Entry TELEMETRY = new Entry(0x60, 0x0f, 109);

    private static final Map<Integer, Entry> BY_KEY = new HashMap<>();

    static {
        for (Entry e : new Entry[] {CHANNELS, BUTTONS, TELEMETRY}) {
            BY_KEY.put(key(e.type, e.subId), e);
        }
    }

    private static int key(int type, int subId) {
        return ((type & 0xFF) << 8) | (subId & 0xFF);
    }

    static Entry lookup(int type, int subId) {
        return BY_KEY.get(key(type, subId));
    }

    private FrameCatalog() {
    }
}
