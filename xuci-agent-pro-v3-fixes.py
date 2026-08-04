#!/usr/bin/env python3
from pathlib import Path
import sys

root = Path(sys.argv[1] if len(sys.argv) > 1 else "xuci-agent-pro")
main = root / "app/src/main/java/ai/xuci/agent/MainActivity.java"
text = main.read_text(encoding="utf-8")
old = "import android.app.AlertDialog;"
new = "import androidx.appcompat.app.AlertDialog;"
if old not in text:
    raise SystemExit(f"expected import not found in {main}")
main.write_text(text.replace(old, new, 1), encoding="utf-8")
print(f"patched {main}")
