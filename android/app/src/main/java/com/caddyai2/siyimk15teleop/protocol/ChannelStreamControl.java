package com.caddyai2.siyimk15teleop.protocol;

/**
 * Builds the write-direction control frame that starts/stops {@code ttyHS1}'s
 * joystick-channel stream ({@link FrameCatalog#CHANNELS}, {@code type=0x20,
 * sub_id=0x01}) — the same frame the vendor "SIYI TX" app sends when its own
 * channel-data screen opens/closes. Traced by decompile (2026-09-11) and then
 * confirmed on the real wire the same day: a background raw {@code ttyHS1}
 * capture plus a parallel {@code adb logcat} (grepped for the {@code
 * WriteTask} tag, which logs the app's own outgoing writes as hex) caught 5
 * real, CRC-valid examples while the vendor app's channel screen was opened
 * and closed. See PROTOCOL.md's "Confirmed on the wire" section for the full
 * byte-level derivation.
 *
 * <p><b>This is a different sync than {@link FrameParser}'s read-direction
 * frames.</b> The channel-data frames this app reads use sync {@code AA 0A
 * 02}; this write-direction control frame uses {@code AA 09 02}:
 * <pre>
 *   AA 09 02 | 01 | counter(2) | start:0x01/stop:0x00 | D0 10 10 01 01 | CRC16_LE(2)
 * </pre>
 * Byte 3 ({@code 0x01}) marks this as a "command, ack requested" write — the
 * RCU's own periodic heartbeat write reuses the same {@code AA 09 02} sync
 * with byte 3 {@code 0x00} and a shorter body, not reproduced here since this
 * class only needs the start/stop command. Bytes 7-10 ({@code D0 10 10 01})
 * were constant across every captured sample and are plausibly the
 * {@code dest=RCU}/{@code CMD_ID=0x01} fields from the abstract request layer
 * the vendor app builds this from; byte 11 was {@code 0x01} in 4 of 5 real
 * samples and {@code 0x00} in one, unexplained — this class always emits the
 * majority value. None of this sub-byte mapping was pinned down further, and
 * doesn't need to be to replicate the write byte-for-byte.
 *
 * <p>The 2-byte counter's semantics weren't reverse-engineered either — real
 * captures show it running across separate start/stop calls and app
 * restarts, not resetting per pair or matching any obvious sequence, and the
 * RCU accepted every value tried. Treated here as an opaque nonce: callers
 * pass whatever they like (e.g. a millisecond clock truncated to 16 bits).
 */
public final class ChannelStreamControl {

    private static final byte[] SYNC = {(byte) 0xAA, 0x09, 0x02};
    private static final byte NEEDS_ACK = 0x01;
    private static final byte[] FIXED_TAIL = {(byte) 0xD0, 0x10, 0x10, 0x01, 0x01};
    private static final int FRAME_LEN = 14; // 12-byte body + 2-byte CRC

    private ChannelStreamControl() {
    }

    /** Builds the "start streaming" (payload {@code 0x01}) request frame. */
    public static byte[] encodeStart(int counter) {
        return encode(counter, true);
    }

    /** Builds the "stop streaming" (payload {@code 0x00}) request frame. */
    public static byte[] encodeStop(int counter) {
        return encode(counter, false);
    }

    private static byte[] encode(int counter, boolean start) {
        byte[] frame = new byte[FRAME_LEN];
        int i = 0;
        frame[i++] = SYNC[0];
        frame[i++] = SYNC[1];
        frame[i++] = SYNC[2];
        frame[i++] = NEEDS_ACK;
        frame[i++] = (byte) ((counter >> 8) & 0xFF);
        frame[i++] = (byte) (counter & 0xFF);
        frame[i++] = (byte) (start ? 0x01 : 0x00);
        System.arraycopy(FIXED_TAIL, 0, frame, i, FIXED_TAIL.length);
        i += FIXED_TAIL.length;
        int crc = Crc16.compute(frame, 0, i);
        Crc16.writeLittleEndian(frame, i, crc);
        return frame;
    }
}
