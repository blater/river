#!/usr/bin/env python3
"""Rank River production Java files by PMD design-debt signals."""

from __future__ import annotations

import argparse
import json
import math
import re
import shutil
import subprocess
import sys
from dataclasses import dataclass, field
from pathlib import Path
from typing import Any, Iterable


COGNITIVE_THRESHOLD = 15
CYCLOMATIC_THRESHOLD = 10
CYCLOMATIC_CLASS_THRESHOLD = 80
NPATH_THRESHOLD = 200
COUPLING_THRESHOLD = 20
GOD_ATFD_THRESHOLD = 5
GOD_TCC_THRESHOLD = 100.0 / 3.0


@dataclass
class FileMetrics:
  path: str
  cognitive_methods: list[int] = field(default_factory=list)
  cyclomatic_methods: list[int] = field(default_factory=list)
  cyclomatic_class: int = 0
  npath_methods: list[int] = field(default_factory=list)
  deep_ifs: int = 0
  coupling: int = 0
  god_wmc: int = 0
  god_atfd: int = 0
  god_tcc: float | None = None

  @property
  def is_god_class(self) -> bool:
    return self.god_tcc is not None

  @property
  def score(self) -> float:
    # A threshold-level violation contributes one severity unit. Log scaling
    # preserves ordering without allowing explosive NPath values to dominate.
    score = 0.0
    score += 10.0 * sum(
        severity(value, COGNITIVE_THRESHOLD)
        for value in self.cognitive_methods
    )
    score += 8.0 * sum(
        severity(value, NPATH_THRESHOLD) for value in self.npath_methods
    )
    score += 5.0 * sum(
        severity(value, CYCLOMATIC_THRESHOLD)
        for value in self.cyclomatic_methods
    )
    if self.cyclomatic_class:
      score += 5.0 * severity(
          self.cyclomatic_class, CYCLOMATIC_CLASS_THRESHOLD
      )
    score += 6.0 * self.deep_ifs
    if self.coupling:
      score += 10.0 * severity(self.coupling, COUPLING_THRESHOLD)
    if self.is_god_class:
      # WMC is already represented by cyclomatic complexity. The additional
      # score covers the god-class conjunction and its structural dimensions.
      score += 30.0
      score += 8.0 * severity(self.god_atfd, GOD_ATFD_THRESHOLD)
      score += 8.0 * inverse_severity(self.god_tcc, GOD_TCC_THRESHOLD)
    return score


def severity(value: float, threshold: float) -> float:
  return 1.0 + math.log2(max(value, threshold) / threshold)


def inverse_severity(value: float | None, threshold: float) -> float:
  if value is None:
    return 0.0
  return 1.0 + math.log2(threshold / max(min(value, threshold), 1.0))


def _number(description: str, pattern: str) -> int | None:
  match = re.search(pattern, description)
  return int(match.group(1)) if match else None


def collect_metrics(report: dict[str, Any]) -> list[FileMetrics]:
  results: list[FileMetrics] = []
  for file_report in report.get("files", []):
    metrics = FileMetrics(path=file_report["filename"])
    for violation in file_report.get("violations", []):
      rule = violation["rule"]
      description = violation["description"]
      if rule == "CognitiveComplexity":
        value = _number(description, r"cognitive complexity of (\d+)")
        if value is not None:
          metrics.cognitive_methods.append(value)
      elif rule == "CyclomaticComplexity":
        total = _number(
            description, r"total cyclomatic complexity of (\d+)"
        )
        value = _number(description, r"cyclomatic complexity of (\d+)"
        )
        if total is not None:
          metrics.cyclomatic_class = max(metrics.cyclomatic_class, total)
        elif value is not None:
          metrics.cyclomatic_methods.append(value)
      elif rule == "NPathComplexity":
        value = _number(description, r"NPath complexity of (\d+)")
        if value is not None:
          metrics.npath_methods.append(value)
      elif rule == "AvoidDeeplyNestedIfStmts":
        metrics.deep_ifs += 1
      elif rule == "CouplingBetweenObjects":
        value = _number(description, r"value of (\d+)")
        if value is not None:
          metrics.coupling = max(metrics.coupling, value)
      elif rule == "GodClass":
        match = re.search(
            r"WMC=(\d+), ATFD=(\d+), TCC=([\d.]+)%", description
        )
        if match:
          wmc, atfd, tcc = match.groups()
          metrics.god_wmc = int(wmc)
          metrics.god_atfd = int(atfd)
          metrics.god_tcc = float(tcc)
    results.append(metrics)
  return sorted(results, key=lambda item: (-item.score, item.path))


def discover_source_roots(repo: Path, include_bench: bool) -> list[Path]:
  roots = []
  for module in sorted(repo.glob("river-*")):
    if not module.is_dir() or module.name == "river-bench" and not include_bench:
      continue
    source_root = module / "src" / "main" / "java"
    if source_root.is_dir():
      roots.append(source_root)
  return roots


def detect_java_version(repo: Path) -> str:
  build_file = repo / "build.gradle.kts"
  if build_file.is_file():
    match = re.search(
        r"options\.release\.set\((\d+)\)",
        build_file.read_text(encoding="utf-8"),
    )
    if match:
      return f"java-{match.group(1)}"
  return "java-25"


