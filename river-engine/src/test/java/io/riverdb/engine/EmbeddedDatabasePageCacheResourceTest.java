package io.riverdb.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.riverdb.base.error.StatusCode;
import io.riverdb.base.id.DatabaseIncarnation;
import io.riverdb.base.id.WalGeneration;
import io.riverdb.engine.checkpoint.CheckpointResult;
import io.riverdb.engine.runtime.DatabaseResourceEnvelope;
import io.riverdb.engine.runtime.DatabaseResourcePlanRequest;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class EmbeddedDatabasePageCacheResourceTest {
  @Test
  void explicitPlanControlsPhysicalStagingAndDetachesBeforeRootRelease(
      @TempDir Path directory) {
    DatabaseResourcePlanRequest request = new DatabaseResourcePlanRequest()
        .memory(64_000_000, 0, 0, 0, 20_000_000)
        .lockProviderBytes(4_000_000)
        .versionWorkspaceBytes(1_000_000)
        .indexedPageCache(24_000_000, 4_000_000)
        .capacity(8, Integer.MAX_VALUE, 2_048, 20_000_000)
        .maximumDelivery(Integer.MAX_VALUE, 1_024, 20_000_000);
    DatabaseResourceEnvelope.Result resources = new DatabaseResourceEnvelope.Result();
    assertEquals(StatusCode.OK, DatabaseResourceEnvelope.create(request, 0, resources));
    EmbeddedDatabaseOpenResult opened = new EmbeddedDatabaseOpenResult();

    assertEquals(StatusCode.OK, EmbeddedDatabase.create(
        resources.root(), resources.plan(), directory,
        DatabaseIncarnation.of(151, 157), WalGeneration.of(1), 8, opened));

    EmbeddedDatabase database = opened.database();
    assertEquals(resources.plan().stagedPageCapacity(),
        database.resourceStagedPageCapacity());
    assertEquals(
        resources.plan().indexedPageCache().maximumRetainedBytes(),
        database.resourcePageCacheRetainedBytes());
    assertTrue(database.retainedDatabaseAccountedBytes()
        >= database.resourcePageCacheRetainedBytes() + resources.plan().lockProviderBytes());
    assertEquals(StatusCode.OK, database.checkpoint(new CheckpointResult()));
    assertEquals(StatusCode.OK, database.close());
    assertEquals(0, resources.root().admittedAccountedBytes());

    opened.reset();
    assertEquals(StatusCode.OK, EmbeddedDatabase.openExisting(
        resources.root(), resources.plan(), directory,
        DatabaseIncarnation.of(151, 157), WalGeneration.of(1), 8, opened));
    assertEquals(resources.plan().stagedPageCapacity(),
        opened.database().resourceStagedPageCapacity());
    assertEquals(StatusCode.OK, opened.database().close());
    assertEquals(0, resources.root().admittedAccountedBytes());
  }

  @Test
  void invalidCacheBudgetFailsBeforeDatabaseFilesAreCreated(@TempDir Path directory) {
    DatabaseResourcePlanRequest request = new DatabaseResourcePlanRequest()
        .memory(64_000_000, 0, 0, 0, 20_000_000)
        .lockProviderBytes(4_000_000)
        .versionWorkspaceBytes(1_000_000)
        .indexedPageCache(100_000, 20_000)
        .capacity(8, Integer.MAX_VALUE, 2_048, 20_000_000)
        .maximumDelivery(Integer.MAX_VALUE, 1_024, 20_000_000);
    DatabaseResourceEnvelope.Result resources = new DatabaseResourceEnvelope.Result();

    assertEquals(StatusCode.RESOURCE_EXHAUSTED,
        DatabaseResourceEnvelope.create(request, 0, resources));
    assertEquals(false, java.nio.file.Files.exists(
        directory.resolve(io.riverdb.engine.table.IndexedTableStore.FILE_NAME)));
  }
}
