#pragma once

#include <stdint.h>

#include <mutex>

// App-owned copies of the latest FastCam frames, shared by the pump and the paint lanes.
namespace staging {

constexpr int kFrameW = 1920;
constexpr int kFrameH = 1300;
constexpr int kFullRowBytes = kFrameW * 2;
constexpr int kFullBytes = kFullRowBytes * kFrameH;

// Mosaic quadrants keep every other UYVY pair and every other row, matching
// fast_cam_compose_2x2, so only a quarter of each frame is copied.
constexpr int kQuarterW = kFrameW / 2;
constexpr int kQuarterH = kFrameH / 2;
constexpr int kQuarterRowBytes = kQuarterW * 2;
constexpr int kQuarterBytes = kQuarterRowBytes * kQuarterH;

constexpr int kMosaicView = 4;

// Guards every accessor below; hold it for as long as the returned pointer is read.
std::mutex& lock();

bool viewReady(int view);

// Null when that camera has not been staged since the view was requested.
const uint8_t* quarter(int cam);
const uint8_t* full(int cam);

// Changes whenever the staged copy changes, so a reader can skip re-reading it.
uint32_t quarterSeq(int cam);
uint32_t fullSeq(int cam);

}  // namespace staging
