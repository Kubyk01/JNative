#!/usr/bin/env bash

OUTPUT_FILE="merged-src.java"

# Clear or create the output file
> "$OUTPUT_FILE"

# Collect only files from src/main
while IFS= read -r -d '' file; do
  echo ""
  echo "/* path: $file */"
  echo ""
  cat "$file"
done < <(
  find src/main -type f -print0 2>/dev/null | sort -z
) >> "$OUTPUT_FILE"