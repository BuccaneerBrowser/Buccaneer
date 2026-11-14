"""Reassemble mechanical JS extractions for the unchanged protected source hashes.

No baseline source is stored here. Each descriptor identifies an exact helper wrapper,
call, and optional continue->return rewrite. The executable helper body is moved back
into its original position, then the old SHA256 is checked. Any edit to the old rules,
new wrappers, call sites or reconstruction descriptors fails that check.
"""
import json
from pathlib import Path

def restore_extractions(root: Path, path: str, text: str) -> str:
    registry = json.loads((root / 'tests/js-extractions.json').read_text())
    for move in reversed(registry.get(path, [])):
        prefix, suffix = move['prefix'], move['suffix']
        assert text.count(prefix) == 1, move['name'] + ': helper wrapper drift'
        start = text.index(prefix)
        end = text.index(suffix, start + len(prefix))
        body = text[start + len(prefix):end]
        helper = text[start:end+len(suffix)]
        text = text[:start] + text[end+len(suffix):]
        for before, after in reversed(move.get('rewrites', [])):
            assert body.count(after) == 1, move['name'] + ': rewritten statement drift'
            body = body.replace(after, before)
        call = move['call']
        assert text.count(call) == 1, move['name'] + ': call site drift'
        text = text.replace(call, body)
    return text
