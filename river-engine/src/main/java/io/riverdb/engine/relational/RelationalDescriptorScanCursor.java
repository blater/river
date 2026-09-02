package io.riverdb.engine.relational;

import io.riverdb.base.error.StatusCode;
import io.riverdb.engine.schema.TableDescriptor;
import io.riverdb.engine.schema.cache.SchemaPin;
import io.riverdb.engine.table.IndexedScanCursor;
import io.riverdb.engine.table.IndexedScanResult;
import io.riverdb.engine.table.IndexedTupleScanCursor;
import io.riverdb.engine.table.IndexedTupleScanResult;

/** Caller-owned scan state for one pinned catalog-v2 table generation. */
public final class RelationalDescriptorScanCursor {
  private final IndexedScanCursor indexed = new IndexedScanCursor();
  private final IndexedScanResult row = new IndexedScanResult();
  private final IndexedTupleScanCursor tupleIndexed = new IndexedTupleScanCursor();
  private final IndexedTupleScanResult tupleRow = new IndexedTupleScanResult();
  private final RelationalDescriptorIndexCursor tupleBounds =
      new RelationalDescriptorIndexCursor();
  private final SchemaPin schema = new SchemaPin();
  private RelationalDescriptorTableAccess owner;
  private long tableId;
  private long rowLayoutId;
  private long generation;
  private boolean physicalOpen;
  private boolean tuplePhysical;
  private boolean emptyPhysical;
  private long logicalRowId;

  public boolean isActive() {
    return owner != null || physicalOpen || schema.isActive();
  }

  public StatusCode reset() {
    if (isActive()) return StatusCode.CONFLICT;
    tableId = 0;
    rowLayoutId = 0;
    generation = 0;
    logicalRowId = 0;
    row.reset();
    tupleRow.reset();
    tupleBounds.clear();
    return indexed.reset();
  }

  IndexedScanCursor indexed() {
    return indexed;
  }

  IndexedScanResult row() {
    return row;
  }

  IndexedTupleScanCursor tupleIndexed() { return tupleIndexed; }
  IndexedTupleScanResult tupleRow() { return tupleRow; }
  RelationalDescriptorIndexCursor tupleBounds() { return tupleBounds; }
  long logicalRowId() { return logicalRowId; }
  void logicalRowId(long value) { logicalRowId = value; }

  StatusCode claim(RelationalDescriptorTableAccess access, SchemaPin source) {
    if (owner != null || !physicalOpen || source == null || !source.isActive()) {
      return StatusCode.CONFLICT;
    }
    TableDescriptor table = source.descriptor();
    StatusCode status = source.transferTo(schema);
    if (!status.isOk()) return status;
    owner = access;
    tableId = table.tableId();
    rowLayoutId = table.rowLayoutId();
    generation = table.catalogGeneration();
    return StatusCode.OK;
  }

  void markPhysicalOpen() {
    physicalOpen = true;
    tuplePhysical = false;
    emptyPhysical = false;
  }

  void markTuplePhysicalOpen() {
    physicalOpen = true;
    tuplePhysical = true;
    emptyPhysical = false;
  }

  void markTupleEmptyOpen() {
    physicalOpen = true;
    tuplePhysical = true;
    emptyPhysical = true;
  }

  void markPhysicalClosed() {
    physicalOpen = false;
    tuplePhysical = false;
    emptyPhysical = false;
  }

  boolean isPhysicalOpen() {
    return physicalOpen;
  }

  boolean isTuplePhysical() { return tuplePhysical; }
  boolean isEmptyPhysical() { return emptyPhysical; }

  boolean matches(RelationalDescriptorTableAccess access) {
    return owner == access && schema.isActive()
        && schema.tableId() == tableId && schema.rowLayoutId() == rowLayoutId
        && schema.catalogGeneration() == generation;
  }

  /** Borrowed descriptor valid only while this cursor remains active. */
  public TableDescriptor descriptor() {
    return schema.descriptor();
  }

  boolean isOwnedBy(RelationalDescriptorTableAccess access) {
    return owner == access;
  }

  StatusCode complete() {
    StatusCode status = schema.release();
    if (!status.isOk()) return status;
    owner = null;
    tableId = 0;
    rowLayoutId = 0;
    generation = 0;
    logicalRowId = 0;
    tupleBounds.clear();
    return StatusCode.OK;
  }
}
