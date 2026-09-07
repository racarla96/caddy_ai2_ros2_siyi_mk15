package com.caddyai2.siyimk15teleop.protocol;

import org.junit.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.Assert.assertEquals;

public class Crc16Test {

    @Test
    public void knownXmodemTestVector() {
        // Standard CRC-16/XMODEM check value (poly 0x1021, init 0x0000, no reflect,
        // no xor-out) for the ASCII string "123456789" is 0x31C3. This is the
        // canonical way to confirm the algorithm itself is implemented correctly,
        // independent of the SIYI framing.
        byte[] data = "123456789".getBytes(StandardCharsets.US_ASCII);
        assertEquals(0x31C3, Crc16.compute(data));
    }

    @Test
    public void emptyInputIsZero() {
        assertEquals(0x0000, Crc16.compute(new byte[0]));
    }

    @Test
    public void offsetAndLengthAreRespected() {
        byte[] padded = new byte[] {0x00, 0x00, '1', '2', '3', '4', '5', '6', '7', '8', '9', 0x00};
        assertEquals(0x31C3, Crc16.compute(padded, 2, 9));
    }

    @Test
    public void littleEndianRoundTrip() {
        byte[] buf = new byte[2];
        Crc16.writeLittleEndian(buf, 0, 0x31C3);
        assertEquals((byte) 0xC3, buf[0]);
        assertEquals((byte) 0x31, buf[1]);
        assertEquals(0x31C3, Crc16.readLittleEndian(buf, 0));
    }
}
