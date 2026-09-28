package io.riverdb.engine.table;

import io.riverdb.base.error.StatusCode;
import io.riverdb.format.catalog.CatalogKeyspace;
import io.riverdb.format.page.LogicalHeadPageCodec;
import io.riverdb.format.page.PageCodec;
import java.nio.ByteBuffer;

/** Validates every reachable table root, row head, and owned directory page. */
final class IndexedHeadGraphValidation {
  private static final int MAXIMUM_DEPTH =
      LogicalHeadPageCodec.TABLE_ROOT_LEVEL
          + LogicalHeadPageCodec.MAXIMUM_ROW_ROOT_LEVEL + 2;

  private final IndexedPageSet pages;
  private final IndexedVersionState versions;
  private final IndexedVersionRecord version = new IndexedVersionRecord();
  private final PagedBooleanArray visited =
      new PagedBooleanArray(IndexedTableLimits.MAX_PAGES);
  private final long[][] edges =
      new long[MAXIMUM_DEPTH][LogicalHeadPageCodec.BRANCH_ENTRIES];
  private long versionRows;
  private long rowCount;
  private int nextPageId;

  IndexedHeadGraphValidation(IndexedPageSet pageSet, IndexedVersionState versionState) {
    pages = pageSet;
    versions = versionState;
  }

  StatusCode validate(int next, long rows) {
    visited.clear();
    versionRows = 0;
    rowCount = rows;
    nextPageId = next;
    StatusCode status = tablePage(
        IndexedTableKernel.HEAD_TABLE_ROOT_PAGE_ID, 0,
        LogicalHeadPageCodec.TABLE_ROOT_LEVEL, 0);
    if (!status.isOk()) return status;
    for (int pageId = IndexedTableKernel.HEAD_TABLE_ROOT_PAGE_ID;
        pageId < nextPageId; pageId++) {
      if (pages.payloadKind(pageId) == PageCodec.PAYLOAD_KIND_LOGICAL_HEAD
          && !visited.get(pageId)) return StatusCode.CORRUPTION;
    }
    return StatusCode.OK;
  }

  long versionRows() { return versionRows; }

  private StatusCode tablePage(int pageId, long base, int level, int depth) {
    StatusCode status = pinAndCheck(
        pageId, 0, level == 0 ? LogicalHeadPageCodec.TABLE_LEAF
            : LogicalHeadPageCodec.TABLE_BRANCH, base, level);
    if (!status.isOk()) return status;
    ByteBuffer page = pages.currentPayload(pageId);
    if (level == 0) {
      for (int slot = 0; status.isOk()
          && slot < LogicalHeadPageCodec.TABLE_LEAF_ENTRIES; slot++) {
        int root = LogicalHeadPageCodec.tableRootPageId(page, slot);
        int rootLevel = LogicalHeadPageCodec.tableRootLevel(page, slot);
        long tableId = base * LogicalHeadPageCodec.TABLE_LEAF_ENTRIES + slot + 1;
        if (root == 0 && rootLevel == 0) continue;
        if (!CatalogKeyspace.validObjectHead(tableId)
            || root <= 0 || rootLevel < 0
            || rootLevel > LogicalHeadPageCodec.MAXIMUM_ROW_ROOT_LEVEL) {
          status = StatusCode.CORRUPTION;
          break;
        }
        edges[depth][slot] = ((long) rootLevel << 32) | (root & 0xffffffffL);
      }
      pages.unpinCurrentPage(pageId);
      if (!status.isOk()) return status;
      for (int slot = 0; slot < LogicalHeadPageCodec.TABLE_LEAF_ENTRIES; slot++) {
        long entry = edges[depth][slot];
        edges[depth][slot] = 0;
        if (entry == 0) continue;
        long tableId = base * LogicalHeadPageCodec.TABLE_LEAF_ENTRIES + slot + 1;
        status = rowPage((int) entry, tableId, 0, (int) (entry >>> 32), depth + 1);
        if (!status.isOk()) return status;
      }
      return StatusCode.OK;
    }
    for (int slot = 0; slot < LogicalHeadPageCodec.BRANCH_ENTRIES; slot++) {
      int child = LogicalHeadPageCodec.branchChild(page, slot);
      if (child < 0) status = StatusCode.CORRUPTION;
      edges[depth][slot] = child;
    }
    pages.unpinCurrentPage(pageId);
    if (!status.isOk()) return status;
    long span = level == 1 ? 1 : LogicalHeadPageCodec.BRANCH_ENTRIES;
    for (int slot = 0; slot < LogicalHeadPageCodec.BRANCH_ENTRIES; slot++) {
      int child = (int) edges[depth][slot];
      edges[depth][slot] = 0;
      if (child == 0) continue;
      status = tablePage(child, base + slot * span, level - 1, depth + 1);
      if (!status.isOk()) return status;
    }
    return StatusCode.OK;
  }

