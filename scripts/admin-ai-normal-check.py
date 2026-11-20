"""Real non-streaming AI regression. Uses existing configured API key if present."""

import argparse, json, os, time, urllib.request
from pathlib import Path

p = argparse.ArgumentParser()
p.add_argument("--base-url", required=True)
args = p.parse_args()
headers = {"Content-Type": "application/json"}
if os.environ.get("KNOWLEDGE_BASE_API_KEY"):
    headers["X-API-Key"] = os.environ["KNOWLEDGE_BASE_API_KEY"]
request = urllib.request.Request(
    args.base_url.rstrip("/") + "/ai/knowledge/chat",
    data=json.dumps(
        {
            "chatId": "adminqa_" + str(int(time.time() * 1000)),
            "message": "Spring Boot Controller、Service、Repository如何分层？请根据知识库简短回答。",
        }
    ).encode(),
    headers=headers,
    method="POST",
)
with urllib.request.urlopen(request, timeout=180) as r:
    body = json.load(r)
    assert r.status == 200
    result = {
        "status": r.status,
        "responseKeys": sorted(body),
        "hasAnswer": bool(body.get("answer")),
        "hasCitations": bool(body.get("citations")),
    }
    assert result["hasAnswer"]
Path("target/admin-ai-normal-result.json").write_text(
    json.dumps(result, indent=2), encoding="utf8"
)
print(json.dumps(result))
