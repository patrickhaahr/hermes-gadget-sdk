// Wi-Fi setup QR payloads, backed by the vendored ricmoo/QRCode encoder.
//
// The device shows a QR code on the Wi-Fi setup screen so a phone camera can
// join the gadget's temporary network without typing the SSID and password.
// The payload uses the de-facto "WIFI:" scheme that Android and iOS both read.
#pragma once

#include <cstdint>
#include <string>
#include <string_view>
#include <vector>

namespace hg::qr {

// The largest matrix this build encodes (see QRCODE_MAX_VERSION). Version 12 is
// 65x65 modules, far more than a Wi-Fi payload needs.
constexpr int kMaxVersion = 12;

// A rendered module matrix: `size` x `size` bits, row-major, true = dark.
struct Code {
  int size = 0;
  std::vector<uint8_t> modules;  // one byte per module, 0 or 1

  bool ok() const { return size > 0; }
  bool at(int x, int y) const { return modules[static_cast<size_t>(y) * size + x] != 0; }
};

// Builds the QR payload for a WPA/WPA2 network:
//   WIFI:T:WPA;S:<ssid>;P:<password>;;
// `;`, `,`, `:`, `\` and `"` are backslash-escaped as the scheme requires.
// An empty password yields an open-network payload (T:nopass).
std::string payload(std::string_view ssid, std::string_view password);

// Encodes `text` at the given error-correction level, or the lowest version
// that fits. Returns an empty Code when the text does not fit kMaxVersion.
Code encode(std::string_view text, int ecc = 1);  // 1 = ECC_MEDIUM

// The smallest version that holds `text`, or 0 when none does.
int smallest_version(std::string_view text, int ecc = 1);

}  // namespace hg::qr
