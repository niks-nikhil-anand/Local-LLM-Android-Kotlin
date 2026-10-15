# LocalAI — physical ARM64 device test procedure

The emulator used during development is x86_64; it says nothing about phone performance, thermal
behaviour or memory pressure. Run this on a real `arm64-v8a` phone before any release claim.
Record results in the matrix at the end; write **Not tested** for anything not measured.

## 0. Prerequisites
- Phone with USB debugging enabled, connected (`adb devices` lists it), ≥ 4 GB RAM.
- `scripts/fetch-llama-cpp.sh` has been run; build with `./gradlew :app:assembleDebug`.

## 1. Device facts
```
adb shell getprop ro.product.manufacturer
adb shell getprop ro.product.model
adb shell getprop ro.build.version.release
adb shell getprop ro.product.cpu.abilist
adb shell getconf PAGE_SIZE
adb shell cat /proc/meminfo | head -3
adb shell cat /proc/cpuinfo | grep -m1 -i "hardware\|model name"
```

## 2. Install and confirm the ARM64 library is used
```
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell dumpsys package com.example.localai | grep primaryCpuAbi      # expect arm64-v8a
unzip -l app/build/outputs/apk/debug/app-debug.apk | grep liblocalai_llama.so
```

## 3. Functional checks (record pass/fail)
1. Explore → search `qwen2.5-0.5b-instruct-gguf` → open `Qwen/Qwen2.5-0.5B-Instruct-GGUF`
   → Download `qwen2.5-0.5b-instruct-q4_k_m.gguf` (491,400,032 bytes).
2. Card shows "Downloaded and verified"; My Models shows "SHA-256 verified".
   Expected SHA-256: `74a4da8c9fdbcd15bd1f6d01d621410d31c6fc00986f5eb687824e7b93d7a9db`.
3. My Models → Load → "Loaded by llama.cpp in N ms" (record N = load time).
4. Chat → "What is the capital of France? Answer in one sentence." → record the reply and the
   status line (first-token time and tokens/s are measured by the app).
5. Ask a follow-up that needs the previous turn; confirm it uses the history.
6. "Write a long story about a robot." → tap Stop after ~5 s → "Stopped · N tokens", Send returns.
7. Unload → Load again → generate → Unload (repeat 5×). Watch for crashes.
8. Offline: `adb shell cmd connectivity airplane-mode enable`; force-stop and relaunch the app;
   repeat steps 3–6. Re-enable with `adb shell cmd connectivity airplane-mode disable`.

## 4. Measurements
- Memory: `adb shell dumpsys meminfo com.example.localai` before load, after load, during
  generation, after unload (record TOTAL PSS and Native Heap). Repeat the load/unload cycle 5×
  and compare the after-unload values to spot leaks.
- Thermal: `adb shell dumpsys thermalservice | grep -i status` before and after 5 long replies.
- Crashes / native errors: `adb logcat -d | grep -E "FATAL|SIGSEGV|SIGABRT|LocalAI-llama"`.
  (A stop request legitimately logs `llama_decode: failed to decode, ret = 2`.)

## 5. Result matrix
| Model (file, quant) | Device | Context | Load time | First token | Decode tok/s | Memory (PSS loaded / unloaded) | Stability | Result |
|---|---|---|---|---|---|---|---|---|
| qwen2.5-0.5b-instruct-q4_k_m | | 2048 | | | | | | Not tested |
| llama2.c-stories15M.Q4_K_M | | 256 | | | | | | Not tested |
