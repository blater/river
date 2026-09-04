package io.riverdb.engine.sql;

import io.riverdb.base.error.StatusCode;
import io.riverdb.base.error.StatusDetail;
import io.riverdb.engine.runtime.SqlRuntimeLease;
import io.riverdb.engine.runtime.RiverRuntimeConfig;
import io.riverdb.engine.runtime.materialized.SqlMaterializedScratchOwner;
import io.riverdb.engine.runtime.materialized.SqlMaterializedPagedByteStream;
import io.riverdb.engine.runtime.materialized.SqlMaterializedScratchFile;
import io.riverdb.engine.runtime.materialized.SqlMaterializedScratchFileKind;
import io.riverdb.engine.runtime.materialized.SqlMaterializedScratchStore;

/** One statement's lazy owner over the database-shared materialized runtime. */
final class SqlMaterializedStatement {
  private final SqlRuntimeLease lease;
  private final SqlMaterializedScratchOwner.Result retainedOwner =
      new SqlMaterializedScratchOwner.Result();
  private final SqlMaterializedScratchStore.Result retainedStore =
      new SqlMaterializedScratchStore.Result();
  private final SqlMaterializedScratchFile.Result retainedFile =
      new SqlMaterializedScratchFile.Result();
  private SqlMaterializedScratchOwner owner;
  private SqlMaterializedStatementStream streams;

  SqlMaterializedStatement(SqlRuntimeLease runtimeLease) { lease = runtimeLease; }

  StatusCode openStore(
      SqlMaterializedScratchOwner.Result ownerResult,
      SqlMaterializedScratchStore.Result storeResult,
      StatusDetail detail) {
    if (storeResult == null) return StatusCode.INVALID_EXTERNAL_INPUT;
    storeResult.reset();
    if (owner == null) {
      if (lease == null || ownerResult == null) return StatusCode.INVALID_EXTERNAL_INPUT;
      StatusCode status = lease.materializedScratch().openOwner(ownerResult, detail);
      if (!status.isOk()) return status;
      owner = ownerResult.owner();
      if (owner == null) return StatusCode.INVARIANT_BROKEN;
    }
    return owner.openStore(storeResult, detail);
  }

  StatusCode openStream(
      SqlMaterializedScratchFileKind kind,
      int fixedRecordBytes,
      int flags,
      SqlMaterializedPagedByteStream.Result target,
      StatusDetail detail) {
    if (target == null || kind == null || fixedRecordBytes < 0) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    target.reset();
    SqlMaterializedStatementStream slot = streams;
    while (slot != null) {
      if (slot.reusable(kind, fixedRecordBytes, flags)) {
        return slot.stream().resetForReuse(target, detail);
      }
      slot = slot.next();
    }
    SqlMaterializedStatementStream retained;
    try {
      retained = new SqlMaterializedStatementStream(streams);
    } catch (OutOfMemoryError failure) {
      return StatusCode.RESOURCE_EXHAUSTED;
    }
    StatusCode status = openStore(retainedOwner, retainedStore, detail);
    if (!status.isOk()) return status;
    status = retainedStore.store().open(kind, retainedFile, detail);
    if (!status.isOk()) return status;
    status = SqlMaterializedPagedByteStream.createNew(
        owner,
        retainedFile.file(),
        kind,
        lease.config().pageBytes(),
        fixedRecordBytes,
        flags,
        target,
        detail);
    if (!status.isOk()) return status;
    retained.attach(target.stream());
    streams = retained;
    return StatusCode.OK;
  }

  StatusCode reserveSortPages(
      SqlMaterializedSortReservation reservation, int runPages) {
    if (owner == null || lease == null || reservation == null || !reservation.available()
        || runPages < RiverRuntimeConfig.MINIMUM_SORT_RUN_PAGES
        || runPages > lease.config().sortRunPages()) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    if (runPages > Integer.MAX_VALUE - 2) return StatusCode.RESOURCE_EXHAUSTED;
    int total = runPages + 2;
    StatusCode status = owner.reservePages(total);
    if (status.isOk()) reservation.attach(this, total);
    return status;
  }

  StatusCode releaseSortPages(SqlMaterializedSortReservation reservation) {
    if (owner == null || reservation == null || !reservation.ownedBy(this)) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    StatusCode status = owner.releasePages(reservation.pages());
    if (status.isOk()) reservation.clear();
    return status;
  }

  int sortRunPages() { return lease == null ? 0 : lease.config().sortRunPages(); }

  int sortPageBytes() {
    return lease == null
        ? RiverRuntimeConfig.DEFAULT_MATERIALIZED_PAGE_BYTES : lease.config().pageBytes();
  }

  int effectiveSortRunPages() {
    return lease == null ? RiverRuntimeConfig.MINIMUM_SORT_RUN_PAGES
        : lease.config().sortRunPages();
  }

  StatusCode close(StatusDetail detail) {
    if (owner == null) {
      if (detail != null) detail.reset();
      return StatusCode.OK;
    }
    StatusCode status = owner.close(detail);
    if (status.isOk()) {
      owner = null;
      streams = null;
    }
    return status;
  }

  boolean active() { return owner != null; }
}
