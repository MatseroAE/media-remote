/*
  ============================================================================
  Media Remote  -  Seeed XIAO nRF52840 Plus + XIAO 1.14" IPS Display board
  ============================================================================

  What it does
    * Appears to your phone as a Bluetooth LE media remote (HID "consumer
      control" only - it does NOT look like a keyboard, so Android keeps its
      on-screen keyboard).
    * Play/pause, next, previous, volume up/down and seek work with any app,
      no companion app needed.
    * With the Android companion app running, the screen shows the track
      title, artist, progress and volume, and long-press seeking jumps
      10 s at a time.

  Controls (board held with the screen facing you, buttons below it)
    LEFT  (USR1)  tap  = previous track      hold = rewind
    RIGHT (USR2)  tap  = next track          hold = fast-forward
    SIDE  (USR3)  tap  = play / pause
                  hold 0.6 s = toggle VOLUME mode (LEFT = vol-, RIGHT = vol+)
                  hold 3 s   = power off   (press SIDE again to turn on)
    Hold LEFT + RIGHT while powering on/resetting = forget all paired phones.

  Arduino setup (see README.md for the full guide)
    Board package : "Seeed nRF52 Boards" 1.1.13+
                    (URL: https://files.seeedstudio.com/arduino/package_seeeduino_boards_index.json)
    Board         : Seeed nRF52 Boards > Seeed XIAO nRF52840 Plus
    Library       : Seeed_GFX2 v1.0.0  (install the ZIP from GitHub)
  ============================================================================
*/

#include <Arduino.h>
#include <Adafruit_TinyUSB.h>
#include <bluefruit.h>
#include <Seeed_GFX.h>
#include "board/boards/XIAO_LCD_Board.h"
#include "driver/tft/Driver_ST7789.h"
#include "panel/Panel_TFT.h"
#include <nrf.h>
#include <nrf_gpio.h>

// ----------------------------------------------------------------------------
// User settings
// ----------------------------------------------------------------------------
#define DEVICE_NAME                "Media Remote"   // name shown on the phone
#define DEBUG_LOG                  1                // 1 = print button/mode/power events to Serial Monitor (115200)
#if DEBUG_LOG
#define LOG(...) do { Serial.print(millis()); Serial.print(" ms  "); Serial.printf(__VA_ARGS__); Serial.println(); } while (0)
#else
#define LOG(...) do {} while (0)
#endif
static constexpr uint8_t  SCREEN_ROTATION          = 3;      // 1 or 3: use the other if text is upside down
static constexpr uint32_t SCREEN_TIMEOUT_MS        = 20000;  // backlight off after this much idle time
static constexpr bool     WAKE_ON_TRACK_CHANGE     = true;   // light the screen briefly on new song
static constexpr uint32_t TRACK_CHANGE_WAKE_MS     = 5000;
static constexpr uint32_t TRACK_WAKE_MIN_GAP_MS    = 30000;  // at most one track-change wake per 30 s
static constexpr uint32_t AUTO_OFF_DISCONNECTED_MS = 10UL * 60UL * 1000UL; // power off if no phone for 10 min
static constexpr uint32_t VOLUME_MODE_TIMEOUT_MS   = 3000;   // drop back to track mode after this

// Button timing
static constexpr uint32_t DEBOUNCE_MS         = 25;
static constexpr uint32_t SEEK_HOLD_MS        = 450;   // left/right hold before seeking starts
static constexpr uint32_t SEEK_REPEAT_MS      = 350;   // app-seek repeat while held
static constexpr uint32_t VOL_REPEAT_DELAY_MS = 400;
static constexpr uint32_t VOL_REPEAT_MS       = 130;
static constexpr uint32_t SIDE_HOLD_MS        = 600;   // side hold -> volume mode
static constexpr uint32_t POWER_HOLD_MS       = 3000;  // side hold -> power off

// Colours (RGB565)
#define COL_BG       TFT_BLACK
#define COL_TEXT     TFT_WHITE
#define COL_DIM      0x8C71   // grey
#define COL_FAINT    0x3186   // dark grey
#define COL_ACCENT   0x05FF   // azure
#define COL_OK       0x07E8   // green
#define COL_WARN     0xFD20   // orange
#define COL_BAD      TFT_RED

// ----------------------------------------------------------------------------
// Hardware (fixed by the Seeed board - nothing to wire)
// ----------------------------------------------------------------------------
static constexpr uint8_t BTN_LEFT  = D6;    // USR1, active LOW (internal pull-up; 1k in series on the board)
static constexpr uint8_t BTN_RIGHT = D7;    // USR2
static constexpr uint8_t BTN_SIDE  = D19;   // USR3 (side)
static constexpr int8_t  LCD_RST_PIN = 38;  // = D17
static constexpr int8_t  LCD_BL_PIN  = 37;  // = D18 (backlight)

// Battery circuit on the XIAO nRF52840 Plus (raw nRF port-0 bits, as in Seeed's demo)
static constexpr uint8_t READ_BAT_P0 = 14;  // P0.14, held LOW permanently: enables the divider AND protects P0.31
                                            // (Seeed: never leave it high/off while charging; costs ~3 uA)
static constexpr uint8_t CHG_P0      = 17;  // P0.17, LOW while charging
#ifndef PIN_VBAT
#define PIN_VBAT 35
#endif
static constexpr float BAT_DIVIDER = (1000.0f + 499.0f) / 499.0f;

// 1.14" panel needs BGR colour order on this board (from Seeed's demos)
struct RemotePanelConfig : Config_Seeed_1inch14_LCD_ST7789 {
  static constexpr uint8_t rgbOrder = TFT_MAD_BGR;
};

// ----------------------------------------------------------------------------
// BLE: HID consumer-control (media keys only)
// ----------------------------------------------------------------------------
static constexpr uint8_t HID_REPORT_ID = 1;
static const uint8_t hidReportMap[] = {
  0x05, 0x0C,        // Usage Page (Consumer)
  0x09, 0x01,        // Usage (Consumer Control)
  0xA1, 0x01,        // Collection (Application)
  0x85, HID_REPORT_ID, //   Report ID
  0x15, 0x00,        //   Logical Minimum (0)
  0x26, 0xFF, 0x03,  //   Logical Maximum (0x3FF)
  0x19, 0x00,        //   Usage Minimum (0)
  0x2A, 0xFF, 0x03,  //   Usage Maximum (0x3FF)
  0x95, 0x01,        //   Report Count (1)
  0x75, 0x10,        //   Report Size (16)
  0x81, 0x00,        //   Input (Data, Array, Absolute)
  0xC0               // End Collection
};
static uint16_t hidInputLen[] = { 2 };   // must stay alive: the library keeps the pointer

