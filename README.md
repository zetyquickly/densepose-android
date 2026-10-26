# DensePose Android

An Android app that runs DensePose on your phone. You can point it at a photo, a video from the gallery, or the live camera, and it paints
each person with colors that show which part of the body surface every pixel belongs to. The network is a TFLite export of
Mobile-Parsing-RCNN-B-CSE.

The app itself is a thin Kotlin UI plus the JNI glue. The actual work is done by a C++ pipeline that lives in DensePoseFnL. A git-ignored
`local.properties` tells the build where to find the TFLite toolchain.

## The models and the engine live in another repo

This repo has the app only. The TFLite models and the inference engine come from
[DensePoseFnL](https://github.com/zetyquickly/DensePoseFnL), which is pinned here as a git submodule at `third_party/DensePoseFnL`. The pin
is the engine version the app was built and tested against, so clone with submodules:

    git clone --recurse-submodules https://github.com/zetyquickly/densepose-android

(or `git submodule update --init` in an existing clone).

**Models.** The app needs four `.tflite` files per model set: `graph_a`, `graph_b1`, `graph_b2` and `graph_c`. There are four sets: `cse` and
`cse_portrait` (640x480, the "Fast" option), and `cse_800` and `cse_800_portrait` (1088x800, the "High" option). Download the
`Mobile-Parsing-RCNN-B-CSE` checkpoint (from [Hugging Face](https://huggingface.co/rusrakhimov/DensePoseFnL), linked in the DensePoseFnL Model Zoo)
into `third_party/DensePoseFnL/models/`, then generate the sets inside the submodule:

    hf download rusrakhimov/DensePoseFnL mobile_parsing_rcnn_b_cse_s3x.pth --local-dir third_party/DensePoseFnL/models
    cd third_party/DensePoseFnL/export
    uv sync
    uv run python export_litert.py configs/cse.yaml
    uv run python export_litert.py configs/cse_portrait.yaml
    uv run python export_litert.py configs/cse_800.yaml
    uv run python export_litert.py configs/cse_800_portrait.yaml

Each run writes its files to `third_party/DensePoseFnL/export/out/litert/<name>/`. The models never go into this repo. The build copies them
from there into the APK for you.

**C++ code.** The app's own native code (the JNI bridge and the code that draws the colors over the frame) is in `app/src/main/cpp/`. The
inference engine is in `third_party/DensePoseFnL/export/engine`, and our CMake file pulls it in from there.

## Bumping the engine

    git -C third_party/DensePoseFnL fetch
    git -C third_party/DensePoseFnL checkout <commit or tag>
    git add third_party/DensePoseFnL

Re-export the models from the new checkout, rebuild, and commit the new pin together with whatever the app needed to change for it. If the
engine's interface changes (results format, `Person` fields), bump the pin in the same PR that adapts the app.

## Prebuilt APK

The debug APK is not in the repo (it was 84 MB per version in Git LFS). Download `app-debug.apk` from the
[Releases](https://github.com/zetyquickly/densepose-android/releases) page and `adb install -r` it. Each release is built from the pinned
DensePoseFnL commit, which is named in the release notes. To cut one after bumping the pin:

    ./gradlew assembleDebug
    gh release create v<version> app/build/outputs/apk/debug/app-debug.apk --title v<version> \
        --notes "Built against DensePoseFnL $(git -C third_party/DensePoseFnL rev-parse --short HEAD)"


## Layout

The toolchain is the only thing that lives outside the repo; its paths are set in `local.properties`:

    densepose-android/
      third_party/DensePoseFnL/     pinned engine + export scripts (submodule)
        export/engine/              C++ pipeline                 (default fnl.engine.dir)
        export/out/litert/<name>/   graph_{a,b1,b2,c}.tflite     (default fnl.models.dir)
      local.properties              untracked
      app/src/main/{cpp,java,assets}
    toolchain_src/
      tensorflow/                   TensorFlow source            -> fnl.tflite.src
      tflite_build/                 TFLite arm64 build           -> fnl.tflite.build

## Building

You need JDK 17 or newer, the Android SDK (platform 34, build-tools 34, CMake 3.22.1), and NDK r27. You also need a TFLite build for
arm64-v8a and android-31 with the GPU delegate and XNNPACK turned on (`DensePoseFnL/export/engine/CMakeLists.txt` shows how), along with the TensorFlow
checkout it was built from.

Then tell Gradle where everything is by creating a `local.properties` file in the repo root. It's git-ignored, so the paths stay yours:

    sdk.dir=<Android SDK>
    ndk.path=<NDK>                        # optional
    fnl.tflite.src=<tensorflow checkout>
    fnl.tflite.build=<TFLite cmake build dir>
    fnl.models=cse,cse_portrait,cse_800,cse_800_portrait   # optional, which sets to bundle
    fnl.engine.dir=<other DensePoseFnL>/export/engine      # optional, overrides the pinned submodule (local development)
    fnl.models.dir=<other DensePoseFnL>/export/out/litert  # optional, same

and build and install (make sure `JAVA_HOME` points at JDK 17+):

    ./gradlew assembleDebug
    adb install -r app/build/outputs/apk/debug/app-debug.apk

Gradle compiles the pinned engine (or `fnl.engine.dir`, if set) into `libfnl_jni.so` through CMake, and a `syncModels` task copies the graphs into the APK's
assets. You only need to build TFLite once; after that it's just the Gradle command.

Gradle does not export or download anything: if the graphs are missing, `syncModels` stops the build with `missing .../graph_a.tflite (see export_litert.py)`, and
if the checkpoint is missing, `export_litert.py` fails with `FileNotFoundError` on `models/mobile_parsing_rcnn_b_cse_s3x.pth`.

If you only exported some of the model sets, list just those in `fnl.models`. Otherwise the build stops and tells you which file is missing.
The app needs Android 12 or newer (minSdk 31), because that's what the TFLite build targets.
