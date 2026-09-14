#!/usr/bin/env bash
set -euo pipefail

project_dir=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)

# Runtime-trained Native Image PGO is currently unreliable with the embedded
# Truffle/Pkl runtime: profile collection can crash during shutdown, and a
# synthetic resolver workload couples CI to evolving archive semantics.
exec "$project_dir/gradlew" -p "$project_dir" nativePair \
    -PoracleGraalVM --rerun-tasks --no-daemon
