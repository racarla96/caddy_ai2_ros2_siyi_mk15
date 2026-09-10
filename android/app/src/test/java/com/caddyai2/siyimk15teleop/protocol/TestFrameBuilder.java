package com.caddyai2.siyimk15teleop.protocol;

import java.io.ByteArrayOutputStream;

/** Builds well-formed synthetic frames (correct CRC) for parser tests. */
final class TestFrameBuilder {

    private TestFrameBuilder() {
    }

    /** Builds a type=0x20/sub_id=0x01 channel frame (45 bytes total) from 16 raw channel values. */
    static byte[] channelFrame(int seq, int[] channels16) {
        if (channels16.length != 16) {
            throw new IllegalArgumentException("expected 16 channels");
        }
        byte[] payload = new byte[32];
        for (int i = 0; i < 16; i++) {
            payload[i * 2] = (byte) (channels16[i] & 0xFF);
            payload[i * 2 + 1] = (byte) ((channels16[i] >> 8) & 0xFF);
        }
        return frame(0x20, 0x01, seq, payload);
    }

    static byte[] frame(int type, int subId, int seq, byte[] payload) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(0xAA);
        out.write(0x0A);
        out.write(0x02);
        out.write(type);
        out.write(seq & 0xFF);
        out.write((seq >> 8) & 0xFF);
        out.write((seq >> 16) & 0xFF);
        out.write(0x10);
        out.write(0xD0);
        out.write(0x10);
        out.write(subId);
        out.writeBytes(payload);

        byte[] withoutCrc = out.toByteArray();
        int crc = Crc16.compute(withoutCrc);

        ByteArrayOutputStream withCrc = new ByteArrayOutputStream();
        withCrc.writeBytes(withoutCrc);
        withCrc.write(crc & 0xFF);
        withCrc.write((crc >> 8) & 0xFF);
        return withCrc.toByteArray();
    }
}
