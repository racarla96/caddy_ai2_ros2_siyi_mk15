package com.caddyai2.siyimk15teleop.sdk;

import org.junit.Test;

import static com.caddyai2.siyimk15teleop.sdk.SdkFrameTest.hex;
import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;

public class ChannelDataTest {

    /** Manual section 4.8.2's own worked request example, 4Hz. */
    @Test
    public void encodeRequestMatchesTheManualsExample4Hz() {
        assertArrayEquals(
                hex("55 66 01 01 00 00 00 42 02 B5 C0"),
                ChannelData.encodeRequest(ChannelData.Frequency.HZ_4));
    }

    /** Same example, OFF. */
    @Test
    public void encodeRequestMatchesTheManualsExampleOff() {
        assertArrayEquals(
                hex("55 66 01 01 00 00 00 42 00 F7 E0"),
                ChannelData.encodeRequest(ChannelData.Frequency.OFF));
    }

    /** Manual's worked "Response (2 Hz)" example payload — see {@link ChannelData}'s
     * Javadoc for why this decodes the payload directly rather than round-tripping
     * through the frame-level CRC (the example's printed CRC doesn't check out). */
    @Test
    public void decodesTheManualsWorkedExample() {
        byte[] payload = hex(
                "DC 05 DC 00 DC 05 DC 05 DC 05 DC 05 DC 05 DC 05 DC 05 DC 05 DC 05 1A 04 DC 05 DC 05 1A 04 1A 04");
        SdkFrame frame = new SdkFrame(0x00, 0x99, ChannelData.CMD_ID, payload);

        ChannelData data = ChannelData.decode(frame);

        assertEquals(16, data.channels.length);
        assertArrayEquals(
                new int[] {1500, 220, 1500, 1500, 1500, 1500, 1500, 1500, 1500, 1500, 1500, 1050, 1500, 1500, 1050, 1050},
                data.channels);
    }

    @Test(expected = IllegalArgumentException.class)
    public void decodeRejectsAFrameWithTheWrongCmdId() {
        SdkFrame frame = new SdkFrame(0x00, 0, 0x99, new byte[32]);

        ChannelData.decode(frame);
    }

    @Test(expected = IllegalArgumentException.class)
    public void decodeRejectsAFrameWithTheWrongPayloadLength() {
        SdkFrame frame = new SdkFrame(0x00, 0, ChannelData.CMD_ID, new byte[16]);

        ChannelData.decode(frame);
    }
}
