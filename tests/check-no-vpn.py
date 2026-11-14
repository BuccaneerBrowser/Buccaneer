#!/usr/bin/env python3
"""VPN removal acceptance gate: no package service or activity consent hooks."""
from pathlib import Path
root = Path(__file__).resolve().parents[1]
files = list((root / "app/src/main").rglob("*.kt")) + [root / "app/src/main/AndroidManifest.xml"]
violations = [str(p.relative_to(root)) for p in files if "VpnService" in p.read_text() or "BIND_VPN_SERVICE" in p.read_text()]
assert not violations, "VPN implementation remains: " + str(violations)
print("PASS: no VpnService imports, class, manifest declaration or consent calls")
