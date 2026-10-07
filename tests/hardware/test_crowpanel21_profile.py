from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
BOARD = ROOT / "firmware/esp32/main/board.cpp"
KCONFIG = ROOT / "firmware/esp32/main/Kconfig.projbuild"
PROFILE = ROOT / "firmware/esp32/boards/crowpanel-21/sdkconfig.defaults"
PLATFORMIO = ROOT / "firmware/esp32/platformio.ini"


def test_crowpanel_profile_uses_vendor_rgb_pins_and_expander():
    board = BOARD.read_text()
    assert '"crowpanel-2.1"' in board
    for field in ("rgb.de = 40", "rgb.vsync = 7", "rgb.hsync = 15", "rgb.pclk = 41",
                  "cmd_cs = 16", "cmd_sclk = 2", "cmd_sda = 1", "encoder_a = 42",
                  "encoder_b = 4", "i2c_expander = 0x21"):
        assert field in board
    assert "46, 3, 8, 18, 17, 14, 13, 12, 11, 10, 9, 5, 45, 48, 47, 21" in board
    assert "encoder_button = -1" in board  # Button is expander P5, not RGB GPIO5.
    assert "touch_irq = -1" in board  # Touch IRQ is expander P2, not I2C SCL GPIO39.
    assert "pwr_key.addr = 0x21" in board
    assert "pwr_key.bit = 5" in board
    assert "pwr_key.pcf8574 = true" in board
    assert 'b.touch.irq = -1' in board
    assert 'b.pwr_key.active_high = false' in board
    assert 'b.lcd.bus.type = LcdBus::Type::Rgb' in board
    # Vendor PWM on GPIO6 is active-high (ledcWrite 204 = bright in the factory demo).
    assert 'b.lcd.backlight_invert = false' in board
    assert 'b.buttons = {-1, -1, -1, -1}' in board
    main_cpp = (ROOT / "firmware/esp32/main/main.cpp").read_text()
    assert 'g_rgb' in main_cpp
    assert "touch.addr = 0x15" in board
    assert "CONFIG_ESPTOOLPY_FLASHSIZE_16MB=y" in PROFILE.read_text()
    assert "[env:crowpanel-21]" in PLATFORMIO.read_text()
    assert 'board = crowpanel-21' in PLATFORMIO.read_text()
    assert '"flash_size": "16MB"' in (ROOT / "firmware/esp32/boards/crowpanel-21.json").read_text()
    assert "HG_BOARD_CROWPANEL_21" in KCONFIG.read_text()
    rgb_driver = (ROOT / "firmware/esp32/main/port_rgb.cpp").read_text()
    assert "ST7701_PANEL_IO_3WIRE_SPI_CONFIG" in rgb_driver
    assert "LCD_CLK_SRC_PLL160M" in rgb_driver
    assert "psram_trans_align = 64" in rgb_driver
    assert "pclk_hz = 12000000" in board
    assert "hsync_pulse_width = 4" in rgb_driver
    assert "hsync_back_porch = 20" in rgb_driver
    assert "hsync_front_porch = 10" in rgb_driver
    assert "vsync_pulse_width = 4" in rgb_driver
    assert "vsync_back_porch = 20" in rgb_driver
    assert "vsync_front_porch = 10" in rgb_driver
    init_header = (ROOT / "firmware/esp32/main/crowpanel_st7701_init.hpp").read_text()
    assert "kCrowPanelInitCommands[]" in init_header
    assert "{0x11, nullptr, 0, 100}" in init_header
    assert "{0x29, nullptr, 0, 50}" in init_header
    assert "kInitGammaPositive" in init_header and "kInitGammaNegative" in init_header and "0xB0" in init_header and "0xB1" in init_header
    assert "const size_t row_size" in rgb_driver
    assert "y1 - y0" in rgb_driver
    assert "swap_rgb565_red_blue" in rgb_driver
    assert "const uint16_t pixel = fb_[src + col]" in rgb_driver
    assert "packed[dst + col] = swap_rgb565_red_blue(pixel)" in rgb_driver
    assert "rgb_cfg.num_fbs = 1" in rgb_driver
    assert "rgb_cfg.flags.fb_in_psram = true" in rgb_driver
    assert "heap_caps_malloc(" in rgb_driver and "MALLOC_CAP_SPIRAM" in rgb_driver
    assert "esp_lcd_panel_draw_bitmap(panel_, 0, y0, cfg_.width, y1, packed)" in rgb_driver
    assert "esp_lcd_rgb_panel_get_frame_buffer(panel_, 1, &scanout_fb)" in rgb_driver
    # RGB panel config must build on both the IDF 5.x and 6.x field sets.
    assert "ESP_IDF_VERSION >= ESP_IDF_VERSION_VAL(6, 0, 0)" in rgb_driver
    assert "rgb_cfg.in_color_format = LCD_COLOR_FMT_RGB565" in rgb_driver
    assert "rgb_cfg.out_color_format = LCD_COLOR_FMT_RGB565" in rgb_driver
    assert "static_cast<gpio_num_t>(cfg.rgb.de)" in rgb_driver
    assert "vendor.init_cmds = kCrowPanelInitCommands" in rgb_driver
    touch_driver = (ROOT / "firmware/esp32/main/port_touch.cpp").read_text()
    assert "TouchInput::read_touch" in touch_driver
    assert "kCstTouchesReg = 0x02" in touch_driver
    assert "kCstDataReg = 0x03" in touch_driver
    assert "key.pcf8574" in touch_driver
    assert "i2c_master_receive(key_dev_" in touch_driver
    assert "gpio_get_level" in touch_driver
    assert "EventType::Encoder" in touch_driver
    assert "TouchInput::read_key" in touch_driver

