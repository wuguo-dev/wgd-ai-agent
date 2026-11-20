"""Hold generated credentials in memory across an externally controlled restart."""
import json
import secrets
import time
from pathlib import Path
from importlib.util import spec_from_file_location, module_from_spec

spec = spec_from_file_location("live", Path(__file__).with_name("agent-chat-live-check.py"))
live = module_from_spec(spec)
spec.loader.exec_module(live)
client = live.Client("http://127.0.0.1:18124/api")
Path("target/agent-restarted.signal").unlink(missing_ok=True)
username = "agentrestart_" + str(int(time.time() * 1000))
password = secrets.token_urlsafe(24)
assert client.request("POST", "/auth/register", {"username": username, "password": password, "confirmPassword": password})[0] == 201
assert client.request("POST", "/auth/login", {"username": username, "password": password})[0] == 200
status, conversation = client.request("POST", "/agent/conversations", {"title": "重启与多轮验证"})
assert status == 201
path = "/agent/conversations/" + conversation["id"]
token = "MEMORY" + secrets.token_hex(3)
questions = [
    "请简要说明Spring Boot Controller、Service、Repository分层，并在回答最后附上代号" + token,
    "继续简要说明Spring Boot分层，告诉我上一轮要求附上的代号是什么。",
]
for question in questions:
    assert client.request("POST", path + "/messages/stream", {"message": question})[0] == 200
status, before = client.request("GET", path + "/messages")
assert status == 200
answer = before["items"][-1]
result = {"conversationId": conversation["id"], "beforeCount": len(before["items"]),
          "multiTurnRemembered": token in answer["content"], "citations": len(answer["citations"]),
          "states": [item["status"] for item in before["items"]]}
Path("target/agent-restart-ready.json").write_text(json.dumps(result), encoding="utf-8")
print("Ready for restart", flush=True)
for _ in range(180):
    if Path("target/agent-restarted.signal").exists():
        break
    time.sleep(1)
else:
    raise TimeoutError("Restart signal not received")
assert client.request("GET", "/auth/me")[0] == 401
assert client.request("POST", "/auth/login", {"username": username, "password": password})[0] == 200
status, after = client.request("GET", path + "/messages")
assert status == 200 and before == after
result["afterCount"] = len(after["items"])
result["restartSessionInvalidated"] = True
result["historyIdentical"] = True
for legacy in ["/ai/knowledge/chat", "/ai/knowledge/chat/stream"]:
    result[legacy] = client.request("POST", legacy, {"chatId": "Student-" + conversation["id"], "message": "test"})[0]
Path("target/agent-restart-result.json").write_text(json.dumps(result, indent=2), encoding="utf-8")
print(json.dumps(result, indent=2))
