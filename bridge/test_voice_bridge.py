"""브리지 단위 테스트. 마이크·인식·시리얼 라이브러리 없이 돈다.

실행: python3 -m unittest -v test_voice_bridge.py
"""
import json
import re
import threading
import unittest
import urllib.request
from http.server import ThreadingHTTPServer

import voice_bridge as vb

# fe/MessagingController 의 NUMBERED_COMMAND 와 같은 식 (앱이 받는 문장인지 확인용)
APP_NUMBERED = re.compile(r"^(10|[1-9])\s*번\s*(?:(?:메시지|메세지)\s*)?보내\s*줘[.!]?$")


def app_accepts_retry(text: str) -> bool:
    return re.sub(r"[\s.,!?~]", "", text) == "재전송해줘"


class NormalizeTest(unittest.TestCase):
    def check(self, raw, expected):
        self.assertEqual(vb.normalize_command(raw), expected, raw)

    def test_digits(self):
        self.check("3번 메시지 보내줘.", "3번 메시지 보내줘")
        self.check(" 1번 메세지 보내 줘 ", "1번 메시지 보내줘")
        self.check("10번 보내줘", "10번 메시지 보내줘")
        self.check("2번 전송해 줘", "2번 메시지 보내줘")

    def test_korean_numbers(self):
        self.check("삼번 메시지 보내줘", "3번 메시지 보내줘")
        self.check("세 번 메시지 보내 줘", "3번 메시지 보내줘")
        self.check("일번 메시지 보내줘", "1번 메시지 보내줘")
        self.check("이번 메시지 보내줘", "2번 메시지 보내줘")
        self.check("다섯번 메시지 보내줘", "5번 메시지 보내줘")
        self.check("여섯 번째 메시지 보내줘", "6번 메시지 보내줘")
        self.check("열번 메시지 보내줘", "10번 메시지 보내줘")
        self.check("한 번 메시지 보내줘", "1번 메시지 보내줘")

    def test_number_only(self):
        self.check("3번", "3번 메시지 보내줘")
        self.check("삼 번.", "3번 메시지 보내줘")

    def test_retry(self):
        self.check("재전송해 줘.", "재전송해줘")
        self.check("재전송해주세요", "재전송해줘")
        self.check("다시 보내줘", "재전송해줘")

    def test_passthrough(self):
        self.check("", "")
        self.check("   ", "")
        self.check("안녕하세요", "안녕하세요")
        self.check("11번 메시지 보내줘", "11번 메시지 보내줘")
        self.check("삼십번 메시지 보내줘", "삼십번 메시지 보내줘")
        self.check("오늘 날씨 어때", "오늘 날씨 어때")

    def test_outputs_are_accepted_by_app(self):
        for raw in ["삼번 메시지 보내줘", "10번 보내줘", "일곱 번 메세지 보내 줘"]:
            self.assertRegex(vb.normalize_command(raw), APP_NUMBERED)
        self.assertTrue(app_accepts_retry(vb.normalize_command("재전송 해줘")))


class AdbCommandTest(unittest.TestCase):
    def test_ptt(self):
        cmd = vb.adb_broadcast_args("adb", "emulator-5554", vb.DEFAULT_PACKAGE, "PTT", "state", "DOWN")
        self.assertEqual(cmd[:4], ["adb", "-s", "emulator-5554", "shell"])
        self.assertEqual(cmd[4], "am broadcast -a com.hyundaiproject.messaging.PTT "
                                 "-p com.hyundaiproject.messaging --es state DOWN")

    def test_voice_result_quotes_korean_and_spaces(self):
        cmd = vb.adb_broadcast_args("adb", None, vb.DEFAULT_PACKAGE, "VOICE_RESULT", "text",
                                    "3번 메시지 보내줘")
        self.assertEqual(cmd[:2], ["adb", "shell"])
        self.assertTrue(cmd[2].endswith("--es text '3번 메시지 보내줘'"), cmd[2])

    def test_voice_result_empty_and_quote(self):
        empty = vb.adb_broadcast_args("adb", None, vb.DEFAULT_PACKAGE, "VOICE_RESULT", "text", "")
        self.assertTrue(empty[2].endswith("--es text ''"))
        tricky = vb.adb_broadcast_args("adb", None, vb.DEFAULT_PACKAGE, "VOICE_RESULT", "text", "it's; rm")
        self.assertIn("'it'\"'\"'s; rm'", tricky[2])


class FakeSpeaker:
    def __init__(self):
        self.spoken = []

    def say(self, text):
        self.spoken.append(text)


class SpeakServerTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.speaker = FakeSpeaker()
        cls.server = ThreadingHTTPServer(("127.0.0.1", 0), vb.make_speak_handler(cls.speaker))
        cls.base = f"http://127.0.0.1:{cls.server.server_address[1]}"
        threading.Thread(target=cls.server.serve_forever, daemon=True).start()

    @classmethod
    def tearDownClass(cls):
        cls.server.shutdown()

    def post(self, body: bytes):
        req = urllib.request.Request(self.base + "/speak", data=body, method="POST",
                                     headers={"Content-Type": "application/json"})
        try:
            with urllib.request.urlopen(req) as res:
                return res.status
        except urllib.error.HTTPError as err:
            return err.code

    def test_speak_204(self):
        status = self.post(json.dumps({"car": "B", "text": "좌회전합니다"}).encode())
        self.assertEqual(status, 204)
        self.assertIn("좌회전합니다", self.speaker.spoken)

    def test_bad_requests(self):
        self.assertEqual(self.post(b"not json"), 400)
        self.assertEqual(self.post(json.dumps({"car": "B", "text": ""}).encode()), 400)
        self.assertEqual(self.post(json.dumps({"text": "가" * 300}).encode()), 400)

    def test_health(self):
        with urllib.request.urlopen(self.base + "/health") as res:
            self.assertEqual(res.status, 200)


class FakeAdb:
    def __init__(self):
        self.sent = []
        self.done = threading.Event()

    def broadcast(self, action, key, value):
        self.sent.append((action, key, value))
        if action == "VOICE_RESULT":
            self.done.set()


class FakeRecorder:
    def __init__(self, samples):
        self.samples = samples

    def start(self):
        pass

    def stop(self):
        return self.samples


class BridgeFlowTest(unittest.TestCase):
    def run_press(self, samples, heard):
        adb = FakeAdb()
        bridge = vb.Bridge(adb, FakeRecorder(samples), lambda _audio: heard)
        bridge.button(True)
        bridge.button(True)  # 떨림: 무시돼야 함
        bridge.button(False)
        self.assertTrue(adb.done.wait(2))
        return adb.sent

    def test_down_then_result_only(self):
        sent = self.run_press([0.0] * vb.SAMPLE_RATE, "삼번 메시지 보내줘")
        self.assertEqual(sent, [("PTT", "state", "DOWN"),
                                ("VOICE_RESULT", "text", "3번 메시지 보내줘")])

    def test_too_short_sends_empty(self):
        sent = self.run_press([0.0] * 100, "무시돼야 함")
        self.assertEqual(sent[-1], ("VOICE_RESULT", "text", ""))


if __name__ == "__main__":
    unittest.main()
