#!/usr/bin/env sh
# Checks out the pinned llama.cpp revision used by app/src/main/cpp/CMakeLists.txt.
# The checkout (~215 MB) is not committed; run this once after cloning LocalAI.
set -eu
TAG="v0.6.0"
COMMIT="d81235049384534c167caea52b85a694f6103d14"
DIR="$(cd "$(dirname "$0")/.." && pwd)/third_party/llama.cpp"

if [ ! -d "$DIR/.git" ]; then
    git clone --quiet --depth 1 --branch "$TAG" https://github.com/ggml-org/llama.cpp.git "$DIR"
fi
ACTUAL="$(git -C "$DIR" rev-parse HEAD)"
if [ "$ACTUAL" != "$COMMIT" ]; then
    echo "third_party/llama.cpp is at $ACTUAL, expected $COMMIT ($TAG)." >&2
    exit 1
fi
echo "llama.cpp $TAG ($COMMIT) is ready."