// Consumer usage codes
static constexpr uint16_t KEY_PLAY_PAUSE = 0x00CD;
static constexpr uint16_t KEY_NEXT       = 0x00B5;
static constexpr uint16_t KEY_PREV       = 0x00B6;
static constexpr uint16_t KEY_FFWD       = 0x00B3;
static constexpr uint16_t KEY_REWIND     = 0x00B4;
static constexpr uint16_t KEY_VOL_UP     = 0x00E9;
static constexpr uint16_t KEY_VOL_DOWN   = 0x00EA;

BLEDis        bledis;
BLEBas        blebas;
BLEHidGeneric blehid(1, 0, 0);

// ----------------------------------------------------------------------------
// BLE: "Now Playing" service, written by the Android companion app
//   Must match android/.../Protocol.kt
// ----------------------------------------------------------------------------
BLEService        npService ("8f1e0001-7c2a-4d55-9b3e-2a6c5e7d9f10");
BLECharacteristic npTitle   ("8f1e0002-7c2a-4d55-9b3e-2a6c5e7d9f10"); // write: UTF-8/ASCII text
BLECharacteristic npArtist  ("8f1e0003-7c2a-4d55-9b3e-2a6c5e7d9f10"); // write: text
BLECharacteristic npState   ("8f1e0004-7c2a-4d55-9b3e-2a6c5e7d9f10"); // write: 12-byte state
BLECharacteristic npCommand ("8f1e0005-7c2a-4d55-9b3e-2a6c5e7d9f10"); // notify: 1-byte command
BLECharacteristic npArt     ("8f1e0006-7c2a-4d55-9b3e-2a6c5e7d9f10"); // write: album art chunks

// Album art: 64 x 64 RGB565, little-endian (8192 bytes), sent in 64 chunks of 128 bytes.
//   chunk = [artId][offset lo][offset hi][128 bytes of pixels]
//   [artId][0xFF][0xFF]  = "this track has no art"
static constexpr int      ART_W = 64, ART_H = 64;
static constexpr uint16_t ART_BYTES = ART_W * ART_H * 2;
static constexpr uint16_t ART_CHUNK = 128;
static constexpr int      ART_X = 4, ART_Y = 23;          // on screen
static constexpr uint32_t ART_WAIT_MS = 4000;             // placeholder if no art arrives after a track change
static uint8_t  artRx[ART_BYTES];                         // being received (BLE task)
static uint8_t  artReady[ART_BYTES];                      // last complete image (BLE task -> loop)
static uint16_t artPix[ART_W * ART_H];                    // what the loop draws
static volatile uint8_t  rxArtId = 0;
static volatile uint64_t rxArtMask = 0;
static volatile uint32_t artSeq = 0;                      // bumps when artReady holds a new image
static volatile bool     artNoneFlag = false;             // last message was "no art"
static volatile uint32_t artNoneSeq = 0;

// State packet (little endian):
//   [0]    flags  bit0 = playing, bit1 = position/duration valid
//   [1..4] position ms   [5..8] duration ms
//   [9]    volume        [10]   volume max   [11] reserved
static constexpr uint8_t STATE_LEN = 12;

enum : uint8_t { CMD_SEEK_BACK = 1, CMD_SEEK_FWD = 2 };

static constexpr uint16_t TEXT_MAX = 120;

// Buffers written from BLE callbacks, copied out in loop()
static char     rxTitle[TEXT_MAX + 1];
static char     rxArtist[TEXT_MAX + 1];
static uint8_t  rxState[STATE_LEN];
static uint32_t rxStateMs;
static volatile uint32_t rxTitleSeq = 0, rxArtistSeq = 0, rxStateSeq = 0;
static volatile bool     bleConnEvent = false;

// ----------------------------------------------------------------------------
// App state
// ----------------------------------------------------------------------------
struct Track {
  char     title[TEXT_MAX + 1]  = "";
  char     artist[TEXT_MAX + 1] = "";
  bool     hasState = false;
  bool     playing  = false;
  bool     timeValid = false;
  uint32_t posMs = 0, durMs = 0, stateAtMs = 0;
  uint8_t  vol = 0, volMax = 0;
} track;

enum UiMode { MODE_TRACK, MODE_VOLUME };
static UiMode   uiMode = MODE_TRACK;
static uint32_t volModeUntil = 0;

static bool     screenOn = true;
static uint32_t lastActivityMs = 0;
static uint32_t screenKeepUntil = 0;     // extra keep-awake (track change)
static uint32_t lastConnectedMs = 0;

static bool     wasConnected = false;
static bool     wasAppLinked = false;

static int      batPercent = -1;         // -1 = unknown / no battery
static float    batVolts = 0;
static bool     batCharging = false;
static bool     usbPower = false;
static uint32_t nextBatReadMs = 0;
static uint32_t nextChgPollMs = 0;

static char     toastText[24] = "";
static uint32_t toastUntil = 0;

// Dirty flags for redraw
static bool dirtyStatus = true, dirtyText = true, dirtyMid = true, dirtyBottom = true;
static uint32_t lastMidDrawMs = 0;

Seeed_GFX display;

// ----------------------------------------------------------------------------
// Button types (must come before the first function: Arduino IDE inserts
// auto-generated function prototypes there, and handleArrow() uses these)
// ----------------------------------------------------------------------------
struct Button {
  const char* name = "";
  uint8_t  pin = 0;
  bool     down = false, raw = false;
  uint32_t rawSince = 0, downSince = 0;
  bool     pressed = false, released = false;

  void begin(uint8_t p, const char* n) { pin = p; name = n; pinMode(p, INPUT_PULLUP); }   // no pull-ups on the board (the 1k resistors are in series)
  void update(uint32_t now) {
    pressed = released = false;
    bool r = digitalRead(pin) == LOW;
    if (r != raw) { raw = r; rawSince = now; }
    if (raw != down && now - rawSince >= DEBOUNCE_MS) {
      down = raw;
      if (down) { downSince = now; pressed = true; LOG("%s pressed", name); }
      else { released = true; LOG("%s released after %lu ms", name, (unsigned long)(now - downSince)); }
    }
  }
  uint32_t heldFor(uint32_t now) const { return down ? now - downSince : 0; }
};

struct ArrowState {
  bool     seeking = false;   // hold-to-seek active
  bool     hidHeld = false;   // FF/REW key currently held down via HID
  uint32_t nextRepeat = 0;
};

