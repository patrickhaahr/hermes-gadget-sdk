// Touch and key input tasks; CrowPanel CST8XX touch and GPIO encoder paths
// follow Elecrow's vendor Arduino example.
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
constexpr uint8_t kCstTouchesReg = 0x02;
constexpr uint8_t kCstDataReg = 0x03;
constexpr uint8_t kCstChipTypeReg = 0xAA;
constexpr uint8_t kCstChipType = 0x11;
constexpr gpio_num_t kCrowEncoderA = GPIO_NUM_42;
constexpr gpio_num_t kCrowEncoderB = GPIO_NUM_4;

bool i2c_read_reg(i2c_master_dev_handle_t dev, uint8_t reg, uint8_t* data, size_t size) {
  return i2c_master_transmit_receive(dev, &reg, 1, data, size, 50) == ESP_OK;
}

}  // namespace

bool TouchInput::begin(const TouchConfig& touch, const ExpanderKeyConfig& key, i2c_master_bus_handle_t bus) {
  if (!bus) return false;
  touch_ = touch;
  key_ = key;

  if (touch.enabled && touch.controller == TouchController::Cst9217) {
    i2c_device_config_t dev = {};
    dev.dev_addr_length = I2C_ADDR_BIT_LEN_7;
    dev.device_address = touch.addr;
    dev.scl_speed_hz = 400000;
    if (i2c_master_bus_add_device(bus, &dev, &touch_dev_) == ESP_OK) {
      uint8_t chip = 0;
      // Vendor CST8XX readRegister8(0xAA) returns CST826 ID 0x11. Probe this
      // before treating the controller as compatible; its touch register map is not optional.
      if (!i2c_read_reg(touch_dev_, kCstChipTypeReg, &chip, 1) || chip != kCstChipType) {
        ESP_LOGW(TAG, "unsupported/no CST8XX device at 0x%02x (id=0x%02x)", touch.addr, chip);
        i2c_master_bus_rm_device(touch_dev_);
        touch_dev_ = nullptr;
      } else {
        ESP_LOGI(TAG, "CST8XX detected at 0x%02x", touch.addr);
      }
    }
  } else if (touch.enabled && touch.controller == TouchController::Ft5x06) {
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
    cfg.rst_gpio_num = GPIO_NUM_NC;
    cfg.int_gpio_num = GPIO_NUM_NC;
    if (esp_lcd_new_panel_io_i2c(bus, &io_cfg, &io) == ESP_OK &&
        esp_lcd_touch_new_i2c_ft5x06(io, &cfg, &managed_touch_) != ESP_OK) esp_lcd_panel_io_del(io);
  } else if (touch.enabled && touch.controller == TouchController::Box3) {
    begin_box_touch(bus);
  }

  if (key.enabled) {
    i2c_device_config_t dev = {};
    dev.dev_addr_length = I2C_ADDR_BIT_LEN_7;
    dev.device_address = key.addr;
    dev.scl_speed_hz = 400000;
    if (i2c_master_bus_add_device(bus, &dev, &key_dev_) != ESP_OK) key_dev_ = nullptr;
    if (key.pcf8574 && key_dev_) {
      // PCF8574 quasi-bidirectional input: latch high before reading P5.
      if (i2c_master_transmit(key_dev_, &expander_outputs_, 1, 50) != ESP_OK) {
        ESP_LOGW(TAG, "PCF8574 0x%02x input release failed", key.addr);
      }
    }
  }

  if (touch.enabled && touch.controller == TouchController::Cst9217 && touch_dev_) {
    encoder_a_ = kCrowEncoderA;
    encoder_b_ = kCrowEncoderB;
    gpio_config_t enc = {};
    enc.pin_bit_mask = (1ULL << encoder_a_) | (1ULL << encoder_b_);
    enc.mode = GPIO_MODE_INPUT;
    enc.pull_up_en = GPIO_PULLUP_ENABLE;
    enc.pull_down_en = GPIO_PULLDOWN_DISABLE;
    enc.intr_type = GPIO_INTR_DISABLE;
    if (gpio_config(&enc) != ESP_OK) {
      encoder_a_ = GPIO_NUM_NC;
      encoder_b_ = GPIO_NUM_NC;
      ESP_LOGW(TAG, "rotary GPIO configuration failed");
    } else {
      encoder_state_ = static_cast<uint8_t>((gpio_get_level(encoder_a_) << 1) | gpio_get_level(encoder_b_));
    }
  }

  if (!has_touch() && !has_key()) return false;
  if (xTaskCreate(&TouchInput::task, "hg-touch", 3072, this, 5, nullptr) != pdPASS) return false;
  ESP_LOGI(TAG, "touch %s, key %s, encoder %s", has_touch() ? "ready" : "off",
           has_key() ? "ready" : "off", encoder_a_ != GPIO_NUM_NC ? "ready" : "off");
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
  if (!touch_dev_) return false;
  // Vendor CST8XX protocol: register 0x02 reports touch count; register 0x03
  // begins count * 6 bytes. Read every active point because the controller's
  // first-slot format differs from the legacy one-point sample on some batches.
  uint8_t count = 0;
  if (!i2c_read_reg(touch_dev_, kCstTouchesReg, &count, 1)) return false;
  if (count > 5) count = 0;
  if (!count) {
    out = {false, 0, 0};
    return true;
  }
  uint8_t raw[30] = {};
  if (!i2c_read_reg(touch_dev_, kCstDataReg, raw, static_cast<size_t>(count) * 6)) return false;
  int x = ((raw[0] & 0x0f) << 8) | raw[1];
  int y = ((raw[2] & 0x0f) << 8) | raw[3];
  if (touch_.mirror_x && touch_.width) x = touch_.width - 1 - x;
  if (touch_.mirror_y && touch_.height) y = touch_.height - 1 - y;
  if (touch_.width) x = std::clamp(x, 0, static_cast<int>(touch_.width) - 1);
  if (touch_.height) y = std::clamp(y, 0, static_cast<int>(touch_.height) - 1);
  out = {true, static_cast<int16_t>(x), static_cast<int16_t>(y)};
  return true;
}

