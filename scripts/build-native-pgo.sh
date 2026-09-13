#!/usr/bin/env bash
set -euo pipefail

project_dir=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
work_dir="$project_dir/build/pgo"
serve_dir="$work_dir/serve"
profile="$work_dir/url.iprof"
instrumented="$work_dir/url-instrumented"

rm -rf "$work_dir"
mkdir -p "$serve_dir"

# Oracle Native Image 25.0.1 crashes in GC while dumping an instrumented
# Truffle/Pkl profile on ARM64. Keep producing optimized ARM release binaries,
# but reserve PGO for architectures where profile collection is reliable.
if [[ $(uname -m) == "arm64" || $(uname -m) == "aarch64" ]]; then
    echo "Skipping PGO profile collection on ARM64"
    exec "$project_dir/gradlew" -p "$project_dir" nativePair \
        -PoracleGraalVM --rerun-tasks --no-daemon
fi

"$project_dir/gradlew" -p "$project_dir" pgoTrainingData \
    -PoracleGraalVM -PpgoTrainingDirectory="$serve_dir" --no-daemon
"$project_dir/gradlew" -p "$project_dir" nativeCompile \
    -PoracleGraalVM -PnativePgoInstrument --rerun-tasks --no-daemon
cp "$project_dir/build/native/nativeCompile/run" "$instrumented"

port=$(python3 -c 'import socket; s=socket.socket(); s.bind(("127.0.0.1", 0)); print(s.getsockname()[1]); s.close()')
python3 -m http.server "$port" --bind 127.0.0.1 --directory "$serve_dir" >"$work_dir/http.log" 2>&1 &
server_pid=$!
cleanup() { kill "$server_pid" 2>/dev/null || true; }
trap cleanup EXIT

server_ready=false
for ignored in {1..50}; do
    if curl --silent --fail --output /dev/null "http://127.0.0.1:$port/"; then
        server_ready=true
        break
    fi
    sleep 0.1
done
if ! $server_ready; then
    echo "Training HTTP server did not become ready" >&2
    exit 1
fi
"$instrumented" -XX:ProfilesDumpFile="$profile" --progress=never --min-free-space=0 \
    --cache-dir="$work_dir/cache" "http://127.0.0.1:$port/jdk-modules.tar.zst/modules" >/dev/null
test -s "$profile"
cleanup
trap - EXIT

"$project_dir/gradlew" -p "$project_dir" nativePair \
    -PoracleGraalVM -PnativePgoProfile="$profile" --rerun-tasks --no-daemon
