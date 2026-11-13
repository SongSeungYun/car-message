#!/usr/bin/env python3
"""Car Talk 맥 음성 브리지.

하는 일 (docs/device-signals.md 기준)
  1. 핸들 버튼(아두이노 USB 시리얼 `BTN:DOWN` / `BTN:UP`) 또는 키보드 Enter를 읽는다.
  2. 버튼을 누르면 앱에 `PTT DOWN` 신호를 보내고 맥 마이크 녹음을 시작한다.
  3. 버튼을 떼면 녹음을 멈추고 한국어로 인식해서 앱에 `VOICE_RESULT` 신호를 보낸다.
     (`PTT UP`은 보내지 않는다. 앱은 결과를 받으면 듣기를 끝낸다.)
  4. 앱이 `POST http://10.0.2.2:8090/speak {"car":"B","text":"..."}`를 보내면 맥 스피커로 읽는다.

무거운 라이브러리(마이크·인식·시리얼)는 쓸 때만 불러온다.
그래서 `--check`, `--say`, 키보드 모드, 테스트는 일부 라이브러리가 없어도 돈다.
"""
from __future__ import annotations

import argparse
import glob
import json
import logging
import queue
import re
import shlex
import shutil
import subprocess
import sys
import threading
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

log = logging.getLogger("bridge")

DEFAULT_PACKAGE = "com.hyundaiproject.messaging"
SAMPLE_RATE = 16_000
MIN_AUDIO_SEC = 0.3
MAX_SPEAK_CHARS = 200
# fe/MessagingController VOICE_TIMEOUT_MS: 앱은 PTT DOWN부터 5초 뒤 듣기를 끝내고 늦은 결과는 버린다.
APP_LISTEN_SEC = 5

# 인식 결과를 앱이 알아듣는 문장("N번 메시지 보내줘")으로 맞춘다.
# 앱(fe/MessagingController)은 숫자와 정확한 문형만 받기 때문이다.
_NUMBER_WORDS = {
    "열": 10, "십": 10,
    "하나": 1, "한": 1, "일": 1,
    "둘": 2, "두": 2, "이": 2,
    "셋": 3, "세": 3, "삼": 3,
    "넷": 4, "네": 4, "사": 4,
    "다섯": 5, "오": 5,
    "여섯": 6, "육": 6, "륙": 6,
    "일곱": 7, "칠": 7,
    "여덟": 8, "팔": 8,
    "아홉": 9, "구": 9,
}
# 긴 단어부터 맞춰야 "여섯"이 "여"+"섯"으로 잘못 잡히지 않는다.
_WORD_ALT = "|".join(sorted(_NUMBER_WORDS, key=len, reverse=True))
# 앞에 다른 한글이 붙은 경우(예: "삼십번")는 숫자로 보지 않는다.
# "이번 메시지"는 "2번"으로 읽는다: 인식기가 "2번"을 "이번"으로 쓰는 경우가 있어서다.
_NUMBER_RE = re.compile(rf"(?<![가-힣0-9])(10|[1-9]|{_WORD_ALT})\s*(?:번째|번)")
_SEND_HINT_RE = re.compile(r"보내|전송|메시지|메세지")
_RETRY_RE = re.compile(r"재\s*전송|다시\s*보내")

WHISPER_PROMPT = "1번 메시지 보내줘. 2번 메시지 보내줘. 3번 메시지 보내줘. 재전송해줘."


def normalize_command(raw: str) -> str:
    """인식된 말을 앱이 받는 문장으로 바꾼다. 못 알아들으면 원문을 그대로 돌려준다."""
    text = (raw or "").strip()
    if not text:
        return ""
    compact = re.sub(r"[\s.,!?~·]", "", text)
    if _RETRY_RE.search(text) and "번" not in compact:
        return "재전송해줘"
    match = _NUMBER_RE.search(text)
    if match:
        token = match.group(1)
        number = int(token) if token.isdigit() else _NUMBER_WORDS[token]
        only_number = re.fullmatch(r"(10|[1-9]|" + _WORD_ALT + r")(번째|번)", compact) is not None
        if 1 <= number <= 10 and (_SEND_HINT_RE.search(text) or only_number):
            return f"{number}번 메시지 보내줘"
    return text


def adb_broadcast_args(adb: str, serial: str | None, package: str, action: str,
                       extra_key: str, extra_value: str) -> list[str]:
    """adb broadcast 명령. 원격 셸이 한 줄로 해석하므로 값은 셸 따옴표로 감싼다."""
    remote = (f"am broadcast -a {package}.{action} -p {package} "
              f"--es {extra_key} {shlex.quote(extra_value)}")
    cmd = [adb]
    if serial:
        cmd += ["-s", serial]
    cmd += ["shell", remote]
    return cmd


