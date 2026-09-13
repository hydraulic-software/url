VER=${VER:-0.14.0}

case "$OS:$ARCH" in
  linux:x86_64) jdk_platform=linux-x64 ;;
  linux:arm64) jdk_platform=linux-aarch64 ;;
  macos:x86_64) jdk_platform=macos-x64 ;;
  macos:arm64) jdk_platform=macos-aarch64 ;;
  *)
    echo "cel-verifier: unsupported platform $OS/$ARCH" >&2
    exit 1
    ;;
esac

jdk_url="https://download.oracle.com/graalvm/25/latest/graalvm-jdk-25_${jdk_platform}_bin.tar.gz/"
verifier_url="https://repo1.maven.org/maven2/dev/cel/verifier-cli/$VER/verifier-cli-$VER.jar"
resolved=$(url "jdk_home=$jdk_url" "verifier=$verifier_url") || exit $?
eval "$resolved" || exit $?

if [ -d "$jdk_home/Contents/Home" ]; then
  jdk_home="$jdk_home/Contents/Home"
fi

exec "$jdk_home/bin/java" --sun-misc-unsafe-memory-access=allow --enable-native-access=ALL-UNNAMED -jar "$verifier" repl "$@"
