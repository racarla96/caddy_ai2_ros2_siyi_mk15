package com.caddyai2.siyimk15teleop.sdk;

import org.junit.Test;

import static com.caddyai2.siyimk15teleop.sdk.SdkFrameTest.hex;
import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;

public class FirmwareVersionTest {

    /** Manual section 4.8.2's own worked example: "0x00 0x03 0x05 0x68, the product ID
     * is 0x68, and the version number is 5.3.0." Decodes all four fields from that same
     * example ACK payload. */
    @Test
    public void decodesTheManualsWorkedExample() {
        byte[] payload = hex("00 03 05 68" + "07 02 05 69" + "02 02 00 56" + "02 02 00 56");
        SdkFrame frame = new SdkFrame(SdkFrame.CTRL_ACK_PACK, 2, FirmwareVersion.CMD_ID, payload);

        FirmwareVersion version = FirmwareVersion.decode(frame);

        assertVersion(version.rcVersion, 0x68, 5, 3, 0);
        assertVersion(version.rfVersion, 0x69, 5, 2, 7);
        assertVersion(version.groundVersion, 0x56, 0, 2, 2);
        assertVersion(version.skyVersion, 0x56, 0, 2, 2);
    }

    @Test
    public void encodeRequestMatchesTheManualsExample() {
        assertArrayEquals(hex("55 66 01 00 00 00 00 47 66 ec"), FirmwareVersion.encodeRequest());
    }

    @Test(expected = IllegalArgumentException.class)
    public void decodeRejectsAFrameWithTheWrongCmdId() {
        SdkFrame frame = new SdkFrame(SdkFrame.CTRL_ACK_PACK, 0, 0x99, new byte[16]);

        FirmwareVersion.decode(frame);
    }

    @Test(expected = IllegalArgumentException.class)
    public void decodeRejectsAFrameWithTheWrongPayloadLength() {
        SdkFrame frame = new SdkFrame(SdkFrame.CTRL_ACK_PACK, 0, FirmwareVersion.CMD_ID, new byte[4]);

        FirmwareVersion.decode(frame);
    }

    private static void assertVersion(FirmwareVersion.Version version, int productId, int major, int minor, int patch) {
        assertEquals(productId, version.productId);
        assertEquals(major, version.major);
        assertEquals(minor, version.minor);
        assertEquals(patch, version.patch);
    }
}
