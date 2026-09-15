#!/usr/bin/env python3
"""Black-box compliance checks for the public run.zip contract."""

from http.server import SimpleHTTPRequestHandler, ThreadingHTTPServer
import os
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile
import threading
import zipfile


PROJECT_DIR = Path(__file__).resolve().parent.parent
RUN = Path(os.environ.get("RUN_BIN", PROJECT_DIR / "build/native/nativeCompile/run")).expanduser().resolve()


class QuietHandler(SimpleHTTPRequestHandler):
    def log_message(self, format, *args):
        pass


def check(condition, message):
    if not condition:
        raise AssertionError(message)


def invoke(run, target, *arguments, cwd=None):
    result = subprocess.run(
        [str(run), "--progress=never", target, *arguments],
        cwd=cwd,
        capture_output=True,
        text=True,
    )
    if result.returncode != 0:
        raise AssertionError(
            f"run failed for {target!r} with exit code {result.returncode}:\n{result.stderr}"
        )
    return result.stdout


def main():
    check(RUN.is_file() and os.access(RUN, os.X_OK), f"run executable not found: {RUN}")

    with tempfile.TemporaryDirectory(prefix="hydraulic-run-compliance-") as temporary:
        root = Path(temporary)
        package = root / "package"
        web = root / "web"
        local = root / "local"
        (web / "demo").mkdir(parents=True)
        package.mkdir()
        local.mkdir()

        (package / "helper.js").write_text('export const marker = "compliance-import";\n')
        (package / "run.js").write_text(
            """import { marker } from \"./helper.js\";

export default {
  executable: \"sh\",
  arguments: [`${context.packageDir}/tool.sh`, marker, context.ver ?? \"none\", ...context.args],
};
"""
        )
        (package / "tool.sh").write_text("#!/bin/sh\nprintf '%s\\n' \"$@\"\n")
        (package / "tool.sh").chmod(0o755)
        shutil.copytree(package, local, dirs_exist_ok=True)

        with zipfile.ZipFile(web / "demo" / "run.zip", "w", zipfile.ZIP_DEFLATED) as archive:
            for path in sorted(package.iterdir()):
                archive.write(path, path.name)

        server = ThreadingHTTPServer(("127.0.0.1", 0), lambda *args, **kwargs: QuietHandler(*args, directory=str(web), **kwargs))
        thread = threading.Thread(target=server.serve_forever, daemon=True)
        thread.start()
        try:
            base = f"http://127.0.0.1:{server.server_port}"
            expected = "compliance-import\nnone\nfirst\ntwo words\n"

            check(invoke(RUN, f"{base}/demo", "first", "two words") == expected, "URL without trailing slash")
            check(invoke(RUN, f"{base}/demo/", "first", "two words") == expected, "URL with trailing slash")
            check(invoke(RUN, f"{base}/demo/run.zip/run.js", "first", "two words") == expected, "explicit run.js URL")
            versioned = "compliance-import\nv1\nfirst\ntwo words\n"
            check(invoke(RUN, f"{base}/demo@v1", "first", "two words") == versioned, "URL version suffix")

            check(
                invoke(RUN, "./local", "local", cwd=root) == "compliance-import\nnone\nlocal\n",
                "relative local directory",
            )
            check(
                invoke(RUN, str(local), "absolute") == "compliance-import\nnone\nabsolute\n",
                "absolute local directory",
            )

            missing = subprocess.run(
                [str(RUN), "--progress=never", "./missing"],
                cwd=root,
                capture_output=True,
                text=True,
            )
            check(missing.returncode != 0, "missing local directory unexpectedly succeeded")
            check("Local run directory does not exist" in missing.stderr, "missing path fell through to URL resolution")
        finally:
            server.shutdown()
            server.server_close()
            thread.join()

    print("run-compliance: PASS")


if __name__ == "__main__":
    try:
        main()
    except (AssertionError, OSError) as error:
        print(f"run-compliance: FAIL: {error}", file=sys.stderr)
        sys.exit(1)
