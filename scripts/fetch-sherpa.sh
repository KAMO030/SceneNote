#!/usr/bin/env bash
# 拉取 sherpa-onnx 预编译产物（不入库）：Android AAR + iOS 静态 xcframework（sherpa-onnx + onnxruntime）。
# 用法：scripts/fetch-sherpa.sh   （幂等；已存在则跳过）
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
VER="${SHERPA_VERSION:-1.13.8}"
ORT="${ORT_VERSION:-1.28.2}"
LIBS="$ROOT/shared/libs"
NATIVE="$ROOT/shared/native/sherpa"
TMP="${SHERPA_TMP:-$(mktemp -d)}"; mkdir -p "$TMP"
mkdir -p "$LIBS" "$NATIVE/include" "$NATIVE/ios-arm64" "$NATIVE/ios-simulator-arm64"

dl() { # url dest
  if [ -f "$2" ]; then echo "skip  $(basename "$2")"; return; fi
  echo "fetch $(basename "$2")"; curl -sSL --retry 3 --max-time 900 -o "$2" "$1"
}

dl "https://github.com/k2-fsa/sherpa-onnx/releases/download/v${VER}/sherpa-onnx-${VER}.aar" "$LIBS/sherpa-onnx-${VER}.aar"

if [ ! -f "$NATIVE/ios-arm64/libsherpa-onnx-c-api.a" ]; then
  dl "https://github.com/k2-fsa/sherpa-onnx/releases/download/xcframework/sherpa-onnx-v${VER}-ios-static.xcframework.zip" "$TMP/sherpa.zip"
  unzip -o -q "$TMP/sherpa.zip" -d "$TMP/sherpa"
  cp "$TMP/sherpa/sherpa-onnx.xcframework/ios-arm64/SherpaOnnxC.framework/SherpaOnnxC" "$NATIVE/ios-arm64/libsherpa-onnx-c-api.a"
  lipo -thin arm64 "$TMP/sherpa/sherpa-onnx.xcframework/ios-arm64_x86_64-simulator/SherpaOnnxC.framework/SherpaOnnxC" -output "$NATIVE/ios-simulator-arm64/libsherpa-onnx-c-api.a"
  rm -rf "$NATIVE/include/sherpa-onnx"; cp -R "$TMP/sherpa/sherpa-onnx.xcframework/ios-arm64/SherpaOnnxC.framework/Headers/sherpa-onnx" "$NATIVE/include/"
  echo "sherpa-onnx ${VER} xcframework -> $NATIVE"
else echo "skip  sherpa xcframework"; fi

if [ ! -f "$NATIVE/ios-arm64/libonnxruntime.a" ]; then
  dl "https://github.com/csukuangfj/onnxruntime-libs/releases/download/v${ORT}/onnxruntime-ios-static-xcframework-${ORT}.xcframework.zip" "$TMP/ort.zip"
  unzip -o -q "$TMP/ort.zip" -d "$TMP/ort"
  lipo -thin arm64 "$TMP/ort/onnxruntime.xcframework/ios-arm64/onnxruntime.framework/onnxruntime" -output "$NATIVE/ios-arm64/libonnxruntime.a" 2>/dev/null || cp "$TMP/ort/onnxruntime.xcframework/ios-arm64/onnxruntime.framework/onnxruntime" "$NATIVE/ios-arm64/libonnxruntime.a"
  lipo -thin arm64 "$TMP/ort/onnxruntime.xcframework/ios-arm64_x86_64-simulator/onnxruntime.framework/onnxruntime" -output "$NATIVE/ios-simulator-arm64/libonnxruntime.a"
  echo "onnxruntime ${ORT} xcframework -> $NATIVE"
else echo "skip  onnxruntime xcframework"; fi
rm -rf "$TMP"
ls -la "$LIBS" "$NATIVE"/ios-arm64 "$NATIVE"/ios-simulator-arm64
