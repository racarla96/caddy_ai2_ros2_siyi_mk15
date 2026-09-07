package com.caddyai2.siyimk15teleop.protocol;

import org.junit.Before;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class FrameParserTest {

    /**
     * Fixture matching the framing description exactly: 45-byte channel frame,
     * all 16 channels centered at 1500 except CH1=1000 (steer full-left) and
     * CH3=2000 (throttle full-forward), reproducing the manual single-axis-at-a-time
     * capture method described when the mapping was reverse-engineered.
     */
    private static int[] centeredChannelsWith(int ch1, int ch3) {
        int[] channels = new int[16];
        for (int i = 0; i < 16; i++) {
            channels[i] = 1500;
        }
        channels[0] = ch1;
        channels[2] = ch3;
        return channels;
    }

    private static final class RecordingListener implements FrameParser.Listener {
        final List<DecodedFrame> frames = new ArrayList<>();
        int resyncs = 0;
        int crcErrors = 0;
        final List<int[]> unknown = new ArrayList<>();

        @Override
        public void onFrame(DecodedFrame frame) {
            frames.add(frame);
        }

        @Override
        public void onResync() {
            resyncs++;
        }

        @Override
        public void onUnknownFrame(int type, int subId) {
            unknown.add(new int[] {type, subId});
        }

        @Override
        public void onCrcError(int type, int subId) {
            crcErrors++;
        }
    }

    private RecordingListener listener;
    private FrameParser parser;

    @Before
    public void setUp() {
        listener = new RecordingListener();
        parser = new FrameParser(listener);
    }

    @Test
    public void decodesASingleValidChannelFrame() {
        byte[] frame = TestFrameBuilder.channelFrame(1, centeredChannelsWith(1000, 2000));

        parser.feed(frame, 0, frame.length);

        assertEquals(1, listener.frames.size());
        DecodedFrame decoded = listener.frames.get(0);
        assertTrue(decoded.isChannelFrame());
        int[] channels = decoded.decodeChannels();
        assertEquals(1000, channels[ChannelMapper.CHANNEL_INDEX_STEER]);
        assertEquals(2000, channels[ChannelMapper.CHANNEL_INDEX_THROTTLE]);
        assertEquals(0, listener.crcErrors);
        assertEquals(0, listener.resyncs);
    }

    @Test
    public void decodesWhenFedOneByteAtATime() {
        byte[] frame = TestFrameBuilder.channelFrame(7, centeredChannelsWith(1234, 1888));

        for (byte b : frame) {
            parser.feed(new byte[] {b}, 0, 1);
        }

        assertEquals(1, listener.frames.size());
        int[] channels = listener.frames.get(0).decodeChannels();
        assertEquals(1234, channels[ChannelMapper.CHANNEL_INDEX_STEER]);
        assertEquals(1888, channels[ChannelMapper.CHANNEL_INDEX_THROTTLE]);
    }

    @Test
    public void decodesTwoBackToBackFrames() {
        byte[] f1 = TestFrameBuilder.channelFrame(1, centeredChannelsWith(1000, 1500));
        byte[] f2 = TestFrameBuilder.channelFrame(2, centeredChannelsWith(2000, 1500));
        byte[] both = new byte[f1.length + f2.length];
        System.arraycopy(f1, 0, both, 0, f1.length);
        System.arraycopy(f2, 0, both, f1.length, f2.length);

        parser.feed(both, 0, both.length);

        assertEquals(2, listener.frames.size());
        assertEquals(1000, listener.frames.get(0).decodeChannels()[ChannelMapper.CHANNEL_INDEX_STEER]);
        assertEquals(2000, listener.frames.get(1).decodeChannels()[ChannelMapper.CHANNEL_INDEX_STEER]);
    }

    @Test
    public void rejectsCorruptedCrcAndDoesNotEmitFrame() {
        byte[] frame = TestFrameBuilder.channelFrame(1, centeredChannelsWith(1500, 1500));
        frame[frame.length - 1] ^= 0xFF; // flip the high CRC byte

        parser.feed(frame, 0, frame.length);

        assertEquals(0, listener.frames.size());
        assertTrue(listener.crcErrors >= 1);
    }

    @Test
    public void resyncsPastGarbageContainingAFalseSyncMarker() {
        // A stray AA 0A 02 in noise that isn't followed by the fixed middle bytes,
        // immediately followed by one genuine channel frame.
        byte[] garbage = {0x11, 0x22, (byte) 0xAA, 0x0A, 0x02, 0x33, 0x44};
        byte[] real = TestFrameBuilder.channelFrame(1, centeredChannelsWith(1600, 1400));
        byte[] both = new byte[garbage.length + real.length];
        System.arraycopy(garbage, 0, both, 0, garbage.length);
        System.arraycopy(real, 0, both, garbage.length, real.length);

        parser.feed(both, 0, both.length);

        assertEquals(1, listener.frames.size());
        assertTrue(listener.resyncs >= 1);
        assertEquals(1600, listener.frames.get(0).decodeChannels()[ChannelMapper.CHANNEL_INDEX_STEER]);
    }

    @Test
    public void reportsUnknownTypeSubIdWithoutBlockingSubsequentFrames() {
        byte[] unknown = TestFrameBuilder.frame(0x7F, 0x7F, 1, new byte[] {0x01, 0x02, 0x03});
        byte[] real = TestFrameBuilder.channelFrame(2, centeredChannelsWith(1500, 1700));
        byte[] both = new byte[unknown.length + real.length];
        System.arraycopy(unknown, 0, both, 0, unknown.length);
        System.arraycopy(real, 0, both, unknown.length, real.length);

        parser.feed(both, 0, both.length);

        assertEquals(1, listener.unknown.size());
        assertArrayEquals(new int[] {0x7F, 0x7F}, listener.unknown.get(0));
        assertEquals(1, listener.frames.size());
        assertEquals(1700, listener.frames.get(0).decodeChannels()[ChannelMapper.CHANNEL_INDEX_THROTTLE]);
    }

    @Test
    public void decodesKnownButtonAndTelemetryFrameLengthsWithoutCrashing() {
        byte[] buttons = TestFrameBuilder.frame(
                FrameCatalog.BUTTONS.type, FrameCatalog.BUTTONS.subId, 1, new byte[16]);
        byte[] telemetry = TestFrameBuilder.frame(
                FrameCatalog.TELEMETRY.type, FrameCatalog.TELEMETRY.subId, 2, new byte[96]);

        parser.feed(buttons, 0, buttons.length);
        parser.feed(telemetry, 0, telemetry.length);

        assertEquals(2, listener.frames.size());
        assertTrue(!listener.frames.get(0).isChannelFrame());
        assertTrue(!listener.frames.get(1).isChannelFrame());
    }
}
