package io.riverdb.engine.relational;

import io.riverdb.base.error.StatusCode;

/** Concrete database-level table and sequence command facade. */
final class RelationalDatabaseCommands {
  private final RelationalTableCommands tables;
  private final RelationalSequenceCommands sequences;

  RelationalDatabaseCommands(
      RelationalSchemaLifecycle lifecycle, RelationalSchemaGate gate) {
    tables = new RelationalTableCommands(lifecycle);
    sequences = new RelationalSequenceCommands(
        lifecycle, gate, new RelationalSequenceService(gate));
  }

  StatusCode createTable(
      CharSequence name,
      CharSequence keyName,
      CharSequence valueName,
      TableDefinition result) {
    return tables.create(name, keyName, valueName, result);
  }

  StatusCode createTable(
      CharSequence name, TableSchema schema, TableDefinition result) {
    return tables.create(name, schema, result);
  }

  StatusCode renameTable(CharSequence current, CharSequence renamed) {
    return tables.renameTable(current, renamed);
  }

  StatusCode renameColumn(
      CharSequence table, CharSequence current, CharSequence renamed) {
    return tables.renameColumn(table, current, renamed);
  }

  StatusCode renameIndex(CharSequence current, CharSequence renamed) {
    return tables.renameIndex(current, renamed);
  }

  StatusCode createSequence(CharSequence name, long start, long increment) {
    return sequences.create(name, start, increment);
  }

  StatusCode dropSequence(CharSequence name) {
    return sequences.drop(name);
  }

  StatusCode nextSequence(CharSequence name, SequenceValueResult result) {
    return sequences.next(name, result);
  }

  StatusCode nextIdentity(TableDefinition table, SequenceValueResult result) {
    return sequences.nextIdentity(table, result);
  }

  StatusCode close() {
    StatusCode status = tables.close();
    StatusCode sequenceStatus = sequences.close();
    return status.isOk() ? sequenceStatus : status;
  }
}
