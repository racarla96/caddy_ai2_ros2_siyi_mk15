package com.caddyai2.siyimk15teleop.sdk;

import org.junit.Before;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static com.caddyai2.siyimk15teleop.sdk.SdkFrameTest.hex;
import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class SdkFrameParserTest {

    static final class RecordingListener implements SdkFrameParser.Listener {
        final List<SdkFrame> frames = new ArrayList<>();
        int resyncs = 0;
        int crcErrors = 0;

        @Override
        public void onFrame(SdkFrame frame) {
            frames.add(frame);
        }

        @Override
        public void onResync() {
            resyncs++;
        }

        @Override
        public void onCrcError(int cmdId) {
            crcErrors++;
        }
    }

    /**
     * Manual section 4.8.2's worked ACK example for CMD_ID 0x47, with the CRC16
     * recomputed -- see {@link FirmwareVersion}'s class Javadoc for why: the manual's
     * own printed CRC ({@code 6d 21}) doesn't check out against its own documented
     * algorithm, off by one byte ({@code 0x216d} printed vs {@code 0x616d} computed).
     */
    private static final byte[] FIRMWARE_ACK_RESPONSE = hex(
            "55 66 02 10 00 02 00 47"
                    + "00 03 05 68 07 02 05 69 02 02 00 56 02 02 00 56"
                    + "6d 61"); // CRC16 LE for 0x616d, corrected from the manual's 0x216d

    private RecordingListener listener;
    private SdkFrameParser parser;

    @Before
    public void setUp() {
        listener = new RecordingListener();
        parser = new SdkFrameParser(listener);
    }

    @Test
    public void decodesTheManualsFirmwareVersionAckExample() {
        parser.feed(FIRMWARE_ACK_RESPONSE, 0, FIRMWARE_ACK_RESPONSE.length);

        assertEquals(1, listener.frames.size());
        assertEquals(0, listener.crcErrors);
        SdkFrame frame = listener.frames.get(0);
        assertTrue(frame.isAck());
        assertEquals(2, frame.seq);
        assertEquals(FirmwareVersion.CMD_ID, frame.cmdId);
        assertEquals(16, frame.data.length);
    }

    @Test
    public void decodesWhenFedOneByteAtATime() {
        for (byte b : FIRMWARE_ACK_RESPONSE) {
            parser.feed(new byte[] {b}, 0, 1);
        }

        assertEquals(1, listener.frames.size());
        assertEquals(FirmwareVersion.CMD_ID, listener.frames.get(0).cmdId);
    }

    @Test
    public void rejectsCorruptedCrcAndDoesNotEmitFrame() {
        byte[] corrupted = FIRMWARE_ACK_RESPONSE.clone();
        corrupted[corrupted.length - 1] ^= 0xFF;

        parser.feed(corrupted, 0, corrupted.length);

        assertEquals(0, listener.frames.size());
        assertTrue(listener.crcErrors >= 1);
    }

    @Test
    public void resyncsPastAFalseSyncMarkerFollowedByARealFrame() {
        byte[] garbage = {0x11, 0x22, 0x55, 0x66, 0x33}; // stray "55 66" that isn't a real header
        byte[] both = new byte[garbage.length + FIRMWARE_ACK_RESPONSE.length];
        System.arraycopy(garbage, 0, both, 0, garbage.length);
        System.arraycopy(FIRMWARE_ACK_RESPONSE, 0, both, garbage.length, FIRMWARE_ACK_RESPONSE.length);

        parser.feed(both, 0, both.length);

        assertEquals(1, listener.frames.size());
        assertEquals(FirmwareVersion.CMD_ID, listener.frames.get(0).cmdId);
    }

    @Test
    public void treatsAnImplausiblyLargeDataLenAsAFalseSyncNotAStall() {
        // "55 66" followed by a Data_len that's absurd for this protocol (0xFFFF) --
        // must resync past it rather than wait forever for 65535 bytes that never come.
        byte[] garbage = hex("55 66 01 FF FF 00 00 47");
        byte[] both = new byte[garbage.length + FIRMWARE_ACK_RESPONSE.length];
        System.arraycopy(garbage, 0, both, 0, garbage.length);
        System.arraycopy(FIRMWARE_ACK_RESPONSE, 0, both, garbage.length, FIRMWARE_ACK_RESPONSE.length);

        parser.feed(both, 0, both.length);

        assertEquals(1, listener.frames.size());
        assertTrue(listener.resyncs >= 1);
    }
}
