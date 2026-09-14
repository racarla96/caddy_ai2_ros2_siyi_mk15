package com.caddyai2.siyimk15teleop.diagnostics;

import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.Comparator;
import java.util.Date;
import java.util.Deque;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

/**
 * Shared, thread-safe diagnostic counters/log, written by {@code MainActivity} (from
 * {@code SiyiSerialReader}'s background read thread) and read by {@code DiagnosticsActivity}
 * (2026-09-14: split into its own screen — see README — so the main screen only shows the
 * live command, not a raw protocol dump). A process-wide singleton rather than passed through
 * an Intent: the two activities need to see the *same live-updating* counters while both may
 * be on screen (e.g. after "back"), not a one-time snapshot handed over at launch.
 *
 * <p>{@code DiagnosticsActivity} polls {@link #snapshot()} on a timer rather than being pushed
 * updates — simpler than wiring a cross-activity listener for a screen that's just a periodic
 * read-only view, and cheap enough at a few times a second.
 */
public final class DiagnosticsState {

    private static final DiagnosticsState INSTANCE = new DiagnosticsState();

    public static DiagnosticsState getInstance() {
        return INSTANCE;
    }

    private static final int MAX_UNKNOWN_GROUPS_SHOWN = 5;
    private static final int MAX_LOG_LINES = 200;

    private final AtomicLong validFrames = new AtomicLong();
    private final AtomicLong crcErrors = new AtomicLong();
    private final AtomicLong resyncs = new AtomicLong();
    private final Map<Integer, AtomicLong> unknownFrameCounts = new ConcurrentHashMap<>();

    private final Object logLock = new Object();
    private final Deque<String> logLines = new ArrayDeque<>();
    private final SimpleDateFormat logTimeFormat = new SimpleDateFormat("HH:mm:ss", Locale.getDefault());

    private volatile String connectionDetail = "";
    private volatile String rawChannelsText = "";

    private DiagnosticsState() {
    }

    public void recordValidFrame() {
        validFrames.incrementAndGet();
    }

    public void recordCrcError() {
        crcErrors.incrementAndGet();
    }

    public void recordResync() {
        resyncs.incrementAndGet();
    }

    /** @return true the first time this (type, subId) pair is seen. */
    public boolean recordUnknownFrame(int type, int subId) {
        int key = ((type & 0xFF) << 8) | (subId & 0xFF);
        unknownFrameCounts.computeIfAbsent(key, k -> new AtomicLong()).incrementAndGet();
        return unknownFrameCounts.get(key).get() == 1;
    }

    public void setConnectionDetail(String detail) {
        connectionDetail = detail;
    }

    public void setRawChannelsText(String text) {
        rawChannelsText = text;
    }

    /** Thread-safe from any thread (unlike the old MainActivity version, which required the
     * UI thread) -- callers no longer need to hop threads just to log something. */
    public void appendLog(String line) {
        synchronized (logLock) {
            logLines.addLast(logTimeFormat.format(new Date()) + "  " + line);
            while (logLines.size() > MAX_LOG_LINES) {
                logLines.removeFirst();
            }
        }
    }

    public static final class Snapshot {
        public final long validFrames;
        public final long crcErrors;
        public final long resyncs;
        public final String unknownFramesSummary;
        public final String connectionDetail;
        public final String rawChannelsText;
        public final String logText;

        private Snapshot(long validFrames, long crcErrors, long resyncs, String unknownFramesSummary,
                          String connectionDetail, String rawChannelsText, String logText) {
            this.validFrames = validFrames;
            this.crcErrors = crcErrors;
            this.resyncs = resyncs;
            this.unknownFramesSummary = unknownFramesSummary;
            this.connectionDetail = connectionDetail;
            this.rawChannelsText = rawChannelsText;
            this.logText = logText;
        }
    }

    public Snapshot snapshot() {
        String logTextCopy;
        synchronized (logLock) {
            logTextCopy = String.join("\n", logLines);
        }
        return new Snapshot(
                validFrames.get(), crcErrors.get(), resyncs.get(),
                summarizeUnknownFrames(), connectionDetail, rawChannelsText, logTextCopy);
    }

    private String summarizeUnknownFrames() {
        if (unknownFrameCounts.isEmpty()) {
            return null;
        }
        return unknownFrameCounts.entrySet().stream()
                .sorted(Comparator.comparingLong((Map.Entry<Integer, AtomicLong> e) -> e.getValue().get()).reversed())
                .limit(MAX_UNKNOWN_GROUPS_SHOWN)
                .map(e -> String.format(Locale.getDefault(), "type=0x%02x sub_id=0x%02x x%d",
                        (e.getKey() >> 8) & 0xFF, e.getKey() & 0xFF, e.getValue().get()))
                .collect(Collectors.joining("\n"));
    }
}