// ----------------------------------------------------------------------------
// Small helpers
// ----------------------------------------------------------------------------
static uint32_t rd32(const uint8_t* p) {
  return (uint32_t)p[0] | ((uint32_t)p[1] << 8) | ((uint32_t)p[2] << 16) | ((uint32_t)p[3] << 24);
}

// Keep only printable ASCII (the built-in fonts are ASCII). The app already
// converts accents (é -> e), this is a safety net.
static void sanitizeCopy(char* dst, const char* src) {
  size_t i = 0;
  for (; src[i] && i < TEXT_MAX; i++) {
    char c = src[i];
    dst[i] = (c >= 0x20 && c <= 0x7E) ? c : '?';
  }
  dst[i] = 0;
}

static void formatTime(char* out, size_t n, uint32_t ms) {
  uint32_t s = ms / 1000;
  if (s >= 3600) snprintf(out, n, "%lu:%02lu:%02lu", (unsigned long)(s / 3600), (unsigned long)((s / 60) % 60), (unsigned long)(s % 60));
  else           snprintf(out, n, "%lu:%02lu", (unsigned long)(s / 60), (unsigned long)(s % 60));
}

static uint32_t currentPositionMs(uint32_t now) {
  if (!track.timeValid) return 0;
  uint32_t p = track.posMs;
  if (track.playing) p += now - track.stateAtMs;
  if (track.durMs && p > track.durMs) p = track.durMs;
  return p;
}

static bool appLinked() {
  return Bluefruit.connected() && npCommand.notifyEnabled();
}

static void showToast(const char* msg, uint32_t ms = 900) {
  strncpy(toastText, msg, sizeof(toastText) - 1);
  toastText[sizeof(toastText) - 1] = 0;
  toastUntil = millis() + ms;
  dirtyBottom = true;
}

// ----------------------------------------------------------------------------
// Icons (drawn into the screen-region sprites)
// ----------------------------------------------------------------------------
static void iconPlay(Seeed_Sprite& g, int cx, int cy, int s, uint16_t c) {
  g.fillTriangle(cx - s / 2, cy - s / 2, cx - s / 2, cy + s / 2, cx + s / 2, cy, c);
}
static void iconPause(Seeed_Sprite& g, int cx, int cy, int s, uint16_t c) {
  int w = s / 3;
  g.fillRect(cx - s / 2, cy - s / 2, w, s, c);
  g.fillRect(cx + s / 2 - w, cy - s / 2, w, s, c);
}
static void iconNext(Seeed_Sprite& g, int cx, int cy, int s, uint16_t c) {
  int h = s / 2;
  g.fillTriangle(cx - h, cy - h, cx - h, cy + h, cx + h - 3, cy, c);
  g.fillRect(cx + h - 3, cy - h, 3, s, c);
}
static void iconPrev(Seeed_Sprite& g, int cx, int cy, int s, uint16_t c) {
  int h = s / 2;
  g.fillTriangle(cx + h, cy - h, cx + h, cy + h, cx - h + 3, cy, c);
  g.fillRect(cx - h, cy - h, 3, s, c);
}
static void iconSpeaker(Seeed_Sprite& g, int x, int cy, uint16_t c) {
  g.fillRect(x, cy - 4, 5, 8, c);
  g.fillTriangle(x + 4, cy, x + 12, cy - 9, x + 12, cy + 9, c);
}
static void iconBolt(Seeed_Sprite& g, int x, int y, uint16_t c) { // ~8x12
  g.fillTriangle(x + 5, y, x, y + 7, x + 4, y + 7, c);
  g.fillTriangle(x + 3, y + 5, x + 8, y + 5, x + 3, y + 12, c);
}

// ----------------------------------------------------------------------------
// Screen regions (each drawn into its own sprite -> no flicker)
//   y   0..17  status bar
//   y  23..50  title   (font 4, scrolls if long)
//   y  53..70  artist  (font 2, scrolls if long)
//   y  75..110 progress / volume panel
//   y 111..134 button hints + toasts
// ----------------------------------------------------------------------------
static constexpr int SCR_W = 240;
// Right-hand column next to the album art: title, artist, progress / volume
static constexpr int COL_X = 74;
static constexpr int COL_W = SCR_W - COL_X;

Seeed_Sprite sprStatus, sprMid, sprBottom;

struct Marquee {
  Seeed_Sprite spr;
  int16_t  y = 0, h = 0;
  uint8_t  font = 2;
  uint16_t color = COL_TEXT;
  char     text[TEXT_MAX + 1] = "";
  int16_t  textW = 0;
  int32_t  offset = 0;
  uint32_t nextStepMs = 0, pauseUntil = 0;
  bool     dirty = true;
  static constexpr int GAP = 40;

  int16_t  x = 0, w = SCR_W;
  void begin(int16_t x_, int16_t y_, int16_t w_, int16_t h_, uint8_t font_) {
    x = x_; y = y_; w = w_; h = h_; font = font_;
    spr.createSprite(display, w, h);
  }
  void set(const char* t, uint16_t c) {
    if (strcmp(t, text) == 0 && c == color) return;
    strncpy(text, t, TEXT_MAX); text[TEXT_MAX] = 0;
    color = c;
    spr.setTextFont(font);
    textW = spr.textWidth(text);
    offset = 0;
    pauseUntil = millis() + 1500;
    dirty = true;
  }
  void tick(uint32_t now) {
    if (textW > w - 8 && now >= pauseUntil && now >= nextStepMs) {
      offset += 2;
      if (offset >= textW + GAP) { offset = 0; pauseUntil = now + 2000; }
      nextStepMs = now + 30;
      dirty = true;
    }
    if (!dirty) return;
    dirty = false;
    spr.fillSprite(COL_BG);
    spr.setTextFont(font);
    spr.setTextColor(color, COL_BG);
    if (textW <= w - 8) {
      spr.drawString(text, 4, 0);
    } else {
      spr.drawString(text, 4 - offset, 0);
      spr.drawString(text, 4 - offset + textW + GAP, 0);
    }
    spr.pushSprite(x, y);
  }
};

Marquee mqTitle, mqArtist;

