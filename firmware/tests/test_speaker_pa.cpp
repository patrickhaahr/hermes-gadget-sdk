#include <deque>
#include "check.hpp"
#include "speaker_pa.hpp"

namespace {
struct FakeSpeaker {
  std::deque<int16_t> queue;
  bool pa = false;
  hg::SpeakerPa gate{
      [this](void* chunk, size_t bytes) {
        auto* out = static_cast<int16_t*>(chunk);
        size_t n = 0;
        for (; n < bytes / sizeof(int16_t) && !queue.empty(); ++n) {
          out[n] = queue.front();
          queue.pop_front();
        }
        return n * sizeof(int16_t);
      },
      [this] { queue.clear(); },
      [this](bool on) { pa = on; }};
};
}  // namespace

TEST("SpeakerPa: abort empties the queue at once and keeps audio queued after it") {
  FakeSpeaker s;
  s.queue = {1, 1, 1, 1};
  int16_t chunk[2] = {};
  uint32_t generation = 0;
  CHECK_EQ(s.gate.receive(chunk, sizeof(chunk), generation), sizeof(chunk));
  CHECK(s.gate.enable(generation));
  CHECK(s.pa);
  s.gate.abort();
  CHECK(!s.pa);
  CHECK(s.queue.empty());
  // A reply that starts before the speaker task runs again must play in full.
  s.queue = {2, 2, 2};
  CHECK_EQ(s.gate.receive(chunk, sizeof(chunk), generation), sizeof(chunk));
  CHECK_EQ(chunk[0], int16_t(2));
  CHECK(s.gate.enable(generation));
  CHECK_EQ(s.queue.size(), size_t(1));
}

TEST("SpeakerPa: a chunk taken before abort cannot turn the amplifier back on") {
  FakeSpeaker s;
  s.queue = {1, 1};
  int16_t chunk[2] = {};
  uint32_t generation = 0;
  CHECK_EQ(s.gate.receive(chunk, sizeof(chunk), generation), sizeof(chunk));
  s.gate.abort();
  CHECK(!s.gate.enable(generation));
  CHECK(!s.pa);
  CHECK_EQ(s.gate.receive(chunk, sizeof(chunk), generation), size_t(0));
  CHECK(s.gate.enable(generation));
  s.gate.disable();
  CHECK(!s.pa);
}
