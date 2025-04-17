#!/usr/bin/env bash
set -e

cd DFA-Inductor-Exhaustive

SRC_DIR=src
BIN_DIR=bin
CONFIG=config.txt
LIB_JARS="lib/*"

if [[ "$1" == "-c" ]]; then
  echo "Compiling Java sources..."
  mkdir -p "$BIN_DIR"
  javac -cp "$LIB_JARS" -d "$BIN_DIR" "$SRC_DIR"/*.java

elif [[ "$1" == "-f" && -n "$2" ]]; then
  if [[ ! -f "$CONFIG" ]]; then
    echo "Config file not found: $CONFIG"
    exit 1
  fi
  CODE="$2"
  echo "Running Main with code=$CODE"
  java -cp "$BIN_DIR:$LIB_JARS" Main -f "$CODE"

else
  cat <<USAGE
Usage:
  $0 -c            # compile with dependencies
  $0 -f <code>     # run Main with config number e.g. 021 or phi_b2
USAGE
  exit 1
fi