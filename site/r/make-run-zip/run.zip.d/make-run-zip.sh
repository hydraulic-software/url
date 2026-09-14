#!/bin/sh
set -eu

fallback_openssl=$1
fallback_zip=$2
shift 2

source=${1:-.}
output=${2:-run.zip}
tsa=${3:-http://timestamp.digicert.com}

source=$(CDPATH= cd -- "$source" && pwd -P)
output_parent=$(CDPATH= cd -- "$(dirname -- "$output")" && pwd -P)
output="$output_parent/$(basename -- "$output")"

case "$output" in
  "$source"|"$source"/*)
    echo "Output archive must be outside the source directory: $output" >&2
    exit 2
    ;;
esac

# Prefer the host's normal tools. The run.js package still resolves pinned
# fallback binaries, but they are only used when one of these commands is
# unavailable on PATH.
system_openssl=$(command -v openssl 2>/dev/null || true)
if [ -n "$system_openssl" ]; then
  openssl=$system_openssl
  openssl_source=system
else
  openssl=$fallback_openssl
  openssl_source=downloaded
fi

system_zip=$(command -v zip 2>/dev/null || true)
if [ -n "$system_zip" ]; then
  zip=$system_zip
  zip_source=system
else
  zip=$fallback_zip
  zip_source=downloaded
fi

# The downloaded OpenSSL build has libraries and configuration beside its
# executable. System OpenSSL already knows its own installation layout.
if [ "$openssl_source" = downloaded ]; then
  openssl_bin_dir=$(CDPATH= cd -- "$(dirname -- "$openssl")" && pwd -P)
  if [ "$(uname -s)" = Darwin ]; then
    DYLD_LIBRARY_PATH="$openssl_bin_dir/../lib${DYLD_LIBRARY_PATH:+:$DYLD_LIBRARY_PATH}"
    export DYLD_LIBRARY_PATH
  else
    LD_LIBRARY_PATH="$openssl_bin_dir/../lib${LD_LIBRARY_PATH:+:$LD_LIBRARY_PATH}"
    export LD_LIBRARY_PATH
  fi
  OPENSSL_CONF="$openssl_bin_dir/../ssl/openssl.cnf"
  OPENSSL_MODULES="$openssl_bin_dir/../lib/ossl-modules"
  export OPENSSL_CONF OPENSSL_MODULES
fi

# The system zip and the downloaded 7-Zip console binary have different
# command line interfaces, so keep the archive operations in these helpers.
create_archive() {
  archive=$1
  if [ "$zip_source" = system ]; then
    "$zip" -X -r "$archive" . -x context.d.ts '*/context.d.ts' timestamp.tsr
  else
    "$zip" a -tzip -mx=9 "$archive" . \
      '-xr!context.d.ts' '-xr!*/context.d.ts' '-xr!timestamp.tsr'
  fi
}

add_timestamp() {
  archive=$1
  if [ "$zip_source" = system ]; then
    "$zip" -X "$archive" timestamp.tsr
  else
    "$zip" a -tzip "$archive" timestamp.tsr
  fi
}

cd "$source"
temporary_zip=$(mktemp "$output_parent/.run.zip.XXXXXX")
timestamp_directory=$(mktemp -d "$output_parent/.run-timestamp.XXXXXX")
manifest="$timestamp_directory/manifest"
request="$timestamp_directory/timestamp.tsq"
response="$timestamp_directory/timestamp.tsr"
temporary_output="$output.tmp"
trap 'rm -rf -- "$temporary_zip" "$timestamp_directory" "$temporary_output"' EXIT HUP INT TERM
rm -f -- "$temporary_zip" "$temporary_output"

if find "$source" -type l ! -path "$source/context.d.ts" -print -quit | grep -q .; then
  echo "Source directory contains unsupported symbolic links" >&2
  exit 2
fi
if ! find "$source" -type f -exec sh -c '
  newline=$(printf "\nX")
  newline=${newline%X}
  carriage=$(printf "\r")
  for file do
    case "$file" in
      *"$newline"*|*"$carriage"*)
        exit 1
        ;;
    esac
  done
' sh {} +; then
  echo "Source file names may not contain newlines" >&2
  exit 2
fi
if find "$source" -name timestamp.tsr -print -quit | grep -q .; then
  echo "Source directory may not contain a file named timestamp.tsr" >&2
  exit 2
fi

(
  cd "$source"
  find . -type f ! -path './context.d.ts' ! -name timestamp.tsr -print
) | LC_ALL=C sort | while IFS= read -r file; do
  relative=${file#./}
  digest=$("$openssl" dgst -sha256 "$source/$relative" | sed 's/^.*= //')
  printf '%s  %s\n' "$digest" "$relative"
done > "$manifest"

create_archive "$temporary_zip"

# Ask the TSA to include its signer certificate so verifiers can check the
# CMS signature without needing a separate certificate download.
"$openssl" ts -query -data "$manifest" -sha256 -no_nonce -cert -out "$request"
curl -fsS \
  -H 'Content-Type: application/timestamp-query' \
  -H 'Accept: application/timestamp-reply' \
  --data-binary "@$request" "$tsa" > "$response"
"$openssl" ts -reply -in "$response" -text >/dev/null
(
  cd "$timestamp_directory"
  add_timestamp "$temporary_zip"
)
mv -f -- "$temporary_zip" "$temporary_output"
mv -f -- "$temporary_output" "$output"

printf '%s\n' "$output"
printf 'Added RFC 3161 timestamp from %s\n' "$tsa" >&2
