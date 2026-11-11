// Car Message 핸들 버튼 (아두이노)
// 버튼 한쪽 다리는 D2, 다른 쪽은 GND. 내부 풀업을 쓰므로 저항이 필요 없다.
// 누르면 "BTN:DOWN", 떼면 "BTN:UP" 한 줄씩 보낸다 (115200 bps). docs/device-signals.md 참고.

const int BUTTON_PIN = 2;
const unsigned long DEBOUNCE_MS = 30;

bool pressed = false;
bool lastReading = false;
unsigned long changedAt = 0;

void setup() {
  pinMode(BUTTON_PIN, INPUT_PULLUP);
  pinMode(LED_BUILTIN, OUTPUT);
  Serial.begin(115200);
}

void loop() {
  bool reading = digitalRead(BUTTON_PIN) == LOW;
  if (reading != lastReading) {
    lastReading = reading;
    changedAt = millis();
  }
  if (reading != pressed && millis() - changedAt >= DEBOUNCE_MS) {
    pressed = reading;
    digitalWrite(LED_BUILTIN, pressed ? HIGH : LOW);
    Serial.println(pressed ? "BTN:DOWN" : "BTN:UP");
  }
}
