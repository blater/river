package io.riverdb.engine.schema.catalog;

import io.riverdb.base.error.StatusCode;
import io.riverdb.base.error.StatusDetail;
import io.riverdb.engine.schema.KeyDescriptor;
import io.riverdb.engine.schema.TableDescriptor;
import io.riverdb.engine.table.IndexedScanCursor;
import io.riverdb.engine.table.IndexedScanResult;
import io.riverdb.engine.table.IndexedTransactionSession;
import io.riverdb.engine.table.IndexedTupleIndexState;
import io.riverdb.format.btree.TupleIndexRootRecordCodec;
import io.riverdb.format.catalog.CatalogKeyspace;

/** Cross-checks every published descriptor and tuple-root registry row at startup. */
final class CatalogIndexRootStartupValidation {
  private final CatalogDefinitionStore definitions;
  private final CatalogRetainedKeyDefinition retained;
  private final IndexedScanCursor cursor = new IndexedScanCursor();
  private final IndexedScanResult scanned = new IndexedScanResult();
  private final IndexedTupleIndexState state = new IndexedTupleIndexState();
  private final TableDescriptor.Result descriptor = new TableDescriptor.Result();
  private final StatusDetail detail = new StatusDetail(128);

  CatalogIndexRootStartupValidation(CatalogDefinitionStore store) {
    definitions = store;
    retained = new CatalogRetainedKeyDefinition(store);
  }

  StatusCode validateTable(
      IndexedTransactionSession session, TableDescriptor table) {
    for (int index = 0; index < CatalogTableKeys.physicalIndexCount(table); index++) {
      KeyDescriptor key = CatalogTableKeys.physicalIndexAt(table, index);
      StatusCode status = session.readTupleIndexState(key.keyId(), state);
      if (!status.isOk()) return status == StatusCode.CONFLICT
          ? StatusCode.CORRUPTION : status;
      if (!CatalogTupleIndexIdentity.published(
          state, key, table.tableId())) return StatusCode.CORRUPTION;
    }
    return StatusCode.OK;
  }

  StatusCode validateRegistry(IndexedTransactionSession session) {
    StatusCode status = cursor.reset();
    if (status.isOk()) status = session.beginScan(
        CatalogKeyspace.INDEX_ROOT_SPACE, Long.MIN_VALUE,
        CatalogKeyspace.SYSTEM_SPACE, Long.MIN_VALUE, cursor);
    while (status.isOk()) {
      status = session.nextScan(cursor, scanned);
      if (status == StatusCode.CONFLICT) {
        status = StatusCode.OK;
        break;
      }
      if (status.isOk()) status = validateRegistryRow(session);
    }
    if (cursor.isActive()) {
      StatusCode closed = session.closeScan(cursor);
      if (status.isOk()) status = closed;
    }
    return status;
  }

  private StatusCode validateRegistryRow(IndexedTransactionSession session) {
    if (scanned.keySpace() != CatalogKeyspace.INDEX_ROOT_SPACE
        || !CatalogKeyspace.validKeyId(scanned.key())) return StatusCode.CORRUPTION;
    StatusCode status = session.readTupleIndexState(scanned.key(), state);
    if (!status.isOk()) return status == StatusCode.CONFLICT ? StatusCode.CORRUPTION : status;
    if (state.state() == TupleIndexRootRecordCodec.STATE_ABSENT) return StatusCode.OK;
    if (state.state() != TupleIndexRootRecordCodec.STATE_READY) return StatusCode.CORRUPTION;
    descriptor.reset();
    status = definitions.readAnyHead(session, state.ownerObjectId());
    if (!status.isOk()) return status == StatusCode.CONFLICT ? StatusCode.CORRUPTION : status;
    KeyDescriptor key = null;
    if (definitions.headState() == io.riverdb.format.catalog.CatalogObjectHeadCodec.STATE_READY) {
      status = definitions.assembleCurrent(
          session, state.ownerObjectId(), descriptor, detail);
      if (status.isOk()) key = find(descriptor.value(), state.keyId());
    } else if (definitions.headState()
        != io.riverdb.format.catalog.CatalogObjectHeadCodec.STATE_TOMBSTONE) {
      return StatusCode.CORRUPTION;
    }
    if (!status.isOk()) return status == StatusCode.CONFLICT ? StatusCode.CORRUPTION : status;
    if (key == null) {
      descriptor.reset();
      status = retained.load(
          session, state.ownerObjectId(), state.keyId(), descriptor, detail);
      if (!status.isOk()) return status == StatusCode.CONFLICT
          ? StatusCode.CORRUPTION : status;
      key = find(descriptor.value(), state.keyId());
    }
    return CatalogTupleIndexIdentity.published(
        state, key, descriptor.value().tableId())
        ? StatusCode.OK : StatusCode.CORRUPTION;
  }

  private static KeyDescriptor find(TableDescriptor table, long keyId) {
    for (int index = 0; index < CatalogTableKeys.physicalIndexCount(table); index++) {
      KeyDescriptor key = CatalogTableKeys.physicalIndexAt(table, index);
      if (key.keyId() == keyId) return key;
    }
    return null;
  }
}
