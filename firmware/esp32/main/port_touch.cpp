// Touch controllers and a key mirrored on a TCA9554 expander, polled over
// I2C from their own task. Samples become Touch and Key events; the app task
// turns them into gestures (hg::TouchGestures) and button presses.
#include "port.hpp"  // first: pulls in FreeRTOS.h ahead of task.h/queue.h

#include <algorithm>

#include "driver/gpio.h"
#include "esp_log.h"
#include "esp_lcd_touch_gt911.h"
#include "esp_lcd_touch_tt21100.h"
#include "esp_lcd_touch_ft5x06.h"
#include "freertos/task.h"

namespace hgp {
namespace {

const char* TAG = "hg.touch";
constexpr uint32_t kPollMs = 20;
constexpr uint8_t kTca9554Input = 0x00;
constexpr uint8_t kTca9554Output = 0x01;
constexpr uint8_t kTca9554Config = 0x03;
constexpr uint8_t kCstAck = 0xAB;

// Bits of the TCA9554 on the 1.8" AMOLED module: LCD_RST, the DSI power rail,
// TOUCH_RST and the SD card chip-select sit on its output bits (Waveshare's
// board_variant.c). The others stay inputs.
constexpr uint8_t kExpLcdRst = 1u << 0;
constexpr uint8_t kExpDsiPwrEn = 1u << 1;
constexpr uint8_t kExpTouchRst = 1u << 2;
constexpr uint8_t kExpSdCs = 1u << 7;
constexpr uint8_t kExpOutputMask = kExpLcdRst | kExpDsiPwrEn | kExpTouchRst | kExpSdCs;

}  // namespace

// Hold LCD_RST, TOUCH_RST and the panel's DSI power rail low through the
// expander, then release them together, so both controllers start from a clean
// reset. Mirrors Waveshare's board_variant.c release_touch_reset().
bool expander_reset(const ExpanderResetConfig& cfg, i2c_master_bus_handle_t bus) {
  if (!cfg.enabled) return true;
  if (!bus) return false;
  i2c_device_config_t dev_cfg = {};
  dev_cfg.dev_addr_length = I2C_ADDR_BIT_LEN_7;
  dev_cfg.device_address = cfg.addr;
  dev_cfg.scl_speed_hz = 400000;
  i2c_master_dev_handle_t dev = nullptr;
  if (i2c_master_bus_add_device(bus, &dev_cfg, &dev) != ESP_OK) {
    ESP_LOGW(TAG, "TCA9554 at 0x%02x did not answer; display and touch stay in reset", cfg.addr);
    return false;
  }
  auto write = [&](uint8_t reg, uint8_t value) {
    const uint8_t data[] = {reg, value};
    return i2c_master_transmit(dev, data, sizeof(data), 100) == ESP_OK;
  };
  // The masked bits become outputs (0 = output, 1 = input on a TCA9554).
  bool ok = write(kTca9554Config, static_cast<uint8_t>(~kExpOutputMask));
  // LCD_RST / TOUCH_RST low with the panel rail off, then release all together.
  if (ok) ok = write(kTca9554Output, kExpSdCs);
  vTaskDelay(pdMS_TO_TICKS(20));
  if (ok) ok = write(kTca9554Output, kExpOutputMask);
  vTaskDelay(pdMS_TO_TICKS(150));
  i2c_master_bus_rm_device(dev);
  if (!ok) ESP_LOGW(TAG, "TCA9554 display/touch reset sequence failed");
  return ok;
}

bool TouchInput::begin(const TouchConfig& touch, const ExpanderKeyConfig& key, i2c_master_bus_handle_t bus) {
  if (!bus) return false;
  touch_ = touch;
  key_ = key;
  if (touch.enabled && touch.controller == TouchController::Ft5x06) {
    esp_lcd_panel_io_i2c_config_t io_cfg = {};
    io_cfg.dev_addr = ESP_LCD_TOUCH_IO_I2C_FT5x06_ADDRESS;
    io_cfg.scl_speed_hz = 100000;
    io_cfg.control_phase_bytes = 1;
    io_cfg.lcd_cmd_bits = 8;
    io_cfg.flags.disable_control_phase = 1;
    esp_lcd_panel_io_handle_t io = nullptr;
    esp_lcd_touch_config_t cfg = {};
    cfg.x_max = touch.width;
    cfg.y_max = touch.height;
    cfg.rst_gpio_num = GPIO_NUM_NC;  // The board's expander already released reset.
    cfg.int_gpio_num = GPIO_NUM_NC;
    if (esp_lcd_new_panel_io_i2c(bus, &io_cfg, &io) == ESP_OK &&
        esp_lcd_touch_new_i2c_ft5x06(io, &cfg, &managed_touch_) != ESP_OK) esp_lcd_panel_io_del(io);
  } else if (touch.enabled && touch.controller == TouchController::Box3) {
    begin_box_touch(bus);
  } else if (touch.enabled) {
    if (touch.rst >= 0) {
      gpio_config_t rst = {};
      rst.pin_bit_mask = 1ULL << touch.rst;
      rst.mode = GPIO_MODE_OUTPUT;
      gpio_config(&rst);
      gpio_set_level(static_cast<gpio_num_t>(touch.rst), 0);
      vTaskDelay(pdMS_TO_TICKS(10));
      gpio_set_level(static_cast<gpio_num_t>(touch.rst), 1);
      vTaskDelay(pdMS_TO_TICKS(50));
    }
    i2c_device_config_t dev = {};
    dev.dev_addr_length = I2C_ADDR_BIT_LEN_7;
    dev.device_address = touch.addr;
    dev.scl_speed_hz = 400000;
    if (i2c_master_bus_add_device(bus, &dev, &touch_dev_) == ESP_OK) {
      // The CST9xx controllers answer a wake/enable command; the CST820 is
      // driven like a CST816 and has no such register.
      if (touch.controller == TouchController::Cst9217) {
        const uint8_t command_mode[2] = {0xD1, 0x01};
        if (i2c_master_transmit(touch_dev_, command_mode, sizeof(command_mode), 50) != ESP_OK) {
          ESP_LOGW(TAG, "touch controller at 0x%02x did not answer", touch.addr);
        }
        vTaskDelay(pdMS_TO_TICKS(10));
      }
    }
  }
  if (key.enabled) {
    i2c_device_config_t dev = {};
    dev.dev_addr_length = I2C_ADDR_BIT_LEN_7;
    dev.device_address = key.addr;
    dev.scl_speed_hz = 400000;
    if (i2c_master_bus_add_device(bus, &dev, &key_dev_) != ESP_OK) key_dev_ = nullptr;
  }
  if (!has_touch() && !key_dev_) return false;
  xTaskCreate(&TouchInput::task, "hg-touch", 3072, this, 5, nullptr);
  ESP_LOGI(TAG, "touch %s, key %s", has_touch() ? "ready" : "off", key_dev_ ? "ready" : "off");
  return true;
}

bool TouchInput::read_touch(TouchSample& out) {
  if (managed_touch_) {
    if (esp_lcd_touch_read_data(managed_touch_) != ESP_OK) return false;
    uint16_t x = 0, y = 0;
    uint8_t points = 0;
    const bool down = esp_lcd_touch_get_coordinates(managed_touch_, &x, &y, nullptr, &points, 1);
    out = {down && points > 0, static_cast<int16_t>(x), static_cast<int16_t>(y)};
    return true;
  }
  if (touch_.controller == TouchController::Cst820) return read_cst820(out);
  const uint8_t reg[2] = {0xD0, 0x00};
  if (i2c_master_transmit(touch_dev_, reg, sizeof(reg), 20) != ESP_OK) return false;
  // The controller needs ~2 ms before the read; at least one tick whatever the tick rate.
  vTaskDelay(std::max<TickType_t>(1, pdMS_TO_TICKS(2)));
  uint8_t buf[10] = {};
  if (i2c_master_receive(touch_dev_, buf, sizeof(buf), 20) != ESP_OK) return false;
  if (buf[6] != kCstAck) return false;  // not a valid report
  const int points = buf[5] & 0x7F;
  const bool down = points > 0 && (buf[0] & 0x0F) == 0x06;
  int x = (buf[1] << 4) | (buf[3] >> 4);
  int y = (buf[2] << 4) | (buf[3] & 0x0F);
  if (touch_.mirror_x && touch_.width) x = touch_.width - 1 - x;
  if (touch_.mirror_y && touch_.height) y = touch_.height - 1 - y;
  out = {down, static_cast<int16_t>(x), static_cast<int16_t>(y)};
  return true;
}

// CST820 (driven like a CST816, per Waveshare's TouchDrvCST816): one report of
// 7 bytes from register 0x00 holds the status, the point count and the
// coordinates; chip ID 0xB7. Single touch only.
bool TouchInput::read_cst820(TouchSample& out) {
  const uint8_t reg = 0x00;
  uint8_t buf[7] = {};
  if (i2c_master_transmit_receive(touch_dev_, &reg, 1, buf, sizeof(buf), 20) != ESP_OK) return false;
  const uint8_t points = buf[2] & 0x0F;
  if (buf[2] == 0xFF) return false;  // some parts return 0xFF after a wake
  const bool down = points > 0;
  int x = ((buf[3] & 0x0F) << 8) | buf[4];
  int y = ((buf[5] & 0x0F) << 8) | buf[6];
  if (touch_.mirror_x && touch_.width) x = touch_.width - 1 - x;
  if (touch_.mirror_y && touch_.height) y = touch_.height - 1 - y;
  out = {down, static_cast<int16_t>(x), static_cast<int16_t>(y)};
  return true;
}

bool TouchInput::begin_box_touch(i2c_master_bus_handle_t bus) {
  esp_lcd_panel_io_i2c_config_t io_cfg = {};
  io_cfg.scl_speed_hz = 100000;
  io_cfg.control_phase_bytes = 1;
  io_cfg.lcd_cmd_bits = 16;
  io_cfg.flags.disable_control_phase = 1;
  bool tt21100 = false;
  if (i2c_master_probe(bus, 0x5d, 50) == ESP_OK || i2c_master_probe(bus, 0x14, 50) == ESP_OK) {
    io_cfg.dev_addr = ESP_LCD_TOUCH_IO_I2C_GT911_ADDRESS;
    if (i2c_master_probe(bus, 0x5d, 50) != ESP_OK) io_cfg.dev_addr = 0x14;
  } else if (i2c_master_probe(bus, 0x24, 50) == ESP_OK) {
    io_cfg.dev_addr = ESP_LCD_TOUCH_IO_I2C_TT21100_ADDRESS;
    tt21100 = true;
  } else {
    return false;
  }
  esp_lcd_panel_io_handle_t io = nullptr;
  if (esp_lcd_new_panel_io_i2c(bus, &io_cfg, &io) != ESP_OK) return false;
  esp_lcd_touch_config_t cfg = {};
  cfg.x_max = touch_.width;
  cfg.y_max = touch_.height;
  cfg.rst_gpio_num = GPIO_NUM_NC;  // Display initialization already reset the shared line.
  cfg.int_gpio_num = GPIO_NUM_3;
  cfg.flags.mirror_x = tt21100;
  const esp_err_t err = tt21100 ? esp_lcd_touch_new_i2c_tt21100(io, &cfg, &managed_touch_)
                              : esp_lcd_touch_new_i2c_gt911(io, &cfg, &managed_touch_);
  if (err != ESP_OK) { esp_lcd_panel_io_del(io); return false; }
  return true;
}

bool TouchInput::read_key(bool& pressed) {
  uint8_t reg = kTca9554Input, value = 0;
  if (i2c_master_transmit_receive(key_dev_, &reg, 1, &value, 1, 20) != ESP_OK) return false;
  bool high = (value >> key_.bit) & 1;
  pressed = key_.active_high ? high : !high;
  return true;
}

void TouchInput::task(void* arg) {
  auto* self = static_cast<TouchInput*>(arg);
  bool was_touching = false, key_down = false;
  for (;;) {
    TouchSample s{};
    if (self->has_touch() && self->read_touch(s)) {
      // Every sample while the finger is down (gestures need the motion), plus the lift.
      if (s.touching || was_touching) events::post(EventType::Touch, &s, sizeof(s));
      was_touching = s.touching;
    }
    bool pressed = false;
    if (self->key_dev_ && self->read_key(pressed) && pressed != key_down) {
      key_down = pressed;
      KeySample k{pressed};
      events::post(EventType::Key, &k, sizeof(k));
    }
    vTaskDelay(pdMS_TO_TICKS(kPollMs));
  }
}

}  // namespace hgp
