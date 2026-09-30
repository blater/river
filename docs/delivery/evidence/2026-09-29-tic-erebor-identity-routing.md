# Erebor identity routing and write-work audit

Date: 2026-09-29 UTC. Branch: `feature/tic-erebor-clustered-row-store`.
This audit follows [ADR 0015](../../adr/0015-clustered-relational-row-store.md)
and the owner's [Stock Level acceptance](../../tickets/tic-erebor.md#scheduling).

`RelationalDescriptorPrimaryAccess.fetchEncoded` seeks the clustered key
directly for a primary point read. `RelationalDescriptorScanAccess` starts
primary and full scans on that key. A secondary entry's stored physical
primary locator is passed to `fetchLocator`; neither path calls
`fetchByIdentity`. The latter is the named route for a caller holding only
stable logical identity and, on declared-primary tables, first scans the
identity-to-locator tree. For a hidden-primary table it derives the clustered
key directly, with no separate mapping tree. A locked secondary candidate
whose old primary locator has moved resolves the protected current successor
through identity. These are source-level route counts, not measured CPU costs.

| Declared-primary operation | Clustered mutation | Identity-map mutation | Secondary mutation |
| --- | ---: | ---: | ---: |
| Insert or delete | 1 insert or delete | 1 insert or delete | 1 per secondary key |
| Non-key update | 1 value replacement | 0 | 0 |
| Primary-key move with unchanged secondary keys | 1 delete plus 1 insert | 1 locator replacement | 1 locator replacement per secondary key |

The count comes from `RelationalDescriptorTupleDeltaPreparation` and
`RelationalDescriptorTupleDeltaStaging`. A hidden-primary table uses the
identity tree as its clustered row store, so its one primary mutation is not
an additional locator-map write. `RelationalDescriptorTupleDeltaPlanTest`
checks the declared-primary insert, delete, primary move and non-key update
counts. The warmed maximum-index plan test reports zero thread allocation
over ten plan updates. `RelationalClusteredReadAllocationTest` subsequently
measured zero allocated bytes over 10,000 actual primary fetches and each
64-row primary/secondary scan. Each committed row borrows the selected primary
leaf buffer; the test checks that buffer identity through all three paths.

`RelationalDescriptorRowPathTest.primaryAndSecondaryReadsDoNotConsultIdentityLocator`
removes a declared-primary row's identity mapping in a controlled test and
still reads it by primary and secondary access. An identity-only fetch fails
as expected. The same test updates a non-key value and again reads the row
through the direct primary route while the mapping remains absent.
`lockedSecondaryCandidateFollowsMovedPrimaryThroughIdentity` exercises the
current-row exception after a primary-key move. The [write-lock evidence](2026-09-29-tic-erebor-clustered-write-lock.md)
records the additional tuple-key lock needed for non-key updates; it does not
create a map mutation.

Each declared-primary mapping mutation carries its key and primary locator
through the existing logical tuple WAL stream and creates a new page
generation for every changed map page. Splits can add page copies and retained
history. The [final write-cost evidence](2026-09-29-tic-erebor-write-cost.md)
measures map-owned physical page copies, dirty pages, retained frames, logical
WAL payload and checkpoint writes through actual descriptor commits. It also
records replacement-lock acquisition cost and the named matched workload
controls/JFR diagnostics. The earlier short mixed run remains historical
correctness evidence. Independent review of the completed replacement remains
required before promotion; the accepted Stock Level decision remains separate.
