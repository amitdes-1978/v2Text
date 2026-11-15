# Native (JNI + whisper.cpp) test on emulated arm64

Runs the app's real `v2text_jni.c` through a minimal fake JNIEnv, built with the same flags as the APK,
under `qemu-aarch64` with the A52s CPU features. No real model is needed: `make_model_eot.py` writes a
random-weights Whisper *tiny* in ggml format (output is gibberish; it exercises load/encode/decode).

```bash
python3 make_model_eot.py ggml-random-tiny-eot.bin
cmake -S . -B build -G Ninja -DCMAKE_TOOLCHAIN_FILE=$ANDROID_NDK/build/cmake/android.toolchain.cmake \
      -DANDROID_ABI=arm64-v8a -DANDROID_PLATFORM=33 -DCMAKE_BUILD_TYPE=Release
cmake --build build
qemu-aarch64-static -cpu max build/qtest ggml-random-tiny-eot.bin ../../third_party/whisper.cpp/../../../samples/jfk.wav 4
```
Any 16 kHz mono 16-bit WAV works as the second argument. Expect ~15 min under emulation.
