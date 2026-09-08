#!/bin/bash
# Splits the UniGCS Windows installer (over GitHub's 100MB single-file limit)
# into <100MB parts so it can be committed to git.
#
# Uses plain `split` (raw byte chunks), NOT a zip-based split: the installer
# is already a compressed executable, so zip buys ~0% extra compression, and
# this repo's `zip` (Info-ZIP 3.0) was found to produce a split archive that
# fails to reassemble correctly (`zip -s 0 ... --out` yields a zip with
# overlapping central-directory entries -- confirmed with both `unzip` and
# Python's `zipfile`, sha256 mismatch). `split`/`cat` has no such ambiguity:
# reassembly is just concatenation, verified here with a sha256 round-trip.

set -e

SOURCE="UniGCS-3_0_1-v527b7522-setup_48fbf8e8.exe"
PART_SIZE="50M"

if [ ! -f "$SOURCE" ]; then
    echo "Error: '$SOURCE' not found in $(pwd)"
    exit 1
fi

echo "Splitting '$SOURCE' into ${PART_SIZE} parts..."
rm -f "${SOURCE}".*.part
split -b "$PART_SIZE" -d --additional-suffix=.part -a 2 "$SOURCE" "${SOURCE}."

echo
echo "✓ Split done. Verifying round-trip with sha256..."
cat "${SOURCE}".*.part > /tmp/"${SOURCE}".verify
if sha256sum "$SOURCE" /tmp/"${SOURCE}".verify | awk '{print $1}' | uniq | wc -l | grep -q '^1$'; then
    echo "✓ sha256 matches -- parts reassemble byte-for-byte."
else
    echo "✗ sha256 MISMATCH -- do not commit these parts."
    rm -f /tmp/"${SOURCE}".verify
    exit 1
fi
rm -f /tmp/"${SOURCE}".verify

ls -lh "${SOURCE}".*.part