static void drawStatusBar() {
  Seeed_Sprite& s = sprStatus;
  s.fillSprite(COL_BG);
  s.setTextFont(2);

  // Connection state (left)
  uint16_t c; const char* msg;
  if (!Bluefruit.connected())  { c = COL_ACCENT; msg = "Waiting for phone..."; }
  else if (!appLinked())       { c = COL_WARN;   msg = "Connected (no app)"; }
  else                         { c = COL_OK;     msg = "Connected"; }
  s.fillCircle(7, 9, 4, c);
  s.setTextColor(COL_DIM, COL_BG);
  s.drawString(msg, 16, 1);

  // Battery (right)
  const int bx = 212, by = 3, bw = 22, bh = 11;
  char pct[8];
  uint16_t fill = COL_OK;
  if (batPercent < 0) {
    strcpy(pct, usbPower ? "No batt" : "?");     // on USB with no battery: check the power switch
  } else {
    snprintf(pct, sizeof(pct), "%d%%", batPercent);
    if (batPercent <= 15) fill = COL_BAD; else if (batPercent <= 35) fill = COL_WARN;
  }
  s.drawRect(bx, by, bw, bh, COL_DIM);
  s.fillRect(bx + bw, by + 3, 2, 5, COL_DIM);
  if (batPercent >= 0) {
    int fw = (bw - 4) * batPercent / 100;
    if (fw > 0) s.fillRect(bx + 2, by + 2, fw, bh - 4, batCharging ? COL_ACCENT : fill);
  }
  int tw = s.textWidth(pct);
  int tx = bx - 4 - tw;
  s.setTextColor(COL_DIM, COL_BG);
  s.drawString(pct, tx, 1);
  if (batCharging) iconBolt(s, tx - 11, 3, TFT_YELLOW);

  s.pushSprite(0, 0);
}

static void updateTextLines() {
  if (track.title[0]) {
    mqTitle.set(track.title, COL_TEXT);
    mqArtist.set(track.artist[0] ? track.artist : "", COL_DIM);
  } else if (!Bluefruit.connected()) {
    mqTitle.set(DEVICE_NAME, COL_TEXT);
    mqArtist.set("Pair it in Android Settings > Bluetooth", COL_DIM);
  } else if (!appLinked()) {
    mqTitle.set(DEVICE_NAME, COL_TEXT);
    mqArtist.set("Buttons work. Open the companion app for track info.", COL_DIM);
  } else {
    mqTitle.set("Nothing playing", COL_DIM);
    mqArtist.set("Start some music on your phone", COL_FAINT);
  }
}

static void drawMidPanel(uint32_t now) {
  Seeed_Sprite& s = sprMid;   // COL_W x 36, right of the album art
  s.fillSprite(COL_BG);
  s.setTextFont(2);
  const int W = COL_W;

  if (uiMode == MODE_VOLUME) {
    iconSpeaker(s, 0, 12, COL_TEXT);
    const int bx = 20, bw = W - 26, by = 8, bh = 10;
    s.fillRoundRect(bx, by, bw, bh, 5, COL_FAINT);
    if (track.hasState && track.volMax > 0) {
      int fw = (int)((long)bw * track.vol / track.volMax);
      if (fw > 0) s.fillRoundRect(bx, by, fw, bh, 5, COL_ACCENT);
      char buf[24];
      snprintf(buf, sizeof(buf), "Volume %d%%", (int)(track.vol * 100 / track.volMax));
      s.setTextColor(COL_TEXT, COL_BG);
      s.drawString(buf, (W - s.textWidth(buf)) / 2, 20);
    } else {
      s.setTextColor(COL_DIM, COL_BG);
      const char* m = "Volume  (level needs app)";
      s.drawString(m, (W - s.textWidth(m)) / 2, 20);
    }
  } else if (track.hasState) {
    const int bx = 0, bw = W - 6, by = 4, bh = 6;
    if (track.timeValid && track.durMs > 0) {
      uint32_t pos = currentPositionMs(now);
      s.fillRoundRect(bx, by, bw, bh, 3, COL_FAINT);
      int fw = (int)((uint64_t)bw * pos / track.durMs);
      if (fw > 0) s.fillRoundRect(bx, by, fw, bh, 3, COL_ACCENT);
      char a[12], b[12];
      formatTime(a, sizeof(a), pos);
      formatTime(b, sizeof(b), track.durMs);
      s.setTextColor(COL_DIM, COL_BG);
      s.drawString(a, bx, 15);
      s.drawString(b, bx + bw - s.textWidth(b), 15);
    }
    if (track.playing) iconPause(s, bw / 2, 24, 14, COL_TEXT);
    else               iconPlay (s, bw / 2, 24, 14, COL_TEXT);
  }
  s.pushSprite(COL_X, 74);
  lastMidDrawMs = now;
}

static void drawBottomBar(uint32_t now) {
  Seeed_Sprite& s = sprBottom;  // 240 x 24
  s.fillSprite(COL_BG);
  s.drawFastHLine(0, 0, SCR_W, COL_FAINT);
  const int cy = 13;

  if (uiMode == MODE_VOLUME) {
    s.fillRect(14, cy - 2, 16, 4, COL_TEXT);                  // "-"
    s.fillRect(SCR_W - 30, cy - 2, 16, 4, COL_TEXT);          // "+"
    s.fillRect(SCR_W - 24, cy - 8, 4, 16, COL_TEXT);
  } else {
    iconPrev(s, 22, cy, 12, COL_DIM);
    iconNext(s, SCR_W - 22, cy, 12, COL_DIM);
  }

  s.setTextFont(2);
  const char* center;
  uint16_t c = COL_FAINT;
  if (toastText[0] && now < toastUntil) { center = toastText; c = COL_TEXT; }
  else center = (uiMode == MODE_VOLUME) ? "VOLUME" : "hold side: volume";
  s.setTextColor(c, COL_BG);
  s.drawString(center, (SCR_W - s.textWidth(center)) / 2, 5);
  s.pushSprite(0, 111);
}

// ----------------------------------------------------------------------------
// Album art (left of the text). Drawn straight to the screen.
// ----------------------------------------------------------------------------
static bool     artValid = false;          // artPix holds the current track's art
static bool     dirtyArt = true;
static uint32_t artWaitSince = 0;          // set on track change; placeholder if nothing arrives

static void drawArt() {
  if (artValid) {
    display.pushImage(ART_X, ART_Y, ART_W, ART_H, artPix);
    return;
  }
  // placeholder: dark tile with a music note
  display.fillRoundRect(ART_X, ART_Y, ART_W, ART_H, 6, 0x18E3);
  const uint16_t c = COL_DIM;
  const int cx = ART_X + ART_W / 2, cy = ART_Y + ART_H / 2;
  display.fillCircle(cx - 9, cy + 10, 6, c);
  display.fillCircle(cx + 11, cy + 6, 6, c);
  display.fillRect(cx - 5, cy - 14, 3, 24, c);
  display.fillRect(cx + 15, cy - 18, 3, 24, c);
  display.fillTriangle(cx - 5, cy - 14, cx + 18, cy - 18, cx + 18, cy - 12, c);
  display.fillTriangle(cx - 5, cy - 14, cx - 5, cy - 8, cx + 18, cy - 12, c);
}

