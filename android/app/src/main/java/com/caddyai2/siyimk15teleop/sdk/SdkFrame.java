package com.caddyai2.siyimk15teleop.sdk;

import com.caddyai2.siyimk15teleop.protocol.Crc16;

/**
 * A single frame of the official SIYI Datalink SDK protocol (MK15_User_Manual_v1_9,
 * section 4.8.1) — <b>not</b> the internal joystick-channel protocol in
 * {@code com.caddyai2.siyimk15teleop.protocol} (see PROTOCOL.md's "Open question").
 * This is the documented, request/response protocol meant for third-party GCS
 * integration, spoken on <b>{@code /dev/ttyHS0}</b> at 115200 baud, and only active
 * once "Datalink → Connection → UART" is selected in the vendor's own "SIYI TX" app
 * ({@code biz.siyi.remotecontrol}) — see that class's caller for details.
 *
 * <pre>
 *   STX(2)=0x55 0x66 | CTRL(1) | Data_len(2 LE) | SEQ(2 LE) | CMD_ID(1) | DATA(Data_len) | CRC16(2 LE)
 * </pre>
 *
 * Unlike the internal joystick protocol, the frame length is carried explicitly
 * ({@code Data_len}), so {@link SdkFrameParser} doesn't need a length catalog. CRC16 is
 * the same algorithm as {@link Crc16} (poly 0x1021, init 0, not reflected, no final
 * XOR, transmitted little-endian) — confirmed against the manual's own worked
 * example for {@code CMD_ID 0x47} (see {@link FirmwareVersion}), and independently
 * against real captured hardware bytes off the *other* protocol during the same
 * investigation, so this is the project's one CRC implementation reused here, not a
 * second copy.
 */
public final class SdkFrame {

    private static final byte STX_HI = 0x55;
    private static final byte STX_LO = 0x66;

    /** CTRL bit: this packet wants an ack in response. */
    public static final int CTRL_NEED_ACK = 0x01;
    /** CTRL bit: this packet *is* an ack. */
    public static final int CTRL_ACK_PACK = 0x02;

    private static final int HEADER_LEN = 8; // STX(2)+CTRL(1)+Data_len(2)+SEQ(2)+CMD_ID(1)
    private static final int CRC_LEN = 2;

    public final int ctrl;
    public final int seq;
    public final int cmdId;
    public final byte[] data;

    SdkFrame(int ctrl, int seq, int cmdId, byte[] data) {
        this.ctrl = ctrl;
        this.seq = seq;
        this.cmdId = cmdId;
        this.data = data;
    }

    public boolean isAck() {
        return (ctrl & CTRL_ACK_PACK) != 0;
    }

    /** Builds the raw wire bytes for an outgoing frame, CRC16 included. */
    public static byte[] encode(int ctrl, int seq, int cmdId, byte[] data) {
        int dataLen = data.length;
        int total = HEADER_LEN + dataLen + CRC_LEN;
        byte[] out = new byte[total];
        out[0] = STX_HI;
        out[1] = STX_LO;
        out[2] = (byte) ctrl;
        out[3] = (byte) (dataLen & 0xFF);
        out[4] = (byte) ((dataLen >> 8) & 0xFF);
        out[5] = (byte) (seq & 0xFF);
        out[6] = (byte) ((seq >> 8) & 0xFF);
        out[7] = (byte) cmdId;
        System.arraycopy(data, 0, out, HEADER_LEN, dataLen);
        int crc = Crc16.compute(out, 0, HEADER_LEN + dataLen);
        out[HEADER_LEN + dataLen] = (byte) (crc & 0xFF);
        out[HEADER_LEN + dataLen + 1] = (byte) ((crc >> 8) & 0xFF);
        return out;
    }

    /** Convenience for the common case: a no-payload request that wants an ack, seq 0. */
    public static byte[] encodeRequest(int cmdId) {
        return encode(CTRL_NEED_ACK, 0, cmdId, new byte[0]);
    }

    /** Convenience for a request with a payload, wants an ack, seq 0. */
    public static byte[] encodeRequest(int cmdId, byte[] data) {
        return encode(CTRL_NEED_ACK, 0, cmdId, data);
    }

    @Override
    public String toString() {
        return "SdkFrame{ctrl=0x" + Integer.toHexString(ctrl)
                + ", seq=" + seq
                + ", cmdId=0x" + Integer.toHexString(cmdId)
                + ", dataLen=" + data.length + "}";
    }
}
