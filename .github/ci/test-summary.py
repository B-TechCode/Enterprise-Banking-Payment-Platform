"""Summarise surefire results per module as a Markdown table.

Printed to stdout; the workflow appends it to the run's summary page and to the
log. Also lists the tests excluded from CI together with their reasons, so the
exclusions are visible on every run instead of being forgotten.

Reporting only: always exits 0. Pass/fail is decided by the Test step.
"""

import xml.etree.ElementTree as ET
from collections import defaultdict
from pathlib import Path

EXCLUDES = Path(".github/ci/surefire-excludes.txt")

totals = defaultdict(lambda: [0, 0, 0, 0])  # tests, failures, errors, skipped

for report in sorted(Path("backend").glob("*/target/surefire-reports/TEST-*.xml")):
    module = report.parts[1]
    suite = ET.parse(report).getroot()
    row = totals[module]
    row[0] += int(suite.get("tests", 0))
    row[1] += int(suite.get("failures", 0))
    row[2] += int(suite.get("errors", 0))
    row[3] += int(suite.get("skipped", 0))

print("## Test results\n")

if not totals:
    print("No surefire reports were found: the Test step did not get as far as "
          "running tests.\n")
else:
    print("| Module | Tests | Passed | Failed | Errors | Skipped | |")
    print("|---|---:|---:|---:|---:|---:|---|")
    grand = [0, 0, 0, 0]
    for module, (tests, failures, errors, skipped) in sorted(totals.items()):
        passed = tests - failures - errors - skipped
        mark = "❌" if failures or errors else "✅"
        print(f"| {module} | {tests} | {passed} | {failures} | {errors} | {skipped} | {mark} |")
        grand = [g + v for g, v in zip(grand, (tests, failures, errors, skipped))]
    tests, failures, errors, skipped = grand
    passed = tests - failures - errors - skipped
    print(f"| **Total** | **{tests}** | **{passed}** | **{failures}** "
          f"| **{errors}** | **{skipped}** | {'❌' if failures or errors else '✅'} |")
    print()

# The excludes file is blocks separated by blank lines. A block that contains
# patterns explains them in its comments; a block with only comments is the
# file's header.
if EXCLUDES.is_file():
    print("## Excluded from CI\n")
    blocks = EXCLUDES.read_text(encoding="utf-8").strip().split("\n\n")
    for block in blocks:
        lines = [line.strip() for line in block.splitlines() if line.strip()]
        reason = " ".join(line.lstrip("# ") for line in lines if line.startswith("#"))
        patterns = [line for line in lines if not line.startswith("#")]
        for pattern in patterns:
            name = pattern.rsplit("/", 1)[-1].removesuffix(".java")
            print(f"- `{name}`: {reason}")
    print()
