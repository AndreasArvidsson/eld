#!/usr/bin/env bash
set -euo pipefail
root="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
jar="$root/target/mylang-1.0-SNAPSHOT.jar"

if [[ ! -f "$jar" ]]; then
    echo "Build first with: mvn package" >&2
    exit 1
fi

exec java -jar "$jar" "$@"
