"""Server-boot smoke test: installs the built mod jar into a throwaway
dedicated NeoForge 1.21.1 server in a temp directory, boots it, and verifies
the mod loaded and the server reached "Done" before shutting it down
cleanly. Never touches any persistent or real server — everything happens
under tempfile.TemporaryDirectory() and is discarded on exit.

Usage: python3 scripts/smoke-server.py <path-to-mod-jar> [--neoforge-version X.Y.Z]

Exit non-zero (and raise) on: the server process exiting before reaching
"Done", the mod's own startup log line never appearing, or the server not
shutting down cleanly within the timeout. Any of those turns this check red.
"""
import os
from pathlib import Path
import queue
import shutil
import signal
import subprocess
import sys
import tempfile
import threading
import time
import urllib.request

DEFAULT_NEOFORGE_VERSION = "21.1.251"
MOD_LOADED_MARKER = "Dynamic Population scaffold loaded"
READY_MARKER = 'Done ('
HELP_MARKER = 'For help, type "help"'


def boot(root):
    process = subprocess.Popen(
        ["bash", "run.sh", "nogui"], cwd=root, stdin=subprocess.PIPE,
        stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True, start_new_session=True,
    )
    lines = queue.Queue()
    seen = []

    def read_output():
        for line in process.stdout:
            print(line, end="", flush=True)
            seen.append(line)
            lines.put(line)
        lines.put(None)

    threading.Thread(target=read_output, daemon=True).start()
    try:
        deadline = time.monotonic() + 300
        mod_loaded = False
        while True:
            line = lines.get(timeout=max(0.01, deadline - time.monotonic()))
            if line is None:
                raise RuntimeError(
                    "Server exited before reaching ready "
                    f"(mod_loaded_seen={mod_loaded}); see captured output above."
                )
            if MOD_LOADED_MARKER in line:
                mod_loaded = True
            if READY_MARKER in line and HELP_MARKER in line:
                if not mod_loaded:
                    raise RuntimeError(
                        "Server reached ready without ever logging the mod's "
                        f"startup marker ({MOD_LOADED_MARKER!r}) — mod did not load."
                    )
                break
        process.stdin.write("stop\n")
        process.stdin.flush()
        rc = process.wait(timeout=60)
        if rc != 0:
            raise RuntimeError(f"Server did not shut down cleanly (exit code {rc})")
        print("Verified mod load marker and clean server shutdown", flush=True)
    finally:
        if process.poll() is None:
            os.killpg(process.pid, signal.SIGTERM)
            try:
                process.wait(timeout=10)
            except subprocess.TimeoutExpired:
                os.killpg(process.pid, signal.SIGKILL)
                process.wait()


if __name__ == "__main__":
    jar = Path(sys.argv[1]).resolve()
    neoforge_version = DEFAULT_NEOFORGE_VERSION
    if "--neoforge-version" in sys.argv:
        neoforge_version = sys.argv[sys.argv.index("--neoforge-version") + 1]

    with tempfile.TemporaryDirectory(prefix="dynamicpopulation-smoke-") as directory:
        root = Path(directory)
        installer = root / "installer.jar"
        urllib.request.urlretrieve(
            f"https://maven.neoforged.net/releases/net/neoforged/neoforge/{neoforge_version}/neoforge-{neoforge_version}-installer.jar",
            installer,
        )
        subprocess.run(["java", "-jar", str(installer), "--installServer"], cwd=root, check=True, timeout=180)
        (root / "eula.txt").write_text("eula=true\n")
        (root / "user_jvm_args.txt").write_text("-Xmx2G\n")
        (root / "server.properties").write_text(
            "server-ip=127.0.0.1\nserver-port=0\nview-distance=2\nsimulation-distance=2\n"
            "level-type=minecraft:flat\ngenerate-structures=false\nspawn-protection=0\nonline-mode=false\n"
        )
        (root / "mods").mkdir(exist_ok=True)
        shutil.copy2(jar, root / "mods" / jar.name)
        print(f"Booting throwaway dedicated server in {root} with {jar.name} installed", flush=True)
        boot(root)
        print("Server-boot smoke check passed", flush=True)
