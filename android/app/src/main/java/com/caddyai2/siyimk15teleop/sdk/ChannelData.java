package com.caddyai2.siyimk15teleop.sdk;

/**
 * {@code CMD_ID 0x42} ("Request Channel Data"), per MK15_User_Manual_v1_9 section 4.8.2
 * — the actual goal of the {@code sdk} package (see {@link FirmwareVersion}'s Javadoc for
 * why that command was implemented first). Requests the handset stream its 16 joystick
 * channels over {@code /dev/ttyHS0} at a chosen frequency; the ACK carries one snapshot.
 *
 * <p>Request: one byte, {@link Frequency#code}. ACK: 16 consecutive {@code int16_t}
 * little-endian channel values, CH1..CH16 at array indices 0..15, default range
 * ~1050-1950 per the manual (not yet re-confirmed against a real ACK — see PROTOCOL.md).
 *
 * <p><b>Likely manual erratum, same class as {@link FirmwareVersion}'s:</b> the manual's
 * worked ACK example for this command ({@code 55 66 00 20 00 99 00 42 ...32 bytes...
 * ff 88}) has a CRC16 that does not check out against the documented algorithm (computed
 * {@code 0x8be2} vs. the example's printed {@code 0x88ff}) — recomputed independently
 * while writing this class, not carried over from the earlier {@code 0x47} finding. Both
 * *request* examples for this command ({@code 02 b5 c0} for 4Hz, {@code 00 f7 e0} for OFF)
 * check out exactly, matching the pattern seen with {@code 0x47}: request examples in this
 * manual are trustworthy, response/ACK examples have at least one transcription slip each.
 * {@link SdkFrame}/{@link SdkFrameParser}'s algorithm is trusted over the printed ACK CRC
 * for the same reasons documented on {@link FirmwareVersion}; {@code ChannelDataTest}
 * decodes the example ACK's payload directly (bypassing frame-level CRC) rather than
 * asserting on its printed CRC bytes.
 */
public final class ChannelData {

    public static final int CMD_ID = 0x42;
    public static final int CHANNEL_COUNT = 16;
    private static final int EXPECTED_PAYLOAD_LEN = CHANNEL_COUNT * 2;

    /** The request's only field: how often the handset should report channel data. */
    public enum Frequency {
        OFF(0), HZ_2(1), HZ_4(2), HZ_5(3), HZ_10(4), HZ_20(5), HZ_50(6), HZ_100(7);

        public final int code;

        Frequency(int code) {
            this.code = code;
        }
    }

    /** Raw channel values, CH1..CH16 at indices 0..15, signed per the manual's int16_t. */
    public final int[] channels;

    private ChannelData(int[] channels) {
        this.channels = channels;
    }

    /** Builds the request frame bytes asking for channel data at {@code freq}. */
    public static byte[] encodeRequest(Frequency freq) {
        return SdkFrame.encodeRequest(CMD_ID, new byte[] {(byte) freq.code});
    }

    /** Decodes an ACK frame's 32-byte payload into 16 channel values. */
    public static ChannelData decode(SdkFrame frame) {
        if (frame.cmdId != CMD_ID) {
            throw new IllegalArgumentException(
                    "Not a channel-data frame: cmdId=0x" + Integer.toHexString(frame.cmdId));
        }
        if (frame.data.length != EXPECTED_PAYLOAD_LEN) {
            throw new IllegalArgumentException(
                    "Unexpected channel-data payload length: " + frame.data.length
                            + " (expected " + EXPECTED_PAYLOAD_LEN + ")");
        }
        int[] channels = new int[CHANNEL_COUNT];
        for (int i = 0; i < CHANNEL_COUNT; i++) {
            int lo = frame.data[i * 2] & 0xFF;
            int hi = frame.data[i * 2 + 1] & 0xFF;
            channels[i] = (short) ((hi << 8) | lo);
        }
        return new ChannelData(channels);
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder("ChannelData{");
        for (int i = 0; i < channels.length; i++) {
            if (i > 0) {
                sb.append(", ");
            }
            sb.append("CH").append(i + 1).append('=').append(channels[i]);
        }
        return sb.append('}').toString();
    }
}