  private StatusCode rowPage(
      int pageId, long owner, long base, int level, int depth) {
    StatusCode status = pinAndCheck(
        pageId, owner, level == 0 ? LogicalHeadPageCodec.ROW_LEAF
            : LogicalHeadPageCodec.ROW_BRANCH, base, level);
    if (!status.isOk()) return status;
    ByteBuffer page = pages.currentPayload(pageId);
    if (level == 0) {
      for (int slot = 0; status.isOk()
          && slot < LogicalHeadPageCodec.ROW_LEAF_ENTRIES; slot++) {
        long head = LogicalHeadPageCodec.rowHead(page, slot);
        if (head == 0) continue;
        if (head < 0 || base > (Long.MAX_VALUE - slot - 1)
            / LogicalHeadPageCodec.ROW_LEAF_ENTRIES) {
          status = StatusCode.CORRUPTION;
        } else status = chain(head);
      }
      pages.unpinCurrentPage(pageId);
      return status;
    }
    for (int slot = 0; slot < LogicalHeadPageCodec.BRANCH_ENTRIES; slot++) {
      int child = LogicalHeadPageCodec.branchChild(page, slot);
      if (child < 0) status = StatusCode.CORRUPTION;
      edges[depth][slot] = child;
    }
    pages.unpinCurrentPage(pageId);
    if (!status.isOk()) return status;
    long span = 1;
    for (int index = 1; index < level; index++) span *= LogicalHeadPageCodec.BRANCH_ENTRIES;
    for (int slot = 0; slot < LogicalHeadPageCodec.BRANCH_ENTRIES; slot++) {
      int child = (int) edges[depth][slot];
      edges[depth][slot] = 0;
      if (child == 0) continue;
      status = rowPage(child, owner, base + slot * span, level - 1, depth + 1);
      if (!status.isOk()) return status;
    }
    return StatusCode.OK;
  }

  private StatusCode chain(long rowId) {
    long newerCommit = 0;
    while (rowId > 0) {
      if (rowId > rowCount || versionRows >= rowCount) return StatusCode.CORRUPTION;
      StatusCode status = versions.lookup(rowId, rowCount, version);
      if (!status.isOk()) return status;
      if (version.commitSequence() <= 0
          || newerCommit > 0 && version.commitSequence() >= newerCommit
          || version.previousRowId() < 0 || version.previousRowId() >= rowId) {
        return StatusCode.CORRUPTION;
      }
      versionRows++;
      newerCommit = version.commitSequence();
      rowId = version.previousRowId();
    }
    return StatusCode.OK;
  }

  private StatusCode pinAndCheck(
      int pageId, long owner, int type, long base, int level) {
    if (pageId < IndexedTableKernel.HEAD_TABLE_ROOT_PAGE_ID
        || pageId >= nextPageId || visited.get(pageId)) return StatusCode.CORRUPTION;
    StatusCode status = visited.reserve(pageId);
    if (!status.isOk()) return status;
    status = pages.pinCurrentPage(pageId);
    if (!status.isOk()) return status;
    ByteBuffer page = pages.currentPayload(pageId);
    if (page == null || pages.payloadKind(pageId) != PageCodec.PAYLOAD_KIND_LOGICAL_HEAD
        || pages.ownerKeyId(pageId) != owner
        || !LogicalHeadPageCodec.validate(page).isOk()
        || LogicalHeadPageCodec.ownerObjectId(page) != owner
        || LogicalHeadPageCodec.type(page) != type
        || LogicalHeadPageCodec.base(page) != base
        || LogicalHeadPageCodec.level(page) != level) {
      pages.unpinCurrentPage(pageId);
      return StatusCode.CORRUPTION;
    }
    visited.set(pageId, true);
    return StatusCode.OK;
  }
}
