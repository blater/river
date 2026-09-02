package io.riverdb.engine.relational;

import io.riverdb.base.error.StatusCode;
import io.riverdb.base.id.DatabaseIncarnation;
import io.riverdb.base.id.WalGeneration;
import io.riverdb.engine.EmbeddedDatabase;
import io.riverdb.engine.EmbeddedDatabaseOpenResult;
import io.riverdb.engine.runtime.DatabaseResourcePlan;
import io.riverdb.engine.runtime.DatabaseResourceEnvelope;
import io.riverdb.engine.runtime.RiverRuntimeConfig;
import io.riverdb.engine.runtime.RuntimeResourceRoot;
import io.riverdb.engine.runtime.SqlDatabaseRuntime;
import io.riverdb.engine.schema.cache.SchemaCache;
import java.nio.file.Path;

/** Owns embedded-database construction and relational catalog admission. */
final class RelationalDatabaseFactory {
  private RelationalDatabaseFactory() {}

  static StatusCode create(
      Path directory,
      DatabaseIncarnation database,
      WalGeneration generation,
      int maximumActiveTransactions,
      RelationalDatabaseOpenResult result) {
    if (result == null) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    result.reset();
    long maximumBytes = Runtime.getRuntime().maxMemory();
    RiverRuntimeConfig.Result configResult = new RiverRuntimeConfig.Result();
    StatusCode status = RiverRuntimeConfig.load(
        directory, maximumBytes, configResult, result.detail());
    if (!status.isOk()) return status;
    DatabaseResourceEnvelope.Result resources = new DatabaseResourceEnvelope.Result();
    long retainedRuntime = DatabaseResourceEnvelope.retainedSqlRuntimeBytes(
        configResult.config());
    status = DatabaseResourceEnvelope.create(
        maximumBytes, maximumActiveTransactions, retainedRuntime, resources);
    if (!status.isOk()) return status;
    EmbeddedDatabaseOpenResult embeddedResult = new EmbeddedDatabaseOpenResult();
    status = EmbeddedDatabase.create(
        resources.root(), resources.plan(), directory, database, generation,
        maximumActiveTransactions, configResult.config().lockWaitTimeoutNanos(), embeddedResult);
    return finish(embeddedResult, result, configResult.config(), status, true);
  }

  static StatusCode create(
      RuntimeResourceRoot resourceRoot,
      DatabaseResourcePlan resourcePlan,
      Path directory,
      DatabaseIncarnation database,
      WalGeneration generation,
      int maximumActiveTransactions,
      RelationalDatabaseOpenResult result) {
    if (resourceRoot == null || resourcePlan == null || result == null) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    result.reset();
    RiverRuntimeConfig.Result configResult = new RiverRuntimeConfig.Result();
    StatusCode status = RiverRuntimeConfig.load(
        directory, resourcePlan.maximumAccountedBytes(), configResult, result.detail());
    if (!status.isOk()) return status;
    EmbeddedDatabaseOpenResult embeddedResult = new EmbeddedDatabaseOpenResult();
    status = EmbeddedDatabase.create(
        resourceRoot, resourcePlan, directory, database, generation,
        maximumActiveTransactions, configResult.config().lockWaitTimeoutNanos(), embeddedResult);
    return finish(embeddedResult, result, configResult.config(), status, true);
  }

  static StatusCode createWithDurableWalQuorum(
      Path directory,
      Path[] followerDirectories,
      int requiredDurableNodes,
      DatabaseIncarnation database,
      WalGeneration generation,
      int maximumActiveTransactions,
      RelationalDatabaseOpenResult result) {
    if (result == null) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    result.reset();
    RiverRuntimeConfig.Result configResult = new RiverRuntimeConfig.Result();
    StatusCode status = RiverRuntimeConfig.load(
        directory, configResult, result.detail());
    if (!status.isOk()) return status;
    EmbeddedDatabaseOpenResult embeddedResult = new EmbeddedDatabaseOpenResult();
    status = EmbeddedDatabase.createWithDurableWalQuorum(
        directory,
        followerDirectories,
        requiredDurableNodes,
        database,
        generation,
        maximumActiveTransactions,
        configResult.config().lockWaitTimeoutNanos(),
        embeddedResult);
    return finish(embeddedResult, result, configResult.config(), status, true);
  }

  static StatusCode openExisting(
      Path directory,
      DatabaseIncarnation database,
      WalGeneration generation,
      int maximumActiveTransactions,
      RelationalDatabaseOpenResult result) {
    if (result == null) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    result.reset();
    long maximumBytes = Runtime.getRuntime().maxMemory();
    RiverRuntimeConfig.Result configResult = new RiverRuntimeConfig.Result();
    StatusCode status = RiverRuntimeConfig.load(
        directory, maximumBytes, configResult, result.detail());
    if (!status.isOk()) return status;
    DatabaseResourceEnvelope.Result resources = new DatabaseResourceEnvelope.Result();
    long retainedRuntime = DatabaseResourceEnvelope.retainedSqlRuntimeBytes(
        configResult.config());
    status = DatabaseResourceEnvelope.create(
        maximumBytes, maximumActiveTransactions, retainedRuntime, resources);
    if (!status.isOk()) return status;
    EmbeddedDatabaseOpenResult embeddedResult = new EmbeddedDatabaseOpenResult();
    status = EmbeddedDatabase.openExisting(
        resources.root(), resources.plan(), directory, database, generation,
        maximumActiveTransactions, configResult.config().lockWaitTimeoutNanos(), embeddedResult);
    return finish(embeddedResult, result, configResult.config(), status, false);
  }

