#!/bin/sh
set -eu

SCRIPT_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
REPO_ROOT=$(CDPATH= cd -- "$SCRIPT_DIR/.." && pwd)
HARNESS_SOURCE="$REPO_ROOT/app/src/test/cpp/shuttle_jni_harness.c"
NATIVE_SOURCE="$REPO_ROOT/app/src/main/cpp/shuttle_pcm.c"
OUTPUT_DIR="$REPO_ROOT/app/build/native-shuttle-host"

if [ "$#" -eq 2 ] && [ "$1" = "--output-dir" ]; then
    case "$2" in
        /*) OUTPUT_DIR=$2 ;;
        *) OUTPUT_DIR="$REPO_ROOT/$2" ;;
    esac
elif [ "$#" -ne 0 ]; then
    echo "usage: $0 [--output-dir PATH]" >&2
    exit 2
fi

OUTPUT_PARENT=$(dirname -- "$OUTPUT_DIR")
mkdir -p "$OUTPUT_PARENT"
if ! mkdir "$OUTPUT_DIR"; then
    echo "refusing existing or unavailable output directory: $OUTPUT_DIR" >&2
    exit 2
fi

JAVA_ROOT=${JAVA_HOME:-}
if [ -z "$JAVA_ROOT" ]; then
    JAVAC_PATH=$(command -v javac || true)
    if [ -z "$JAVAC_PATH" ]; then
        echo "set JAVA_HOME or put javac on PATH" >&2
        exit 2
    fi
    JAVA_ROOT=$(dirname -- "$(dirname -- "$(readlink -f -- "$JAVAC_PATH")")")
fi
if [ ! -f "$JAVA_ROOT/include/jni.h" ] || [ ! -f "$JAVA_ROOT/include/linux/jni_md.h" ]; then
    echo "JNI headers not found under JAVA_HOME=$JAVA_ROOT" >&2
    exit 2
fi

CC_BIN=${CC:-cc}
GCOV_BIN=${GCOV:-gcov}
command -v "$CC_BIN" >/dev/null 2>&1 || { echo "C compiler not found: $CC_BIN" >&2; exit 2; }
command -v "$GCOV_BIN" >/dev/null 2>&1 || { echo "gcov not found: $GCOV_BIN" >&2; exit 2; }

sha256sum "$NATIVE_SOURCE" "$HARNESS_SOURCE" "$SCRIPT_DIR/test_native_shuttle_host.sh" > "$OUTPUT_DIR/source-bindings.sha256"
"$CC_BIN" --version > "$OUTPUT_DIR/compiler-version.txt" 2>&1
"$GCOV_BIN" --version > "$OUTPUT_DIR/gcov-version.txt" 2>&1
if "$CC_BIN" -std=c11 -Wall -Wextra -Werror -O0 -g --coverage \
    -fsanitize=address,undefined -fno-omit-frame-pointer \
    -fno-fast-math -fno-associative-math -ffp-contract=off \
    -I"$JAVA_ROOT/include" -I"$JAVA_ROOT/include/linux" \
    -DPRODUCTION_SOURCE="\"$NATIVE_SOURCE\"" \
    "$HARNESS_SOURCE" -Wl,--wrap=malloc -lm -o "$OUTPUT_DIR/shuttle-jni-harness" \
    > "$OUTPUT_DIR/compile.stdout" 2> "$OUTPUT_DIR/compile.stderr"; then
    COMPILE_STATUS=0
else
    COMPILE_STATUS=$?
fi
printf '%s\n' "$COMPILE_STATUS" > "$OUTPUT_DIR/compile.exit"
if sha256sum -c "$OUTPUT_DIR/source-bindings.sha256" > "$OUTPUT_DIR/source-binding-check.txt" 2>&1; then
    BINDING_AFTER_COMPILE=0
else
    BINDING_AFTER_COMPILE=1
fi
if [ "$COMPILE_STATUS" -ne 0 ]; then
    exit "$COMPILE_STATUS"
fi
if [ "$BINDING_AFTER_COMPILE" -ne 0 ]; then
    echo "source changed during compilation; refusing to run" >&2
    exit 1
fi

if ASAN_OPTIONS=detect_leaks=1:halt_on_error=1 UBSAN_OPTIONS=halt_on_error=1 \
    "$OUTPUT_DIR/shuttle-jni-harness" > "$OUTPUT_DIR/run.stdout" 2> "$OUTPUT_DIR/run.stderr"; then
    RUN_STATUS=0
else
    RUN_STATUS=$?
fi
printf '%s\n' "$RUN_STATUS" > "$OUTPUT_DIR/run.exit"
GCNO=$(find "$OUTPUT_DIR" -maxdepth 1 -name '*.gcno' -print -quit)
if [ -z "$GCNO" ]; then
    echo "compiler produced no gcno profile" > "$OUTPUT_DIR/gcov.stderr"
    GCOV_STATUS=125
elif (cd "$OUTPUT_DIR" && "$GCOV_BIN" -b -c -o "$GCNO" "$HARNESS_SOURCE") \
    > "$OUTPUT_DIR/gcov.stdout" 2> "$OUTPUT_DIR/gcov.stderr"; then
    GCOV_STATUS=0
else
    GCOV_STATUS=$?
fi
printf '%s\n' "$GCOV_STATUS" > "$OUTPUT_DIR/gcov.exit"
if sha256sum -c "$OUTPUT_DIR/source-bindings.sha256" > "$OUTPUT_DIR/source-binding-check-after.txt" 2>&1; then
    BINDING_AFTER_RUN=0
else
    BINDING_AFTER_RUN=1
    echo "source changed during test execution" >&2
fi
cat "$OUTPUT_DIR/run.stdout"
if [ "$RUN_STATUS" -ne 0 ]; then
    exit "$RUN_STATUS"
fi
if [ "$BINDING_AFTER_RUN" -ne 0 ]; then
    exit 1
fi
exit "$GCOV_STATUS"
