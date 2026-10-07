#include "hg/qr.hpp"

#include <cstring>

#include "qrcode.h"

namespace hg::qr {
namespace {

// The WIFI: scheme reserves these characters inside a field value.
bool reserved(char c) { return c == '\\' || c == ';' || c == ',' || c == ':' || c == '"'; }

void append_field(std::string& out, std::string_view value) {
  for (char c : value) {
    if (reserved(c)) out.push_back('\\');
    out.push_back(c);
  }
}

// One reusable buffer: the app encodes on a single thread and keeps the result
// in the UI model, so a second concurrent encode never happens.
uint8_t g_modules[(kMaxVersion * 4 + 17) * (kMaxVersion * 4 + 17) / 8 + 1];

Code from(const QRCode& q) {
  Code code;
  code.size = q.size;
  code.modules.resize(static_cast<size_t>(q.size) * q.size);
  for (int y = 0; y < q.size; ++y) {
    for (int x = 0; x < q.size; ++x) {
      code.modules[static_cast<size_t>(y) * q.size + x] = qrcode_getModule(const_cast<QRCode*>(&q), x, y) ? 1 : 0;
    }
  }
  return code;
}

}  // namespace

std::string payload(std::string_view ssid, std::string_view password) {
  std::string out = password.empty() ? "WIFI:T:nopass;S:" : "WIFI:T:WPA;S:";
  append_field(out, ssid);
  if (!password.empty()) {
    out += ";P:";
    append_field(out, password);
  }
  out += ";;";
  return out;
}

int smallest_version(std::string_view text, int ecc) {
  QRCode q;
  for (int version = 1; version <= kMaxVersion; ++version) {
    if (qrcode_initBytes(&q, g_modules, static_cast<uint8_t>(version), static_cast<uint8_t>(ecc),
                         reinterpret_cast<uint8_t*>(const_cast<char*>(text.data())),
                         static_cast<uint16_t>(text.size())) == 0) {
      return version;
    }
  }
  return 0;
}

Code encode(std::string_view text, int ecc) {
  QRCode q;
  for (int version = 1; version <= kMaxVersion; ++version) {
    if (qrcode_initBytes(&q, g_modules, static_cast<uint8_t>(version), static_cast<uint8_t>(ecc),
                         reinterpret_cast<uint8_t*>(const_cast<char*>(text.data())),
                         static_cast<uint16_t>(text.size())) == 0) {
      return from(q);
    }
  }
  return {};
}

}  // namespace hg::qr
