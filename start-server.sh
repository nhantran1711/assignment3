#!/bin/sh
cd "$(dirname "$0")" || exit 1

if [ ! -f "$1" ]; then
  echo "Usage: sh start-server.sh /Volumes/<USB name>/key.txt"
  exit 1
fi

# Read the key, dropping any line endings (key.txt may have been made on Windows)
OPENAI_API_KEY="$(tr -d '\r\n' < "$1")"
export OPENAI_API_KEY

exec java -jar comp3011-0.0.1-SNAPSHOT.jar
