#!/usr/bin/env bash
# P62: inspect (do not execute) the official whisper.cpp Windows x64 release archives at the latest
# release (tag v1.9.5 -> d1be6fde; binaries are published under build tag b5454, same commit).
# Usage: bash P62-whispercpp-windows-packaging.sh <empty download dir>
set -eu
cd "$1"
gh api repos/ggml-org/whisper.cpp/releases/latest --jq '"latest_release=\(.tag_name) published=\(.published_at) target=\(.target_commitish) assets=\(.assets|length)"'
gh api repos/ggml-org/whisper.cpp/releases/tags/b5454 --jq '"b5454 target=\(.target_commitish)", (.assets[] | "\(.name)\t\(.size)")'
gh release download b5454 -R ggml-org/whisper.cpp -p whisper-bin-x64.zip -p whisper-blas-bin-x64.zip --skip-existing
sha256sum whisper-bin-x64.zip whisper-blas-bin-x64.zip
unzip -l whisper-bin-x64.zip
unzip -l whisper-blas-bin-x64.zip | grep -i '\.dll'
gh api repos/ggml-org/whisper.cpp/releases/tags/v1.8.3 --jq '(.assets[] | select(.name|test("jar|x64")) | "v1.8.3\t\(.name)\t\(.size)")'
