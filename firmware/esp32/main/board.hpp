// Board description: which peripherals exist and how they are wired.
//
// Select the board's pins and drivers in board.cpp through the Kconfig choice.
// Board-specific power/reset sequencing runs before peripheral initialization.
// See docs/porting.md for the display, audio, input and power contracts.
#pragma once

#include <cstdint>

namespace hgp {

// How an I80 panel is wired: eight data lines and a write strobe.
struct LcdBus {
  enum class Type : uint8_t { Spi, I80 };
  Type type = Type::Spi;
  int data[8] = {-1, -1, -1, -1, -1, -1, -1, -1};
  int wr = -1;
  int pclk_mhz = 16;
};

enum class LcdController { St7789, Box3, CoreS3 };

struct LcdConfig {
  bool enabled = false;
  uint16_t width = 320, height = 240;  // after rotation
  bool swap_xy = true, mirror_x = true, mirror_y = false, invert = true;
  int gap_x = 0, gap_y = 0;
  int mosi = -1, sclk = -1, cs = -1, dc = -1, rst = -1, backlight = -1;
  int spi_mhz = 40;
  LcdBus bus{};
  LcdController controller = LcdController::St7789;
  bool reset_active_high = false;
};

struct I2sMicConfig {
  bool enabled = false;
  int sck = -1, ws = -1, sd = -1;
};

struct I2sSpeakerConfig {
  bool enabled = false;
  int bclk = -1, ws = -1, dout = -1;
};

// Which vendor bring-up table a CO5300 panel needs. The round 466x466 1.75"
// modules write extra page-2 registers; the rectangular 368x448 1.8" modules use
// the shorter table from Waveshare's own board example.
enum class AmoledPanel { Co5300_466, Co5300_368 };

// QSPI AMOLED with a CO5300 controller (round 466x466 or rectangular 368x448).
struct AmoledConfig {
  bool enabled = false;
  uint16_t width = 466, height = 466;
  int cs = -1, sclk = -1, d0 = -1, d1 = -1, d2 = -1, d3 = -1, rst = -1;
  int gap_x = 0, gap_y = 0;  // the controller's RAM is wider than the glass
  int qspi_mhz = 40;
  bool round = false;
  AmoledPanel panel = AmoledPanel::Co5300_466;
};

struct I2cBusConfig {
  int sda = -1, scl = -1;
  uint32_t hz = 400000;
};

// ES8311/AW88298 (speaker) and the microphone ADC, sharing one duplex I2S bus,
// controlled over the I2C bus.
enum class SpeakerCodec { Es8311, Aw88298 };

// The microphone front end: an ES7210 digital ADC for MEMS microphones, or the
// ES8311's own ADC for a board that wires an analog electret mic to the speaker
// codec (the 1.8" AMOLED module does the latter).
enum class MicCodec { Es7210, Es8311 };

struct CodecAudioConfig {
  bool enabled = false;
  int mclk = -1, bclk = -1, ws = -1, dout = -1, din = -1;
  int pa = -1;               // speaker amplifier enable, active high
  float amp_supply_v = 5.0f;  // amplifier supply; the ES8311 driver sets its output level from it
  float mic_gain_db = 24.0f;
  SpeakerCodec speaker = SpeakerCodec::Es8311;
  MicCodec mic = MicCodec::Es7210;
};

// Capacitive touch on the I2C bus: hold to talk, tap, swipe down to cancel.
enum class TouchController { Cst9217, Box3, Ft5x06, Cst820 };

struct TouchConfig {
  bool enabled = false;
  uint8_t addr = 0x5A;
  int rst = -1;
  uint16_t width = 0, height = 0;
  bool mirror_x = false, mirror_y = false;
  TouchController controller = TouchController::Cst9217;
};

// A key whose level is read from a TCA9554 I/O expander input (e.g. a PMIC's
// power key). Acts as CANCEL: a press cancels, holding 2 s starts a new session.
struct ExpanderKeyConfig {
  bool enabled = false;
  uint8_t addr = 0x20;
  uint8_t bit = 0;
  bool active_high = true;
};

struct ButtonConfig {
  int talk = -1, cancel = -1, up = -1, down = -1;  // active-low GPIOs, -1 = absent
};

// Boards whose display and touch controllers are held in reset by a TCA9554 I/O
// expander instead of direct GPIOs (LCD_RST, the DSI power rail and TOUCH_RST sit
// on its output bits). The sequence runs once, before the display is initialised.
struct ExpanderResetConfig {
  bool enabled = false;
  uint8_t addr = 0x20;
};

// A battery behind a resistive divider, with a latch that keeps it powered.
struct LatchPowerConfig {
  bool enabled = false;
  int adc = -1, enable = -1, charging = -1;
  int backlight = -1;
  // VBAT = VADC * ratio. Waveshare's LCD-1.54 divides by three; the T-Display-S3
  // divides by two. Ignored when the ADC pin is -1.
  int mv_ratio = 3;
  uint16_t max_battery_mv = 4998;
  bool power_off_supported = true;
};

struct BoardConfig {
  const char* name;
  LcdConfig lcd;
  I2sMicConfig mic;
  I2sSpeakerConfig speaker;
  ButtonConfig buttons;
  AmoledConfig amoled;
  I2cBusConfig i2c;
  CodecAudioConfig codec;
  TouchConfig touch;
  ExpanderKeyConfig pwr_key;
  ExpanderResetConfig expander_reset;
  bool axp2101 = false;
  bool axp_audio_supply = false;
  bool cores3 = false;
  LatchPowerConfig latch_power;
  int status_led = -1;
  const char* talk_label = "TALK";
  const char* cancel_label = "CANCEL";
};

// GPIO held high to power the panel's peripheral rail. Boards without one
// leave it -1; the driver must see it high before it initialises the panel.
int lcd_power_pin(const BoardConfig& b);

const BoardConfig& board_config();

}  // namespace hgp
