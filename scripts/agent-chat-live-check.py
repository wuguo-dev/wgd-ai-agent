"""Development-only API acceptance. Credentials stay in process memory.

Run against an explicitly selected local backend via --base-url.
Creates two ordinary test students and retains their data for restart checks.
"""
import argparse
import http.cookiejar
import json
import secrets
import time
import urllib.error
import urllib.request
from pathlib import Path


class Client:
    def __init__(self, base):
        self.base = base
        self.opener = urllib.request.build_opener(
            urllib.request.HTTPCookieProcessor(http.cookiejar.CookieJar()))

    def request(self, method, path, body=None, csrf=True):
        headers = {"Content-Type": "application/json"}
        if method != "GET" and csrf:
            _, token = self.request("GET", "/auth/csrf")
            headers[token["headerName"]] = token["token"]
        data = None if body is None else json.dumps(body).encode()
        req = urllib.request.Request(self.base + path, data=data, headers=headers, method=method)
        try:
            response = self.opener.open(req, timeout=180)
        except urllib.error.HTTPError as error:
            response = error
        raw = response.read().decode("utf-8")
        if response.headers.get("Content-Type", "").startswith("application/json"):
            return response.status, json.loads(raw)
        return response.status, raw


def run(base, output):
    checks = []

    def expect(label, value, expected):
        assert value == expected, f"{label}: expected {expected}, got {value}"
        checks.append({"check": label, "result": "passed", "status": value})

    anonymous = Client(base)
    expect("anonymous history", anonymous.request("GET", "/agent/conversations")[0], 401)
    users = []
    for suffix in ["a", "b"]:
        client = Client(base)
        username = "agentqa_" + str(int(time.time() * 1000)) + suffix
        password = secrets.token_urlsafe(24)
        body = {"username": username, "password": password, "confirmPassword": password}
        expect("register " + suffix, client.request("POST", "/auth/register", body)[0], 201)
        expect("login " + suffix, client.request("POST", "/auth/login", body)[0], 200)
        users.append(client)
    first, second = users
    expect("create CSRF", first.request("POST", "/agent/conversations", {}, csrf=False)[0], 403)
    status, conversation = first.request("POST", "/agent/conversations", {"title": "QA 持久会话"})
    expect("create", status, 201)
    path = "/agent/conversations/" + conversation["id"]
    expect("own history", first.request("GET", path + "/messages")[0], 200)
    for method, route, body in [
        ("GET", path + "/messages", None),
        ("PATCH", path, {"title": "unauthorized"}),
        ("DELETE", path, None),
        ("POST", path + "/messages/stream", {"message": "越权问题"}),
        ("POST", path + "/cancel", {}),
    ]:
        expect("IDOR " + method + route.rsplit("/", 1)[-1], second.request(method, route, body)[0], 404)
    expect("rename", first.request("PATCH", path, {"title": "QA 重命名"})[0], 200)
    expect("unknown owner field", first.request("POST", "/agent/conversations", {"userId": 1})[0], 400)
    status, stream = first.request("POST", path + "/messages/stream", {"message": "Spring Boot 中 Controller、Service、Repository 应该如何分层？请根据知识库简短回答。"})
    expect("real SSE HTTP", status, 200)
    terminal = "done" if "event:done" in stream or "event: done" in stream else "error" if "event:error" in stream or "event: error" in stream else "missing"
    checks.append({"check": "real model/RAG terminal", "result": terminal})
    status, history = first.request("GET", path + "/messages")
    expect("persisted history", status, 200)
    assert len(history["items"]) >= 2
    checks.append({"check": "persisted message states", "states": [m["status"] for m in history["items"]]})
    result = {"baseUrl": base, "conversationId": conversation["id"], "checks": checks,
              "historyCount": len(history["items"]), "hasCitations": any(m.get("citations") for m in history["items"])}
    Path(output).write_text(json.dumps(result, ensure_ascii=False, indent=2), encoding="utf-8")
    print(json.dumps(result, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("--base-url", required=True)
    parser.add_argument("--output", default="target/agent-chat-live-result.json")
    args = parser.parse_args()
    run(args.base_url.rstrip("/"), args.output)
