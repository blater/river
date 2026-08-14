package io.riverdb.engine.relational;

import io.riverdb.base.error.StatusCode;
import io.riverdb.tx.api.IsolationLevel;
import io.riverdb.tx.api.TransactionOutcome;
import io.riverdb.tx.api.TransactionState;

/** Owns implicit sequence DDL and schema-gated value allocation entry. */
final class RelationalSequenceCommands {
  private final RelationalSchemaLifecycle lifecycle;
  private final RelationalSchemaGate schemaGate;
  private final RelationalSequenceService sequences;
  private final RelationalKey.LongKeyResult catalogKey = new RelationalKey.LongKeyResult();

  RelationalSequenceCommands(
      RelationalSchemaLifecycle schemaLifecycle,
      RelationalSchemaGate gate,
      RelationalSequenceService sequenceService) {
    lifecycle = schemaLifecycle;
    schemaGate = gate;
    sequences = sequenceService;
  }

  synchronized StatusCode create(CharSequence name, long start, long increment) {
    RelationalSession session = lifecycle.newSession();
    if (session == null) {
      return StatusCode.RESOURCE_EXHAUSTED;
    }
    TransactionOutcome outcome = new TransactionOutcome();
    StatusCode status = session.begin(IsolationLevel.SERIALIZABLE);
    if (status.isOk()) {
      status = session.createSequence(name, start, increment);
    }
    return finish(session, outcome, status);
  }

  synchronized StatusCode drop(CharSequence name) {
    RelationalSession session = lifecycle.newSession();
    if (session == null) {
      return StatusCode.RESOURCE_EXHAUSTED;
    }
    TransactionOutcome outcome = new TransactionOutcome();
    StatusCode status = session.begin(IsolationLevel.SERIALIZABLE);
    if (status.isOk()) {
      status = session.dropSequence(name);
    }
    return finish(session, outcome, status);
  }

  StatusCode next(CharSequence name, SequenceValueResult result) {
    if (!RelationalKey.validName(name) || result == null) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    result.reset();
    StatusCode status = schemaGate.enterSequenceOperation();
    if (!status.isOk()) {
      return status;
    }
    try {
      return nextAdmitted(name, result);
    } finally {
      schemaGate.leaveSequenceOperation();
    }
  }

  StatusCode nextIdentity(TableDefinition table, SequenceValueResult result) {
    if (table == null || result == null || !table.hasIdentity()) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    StatusCode status = schemaGate.enterIdentitySequenceOperation(table);
    if (status == StatusCode.INVALID_EXTERNAL_INPUT) {
      return status;
    }
    result.reset();
    if (!status.isOk()) {
      return status;
    }
    try {
      return nextIdentityAdmitted(table, result);
    } finally {
      schemaGate.leaveSequenceOperation();
    }
  }

  private synchronized StatusCode nextAdmitted(
      CharSequence name, SequenceValueResult result) {
    StatusCode status = RelationalKey.catalogTableKey(name, catalogKey);
    return status.isOk()
        ? allocate(catalogKey.key(), name, 0, Long.MIN_VALUE, Long.MAX_VALUE, result)
        : status;
  }

  private synchronized StatusCode nextIdentityAdmitted(
      TableDefinition table, SequenceValueResult result) {
    return allocate(
        RelationalKey.identitySequenceKey(table.tableId()),
        null,
        table.tableId(),
        1,
        RelationalKey.MAXIMUM_USER_KEY,
        result);
  }

  private StatusCode allocate(
      long sequenceKey,
      CharSequence name,
      int identityTableId,
      long minimum,
      long maximum,
      SequenceValueResult result) {
    if (sequences.consumeCached(sequenceKey, result)) {
      return StatusCode.OK;
    }
    RelationalSession session = lifecycle.newSession();
    return session == null
        ? StatusCode.RESOURCE_EXHAUSTED
        : sequences.reserve(
            session, sequenceKey, name, identityTableId, minimum, maximum, result);
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
}
