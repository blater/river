# U05 selected legacy adaptation report

Status: accepted 2026-08-15

River does not promise Ingres compatibility. This report records the bounded
historical test evidence selected for U05 and the independent River fixtures
that exercise the overlapping v1 semantics. Historical SQL text, expected-
output canon, data files, and fixture names were not copied.

## Provenance boundary

The approved reference is `legacy-ingres-tests` at `svn-r1000`, upstream
`http://code.ingres.com/ingres/main/src/tst`, under GPL-2.0 with notice evidence
in the external tree's `README.txt`. Its reproducible
`river-tree-sha256-v2` identity is
`51b7a093fe9c650bb54f22478a12035539257f0ad825250d1207ed536eec3d3c`.
The exact selected-file hashes are recorded in
[the support matrix](legacy-support-matrix.csv).

## Selected semantic oracles

- `be/datatypes/sep/dt00.sep` lines 55-60 and 80-93 informed the requirement
  that duplicate NULL
  values form one `DISTINCT` result. River's fixture uses an independently
  created `BIGINT` table with two NULL rows and one non-NULL row.
- `be/qryproc/sep/qp004.sep` lines 171-211 informed duplicate and unmatched outer-row
  retention. River uses native SQL-standard `LEFT JOIN`; an unmatched inner
  value is NULL rather than the legacy test's explicit numeric substitution.
- `be/qryproc/sep/qp012.sep` lines 107-120 and 202-210 separately demonstrate
  grouped `SUM` results and
  aggregate `HAVING` filtering. River's independent fixture deliberately
  combines those two semantic oracles: it verifies exact `SUM` values and
  retains only the group selected by the repeated aggregate in `HAVING`.

All three adaptations live in
`EmbeddedRiverLegacyCompatibilityTest`. They execute through the public
embedded SQL path and add no production fixture or compatibility adapter.

## Deliberate limits

The selected files contain additional syntax, types, utilities, and expected
diagnostics that were not selected. In particular this checkpoint does not
adopt legacy `COPY`, `UNION`, floating-point, character-padding, error-message,
or fixture-file behavior. Unselected historical tests imply no River support
or compatibility commitment; River's SQL conformance profile remains the
semantic authority.

## Acceptance evidence

U05 requires all of the following before promotion:

1. the provenance-v2 ledger and snapshot verifier accept the approved sibling
   source and test trees;
2. the support-matrix file hashes match the selected external files;
3. `EmbeddedRiverLegacyCompatibilityTest` passes; and
4. independent provenance and relational-semantics review confirms the
   independent-rewrite boundary and the absence of a broader compatibility
   promise.
