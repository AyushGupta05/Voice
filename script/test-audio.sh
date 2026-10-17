#!/bin/bash
set -euo pipefail
ROOT_DIR="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT_DIR"
export CLANG_MODULE_CACHE_PATH="$ROOT_DIR/.local/clang-cache"
export SWIFTPM_MODULECACHE_OVERRIDE="$ROOT_DIR/.local/swift-module-cache"
swift test --package-path apps/companion --scratch-path .local/swift-build --cache-path .local/swift-cache
