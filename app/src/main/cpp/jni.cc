// JNI bridge: com.skoltech.densepose.NativeEngine <-> fnl::Engine (export/engine).
//
// A Session owns an Engine plus what turns its results into pictures. Frames go in through the nativeSubmit* calls (any pixel
// source, rotated upright and reduced to the working size), come out of nativeNext() in order, already blended with the
// vertex colours, and nativeRender() copies the last picture into a Bitmap.
#include <android/bitmap.h>
#include <android/log.h>
#include <jni.h>

#include <cstring>
#include <memory>
#include <mutex>
#include <string>
#include <unordered_map>

#include "fnl/engine.h"
#include "fnl/thread_pool.h"
#include "frame_convert.h"
#include "overlay.h"

#define LOG_TAG "fnl"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)

namespace {

using fnlapp::RgbImage;

struct Session {
  std::unique_ptr<fnl::Engine> engine;
  fnlapp::VertexColors colors;
  int max_side = 960;
  fnl::ThreadPool pool{4};  // pixel conversion; one submitter at a time

  std::mutex m;
  std::unordered_map<int64_t, RgbImage> pending;  // submitted frames, until their result arrives

  // consumer side (nativeNext / nativeRender are called from one thread)
  std::vector<uint8_t> picture;  // rgba of the last result
  int pic_w = 0, pic_h = 0;
};

Session* S(jlong h) { return reinterpret_cast<Session*>(h); }

void Throw(JNIEnv* env, const char* cls, const std::string& msg) {
  jclass c = env->FindClass(cls);
  if (c) env->ThrowNew(c, msg.c_str());
}

jintArray Size(JNIEnv* env, int w, int h) {
  jintArray a = env->NewIntArray(2);
  const jint v[2] = {w, h};
  env->SetIntArrayRegion(a, 0, 2, v);
  return a;
}

// Rotate / reduce through `fetch`, remember the frame for compositing, hand it to the engine.
template <class Fetch>
jintArray Submit(JNIEnv* env, Session* s, int64_t id, int sw, int sh, int rotation, const Fetch& fetch) {
  RgbImage img;
  fnlapp::WorkingSize(sw, sh, rotation, s->max_side, &img.w, &img.h);
  img.px.resize(static_cast<size_t>(img.w) * img.h * 3);
  fnlapp::Resample(sw, sh, rotation, img.w, img.h, fetch, &s->pool, img.px.data());
  const int w = img.w, h = img.h;
  const uint8_t* rgb;
  {
    std::lock_guard<std::mutex> l(s->m);
    rgb = (s->pending[id] = std::move(img)).px.data();
  }
  // unordered_map nodes are stable, and the entry is only erased once its result has come out of the engine
  try {
    s->engine->Submit(id, rgb, w, h);
  } catch (const std::exception& e) {
    Throw(env, "java/lang/RuntimeException", e.what());
    return nullptr;
  }
  return Size(env, w, h);
}

}  // namespace

