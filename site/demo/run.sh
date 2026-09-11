#!/bin/sh
set -eu

printf '%s\n' 'Hello from run.zip.'
for argument in "$@"; do
    printf 'argument: %s\n' "$argument"
done
