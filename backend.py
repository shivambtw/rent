"""Bridge to the compiled Java program (dist/rentease.jar).

One long-running Java process is started with `--api`. We send it one tab-separated
request line and read back one JSON line. Java owns all the data and business rules.

If the jar is missing or out of date and a JDK (`javac`) is available, it is rebuilt
automatically from src/RentEase.java.
"""
import json
import os
import shutil
import subprocess
import threading
from pathlib import Path

ROOT = Path(__file__).parent
JAR = ROOT / "dist" / "rentease.jar"
SRC = ROOT / "src" / "RentEase.java"
DATA_DIR = ROOT / "data"
REQUIRED_API = 2  # must match API_VERSION in RentEase.java


def find_tool(name: str) -> str:
    """Locate a Java tool (java, javac, jar) on PATH or under JAVA_HOME."""
    found = shutil.which(name)
    if found:
        return found
    home = os.environ.get("JAVA_HOME")
    if home:
        for candidate in (Path(home) / "bin" / f"{name}.exe", Path(home) / "bin" / name):
            if candidate.exists():
                return str(candidate)
    raise RuntimeError(f"`{name}` not found. Install JDK 17+ (https://adoptium.net) and make sure "
                       f"`{name}` is on PATH (check with `{name} -version`).")


def build_jar() -> None:
    """Compile src/RentEase.java into dist/rentease.jar (same steps as build.bat / build.sh)."""
    javac, jar = find_tool("javac"), find_tool("jar")
    build = ROOT / "build"
    shutil.rmtree(build, ignore_errors=True)
    build.mkdir()
    JAR.parent.mkdir(exist_ok=True)
    done = subprocess.run([javac, "-encoding", "UTF-8", "-d", str(build), str(SRC)],
                          capture_output=True, text=True)
    if done.returncode:
        raise RuntimeError("Java compile failed:\n" + (done.stderr or done.stdout))
    (build / "manifest.txt").write_text("Main-Class: RentEase\n")
    done = subprocess.run([jar, "cfm", str(JAR), str(build / "manifest.txt"), "-C", str(build), "."],
                          capture_output=True, text=True)
    if done.returncode:
        raise RuntimeError("Packaging the jar failed:\n" + (done.stderr or done.stdout))


class OutdatedJar(RuntimeError):
    pass


class JavaBackend:
    def __init__(self, jar: Path = JAR, data_dir: Path = DATA_DIR):
        try:
            self._start(jar, data_dir)
        except OutdatedJar as exc:
            try:
                build_jar()
            except RuntimeError as build_error:
                raise RuntimeError(f"{exc} It could not be rebuilt automatically: {build_error}") from None
            self._start(jar, data_dir)

    def _start(self, jar: Path, data_dir: Path):
        if not jar.exists():
            raise OutdatedJar(f"{jar} not found.")
        self.proc = subprocess.Popen(
            [find_tool("java"), "-jar", str(jar), "--api", "--data", str(data_dir)],
            stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.PIPE,
            text=True, encoding="utf-8", bufsize=1,
        )
        self.lock = threading.Lock()
        first = self.proc.stdout.readline()
        if not first:
            raise RuntimeError("Java backend failed to start: " + (self.proc.stderr.read() or "no output").strip())
        ready = json.loads(first).get("data")
        if ready != f"ready:{REQUIRED_API}":
            self.proc.kill()
            raise OutdatedJar(f"{jar} is out of date (found {ready!r}).")

    def call(self, *parts):
        clean = [str(p).replace("\t", " ").replace("\n", " ").replace("\r", " ") for p in parts]
        with self.lock:
            if self.proc.poll() is not None:
                raise RuntimeError("Java process has stopped. Restart the app.")
            self.proc.stdin.write("\t".join(clean) + "\n")
            self.proc.stdin.flush()
            line = self.proc.stdout.readline()
        if not line:
            raise RuntimeError("Java process closed unexpectedly.")
        reply = json.loads(line)
        if not reply["ok"]:
            raise ValueError(reply["error"])
        return reply["data"]
