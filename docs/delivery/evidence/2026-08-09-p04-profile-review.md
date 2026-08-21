# P04 support-profile review

Date: 2026-08-09

Status: active after independent product and relational review

## Accepted direction

The project-owner decision legitimately removes direct Ingres compatibility
and an exhaustive one-row-per-historical-test inventory from P04. Legacy
material is an approved, provenance-linked design/test reference; River's own
product and SQL profiles remain authoritative.

## Blocking findings

- There is no small versioned River v1 support-profile baseline classifying
  current feature families as supported, deferred, or excluded, with explicit
  evolution rules. ADR 0001 currently delegates the matrix to Q01 even though
  Q01 hard-depends on P04.
- The high-level and implementation plans retain legacy-matrix and Phase 0
  conformance wording that contradicts the revised owner decision.
- U05 still hard-depends a legacy compatibility report without a selected-set
  minimum or a valid zero-adaptation exit rule, allowing either accidental
  compatibility expansion or a vacuous pass.
- The header-only legacy evidence matrix does not state whether no historical
  test has yet materially informed River behavior, or whether currently
  selected/adapted references are missing rows.

## Required correction

Publish a compact, versioned River-owned feature-family profile; reconcile ADR
0001 and both authoritative plans; redefine U05 as a selected-reference and
adaptation report with explicit exit rules; and record either the intentional
zero-selected baseline or provenance-linked rows for all currently used legacy
tests. Then obtain a new independent product/relational review.

P04 is therefore `active`, not passed. Exhaustive historical parity remains
out of scope, and no other deliverable or milestone is promoted by this review.