static void fullRedraw() {
  display.fillScreen(COL_BG);
  display.drawFastHLine(0, 19, SCR_W, COL_FAINT);
  dirtyStatus = dirtyText = dirtyMid = dirtyBottom = dirtyArt = true;
  mqTitle.dirty = mqArtist.dirty = true;
}

static void render(uint32_t now) {
  if (!screenOn) return;
  if (dirtyStatus) { dirtyStatus = false; drawStatusBar(); }
  if (dirtyText)   { dirtyText = false; updateTextLines(); }
  if (dirtyArt)    { dirtyArt = false; drawArt(); }
  mqTitle.tick(now);
  mqArtist.tick(now);
  if (toastText[0] && now >= toastUntil) { toastText[0] = 0; dirtyBottom = true; }
  bool tickProgress = uiMode == MODE_TRACK && track.playing && track.timeValid && now - lastMidDrawMs >= 500;
  if (dirtyMid || tickProgress) { dirtyMid = false; drawMidPanel(now); }
  if (dirtyBottom) { dirtyBottom = false; drawBottomBar(now); }
}

// ----------------------------------------------------------------------------
// Screen power
// ----------------------------------------------------------------------------
// The display library turns the backlight on with analogWrite() (hardware PWM). While a PWM
// module owns the pin, digitalWrite() has no effect, so the backlight could never be switched
// off. Take the pin back from PWM, stop the module, and drive it as a plain GPIO instead.
static void releaseBacklightPwm() {
  for (int i = 0; i < HWPWM_MODULE_NUM; i++) {
    HardwarePWM* pwm = HwPWMx[i];
    if (pwm->pin2channel(LCD_BL_PIN) >= 0) {
      pwm->removePin(LCD_BL_PIN);
      if (pwm->usedChannelCount() == 0) pwm->stop();
      LOG("Backlight released from PWM%d", i);
    }
  }
  pinMode(LCD_BL_PIN, OUTPUT);
}

static void screenSleep() {
  if (!screenOn) return;
  releaseBacklightPwm();             // (no-op once done at startup; belt and braces)
  LOG("Screen off");
  digitalWrite(LCD_BL_PIN, LOW);     // backlight off (Q2/Q1 switch, pulled off by R8)
  display.writecommand(0x28);        // ST7789 DISPOFF: blank the panel output first
  delay(20);
  display.writecommand(0x10);        // ST7789 SLPIN: then sleep the controller
  screenOn = false;
}

static void screenWake() {
  if (screenOn) return;
  display.writecommand(0x11);        // ST7789 SLPOUT
  delay(120);
  display.writecommand(0x29);        // ST7789 DISPON
  screenOn = true;
  fullRedraw();
  render(millis());
  digitalWrite(LCD_BL_PIN, HIGH);
}

static void userActivity() {
  lastActivityMs = millis();
  if (!screenOn) LOG("Screen on: button");
  screenWake();
}

// ----------------------------------------------------------------------------
// Sending keys / commands
// ----------------------------------------------------------------------------
static void hidSend(uint16_t usage) {
  if (Bluefruit.connected()) blehid.inputReport(HID_REPORT_ID, &usage, sizeof(usage));
}

static bool hidTap(uint16_t usage) {
  if (!Bluefruit.connected()) { showToast("Not connected"); return false; }
  hidSend(usage);
  delay(10);
  hidSend(0);
  return true;
}

static void sendAppCommand(uint8_t cmd) {
  if (appLinked()) npCommand.notify8(cmd);
}

// ----------------------------------------------------------------------------
// Power off (System OFF, ~ a few uA). The side button wakes it (= reboot).
// ----------------------------------------------------------------------------
static void powerOff(const char* reason) {
  LOG("Power off: %s", reason);
  screenWake();
  display.fillScreen(COL_BG);
  display.setTextFont(4);
  display.setTextColor(COL_TEXT, COL_BG);
  const char* m = "Powering off";
  display.drawString(m, (SCR_W - display.textWidth(m)) / 2, 45);
  display.setTextFont(2);
  display.setTextColor(COL_DIM, COL_BG);
  display.setTextColor(COL_WARN, COL_BG);
  display.drawString(reason, (SCR_W - display.textWidth(reason)) / 2, 78);
  display.setTextColor(COL_DIM, COL_BG);
  m = "Press the side button to turn on";
  display.drawString(m, (SCR_W - display.textWidth(m)) / 2, 100);
  delay(1200);

  while (digitalRead(BTN_SIDE) == LOW) delay(10);   // must release first or it wakes instantly
  delay(50);

  Bluefruit.Advertising.stop();
  if (Bluefruit.connected()) Bluefruit.disconnect(Bluefruit.connHandle());
  delay(100);
  screenSleep();
  systemOff(BTN_SIDE, LOW);
}

// ----------------------------------------------------------------------------
// Buttons
// ----------------------------------------------------------------------------
// (Button / ArrowState are defined near the top, before the first function,
//  so Arduino's auto-generated prototypes can see them.)

Button btnLeft, btnRight, btnSide;
ArrowState stLeft, stRight;
static uint8_t sideStage = 0;   // 0 = short, 1 = volume toggled, 2 = power-off armed

static void enterVolumeMode(bool on) {
  if ((uiMode == MODE_VOLUME) != on) LOG("%s volume mode", on ? "Enter" : "Leave");
  uiMode = on ? MODE_VOLUME : MODE_TRACK;
  volModeUntil = millis() + VOLUME_MODE_TIMEOUT_MS;
  dirtyMid = dirtyBottom = true;
}

static void volumeStep(int dir) {
  if (!hidTap(dir > 0 ? KEY_VOL_UP : KEY_VOL_DOWN)) return;
  volModeUntil = millis() + VOLUME_MODE_TIMEOUT_MS;
  if (track.hasState && track.volMax > 0) {          // optimistic update, app confirms
    int v = (int)track.vol + dir;
    if (v < 0) v = 0;
    if (v > track.volMax) v = track.volMax;
    track.vol = (uint8_t)v;
  }
  dirtyMid = true;
}

