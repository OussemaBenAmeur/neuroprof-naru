#!/usr/bin/env python3
"""NaruProf NARU test console.

Serves a browser UI that exercises every HTTP route, both gRPC methods, the
maintenance CLI, the fixtures and the helper scripts of this module.

  python3 test-console/server.py            # real Qwen (qwen3.5:4b) via Ollama
  python3 test-console/server.py --fixture  # deterministic scripted model
  # then open http://127.0.0.1:8090 and press Start

Stdlib only. The browser talks to this process; this process adds the
private bearer token and forwards to the engine, and it starts/stops the
scripted model, the engine JAR and the projection replay for you.
"""
import argparse
import json
import os
import shutil
import signal
import subprocess
import tempfile
import threading
import time
import urllib.error
import urllib.request
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from urllib.parse import urlparse, parse_qs

HERE = Path(__file__).resolve().parent
MODULE = HERE.parent
JAR = MODULE / "app" / "target" / "app-1.0.0.jar"


def default_java_home():
    for cand in ["/usr/lib64/jvm/java-25-openjdk-25", "/usr/lib/jvm/java-25-openjdk-amd64",
                 "/usr/lib/jvm/java-25-openjdk", os.environ.get("JAVA_HOME", "")]:
        if cand and (Path(cand) / "bin" / "java").exists():
            return cand
    return ""


CONFIG = {
    "httpUrl": "http://127.0.0.1:8082",
    "grpcTarget": "127.0.0.1:9092",
    "replayTarget": "127.0.0.1:9192",
    "profileId": "f7933216-20db-49cb-90e0-3a9d5396966d",
    "vertxToken": "fixture-vertx",
    "twinToken": "fixture-twin",
    "modelUrl": "http://127.0.0.1:18082/v1/chat/completions",
    "modelId": "fixture",
    "modelPort": "18082",
    "slowSeconds": "8",
    "dataDir": "",
    "javaHome": default_java_home(),
    "ignoreEnvKeys": True,
    # Real model: a local Ollama server running Qwen. "fixture" switches to fixtures/model.py.
    "modelMode": "ollama",
    "ollamaBase": "http://127.0.0.1:11434",
    "ollamaModel": "qwen3.5:4b",
    "ollamaContext": "16384",
}
FIXTURE_URL = "http://127.0.0.1:{port}/v1/chat/completions"


def apply_mode():
    """Keep modelUrl/modelId in step with the selected model mode."""
    c = CONFIG
    if c["modelMode"] == "ollama":
        c["modelUrl"] = c["ollamaBase"].rstrip("/") + "/v1/chat/completions"
        c["modelId"] = c["ollamaModel"]
    else:
        c["modelUrl"] = FIXTURE_URL.format(port=c["modelPort"])
        c["modelId"] = "fixture"


def engine_env(extra=None):
    env = dict(os.environ)
    c = CONFIG
    env.update({
        "NARUPROF_DATA_DIR": c["dataDir"],
        "LH_AUTH_FILE": str(Path(c["dataDir"]) / "auth.json"),
        "NARUPROF_PROFILE_ID": c["profileId"],
        "NARUPROF_MODEL_URL": c["modelUrl"],
        "NARUPROF_MODEL_ID": c["modelId"],
        "VERTX_TO_NARU_TOKEN": c["vertxToken"],
        "TWIN_TO_NARU_TOKEN": c["twinToken"],
        "NARU_HTTP_URL": c["httpUrl"],
        "NARU_GRPC_TARGET": c["grpcTarget"],
    })
    if c["ignoreEnvKeys"]:
        env["LH_IGNORE_ENV_KEYS"] = "1"
    if c["javaHome"]:
        env["JAVA_HOME"] = c["javaHome"]
        env["PATH"] = str(Path(c["javaHome"]) / "bin") + os.pathsep + env.get("PATH", "")
    if extra:
        env.update(extra)
    return env


