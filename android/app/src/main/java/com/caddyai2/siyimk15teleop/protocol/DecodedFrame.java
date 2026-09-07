package com.caddyai2.siyimk15teleop.protocol;

import java.util.Arrays;

/** A single validated (CRC-correct) frame, still in raw form. */
public final class DecodedFrame {
    public final int type;
    public final int subId;
    public final int seq;
    public final byte[] payload;

    DecodedFrame(int type, int subId, int seq, byte[] payload) {
        this.type = type;
        this.subId = subId;
        this.seq = seq;
        this.payload = payload;
    }

    public boolean isChannelFrame() {
        return type == FrameCatalog.CHANNELS.type && subId == FrameCatalog.CHANNELS.subId;
    }

    /**
     * Decodes the 16 joystick channels (uint16 LE, range ~1000-2000) out of the payload.
     * Only valid when {@link #isChannelFrame()} is true.
     */
    public int[] decodeChannels() {
        int[] channels = new int[16];
        for (int i = 0; i < 16; i++) {
            int lo = payload[i * 2] & 0xFF;
            int hi = payload[i * 2 + 1] & 0xFF;
            channels[i] = lo | (hi << 8);
        }
        return channels;
    }

    @Override
    public String toString() {
        return "DecodedFrame{type=0x" + Integer.toHexString(type)
                + ", subId=0x" + Integer.toHexString(subId)
                + ", seq=" + seq
                + ", payload=" + Arrays.toString(payload) + "}";
    }
}
