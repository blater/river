package io.riverdb.engine.table;

import io.riverdb.base.error.StatusCode;

/** Canonically installs immutable page, row-version, and logical-floor commit generations. */
final class IndexedPreparedCommitInstaller {
  private final IndexedTableKernel kernel;
  private final IndexedPageSet pages;
  private final IndexedLogicalRowIdPublication logicalRowIds;

  IndexedPreparedCommitInstaller(
      IndexedTableKernel tableKernel, IndexedPageSet pageSet,
      IndexedLogicalRowIdRegistry logicalRowIdRegistry) {
    kernel = tableKernel;
    pages = pageSet;
    logicalRowIds = new IndexedLogicalRowIdPublication(logicalRowIdRegistry);
  }

  StatusCode install(
      IndexedRelationalMutationBuffer[] mutations,
      long[] sequences,
      long[] rowEnds,
      int[] heapPageEnds,
      int count,
      long groupBaseRow,
      long walStart,
      long walEnd,
      boolean logicalFloorsAlreadyPublished) {
    StatusCode status = validate(
        mutations, sequences, rowEnds, heapPageEnds, count, groupBaseRow, walStart, walEnd,
        logicalFloorsAlreadyPublished);
    if (status.isOk()) {
      status = pages.installPreparedPages(sequences, count, walStart, walEnd);
    }
    long previousRowEnd = groupBaseRow;
    for (int member = 0; status.isOk() && member < count; member++) {
      int firstVersion = (int) (previousRowEnd - groupBaseRow);
      int versionCount = (int) (rowEnds[member] - previousRowEnd);
      status = kernel.publishOperationRows(groupBaseRow, firstVersion, versionCount);
      if (status.isOk()) {
        status = kernel.recordOperationVersions(
            groupBaseRow, firstVersion, versionCount, sequences[member]);
      }
      if (status.isOk() && !logicalFloorsAlreadyPublished) {
        status = logicalRowIds.publish(mutations[member]);
      }
      previousRowEnd = rowEnds[member];
    }
    return status.isOk()
        ? kernel.publishOperationFrontier(
            groupBaseRow, rowEnds[count - 1], heapPageEnds[count - 1])
        : status;
  }

  private StatusCode validate(
      IndexedRelationalMutationBuffer[] mutations,
      long[] sequences,
      long[] rowEnds,
      int[] heapPageEnds,
      int count,
      long groupBaseRow,
      long walStart,
      long walEnd,
      boolean logicalFloorsAlreadyPublished) {
    if (mutations == null || sequences == null || rowEnds == null || heapPageEnds == null
        || count <= 0 || count > mutations.length || count > sequences.length
        || count > rowEnds.length || count > heapPageEnds.length
        || groupBaseRow < 0 || groupBaseRow != kernel.rowCount()
        || walStart < 0 || walEnd < walStart) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    long previousSequence = 0;
    long previousRows = groupBaseRow;
    int previousHeapPage = kernel.lastHeapPageId();
    for (int member = 0; member < count; member++) {
      int required = IndexedVersionOperation.required(mutations[member]);
      if (mutations[member] == null || !mutations[member].sealed()
          || sequences[member] <= previousSequence || rowEnds[member] < previousRows
          || rowEnds[member] - groupBaseRow > Integer.MAX_VALUE
          || required < 0 || rowEnds[member] - previousRows != required
          || heapPageEnds[member] < previousHeapPage) return StatusCode.INVARIANT_BROKEN;
      if (!logicalFloorsAlreadyPublished) {
        StatusCode floorStatus = logicalRowIds.validate(mutations[member]);
        if (!floorStatus.isOk()) return floorStatus;
      }
      previousSequence = sequences[member];
      previousRows = rowEnds[member];
      previousHeapPage = heapPageEnds[member];
    }
    return previousRows == kernel.operationRowCount()
            && previousRows - groupBaseRow == kernel.operationVersionCount()
            && previousHeapPage == kernel.operationLastHeapPageId()
        ? StatusCode.OK : StatusCode.INVARIANT_BROKEN;
  }
}
