// CST816 touch controller: report parsing and the poll-failure release.
#pragma once
#include <cstdint>

namespace hg {
// After five failed CST816 polls, release a touch that is still held.
class Cst816Fault {
 public:
  void valid() { failures_ = 0; }
  bool release_due(bool held) {
    if (failures_ < 5) ++failures_;
    return held && failures_ >= 5;
  }
 private:
  uint8_t failures_ = 0;
};

// CST816 report at register 0x02: count, X high/low, Y high/low.
// False for a touch outside width x height.
inline bool cst816_touch(const uint8_t* data, uint16_t width, uint16_t height, bool& down, int16_t& x, int16_t& y) {
  down = (data[0] & 0x0f) != 0;
  x = ((data[1] & 0x0f) << 8) | data[2];
  y = ((data[3] & 0x0f) << 8) | data[4];
  return !down || (x < width && y < height);
}
}  // namespace hg
