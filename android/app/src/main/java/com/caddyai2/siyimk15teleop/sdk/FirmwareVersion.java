package com.caddyai2.siyimk15teleop.sdk;

/**
 * {@code CMD_ID 0x47} ("Request Firmware Version"), per MK15_User_Manual_v1_9 section
 * 4.8.2. Chosen as the first SDK command to implement: it needs no payload, so it's
 * the simplest possible round-trip to validate the {@link SdkFrame}/{@link
 * SdkFrameParser} framing + CRC end-to-end on real hardware before building anything
 * that depends on it (like {@code CMD_ID 0x42}, "Request Channel Data" — the actual
 * goal, see PROTOCOL.md's "Open question").
 *
 * <p>Request: empty payload. ACK: four {@code uint32_t} fields, each packing a
 * product ID and a major.minor.patch version:
 * <pre>
 *   byte[0] = patch, byte[1] = minor, byte[2] = major, byte[3] = product ID
 * </pre>
 * per the manual's own wording ("the first byte is the product ID, and the remaining
 * three bytes are the version number... first byte in the low bit") and confirmed
 * against its worked example ({@code 00 03 05 68} -&gt; product {@code 0x68}, version
 * 5.3.0 -- see {@code FirmwareVersionTest}).
 *
 * <p><b>Manual erratum found while implementing this:</b> the manual's own worked ACK
 * example ({@code 55 66 02 10 00 02 00 47 ...0x16 bytes... 6d 21}) has a CRC16 that
 * does not check out against the CRC16 algorithm documented two pages earlier in the
 * same manual (section 4.8.4) -- computed CRC is {@code 0x616d}, the example prints
 * {@code 0x216d} (low byte matches, high byte doesn't -- looks like a transcription
 * slip in the PDF, not an algorithm difference). The *request* half of the same
 * example ({@code 55 66 01 00 00 00 00 47 66 ec}) checks out exactly, and so does
 * every byte of live hardware traffic CRC-checked earlier in this investigation (see
 * the project memory / PROTOCOL.md), so {@link SdkFrame}/{@link SdkFrameParser} use
 * the algorithm as documented; the test fixtures for this class use the
 * correctly-recomputed CRC for that one example rather than the manual's printed
 * (likely erroneous) bytes.
 */
public final class FirmwareVersion {

    public static final int CMD_ID = 0x47;
    private static final int FIELD_LEN = 4;
    private static final int EXPECTED_PAYLOAD_LEN = FIELD_LEN * 4;

    /** Remote controller function firmware version. */
    public final Version rcVersion;
    /** Air unit function firmware version. */
    public final Version rfVersion;
    /** Remote controller FPV (image transmission) firmware version. */
    public final Version groundVersion;
    /** Air unit FPV (image transmission) firmware version. */
    public final Version skyVersion;

    private FirmwareVersion(Version rcVersion, Version rfVersion, Version groundVersion, Version skyVersion) {
        this.rcVersion = rcVersion;
        this.rfVersion = rfVersion;
        this.groundVersion = groundVersion;
        this.skyVersion = skyVersion;
    }

    /** Builds the (empty-payload) request frame bytes for {@link #CMD_ID}. */
    public static byte[] encodeRequest() {
        return SdkFrame.encodeRequest(CMD_ID);
    }

    /** Decodes an ACK frame's payload. */
    public static FirmwareVersion decode(SdkFrame frame) {
        if (frame.cmdId != CMD_ID) {
            throw new IllegalArgumentException(
                    "Not a firmware-version frame: cmdId=0x" + Integer.toHexString(frame.cmdId));
        }
        if (frame.data.length != EXPECTED_PAYLOAD_LEN) {
            throw new IllegalArgumentException(
                    "Unexpected firmware-version payload length: " + frame.data.length
                            + " (expected " + EXPECTED_PAYLOAD_LEN + ")");
        }
        return new FirmwareVersion(
                Version.decode(frame.data, 0),
                Version.decode(frame.data, FIELD_LEN),
                Version.decode(frame.data, FIELD_LEN * 2),
                Version.decode(frame.data, FIELD_LEN * 3));
    }

    @Override
    public String toString() {
        return "FirmwareVersion{rc=" + rcVersion + ", rf=" + rfVersion
                + ", ground=" + groundVersion + ", sky=" + skyVersion + "}";
    }

    /** One product-ID + major.minor.patch field, per the class-level encoding note. */
    public static final class Version {
        public final int productId;
        public final int major;
        public final int minor;
        public final int patch;

        private Version(int productId, int major, int minor, int patch) {
            this.productId = productId;
            this.major = major;
            this.minor = minor;
            this.patch = patch;
        }

        static Version decode(byte[] data, int offset) {
            int patch = data[offset] & 0xFF;
            int minor = data[offset + 1] & 0xFF;
            int major = data[offset + 2] & 0xFF;
            int productId = data[offset + 3] & 0xFF;
            return new Version(productId, major, minor, patch);
        }

        @Override
        public String toString() {
            return major + "." + minor + "." + patch + " (product 0x" + Integer.toHexString(productId) + ")";
        }
    }
}
