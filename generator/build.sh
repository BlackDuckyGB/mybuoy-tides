#!/bin/sh
#
# Builds the packed tide grid from the published EOT20 model, following the
# procedure described in README.md.
#
# Every step is skipped when its result is already present, so the script can
# be re-run at will: it only does the work that is still missing. Delete a file
# under build/ to force that step to run again.
#
# Requires a JDK 17 or later on the PATH (or JAVA_HOME set), plus curl, unzip
# and gzip. Nothing else.
#
# Licensed under MIT (see ../LICENSE).

set -eu

cd "$(dirname "$0")"

BUILD=build
SOURCE_URL=https://www.seanoe.org/data/00683/79489/data/85762.zip
CONSTITUENTS=all
AREA=world
STEP_CENTIMETRES=0.1
GRID=$BUILD/tides_world.bin

if [ -n "${JAVA_HOME:-}" ]; then
    JAVAC=$JAVA_HOME/bin/javac
    JAVA=$JAVA_HOME/bin/java
else
    JAVAC=javac
    JAVA=java
fi

announce() {
    printf '\n== %s\n' "$1"
}

already() {
    printf '   present, skipping: %s\n' "$1"
}

mkdir -p "$BUILD"

announce "1/8  source archive"
if [ -f "$BUILD/85762.zip" ]; then
    already "$BUILD/85762.zip"
else
    # The server ignores suffix ranges but honours ordinary ones, so an
    # interrupted transfer of this 2.33 GB archive resumes cleanly.
    curl -L -C - --retry 20 --retry-all-errors --retry-delay 5 \
        -o "$BUILD/85762.zip" "$SOURCE_URL"
fi

announce "2/8  ocean tide archive"
if [ -f "$BUILD/ocean_tides.zip" ]; then
    already "$BUILD/ocean_tides.zip"
else
    # Only the ocean tides are used; the load tides in the same archive
    # describe sea floor deformation and are of no use here.
    unzip -o "$BUILD/85762.zip" ocean_tides.zip -d "$BUILD"
fi

announce "3/8  constituent files"
if [ -f "$BUILD/eot20/ocean_tides/M2_ocean_eot20.nc" ]; then
    already "$BUILD/eot20/ocean_tides"
else
    unzip -q -o "$BUILD/ocean_tides.zip" -d "$BUILD/eot20"
    rm -rf "$BUILD/eot20/__MACOSX"
fi

found=$(ls "$BUILD"/eot20/ocean_tides/*_ocean_eot20.nc 2>/dev/null | wc -l | tr -d ' ')
if [ "$found" -ne 17 ]; then
    echo "   expected 17 constituent files, found $found" >&2
    exit 1
fi
printf '   %s constituent files\n' "$found"

announce "4/8  compile the converter"
if [ -f "$BUILD/classes/generator/PackEot20.class" ]; then
    already "$BUILD/classes"
else
    "$JAVAC" -d "$BUILD/classes" ./*.java
fi

announce "5/8  amplitude statistics"
if [ -f "$BUILD/stats.txt" ]; then
    already "$BUILD/stats.txt"
else
    # Worth keeping: it is what shows the handful of cells holding values that
    # cannot be real, and why the quantisation step is set by hand.
    "$JAVA" -Xmx2g -cp "$BUILD/classes" generator.GridStats \
        "$BUILD/eot20/ocean_tides" | tee "$BUILD/stats.txt"
fi

announce "6/8  pack the grid"
if [ -f "$GRID" ]; then
    already "$GRID"
else
    "$JAVA" -Xmx2g -cp "$BUILD/classes" generator.PackEot20 \
        "$BUILD/eot20/ocean_tides" "$GRID" \
        "$CONSTITUENTS" "$AREA" "$STEP_CENTIMETRES"
fi

announce "7/8  compress for publication"
if [ -f "$GRID.gz" ]; then
    already "$GRID.gz"
else
    gzip -9 -c "$GRID" > "$GRID.gz"
fi

announce "8/8  verify the grid"
# Always run: this is the only step that proves the file is usable. It reads
# the grid back with an implementation independent of the one that wrote it,
# and compares the prediction with a published tide table.
"$JAVA" -Xmx2g -cp "$BUILD/classes" generator.TideCheck "$GRID" --check

announce "result"
for file in "$GRID" "$GRID.gz"; do
    printf '   %-28s %12s bytes\n' "$(basename "$file")" "$(wc -c < "$file" | tr -d ' ')"
done

printf '\n   sha256:\n'
if command -v shasum > /dev/null 2>&1; then
    shasum -a 256 "$GRID" "$GRID.gz" | sed 's/^/   /'
else
    sha256sum "$GRID" "$GRID.gz" | sed 's/^/   /'
fi

printf '\n   Attach %s to a GitHub Release, and put the two sizes above\n' "$(basename "$GRID.gz")"
printf '   into the manifest entry named tides_world.\n'
