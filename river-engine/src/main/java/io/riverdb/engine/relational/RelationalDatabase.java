package io.riverdb.engine.relational;

import io.riverdb.base.error.StatusCode;
import io.riverdb.base.id.DatabaseIncarnation;
import io.riverdb.base.id.WalGeneration;
import io.riverdb.engine.EmbeddedDatabase;
import io.riverdb.engine.checkpoint.CheckpointResult;
import io.riverdb.engine.runtime.DatabaseResourcePlan;
import io.riverdb.engine.runtime.RuntimeResourceRoot;
import io.riverdb.tx.api.IsolationLevel;
import io.riverdb.tx.api.TransactionOutcome;
import java.nio.file.Path;

/** Durable named-table catalog and logical table sessions over the embedded kernel. */
public final class RelationalDatabase {
  private final EmbeddedDatabase embedded;
  private final RelationalDatabaseServices services;
  private final RelationalSchemaGate schemaGate = new RelationalSchemaGate();
  private final RelationalSchemaLifecycle schemaLifecycle;
  private final RelationalDatabaseCommands commands;
  private final RelationalCatalogBootstrap catalogBootstrap =
      new RelationalCatalogBootstrap();
  private final RelationalInternalSessionOwner bootstrapSessions =
      new RelationalInternalSessionOwner();

  RelationalDatabase(
      EmbeddedDatabase database,
      RelationalDatabaseServices databaseServices) {
    embedded = database;
    services = databaseServices;
    schemaLifecycle = new RelationalSchemaLifecycle(database, schemaGate, services);
    commands = new RelationalDatabaseCommands(schemaLifecycle, schemaGate);
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

  public static StatusCode create(
      RuntimeResourceRoot resourceRoot,
      DatabaseResourcePlan resourcePlan,
      Path directory,
      DatabaseIncarnation database,
      WalGeneration generation,
      int maximumActiveTransactions,
      RelationalDatabaseOpenResult result) {
    return RelationalDatabaseFactory.create(
        resourceRoot, resourcePlan, directory, database, generation,
        maximumActiveTransactions, result);
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

  public static StatusCode openExisting(
      RuntimeResourceRoot resourceRoot,
      DatabaseResourcePlan resourcePlan,
      Path directory,
      DatabaseIncarnation database,
      WalGeneration generation,
      int maximumActiveTransactions,
      RelationalDatabaseOpenResult result) {
    return RelationalDatabaseFactory.openExisting(
        resourceRoot, resourcePlan, directory, database, generation,
        maximumActiveTransactions, result);
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

  public long lockWaitsEntered() { return embedded.lockWaitsEntered(); }

  public long lockWaitsGranted() { return embedded.lockWaitsGranted(); }

  public long lockWaitsTimedOut() { return embedded.lockWaitsTimedOut(); }

  public long lockWaitsDeadlocked() { return embedded.lockWaitsDeadlocked(); }

  public long lockWaitsCancelled() { return embedded.lockWaitsCancelled(); }

  public boolean lockEscalationSupported() { return embedded.lockEscalationSupported(); }

  public long lockEscalationCount() { return embedded.lockEscalationCount(); }

  boolean resourceGoverned() { return embedded.resourceGoverned(); }

  long resourceWriteEntryCapacity() { return embedded.resourceWriteEntryCapacity(); }

  public StatusCode createTable(CharSequence name, TableDefinition result) {
    return createTable(name, "key", "value", result);
  }

  public StatusCode createTable(
      CharSequence name,
      CharSequence keyColumnName,
      CharSequence valueColumnName,
      TableDefinition result) {
    return commands.createTable(name, keyColumnName, valueColumnName, result);
  }

  public StatusCode createSequence(
      CharSequence name,
      long start,
      long increment) {
    return commands.createSequence(name, start, increment);
  }

  public StatusCode dropSequence(CharSequence name) {
    return commands.dropSequence(name);
  }

  public StatusCode nextSequenceValue(
      CharSequence name,
      SequenceValueResult result) {
    return commands.nextSequence(name, result);
  }

  public StatusCode nextIdentityValue(
      TableDefinition table,
      SequenceValueResult result) {
    return commands.nextIdentity(table, result);
  }

  public StatusCode createTable(
      CharSequence name,
      TableSchema schema,
      TableDefinition result) {
    return commands.createTable(name, schema, result);
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
    return commands.renameTable(currentName, renamedName);
  }

  public StatusCode renameColumn(
      CharSequence tableName,
      CharSequence currentName,
      CharSequence renamedName) {
    return commands.renameColumn(tableName, currentName, renamedName);
  }

  public StatusCode renameIndex(
      CharSequence currentName,
      CharSequence renamedName) {
    return commands.renameIndex(currentName, renamedName);
  }

  public synchronized StatusCode createSession(RelationalSessionOpenResult result) {
    if (result == null) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    result.reset();
    if (services.isClosing()) return StatusCode.CLOSED;
    return schemaLifecycle.openSession(result);
  }

  public StatusCode vacuum(TransactionOutcome result) {
    return embedded.vacuum(result);
  }

  public StatusCode checkpoint(CheckpointResult result) {
    return embedded.checkpoint(result);
  }

  /** Auxiliary SQL runtime, schema-cache, and catalog-v2 services sharing this close gate. */
  public RelationalDatabaseServices services() {
    return services;
  }

  public synchronized StatusCode close() {
    StatusCode status = commands.close();
    status = firstFailure(status, bootstrapSessions.retry());
    status = firstFailure(status, schemaLifecycle.close());
    return firstFailure(status, services.close(embedded));
  }

  synchronized StatusCode closeAfterOpenFailure() {
    StatusCode first = commands.close();
    first = firstFailure(first, bootstrapSessions.retry());
    first = firstFailure(first, schemaLifecycle.close());
    return firstFailure(first, services.closeAfterOpenFailure());
  }

  private static StatusCode firstFailure(StatusCode first, StatusCode next) {
    return first.isOk() ? next : first;
  }

  StatusCode initializeCatalog() {
    StatusCode status = bootstrapSessions.retry();
    if (status.isOk()) {
      status = catalogBootstrap.initialize(newSession(), bootstrapSessions);
    }
    return status.isOk() ? services.initializeCatalog() : status;
  }

  StatusCode validateCatalog() {
    StatusCode status = bootstrapSessions.retry();
    if (status.isOk()) {
      status = catalogBootstrap.validate(newSession(), bootstrapSessions);
    }
    if (status.isOk()) status = services.validateCatalog();
    return status.isOk() ? validateDescriptorNames() : status;
  }

  private StatusCode validateDescriptorNames() {
    StatusCode cleanup = bootstrapSessions.retry();
    if (!cleanup.isOk()) return cleanup;
    RelationalSession session = newSession();
    if (session == null) return StatusCode.RESOURCE_EXHAUSTED;
    TransactionOutcome outcome = new TransactionOutcome();
    StatusCode status = session.begin(IsolationLevel.REPEATABLE_READ);
    if (status.isOk()) status = session.validateDescriptorNames();
    return bootstrapSessions.finish(session, outcome, status, false);
  }

  private RelationalSession newSession() {
    return schemaLifecycle.newSession();
  }

}
