# Test streamlining delivery

Implements the accepted [test-value audit](2026-09-07-test-value-audit.md).
Luna high-effort agents authored disjoint test changes; the lead reviewed their
coverage across subsystem boundaries, ran serial validation and integrated them.
The lead authored the build change, with an independent Luna review.

| Slice | Ticket | Delivered merge |
| --- | --- | --- |
| Duplicate assertions and TLS fixtures | [tic-1f42](../../tickets/tic-1f42.md) | `eb92bc9` |
| Cheaper sort boundary fixture | [tic-5306](../../tickets/tic-5306.md) | `73df36c` |
| Lock, retention and root-repair tests | [tic-855a](../../tickets/tic-855a.md) | `da09f01` |
| Incremental compiler visibility proof | [tic-ff83](../../tickets/tic-ff83.md) | `06fc842` |

## Results

The expensive sort method took **338.838s unchanged**, **79.428s in the
candidate**, and **80.232s in combined validation**. It still merges 65 complete
runs plus one row and checks every output value, public key and cleanup.
These local measurements establish fixture savings, not a stable full-suite
speedup. The separate public SQL test beyond ordinal 65,535 remains.

The dependency compiler proof passed on its first run, was UP-TO-DATE on an
unchanged repeat, and reran after a temporary module build-file change.
Corresponding build times were 31s (with two other policy tasks), 7s and 18s.
The proof retains both successful direct compilation and rejected transitive
visibility. Compiler selection is recorded and pinned for the nested build.

Focused validation passed 306 client/protocol/SQL/benchmark tests, 147
transaction tests, 42 targeted engine tests and all five direct sort tests.
Allocation, recovery, security and public-boundary coverage remains; no test
framework, production test hook, database or benchmark behavior was added.

## Combined checkpoint

Tested source: `da09f01`, after all four merges. Command:

```sh
RIVER_GRADLE_HOME=/Users/blater/.gradle ./verify --continue
```

Every Gradle invocation used `--no-daemon`. Two uncached clean archive builds
passed in 44s and 31s and produced identical archives. The subsequent clean
check took 7m 2s: **384 suites / 1,806 tests, zero failures/errors, two existing
skips**. Engine tests executed freshly; 17 other module test tasks reused
passing cached results, including the affected suites run earlier in this
delivery. This is not a claim that all 1,806 tests executed freshly.

The overall check remains red on existing gates: surplus dependency verification
metadata, 113 hot-path bytecode findings, 135 source-policy findings, and 19
SQL-shape matches against a ceiling of 13. All files flagged by source policy
are byte-identical to the starting revision except two test files whose existing
Unicode escapes are unchanged. Production source and the failing policy rules
are unchanged. No policy was relaxed. Module graph, compiler visibility and
build-policy fixtures passed.

Raw logs, XML and checksums are retained under
`/private/tmp/river-test-streamline-evidence-20260907`. Ticket bodies contain the
focused commands and preserved coverage. No production TPS or slopmark change
is claimed: all source changes are tests, test fixtures or build logic.
