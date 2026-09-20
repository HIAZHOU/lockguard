"""Run the production Kotlin verification through the Windows build script."""
from pathlib import Path
import subprocess

root = Path(__file__).resolve().parents[1]
raise SystemExit(subprocess.call(["powershell", "-NoProfile", "-ExecutionPolicy", "Bypass", "-File", str(root / "tools" / "build_apk.ps1")], cwd=root))