def java():
    if CONFIG["javaHome"]:
        return str(Path(CONFIG["javaHome"]) / "bin" / "java")
    return "java"


class Proc:
    """A managed child process with a ring-buffered, line-numbered log."""

    def __init__(self, name):
        self.name = name
        self.lines = []
        self.offset = 0  # number of lines dropped from the front
        self.popen = None
        self.argv = []
        self.started = None
        self.exit = None
        self.lock = threading.Lock()

    def running(self):
        return self.popen is not None and self.popen.poll() is None

    def log(self, text):
        with self.lock:
            self.lines.append(text)
            if len(self.lines) > 4000:
                drop = len(self.lines) - 4000
                del self.lines[:drop]
                self.offset += drop

    def start(self, argv, env=None, cwd=MODULE, stdin_text=None):
        if self.running():
            raise RuntimeError(f"{self.name} is already running")
        self.exit = None
        self.argv = argv
        self.started = time.time()
        self.log(f"$ {' '.join(argv)}")
        self.popen = subprocess.Popen(
            argv, cwd=str(cwd), env=env or os.environ.copy(),
            stdin=subprocess.PIPE if stdin_text is not None else subprocess.DEVNULL,
            stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True, bufsize=1,
            start_new_session=True)
        if stdin_text is not None:
            self.popen.stdin.write(stdin_text)
            self.popen.stdin.close()
        threading.Thread(target=self._pump, daemon=True).start()

    def _pump(self):
        p = self.popen
        for line in p.stdout:
            self.log(line.rstrip("\n"))
        p.wait()
        self.exit = p.returncode
        self.log(f"[exited with code {p.returncode}]")

    def stop(self):
        if not self.running():
            return False
        try:
            os.killpg(self.popen.pid, signal.SIGTERM)
        except ProcessLookupError:
            pass
        for _ in range(50):
            if not self.running():
                return True
            time.sleep(0.1)
        try:
            os.killpg(self.popen.pid, signal.SIGKILL)
        except ProcessLookupError:
            pass
        try:
            self.popen.wait(timeout=10)  # reap, so an immediate restart is not refused
        except subprocess.TimeoutExpired:
            pass
        return True

    def view(self, since):
        with self.lock:
            start = max(0, since - self.offset)
            return {
                "name": self.name, "running": self.running(), "exit": self.exit,
                "argv": self.argv, "started": self.started,
                "next": self.offset + len(self.lines), "lines": self.lines[start:],
            }


PROCS = {n: Proc(n) for n in ("model", "engine", "replay", "build", "task")}


# ---------- Ollama (real Qwen model) ----------
def ollama_call(path, body=None, timeout=5):
    url = CONFIG["ollamaBase"].rstrip("/") + path
    data = json.dumps(body).encode() if body is not None else None
    req = urllib.request.Request(url, data=data, method="POST" if data else "GET",
                                 headers={"Content-Type": "application/json"})
    with urllib.request.urlopen(req, timeout=timeout) as r:
        return json.loads(r.read() or b"{}")


def ollama_status():
    try:
        tags = ollama_call("/api/tags", timeout=2)
    except Exception:
        return {"up": False, "installed": False, "loaded": False}
    name = CONFIG["ollamaModel"]
    names = {m.get("name") for m in tags.get("models", [])} | {m.get("model") for m in tags.get("models", [])}
    try:
        ps = ollama_call("/api/ps", timeout=2).get("models", [])
    except Exception:
        ps = []
    loaded = next((m for m in ps if name in (m.get("name"), m.get("model"))), None)
    return {"up": True, "installed": name in names, "loaded": loaded is not None,
            "vram": loaded.get("size_vram") if loaded else None,
            "context": loaded.get("context_length") if loaded else None}


MODEL_LOCK = threading.Lock()


