package io.riverdb.engine.relational;

import io.riverdb.base.error.StatusCode;
import io.riverdb.base.id.DatabaseIncarnation;
import io.riverdb.base.id.WalGeneration;
import io.riverdb.engine.EmbeddedDatabase;
import io.riverdb.engine.checkpoint.CheckpointResult;
import io.riverdb.tx.api.TransactionOutcome;
import java.nio.file.Path;

/** Durable named-table catalog and logical table sessions over the embedded kernel. */
public final class RelationalDatabase {
  private final EmbeddedDatabase embedded;
  private final RelationalSchemaGate schemaGate = new RelationalSchemaGate();
  private final RelationalSequenceService sequences =
      new RelationalSequenceService(schemaGate);
  private final RelationalSchemaLifecycle schemaLifecycle;
  private final RelationalTableCommands tableCommands;
  private final RelationalSequenceCommands sequenceCommands;
  private final RelationalCatalogBootstrap catalogBootstrap =
      new RelationalCatalogBootstrap();

  RelationalDatabase(EmbeddedDatabase database) {
    embedded = database;
    schemaLifecycle = new RelationalSchemaLifecycle(database, schemaGate);
    tableCommands = new RelationalTableCommands(schemaLifecycle);
    sequenceCommands = new RelationalSequenceCommands(
        schemaLifecycle, schemaGate, sequences);
  }

  public static StatusCode create(
      Path directory,
      DatabaseIncarnation database,
      WalGeneration generation,
      int maximumActiveTransactions,
      RelationalDatabaseOpenResult result) {
    return RelationalDatabaseFactory.create(
        directory, database, generation, maximumActiveTransactions, result);
  }

  public static StatusCode createWithDurableWalQuorum(
      Path directory,
      Path[] followerDirectories,
      int requiredDurableNodes,
      DatabaseIncarnation database,
      WalGeneration generation,
      int maximumActiveTransactions,
      RelationalDatabaseOpenResult result) {
    return RelationalDatabaseFactory.createWithDurableWalQuorum(
        directory,
        followerDirectories,
        requiredDurableNodes,
        database,
        generation,
        maximumActiveTransactions,
        result);
  }

  public static StatusCode openExisting(
      Path directory,
      DatabaseIncarnation database,
      WalGeneration generation,
      int maximumActiveTransactions,
      RelationalDatabaseOpenResult result) {
    return RelationalDatabaseFactory.openExisting(
        directory, database, generation, maximumActiveTransactions, result);
  }

  public static StatusCode openWithDurableWalQuorum(
      Path directory,
      Path[] followerDirectories,
      int requiredDurableNodes,
      DatabaseIncarnation database,
      WalGeneration generation,
      int maximumActiveTransactions,
      RelationalDatabaseOpenResult result) {
    return RelationalDatabaseFactory.openWithDurableWalQuorum(
        directory,
        followerDirectories,
        requiredDurableNodes,
        database,
        generation,
        maximumActiveTransactions,
        result);
  }

  public int requiredDurableNodeCount() {
    return embedded.requiredDurableNodeCount();
  }

  public int availableDurableNodeCount() {
    return embedded.availableDurableNodeCount();
  }

  public long quorumDurableCommitSequence() {
    return embedded.quorumDurableCommitSequence();
  }

  public long replicatedWalPayloadBytes() {
    return embedded.replicatedWalPayloadBytes();
  }

  public StatusCode createTable(CharSequence name, TableDefinition result) {
    return createTable(name, "key", "value", result);
  }

  public StatusCode createTable(
      CharSequence name,
      CharSequence keyColumnName,
      CharSequence valueColumnName,
      TableDefinition result) {
    return tableCommands.create(name, keyColumnName, valueColumnName, result);
  }

  public StatusCode createSequence(
      CharSequence name,
      long start,
      long increment) {
    return sequenceCommands.create(name, start, increment);
  }

  public StatusCode dropSequence(CharSequence name) {
    return sequenceCommands.drop(name);
  }

  public StatusCode nextSequenceValue(
      CharSequence name,
      SequenceValueResult result) {
    return sequenceCommands.next(name, result);
  }

  public StatusCode nextIdentityValue(
      TableDefinition table,
      SequenceValueResult result) {
    return sequenceCommands.nextIdentity(table, result);
  }

  public StatusCode createTable(
      CharSequence name,
      TableSchema schema,
      TableDefinition result) {
    return tableCommands.create(name, schema, result);
  }

  public StatusCode dropTable(CharSequence name) {
    return schemaLifecycle.dropTable(name, Integer.MAX_VALUE);
  }

  StatusCode dropTable(CharSequence name, int maximumCleanupBatches) {
    return schemaLifecycle.dropTable(name, maximumCleanupBatches);
  }

  public StatusCode createUniqueValueIndex(
      CharSequence indexName, CharSequence tableName) {
    return schemaLifecycle.createUniqueValueIndex(indexName, tableName, "value");
  }

  public StatusCode createUniqueValueIndex(
      CharSequence indexName, CharSequence tableName, CharSequence columnName) {
    return schemaLifecycle.createValueIndex(
        indexName, tableName, columnName, Integer.MAX_VALUE, true);
  }

  public StatusCode createValueIndex(
      CharSequence indexName, CharSequence tableName, CharSequence columnName) {
    return schemaLifecycle.createValueIndex(
        indexName, tableName, columnName, Integer.MAX_VALUE, false);
  }

  StatusCode createUniqueValueIndex(
      CharSequence indexName, CharSequence tableName, int maximumBuildBatches) {
    return schemaLifecycle.createValueIndex(
        indexName, tableName, "value", maximumBuildBatches, true);
  }

  StatusCode createValueIndex(
      CharSequence indexName, CharSequence tableName, int maximumBuildBatches) {
    return schemaLifecycle.createValueIndex(
        indexName, tableName, "value", maximumBuildBatches, false);
  }

  public StatusCode dropValueIndex(
      CharSequence indexName, CharSequence tableName) {
    return schemaLifecycle.dropValueIndex(
        indexName, tableName, Integer.MAX_VALUE);
  }

  StatusCode dropValueIndex(
      CharSequence indexName, CharSequence tableName, int maximumCleanupBatches) {
    return schemaLifecycle.dropValueIndex(
        indexName, tableName, maximumCleanupBatches);
  }

  public StatusCode renameTable(
      CharSequence currentName,
      CharSequence renamedName) {
    return tableCommands.renameTable(currentName, renamedName);
  }

  public StatusCode renameColumn(
      CharSequence tableName,
      CharSequence currentName,
      CharSequence renamedName) {
    return tableCommands.renameColumn(tableName, currentName, renamedName);
  }

  public StatusCode renameIndex(
      CharSequence currentName,
      CharSequence renamedName) {
    return tableCommands.renameIndex(currentName, renamedName);
  }

  public StatusCode createSession(RelationalSessionOpenResult result) {
    if (result == null) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    result.reset();
    RelationalSession session = newSession();
    if (session == null) {
      return StatusCode.RESOURCE_EXHAUSTED;
    }
    result.set(session);
    return StatusCode.OK;
  }

  public StatusCode vacuum(TransactionOutcome result) {
    return embedded.vacuum(result);
  }

  public StatusCode checkpoint(CheckpointResult result) {
    return embedded.checkpoint(result);
  }

  public StatusCode close() {
    return embedded.close();
  }

  StatusCode initializeCatalog() {
    return catalogBootstrap.initialize(newSession());
  }

  StatusCode validateCatalog() {
    return catalogBootstrap.validate(newSession());
  }

  private RelationalSession newSession() {
    return schemaLifecycle.newSession();
  }

}
