#include <string>
#include <vector>

#include "check.hpp"
#include "shared_reply.hpp"

namespace {

// A fake wake-up: records gives and waits, and answers waits from a flag.
struct Signal {
  static inline std::vector<std::string> ops;
  bool given = false;
  void give() {
    ops.push_back("give");
    given = true;
  }
  bool wait(uint32_t ms) {
    ops.push_back("wait " + std::to_string(ms));
    return given;
  }
};

using Reply = hg::SharedReply<Signal>;
using Ops = std::vector<std::string>;

}  // namespace

TEST("SharedReply: the answer reaches an asker that is still waiting") {
  Signal::ops.clear();
  auto* slot = Reply::create();
  slot->answer("@ok ready");
  CHECK(slot->wait(3000));
  CHECK_EQ(slot->reply(), std::string("@ok ready"));
  CHECK_EQ(Reply::live(), 1);  // the asker still holds its share
  slot->release();
  CHECK_EQ(Reply::live(), 0);
  CHECK(Signal::ops == Ops({"give", "wait 3000"}));
}

TEST("SharedReply: an answer after the asker gave up is dropped, not written into freed memory") {
  Signal::ops.clear();
  auto* slot = Reply::create();
  CHECK(!slot->wait(3000));
  slot->release();           // the asker is gone
  CHECK_EQ(Reply::live(), 1);  // but the slot stays for the answerer
  slot->answer("@ok late");  // writes into live memory, gives a live signal, then frees the slot
  CHECK_EQ(Reply::live(), 0);
  CHECK(Signal::ops == Ops({"wait 3000", "give"}));
}

TEST("SharedReply: an asker whose question was never queued frees both shares") {
  auto* slot = Reply::create();
  slot->release();  // the answerer's share, since nobody will answer
  slot->release();  // the asker's own
  CHECK_EQ(Reply::live(), 0);
}
