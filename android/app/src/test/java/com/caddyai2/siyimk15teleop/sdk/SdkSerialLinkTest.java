package com.caddyai2.siyimk15teleop.sdk;

import org.junit.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.assertTrue;

public class SdkSerialLinkTest {

    /** Regression test for the on-device corruption found 2026-09-10 (see this class's
     * Javadoc): {@code iuclc}/{@code ixon}/{@code ixoff}/{@code ixany} must stay disabled,
     * and the baud rate must be {@link SdkSerialLink#BAUD_RATE}. */
    @Test
    public void sttyArgsDisablesTheFlagsThatCorruptedFrameBytesOnDevice() {
        List<String> args = Arrays.asList(SdkSerialLink.sttyArgs("/dev/ttyHS0"));

        assertTrue(args.contains("/dev/ttyHS0"));
        assertTrue(args.contains(String.valueOf(SdkSerialLink.BAUD_RATE)));
        assertTrue(args.contains("-iuclc"));
        assertTrue(args.contains("-ixon"));
        assertTrue(args.contains("-ixoff"));
        assertTrue(args.contains("-ixany"));
        assertTrue(args.contains("raw"));
    }
}
