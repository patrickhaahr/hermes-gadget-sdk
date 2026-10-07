// The Wi-Fi setup QR code: payload building and the vendored encoder.
#include <string>

#include "check.hpp"
#include "hg/qr.hpp"

namespace {

// FNV-1a over the module bits, so a change in the encoder's output is caught.
uint32_t fingerprint(const hg::qr::Code& code) {
  uint32_t h = 2166136261u;
  for (int y = 0; y < code.size; ++y) {
    for (int x = 0; x < code.size; ++x) {
      h ^= code.at(x, y) ? 1u : 0u;
      h *= 16777619u;
    }
  }
  return h;
}

}  // namespace

TEST("qr: the payload is the standard WIFI scheme a phone camera reads") {
  CHECK_EQ(hg::qr::payload("Kitchen", "hunter2000"), std::string("WIFI:T:WPA;S:Kitchen;P:hunter2000;;"));
  // An empty password means an open network.
  CHECK_EQ(hg::qr::payload("Guest", ""), std::string("WIFI:T:nopass;S:Guest;;"));
}

TEST("qr: reserved characters in the credentials are escaped") {
  // `;`, `,`, `:`, `\` and `"` are field separators in the WIFI: scheme.
  CHECK_EQ(hg::qr::payload("a;b,c:d", "p\\q\"r"), std::string("WIFI:T:WPA;S:a\\;b\\,c\\:d;P:p\\\\q\\\"r;;"));
}

TEST("qr: a Wi-Fi payload encodes to a square matrix of the right size") {
  const std::string text = hg::qr::payload("Hermes-A1B2", "3f9c2a1b4d5e6f70");
  const hg::qr::Code code = hg::qr::encode(text);
  CHECK(code.ok());
  CHECK_EQ(code.size % 4, 1);  // every QR version is 4n+17
  CHECK(code.size >= 21 && code.size <= 4 * hg::qr::kMaxVersion + 17);
  CHECK_EQ(static_cast<int>(code.modules.size()), code.size * code.size);
  // A QR code always has a dark module at the bottom-left finder's corner.
  CHECK(code.at(0, code.size - 1));
  // The three finder patterns start with a 7-module dark run.
  for (int i = 0; i < 7; ++i) {
    CHECK(code.at(i, 0) && code.at(0, i));
    CHECK(code.at(code.size - 1 - i, 0) && code.at(code.size - 1, i));
  }
}

TEST("qr: the encoder's output is pinned to a known-good matrix") {
  // Regression lock. The matrix was decoded with a real scanner (zxing-cpp) and
  // cross-checked against an independent encoder; this catches any change to the
  // vendored code. The encoder picks the smallest version that fits, so this
  // 45-byte payload lands at version 4.
  const hg::qr::Code code = hg::qr::encode(hg::qr::payload("Hermes-A1B2", "3f9c2a1b4d5e6f70"));
  CHECK_EQ(code.size, 33);
  CHECK_EQ(fingerprint(code), uint32_t(0x11260e04u));
}

TEST("qr: a payload too large to encode is refused, not truncated") {
  // Far beyond what any version this build supports can hold.
  const std::string huge(2000, 'A');
  CHECK(!hg::qr::encode(huge).ok());
  CHECK_EQ(hg::qr::smallest_version(huge), 0);

  // Just over a small version's capacity still finds a larger one.
  const std::string medium(200, 'B');
  CHECK(hg::qr::encode(medium).ok());
  CHECK(hg::qr::smallest_version(medium) > 1);
}

TEST("qr: encoding is deterministic") {
  const std::string text = hg::qr::payload("Net", "password1");
  CHECK_EQ(fingerprint(hg::qr::encode(text)), fingerprint(hg::qr::encode(text)));
}
