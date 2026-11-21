#!/usr/bin/env python3
"""Presentation client for a local NaruProf process and the scripted model."""
import argparse
from contextlib import contextmanager
import io
import json
import os
from pathlib import Path
import shutil
import subprocess
import time
from urllib.error import HTTPError
from urllib.request import ProxyHandler, Request, build_opener
from uuid import uuid4
from zipfile import ZipFile

BASE = Path(__file__).resolve().parents[1]
SAMPLE_PROFILE = "f7933216-20db-49cb-90e0-3a9d5396966d"
MEDIA_JSON = "application/json"
COLORS = "background accent surface bold border italic text code textMuted error success warning"


def require(condition, message):
    if not condition:
        raise RuntimeError(message)


def show(label, value):
    print(f"  {label} {json.dumps(value, ensure_ascii=False)}", flush=True)


def call_from(chat, name):
    for msg in reversed(chat["messages"]):
        parts = msg.get("content")
        if msg.get("role") != "assistant" or msg.get("stopReason") in {"aborted", "error"} or not isinstance(parts, list):
            continue
        for index, part in enumerate(parts):
            if part.get("type") == "toolCall" and part.get("name") == name:
                return part, {
                    "messageId": msg["id"], "contentIndex": index,
                    "toolCallId": part["id"], "toolName": name,
                }
    raise RuntimeError(f"No saved {name} call in chat {chat['id']}")


def notes_multipart():
    boundary = uuid4().hex
    body = (
        f'--{boundary}\r\nContent-Disposition: form-data; name="files"; filename="fractions.md"\r\n'
        'Content-Type: text/markdown\r\n\r\n'
        '# Equivalent fractions\nOne half is two quarters.\r\n'
        f'--{boundary}\r\nContent-Disposition: form-data; name="paths"\r\n\r\n'
        f'course/fractions.md\r\n--{boundary}--\r\n'
    ).encode()
    return body, f"multipart/form-data; boundary={boundary}"


