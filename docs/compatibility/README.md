# Compatibility evidence

Status: approved reference use; no direct legacy compatibility requirement

The project owner approved the workspace Ingres source and test trees as core
design and test references. River does not promise direct Ingres compatibility,
so P04 does not require a one-for-one classification of every historical test
before G0. River's accepted product and SQL profiles define supported behavior.

The [SQL conformance profile](sql-conformance-profile.md) is the authoritative
River-owned SQL contract. It records supported syntax and semantics, resource
limits, and the fixtures that prove each feature.

The [JDBC support matrix](jdbc-support-matrix.md) records the exact accepted
and rejected Java/JDBC conversions for that SQL type profile.

When a historical test materially informs a River requirement or test, add a
row to `legacy-support-matrix.csv` with one disposition:

- `required`: part of the agreed River v1 semantic profile;
- `adapt`: behavior is required but the test must be independently rewritten;
- `later`: valid behavior assigned to a named post-v1 milestone;
- `unsupported`: deliberately outside the product charter.

The matrix is a provenance and design-evidence index, not a compatibility
completeness promise. Missing provenance for used material is a failure; unused
legacy tests do not require placeholder rows.

The bounded U05 selection and its deliberate differences are recorded in the
[selected legacy adaptation report](u05-selected-legacy-report.md).