static void handleArrow(Button& b, ArrowState& s, int dir, uint32_t now) {
  if (b.pressed) {
    userActivity();
    s.seeking = s.hidHeld = false;
    if (uiMode == MODE_VOLUME) {
      volumeStep(dir);
      s.nextRepeat = now + VOL_REPEAT_DELAY_MS;
    }
  }

  if (b.down) {
    if (uiMode == MODE_VOLUME) {
      if (now >= s.nextRepeat) {
        volumeStep(dir);
        s.nextRepeat = now + VOL_REPEAT_MS;
        lastActivityMs = now;
      }
    } else if (!s.seeking && b.heldFor(now) >= SEEK_HOLD_MS) {
      s.seeking = true;
      if (appLinked()) {
        sendAppCommand(dir > 0 ? CMD_SEEK_FWD : CMD_SEEK_BACK);
        s.nextRepeat = now + SEEK_REPEAT_MS;
        showToast(dir > 0 ? ">> +10s" : "<< -10s");
      } else if (Bluefruit.connected()) {
        hidSend(dir > 0 ? KEY_FFWD : KEY_REWIND);    // hold the key until release
        s.hidHeld = true;
        showToast(dir > 0 ? "Fast forward" : "Rewind", 60000);
      }
    } else if (s.seeking && !s.hidHeld && now >= s.nextRepeat) {
      sendAppCommand(dir > 0 ? CMD_SEEK_FWD : CMD_SEEK_BACK);
      s.nextRepeat = now + SEEK_REPEAT_MS;
      showToast(dir > 0 ? ">> +10s" : "<< -10s");
      lastActivityMs = now;
    }
  }

  if (b.released) {
    if (s.hidHeld) {
      hidSend(0);
      toastUntil = 0;
    } else if (uiMode == MODE_TRACK && !s.seeking) {
      if (hidTap(dir > 0 ? KEY_NEXT : KEY_PREV)) showToast(dir > 0 ? "Next" : "Previous");
    }
    s.seeking = s.hidHeld = false;
  }
}

static void handleSide(uint32_t now) {
  if (btnSide.pressed) { userActivity(); sideStage = 0; }

  if (btnSide.down) {
    uint32_t h = btnSide.heldFor(now);
    if (sideStage == 0 && h >= SIDE_HOLD_MS) {
      sideStage = 1;
      enterVolumeMode(uiMode != MODE_VOLUME);
    }
    if (sideStage == 1 && h >= POWER_HOLD_MS) {
      sideStage = 2;
      enterVolumeMode(false);
      showToast("Release to power off", 60000);
    }
  }

  if (btnSide.released) {
    if (sideStage == 0) {
      if (hidTap(KEY_PLAY_PAUSE)) {
        if (track.hasState) showToast(track.playing ? "Pause" : "Play");
        else                showToast("Play / Pause");
      }
    } else if (sideStage == 2) {
      powerOff("Side button held 3 s");
    }
    sideStage = 0;
  }
}

// ----------------------------------------------------------------------------
// Battery
// ----------------------------------------------------------------------------
static int lipoPercent(float v) {
  struct P { float v; int p; };
  static const P t[] = {
    {4.20f, 100}, {4.10f, 90}, {4.00f, 80}, {3.92f, 70}, {3.85f, 60},
    {3.79f, 50},  {3.72f, 40}, {3.66f, 30}, {3.58f, 20}, {3.50f, 10},
    {3.30f, 5},   {3.10f, 1},  {3.00f, 0}
  };
  const int n = sizeof(t) / sizeof(t[0]);
  if (v >= t[0].v) return 100;
  if (v <= t[n - 1].v) return 0;
  for (int i = 0; i < n - 1; i++) {
    if (v <= t[i].v && v >= t[i + 1].v) {
      float f = (v - t[i + 1].v) / (t[i].v - t[i + 1].v);
      return t[i + 1].p + (int)(f * (t[i].p - t[i + 1].p) + 0.5f);
    }
  }
  return 0;
}

static bool readChargingPin() {
  uint8_t low = 0;
  for (uint8_t i = 0; i < 7; i++) {
    if ((NRF_P0->IN & (1UL << CHG_P0)) == 0) low++;
    delayMicroseconds(300);
  }
  return low >= 4;
}

static void pollPower(bool readVoltage) {
  bool usb = (NRF_POWER->USBREGSTATUS & POWER_USBREGSTATUS_VBUSDETECT_Msk) != 0;
  bool chg = usb && readChargingPin();
  if (usb != usbPower || chg != batCharging) dirtyStatus = true;
  usbPower = usb; batCharging = chg;

  if (!readVoltage) return;

  NRF_P0->OUTCLR = (1UL << READ_BAT_P0);   // divider on (it is kept on; see READ_BAT_P0)
  NRF_P0->DIRSET = (1UL << READ_BAT_P0);
  uint32_t sum = 0;
  for (int i = 0; i < 16; i++) { sum += analogRead(PIN_VBAT); delay(1); }

  float v = (sum / 16.0f) * 3.6f / 4095.0f * BAT_DIVIDER;
  int pct;
  // No battery connected: nothing in range, or (on USB) the charger output with no cell behind it,
  // e.g. the power slide switch is OFF - that switch sits between the battery and the charger.
  if (v < 2.8f || v > 4.4f || (usb && v < 3.0f)) pct = -1;
  else {
    pct = lipoPercent(v);
    if (batPercent >= 0) pct = (batPercent * 3 + pct + 2) / 4;  // smooth
  }
  batVolts = v;
  if (pct != batPercent) {
    batPercent = pct;
    dirtyStatus = true;
    if (pct >= 0) { blebas.write((uint8_t)pct); blebas.notify((uint8_t)pct); }
  }
}

// ----------------------------------------------------------------------------
// BLE callbacks
// ----------------------------------------------------------------------------
static void onTitleWrite(uint16_t, BLECharacteristic*, uint8_t* data, uint16_t len) {
  if (len > TEXT_MAX) len = TEXT_MAX;
  taskENTER_CRITICAL();
  memcpy(rxTitle, data, len); rxTitle[len] = 0; rxTitleSeq++;
  taskEXIT_CRITICAL();
}

static void onArtistWrite(uint16_t, BLECharacteristic*, uint8_t* data, uint16_t len) {
  if (len > TEXT_MAX) len = TEXT_MAX;
  taskENTER_CRITICAL();
  memcpy(rxArtist, data, len); rxArtist[len] = 0; rxArtistSeq++;
  taskEXIT_CRITICAL();
}