class Adb:
    def __init__(self, adb: str, serial: str | None, package: str, dry_run: bool):
        self.adb, self.serial, self.package, self.dry_run = adb, serial, package, dry_run

    def broadcast(self, action: str, key: str, value: str) -> None:
        cmd = adb_broadcast_args(self.adb, self.serial, self.package, action, key, value)
        if self.dry_run:
            log.info("[dry-run] %s", " ".join(shlex.quote(c) for c in cmd))
            return
        started = time.monotonic()
        try:
            out = subprocess.run(cmd, capture_output=True, text=True, timeout=5)
        except (OSError, subprocess.TimeoutExpired) as exc:
            log.error("adb 실패: %s", exc)
            return
        ms = (time.monotonic() - started) * 1000
        if out.returncode != 0 or "Broadcast completed" not in out.stdout:
            log.error("adb 응답 이상(%d ms): %s %s", ms, out.stdout.strip(), out.stderr.strip())
        else:
            log.info("→ 앱 %s %s=%r (%d ms)", action, key, value, ms)


class Speaker:
    """/speak로 받은 글자를 순서대로 맥 `say`로 읽는다."""

    def __init__(self, voice: str | None, rate: int, enabled: bool = True):
        self.voice, self.rate, self.enabled = voice, rate, enabled
        self.jobs: queue.Queue[str] = queue.Queue()
        threading.Thread(target=self._run, daemon=True).start()

    def say(self, text: str) -> None:
        self.jobs.put(text)

    def _run(self) -> None:
        while True:
            text = self.jobs.get()
            cmd = ["say", "-r", str(self.rate)]
            if self.voice:
                cmd += ["-v", self.voice]
            cmd.append(text)
            try:
                if not self.enabled or shutil.which("say") is None:
                    log.info("[읽기 생략] %s", text)
                    continue
                started = time.monotonic()
                subprocess.run(cmd, check=False)
                log.info("♪ 읽음 %r (%d ms)", text, (time.monotonic() - started) * 1000)
            finally:
                self.jobs.task_done()


def korean_voice() -> str | None:
    """설치된 첫 한국어 목소리(ko_KR). 없으면 None (기본 목소리는 한국어를 제대로 못 읽음)."""
    if shutil.which("say") is None:
        return None
    out = subprocess.run(["say", "-v", "?"], capture_output=True, text=True).stdout
    for line in out.splitlines():
        if "ko_KR" in line:
            name = line.split("ko_KR")[0].strip()
            return name or None
    return None


def make_speak_handler(speaker: Speaker):
    class SpeakHandler(BaseHTTPRequestHandler):
        def do_GET(self):  # noqa: N802
            if self.path == "/health":
                self._reply(200, b'{"ok":true}')
            else:
                self._reply(404, b"")

        def do_POST(self):  # noqa: N802
            if self.path != "/speak":
                return self._reply(404, b"")
            try:
                length = int(self.headers.get("Content-Length", "0"))
                body = json.loads(self.rfile.read(min(length, 4096)) or b"{}")
                text = str(body.get("text", "")).strip()
                car = str(body.get("car", ""))
            except (ValueError, json.JSONDecodeError):
                return self._reply(400, b'{"error":"bad json"}')
            if not text or len(text) > MAX_SPEAK_CHARS:
                return self._reply(400, b'{"error":"text"}')
            log.info("← /speak car=%s text=%r", car or "-", text)
            speaker.say(text)
            self._reply(204, b"")

        def _reply(self, code: int, body: bytes):
            self.send_response(code)
            if body:
                self.send_header("Content-Type", "application/json")
                self.send_header("Content-Length", str(len(body)))
            self.end_headers()
            if body:
                self.wfile.write(body)

        def log_message(self, *_):
            pass

    return SpeakHandler


class Recorder:
    """마이크를 계속 열어 두고, 버튼을 누른 동안만 모은다(시작 지연을 없애려고)."""

    def __init__(self, device=None):
        import numpy as np
        import sounddevice as sd

        self.np = np
        self.frames: list = []
        self.recording = False
        self.lock = threading.Lock()
        self.stream = sd.InputStream(samplerate=SAMPLE_RATE, channels=1, dtype="float32",
                                     device=device, callback=self._callback)
        self.stream.start()

    def _callback(self, indata, _frames, _time, status):
        if status:
            log.debug("mic: %s", status)
        if self.recording:
            with self.lock:
                self.frames.append(indata[:, 0].copy())

    def start(self):
        with self.lock:
            self.frames = []
        self.recording = True

    def stop(self):
        self.recording = False
        with self.lock:
            frames, self.frames = self.frames, []
        if not frames:
            return self.np.zeros(0, dtype="float32")
        return self.np.concatenate(frames)


