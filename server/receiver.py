#!/usr/bin/env python3
"""재실 시간 앱이 보내는 하루치 요약을 받아 파일로 저장하는 최소 예제 서버 (표준 라이브러리만 사용).

실행:  TOKEN=원하는토큰 python3 server/receiver.py [포트=8080]
저장:  data/<participantId>/<date>.json  (같은 날짜를 다시 받으면 덮어씀 = 재전송 안전)

주의: 앱은 https:// 만 보낼 수 있다. 실제 운영에서는 nginx/Caddy 같은 HTTPS 리버스 프록시 뒤에 두거나
      HTTPS 를 제공하는 호스팅에 올려야 한다. 이 파일은 참고용 예제다.
"""
import json
import os
import re
import sys
from http.server import BaseHTTPRequestHandler, HTTPServer
from pathlib import Path

TOKEN = os.environ.get("TOKEN", "")
DATA = Path(os.environ.get("DATA_DIR", "data"))
PID_RE = re.compile(r"^[A-Za-z0-9_-]{1,64}$")
DATE_RE = re.compile(r"^\d{4}-\d{2}-\d{2}$")
MAX_BODY = 256 * 1024


class Handler(BaseHTTPRequestHandler):
    def _reply(self, code, msg=""):
        body = msg.encode()
        self.send_response(code)
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def do_GET(self):
        self._reply(200, "ok") if self.path == "/health" else self._reply(404)

    def do_POST(self):
        if self.path != "/ingest":
            return self._reply(404)
        if TOKEN and self.headers.get("Authorization") != f"Bearer {TOKEN}":
            return self._reply(401, "bad token")
        n = int(self.headers.get("Content-Length") or 0)
        if n <= 0 or n > MAX_BODY:
            return self._reply(413 if n > MAX_BODY else 400)
        try:
            doc = json.loads(self.rfile.read(n))
            pid, date = doc["participantId"], doc["date"]
            assert doc["schema"] == 1 and isinstance(doc["totalMinutes"], int)
        except Exception:
            return self._reply(400, "bad payload")
        if not PID_RE.match(str(pid)) or not DATE_RE.match(str(date)):
            return self._reply(400, "bad id/date")  # 경로 조작 방지
        out = DATA / pid / f"{date}.json"
        out.parent.mkdir(parents=True, exist_ok=True)
        tmp = out.with_suffix(".tmp")
        tmp.write_text(json.dumps(doc, ensure_ascii=False, indent=1))
        tmp.replace(out)
        self._reply(200, "stored")

    def log_message(self, fmt, *args):  # 토큰/본문은 로그에 남기지 않음
        sys.stderr.write("%s %s\n" % (self.address_string(), fmt % args))


if __name__ == "__main__":
    port = int(sys.argv[1]) if len(sys.argv) > 1 else 8080
    print(f"listening on :{port}, token {'set' if TOKEN else 'NOT set (인증 없음)'}")
    HTTPServer(("0.0.0.0", port), Handler).serve_forever()
