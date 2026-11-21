#!/usr/bin/env bash
set -euo pipefail
root=$(cd "$(dirname "$0")/.." && pwd)
work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT
src="$root/app/src/main/java/org/cwmobile"
javac -encoding UTF-8 -d "$work" \
    "$src"/{Morse,Rig,RigProfile,Civ,AsciiRig,WolfKeyer}.java "$root/tests/ProtocolChecks.java"
java -cp "$work" org.cwmobile.ProtocolChecks
