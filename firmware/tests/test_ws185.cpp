#include <array>
#include <vector>
#include "check.hpp"
#include "cst816.hpp"
#include "tca9554.hpp"
#include "ws185.hpp"

TEST("TCA9554 reset: latch low before output mode, preserve all other expander bits") {
  std::array<uint8_t, 4> regs = {0, 0xa5, 0x5a, 0xff};
  std::vector<std::pair<uint8_t, uint8_t>> writes;
  std::vector<uint32_t> delays;
  hg::Tca9554Reset resets(
      0x03,
      [&](uint8_t reg, uint8_t& value) { value = regs[reg]; return true; },
      [&](uint8_t reg, uint8_t value) { regs[reg] = value; writes.emplace_back(reg, value); return true; },
      [&](uint32_t ms) { delays.push_back(ms); });
  CHECK(resets.begin());
  CHECK_EQ(writes.size(), size_t(3));
  CHECK_EQ(writes[0].first, uint8_t(1));
  CHECK_EQ(writes[0].second, uint8_t(0xa4));
  CHECK_EQ(writes[1].first, uint8_t(3));
  CHECK_EQ(writes[1].second, uint8_t(0xfc));
  CHECK_EQ(writes[2].first, uint8_t(1));
  CHECK_EQ(writes[2].second, uint8_t(0xa7));
  CHECK_EQ(regs[2], uint8_t(0x5a));
  CHECK(delays == std::vector<uint32_t>({10, 50}));
}

TEST("TCA9554 reset: another board's mask changes only its own bits") {
  std::array<uint8_t, 4> regs = {0, 0x00, 0, 0xff};
  std::vector<std::pair<uint8_t, uint8_t>> writes;
  hg::Tca9554Reset resets(
      0x05,
      [&](uint8_t reg, uint8_t& value) { value = regs[reg]; return true; },
      [&](uint8_t reg, uint8_t value) { regs[reg] = value; writes.emplace_back(reg, value); return true; },
      [](uint32_t) {});
  CHECK(resets.begin());
  CHECK_EQ(writes.size(), size_t(3));
  CHECK_EQ(writes[1].second, uint8_t(0xfa));
  CHECK_EQ(regs[1], uint8_t(0x05));
}

TEST("TCA9554 reset: every failed expander operation stops initialization") {
  for (int fail = 0; fail < 5; ++fail) {
    int operation = 0, delays = 0;
    hg::Tca9554Reset resets(
        0x03,
        [&](uint8_t, uint8_t& value) { value = 0xff; return operation++ != fail; },
        [&](uint8_t, uint8_t) { return operation++ != fail; },
        [&](uint32_t) { ++delays; });
    CHECK(!resets.begin());
    CHECK_EQ(operation, fail + 1);
    CHECK_EQ(delays, fail == 4 ? 1 : 0);
  }
}

TEST("CST816: count and 12-bit coordinates, release and invalid reports") {
  bool down = false;
  int16_t x = 0, y = 0;
  uint8_t data[] = {1, 0x81, 0x67, 0x41, 0x66};
  CHECK(hg::cst816_touch(data, 360, 360, down, x, y));
  CHECK(down);
  CHECK_EQ(x, int16_t(359));
  CHECK_EQ(y, int16_t(358));
  CHECK(!hg::cst816_touch(data, 359, 360, down, x, y));  // outside the glass, not clamped
  CHECK(!hg::cst816_touch(data, 360, 358, down, x, y));
  data[0] = 0;
  CHECK(hg::cst816_touch(data, 360, 360, down, x, y));
  CHECK(!down);
}

TEST("WS185: RMNM conversion uses both mics and excludes reference/unused") {
  const int16_t raw[] = {32767, 200, -32768, 600, -10000, -32768, 10000, -32768,
                         1234, 32767, -1234, 32767, 100, 32767, 100, -32768};
  int16_t mono[4] = {};
  hg::ws185_mono(raw, mono, 4);
  CHECK_EQ(mono[0], int16_t(400));
  CHECK_EQ(mono[1], int16_t(-32768));
  CHECK_EQ(mono[2], int16_t(32767));
  CHECK_EQ(mono[3], int16_t(0));
}

TEST("CST816: touch fault releases only a held touch after five failures and recovers") {
  hg::Cst816Fault fault;
  for (int i = 0; i < 4; ++i) CHECK(!fault.release_due(true));
  CHECK(fault.release_due(true));
  CHECK(fault.release_due(true));  // failed event enqueue must be retryable
  CHECK(!fault.release_due(false));  // no duplicate release after delivery
  fault.valid();
  CHECK(!fault.release_due(true));
}

TEST("WS185: signed PCM16 expands to stereo32 without negative-shift UB") {
  const int16_t mono[] = {-32768, -1, 0, 1, 32767};
  int32_t stereo[10] = {};
  hg::ws185_stereo32(mono, stereo, 5);
  for (size_t i = 0; i < 5; ++i) {
    CHECK_EQ(stereo[2 * i], static_cast<int32_t>(mono[i]) * 65536);
    CHECK_EQ(stereo[2 * i + 1], stereo[2 * i]);
  }
  CHECK_EQ(stereo[0], INT32_MIN);
}
