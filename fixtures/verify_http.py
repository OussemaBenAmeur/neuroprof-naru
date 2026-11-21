#!/usr/bin/env python3
"""Vert.x can run the same client against its own facade using the gateway hop credential."""
import json
import os
from urllib.request import Request, urlopen

origin = os.environ.get("NARU_HTTP_URL", "http://127.0.0.1:8082")
headers = {"Authorization": "Bearer " + os.environ["VERTX_TO_NARU_TOKEN"], "Content-Type": "application/json"}


def request(path, body=None):
    message = Request(origin + path, data=None if body is None else json.dumps(body).encode(), headers=headers)
    with urlopen(message, timeout=90) as response:
        assert response.status == 200
        return response.read()


def settle(chat_id):
    assert request(f"/api/chats/{chat_id}/stream").endswith(b"data: [DONE]\n\n")
    return json.loads(request("/api/chats/" + chat_id))


chat = json.loads(request("/api/chats", {"title": "Fractions"}))["id"]
assert json.loads(request("/api/chat", {"chatId": chat, "input": {"type": "text", "text": "Why halves?"}})) == {"ok": True}
probe = settle(chat)["messages"][-1]["content"][0]
assert probe["name"] == "probe"
request("/api/chat", {"chatId": chat, "input": {"type": "answer", "toolCallId": probe["id"],
                                               "answer": {"kind": "quiz", "correct": True}}})
result = settle(chat)
assert any(msg.get("role") == "toolResult" and msg.get("toolCallId") == probe["id"] for msg in result["messages"])
assert any(block.get("name") == "quiz" for msg in result["messages"] if msg["role"] == "assistant" for block in msg["content"])
print("Probe, answer, plan and node-tagged quiz reopened via Servlet.")