class Transcriber:
    def __init__(self, model_size: str):
        from faster_whisper import WhisperModel

        started = time.monotonic()
        log.info("음성 인식 모델 불러오는 중: %s (처음엔 내려받느라 오래 걸림)", model_size)
        self.model = WhisperModel(model_size, device="cpu", compute_type="int8")
        log.info("모델 준비 완료 (%d ms)", (time.monotonic() - started) * 1000)

    def __call__(self, audio) -> str:
        segments, _ = self.model.transcribe(audio, language="ko", beam_size=1,
                                            initial_prompt=WHISPER_PROMPT,
                                            condition_on_previous_text=False)
        return "".join(s.text for s in segments).strip()


class Bridge:
    def __init__(self, adb: Adb, recorder: Recorder | None, transcribe):
        self.adb, self.recorder, self.transcribe = adb, recorder, transcribe
        self.pressed = False
        self.pressed_at = 0.0
        self.work: queue.Queue = queue.Queue()
        threading.Thread(target=self._worker, daemon=True).start()

    def button(self, down: bool) -> None:
        if down == self.pressed:
            return  # 같은 신호가 두 번 오면 무시(버튼 떨림)
        self.pressed = down
        if down:
            self.pressed_at = time.monotonic()
            log.info("● 버튼 누름")
            if self.recorder:
                self.recorder.start()
            self.adb.broadcast("PTT", "state", "DOWN")
        else:
            held = time.monotonic() - self.pressed_at
            log.info("○ 버튼 뗌 (%.1f초)", held)
            audio = self.recorder.stop() if self.recorder else None
            self.work.put((audio, self.pressed_at))

    def _worker(self):
        while True:
            audio, pressed_at = self.work.get()
            started = time.monotonic()
            if audio is None or self.transcribe is None:
                raw = ""
            elif len(audio) < SAMPLE_RATE * MIN_AUDIO_SEC:
                log.info("너무 짧음 → 빈 결과")
                raw = ""
            else:
                try:
                    raw = self.transcribe(audio)
                except Exception as exc:  # 인식 실패해도 브리지는 계속 돈다
                    log.error("인식 실패: %s", exc)
                    raw = ""
            text = normalize_command(raw)
            log.info("인식 %r → 앱에 %r (%d ms)", raw, text, (time.monotonic() - started) * 1000)
            self.adb.broadcast("VOICE_RESULT", "text", text)
            total = time.monotonic() - pressed_at
            if total > APP_LISTEN_SEC:
                log.warning("누름부터 결과까지 %.1f초: 앱 듣기 시간(%d초)을 넘겨 앱이 결과를 버렸을 수 있음. "
                            "짧게 말하거나 --model base 시도", total, APP_LISTEN_SEC)


def find_serial_port() -> str | None:
    ports = sorted(glob.glob("/dev/cu.usbmodem*") + glob.glob("/dev/cu.usbserial*")
                   + glob.glob("/dev/cu.wchusbserial*"))
    return ports[0] if ports else None


def run_serial(bridge: Bridge, port: str, baud: int) -> None:
    import serial  # pyserial

    while True:
        try:
            with serial.Serial(port, baud, timeout=1) as conn:
                log.info("아두이노 연결: %s (%d bps)", port, baud)
                time.sleep(2)  # 아두이노는 포트를 열면 재시작한다
                conn.reset_input_buffer()
                while True:
                    line = conn.readline().decode("ascii", "ignore").strip()
                    if line == "BTN:DOWN":
                        bridge.button(True)
                    elif line == "BTN:UP":
                        bridge.button(False)
                    elif line:
                        log.debug("시리얼: %s", line)
        except (OSError, serial.SerialException) as exc:
            if bridge.pressed:
                bridge.button(False)
            log.error("아두이노 연결 끊김(%s). 2초 뒤 다시 연결", exc)
            time.sleep(2)


def run_keyboard(bridge: Bridge) -> None:
    print("\n키보드 모드: Enter = 버튼 누름, 말한 뒤 다시 Enter = 버튼 뗌. 끝내려면 Ctrl+C\n")
    for _ in sys.stdin:
        bridge.button(not bridge.pressed)