static void onArtWrite(uint16_t, BLECharacteristic*, uint8_t* data, uint16_t len) {
  if (len < 3) return;
  uint8_t  id  = data[0];
  uint16_t off = data[1] | (data[2] << 8);
  taskENTER_CRITICAL();
  if (off == 0xFFFF) {                          // this track has no art
    rxArtId = id; rxArtMask = 0;
    artNoneFlag = true; artNoneSeq++;
  } else if (off % ART_CHUNK == 0 && off + (len - 3) <= ART_BYTES && len - 3 == ART_CHUNK) {
    if (id != rxArtId) { rxArtId = id; rxArtMask = 0; }
    memcpy(artRx + off, data + 3, ART_CHUNK);
    rxArtMask |= (1ULL << (off / ART_CHUNK));
    if (rxArtMask == ~0ULL) {                    // all 64 chunks in
      memcpy(artReady, artRx, ART_BYTES);
      rxArtMask = 0;
      artNoneFlag = false;
      artSeq++;
    }
  }
  taskEXIT_CRITICAL();
}

static void onStateWrite(uint16_t, BLECharacteristic*, uint8_t* data, uint16_t len) {
  if (len < 11) return;
  taskENTER_CRITICAL();
  memset(rxState, 0, sizeof(rxState));
  memcpy(rxState, data, len > STATE_LEN ? STATE_LEN : len);
  rxStateMs = millis();
  rxStateSeq++;
  taskEXIT_CRITICAL();
}

static void onConnect(uint16_t conn) {
  (void)conn;
  bleConnEvent = true;
}

static void onDisconnect(uint16_t conn, uint8_t reason) {
  (void)conn; (void)reason;
  bleConnEvent = true;
}

// Copy anything the BLE callbacks received into `track`
static void pullBleData(uint32_t now) {
  static uint32_t seenTitle = 0, seenArtist = 0, seenState = 0;
  char tmp[TEXT_MAX + 1];
  bool trackChanged = false;

  if (rxTitleSeq != seenTitle) {
    taskENTER_CRITICAL(); memcpy(tmp, rxTitle, sizeof(tmp)); seenTitle = rxTitleSeq; taskEXIT_CRITICAL();
    char clean[TEXT_MAX + 1]; sanitizeCopy(clean, tmp);
    if (strcmp(clean, track.title) != 0) { strcpy(track.title, clean); trackChanged = true; }
    dirtyText = true;
  }
  if (rxArtistSeq != seenArtist) {
    taskENTER_CRITICAL(); memcpy(tmp, rxArtist, sizeof(tmp)); seenArtist = rxArtistSeq; taskEXIT_CRITICAL();
    sanitizeCopy(track.artist, tmp);
    dirtyText = true;
  }
  if (rxStateSeq != seenState) {
    uint8_t st[STATE_LEN]; uint32_t at;
    taskENTER_CRITICAL(); memcpy(st, rxState, STATE_LEN); at = rxStateMs; seenState = rxStateSeq; taskEXIT_CRITICAL();
    bool wasPlaying = track.playing;
    track.hasState  = true;
    track.playing   = st[0] & 0x01;
    track.timeValid = st[0] & 0x02;
    track.posMs     = rd32(&st[1]);
    track.durMs     = rd32(&st[5]);
    track.vol       = st[9];
    track.volMax    = st[10];
    track.stateAtMs = at;
    if (wasPlaying != track.playing) dirtyText = true;
    dirtyMid = true;
  }

  // album art. A track change starts a short wait; new art (or "no art") ends it.
  if (trackChanged) artWaitSince = now ? now : 1;      // keep the old art until the new one lands...
  static uint32_t seenArt = 0, seenNone = 0;
  if (artSeq != seenArt) {
    taskENTER_CRITICAL(); memcpy(artPix, artReady, ART_BYTES); seenArt = artSeq; taskEXIT_CRITICAL();
    artValid = true; artWaitSince = 0; dirtyArt = true;
  }
  if (artNoneSeq != seenNone) {
    seenNone = artNoneSeq;
    if (artNoneFlag) { artValid = false; artWaitSince = 0; dirtyArt = true; }
  }
  // ...or give up and show the placeholder
  if (artWaitSince && now - artWaitSince > ART_WAIT_MS) {
    artWaitSince = 0;
    if (artValid) { artValid = false; dirtyArt = true; }
  }

  // Light the screen for a new song, but not when the title just flips back to the one before
  // (e.g. the phone alternating between two players) and at most once every 30 s.
  static char prevTitle[TEXT_MAX + 1] = "";
  static char lastTitle[TEXT_MAX + 1] = "";
  static uint32_t lastTrackWakeMs = 0;
  if (trackChanged) {
    bool flipBack = strcmp(track.title, prevTitle) == 0;
    strcpy(prevTitle, lastTitle);
    strcpy(lastTitle, track.title);
    if (WAKE_ON_TRACK_CHANGE && track.title[0] && !flipBack &&
        (lastTrackWakeMs == 0 || now - lastTrackWakeMs > TRACK_WAKE_MIN_GAP_MS)) {
      lastTrackWakeMs = now;
      screenKeepUntil = now + TRACK_CHANGE_WAKE_MS;
      if (!screenOn) LOG("Screen on: new track \"%s\"", track.title);
      screenWake();
    } else if (!screenOn) {
      LOG("Track change ignored for wake (%s)", flipBack ? "flip-back" : "too soon");
    }
  }
}

