package io.riverdb.engine.relational;

import io.riverdb.base.error.StatusCode;
import io.riverdb.tx.api.IsolationLevel;
import io.riverdb.tx.api.TransactionOutcome;
import io.riverdb.tx.api.TransactionState;
import java.nio.ByteBuffer;

/** Table rename and durable drop lifecycle. */
final class RelationalTableLifecycle {
  private final RelationalSchemaGate schemaGate;
  private final RelationalCatalogDependencies dependencies;
  private final RelationalPhysicalCleanup physicalCleanup;
  private final io.riverdb.storage.heap.HeapRowResult catalogRow =
      new io.riverdb.storage.heap.HeapRowResult();
  private final ByteBuffer catalogScratch =
      ByteBuffer.allocateDirect(CatalogRecord.MAXIMUM_BYTES);
  private final ByteBuffer catalogOutput =
      ByteBuffer.allocateDirect(CatalogRecord.MAXIMUM_BYTES);
  private final RelationalKey.KeyResult catalogKey = new RelationalKey.KeyResult();
  private final TableDefinition table = new TableDefinition();
  private final TableDefinition updatedTable = new TableDefinition();
  private boolean alreadyMarked;

  RelationalTableLifecycle(RelationalSchemaGate gate) {
    schemaGate = gate;
    dependencies = new RelationalCatalogDependencies(gate);
    physicalCleanup = new RelationalPhysicalCleanup(gate);
  }

  StatusCode renameTable(
      RelationalSession session,
      CharSequence currentName,
      CharSequence renamedName) {
    StatusCode status = session.resolveTable(currentName, table);
    if (status.isOk()) {
      status = dependencies.checkViewReferences(session, table.tableId());
    }
    if (status.isOk()) {
      status = RelationalKey.catalogTableKey(renamedName, catalogKey);
    }
    if (status.isOk()) {
      status = ensureCatalogKeyAbsent(session);
    }
    if (status.isOk()) {
      CatalogRecord.encodeTable(
          catalogOutput,
          table.tableId(),
          0,
          TableDefinition.INDEX_NONE,
          -1,
          renamedName,
          table);
      status = session.indexedSession().insert(
          catalogKey.space(), catalogKey.key(), catalogOutput);
    }
    if (status.isOk()) {
      status = RelationalKey.catalogTableKey(currentName, catalogKey);
    }
    return status.isOk()
        ? session.indexedSession().delete(catalogKey.space(), catalogKey.key()) : status;
  }

  StatusCode renameColumn(
      RelationalSession session,
      CharSequence tableName,
      CharSequence currentName,
      CharSequence renamedName) {
    StatusCode status = session.resolveTable(tableName, table);
    if (status.isOk()) {
      status = dependencies.checkViewReferences(session, table.tableId());
    }
    if (status.isOk()) {
      updatedTable.set(
          schemaGate,
          table.tableId(),
          0,
          TableDefinition.INDEX_NONE,
          -1,
          table);
      status = updatedTable.renameColumn(currentName, renamedName);
    }
    if (status.isOk()) {
      status = RelationalKey.catalogTableKey(tableName, catalogKey);
    }
    if (status.isOk()) {
      CatalogRecord.encodeTable(
          catalogOutput,
          updatedTable.tableId(),
          0,
          TableDefinition.INDEX_NONE,
          -1,
          tableName,
          updatedTable);
      status = session.indexedSession().update(
          catalogKey.space(), catalogKey.key(), catalogOutput);
    }
    return status;
  }

  synchronized StatusCode drop(
      RelationalSession session,
      CharSequence name,
      int maximumCleanupBatches) {
    if (!RelationalKey.validName(name) || maximumCleanupBatches <= 0) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    TransactionOutcome outcome = new TransactionOutcome();
    StatusCode status = session.begin(IsolationLevel.SERIALIZABLE);
    if (status.isOk()) {
      status = session.beginPersistentSchemaChange();
    }
    if (status.isOk()) {
      status = markDropping(session, name);
    }
    if (session.indexedSession().transaction().state() == TransactionState.ACTIVE) {
      StatusCode terminal = status.isOk() && !alreadyMarked
          ? session.commitBuildPhase(outcome) : session.abortBuildPhase(outcome);
      if (status.isOk()) {
        status = terminal;
      }
    }
    if (status.isOk() && !alreadyMarked) {
      status = schemaGate.publishOwnedSchema(session);
    }
    if (!status.isOk()) {
      session.releasePersistentSchemaChange();
      return status;
    }
    return physicalCleanup.cleanupDroppingTable(
        session, table, name, outcome, maximumCleanupBatches);
  }

  synchronized StatusCode markDropping(
      RelationalSession session, CharSequence name) {
    alreadyMarked = false;
    StatusCode status = RelationalKey.catalogTableKey(name, catalogKey);
    if (status.isOk()) {
      status = session.indexedSession().fetchByKey(
          catalogKey.space(), catalogKey.key(), catalogRow);
    }
    if (status.isOk()) {
      alreadyMarked = CatalogRecord.isDroppingTable(catalogRow, catalogScratch);
      status = alreadyMarked
          ? CatalogRecord.decodeDroppingTable(
              catalogRow, catalogScratch, name, schemaGate, table)
          : CatalogRecord.decodeTable(
              catalogRow, catalogScratch, name, schemaGate, table);
    }
    if (status.isOk() && !alreadyMarked) {
      status = dependencies.checkSchemaReferences(session, table);
    }
    if (status.isOk() && !alreadyMarked) {
      CatalogRecord.encodeDroppingTable(
          catalogOutput, table.tableId(), name, table);
      status = session.indexedSession().update(
          catalogKey.space(), catalogKey.key(), catalogOutput);
      if (status.isOk() && table.hasIdentity()) {
        status = session.indexedSession().delete(
            RelationalKey.CATALOG_SEQUENCE_SPACE,
            RelationalKey.identitySequenceKey(table.tableId()));
      }
    }
    return status;
  }

  StatusCode finishDropping(
      RelationalSession session,
      CharSequence tableName,
      TransactionOutcome outcome) {
    StatusCode status = schemaGate.publishOwnedSchema(session);
    return status.isOk()
        ? physicalCleanup.cleanupDroppingTable(
            session, table, tableName, outcome, Integer.MAX_VALUE)
        : status;
  }

  private StatusCode ensureCatalogKeyAbsent(RelationalSession session) {
    StatusCode status = session.indexedSession().fetchByKey(
        catalogKey.space(), catalogKey.key(), catalogRow);
    if (status.isOk()) {
      return StatusCode.CONFLICT;
    }
    return status == StatusCode.CONFLICT ? StatusCode.OK : status;
  }
}
