#!/usr/bin/env bash
# Compile RentEase.java and package it as dist/rentease.jar (macOS / Linux / Git Bash)
set -e
cd "$(dirname "$0")"
rm -rf build && mkdir -p build dist
javac -encoding UTF-8 -d build src/RentEase.java
printf 'Main-Class: RentEase\n' > build/manifest.txt
jar cfm dist/rentease.jar build/manifest.txt -C build .
echo "Built dist/rentease.jar"
