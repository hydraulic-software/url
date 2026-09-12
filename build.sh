#!/bin/sh
set -eu

cd "$(dirname "$0")"

if command -v url >/dev/null 2>&1; then
    case "$(uname -s):$(uname -m)" in
        Darwin:arm64|Darwin:aarch64) platform=macos-aarch64 ;;
        Linux:x86_64|Linux:amd64) platform=linux-x64 ;;
        Linux:arm64|Linux:aarch64) platform=linux-aarch64 ;;
        *)
            echo "build.sh: Oracle GraalVM 25 is unavailable for $(uname -s) $(uname -m)" >&2
            exit 1
            ;;
    esac

    graalvm_home=$(url "https://download.oracle.com/graalvm/25/latest/graalvm-jdk-25_${platform}_bin.tar.gz/")
    if [ -d "$graalvm_home/Contents/Home" ]; then
        graalvm_home="$graalvm_home/Contents/Home"
    fi
    if [ ! -x "$graalvm_home/bin/native-image" ]; then
        echo "build.sh: $graalvm_home/bin/native-image is missing or not executable" >&2
        exit 1
    fi

    JAVA_HOME=$graalvm_home
    PATH="$JAVA_HOME/bin:$PATH"
    export JAVA_HOME PATH
    exec ./gradlew \
        "-Dorg.gradle.java.installations.paths=$JAVA_HOME" \
        -Dorg.gradle.java.installations.auto-detect=false \
        -PoracleGraalVM \
        "$@"
fi

exec ./gradlew "$@"
