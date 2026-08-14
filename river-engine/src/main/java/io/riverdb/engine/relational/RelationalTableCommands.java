package io.riverdb.engine.relational;

import io.riverdb.base.error.StatusCode;
import io.riverdb.tx.api.IsolationLevel;
import io.riverdb.tx.api.TransactionOutcome;
import io.riverdb.tx.api.TransactionState;

/** Owns implicit-transaction entry for table creation and rename commands. */
final class RelationalTableCommands {
  private final RelationalSchemaLifecycle lifecycle;

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
    RelationalSession session = lifecycle.newSession();
    if (session == null) {
      return StatusCode.RESOURCE_EXHAUSTED;
    }
    TransactionOutcome outcome = new TransactionOutcome();
    StatusCode status = session.begin(IsolationLevel.SERIALIZABLE);
    if (status.isOk()) {
      status = session.createTable(name, keyColumnName, valueColumnName, result);
    }
    return finishCreate(session, outcome, status);
  }

  StatusCode create(CharSequence name, TableSchema schema, TableDefinition result) {
    if (!RelationalKey.validName(name)
        || schema == null
        || !schema.isValid()
        || result == null) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    result.reset();
    RelationalSession session = lifecycle.newSession();
    if (session == null) {
      return StatusCode.RESOURCE_EXHAUSTED;
    }
    TransactionOutcome outcome = new TransactionOutcome();
    StatusCode status = session.begin(IsolationLevel.SERIALIZABLE);
    if (status.isOk()) {
      status = session.createTable(name, schema, result);
    }
    return finishCreate(session, outcome, status);
  }

  synchronized StatusCode renameTable(CharSequence currentName, CharSequence renamedName) {
    RelationalSession session = lifecycle.newSession();
    if (session == null) {
      return StatusCode.RESOURCE_EXHAUSTED;
    }
    TransactionOutcome outcome = new TransactionOutcome();
    StatusCode status = session.begin(IsolationLevel.SERIALIZABLE);
    if (status.isOk()) {
      status = session.renameTable(currentName, renamedName);
    }
    return finish(session, outcome, status);
  }

  synchronized StatusCode renameColumn(
      CharSequence tableName, CharSequence currentName, CharSequence renamedName) {
    RelationalSession session = lifecycle.newSession();
    if (session == null) {
      return StatusCode.RESOURCE_EXHAUSTED;
    }
    TransactionOutcome outcome = new TransactionOutcome();
    StatusCode status = session.begin(IsolationLevel.SERIALIZABLE);
    if (status.isOk()) {
      status = session.renameColumn(tableName, currentName, renamedName);
    }
    return finish(session, outcome, status);
  }

  synchronized StatusCode renameIndex(CharSequence currentName, CharSequence renamedName) {
    RelationalSession session = lifecycle.newSession();
    if (session == null) {
      return StatusCode.RESOURCE_EXHAUSTED;
    }
    TransactionOutcome outcome = new TransactionOutcome();
    StatusCode status = session.begin(IsolationLevel.SERIALIZABLE);
    if (status.isOk()) {
      status = session.renameIndex(currentName, renamedName);
    }
    return finish(session, outcome, status);
  }

  private static StatusCode finish(
      RelationalSession session, TransactionOutcome outcome, StatusCode status) {
    if (status.isOk()) {
      return session.commit(outcome);
    }
    if (session.indexedSession().transaction().state() == TransactionState.ACTIVE) {
      StatusCode abort = session.abort(outcome);
      return abort.isOk() ? status : abort;
    }
    return status;
  }

  private static StatusCode finishCreate(
      RelationalSession session, TransactionOutcome outcome, StatusCode status) {
    if (status.isOk()) {
      return session.commit(outcome);
    }
    if (session.indexedSession().transaction().state() == TransactionState.ACTIVE) {
      session.abort(outcome);
    }
    return status;
  }
}
