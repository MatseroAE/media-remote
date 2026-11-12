/*
  ButtonTest - find out which pin each button is really on.
  Board: Seeed XIAO nRF52840 Plus (same settings as MediaRemote).

  1. Upload, then open Tools > Serial Monitor at 115200 baud.
  2. Don't touch anything for ~10 s: every line printed then is a
     "ghost" change (noise) and is worth reporting.
  3. Press and release LEFT, RIGHT, then SIDE, one at a time.
  4. Copy the Serial Monitor text back to Claude.
*/
#include <Arduino.h>
#include <Adafruit_TinyUSB.h>

struct P { const char* name; uint8_t pin; };
// 36 and 38 are the two candidates for the side button (D19 / D17).
// 37 is the backlight, so it is left alone.
P pins[] = {
  {"LEFT(D6)", D6}, {"RIGHT(D7)", D7}, {"pin36", 36}, {"pin38", 38},
};
const int N = sizeof(pins) / sizeof(pins[0]);
int last[N];

void printAll(const char* why) {
  Serial.print(millis()); Serial.print(" ms  "); Serial.print(why); Serial.print(":  ");
  for (int i = 0; i < N; i++) {
    Serial.print(pins[i].name); Serial.print('=');
    Serial.print(last[i] ? "up" : "DOWN"); Serial.print("  ");
  }
  Serial.println();
}

void setup() {
  Serial.begin(115200);
  while (!Serial && millis() < 5000) delay(10);
  for (int i = 0; i < N; i++) { pinMode(pins[i].pin, INPUT_PULLUP); }
  delay(20);
  for (int i = 0; i < N; i++) last[i] = digitalRead(pins[i].pin);
  Serial.println("ButtonTest ready. 'DOWN' = pin reads LOW (pressed).");
  printAll("start");
}

void loop() {
  bool changed = false;
  for (int i = 0; i < N; i++) {
    int v = digitalRead(pins[i].pin);
    if (v != last[i]) { last[i] = v; changed = true; }
  }
  if (changed) printAll("change");
  static uint32_t t = 0;
  if (millis() - t > 5000) { t = millis(); printAll("still"); }
  delay(5);
}
