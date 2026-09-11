---
id: tic-70e3
status: closed
type: story
priority: 2
delivery: code
created: 2026-09-11
parent: tic-c8d1
---
# Simplify ProtocolResponsePayloadDecoder

File: `river-protocol/src/main/java/io/riverdb/protocol/ProtocolResponsePayloadDecoder.java`. Baseline slopwatch score: **134.340**.

## Approach

Use one shared inverse stable-status mapping for all response decoders. Keep
fixed-field read/reserve/complete order in the payload decoder; give common
response admission and query-open format decoding explicit owners. Query-open
metadata admission and first-row decoding belong together, matching the encoder.
Preserve every accepted/rejected frame, arithmetic bound, status, buffer offset
and publication order, with no per-frame allocation.

## Acceptance

The file and any extracted files score below 90 with the unchanged full-repository
`slopwatch -follow=false -include-tests -format json -limit 0 .` scan. Preserve
behavior, public contracts and failure cleanup. No suppressions, scorer changes,
new per-row allocation, or arbitrary file splitting. Luna/high codes; Sol/high
reviews; the lead reviews architectural effects across adjacent owners.
Run focused `river-protocol` checks and the epic's light performance check, record the
before/after score and result, then integrate this ticket independently.


## Validation

Implementation `b9b91574`, validation merge `fa36f0e4`, on
`ticket/tic-70e3-protocol-status`. Payload decoder falls from 134.340 to 0;
ProtocolStableStatus scores 0, ProtocolResponseAdmission 19.3197 and
ProtocolQueryOpenResponseDecoder 19.2961. Root and Sol reviewed exact status
mapping, validation order, arithmetic, flags, malformed-frame state, publication,
absolute buffer offsets and absence of per-frame allocation.

Thirty-three focused protocol tests passed; protocol checks, source-policy
validation and TPS installation passed with `--no-daemon`, zero failures/errors.
Log: `/private/tmp/river-tic-70e3-protocol-check.log`.

Light JVM sample all, four workers, one warehouse, seed 42, 20 retries,
five seconds warmup and ten seconds measured: **301.19 TPS**, p99 **67.371 ms**,
478 retries, zero failed/unknown outcomes, passed invariants and graceful inactive
cleanup. Consistent with adjacent external-harness variation; no speedup claim.
Version `tic-70e3-b9b91574-jvm`; artifact
`/Users/blater/src/ingres/river-harness/runs/river_harness_20260911_074648_2e118279`.


## M5 integration promotion

Accepted in the eleven-ticket integration at source `2c5dd377`, checkpoint
`perf-checkpoint-20260911-score-first62-m5`. Current-platform clean checks
passed (1,952 tests, zero failures/errors, 18 existing skips), all touched files
score below 90, and independent integration review found no blocking issue.
The adjacent master/candidate/candidate/master JVM series passed at
562.82/526.70/501.22/422.15 TPS with valid invariants, zero failed/unknown
outcomes and graceful cleanup. No repeated candidate regression was observed.
Full evidence is in `docs/performance-checkpoints.md`. Native compilation
remains blocked on unchanged master by the separately tracked `tic-ae17`;
this acceptance certifies the JVM path, not native execution.
