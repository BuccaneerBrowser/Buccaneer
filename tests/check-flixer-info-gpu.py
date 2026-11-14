#!/usr/bin/env python3
"""Static source contract; on-TV computed-style positive control is separate."""
from pathlib import Path
import unittest
ROOT = Path(__file__).resolve().parents[1]
class InfoGpuContract(unittest.TestCase):
    def test_scoped_static_backdrop_mitigation(self):
        source = (ROOT / 'app/src/main/assets/flixer_image_guard.js').read_text()
        self.assertIn('function installInfoGpuStyle()', source)
        region = source.split('function installInfoGpuStyle()', 1)[1].split('var MAX =', 1)[0]
        self.assertIn("document.getElementById('__bkInfoGpuStyle')", region)
        self.assertIn('div.fixed.inset-0[class*="z-[100]"]', region)
        self.assertIn('-webkit-backdrop-filter:none!important;backdrop-filter:none!important;', region)
        for forbidden in ('setInterval', 'setTimeout', 'querySelectorAll', 'MutationObserver', 'requestAnimationFrame'):
            self.assertNotIn(forbidden, region)
        self.assertIn("document.head || document.documentElement", region)
        self.assertIn("{ once: true }", region)
if __name__ == '__main__':
    unittest.main()
