package com.caddyai2.siyimk15teleop.protocol;

/**
 * CRC-16/XMODEM: polynomial 0x1021, init 0x0000, not reflected, no xor-out.
 *
 * This is the checksum used by the SIYI MK15 internal joystick-channel protocol
 * (reverse-engineered from /dev/ttyHS1 traffic — see PROTOCOL.md at the repo root).
 * It is computed over every byte of the frame except the trailing 2-byte CRC field
 * itself, and transmitted little-endian.
 *
 * Pure Java, no Android dependencies, so it can be unit-tested on the plain JVM
 * ("test" source set) without an emulator/device.
 */
public final class Crc16 {

    private static final int POLY = 0x1021;

    private Crc16() {
    }

    /** Computes the CRC over {@code length} bytes of {@code data} starting at {@code offset}. */
    public static int compute(byte[] data, int offset, int length) {
        int crc = 0x0000;
        for (int i = offset; i < offset + length; i++) {
            crc ^= (data[i] & 0xFF) << 8;
            for (int bit = 0; bit < 8; bit++) {
                if ((crc & 0x8000) != 0) {
                    crc = ((crc << 1) ^ POLY) & 0xFFFF;
                } else {
                    crc = (crc << 1) & 0xFFFF;
                }
            }
        }
        return crc & 0xFFFF;
    }

    /** Convenience overload covering the whole array. */
    public static int compute(byte[] data) {
        return compute(data, 0, data.length);
    }

    /** Reads a little-endian uint16 CRC field at {@code offset}. */
    public static int readLittleEndian(byte[] data, int offset) {
        return (data[offset] & 0xFF) | ((data[offset + 1] & 0xFF) << 8);
    }

    /** Writes {@code crc} as a little-endian uint16 at {@code offset}. */
    public static void writeLittleEndian(byte[] data, int offset, int crc) {
        data[offset] = (byte) (crc & 0xFF);
        data[offset + 1] = (byte) ((crc >> 8) & 0xFF);
    }
}
