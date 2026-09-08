#!/bin/bash

set -e

SOURCE="UniGCS_Win"
OUTPUT="UniGCS-3_0_1-v527b7522-setup_48fbf8e8.zip"
SPLIT_SIZE="50m"

# Crear carpeta si no existe
if [ ! -d "$SOURCE" ]; then
    echo "La carpeta '$SOURCE' no existe. Creándola..."
    mkdir -p "$SOURCE"
fi

echo "Comprimiendo '$SOURCE'..."
echo "Archivo: $OUTPUT"
echo "Tamaño por parte: $SPLIT_SIZE"
echo

zip -r -s "$SPLIT_SIZE" "$OUTPUT" "$SOURCE"

echo
echo "✓ Compresión terminada."
echo
ls -lh "${OUTPUT%.zip}"*