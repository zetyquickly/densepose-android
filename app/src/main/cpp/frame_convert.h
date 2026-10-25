// Source pixels (RGBA from the camera / a bitmap, YUV 4:2:0 from MediaCodec) -> upright RGB8 working frame.
//
// The engine center-crops and bilinearly resizes with no antialiasing, so big frames (1080p video) are first reduced here
// with an area average to a working size whose longer side is at most `max_side`; frames that are already small are only
// rotated / converted.
#pragma once

#include <algorithm>
#include <cstddef>
#include <cstdint>
#include <vector>

#include "fnl/thread_pool.h"

namespace fnlapp {

struct RgbImage {
  int w = 0, h = 0;
  std::vector<uint8_t> px;  // w*h*3
};

// Size of a (sw x sh) source after rotating it clockwise by `rotation` (0/90/180/270) and capping the longer side.
inline void WorkingSize(int sw, int sh, int rotation, int max_side, int* ow, int* oh) {
  const bool swap = rotation == 90 || rotation == 270;
  const int uw = swap ? sh : sw, uh = swap ? sw : sh;
  const int longest = std::max(uw, uh);
  if (max_side <= 0 || longest <= max_side) {
    *ow = uw, *oh = uh;
    return;
  }
  const double s = static_cast<double>(max_side) / longest;
  *ow = std::max(static_cast<int>(uw * s + 0.5), 1);
  *oh = std::max(static_cast<int>(uh * s + 0.5), 1);
}

// `fetch(sx, sy, rgb)` writes the RGB of source pixel (sx, sy). The output pixel (ox, oy) averages the box of upright
// source pixels it covers.
template <class Fetch>
void Resample(int sw, int sh, int rotation, int ow, int oh, const Fetch& fetch, fnl::ThreadPool* pool, uint8_t* dst) {
  const bool swap = rotation == 90 || rotation == 270;
  const int uw = swap ? sh : sw, uh = swap ? sw : sh;
  auto row = [&](int oy) {
    const int y0 = static_cast<int>(static_cast<int64_t>(oy) * uh / oh);
    const int y1 = std::max(static_cast<int>((static_cast<int64_t>(oy + 1) * uh + oh - 1) / oh), y0 + 1);
    uint8_t* out = dst + static_cast<size_t>(oy) * ow * 3;
    for (int ox = 0; ox < ow; ++ox) {
      const int x0 = static_cast<int>(static_cast<int64_t>(ox) * uw / ow);
      const int x1 = std::max(static_cast<int>((static_cast<int64_t>(ox + 1) * uw + ow - 1) / ow), x0 + 1);
      int r = 0, g = 0, b = 0;
      for (int y = y0; y < std::min(y1, uh); ++y)
        for (int x = x0; x < std::min(x1, uw); ++x) {
          int sx, sy;
          switch (rotation) {  // upright (x, y) -> source pixel
            case 90: sx = y, sy = sh - 1 - x; break;
            case 180: sx = sw - 1 - x, sy = sh - 1 - y; break;
            case 270: sx = sw - 1 - y, sy = x; break;
            default: sx = x, sy = y;
          }
          uint8_t p[3];
          fetch(sx, sy, p);
          r += p[0], g += p[1], b += p[2];
        }
      const int n = (std::min(y1, uh) - y0) * (std::min(x1, uw) - x0);
      out[ox * 3] = static_cast<uint8_t>(r / n);
      out[ox * 3 + 1] = static_cast<uint8_t>(g / n);
      out[ox * 3 + 2] = static_cast<uint8_t>(b / n);
    }
  };
  if (pool)
    pool->For(oh, row);
  else
    for (int y = 0; y < oh; ++y) row(y);
}

struct RgbaFetch {
  const uint8_t* p;
  size_t stride;  // bytes per row
  void operator()(int x, int y, uint8_t* rgb) const {
    const uint8_t* s = p + static_cast<size_t>(y) * stride + static_cast<size_t>(x) * 4;
    rgb[0] = s[0], rgb[1] = s[1], rgb[2] = s[2];
  }
};

// Integer YCbCr -> RGB coefficients (x256). R = y*(Y-y_off) + rv*Cr, G = ... - gu*Cb - gv*Cr, B = ... + bu*Cb
struct YuvMatrix {
  int y_scale, y_off, rv, gu, gv, bu;
};
enum class YuvStandard { kBt601, kBt709 };
inline YuvMatrix MakeYuvMatrix(YuvStandard s, bool full_range) {
  if (s == YuvStandard::kBt709) return full_range ? YuvMatrix{256, 0, 403, 48, 120, 475} : YuvMatrix{298, 16, 459, 55, 136, 541};
  return full_range ? YuvMatrix{256, 0, 359, 88, 183, 454} : YuvMatrix{298, 16, 409, 100, 208, 516};
}

// YUV_420_888 planes (any of planar / semi-planar through the pixel strides).
struct YuvFetch {
  const uint8_t *y, *u, *v;
  size_t y_stride, uv_stride, uv_pixel_stride;
  YuvMatrix m;
  void operator()(int x, int yy, uint8_t* rgb) const {
    const int Y = y[static_cast<size_t>(yy) * y_stride + x];
    const size_t c = static_cast<size_t>(yy >> 1) * uv_stride + static_cast<size_t>(x >> 1) * uv_pixel_stride;
    const int cb = u[c] - 128, cr = v[c] - 128;
    const int base = m.y_scale * (Y - m.y_off) + 128;
    rgb[0] = Clamp8((base + m.rv * cr) >> 8);
    rgb[1] = Clamp8((base - m.gu * cb - m.gv * cr) >> 8);
    rgb[2] = Clamp8((base + m.bu * cb) >> 8);
  }
  static uint8_t Clamp8(int v) { return static_cast<uint8_t>(v < 0 ? 0 : v > 255 ? 255 : v); }
};

// RGBA -> YUV 4:2:0 (BT.709, limited range) into the planes of a MediaCodec input Image; w and h are even, chroma is the mean
// of each 2x2 block.
inline void RgbaToYuv420(const uint8_t* rgba, size_t stride, int w, int h, uint8_t* y, size_t y_stride, uint8_t* u, uint8_t* v,
                         size_t uv_stride, size_t uv_pixel_stride) {
  for (int j = 0; j < h; ++j) {
    const uint8_t* s = rgba + static_cast<size_t>(j) * stride;
    for (int i = 0; i < w; ++i, s += 4)
      y[static_cast<size_t>(j) * y_stride + i] = static_cast<uint8_t>(((47 * s[0] + 157 * s[1] + 16 * s[2] + 128) >> 8) + 16);
  }
  for (int j = 0; j < h; j += 2)
    for (int i = 0; i < w; i += 2) {
      const uint8_t* a = rgba + static_cast<size_t>(j) * stride + static_cast<size_t>(i) * 4;
      const uint8_t* b = a + stride;
      const int r = (a[0] + a[4] + b[0] + b[4] + 2) >> 2, g = (a[1] + a[5] + b[1] + b[5] + 2) >> 2,
                bl = (a[2] + a[6] + b[2] + b[6] + 2) >> 2;
      const size_t c = static_cast<size_t>(j >> 1) * uv_stride + static_cast<size_t>(i >> 1) * uv_pixel_stride;
      u[c] = static_cast<uint8_t>(((-26 * r - 87 * g + 112 * bl + 128) >> 8) + 128);
      v[c] = static_cast<uint8_t>(((112 * r - 102 * g - 10 * bl + 128) >> 8) + 128);
    }
}

}  // namespace fnlapp
