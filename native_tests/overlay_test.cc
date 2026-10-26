// Host test of the app's pixel code (no Android, no TFLite).
//
//   overlay_test convert OUT_PREFIX            rotation / area-average / YUV checks against closed-form expectations
//   overlay_test render RESULTS.fnl FRAMES.rgb W H COLORS.bin OUT.ppm FRAME_INDEX
//     composites one `fnl_run --save` result over its frame with Composite() (compare with engine/tools/render_overlay.py)
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <string>
#include <vector>

#include "frame_convert.h"
#include "overlay.h"

using namespace fnlapp;

static int failures = 0;
#define CHECK(c)                                                      \
  do {                                                                \
    if (!(c)) {                                                       \
      std::fprintf(stderr, "FAIL %s:%d: %s\n", __FILE__, __LINE__, #c); \
      ++failures;                                                     \
    }                                                                 \
  } while (0)

static int Convert() {
  // 4x2 RGBA image, pixel (x, y) = (x*10, y*10, 7)
  const int w = 4, h = 2;
  std::vector<uint8_t> rgba(w * h * 4);
  for (int y = 0; y < h; ++y)
    for (int x = 0; x < w; ++x) {
      uint8_t* p = &rgba[(y * w + x) * 4];
      p[0] = x * 10, p[1] = y * 10, p[2] = 7, p[3] = 255;
    }
  for (int rot : {0, 90, 180, 270}) {
    int ow, oh;
    WorkingSize(w, h, rot, 0, &ow, &oh);
    CHECK(ow == ((rot % 180) ? h : w) && oh == ((rot % 180) ? w : h));
    std::vector<uint8_t> out(ow * oh * 3);
    Resample(w, h, rot, ow, oh, RgbaFetch{rgba.data(), static_cast<size_t>(w * 4)}, nullptr, out.data());
    // rotating clockwise by `rot`: upright pixel (x, y) came from this source pixel
    for (int y = 0; y < oh; ++y)
      for (int x = 0; x < ow; ++x) {
        int sx = x, sy = y;
        if (rot == 90) sx = y, sy = h - 1 - x;
        if (rot == 180) sx = w - 1 - x, sy = h - 1 - y;
        if (rot == 270) sx = w - 1 - y, sy = x;
        CHECK(out[(y * ow + x) * 3] == sx * 10 && out[(y * ow + x) * 3 + 1] == sy * 10);
      }
  }
  // 90 degrees clockwise: the top-left source pixel ends up top-right
  {
    std::vector<uint8_t> out(h * w * 3);
    Resample(w, h, 90, h, w, RgbaFetch{rgba.data(), static_cast<size_t>(w * 4)}, nullptr, out.data());
    CHECK(out[(0 * h + (h - 1)) * 3] == 0 && out[(0 * h + (h - 1)) * 3 + 1] == 0);  // (0,0) -> (h-1, 0)
    CHECK(out[((w - 1) * h + (h - 1)) * 3] == 30 && out[((w - 1) * h + (h - 1)) * 3 + 1] == 0);  // (3,0) -> (h-1, w-1): bottom-right
  }
  // area average: 4x2 -> 2x1 (mean of 2x2 blocks), with a row stride larger than the row
  {
    std::vector<uint8_t> padded(w * h * 4 + h * 8, 0);
    const size_t stride = w * 4 + 8;
    for (int y = 0; y < h; ++y) std::memcpy(&padded[y * stride], &rgba[y * w * 4], w * 4);
    int ow, oh;
    WorkingSize(w, h, 0, 2, &ow, &oh);
    CHECK(ow == 2 && oh == 1);
    std::vector<uint8_t> out(ow * oh * 3);
    Resample(w, h, 0, ow, oh, RgbaFetch{padded.data(), stride}, nullptr, out.data());
    CHECK(out[0] == 5 && out[1] == 5 && out[3] == 25 && out[4] == 5);  // x: (0+10)/2, (20+30)/2; y: (0+10)/2
  }
  // YUV: grey, and a saturated red, BT.601 limited range, NV12-style (pixel stride 2) chroma
  {
    const int W = 2, H = 2;
    uint8_t Y[4] = {126, 126, 126, 126};          // 1.164*(126-16) = 128
    uint8_t UV[2] = {128, 128};                    // U, V interleaved
    uint8_t rgb[3];
    YuvFetch f{Y, UV, UV + 1, W, W, 2, MakeYuvMatrix(YuvStandard::kBt601, false)};
    f(1, 1, rgb);
    CHECK(std::abs(rgb[0] - 128) <= 1 && std::abs(rgb[1] - 128) <= 1 && std::abs(rgb[2] - 128) <= 1);
    uint8_t Yr[4] = {81, 81, 81, 81}, UVr[2] = {90, 240};  // BT.601 red
    YuvFetch fr{Yr, UVr, UVr + 1, W, W, 2, MakeYuvMatrix(YuvStandard::kBt601, false)};
    fr(0, 0, rgb);
    CHECK(rgb[0] > 250 && rgb[1] < 6 && rgb[2] < 6);
    (void)H;
  }
  // RGB -> YUV (encoder) -> RGB (decoder fetch) round trip, planar and semi-planar chroma
  {
    const int W = 8, H = 4;
    std::vector<uint8_t> src(W * H * 4);
    for (int i = 0; i < W * H; ++i) {  // flat 2x2 blocks so chroma averaging is lossless
      const int bx = (i % W) / 2, by = (i / W) / 2;
      src[i * 4] = 40 + bx * 50, src[i * 4 + 1] = 200 - by * 90, src[i * 4 + 2] = 90 + bx * 20, src[i * 4 + 3] = 255;
    }
    for (int semi = 0; semi < 2; ++semi) {
      std::vector<uint8_t> Y(W * H), C(W * H);  // C holds U and V (planar: U | V, semi-planar: UVUV)
      uint8_t *u = C.data(), *v = semi ? C.data() + 1 : C.data() + W * H / 4;
      const size_t pix = semi ? 2 : 1;
      RgbaToYuv420(src.data(), W * 4, W, H, Y.data(), W, u, v, W / 2 * pix, pix);
      YuvFetch f{Y.data(), u, v, W, W / 2 * pix, pix, MakeYuvMatrix(YuvStandard::kBt709, false)};
      int worst = 0;
      for (int y = 0; y < H; ++y)
        for (int x = 0; x < W; ++x) {
          uint8_t rgb[3];
          f(x, y, rgb);
          for (int c = 0; c < 3; ++c) worst = std::max(worst, std::abs(rgb[c] - src[(y * W + x) * 4 + c]));
        }
      std::printf("yuv round trip (%s): worst channel error %d\n", semi ? "semi-planar" : "planar", worst);
      CHECK(worst <= 3);
    }
  }
  std::printf(failures ? "convert: FAILED\n" : "convert: ok\n");
  return failures != 0;
}

static int Render(char** a) {
  std::FILE* f = std::fopen(a[0], "rb");
  if (!f) return std::perror("results"), 1;
  char magic[4];
  uint32_t d[3];
  float crop[3];
  if (std::fread(magic, 1, 4, f) != 4 || std::memcmp(magic, "FNL2", 4) || std::fread(d, 4, 3, f) != 3 || std::fread(crop, 4, 3, f) != 3)
    return std::fprintf(stderr, "not a results file\n"), 1;
  const int W = std::atoi(a[2]), H = std::atoi(a[3]), want = std::atoi(a[6]);
  const uint32_t res = d[2];

  VertexColors colors;
  {
    std::FILE* c = std::fopen(a[4], "rb");
    if (!c) return std::perror("colors"), 1;
    std::fseek(c, 0, SEEK_END);
    colors.rgb.resize(std::ftell(c));
    std::fseek(c, 0, SEEK_SET);
    if (std::fread(colors.rgb.data(), 1, colors.rgb.size(), c) != colors.rgb.size()) return 1;
    std::fclose(c);
  }
  for (int idx = 0;; ++idx) {
    int64_t id;
    uint32_t n;
    if (std::fread(&id, 8, 1, f) != 1 || std::fread(&n, 4, 1, f) != 1) return std::fprintf(stderr, "frame %d not found\n", want), 1;
    fnl::FrameResult r;
    r.id = id, r.res = res, r.net_w = d[0], r.net_h = d[1], r.scale = crop[0], r.crop_x = static_cast<int>(crop[1]),
    r.crop_y = static_cast<int>(crop[2]);
    for (uint32_t i = 0; i < n; ++i) {
      fnl::Person p;
      p.margin.resize(res * res), p.vertex.resize(res * res);
      if (std::fread(&p.box, 4, 4, f) != 4 || std::fread(&p.score, 4, 1, f) != 1 || std::fread(p.margin.data(), 4, res * res, f) != res * res ||
          std::fread(p.vertex.data(), 4, res * res, f) != res * res)
        return std::fprintf(stderr, "truncated\n"), 1;
      r.people.push_back(std::move(p));
    }
    if (idx != want) continue;

    RgbImage frame;
    frame.w = W, frame.h = H, frame.px.resize(static_cast<size_t>(W) * H * 3);
    std::FILE* fr = std::fopen(a[1], "rb");
    if (!fr) return std::perror("frames"), 1;
    std::fseek(fr, static_cast<long>(id) * static_cast<long>(frame.px.size()), SEEK_SET);
    if (std::fread(frame.px.data(), 1, frame.px.size(), fr) != frame.px.size()) return std::fprintf(stderr, "short frame\n"), 1;
    std::fclose(fr);

    std::vector<uint8_t> rgba(static_cast<size_t>(W) * H * 4);
    Composite(frame, r, colors, 0.65f, rgba.data());
    std::FILE* o = std::fopen(a[5], "wb");
    std::fprintf(o, "P6\n%d %d\n255\n", W, H);
    for (size_t i = 0; i < static_cast<size_t>(W) * H; ++i) std::fwrite(&rgba[i * 4], 1, 3, o);
    std::fclose(o);
    std::printf("frame id %lld: %u people -> %s\n", static_cast<long long>(id), n, a[5]);
    return 0;
  }
}

int main(int argc, char** argv) {
  if (argc >= 2 && std::string(argv[1]) == "convert") return Convert();
  if (argc == 9 && std::string(argv[1]) == "render") return Render(argv + 2);
  std::fprintf(stderr, "usage: see the header comment\n");
  return 2;
}
