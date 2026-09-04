package io.riverdb.engine.relational;

import io.riverdb.base.error.StatusCode;
import io.riverdb.base.id.DatabaseIncarnation;
import io.riverdb.base.id.WalGeneration;
import io.riverdb.engine.EmbeddedDatabase;
import io.riverdb.engine.EmbeddedDatabaseOpenResult;
import io.riverdb.engine.EmbeddedLockDiagnosticsConfig;
import io.riverdb.engine.runtime.DatabaseResourcePlan;
import io.riverdb.engine.runtime.DatabaseResourcePlanRequest;
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
      DatabaseResourcePlanRequest resourceRequest,
      Path directory,
      DatabaseIncarnation database,
      WalGeneration generation,
      int maximumActiveTransactions,
      RelationalDatabaseOpenResult result) {
    return create(
        resourceRequest, directory, database, generation, maximumActiveTransactions,
        EmbeddedLockDiagnosticsConfig.disabled(), result);
  }

  static StatusCode create(
      DatabaseResourcePlanRequest resourceRequest,
      Path directory,
      DatabaseIncarnation database,
      WalGeneration generation,
      int maximumActiveTransactions,
      EmbeddedLockDiagnosticsConfig lockDiagnostics,
      RelationalDatabaseOpenResult result) {
    if (resourceRequest == null || lockDiagnostics == null || result == null) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    result.reset();
    RiverRuntimeConfig.Result configResult = new RiverRuntimeConfig.Result();
    StatusCode status = RiverRuntimeConfig.load(
        directory, resourceRequest.maximumAccountedBytes(), configResult, result.detail());
    if (!status.isOk()) return status;
    DatabaseResourceEnvelope.Result resources = new DatabaseResourceEnvelope.Result();
    status = DatabaseResourceEnvelope.create(
        resourceRequest,
        DatabaseResourceEnvelope.retainedSqlRuntimeBytes(configResult.config()),
        resources);
    if (!status.isOk()) return status;
    EmbeddedDatabaseOpenResult embeddedResult = new EmbeddedDatabaseOpenResult();
    status = EmbeddedDatabase.create(
        resources.root(), resources.plan(), directory, database, generation,
        maximumActiveTransactions, configResult.config().lockWaitTimeoutNanos(),
        lockDiagnostics, embeddedResult);
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
      DatabaseResourcePlanRequest resourceRequest,
      Path directory,
      Path[] followerDirectories,
      int requiredDurableNodes,
      DatabaseIncarnation database,
      WalGeneration generation,
      int maximumActiveTransactions,
      RelationalDatabaseOpenResult result) {
    if (resourceRequest == null || result == null) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    result.reset();
    RiverRuntimeConfig.Result configResult = new RiverRuntimeConfig.Result();
    StatusCode status = RiverRuntimeConfig.load(
        directory, resourceRequest.maximumAccountedBytes(), configResult, result.detail());
    if (!status.isOk()) return status;
    DatabaseResourceEnvelope.Result resources = new DatabaseResourceEnvelope.Result();
    status = DatabaseResourceEnvelope.create(
        resourceRequest,
        DatabaseResourceEnvelope.retainedSqlRuntimeBytes(configResult.config()),
        resources);
    if (!status.isOk()) return status;
    EmbeddedDatabaseOpenResult embeddedResult = new EmbeddedDatabaseOpenResult();
    status = EmbeddedDatabase.createWithDurableWalQuorum(
        resources.root(),
        resources.plan(),
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
      DatabaseResourcePlanRequest resourceRequest,
      Path directory,
      DatabaseIncarnation database,
      WalGeneration generation,
      int maximumActiveTransactions,
      RelationalDatabaseOpenResult result) {
    if (resourceRequest == null || result == null) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    result.reset();
    RiverRuntimeConfig.Result configResult = new RiverRuntimeConfig.Result();
    StatusCode status = RiverRuntimeConfig.load(
        directory, resourceRequest.maximumAccountedBytes(), configResult, result.detail());
    if (!status.isOk()) return status;
    DatabaseResourceEnvelope.Result resources = new DatabaseResourceEnvelope.Result();
    status = DatabaseResourceEnvelope.create(
        resourceRequest,
        DatabaseResourceEnvelope.retainedSqlRuntimeBytes(configResult.config()),
        resources);
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
      DatabaseResourcePlanRequest resourceRequest,
      Path directory,
      Path[] followerDirectories,
      int requiredDurableNodes,
      DatabaseIncarnation database,
      WalGeneration generation,
      int maximumActiveTransactions,
      RelationalDatabaseOpenResult result) {
    if (resourceRequest == null || result == null) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    result.reset();
    RiverRuntimeConfig.Result configResult = new RiverRuntimeConfig.Result();
    StatusCode status = RiverRuntimeConfig.load(
        directory, resourceRequest.maximumAccountedBytes(), configResult, result.detail());
    if (!status.isOk()) return status;
    DatabaseResourceEnvelope.Result resources = new DatabaseResourceEnvelope.Result();
    status = DatabaseResourceEnvelope.create(
        resourceRequest,
        DatabaseResourceEnvelope.retainedSqlRuntimeBytes(configResult.config()),
        resources);
    if (!status.isOk()) return status;
    EmbeddedDatabaseOpenResult embeddedResult = new EmbeddedDatabaseOpenResult();
    status = EmbeddedDatabase.openWithDurableWalQuorum(
        resources.root(),
        resources.plan(),
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
