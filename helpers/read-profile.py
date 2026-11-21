#!/usr/bin/env python3
"""Run an original LearnHarness script against a current NaruProf profile snapshot."""
import os
from pathlib import Path
import subprocess
import sys
import tempfile
from urllib.request import Request, urlopen
from zipfile import ZipFile


def main(argv):
    if not argv or argv[0] not in {"graph", "sessions"}:
        raise SystemExit("usage: read-profile.py {graph|sessions} [options]")
    origin = os.environ.get("NARU_HTTP_URL", "http://127.0.0.1:8082").rstrip("/")
    auth = "Bearer " + os.environ["VERTX_TO_NARU_TOKEN"]
    request = Request(origin + "/internal/v1/profile/export", headers={"Authorization": auth})
    with tempfile.TemporaryDirectory(prefix="naruprof-read-") as scratch:
        archive = Path(scratch) / "profile.zip"
        with urlopen(request, timeout=60) as response, archive.open("wb") as dst:
            while chunk := response.read(64 * 1024):
                dst.write(chunk)
        profile = Path(scratch) / "profile"
        with ZipFile(archive) as data:
            for name in data.namelist():
                path = Path(name)
                if path.is_absolute() or ".." in path.parts:
                    raise ValueError("Unexpected archive path")
            data.extractall(profile)
        script = Path(__file__).resolve().parent / "learning-scripts" / (argv[0] + ".py")
        return subprocess.call([sys.executable, str(script), "--data-dir", str(profile), *argv[1:]])


if __name__ == "__main__":
    raise SystemExit(main(sys.argv[1:]))