  static StatusCode openExisting(
      RuntimeResourceRoot resourceRoot,
      DatabaseResourcePlan resourcePlan,
      Path directory,
      DatabaseIncarnation database,
      WalGeneration generation,
      int maximumActiveTransactions,
      RelationalDatabaseOpenResult result) {
    if (resourceRoot == null || resourcePlan == null || result == null) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    result.reset();
    RiverRuntimeConfig.Result configResult = new RiverRuntimeConfig.Result();
    StatusCode status = RiverRuntimeConfig.load(
        directory, resourcePlan.maximumAccountedBytes(), configResult, result.detail());
    if (!status.isOk()) return status;
    EmbeddedDatabaseOpenResult embeddedResult = new EmbeddedDatabaseOpenResult();
    status = EmbeddedDatabase.openExisting(
        resourceRoot, resourcePlan, directory, database, generation,
        maximumActiveTransactions, configResult.config().lockWaitTimeoutNanos(), embeddedResult);
    return finish(embeddedResult, result, configResult.config(), status, false);
  }

  static StatusCode openWithDurableWalQuorum(
      Path directory,
      Path[] followerDirectories,
      int requiredDurableNodes,
      DatabaseIncarnation database,
      WalGeneration generation,
      int maximumActiveTransactions,
      RelationalDatabaseOpenResult result) {
    if (result == null) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    result.reset();
    RiverRuntimeConfig.Result configResult = new RiverRuntimeConfig.Result();
    StatusCode status = RiverRuntimeConfig.load(
        directory, configResult, result.detail());
    if (!status.isOk()) return status;
    EmbeddedDatabaseOpenResult embeddedResult = new EmbeddedDatabaseOpenResult();
    status = EmbeddedDatabase.openWithDurableWalQuorum(
        directory,
        followerDirectories,
        requiredDurableNodes,
        database,
        generation,
        maximumActiveTransactions,
        configResult.config().lockWaitTimeoutNanos(),
        embeddedResult);
    return finish(embeddedResult, result, configResult.config(), status, false);
  }

  private static StatusCode finish(
      EmbeddedDatabaseOpenResult embeddedResult,
      RelationalDatabaseOpenResult result,
      RiverRuntimeConfig config,
      StatusCode status,
      boolean initialize) {
    if (!status.isOk()) {
      result.detail().set(status);
      return status;
    }
    long retainedRuntime = DatabaseResourceEnvelope.retainedSqlRuntimeBytes(config);
    status = retainedRuntime < 0 ? StatusCode.RESOURCE_EXHAUSTED
        : embeddedResult.database().retainRuntimeCapacity(retainedRuntime);
    if (!status.isOk()) {
      result.detail().set(status);
      return RelationalOpenCleanup.result(
          status, embeddedResult.database().closeAfterOpenFailure(),
          StatusCode.OK, result.detail());
    }
    SqlDatabaseRuntime.OpenResult runtimeResult = new SqlDatabaseRuntime.OpenResult();
    status = SqlDatabaseRuntime.create(
        config,
        embeddedResult.database().primaryDirectoryRoot(),
        embeddedResult.database().databaseIncarnation(),
        runtimeResult,
        result.detail());
    if (!status.isOk()) {
      result.detail().set(status);
      return RelationalOpenCleanup.result(
          status, embeddedResult.database().closeAfterOpenFailure(),
          StatusCode.OK, result.detail());
    }
    SchemaCache.Result cacheResult = new SchemaCache.Result();
    status = SchemaCache.createBudgeted(
        config.schemaCacheBytes(), cacheResult, result.detail());
    if (!status.isOk()) {
      StatusCode runtimeClose = runtimeResult.runtime().prepareClose();
      if (runtimeClose.isOk()) runtimeClose = runtimeResult.runtime().completeClose();
      StatusCode embeddedClose = embeddedResult.database().closeAfterOpenFailure();
      return RelationalOpenCleanup.result(
          status, runtimeClose, embeddedClose, result.detail());
    }
    RelationalDatabase relational;
    try {
      RelationalDatabaseServices services = new RelationalDatabaseServices(
          embeddedResult.database(), runtimeResult.runtime(), cacheResult.value());
      relational = new RelationalDatabase(embeddedResult.database(), services);
    } catch (OutOfMemoryError error) {
      result.detail().set(StatusCode.RESOURCE_EXHAUSTED);
      StatusCode runtimeClose = runtimeResult.runtime().prepareClose();
      if (runtimeClose.isOk()) runtimeClose = runtimeResult.runtime().completeClose();
      StatusCode embeddedClose = embeddedResult.database().closeAfterOpenFailure();
      return RelationalOpenCleanup.result(
          StatusCode.RESOURCE_EXHAUSTED,
          runtimeClose,
          embeddedClose,
          result.detail());
    }
    status = initialize ? relational.initializeCatalog() : relational.validateCatalog();
    if (!status.isOk()) {
      result.detail().set(status);
      return RelationalOpenCleanup.result(
          status, relational.closeAfterOpenFailure(), StatusCode.OK, result.detail());
    }
    result.set(relational);
    return StatusCode.OK;
  }
}
