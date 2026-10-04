"""Offline checks only; no ERP reset or model calls."""
import tempfile
from pathlib import Path
import unittest
from contract_fix import require_unused,validate_focused

class ContractFixGateTests(unittest.TestCase):
    def test_existing_attempt_always_blocks_even_without_result(self):
        with tempfile.TemporaryDirectory() as d:
            path=Path(d)/'attempt';require_unused(path);path.mkdir()
            with self.assertRaises(RuntimeError):require_unused(path)
    def test_focused_gate_requires_all_five_successful_suites(self):
        with tempfile.TemporaryDirectory() as d:
            paths=[]
            for i,count in enumerate([12,7,13,13,22]):
                p=Path(d)/str(i);p.write_text(f'<testsuite tests="{count}" failures="0" errors="0" skipped="0"/>');paths.append(p)
            validate_focused(paths)
            with self.assertRaises(RuntimeError):validate_focused(paths[:-1])
            paths[0].write_text('<testsuite tests="12" failures="1" errors="0" skipped="0"/>')
            with self.assertRaises(RuntimeError):validate_focused(paths)

if __name__=='__main__':unittest.main()
