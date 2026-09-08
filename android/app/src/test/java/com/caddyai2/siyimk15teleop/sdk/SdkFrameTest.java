package com.caddyai2.siyimk15teleop.sdk;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class SdkFrameTest {

    static byte[] hex(String hex) {
        String clean = hex.replace(" ", "");
        byte[] out = new byte[clean.length() / 2];
        for (int i = 0; i < out.length; i++) {
            out[i] = (byte) Integer.parseInt(clean.substring(i * 2, i * 2 + 2), 16);
        }
        return out;
    }

    /** Manual section 4.8.2, CMD_ID 0x47 "Request Firmware Version" example request. */
    @Test
    public void encodesFirmwareVersionRequestMatchingManualExample() {
        byte[] expected = hex("55 66 01 00 00 00 00 47 66 ec");

        byte[] actual = SdkFrame.encodeRequest(FirmwareVersion.CMD_ID);

        assertArrayEquals(expected, actual);
    }

    @Test
    public void encodeRoundTripsThroughTheParser() {
        byte[] payload = {0x01, 0x02, 0x03, 0x04, 0x05};
        byte[] wire = SdkFrame.encode(SdkFrame.CTRL_NEED_ACK, 42, 0x7A, payload);

        List<SdkFrame> frames = new ArrayList<>();
        SdkFrameParser parser = new SdkFrameParser(frame -> frames.add(frame));
        parser.feed(wire, 0, wire.length);

        assertEquals(1, frames.size());
        SdkFrame frame = frames.get(0);
        assertEquals(SdkFrame.CTRL_NEED_ACK, frame.ctrl);
        assertEquals(42, frame.seq);
        assertEquals(0x7A, frame.cmdId);
        assertArrayEquals(payload, frame.data);
        assertTrue(!frame.isAck());
    }
}
