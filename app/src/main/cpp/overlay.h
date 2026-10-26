// Draws the engine's per-person vertex-id maps over the frame they were computed on: person where the bilinearly upsampled margin
// is positive, coloured by a bilinear blend of the neighbouring cells' vertex colours.
#pragma once

#include <cstdint>
#include <vector>

#include "fnl/engine.h"
#include "frame_convert.h"

namespace fnlapp {

// Per-vertex colours (n*3 RGB8), indexed by the SMPL vertex id graph C returns.
struct VertexColors {
  std::vector<uint8_t> rgb;
  int count() const { return static_cast<int>(rgb.size() / 3); }
};

// `rgba` (frame.w * frame.h * 4) = the frame, with every person's pixels blended towards their vertex colour by `alpha`.
// The result's boxes map to the frame as net px / scale + crop (the engine's centered crop of the frame it was given).
void Composite(const RgbImage& frame, const fnl::FrameResult& result, const VertexColors& colors, float alpha, uint8_t* rgba);

}  // namespace fnlapp
