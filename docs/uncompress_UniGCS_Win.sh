#!/bin/bash

set -e

ARCHIVE="UniGCS-3_0_1-v527b7522-setup_48fbf8e8.zip"
OUTPUT_DIR="UniGCS_extraido"

if [ ! -f "$ARCHIVE" ]; then
    echo "Error: no existe '$ARCHIVE'"
    exit 1
fi

echo "Comprobando archivo dividido..."
echo

zip -s 0 "$ARCHIVE" --out "${ARCHIVE%.zip}_completo.zip"

echo
echo "✓ Archivo reconstruido."
echo
echo "Descomprimiendo en '$OUTPUT_DIR'..."

mkdir -p "$OUTPUT_DIR"

unzip "${ARCHIVE%.zip}_completo.zip" -d "$OUTPUT_DIR"

echo
echo "✓ Descompresión terminada."
echo "Contenido:"
ls -lah "$OUTPUT_DIR"