// ----------------------------------------------------------------------------
// BLE setup
// ----------------------------------------------------------------------------
static void setupBle() {
  Bluefruit.configPrphBandwidth(BANDWIDTH_MAX);   // bigger MTU for track titles
  Bluefruit.begin();
  Bluefruit.autoConnLed(false);                   // save power
  Bluefruit.setTxPower(4);
  Bluefruit.setName(DEVICE_NAME);
  Bluefruit.Periph.setConnectCallback(onConnect);
  Bluefruit.Periph.setDisconnectCallback(onDisconnect);

  bledis.setManufacturer("DIY");
  bledis.setModel("XIAO nRF52840 Media Remote");
  bledis.begin();

  blebas.begin();
  blebas.write(100);

  blehid.setReportLen(hidInputLen);
  blehid.setReportMap(hidReportMap, sizeof(hidReportMap));
  blehid.begin();
  Bluefruit.Periph.setConnInterval(12, 24);       // 15-30 ms: responsive, frugal

  npService.begin();

  npTitle.setProperties(CHR_PROPS_WRITE | CHR_PROPS_WRITE_WO_RESP);
  npTitle.setPermission(SECMODE_NO_ACCESS, SECMODE_OPEN);
  npTitle.setMaxLen(TEXT_MAX);
  npTitle.setWriteCallback(onTitleWrite);
  npTitle.begin();

  npArtist.setProperties(CHR_PROPS_WRITE | CHR_PROPS_WRITE_WO_RESP);
  npArtist.setPermission(SECMODE_NO_ACCESS, SECMODE_OPEN);
  npArtist.setMaxLen(TEXT_MAX);
  npArtist.setWriteCallback(onArtistWrite);
  npArtist.begin();

  npState.setProperties(CHR_PROPS_WRITE | CHR_PROPS_WRITE_WO_RESP);
  npState.setPermission(SECMODE_NO_ACCESS, SECMODE_OPEN);
  npState.setMaxLen(STATE_LEN);
  npState.setWriteCallback(onStateWrite);
  npState.begin();

  npCommand.setProperties(CHR_PROPS_READ | CHR_PROPS_NOTIFY);
  npCommand.setPermission(SECMODE_OPEN, SECMODE_NO_ACCESS);
  npCommand.setFixedLen(1);
  npCommand.begin();
  npCommand.write8(0);

  npArt.setProperties(CHR_PROPS_WRITE | CHR_PROPS_WRITE_WO_RESP);
  npArt.setPermission(SECMODE_NO_ACCESS, SECMODE_OPEN);
  npArt.setMaxLen(3 + ART_CHUNK);
  npArt.setWriteCallback(onArtWrite);
  npArt.begin();

  // Advertising: flags + tx power + appearance + HID service + name (fits 31 bytes)
  Bluefruit.Advertising.addFlags(BLE_GAP_ADV_FLAGS_LE_ONLY_GENERAL_DISC_MODE);
  Bluefruit.Advertising.addTxPower();
  Bluefruit.Advertising.addAppearance(0x03C0);    // Generic HID
  Bluefruit.Advertising.addService(blehid);
  Bluefruit.Advertising.addName();
  Bluefruit.ScanResponse.addService(npService);   // lets the app find it by UUID too
  Bluefruit.Advertising.restartOnDisconnect(true);
  Bluefruit.Advertising.setInterval(32, 244);     // fast 20 ms, then slow 152.5 ms
  Bluefruit.Advertising.setFastTimeout(30);
  Bluefruit.Advertising.start(0);
}

// ----------------------------------------------------------------------------
// setup / loop
// ----------------------------------------------------------------------------
void setup() {
  Serial.begin(115200);

  btnLeft.begin(BTN_LEFT, "LEFT");
  btnRight.begin(BTN_RIGHT, "RIGHT");
  btnSide.begin(BTN_SIDE, "SIDE");
  LOG("MediaRemote started");

  nrf_gpio_cfg_input(CHG_P0, NRF_GPIO_PIN_PULLUP);
  NRF_P0->OUTCLR = (1UL << READ_BAT_P0);   // battery divider on, permanently (protects P0.31 while charging)
  NRF_P0->DIRSET = (1UL << READ_BAT_P0);
  analogReadResolution(12);

  if (!display.begin<Board_XIAO_1inch14_LCD<LCD_RST_PIN, LCD_BL_PIN>, RemotePanelConfig>()) {
    Serial.println(display.lastResult().message);
  }
  releaseBacklightPwm();                     // backlight on/off as plain GPIO from here on
  digitalWrite(LCD_BL_PIN, HIGH);
  display.setRotation(SCREEN_ROTATION);
  display.fillScreen(COL_BG);

  sprStatus.createSprite(display, SCR_W, 18);
  sprMid.createSprite(display, COL_W, 36);
  sprBottom.createSprite(display, SCR_W, 24);
  mqTitle.begin(COL_X - 4, 23, COL_W + 4, 28, 4);
  mqArtist.begin(COL_X - 4, 53, COL_W + 4, 18, 2);

  setupBle();

  // Hold LEFT + RIGHT at power-on to forget all paired phones
  if (digitalRead(BTN_LEFT) == LOW && digitalRead(BTN_RIGHT) == LOW) {
    Bluefruit.Periph.clearBonds();
    display.setTextFont(4);
    display.setTextColor(COL_WARN, COL_BG);
    const char* m = "Pairings cleared";
    display.drawString(m, (SCR_W - display.textWidth(m)) / 2, 55);
    delay(1500);
    while (digitalRead(BTN_LEFT) == LOW || digitalRead(BTN_RIGHT) == LOW) delay(10);
  }

  pollPower(true);
  nextBatReadMs = millis() + 30000;
  lastActivityMs = lastConnectedMs = millis();
  fullRedraw();
}

void loop() {
  uint32_t now = millis();

  // --- connection changes
  if (bleConnEvent) {
    bleConnEvent = false;
    bool c = Bluefruit.connected();
    if (!c) {                         // forget stale track info on disconnect
      track = Track();
      artValid = false; artWaitSince = 0; dirtyArt = true;
      uiMode = MODE_TRACK;
    }
    LOG("%s", c ? "Phone connected" : "Phone disconnected");
    showToast(c ? "Phone connected" : "Disconnected", 1500);
    userActivity();
  }
  bool connected = Bluefruit.connected();
  bool linked = appLinked();
  if (connected != wasConnected || linked != wasAppLinked) {
    wasConnected = connected; wasAppLinked = linked;
    dirtyStatus = dirtyText = dirtyMid = true;
  }
  if (connected) lastConnectedMs = now;

  pullBleData(now);

  // --- buttons
  btnLeft.update(now);
  btnRight.update(now);
  btnSide.update(now);
  handleArrow(btnLeft,  stLeft,  -1, now);
  handleArrow(btnRight, stRight, +1, now);
  handleSide(now);

  if (uiMode == MODE_VOLUME && now >= volModeUntil && !btnLeft.down && !btnRight.down && !btnSide.down) {
    enterVolumeMode(false);
  }

  // --- battery
  if (now >= nextChgPollMs) { nextChgPollMs = now + 1000; pollPower(false); }
  if (now >= nextBatReadMs) { nextBatReadMs = now + 30000; pollPower(true); }

  // --- screen timeout / auto power-off
  bool anyDown = btnLeft.down || btnRight.down || btnSide.down;
  if (screenOn && !anyDown && now - lastActivityMs > SCREEN_TIMEOUT_MS && now > screenKeepUntil) {
    screenSleep();
  }
  if (!connected && !anyDown && now - lastConnectedMs > AUTO_OFF_DISCONNECTED_MS &&
      now - lastActivityMs > AUTO_OFF_DISCONNECTED_MS) {
    powerOff("No phone for 10 min");
  }

  render(now);
  delay(screenOn ? 5 : 15);   // the nRF52 core sleeps the CPU during delay()
}
