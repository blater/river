package io.riverdb.engine.relational;

import io.riverdb.base.error.StatusCode;
import io.riverdb.storage.heap.HeapRowResult;
import java.nio.ByteBuffer;

/** Catalog row construction and removal for tables, sequences, and views. */
final class RelationalCatalogDdl {
  private final RelationalSchemaGate schemaGate;
  private final HeapRowResult catalogRow = new HeapRowResult();
  private final ByteBuffer scratch = ByteBuffer.allocateDirect(CatalogRecord.MAXIMUM_BYTES);
  private final ByteBuffer output = ByteBuffer.allocateDirect(CatalogRecord.MAXIMUM_BYTES);
  private final RelationalKey.LongKeyResult key = new RelationalKey.LongKeyResult();
  private final CatalogSequenceCodec.IntResult nextTableId =
      new CatalogSequenceCodec.IntResult();
  private final CatalogSequenceCodec.SequenceResult sequence =
      new CatalogSequenceCodec.SequenceResult();
  private final ViewDefinition view = new ViewDefinition();
  private final TableSchema twoColumnSchema = new TableSchema();

  RelationalCatalogDdl(RelationalSchemaGate gate) {
    schemaGate = gate;
  }

  StatusCode createTable(
      RelationalSession session,
      CharSequence name,
      CharSequence keyColumnName,
      CharSequence valueColumnName,
      TableDefinition result) {
    twoColumnSchema.reset();
    StatusCode status = twoColumnSchema.addBigint(keyColumnName);
    if (status.isOk()) {
      status = twoColumnSchema.addBigint(valueColumnName);
    }
    return status.isOk()
        ? createTable(session, name, twoColumnSchema, result) : status;
  }

  StatusCode createTable(
      RelationalSession session,
      CharSequence name,
      TableSchema schema,
      TableDefinition result) {
    StatusCode status = availableName(session, name);
    if (status.isOk()) {
      status = readNextTableId(session);
    }
    int tableId = nextTableId.value();
    if (status.isOk() && tableId > RelationalKey.MAXIMUM_TABLE_ID) {
      status = StatusCode.RESOURCE_EXHAUSTED;
    }
    if (status.isOk()) {
      CatalogSequenceCodec.encodeAllocation(output, tableId + 1);
      status = session.indexedSession().update(
          RelationalKey.CATALOG_SEQUENCE_KEY, output);
    }
    if (status.isOk()) {
      CatalogRecord.encodeTable(
          output,
          tableId,
          0,
          TableDefinition.INDEX_NONE,
          -1,
          name,
          schema);
      status = session.indexedSession().insert(key.key(), output);
    }
    if (status.isOk() && schema.hasIdentity()) {
      CatalogSequenceCodec.encodeIdentity(output, tableId, 1, false);
      status = session.indexedSession().insert(
          RelationalKey.identitySequenceKey(tableId), output);
    }
    if (status.isOk()) {
      result.set(
          schemaGate, tableId, 0, TableDefinition.INDEX_NONE, -1, schema);
      status = schemaGate.bindOwnedDefinition(session, result);
    }
    return status;
  }

  StatusCode createSequence(
      RelationalSession session,
      CharSequence name,
      long start,
      long increment) {
    StatusCode status = availableName(session, name);
    if (status.isOk()) {
      CatalogSequenceCodec.encodeUser(output, name, start, increment, false);
      status = session.indexedSession().insert(key.key(), output);
    }
    return status;
  }

  StatusCode createView(
      RelationalSession session,
      CharSequence name,
      CharSequence query,
      int baseTableId) {
    StatusCode status = availableName(session, name);
    if (status.isOk()) {
      CatalogViewCodec.encode(output, name, query, baseTableId);
      status = session.indexedSession().insert(key.key(), output);
    }
    return status;
  }

  StatusCode dropView(RelationalSession session, CharSequence name) {
    StatusCode status = fetchNamed(session, name);
    if (status.isOk()) {
      status = CatalogViewCodec.decode(catalogRow, scratch, name, view);
    }
    return status.isOk()
        ? session.indexedSession().delete(key.key()) : status;
  }

  StatusCode dropSequence(RelationalSession session, CharSequence name) {
    StatusCode status = fetchNamed(session, name);
    if (status.isOk()) {
      status = CatalogSequenceCodec.decodeUser(catalogRow, scratch, name, sequence);
    }
    return status.isOk()
        ? session.indexedSession().delete(key.key()) : status;
  }

  private StatusCode availableName(
      RelationalSession session, CharSequence name) {
    StatusCode status = RelationalKey.catalogTableKey(name, key);
    if (!status.isOk()) {
      return status;
    }
    status = session.indexedSession().fetchByKey(key.key(), catalogRow);
    return status.isOk()
        ? StatusCode.CONFLICT
        : status == StatusCode.CONFLICT ? StatusCode.OK : status;
  }

  private StatusCode fetchNamed(
      RelationalSession session, CharSequence name) {
    StatusCode status = RelationalKey.catalogTableKey(name, key);
    return status.isOk()
        ? session.indexedSession().fetchByKey(key.key(), catalogRow) : status;
  }

  private StatusCode readNextTableId(RelationalSession session) {
    StatusCode status = session.indexedSession().fetchByKey(
        RelationalKey.CATALOG_SEQUENCE_KEY, catalogRow);
    return status.isOk()
        ? CatalogSequenceCodec.decodeAllocation(catalogRow, scratch, nextTableId)
        : status;
  }
}