extern "C" {

JNIEXPORT jlong JNICALL Java_com_skoltech_densepose_NativeEngine_nativeCreate(
    JNIEnv* env, jobject, jstring model_dir, jboolean gpu, jint cpu_threads, jfloat score_thresh, jbyteArray colors, jint max_side) {
  try {
    auto s = std::make_unique<Session>();
    s->max_side = max_side;
    const jsize n = env->GetArrayLength(colors);
    s->colors.rgb.resize(n);
    env->GetByteArrayRegion(colors, 0, n, reinterpret_cast<jbyte*>(s->colors.rgb.data()));

    fnl::EngineOptions o;
    const char* dir = env->GetStringUTFChars(model_dir, nullptr);
    o.model_dir = dir;
    env->ReleaseStringUTFChars(model_dir, dir);
    o.gpu = gpu;
    o.cpu_threads = cpu_threads;
    o.score_thresh = score_thresh;
    s->engine = std::make_unique<fnl::Engine>(o);
    LOGI("engine ready: %dx%d, %s", s->engine->net_width(), s->engine->net_height(), gpu ? "GPU" : "CPU");
    return reinterpret_cast<jlong>(s.release());
  } catch (const std::exception& e) {
    Throw(env, "java/lang/RuntimeException", e.what());
    return 0;
  }
}

JNIEXPORT void JNICALL Java_com_skoltech_densepose_NativeEngine_nativeDestroy(JNIEnv*, jobject, jlong h) { delete S(h); }

// RGBA8888 (CameraX analysis frames). `buf` is a direct buffer, rows `row_stride` bytes apart.
JNIEXPORT jintArray JNICALL Java_com_skoltech_densepose_NativeEngine_nativeSubmitRgba(
    JNIEnv* env, jobject, jlong h, jlong id, jobject buf, jint w, jint hh, jint row_stride, jint rotation) {
  auto* p = static_cast<const uint8_t*>(env->GetDirectBufferAddress(buf));
  if (!p) return Throw(env, "java/lang/IllegalArgumentException", "buffer is not direct"), nullptr;
  return Submit(env, S(h), id, w, hh, rotation, fnlapp::RgbaFetch{p, static_cast<size_t>(row_stride)});
}

// ARGB_8888 software Bitmap.
JNIEXPORT jintArray JNICALL Java_com_skoltech_densepose_NativeEngine_nativeSubmitBitmap(JNIEnv* env, jobject, jlong h, jlong id,
                                                                                         jobject bitmap, jint rotation) {
  AndroidBitmapInfo info;
  void* px = nullptr;
  if (AndroidBitmap_getInfo(env, bitmap, &info) != ANDROID_BITMAP_RESULT_SUCCESS || info.format != ANDROID_BITMAP_FORMAT_RGBA_8888 ||
      AndroidBitmap_lockPixels(env, bitmap, &px) != ANDROID_BITMAP_RESULT_SUCCESS)
    return Throw(env, "java/lang/IllegalArgumentException", "need an ARGB_8888 software bitmap"), nullptr;
  jintArray r = Submit(env, S(h), id, info.width, info.height, rotation,
                       fnlapp::RgbaFetch{static_cast<const uint8_t*>(px), info.stride});
  AndroidBitmap_unlockPixels(env, bitmap);
  return r;
}

// YUV_420_888 planes (direct buffers). standard: 0 = BT.601, 1 = BT.709.
JNIEXPORT jintArray JNICALL Java_com_skoltech_densepose_NativeEngine_nativeSubmitYuv(
    JNIEnv* env, jobject, jlong h, jlong id, jobject yb, jobject ub, jobject vb, jint w, jint hh, jint y_stride, jint uv_stride,
    jint uv_pixel_stride, jint rotation, jint standard, jboolean full_range) {
  auto* y = static_cast<const uint8_t*>(env->GetDirectBufferAddress(yb));
  auto* u = static_cast<const uint8_t*>(env->GetDirectBufferAddress(ub));
  auto* v = static_cast<const uint8_t*>(env->GetDirectBufferAddress(vb));
  if (!y || !u || !v) return Throw(env, "java/lang/IllegalArgumentException", "buffers are not direct"), nullptr;
  const fnlapp::YuvMatrix m =
      fnlapp::MakeYuvMatrix(standard == 1 ? fnlapp::YuvStandard::kBt709 : fnlapp::YuvStandard::kBt601, full_range);
  return Submit(env, S(h), id, w, hh, rotation,
                fnlapp::YuvFetch{y, u, v, static_cast<size_t>(y_stride), static_cast<size_t>(uv_stride),
                                 static_cast<size_t>(uv_pixel_stride), m});
}

JNIEXPORT void JNICALL Java_com_skoltech_densepose_NativeEngine_nativeClose(JNIEnv*, jobject, jlong h) { S(h)->engine->Close(); }

// Next finished frame (blocks), composited with alpha `alpha` (0: the plain frame). null when closed and drained.
// -> [id, width, height, latency ms, n people, then per person x1, y1, x2, y2, score (frame px)]
JNIEXPORT jdoubleArray JNICALL Java_com_skoltech_densepose_NativeEngine_nativeNext(JNIEnv* env, jobject, jlong h, jfloat alpha) {
  Session* s = S(h);
  try {
    fnl::FrameResult r;
    if (!s->engine->Next(&r)) return nullptr;
    RgbImage frame;
    {
      std::lock_guard<std::mutex> l(s->m);
      auto it = s->pending.find(r.id);
      if (it == s->pending.end()) return Throw(env, "java/lang/IllegalStateException", "result for an unknown frame"), nullptr;
      frame = std::move(it->second);
      s->pending.erase(it);
    }
    s->pic_w = frame.w, s->pic_h = frame.h;
    s->picture.resize(static_cast<size_t>(frame.w) * frame.h * 4);
    fnlapp::Composite(frame, r, s->colors, alpha, s->picture.data());

    std::vector<double> out = {static_cast<double>(r.id), static_cast<double>(frame.w), static_cast<double>(frame.h), r.t.latency,
                               static_cast<double>(r.people.size())};
    for (const auto& p : r.people) {
      const double ox = r.crop_x, oy = r.crop_y;
      out.insert(out.end(), {p.box.x1 / r.scale + ox, p.box.y1 / r.scale + oy, p.box.x2 / r.scale + ox, p.box.y2 / r.scale + oy,
                             static_cast<double>(p.score)});
    }
    jdoubleArray a = env->NewDoubleArray(static_cast<jsize>(out.size()));
    env->SetDoubleArrayRegion(a, 0, static_cast<jsize>(out.size()), out.data());
    return a;
  } catch (const std::exception& e) {
    Throw(env, "java/lang/RuntimeException", e.what());
    return nullptr;
  }
}

// Copy the picture of the last nativeNext() into an ARGB_8888 bitmap of the same size.
JNIEXPORT void JNICALL Java_com_skoltech_densepose_NativeEngine_nativeRender(JNIEnv* env, jobject, jlong h, jobject bitmap) {
  Session* s = S(h);
  AndroidBitmapInfo info;
  void* px = nullptr;
  if (AndroidBitmap_getInfo(env, bitmap, &info) != ANDROID_BITMAP_RESULT_SUCCESS || info.format != ANDROID_BITMAP_FORMAT_RGBA_8888 ||
      static_cast<int>(info.width) != s->pic_w || static_cast<int>(info.height) != s->pic_h ||
      AndroidBitmap_lockPixels(env, bitmap, &px) != ANDROID_BITMAP_RESULT_SUCCESS) {
    Throw(env, "java/lang/IllegalArgumentException", "bitmap must be ARGB_8888 and match the frame size");
    return;
  }
  for (int y = 0; y < s->pic_h; ++y)
    std::memcpy(static_cast<uint8_t*>(px) + static_cast<size_t>(y) * info.stride, s->picture.data() + static_cast<size_t>(y) * s->pic_w * 4,
                static_cast<size_t>(s->pic_w) * 4);
  AndroidBitmap_unlockPixels(env, bitmap);
}

// Bitmap -> the planes of a MediaCodec input Image (direct buffers), for the video encoder.
JNIEXPORT void JNICALL Java_com_skoltech_densepose_NativeUtil_nativeBitmapToYuv(JNIEnv* env, jclass, jobject bitmap, jobject yb, jobject ub,
                                                                                  jobject vb, jint y_stride, jint uv_stride,
                                                                                  jint uv_pixel_stride) {
  AndroidBitmapInfo info;
  void* px = nullptr;
  auto* y = static_cast<uint8_t*>(env->GetDirectBufferAddress(yb));
  auto* u = static_cast<uint8_t*>(env->GetDirectBufferAddress(ub));
  auto* v = static_cast<uint8_t*>(env->GetDirectBufferAddress(vb));
  if (!y || !u || !v || AndroidBitmap_getInfo(env, bitmap, &info) != ANDROID_BITMAP_RESULT_SUCCESS ||
      info.format != ANDROID_BITMAP_FORMAT_RGBA_8888 || AndroidBitmap_lockPixels(env, bitmap, &px) != ANDROID_BITMAP_RESULT_SUCCESS) {
    Throw(env, "java/lang/IllegalArgumentException", "need an ARGB_8888 bitmap and direct buffers");
    return;
  }
  fnlapp::RgbaToYuv420(static_cast<const uint8_t*>(px), info.stride, static_cast<int>(info.width) & ~1, static_cast<int>(info.height) & ~1,
                       y, y_stride, u, v, uv_stride, uv_pixel_stride);
  AndroidBitmap_unlockPixels(env, bitmap);
}

}  // extern "C"
