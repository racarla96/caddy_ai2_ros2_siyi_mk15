#!/usr/bin/env python3
"""Offline analyzer for raw `/dev/ttyHS1` captures from the SIYI MK15 handset.

Usage:
    adb shell timeout 20 cat /dev/ttyHS1 > capture.bin
    python3 tools/analyze_capture.py capture.bin
    python3 tools/analyze_capture.py steer_only.bin throttle_only.bin   # diff mode

Deliberately does NOT hardcode the current app's FrameCatalog (type=0x20,
sub_id=0x01, 45 bytes) as the only valid shape: PROTOCOL.md's catalog is
itself unconfirmed against current firmware (see the "Open question" note
in that file), so this script re-derives frame boundaries from the sync
marker and CRC alone, the same way a first-principles capture analysis
should. It groups whatever frames it finds by (type, sub_id, length) and
reports them, so a run against a fresh capture tells you directly which
groups actually exist -- no assumption baked in about which one is "the"
channel frame.

Only the outer envelope is assumed fixed, per PROTOCOL.md's "Frame layout"
section (this part was CRC-confirmed on both the original and the anomalous
25-byte captures, so it's on solid footing even though the middle 4 bytes'
*value* and the (type, sub_id, length) tuple are not):

    AA 0A 02 | type(1) | seq(2 LE) | middle(4) | sub_id(1) | payload(N) | CRC16(2 LE)

i.e. an 11-byte fixed header before the payload, CRC-16/XMODEM (poly 0x1021,
init 0x0000, no reflect, no final XOR) over every byte from the sync through
the end of the payload, transmitted little-endian.
"""

import sys
from collections import defaultdict

SYNC = bytes([0xAA, 0x0A, 0x02])
HEADER_LEN = 11  # sync(3) + type(1) + seq(2) + middle(4) + sub_id(1)
CRC_LEN = 2
MIN_TOTAL_LEN = HEADER_LEN + CRC_LEN  # zero-payload frame
DEFAULT_MAX_TOTAL_LEN = 256


def crc16_xmodem(data, start, end):
    """CRC-16/XMODEM over data[start:end]: poly 0x1021, init 0, no reflect, no final xor."""
    crc = 0x0000
    for b in data[start:end]:
        crc ^= b << 8
        for _ in range(8):
            if crc & 0x8000:
                crc = ((crc << 1) ^ 0x1021) & 0xFFFF
            else:
                crc = (crc << 1) & 0xFFFF
    return crc


class Frame:
    __slots__ = ("offset", "type", "sub_id", "seq", "length", "payload", "confirmed")

    def __init__(self, offset, type_, sub_id, seq, length, payload, confirmed):
        self.offset = offset
        self.type = type_
        self.sub_id = sub_id
        self.seq = seq
        self.length = length
        self.payload = payload
        self.confirmed = confirmed


def find_candidate_lengths(data, sync, max_total_len):
    """All total-lengths L (>= MIN_TOTAL_LEN) starting at `sync` whose CRC checks out."""
    candidates = []
    limit = min(max_total_len, len(data) - sync)
    for total in range(MIN_TOTAL_LEN, limit + 1):
        crc_off = sync + total - CRC_LEN
        computed = crc16_xmodem(data, sync, crc_off)
        received = data[crc_off] | (data[crc_off + 1] << 8)
        if computed == received:
            candidates.append(total)
    return candidates


def parse_frames(data, max_total_len=DEFAULT_MAX_TOTAL_LEN):
    """Sync-scan + CRC-verify the whole buffer, byte-at-a-time resync on failure."""
    frames = []
    resyncs = 0
    cursor = 0
    n = len(data)
    while cursor + 3 <= n:
        idx = data.find(SYNC, cursor)
        if idx < 0:
            break
        if idx + HEADER_LEN + CRC_LEN > n:
            break  # not enough bytes left for even a zero-payload frame

        candidates = find_candidate_lengths(data, idx, max_total_len)
        if not candidates:
            resyncs += 1
            cursor = idx + 1
            continue

        # Prefer a length whose end is itself a sync (or end-of-buffer) -- that's
        # the strongest evidence it's the *real* frame boundary rather than a
        # coincidental CRC match on a wrong length (~1/65536 chance per try).
        chosen = None
        for total in candidates:
            end = idx + total
            if end == n or data[end:end + 3] == SYNC:
                chosen = total
                break
        confirmed = chosen is not None
        if chosen is None:
            chosen = candidates[0]  # smallest candidate, flagged unconfirmed

        type_ = data[idx + 3]
        seq = data[idx + 4] | (data[idx + 5] << 8)
        sub_id = data[idx + 10]
        payload = data[idx + HEADER_LEN: idx + chosen - CRC_LEN]
        frames.append(Frame(idx, type_, sub_id, seq, chosen, payload, confirmed))
        cursor = idx + chosen

    return frames, resyncs


