# DensePose Android

An Android app that runs DensePose on your phone. You can point it at a photo, a video from the gallery, or the live camera, and it paints
each person with colors that show which part of the body surface every pixel belongs to. The network is a TFLite export of
Mobile-Parsing-RCNN-B-CSE.

The app itself is a thin Kotlin UI plus the JNI glue. The actual work is done by a C++ pipeline that lives in DensePoseFnL. A git-ignored
`local.properties` tells the build where to find it, along with the models.

## The models and the engine live in another repo

This repo has the app only. The TFLite models and the inference engine come from
[DensePoseFnL](https://github.com/zetyquickly/DensePoseFnL), so start by cloning that next to this one.

**Models.** The app needs four `.tflite` files per model set: `graph_a`, `graph_b1`, `graph_b2` and `graph_c`. There are four sets: `cse` and
`cse_portrait` (640x480, the "Fast" option), and `cse_800` and `cse_800_portrait` (1088x800, the "High" option). Generate them inside
DensePoseFnL:

    cd DensePoseFnL/export
    uv run python export_litert.py configs/cse.yaml
    uv run python export_litert.py configs/cse_portrait.yaml
    uv run python export_litert.py configs/cse_800.yaml
    uv run python export_litert.py configs/cse_800_portrait.yaml

Each run writes its files to `DensePoseFnL/export/out/litert/<name>/`. The models never go into this repo. The build copies them from there
into the APK for you.

**C++ code.** The app's own native code (the JNI bridge and the code that draws the colors over the frame) is in `app/src/main/cpp/`. The
inference engine is in `DensePoseFnL/export/engine`, and our CMake file pulls it in from wherever you point it (see below).

## Suggested layout

Nothing forces these locations, since everything is configured in `local.properties`, but this is a tidy way to arrange it. The comments show
which property points where:

    DensePoseFnL/export/
      engine/                   C++ pipeline                 -> fnl.engine.dir
      out/litert/<name>/        graph_{a,b1,b2,c}.tflite     -> fnl.models.dir
    toolchain_src/
      tensorflow/               TensorFlow source            -> fnl.tflite.src
      tflite_build/             TFLite arm64 build           -> fnl.tflite.build
    densepose-android/
      local.properties          untracked
      app/src/main/{cpp,java,assets}

## Building

You need JDK 17 or newer, the Android SDK (platform 34, build-tools 34, CMake 3.22.1), and NDK r27. You also need a TFLite build for
arm64-v8a and android-31 with the GPU delegate and XNNPACK turned on (`DensePoseFnL/export/engine/CMakeLists.txt` shows how), along with the TensorFlow
checkout it was built from.

Then tell Gradle where everything is by creating a `local.properties` file in the repo root. It's git-ignored, so the paths stay yours:

    sdk.dir=<Android SDK>
    ndk.path=<NDK>                        # optional
    fnl.tflite.src=<tensorflow checkout>
    fnl.tflite.build=<TFLite cmake build dir>
    fnl.engine.dir=<DensePoseFnL>/export/engine
    fnl.models.dir=<DensePoseFnL>/export/out/litert
    fnl.models=cse,cse_portrait,cse_800,cse_800_portrait   # optional, which sets to bundle

and build and install (make sure `JAVA_HOME` points at JDK 17+):

    ./gradlew assembleDebug
    adb install -r app/build/outputs/apk/debug/app-debug.apk

Gradle compiles the engine from `fnl.engine.dir` into `libfnl_jni.so` through CMake, and a `syncModels` task copies the graphs into the APK's
assets. You only need to build TFLite once; after that it's just the Gradle command.

If you only exported some of the model sets, list just those in `fnl.models`. Otherwise the build stops and tells you which file is missing.
The app needs Android 12 or newer (minSdk 31), because that's what the TFLite build targets.
