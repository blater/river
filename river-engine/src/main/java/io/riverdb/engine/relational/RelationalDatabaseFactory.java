package io.riverdb.engine.relational;

import io.riverdb.base.error.StatusCode;
import io.riverdb.base.id.DatabaseIncarnation;
import io.riverdb.base.id.WalGeneration;
import io.riverdb.engine.EmbeddedDatabase;
import io.riverdb.engine.EmbeddedDatabaseOpenResult;
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
    EmbeddedDatabaseOpenResult embeddedResult = new EmbeddedDatabaseOpenResult();
    StatusCode status = EmbeddedDatabase.create(
        directory, database, generation, maximumActiveTransactions, embeddedResult);
    return finish(embeddedResult, result, status, true);
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
    EmbeddedDatabaseOpenResult embeddedResult = new EmbeddedDatabaseOpenResult();
    StatusCode status = EmbeddedDatabase.createWithDurableWalQuorum(
        directory,
        followerDirectories,
        requiredDurableNodes,
        database,
        generation,
        maximumActiveTransactions,
        embeddedResult);
    return finish(embeddedResult, result, status, true);
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
    EmbeddedDatabaseOpenResult embeddedResult = new EmbeddedDatabaseOpenResult();
    StatusCode status = EmbeddedDatabase.openExisting(
        directory, database, generation, maximumActiveTransactions, embeddedResult);
    return finish(embeddedResult, result, status, false);
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
    EmbeddedDatabaseOpenResult embeddedResult = new EmbeddedDatabaseOpenResult();
    StatusCode status = EmbeddedDatabase.openWithDurableWalQuorum(
        directory,
        followerDirectories,
        requiredDurableNodes,
        database,
        generation,
        maximumActiveTransactions,
        embeddedResult);
    return finish(embeddedResult, result, status, false);
  }

  private static StatusCode finish(
      EmbeddedDatabaseOpenResult embeddedResult,
      RelationalDatabaseOpenResult result,
      StatusCode status,
      boolean initialize) {
    if (!status.isOk()) {
      return status;
    }
    RelationalDatabase relational = new RelationalDatabase(embeddedResult.database());
    status = initialize ? relational.initializeCatalog() : relational.validateCatalog();
    if (!status.isOk()) {
      relational.close();
      return status;
    }
    result.set(relational);
    return StatusCode.OK;
  }
}
