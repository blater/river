#!/usr/bin/env python3

import unittest

from codehealth import collect_metrics, inverse_severity, render_table, severity


class CodeHealthTest(unittest.TestCase):
  def test_extracts_and_scores_all_signals(self):
    report = {
        "files": [{
            "filename": "river-engine/src/main/java/example/Bad.java",
            "violations": [
                violation(
                    "GodClass",
                    "Possible God Class (WMC=101, ATFD=32, TCC=20.000%)",
                ),
                violation(
                    "CognitiveComplexity",
                    "The method 'open()' has a cognitive complexity of 72, "
                    "current threshold is 15",
                ),
                violation(
                    "CyclomaticComplexity",
                    "The class 'Bad' has a total cyclomatic complexity of "
                    "101 (highest 58).",
                ),
                violation(
                    "CyclomaticComplexity",
                    "The method 'open()' has a cyclomatic complexity of 58.",
                ),
                violation(
                    "NPathComplexity",
                    "The method 'open()' has an NPath complexity of 691404, "
                    "current threshold is 200",
                ),
                violation(
                    "AvoidDeeplyNestedIfStmts",
                    "Deeply nested if..then statements are hard to read",
                ),
                violation(
                    "CouplingBetweenObjects",
                    "A value of 27 may denote a high amount of coupling "
                    "within the class (threshold: 20)",
                ),
            ],
        }]
    }

    result = collect_metrics(report)[0]

    self.assertTrue(result.is_god_class)
    self.assertEqual(101, result.god_wmc)
    self.assertEqual(32, result.god_atfd)
    self.assertEqual(20.0, result.god_tcc)
    self.assertEqual([72], result.cognitive_methods)
    self.assertEqual(101, result.cyclomatic_class)
    self.assertEqual([58], result.cyclomatic_methods)
    self.assertEqual([691404], result.npath_methods)
    self.assertEqual(1, result.deep_ifs)
    self.assertEqual(27, result.coupling)
    self.assertGreater(result.score, 100.0)

  def test_sorts_by_score_then_path(self):
    report = {
        "files": [
            file_with_cognitive("Low.java", 15),
            file_with_cognitive("High.java", 60),
            file_with_cognitive("AnotherHigh.java", 60),
        ]
    }

    result = collect_metrics(report)

    self.assertEqual(
        ["AnotherHigh.java", "High.java", "Low.java"],
        [item.path for item in result],
    )

  def test_severity_is_log_scaled(self):
    self.assertEqual(1.0, severity(15, 15))
    self.assertEqual(2.0, severity(30, 15))
    self.assertEqual(1.0, inverse_severity(100.0, 100.0 / 3.0))
    self.assertGreater(inverse_severity(10.0, 100.0 / 3.0), 2.0)

  def test_table_is_aligned_and_pipe_delimited(self):
    metrics = collect_metrics({
        "files": [
            file_with_cognitive("Short.java", 15),
            file_with_cognitive("a/considerably/longer/Path.java", 60),
        ]
    })

    lines = render_table(metrics, limit=0).splitlines()
    delimiter_positions = [
        [index for index, character in enumerate(line) if character == "|"]
        for line in lines
    ]

    self.assertTrue(all(line.count("|") == 8 for line in lines))
    self.assertTrue(all(
        positions == delimiter_positions[0]
        for positions in delimiter_positions[1:]
    ))
    self.assertEqual("file", lines[0].split("|")[8])


def violation(rule, description):
  return {"rule": rule, "description": description}


def file_with_cognitive(path, value):
  return {
      "filename": path,
      "violations": [violation(
          "CognitiveComplexity",
          f"The method 'run()' has a cognitive complexity of {value}, current threshold is 15",
      )],
  }


if __name__ == "__main__":
  unittest.main()
