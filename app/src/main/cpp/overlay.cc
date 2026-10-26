#include "overlay.h"

#include <algorithm>
#include <cmath>
#include <cstring>

namespace fnlapp {

void Composite(const RgbImage& frame, const fnl::FrameResult& result, const VertexColors& colors, float alpha, uint8_t* rgba) {
  const size_t n = static_cast<size_t>(frame.w) * frame.h;
  for (size_t i = 0; i < n; ++i) {
    rgba[i * 4] = frame.px[i * 3], rgba[i * 4 + 1] = frame.px[i * 3 + 1], rgba[i * 4 + 2] = frame.px[i * 3 + 2];
    rgba[i * 4 + 3] = 255;
  }
  if (alpha <= 0) return;

  const int res = result.res;
  const int a = static_cast<int>(alpha * 256 + 0.5f), ia = 256 - a;
  std::vector<int> c0(frame.w), c1(frame.w);
  std::vector<float> cf(frame.w);
  std::vector<uint8_t> cell_rgb(static_cast<size_t>(res) * res * 3);
  std::vector<uint8_t> cell_ok(static_cast<size_t>(res) * res);
  for (const fnl::Person& p : result.people) {
    const float x1 = p.box.x1 / result.scale + result.crop_x, y1 = p.box.y1 / result.scale + result.crop_y;
    const float x2 = p.box.x2 / result.scale + result.crop_x, y2 = p.box.y2 / result.scale + result.crop_y;
    const float bw = x2 - x1, bh = y2 - y1;
    if (bw < 1 || bh < 1) continue;
    const int px0 = std::max(static_cast<int>(std::floor(x1)), 0), px1 = std::min(static_cast<int>(std::ceil(x2)), frame.w);
    const int py0 = std::max(static_cast<int>(std::floor(y1)), 0), py1 = std::min(static_cast<int>(std::ceil(y2)), frame.h);

    // colour of every map cell; cells without a vertex id take no part in the colour interpolation
    for (int i = 0; i < res * res; ++i) {
      const int id = p.vertex[i];
      cell_ok[i] = id >= 0 && id < colors.count();
      if (cell_ok[i]) std::memcpy(&cell_rgb[i * 3], &colors.rgb[static_cast<size_t>(id) * 3], 3);
    }

    // frame column -> the two map columns it interpolates between, and the weight of the second
    for (int x = px0; x < px1; ++x) {
      const float u = std::min(std::max((x + 0.5f - x1) / bw * res - 0.5f, 0.f), res - 1.f);
      c0[x] = std::min(static_cast<int>(u), res - 1);
      c1[x] = std::min(c0[x] + 1, res - 1);
      cf[x] = u - c0[x];
    }
    for (int y = py0; y < py1; ++y) {
      const float v = std::min(std::max((y + 0.5f - y1) / bh * res - 0.5f, 0.f), res - 1.f);
      const int r0 = std::min(static_cast<int>(v), res - 1), r1 = std::min(r0 + 1, res - 1);
      const float fv = v - r0;
      for (int x = px0; x < px1; ++x) {
        const int idx[4] = {r0 * res + c0[x], r0 * res + c1[x], r1 * res + c0[x], r1 * res + c1[x]};
        const float w[4] = {(1 - cf[x]) * (1 - fv), cf[x] * (1 - fv), (1 - cf[x]) * fv, cf[x] * fv};
        // person where the interpolated margin is positive; colour: bilinear over the neighbouring cells that have one
        float m = 0, ws = 0, rgb[3] = {0, 0, 0};
        for (int k = 0; k < 4; ++k) {
          m += w[k] * p.margin[idx[k]];
          const float wk = w[k] * cell_ok[idx[k]];
          ws += wk;
          for (int ch = 0; ch < 3; ++ch) rgb[ch] += wk * cell_rgb[idx[k] * 3 + ch];
        }
        if (m <= 0.f || ws <= 1e-6f) continue;
        const uint8_t c[3] = {static_cast<uint8_t>(rgb[0] / ws + 0.5f), static_cast<uint8_t>(rgb[1] / ws + 0.5f),
                              static_cast<uint8_t>(rgb[2] / ws + 0.5f)};
        uint8_t* d = rgba + (static_cast<size_t>(y) * frame.w + x) * 4;
        d[0] = static_cast<uint8_t>((d[0] * ia + c[0] * a) >> 8);
        d[1] = static_cast<uint8_t>((d[1] * ia + c[1] * a) >> 8);
        d[2] = static_cast<uint8_t>((d[2] * ia + c[2] * a) >> 8);
      }
    }
  }
}

}  // namespace fnlapp
