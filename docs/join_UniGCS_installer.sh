#!/bin/bash
# Reassembles the UniGCS Windows installer from the parts produced by
# split_UniGCS_installer.sh (plain concatenation -- see that script for why
# this isn't zip-based).

set -e

OUTPUT="UniGCS-3_0_1-v527b7522-setup_48fbf8e8.exe"
PARTS=("${OUTPUT}".*.part)

if [ ! -e "${PARTS[0]}" ]; then
    echo "Error: no '${OUTPUT}.*.part' files found in $(pwd)"
    exit 1
fi

echo "Reassembling ${#PARTS[@]} part(s) into '$OUTPUT'..."
cat "${OUTPUT}".*.part > "$OUTPUT"

echo "✓ Done."
ls -lh "$OUTPUT"