def run_pmd(
    pmd: str,
    ruleset: Path,
    repo: Path,
    source_roots: Iterable[Path],
) -> dict[str, Any]:
  command = [
      pmd,
      "check",
      "--no-progress",
      "--no-fail-on-violation",
      "--no-cache",
      "--use-version",
      detect_java_version(repo),
      "--format",
      "json",
      "--rulesets",
      str(ruleset),
      "--relativize-paths-with",
      str(repo),
  ]
  for source_root in source_roots:
    command.extend(("--dir", str(source_root)))
  completed = subprocess.run(
      command, capture_output=True, check=False, text=True
  )
  if completed.returncode:
    detail = completed.stderr.strip() or completed.stdout.strip()
    raise RuntimeError(f"PMD failed with exit {completed.returncode}:\n{detail}")
  try:
    report = json.loads(completed.stdout)
  except json.JSONDecodeError as error:
    raise RuntimeError(f"PMD did not produce valid JSON: {error}") from error
  errors = report.get("processingErrors", []) + report.get(
      "configurationErrors", []
  )
  if errors:
    detail = "\n".join(str(error) for error in errors)
    raise RuntimeError(f"PMD reported analysis errors:\n{detail}")
  return report


def metric_max(values: list[int]) -> str:
  return "-" if not values else f"{max(values)}/{len(values)}"


def render_table(metrics: list[FileMetrics], limit: int) -> str:
  selected = metrics if limit == 0 else metrics[:limit]
  rows = []
  for rank, item in enumerate(selected, 1):
    god = "-"
    if item.is_god_class:
      god = f"{item.god_wmc}/{item.god_atfd}/{item.god_tcc:.1f}%"
    cyclomatic = "-"
    if item.cyclomatic_class or item.cyclomatic_methods:
      class_total = str(item.cyclomatic_class or "-")
      method_max = max(item.cyclomatic_methods, default=0)
      cyclomatic = f"{class_total}/{method_max or '-'}"
    rows.append([
        str(rank),
        f"{item.score:.1f}",
        god,
        metric_max(item.cognitive_methods),
        metric_max(item.npath_methods),
        cyclomatic,
        str(item.deep_ifs or "-"),
        str(item.coupling or "-"),
        item.path,
    ])

  headers = [
      "#", "score", "god W/A/T", "cog max/#", "npath max/#",
      "cyclo tot/max", "deep", "CBO", "file",
  ]
  widths = [len(header) for header in headers]
  for row in rows:
    for index, value in enumerate(row):
      widths[index] = max(widths[index], len(value))

  def format_row(row: list[str]) -> str:
    numeric = {0, 1, 2, 3, 4, 5, 6, 7}
    cells = []
    for index, value in enumerate(row):
      cells.append(value.rjust(widths[index]) if index in numeric
                   else value.ljust(widths[index]))
    return "|".join(cells).rstrip()

  lines = [format_row(headers), format_row(["-" * width for width in widths])]
  lines.extend(format_row(row) for row in rows)
  return "\n".join(lines)


def explain() -> str:
  return """\
The score is a refactoring triage heuristic, not a quality gate. Each PMD
violation starts at one unit at its configured threshold; severity above the
threshold is 1 + log2(measure / threshold). Weights are:

  cognitive method 10    NPath method 8       cyclomatic method 5
  cyclomatic class 5     deeply nested if 6   coupling 10
  PMD GodClass 30 + ATFD 8 + low-cohesion TCC 8

Class-level cyclomatic complexity already represents GodClass WMC, so WMC is
shown but is not scored twice. Columns ending in /n show maximum/count;
cyclo is class total/highest violating method; god is WMC/ATFD/TCC. A dash
means PMD found no threshold violation for that signal, not necessarily zero.
Rows are pipe-delimited, so fields can be selected with cut -d'|' -fN.
"""


def parse_args(argv: list[str]) -> argparse.Namespace:
  tool_dir = Path(__file__).resolve().parent
  parser = argparse.ArgumentParser(
      description="Rank River production Java design debt using PMD."
  )
  parser.add_argument(
      "--repo",
      type=Path,
      default=tool_dir.parent,
      help="River repository root (default: parent of this tool directory)",
  )
  parser.add_argument(
      "--limit",
      type=int,
      default=50,
      help="number of results to print; 0 prints all (default: 50)",
  )
  parser.add_argument(
      "--include-bench",
      action="store_true",
      help="include river-bench, which is excluded from production by default",
  )
  parser.add_argument(
      "--explain", action="store_true", help="explain scoring after the table"
  )
  args = parser.parse_args(argv)
  if args.limit < 0:
    parser.error("--limit must be non-negative")
  return args


def main(argv: list[str]) -> int:
  args = parse_args(argv)
  repo = args.repo.resolve()
  pmd = shutil.which("pmd")
  if not pmd:
    print("error: pmd is not on PATH", file=sys.stderr)
    return 2
  roots = discover_source_roots(repo, args.include_bench)
  if not roots:
    print(f"error: no production Java source roots found under {repo}",
          file=sys.stderr)
    return 2
  ruleset = Path(__file__).resolve().with_name("river-design-debt.xml")
  try:
    report = run_pmd(pmd, ruleset, repo, roots)
  except RuntimeError as error:
    print(f"error: {error}", file=sys.stderr)
    return 1
  metrics = collect_metrics(report)
  print(render_table(metrics, args.limit))
  if args.explain:
    print()
    print(explain())
  return 0


if __name__ == "__main__":
  raise SystemExit(main(sys.argv[1:]))
