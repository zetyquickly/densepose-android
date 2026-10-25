#include "overlay.h"

#include <algorithm>
#include <cmath>

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
  std::vector<int> c0(frame.w), c1(frame.w), cn(frame.w);
  std::vector<float> cf(frame.w);
  for (const fnl::Person& p : result.people) {
    const float x1 = p.box.x1 / result.scale + result.crop_x, y1 = p.box.y1 / result.scale + result.crop_y;
    const float x2 = p.box.x2 / result.scale + result.crop_x, y2 = p.box.y2 / result.scale + result.crop_y;
    const float bw = x2 - x1, bh = y2 - y1;
    if (bw < 1 || bh < 1) continue;
    const int px0 = std::max(static_cast<int>(std::floor(x1)), 0), px1 = std::min(static_cast<int>(std::ceil(x2)), frame.w);
    const int py0 = std::max(static_cast<int>(std::floor(y1)), 0), py1 = std::min(static_cast<int>(std::ceil(y2)), frame.h);

    // frame column -> the two map columns it interpolates between (mask) and the nearest one (vertex id)
    for (int x = px0; x < px1; ++x) {
      const float u = std::min(std::max((x + 0.5f - x1) / bw * res - 0.5f, 0.f), res - 1.f);
      c0[x] = std::min(static_cast<int>(u), res - 1);
      c1[x] = std::min(c0[x] + 1, res - 1);
      cf[x] = u - c0[x];
      cn[x] = std::min(static_cast<int>(u + 0.5f), res - 1);
    }
    for (int y = py0; y < py1; ++y) {
      const float v = std::min(std::max((y + 0.5f - y1) / bh * res - 0.5f, 0.f), res - 1.f);
      const int r0 = std::min(static_cast<int>(v), res - 1), r1 = std::min(r0 + 1, res - 1);
      const float fv = v - r0;
      const int rn = std::min(static_cast<int>(v + 0.5f), res - 1);
      for (int x = px0; x < px1; ++x) {
        const float top = p.person[r0 * res + c0[x]] * (1 - cf[x]) + p.person[r0 * res + c1[x]] * cf[x];
        const float bot = p.person[r1 * res + c0[x]] * (1 - cf[x]) + p.person[r1 * res + c1[x]] * cf[x];
        if (top * (1 - fv) + bot * fv < 0.5f) continue;
        int id = p.vertex[rn * res + cn[x]];
        if (id < 0) {  // on the mask edge but between vertex cells: take any valid neighbour
          for (int dy = -1; dy <= 1 && id < 0; ++dy)
            for (int dx = -1; dx <= 1 && id < 0; ++dx) {
              const int yy = rn + dy, xx = cn[x] + dx;
              if (yy >= 0 && yy < res && xx >= 0 && xx < res) id = p.vertex[yy * res + xx];
            }
          if (id < 0) continue;
        }
        if (id >= colors.count()) continue;
        uint8_t* d = rgba + (static_cast<size_t>(y) * frame.w + x) * 4;
        const uint8_t* c = &colors.rgb[static_cast<size_t>(id) * 3];
        d[0] = static_cast<uint8_t>((d[0] * ia + c[0] * a) >> 8);
        d[1] = static_cast<uint8_t>((d[1] * ia + c[1] * a) >> 8);
        d[2] = static_cast<uint8_t>((d[2] * ia + c[2] * a) >> 8);
      }
    }
  }
}

}  // namespace fnlapp