class Demo:
    def __init__(self, args):
        self.args = args
        self.origin = os.environ.get("NARU_HTTP_URL", "http://127.0.0.1:8082").rstrip("/")
        self.token = os.environ["VERTX_TO_NARU_TOKEN"]
        self.http = build_opener(ProxyHandler({}))

    def chapter(self, label):
        print(f"\n{label}", flush=True)
        if self.args.pause:
            input("Press Enter to continue… ")

    @contextmanager
    def request(self, method, path, payload=None, *, status=200, media=MEDIA_JSON, auth=True):
        data = json.dumps(payload).encode() if media == MEDIA_JSON and payload is not None else payload
        headers = {"Authorization": f"Bearer {self.token}"} if auth else {}
        if data is not None:
            headers["Content-Type"] = media
        req = Request(self.origin + path, data=data, method=method, headers=headers)
        try:
            response = self.http.open(req, timeout=90)
        except HTTPError as e:
            response = e
        with response as result:
            print(f"  {method:6} {path}   {result.status}", flush=True)
            if result.status != status:
                raise RuntimeError(f"Expected HTTP {status}. {result.read(512).decode(errors='replace')}")
            yield result

    def json(self, method, path, payload=None, **checks):
        with self.request(method, path, payload, **checks) as response:
            if checks.get("status") == 401:
                return None
            return json.load(response)

    def snapshot(self, label):
        if not self.args.grpc:
            return
        cmd = [
            "grpcurl", "-plaintext", "-max-time", "5",
            "-H", "authorization: Bearer " + os.environ["TWIN_TO_NARU_TOKEN"],
            "-import-path", str(BASE / "ws-grpc/src/main/proto"), "-proto", "learning_projection_v1.proto",
            "-d", json.dumps({"profileId": os.environ.get("NARUPROF_PROFILE_ID", SAMPLE_PROFILE)}),
            os.environ.get("NARU_GRPC_TARGET", "127.0.0.1:9092"), "naruprof.learning.v1.LearningProjection/GetSnapshot",
        ]
        result = subprocess.run(cmd, capture_output=True, text=True, timeout=8)
        require(result.returncode == 0, "GetSnapshot failed. Check the gRPC listener, credential and profile ID.")
        show(label, json.loads(result.stdout))

    def submit(self, chat, event, **checks):
        return self.json("POST", "/api/chat", {"chatId": chat, "input": event}, **checks)

    def chat(self, chat_id):
        return self.json("GET", f"/api/chats/{chat_id}")

    def settle(self, chat_id):
        with self.request("GET", f"/api/chats/{chat_id}/stream") as response:
            require(response.headers.get_content_type() == "text/event-stream", "Expected a turn SSE response")
            done = False
            for line in response:
                if line.strip() == b"data: [DONE]":
                    done = True
                    break
                if line.startswith(b"data: "):
                    frame = json.loads(line[6:])
                    show("SSE", {"type": frame.get("type"), "messageId": frame.get("message", {}).get("id")})
            require(done, "Turn stream ended without [DONE]")
        return self.chat(chat_id)

    def start(self):
        self.chapter("1. Check the private HTTP listener and the scripted provider selection")
        self.json("GET", "/internal/health/live", auth=False, status=401)
        self.json("GET", "/internal/health/live")
        self.json("GET", "/internal/health/ready")
        models = self.json("GET", "/api/models")
        specs = [
            slot.get("model") if isinstance(slot, dict) else None
            for slot in ((models.get("slots") or {}).get(tier) for tier in ("strong", "cheap"))
        ]
        require(all(isinstance(spec, dict) and spec.get("provider") == "learning" for spec in specs),
                "Use the isolated fixture profile. Strong and cheap must select the learning adapter.")
        show("Selected models", models["slots"])
        if self.args.extras:
            require(not self.json("GET", "/api/chats")["chats"],
                    "--extras requires a fresh scratch profile because it edits settings and graph fixtures.")
        self.snapshot("Initial projection")

    def teaching(self):
        self.chapter("2. Ask a question, reopen the probe, reject a stale answer and advance to teaching")
        chat_id = self.json("POST", "/api/chats", {"title": "Fractions demo"})["id"]
        self.snapshot("After creating one teaching session")
        self.submit(chat_id, {"type": "text", "text": "Why is one half equal to two quarters?"})
        probe, occurrence = call_from(self.settle(chat_id), "probe")
        show("Saved probe", {"id": probe["id"], "question": probe["arguments"]["question"]})
        answer = {"type": "answer", "toolCallId": probe["id"], "answer": {"kind": "quiz", "correct": True},
                  "occurrence": {**occurrence, "messageId": "stale"}}
        self.submit(chat_id, answer, status=400)
        answer["occurrence"] = occurrence
        self.submit(chat_id, answer)
        saved = self.settle(chat_id)
        result = next(m for m in saved["messages"] if m.get("role") == "toolResult" and m.get("toolCallId") == probe["id"])
        require(result["details"]["correct"], "Probe answer evidence was not stored")
        quiz, anchor = call_from(saved, "quiz")
        require(quiz["arguments"]["nodeId"] == "scale", "Expected the scale node in the teaching check")
        show("Same call ID in persisted answer", result["toolCallId"])
        show("Plan and check", {"tree": saved.get("tree"), "nodeId": quiz["arguments"]["nodeId"]})
        phase = next(row["phase"] for row in self.json("GET", "/api/chats")["chats"] if row["id"] == chat_id)
        require(phase == "teach", f"Expected teach phase, got {phase}")
        return chat_id, quiz, anchor

    def practice(self):
        self.chapter("3. Practice uses a separate session and a separate tool set")
        chat_id = self.json("POST", "/api/chats", {"title": "Fraction practice", "kind": "practice"})["id"]
        self.snapshot("After creating one practice session")
        self.submit(chat_id, {"type": "text", "text": "Give me a fraction exercise."})
        problem, occurrence = call_from(self.settle(chat_id), "problem")
        show("Saved practice problem", problem["arguments"]["statement"])
        self.submit(chat_id, {"type": "thinking", "problemCallId": problem["id"], "occurrence": occurrence,
                              "text": "Multiply the numerator and denominator by two."})
        conclude, _ = call_from(self.settle(chat_id), "conclude")
        show("Practice conclusion", conclude["arguments"])
        self.submit(chat_id, {"type": "next"})
        next_problem, _ = call_from(self.settle(chat_id), "problem")
        require(next_problem["id"] != problem["id"], "Next did not produce a new occurrence")
        return chat_id

    def graph(self):
        self.chapter("4. Import notes, show provenance and inspect the projection")
        payload, media = notes_multipart()
        imported = self.json("POST", "/api/graph/import", payload, media=media)
        require(imported["results"][0]["ok"], f"Import did not finish. {imported['results']}")
        show("Import result", imported["results"][0])
        graph = self.json("GET", "/api/graph")
        show("Graph", {"propositions": [n["name"] for n in graph["propositions"]], "chains": graph["chains"]})
        self.snapshot("After graph merge")
        return graph

    def side_chat(self, chat_id, occurrence):
        self.chapter("5. Ask for a hint in a side conversation")
        path = f"/api/chats/{chat_id}/side-chats"
        message_id = occurrence["messageId"]
        payload = {"requestId": "demo_" + uuid4().hex, "text": "Can you explain the idea without giving away the answer?",
                   "anchor": {"messageId": message_id, "partId": message_id + "-t0", "blockIndex": 0,
                              "blockText": "Multiply both parts by the same number."}}
        result = self.side_events(path, payload)
        thread = result["thread"]
        show("Final side answer", thread["messages"][-1]["text"])
        self.json("GET", path)
        thread_path = path + "/" + thread["id"]
        self.json("PATCH", thread_path, {"open": False})
        with self.request("GET", thread_path) as response:
            require(json.loads(response.readline())["thread"]["open"] is False, "Side thread close was not saved")
        return thread_path

    def side_events(self, path, payload):
        with self.request("POST", path, payload) as response:
            require(response.headers.get_content_type() == "application/x-ndjson", "Expected a side-chat NDJSON response")
            for line in response:
                event = json.loads(line)
                show("NDJSON", event["type"])
                if event["type"] == "done":
                    return event
            raise RuntimeError("Side reply ended without a done event")

    def export(self, chat_id):
        self.chapter("6. Read back the native record, Markdown and private ZIP")
        reloaded = self.chat(chat_id)
        show("Stored message count and usage turns", {"messages": len(reloaded["messages"]), "turns": len(reloaded.get("turns", []))})
        with self.request("GET", f"/api/chats/{chat_id}/markdown") as response:
            show("Markdown", response.read().decode().splitlines()[:5])
        with self.request("GET", "/internal/v1/profile/export") as response:
            with ZipFile(io.BytesIO(response.read())) as archive:
                show("Profile archive members", archive.namelist()[:12])
                require(f"chats/{chat_id}.json" in archive.namelist(), "Saved fixture chat missing from ZIP")

    def start_and_abort(self, chat_id, side_path):
        self.chapter("Extra. Watch a partial response, steer the turn and stop it")
        prefix = f"/api/chats/{chat_id}"
        self.submit(chat_id, {"type": "text", "text": "[demo:slow] Please explain."})
        with self.request("GET", prefix + "/stream") as stream:
            saw_partial = False
            for line in stream:
                if line.startswith(b"data: "):
                    payload = line[6:].strip()
                    if payload == b"[DONE]":
                        break
                    frame = json.loads(payload)
                    if frame["type"] == "message_update":
                        show("Partial assistant response", frame["message"]["content"])
                        saw_partial = True
                        break
            require(saw_partial, "Demo fixture did not leave a partial response in an active turn")
            self.submit(chat_id, {"type": "text", "text": "Issue a second turn during the first."}, status=409)
            self.json("POST", prefix + "/steer", {"input": {"type": "steer", "id": "steer_demo", "text": "Focus on scaling."}})
            self.json("POST", "/api/activity/abort", {"kind": "turn", "id": chat_id})
            require(b"data: [DONE]" in stream.read(), "Stopped turn should settle the stream")
        stopped = self.chat(chat_id)["messages"][-1]
        require(stopped["stopReason"] == "aborted", "Expected a persisted aborted turn")
        show("Persisted stop reason", stopped["stopReason"])
        thread = side_path.rsplit("/", 1)[-1]
        with self.request("POST", prefix + "/side-chats", {"threadId": thread, "requestId": "stop_" + uuid4().hex,
                                                         "text": "[demo:slow] A longer explanation, please."}) as stream:
            saw_partial = False
            for line in stream:
                event = json.loads(line)
                if event["type"] == "text" and event["text"]:
                    self.json("POST", side_path + "/stop")
                    saw_partial = True
                    break
            require(saw_partial, "Expected a live side reply")
            for line in stream:
                event = json.loads(line)
                if event["type"] == "done":
                    require(event["thread"]["messages"][-1].get("stopped"), "Expected a persisted stopped side reply")
                    return
        raise RuntimeError("Stopped side reply did not settle")

    def extras(self, chat_id, quiz, side_path, graph, practice_id):
        self.chapter("Extra. Exercise settings, safe refusals and graph/session edits")
        prefix = f"/api/chats/{chat_id}"
        self.json("PUT", prefix, {"title": "Fractions demo renamed"})
        self.json("PUT", prefix + "/settings", {"model": {"provider": "learning", "id": "fixture"}, "thinkingLevel": "off"})
        self.json("GET", "/api/settings")
        self.json("PUT", "/api/settings/models", {tier: {"model": {"provider": "learning", "id": "fixture"}}
                                                  for tier in ("strong", "cheap", "vision")})
        prompts = self.json("GET", "/api/settings/prompts")
        segment = prompts["teacher"][0]["path"]
        self.json("PUT", f"/api/settings/prompts/{segment}", {"text": "Demo override"})
        self.json("DELETE", f"/api/settings/prompts/{segment}")
        keys = self.json("GET", "/api/keys")
        if not any(k["id"] == "OPENAI_API_KEY" and k["present"] for k in keys["keys"]):
            key_path = "/api/keys/OPENAI_API_KEY"
            try:
                key = self.json("PUT", key_path, {"value": "fixture-only-not-a-real-key"})
                show("Masked key status", key["status"]["masked"])
            finally:
                self.json("DELETE", key_path)
        if not any(k["id"] == "DEEPGRAM_API_KEY" and k["present"] for k in keys["keys"]):
            self.json("GET", "/api/stt/token", status=503)
        self.json("GET", "/api/themes")
        theme = self.json("POST", "/api/themes", {"name": "Demo theme", "mode": "dark", "colors": {
            name: "#234567" for name in COLORS.split()}}, status=201)
        self.json("DELETE", "/api/themes/" + theme["theme"]["id"])
        self.json("GET", prefix + "/images/" + "0" * 64, status=404)
        self.json("GET", "/api/jobs?chatId=" + chat_id)
        self.json("GET", "/api/activity")
        self.json("GET", "/api/graph/status")
        self.json("POST", side_path + "/stop", status=409)
        self.json("POST", prefix + "/dismiss", {"input": {"type": "dismiss", "toolCallId": quiz["id"],
                                                          "answer": {"kind": "quiz", "dismissed": True}}})
        self.start_and_abort(chat_id, side_path)
        self.json("POST", "/api/graph/coverage/extract", {"chatId": chat_id}, status=202)
        for _ in range(30):
            if not self.json("GET", "/api/graph/status")["runs"]:
                break
            time.sleep(0.25)
        else:
            raise RuntimeError("Extraction still active after the demo polling window")
        self.json("GET", "/api/graph/render-settings")
        self.json("PUT", "/api/graph/render-settings", {"sizeUnits": "world", "nodeSize": 3})
        self.json("PUT", "/api/graph/positions", {graph["propositions"][0]["id"]: {"x": 120, "y": 80}})
        source = next(row for row in self.json("GET", "/api/graph/sources")["sources"] if row.get("filePath") == "course/fractions.md")
        self.json("POST", "/api/graph/sources", {"action": "rename-source", "id": source["id"], "name": "Fractions lecture notes"})
        self.json("DELETE", "/api/graph/nodes/" + graph["chains"][0]["id"])
        self.json("DELETE", "/api/graph/sources", {"sourceIds": [source["id"]], "folderIds": [], "deleteSessions": False})
        self.json("DELETE", "/api/chats/" + practice_id + "?mode=preserve-graph")
        self.snapshot("After the optional deletions")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--pause", action="store_true", help="Wait for Enter before each chapter")
    parser.add_argument("--grpc", action="store_true", help="Show snapshots with grpcurl")
    parser.add_argument("--extras", action="store_true", help="Exercise additional writes and a delayed fixture turn on a fresh profile")
    args = parser.parse_args()
    if args.grpc and shutil.which("grpcurl") is None:
        parser.error("--grpc requires grpcurl on PATH")
    try:
        demo = Demo(args)
        demo.start()
        chat, quiz, occurrence = demo.teaching()
        practice = demo.practice()
        graph = demo.graph()
        thread = demo.side_chat(chat, occurrence)
        demo.export(chat)
        if args.extras:
            demo.extras(chat, quiz, thread, graph, practice)
        show("Teaching session kept for reopening", chat)
    except (RuntimeError, KeyError, StopIteration, OSError, ValueError) as exc:
        parser.exit(1, f"Demo stopped. {exc}\n")


if __name__ == "__main__":
    main()
