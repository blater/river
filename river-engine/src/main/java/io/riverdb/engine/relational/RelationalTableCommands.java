package io.riverdb.engine.relational;

import io.riverdb.base.error.StatusCode;
import io.riverdb.tx.api.IsolationLevel;
import io.riverdb.tx.api.TransactionOutcome;

/** Owns implicit-transaction entry for table creation and rename commands. */
final class RelationalTableCommands {
  private final RelationalSchemaLifecycle lifecycle;
  private final RelationalInternalSessionOwner sessions =
      new RelationalInternalSessionOwner();

  RelationalTableCommands(RelationalSchemaLifecycle schemaLifecycle) {
    lifecycle = schemaLifecycle;
  }

  StatusCode create(
      CharSequence name,
      CharSequence keyColumnName,
      CharSequence valueColumnName,
      TableDefinition result) {
    if (!RelationalKey.validName(name)
        || !RelationalKey.validName(keyColumnName)
        || !RelationalKey.validName(valueColumnName)
        || result == null) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    result.reset();
    StatusCode cleanup = sessions.retry();
    if (!cleanup.isOk()) return cleanup;
    RelationalSession session = lifecycle.newSession();
    if (session == null) {
      return StatusCode.RESOURCE_EXHAUSTED;
    }
    TransactionOutcome outcome = new TransactionOutcome();
    StatusCode status = session.begin(IsolationLevel.SERIALIZABLE);
    if (status.isOk()) {
      status = session.createTable(name, keyColumnName, valueColumnName, result);
    }
    return sessions.finish(session, outcome, status);
  }

  StatusCode create(CharSequence name, TableSchema schema, TableDefinition result) {
    if (!RelationalKey.validName(name)
        || schema == null
        || !schema.isValid()
        || result == null) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    result.reset();
    StatusCode cleanup = sessions.retry();
    if (!cleanup.isOk()) return cleanup;
    RelationalSession session = lifecycle.newSession();
    if (session == null) {
      return StatusCode.RESOURCE_EXHAUSTED;
    }
    TransactionOutcome outcome = new TransactionOutcome();
    StatusCode status = session.begin(IsolationLevel.SERIALIZABLE);
    if (status.isOk()) {
      status = session.createTable(name, schema, result);
    }
    return sessions.finish(session, outcome, status);
  }

  synchronized StatusCode renameTable(CharSequence currentName, CharSequence renamedName) {
    StatusCode cleanup = sessions.retry();
    if (!cleanup.isOk()) return cleanup;
    RelationalSession session = lifecycle.newSession();
    if (session == null) {
      return StatusCode.RESOURCE_EXHAUSTED;
    }
    TransactionOutcome outcome = new TransactionOutcome();
    StatusCode status = session.begin(IsolationLevel.SERIALIZABLE);
    if (status.isOk()) {
      status = session.renameTable(currentName, renamedName);
    }
    return sessions.finish(session, outcome, status);
  }

  synchronized StatusCode renameColumn(
      CharSequence tableName, CharSequence currentName, CharSequence renamedName) {
    StatusCode cleanup = sessions.retry();
    if (!cleanup.isOk()) return cleanup;
    RelationalSession session = lifecycle.newSession();
    if (session == null) {
      return StatusCode.RESOURCE_EXHAUSTED;
    }
    TransactionOutcome outcome = new TransactionOutcome();
    StatusCode status = session.begin(IsolationLevel.SERIALIZABLE);
    if (status.isOk()) {
      status = session.renameColumn(tableName, currentName, renamedName);
    }
    return sessions.finish(session, outcome, status);
  }

  synchronized StatusCode renameIndex(CharSequence currentName, CharSequence renamedName) {
    StatusCode cleanup = sessions.retry();
    if (!cleanup.isOk()) return cleanup;
    RelationalSession session = lifecycle.newSession();
    if (session == null) {
      return StatusCode.RESOURCE_EXHAUSTED;
    }
    TransactionOutcome outcome = new TransactionOutcome();
    StatusCode status = session.begin(IsolationLevel.SERIALIZABLE);
    if (status.isOk()) {
      status = session.renameIndex(currentName, renamedName);
    }
    return sessions.finish(session, outcome, status);
  }

  StatusCode close() { return sessions.retry(); }
}