def check_environment(args) -> int:
    print("== 환경 점검 ==")
    print("say:", shutil.which("say") or "없음")
    print("한국어 목소리:", korean_voice() or "없음 → 시스템 설정 > 손쉬운 사용 > 읽기 및 말하기에서 한국어 목소리(Yuna 등) 추가")
    adb = shutil.which(args.adb) or args.adb
    try:
        out = subprocess.run([adb, "devices"], capture_output=True, text=True, timeout=5).stdout
        print("adb devices:\n" + out.strip())
    except OSError:
        print("adb: 실행 못 함 (", adb, ")")
    print("아두이노 포트:", find_serial_port() or "없음")
    for name in ("numpy", "sounddevice", "faster_whisper", "serial"):
        try:
            __import__(name)
            print(f"python {name}: 있음")
        except ImportError:
            print(f"python {name}: 없음 → pip install -r requirements.txt")
        except OSError as exc:
            print(f"python {name}: 설치됐지만 불러오기 실패 ({exc})")
    try:
        import sounddevice as sd

        print("기본 마이크:", sd.query_devices(kind="input")["name"])
    except Exception as exc:  # noqa: BLE001
        print("마이크 확인 못 함:", exc)
    return 0


def main(argv=None) -> int:
    p = argparse.ArgumentParser(description="Car Talk 맥 음성 브리지")
    p.add_argument("--serial-port", default="auto",
                   help="아두이노 포트. auto(기본) | /dev/cu.usbmodemXXXX | none")
    p.add_argument("--baud", type=int, default=115200)
    p.add_argument("--keyboard", action="store_true", help="아두이노 대신 키보드 Enter로 버튼 흉내")
    p.add_argument("--adb", default="adb")
    p.add_argument("--adb-serial", default="emulator-5554", help="버튼 신호를 보낼 차(기본 A차)")
    p.add_argument("--package", default=DEFAULT_PACKAGE)
    p.add_argument("--port", type=int, default=8090, help="/speak 받는 포트")
    p.add_argument("--speak-only", action="store_true", help="읽기만 (노트북 2, B차)")
    p.add_argument("--model", default="small", help="faster-whisper 모델: tiny|base|small|medium")
    p.add_argument("--mic", default=None, help="마이크 장치 이름 또는 번호 (기본 = 시스템 기본)")
    p.add_argument("--voice", default="auto", help="say 목소리. auto = 첫 한국어 목소리")
    p.add_argument("--rate", type=int, default=190, help="읽는 속도(분당 단어)")
    p.add_argument("--dry-run", action="store_true", help="adb 명령을 실행하지 않고 출력만")
    p.add_argument("--check", action="store_true", help="환경 점검만 하고 끝")
    p.add_argument("--say", metavar="TEXT", help="이 글자를 읽어 보고 끝")
    p.add_argument("-v", "--verbose", action="store_true")
    args = p.parse_args(argv)

    logging.basicConfig(level=logging.DEBUG if args.verbose else logging.INFO,
                        format="%(asctime)s.%(msecs)03d %(message)s", datefmt="%H:%M:%S")

    if args.check:
        return check_environment(args)

    voice = korean_voice() if args.voice == "auto" else args.voice
    if voice is None and shutil.which("say"):
        log.warning("한국어 목소리가 없어 기본 목소리로 읽음 (발음이 이상할 수 있음). --check 참고")
    speaker = Speaker(voice, args.rate)

    if args.say:
        speaker.say(args.say)
        speaker.jobs.join()
        return 0

    server = ThreadingHTTPServer(("127.0.0.1", args.port), make_speak_handler(speaker))
    threading.Thread(target=server.serve_forever, daemon=True).start()
    log.info("/speak 대기: http://127.0.0.1:%d/speak (에뮬레이터에서는 http://10.0.2.2:%d/speak), 목소리=%s",
             args.port, args.port, voice or "기본")

    if args.speak_only:
        log.info("읽기 전용 모드. 끝내려면 Ctrl+C")
        try:
            while True:
                time.sleep(3600)
        except KeyboardInterrupt:
            return 0

    adb = Adb(args.adb, args.adb_serial or None, args.package, args.dry_run)
    recorder = Recorder(args.mic)
    transcribe = Transcriber(args.model)
    bridge = Bridge(adb, recorder, transcribe)
    log.info("버튼 신호 → %s (%s)", args.adb_serial, args.package)

    try:
        if args.keyboard:
            run_keyboard(bridge)
        else:
            port = find_serial_port() if args.serial_port == "auto" else args.serial_port
            if not port or port == "none":
                log.error("아두이노 포트를 못 찾음. --serial-port 로 지정하거나 --keyboard 로 시험")
                return 2
            run_serial(bridge, port, args.baud)
    except KeyboardInterrupt:
        pass
    return 0


if __name__ == "__main__":
    sys.exit(main())
