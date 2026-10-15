# llama.cpp in LocalAI

| | |
|---|---|
| Upstream | https://github.com/ggml-org/llama.cpp (MIT licence) |
| Pinned release | `v0.6.0` (published 2026-10-05) |
| Commit | `d81235049384534c167caea52b85a694f6103d14` |
| Fetch | `scripts/fetch-llama-cpp.sh` (clones the tag and verifies the commit) |
| Location | `third_party/llama.cpp/` (git-ignored) |

## Build configuration (`app/src/main/cpp/CMakeLists.txt`)

- NDK `30.0.16248370`, CMake `4.1.2`, `ANDROID_STL=c++_static`, always `CMAKE_BUILD_TYPE=Release`.
- ABIs: `arm64-v8a` (compiled; not yet run on hardware) and `x86_64` (run on the emulator).
- Static `llama` + `ggml` linked into one `liblocalai_llama.so`; only the public `llama.h` C API
  is used (no `common`, tools, server, examples).
- `GGML_NATIVE=OFF`, `GGML_OPENMP=OFF`, `GGML_LLAMAFILE=OFF`, `LLAMA_OPENSSL=OFF` as recommended
  by upstream `docs/android.md`; `GGML_BACKEND_DL=OFF`; no Vulkan/OpenCL (GPU unverified).
- 16 KB page alignment (`-z max-page-size=16384`).

## Known trade-offs

- Portable CPU baseline only. Upstream's Android example instead builds every CPU variant
  (`GGML_BACKEND_DL` + `GGML_CPU_ALL_VARIANTS`) and picks one at runtime; that is faster on
  modern phones and is a candidate optimisation once hardware measurements exist.
- The MIT licence notice of llama.cpp/ggml must be shown in the app's licences screen before release.
