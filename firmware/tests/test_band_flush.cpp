#include <deque>
#include <string>
#include <vector>

#include "band_flush.hpp"
#include "check.hpp"

namespace {

// A fake panel: records each submit and wait, and answers waits from a script
// (true when nothing is scripted).
struct Panel {
  std::vector<std::string> ops;
  std::deque<bool> completions;
  bool submit_ok = true;
  hg::BandFlush bands{60, 20, 50,
                      [this](int y, int rows) {
                        ops.push_back("draw " + std::to_string(y) + "+" + std::to_string(rows));
                        return submit_ok;
                      },
                      [this](uint32_t ms) {
                        ops.push_back("wait " + std::to_string(ms));
                        if (completions.empty()) return true;
                        bool done = completions.front();
                        completions.pop_front();
                        return done;
                      }};
};

using Ops = std::vector<std::string>;
using Event = hg::BandFlush::Event;

}  // namespace

TEST("BandFlush: each band's transfer completes before the buffer is refilled") {
  Panel p;
  CHECK(p.bands.flush(10, 55) == Event::None);
  CHECK(p.ops == Ops({"draw 10+20", "wait 50", "draw 30+20", "wait 50", "draw 50+5", "wait 50"}));
}

TEST("BandFlush: a late transfer pauses drawing without blocking, then redraws everything") {
  Panel p;
  p.completions = {true, false};
  CHECK(p.bands.flush(0, 60) == Event::TimedOut);
  CHECK(p.ops == Ops({"draw 0+20", "wait 50", "draw 20+20", "wait 50"}));

  p.ops.clear();
  p.completions = {false};
  CHECK(p.bands.flush(40, 60) == Event::None);
  CHECK(p.ops == Ops({"wait 0"}));

  p.ops.clear();
  CHECK(p.bands.flush(20, 40) == Event::Resumed);
  CHECK(p.ops == Ops({"wait 0", "draw 0+20", "wait 50", "draw 20+20", "wait 50", "draw 40+20", "wait 50"}));

  p.ops.clear();
  CHECK(p.bands.flush(0, 20) == Event::None);
  CHECK(p.ops == Ops({"draw 0+20", "wait 50"}));
}

TEST("BandFlush: a submit error stops drawing for good") {
  Panel p;
  p.submit_ok = false;
  CHECK(p.bands.flush(0, 60) == Event::Failed);
  CHECK(p.ops == Ops({"draw 0+20"}));

  p.ops.clear();
  p.submit_ok = true;
  CHECK(p.bands.flush(0, 60) == Event::None);
  CHECK(p.ops.empty());
}