def start_ollama_model():
    """Start `ollama serve` if nothing answers, pull the model if absent, then load it into memory."""
    c, log = CONFIG, PROCS["model"].log
    with MODEL_LOCK:
        st = ollama_status()
        if not st["up"]:
            exe = shutil.which("ollama")
            if not exe:
                raise RuntimeError("ollama is not on PATH. Install it from https://ollama.com or start with --fixture.")
            env = dict(os.environ, OLLAMA_CONTEXT_LENGTH=str(c["ollamaContext"]), OLLAMA_KEEP_ALIVE="-1")
            host = urlparse(c["ollamaBase"])
            env["OLLAMA_HOST"] = f"{host.hostname}:{host.port or 11434}"
            PROCS["model"].start([exe, "serve"], env=env)
            for _ in range(60):
                time.sleep(0.5)
                if not PROCS["model"].running():
                    raise RuntimeError("ollama serve exited; see the model log")
                st = ollama_status()
                if st["up"]:
                    break
            else:
                raise RuntimeError("ollama serve did not answer within 30 s")
        else:
            log(f"[console] Ollama already serving at {c['ollamaBase']}")
        if not st["installed"]:
            log(f"[console] pulling {c['ollamaModel']} (first run only)…")
            ollama_call("/api/pull", {"model": c["ollamaModel"], "stream": False}, timeout=3600)
        if not st["loaded"]:
            log(f"[console] loading {c['ollamaModel']} into memory (num_ctx={c['ollamaContext']})…")
            t0 = time.time()
            ollama_call("/api/generate", {"model": c["ollamaModel"], "prompt": "", "keep_alive": -1,
                                          "options": {"num_ctx": int(c["ollamaContext"])}}, timeout=600)
            log(f"[console] {c['ollamaModel']} loaded in {time.time() - t0:.1f} s")
        st = ollama_status()
        log(f"[console] model ready: {json.dumps(st)}")
        return st


def stop_ollama_model():
    """Unload the model; stop `ollama serve` only if this console started it."""
    try:
        ollama_call("/api/generate", {"model": CONFIG["ollamaModel"], "keep_alive": 0}, timeout=30)
        PROCS["model"].log(f"[console] unloaded {CONFIG['ollamaModel']}")
    except Exception:
        pass
    return PROCS["model"].stop()


def run_task(argv, env=None, cwd=MODULE):
    """One-shot command in the shared 'task' slot."""
    PROCS["task"].start(argv, env=env, cwd=cwd)


def tool_status():
    jh = CONFIG["javaHome"]
    javac = bool(jh) and (Path(jh) / "bin" / "javac").exists()
    return {
        "jar": JAR.exists(),
        "jarMtime": JAR.stat().st_mtime if JAR.exists() else None,
        "javaHome": jh,
        "javacInJavaHome": javac,
        "mvn": shutil.which("mvn") is not None,
        "grpcurl": shutil.which("grpcurl") is not None,
        "node": shutil.which("node") is not None,
        "python": shutil.which("python3") is not None,
        "pdftotext": shutil.which("pdftotext") is not None,
    }


def grpc_argv(method, payload, target, max_time):
    return ["grpcurl", "-plaintext", "-max-time", str(max_time),
            "-H", f"authorization: Bearer {CONFIG['twinToken']}",
            "-import-path", str(MODULE / "ws-grpc/src/main/proto"),
            "-proto", "learning_projection_v1.proto", "-d", payload, target,
            f"naruprof.learning.v1.LearningProjection/{method}"]


HOP_HEADERS = {"connection", "keep-alive", "transfer-encoding", "te", "trailer", "upgrade", "host", "content-length"}