bool TouchInput::sample_encoder(int& direction) {
  direction = 0;
  if (encoder_a_ == GPIO_NUM_NC || encoder_b_ == GPIO_NUM_NC) return false;
  const uint8_t state = static_cast<uint8_t>((gpio_get_level(encoder_a_) << 1) | gpio_get_level(encoder_b_));
  const uint8_t transition = static_cast<uint8_t>((encoder_state_ << 2) | state);
  encoder_state_ = state;
  static constexpr int8_t table[16] = {0, -1, 1, 0, 1, 0, 0, -1, -1, 0, 0, 1, 0, 1, -1, 0};
  encoder_accumulator_ = static_cast<int8_t>(encoder_accumulator_ + table[transition]);
  if (encoder_accumulator_ >= 4) { direction = 1; encoder_accumulator_ = 0; }
  else if (encoder_accumulator_ <= -4) { direction = -1; encoder_accumulator_ = 0; }
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
  cfg.rst_gpio_num = GPIO_NUM_NC;
  cfg.int_gpio_num = GPIO_NUM_3;
  cfg.flags.mirror_x = tt21100;
  const esp_err_t err = tt21100 ? esp_lcd_touch_new_i2c_tt21100(io, &cfg, &managed_touch_)
                              : esp_lcd_touch_new_i2c_gt911(io, &cfg, &managed_touch_);
  if (err != ESP_OK) { esp_lcd_panel_io_del(io); return false; }
  return true;
}

bool TouchInput::read_key(bool& pressed) {
  if (!key_dev_) return false;
  uint8_t value = 0xff;
  if (i2c_master_receive(key_dev_, &value, 1, 50) != ESP_OK) return false;
  const bool high = (value & (1u << key_.bit)) != 0;
  pressed = key_.active_high ? high : !high;
  return true;
}

void TouchInput::task(void* arg) {
  auto* self = static_cast<TouchInput*>(arg);
  bool was_touching = false;
  bool key_down = false;
  for (;;) {
    TouchSample sample{};
    if (self->has_touch() && self->read_touch(sample)) {
      if (sample.touching || was_touching) events::post(EventType::Touch, &sample, sizeof(sample));
      was_touching = sample.touching;
    }
    bool pressed = false;
    if (self->read_key(pressed) && pressed != key_down) {
      key_down = pressed;
      KeySample key{pressed};
      events::post(EventType::Key, &key, sizeof(key));
    }
    int direction = 0;
    if (self->sample_encoder(direction) && direction) {
      EncoderSample encoder{static_cast<int8_t>(direction)};
      events::post(EventType::Encoder, &encoder, sizeof(encoder));
    }
    vTaskDelay(pdMS_TO_TICKS(kPollMs));
  }
}

}  // namespace hgp
