package com.caddyai2.siyimk15teleop.protocol;

import org.junit.Test;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;

/**
 * Verifies {@link ChannelStreamControl} against the 5 real frames captured on
 * real hardware 2026-09-11 via {@code WriteTask} logcat lines (see
 * PROTOCOL.md's "Confirmed on the wire" section) — not synthetic fixtures,
 * the literal bytes the vendor app sent on {@code /dev/ttyHS1} to start/stop
 * its channel-data screen, each independently CRC16-verified by hand before
 * being hard-coded here.
 */
public class ChannelStreamControlTest {

    private static byte[] fromHex(String hex) {
        byte[] out = new byte[hex.length() / 2];
        for (int i = 0; i < out.length; i++) {
            out[i] = (byte) Integer.parseInt(hex.substring(i * 2, i * 2 + 2), 16);
        }
        return out;
    }

    @Test
    public void matchesRealCapturedStartFrame_counter0xF317() {
        assertArrayEquals(
                fromHex("AA090201F31701D0101001017C34"),
                ChannelStreamControl.encodeStart(0xF317));
    }

    // One real captured sample (counter 0xF31C) has tail byte 11 = 0x00 instead of the
    // 0x01 every other sample (4/5) used — an unexplained one-off, noted in this class's
    // Javadoc. encodeStart() always emits the majority-observed 0x01 there, so that sample
    // is deliberately not asserted against byte-for-byte; see crcIsValidOnTheOneAnomalousSample
    // below for its own CRC check instead.
    @Test
    public void crcIsValidOnTheOneAnomalousSample() {
        byte[] real = fromHex("AA090201F31C01D010100100726F");
        int crc = Crc16.compute(real, 0, real.length - 2);
        assertEquals(Crc16.readLittleEndian(real, real.length - 2), crc);
    }

    @Test
    public void matchesRealCapturedStartFrame_counter0xF31D() {
        assertArrayEquals(
                fromHex("AA090201F31D01D01010010132C7"),
                ChannelStreamControl.encodeStart(0xF31D));
    }

    @Test
    public void matchesRealCapturedStopFrame_counter0xF315() {
        assertArrayEquals(
                fromHex("AA090201F31500D0101001013F11"),
                ChannelStreamControl.encodeStop(0xF315));
    }

    @Test
    public void matchesRealCapturedStopFrame_counter0xF317() {
        assertArrayEquals(
                fromHex("AA090201F31700D010100101DC71"),
                ChannelStreamControl.encodeStop(0xF317));
    }

    @Test
    public void frameLengthIsAlways14Bytes() {
        assertEquals(14, ChannelStreamControl.encodeStart(0).length);
        assertEquals(14, ChannelStreamControl.encodeStop(0).length);
    }
}
