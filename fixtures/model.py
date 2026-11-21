#!/usr/bin/env python3
"""A local Chat Completions peer. Inputs and replies contain only fixture lesson data."""
import argparse
import json
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer


def reply(request):
    tools = {item["function"]["name"] for item in request.get("tools", [])}
    messages = request["messages"]
    instructions = messages[0]["content"]
    if slow_request(request):
        return {"role": "assistant", "content": "Start with one half. Then multiply both parts by two."}
    if instructions.startswith("Extract propositions"):
        body = {"propositions": [
            {"id": "p1", "name": "Equivalent fractions", "statement": "Scaling both parts of a fraction preserves its value."},
            {"id": "p2", "name": "Halves and fourths", "statement": "One half equals two fourths."}],
            "chains": [{"premises": ["p1"], "conclusion": "p2"}]}
        return {"role": "assistant", "content": json.dumps(body)}
    if instructions.startswith("Check whether"):
        return {"role": "assistant", "content": json.dumps({"links": {}, "rejected": []})}
    previous = [call["function"]["name"] for msg in messages for call in msg.get("tool_calls", [])]

    def tool(name, arguments):
        return {"role": "assistant", "content": None, "tool_calls": [
            {"id": f"call_{name}", "type": "function", "function": {"name": name, "arguments": json.dumps(arguments)}}]}

    question = {"question": "Which fraction equals one half?", "options": [
        {"value": "a", "label": "2/4"}, {"value": "b", "label": "1/4"}],
        "explanation": "Multiply the numerator and denominator by two.", "correctAnswer": "a"}
    if "probe" in tools:
        if "probe" not in previous:
            return tool("probe", question)
        return tool("probe_done", {"assessment": {"strands": [
            {"strand": "Equivalent fractions", "assessment": "Can identify one half in fourths."}]}})
    if "plan_init" in tools:
        if "plan_init" not in previous:
            return tool("plan_init", {"mermaid": "graph TD\n halves[Halves] --> scale[Scaling both parts]"})
        if "quiz" not in previous:
            check = tool("quiz", {**question, "nodeId": "scale", "type": "check"})
            check["content"] = "Multiply the numerator and denominator by the same number to keep the fraction equal."
            return check
    if "problem" in tools:
        latest_practice_call = next((name for name in reversed(previous) if name in {"problem", "conclude"}), None)
        if latest_practice_call != "problem":
            return tool("problem", {"statement": "Write one half in fourths.", "modelSolution": "2/4", "mode": "solve"})
        return tool("conclude", {"outcome": "solved", "verdict": "Both parts scale by two."})
    return {"role": "assistant", "content": "Both parts doubled, so the fraction has the same value."}


def slow_request(request):
    last = request["messages"][-1]
    return last["role"] == "user" and "[demo:slow]" in (last.get("content") or "")


class Model(BaseHTTPRequestHandler):
    slow_seconds = 5

    def log_message(self, *args):
        pass

    def do_POST(self):
        if self.path != "/v1/chat/completions":
            self.send_error(404)
            return
        length = int(self.headers.get("Content-Length", 0))
        if not 0 < length <= 2_000_000:
            self.send_error(413)
            return
        try:
            request = json.loads(self.rfile.read(length))
            msg = reply(request)
            data = json.dumps({"choices": [{"message": msg, "finish_reason": "tool_calls" if msg.get("tool_calls") else "stop"}],
                               "usage": {"prompt_tokens": 32, "completion_tokens": 12, "total_tokens": 44}}).encode()
        except (KeyError, ValueError, TypeError):
            self.send_error(400)
            return
        if request.get("stream"):
            self.send_response(200)
            self.send_header("Content-Type", "text/event-stream")
            self.end_headers()
            finish = "tool_calls" if msg.get("tool_calls") else "stop"
            if msg.get("content"):
                text = msg["content"]
                split = len(text) // 2
                self.write_event({"choices": [{"delta": {"content": text[:split]}}]})
                if slow_request(request):
                    time.sleep(self.slow_seconds)
                try:
                    self.write_event({"choices": [{"delta": {"content": text[split:]}}]})
                except (BrokenPipeError, ConnectionResetError):
                    return
            for index, call in enumerate(msg.get("tool_calls", [])):
                self.write_event({"choices": [{"delta": {"tool_calls": [{"index": index, **call}]}}]})
            self.write_event({"choices": [{"delta": {}, "finish_reason": finish}],
                              "usage": {"prompt_tokens": 32, "completion_tokens": 12, "total_tokens": 44}})
            self.wfile.write(b"data: [DONE]\n\n")
            self.wfile.flush()
            return
        self.send_response(200)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(data)))
        self.end_headers()
        self.wfile.write(data)


    def write_event(self, event):
        self.wfile.write(b"data: " + json.dumps(event).encode() + b"\n\n")
        self.wfile.flush()


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("--port", type=int, default=18082)
    parser.add_argument("--slow-seconds", type=float, default=5, help="Delay a fixture reply marked [demo:slow]")
    args = parser.parse_args()
    Model.slow_seconds = args.slow_seconds
    ThreadingHTTPServer(("127.0.0.1", args.port), Model).serve_forever()
