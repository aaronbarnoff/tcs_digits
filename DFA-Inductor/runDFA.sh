#!/usr/bin/env bash
#SBATCH --account=def-cbright
#SBATCH --cpus-per-task=1
set -euo pipefail

usage() {
cat << EOF
Usage: $0 [-c] -f <code> [-s <solver>]

Examples:
  $0 -c
  $0 -f phi_b2 -s 0
  $0 -f 021 -s 1

Options:
  -c             compile Java sources
  -f <code>      config key to pass to Java Main
  -s <solver>    solver type: 0 regular cadical, 1 cadical-exhaust (default 0)
EOF
}

options=$(getopt "hcf:s:" "$@") || { usage; exit 2; }
eval set -- "$options"

compile_only=0
code=""
solver=0

while true; do
  case "$1" in
    -h) usage; exit 0 ;;
    -c) compile_only=1; shift ;;
    -f) code="$2"; shift 2 ;;
    -s) solver="$2"; shift 2 ;;
    --) shift; break ;;
    *)  echo "Bad option"; usage; exit 2 ;;
  esac
done

SRC_DIR=src
BIN_DIR=bin
CONFIG=config.txt
LIB_JARS="lib/*"

if (( compile_only == 1 )); then
  echo "Compiling Java sources..."
  mkdir -p "$BIN_DIR"
  javac -cp "$LIB_JARS" -d "$BIN_DIR" "$SRC_DIR"/*.java
  exit 0
fi

if [[ -z "$code" ]]; then
  echo "Missing -f <code>"
  usage
  exit 2
fi

if [[ ! -f "$CONFIG" ]]; then
  echo "Config file not found: $CONFIG"
  exit 1
fi

echo "Running Main with code=$code solver=$solver"
java -cp "$BIN_DIR:$LIB_JARS" Main -f "$code" -s "$solver"