def payload_u16_le(payload):
    return [payload[i] | (payload[i + 1] << 8) for i in range(0, len(payload) - 1, 2)]


def summarize(frames, resyncs, total_bytes, label):
    print(f"=== {label} ===")
    print(f"{total_bytes} bytes captured, {len(frames)} CRC-valid frames, "
          f"{resyncs} byte-at-a-time resyncs")
    if not frames:
        print("  (no frames found)")
        return {}

    groups = defaultdict(list)
    for f in frames:
        groups[(f.type, f.sub_id, f.length)].append(f)

    print(f"\n  {'type':>6} {'sub_id':>7} {'len':>5} {'count':>7} {'unconfirmed':>12}  payload uint16 fields (min..max across frames)")
    for (type_, sub_id, length), fs in sorted(groups.items(), key=lambda kv: -len(kv[1])):
        unconfirmed = sum(1 for f in fs if not f.confirmed)
        fields = [payload_u16_le(f.payload) for f in fs if len(f.payload) >= 2]
        ranges = ""
        if fields and all(len(x) == len(fields[0]) for x in fields):
            width = len(fields[0])
            mins = [min(f[i] for f in fields) for i in range(width)]
            maxs = [max(f[i] for f in fields) for i in range(width)]
            ranges = "  ".join(f"[{i}]{lo}..{hi}" for i, (lo, hi) in enumerate(zip(mins, maxs)))
        print(f"  0x{type_:04x} 0x{sub_id:05x} {length:5d} {len(fs):7d} {unconfirmed:12d}  {ranges}")
    print()
    return groups


def diff_mode(groups_a, groups_b, label_a, label_b):
    """For groups common to both captures, show which uint16 field moved the most --
    the field whose typical value differs most between the two captures is the one
    to look at for that isolated stick axis."""
    common = set(groups_a) & set(groups_b)
    if not common:
        print("No (type, sub_id, length) group is common to both captures -- "
              "nothing to diff.")
        return

    print(f"=== Diff: {label_a} vs {label_b} (common groups only) ===")
    for key in sorted(common, key=lambda k: -min(len(groups_a[k]), len(groups_b[k]))):
        type_, sub_id, length = key
        fields_a = [payload_u16_le(f.payload) for f in groups_a[key] if len(f.payload) >= 2]
        fields_b = [payload_u16_le(f.payload) for f in groups_b[key] if len(f.payload) >= 2]
        if not fields_a or not fields_b:
            continue
        width = min(len(fields_a[0]), len(fields_b[0]))
        print(f"  0x{type_:04x}/0x{sub_id:05x} len={length}:")
        diffs = []
        for i in range(width):
            mean_a = sum(f[i] for f in fields_a) / len(fields_a)
            mean_b = sum(f[i] for f in fields_b) / len(fields_b)
            diffs.append((abs(mean_a - mean_b), i, mean_a, mean_b))
        diffs.sort(reverse=True)
        for delta, i, mean_a, mean_b in diffs[:5]:
            if delta < 1:
                continue
            print(f"    field[{i}]: {label_a} mean={mean_a:.0f}  {label_b} mean={mean_b:.0f}  "
                  f"delta={delta:.0f}")
        print()


def main():
    if len(sys.argv) not in (2, 3):
        print(__doc__)
        sys.exit(1)

    paths = sys.argv[1:]
    groups_by_file = []
    for path in paths:
        with open(path, "rb") as fh:
            data = fh.read()
        frames, resyncs = parse_frames(data)
        groups = summarize(frames, resyncs, len(data), path)
        groups_by_file.append(groups)

    if len(paths) == 2:
        diff_mode(groups_by_file[0], groups_by_file[1], paths[0], paths[1])


if __name__ == "__main__":
    main()