class Handler(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"
    server_version = "NaruTestConsole/1.0"

    def log_message(self, fmt, *args):  # quiet
        pass

    # ---------- helpers ----------
    def send_json(self, obj, status=200):
        body = json.dumps(obj).encode()
        self.send_response(status)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(body)))
        self.send_header("Cache-Control", "no-store")
        self.end_headers()
        self.wfile.write(body)

    def body(self):
        n = int(self.headers.get("Content-Length") or 0)
        return self.rfile.read(n) if n else b""

    def jbody(self):
        raw = self.body()
        return json.loads(raw) if raw else {}

    # ---------- routing ----------
    def do_GET(self): self.route("GET")
    def do_POST(self): self.route("POST")
    def do_PUT(self): self.route("PUT")
    def do_PATCH(self): self.route("PATCH")
    def do_DELETE(self): self.route("DELETE")

    def route(self, method):
        url = urlparse(self.path)
        try:
            if url.path.startswith("/naru/"):
                return self.proxy(method, self.path[len("/naru"):])
            pages = {"/": "index.html", "/index.html": "index.html", "/advanced": "advanced.html", "/advanced.html": "advanced.html"}
            if url.path in pages:
                data = (HERE / pages[url.path]).read_bytes()
                self.send_response(200)
                self.send_header("Content-Type", "text/html; charset=utf-8")
                self.send_header("Content-Length", str(len(data)))
                self.send_header("Cache-Control", "no-store")
                self.end_headers()
                self.wfile.write(data)
                return
            if url.path.startswith("/ctl/"):
                return self.control(method, url.path[5:], parse_qs(url.query))
            self.send_json({"error": "not found"}, 404)
        except Exception as e:  # surface every failure to the UI
            try:
                self.send_json({"error": f"{type(e).__name__}: {e}"}, 500)
            except Exception:
                pass

    # ---------- proxy to the engine ----------
    def proxy(self, method, path):
        target = CONFIG["httpUrl"].rstrip("/") + path
        headers = {}
        for k in ("Content-Type", "Accept"):
            if self.headers.get(k):
                headers[k] = self.headers[k]
        mode = self.headers.get("X-Naru-Auth", "vertx")
        if mode == "vertx":
            headers["Authorization"] = "Bearer " + CONFIG["vertxToken"]
        elif mode == "custom":
            headers["Authorization"] = self.headers.get("X-Naru-Authorization", "")
        data = self.body() if method in ("POST", "PUT", "PATCH", "DELETE") else None
        if data is not None and not data and method == "DELETE":
            data = None
        req = urllib.request.Request(target, data=data, method=method, headers=headers)
        started = time.time()
        try:
            resp = urllib.request.urlopen(req, timeout=3600)
        except urllib.error.HTTPError as e:
            resp = e
        except (urllib.error.URLError, ConnectionError, OSError) as e:
            return self.send_json({"proxyError": f"Engine unreachable at {CONFIG['httpUrl']}: {getattr(e, 'reason', e)}"}, 502)
        self.send_response(resp.status if hasattr(resp, "status") else resp.code)
        for k, v in resp.headers.items():
            if k.lower() not in HOP_HEADERS:
                self.send_header(k, v)
        self.send_header("X-Upstream-Ms", str(int((time.time() - started) * 1000)))
        self.send_header("Transfer-Encoding", "chunked")
        self.end_headers()
        try:
            while True:
                chunk = resp.read1(65536) if hasattr(resp, "read1") else resp.read(65536)
                if not chunk:
                    break
                self.wfile.write(b"%x\r\n%s\r\n" % (len(chunk), chunk))
                self.wfile.flush()
            self.wfile.write(b"0\r\n\r\n")
            self.wfile.flush()
        except (BrokenPipeError, ConnectionResetError):
            pass  # browser closed the stream (e.g. user pressed Stop)
        finally:
            try:
                resp.close()
            except Exception:
                pass

    # ---------- control plane ----------
    def control(self, method, op, query):
        c = CONFIG
        if op == "state" and method == "GET":
            return self.send_json({"config": c, "tools": tool_status(),
                                   "ollama": ollama_status() if c["modelMode"] == "ollama" else None,
                                   "procs": {n: {"running": p.running(), "exit": p.exit, "argv": p.argv}
                                             for n, p in PROCS.items()}})
        if op == "config" and method == "PUT":
            body = self.jbody()
            for k, v in body.items():
                if k in c:
                    c[k] = v
            if any(k in body for k in ("modelMode", "ollamaBase", "ollamaModel")):
                apply_mode()
            return self.send_json({"config": c})
        if op.startswith("logs/"):
            name = op[5:]
            since = int(query.get("since", ["0"])[0])
            return self.send_json(PROCS[name].view(since))
        if op == "stop/model" and c["modelMode"] == "ollama":
            return self.send_json({"stopped": stop_ollama_model()})
        if op.startswith("stop/"):
            return self.send_json({"stopped": PROCS[op[5:]].stop()})
        if op == "new-profile":
            c["dataDir"] = tempfile.mkdtemp(prefix="naruprof-console-")
            return self.send_json({"dataDir": c["dataDir"]})
        if op == "start/model" and c["modelMode"] == "ollama":
            try:
                return self.send_json({"ok": True, "ollama": start_ollama_model()})
            except Exception as e:
                PROCS["model"].log(f"[console] {e}")
                return self.send_json({"error": f"Could not start {c['ollamaModel']}: {e}"}, 502)
        if op == "start/model":
            if PROCS["model"].running():
                return self.send_json({"ok": True})
            PROCS["model"].start(["python3", "fixtures/model.py", "--port", str(c["modelPort"]),
                                  "--slow-seconds", str(c["slowSeconds"])])
            return self.send_json({"ok": True})
        if op == "start/engine":
            if not JAR.exists():
                return self.send_json({"error": "app/target/app-1.0.0.jar is missing. Build first."}, 409)
            if PROCS["engine"].running():
                return self.send_json({"ok": True, "dataDir": c["dataDir"]})
            if not c["dataDir"]:
                c["dataDir"] = tempfile.mkdtemp(prefix="naruprof-console-")
            PROCS["engine"].start([java(), "-jar", str(JAR)], env=engine_env())
            return self.send_json({"ok": True, "dataDir": c["dataDir"]})
        if op == "start/replay":
            if not JAR.exists():
                return self.send_json({"error": "JAR missing. Build first."}, 409)
            PROCS["replay"].start([java(), "-jar", str(JAR), "projection-replay", "fixtures/projection.jsonl"],
                                  env=engine_env({"NARU_GRPC_TARGET": c["replayTarget"]}))
            return self.send_json({"ok": True})
        if op == "build":
            b = self.jbody()
            argv = ["mvn", "-B"] + (["-DskipTests"] if b.get("skipTests", True) else []) + [b.get("goal", "package")]
            if b.get("realDeps"):
                argv.insert(2, "-Preal-deps")
            if b.get("tests"):
                argv = ["mvn", "-B", "-pl", b.get("module", "contract-tests"), "-am", f"-Dtest={b['tests']}",
                        "-Dsurefire.failIfNoSpecifiedTests=false", "test"]
            PROCS["build"].start(argv, env=engine_env())
            return self.send_json({"ok": True})
        if op == "cli":
            b = self.jbody()
            args = b.get("args", [])
            if not JAR.exists():
                return self.send_json({"error": "JAR missing. Build first."}, 409)
            if args and args[0] == "profile" and len(args) > 1 and args[1] != "preview" and PROCS["engine"].running():
                return self.send_json({"error": "Stop the engine first: maintenance commands need the profile closed."}, 409)
            run_task([java(), "-jar", str(JAR), *args], env=engine_env())
            return self.send_json({"ok": True})
        if op == "script":
            b = self.jbody()
            name = b.get("name")
            scripts = {
                "demo": ["python3", "fixtures/demo.py"],
                "demo-grpc": ["python3", "fixtures/demo.py", "--grpc"],
                "demo-extras": ["python3", "fixtures/demo.py", "--grpc", "--extras"] if shutil.which("grpcurl") else None,
                "demo-extras-nogrpc": ["python3", "fixtures/demo.py", "--extras"],
                "verify-http": ["python3", "fixtures/verify_http.py"],
                "desktop-ipc": ["node", "--test", "fixtures/desktop-test.cjs"],
                "read-sessions": ["python3", "helpers/read-profile.py", "sessions", "--format", "json", "list"],
                "read-graph": ["python3", "helpers/read-profile.py", "graph", "--format", "json", "propositions"],
            }
            argv = scripts.get(name)
            if name == "learning-script":
                argv = ["python3", f"helpers/learning-scripts/{b['script']}.py", "--data-dir", b["dataDir"], *b.get("args", [])]
            if name == "read-profile":
                argv = ["python3", "helpers/read-profile.py", *b.get("args", [])]
            if not argv:
                return self.send_json({"error": f"Unknown or unavailable script {name}"}, 400)
            run_task(argv, env=engine_env())
            return self.send_json({"ok": True})
        if op == "grpc":
            b = self.jbody()
            if not shutil.which("grpcurl"):
                return self.send_json({"error": "grpcurl is not on PATH. Install it (e.g. `go install github.com/fullstorydev/grpcurl/cmd/grpcurl@latest` or your package manager) and retry."}, 409)
            target = c["replayTarget"] if b.get("replay") else c["grpcTarget"]
            payload = {"profileId": b.get("profileId") or c["profileId"]}
            if b["method"] == "Watch":
                payload["afterSequence"] = str(b.get("afterSequence", 0))
            argv = grpc_argv(b["method"], json.dumps(payload), target, b.get("maxTime", 8))
            if b.get("noAuth"):
                del argv[4:6]
            run_task(argv)
            return self.send_json({"ok": True})
        if op == "stop-all":
            for p in PROCS.values():
                p.stop()
            return self.send_json({"ok": True})
        if op == "list-learning-scripts":
            return self.send_json({"scripts": sorted(p.stem for p in (MODULE / "helpers/learning-scripts").glob("*.py"))})
        self.send_json({"error": f"unknown control {method} {op}"}, 404)


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--host", default="127.0.0.1")
    ap.add_argument("--port", type=int, default=8090)
    ap.add_argument("--ollama", metavar="MODEL", default=CONFIG["ollamaModel"],
                    help="Ollama model the console launches and the engine uses (default %(default)s)")
    ap.add_argument("--ollama-url", default=CONFIG["ollamaBase"],
                    help="Ollama base URL (default %(default)s); `ollama serve` is started here if nothing answers")
    ap.add_argument("--context", default=CONFIG["ollamaContext"],
                    help="context window for the model (default %(default)s; the tutor prompt alone is ~4k tokens)")
    ap.add_argument("--fixture", action="store_true",
                    help="use the deterministic scripted model (fixtures/model.py) instead of Ollama")
    args = ap.parse_args()
    CONFIG["ollamaModel"] = args.ollama
    CONFIG["ollamaBase"] = args.ollama_url.split("/v1/")[0].rstrip("/")
    CONFIG["ollamaContext"] = str(args.context)
    CONFIG["modelMode"] = "fixture" if args.fixture else "ollama"
    apply_mode()
    try:
        srv = ThreadingHTTPServer((args.host, args.port), Handler)
    except OSError as e:
        if e.errno != 98:
            raise
        raise SystemExit(f"Port {args.port} is already in use: another test console is probably running "
                         f"(open http://{args.host}:{args.port}, or stop it, or pass --port N).\n"
                         f"Find it with: ss -ltnp 'sport = :{args.port}'")
    srv.daemon_threads = True
    print(f"NaruProf NARU test console on http://{args.host}:{args.port} · model: "
          f"{'scripted fixture' if args.fixture else CONFIG['ollamaModel'] + ' via Ollama'}", flush=True)
    try:
        srv.serve_forever()
    except KeyboardInterrupt:
        pass
    finally:
        for p in PROCS.values():
            p.stop()


if __name__ == "__main__":
    main()
