package io.riverdb.engine.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import io.riverdb.base.error.StatusCode;
import io.riverdb.base.error.StatusDetail;
import io.riverdb.base.id.DatabaseIncarnation;
import io.riverdb.engine.runtime.materialized.SqlMaterializedPagePool;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class SqlDatabaseRuntimeTest {
  private static final DatabaseIncarnation DATABASE =
      DatabaseIncarnation.of(0x52554e54494d4553L, 0x4352415443485047L);

  @Test
  void leasesBlockCloseAndReleaseExactlyOnce(@TempDir Path root) throws IOException {
    RiverRuntimeConfig.Result configResult = new RiverRuntimeConfig.Result();
    assertEquals(
        StatusCode.OK,
        RiverRuntimeConfig.load(
            root,
            2_048_000_000L,
            root.toString(),
            configResult,
            new StatusDetail(512)));
    SqlDatabaseRuntime.OpenResult runtimeResult = new SqlDatabaseRuntime.OpenResult();
    assertEquals(
        StatusCode.OK,
        SqlDatabaseRuntime.create(
            configResult.config(), root, DATABASE, runtimeResult,
            new StatusDetail(256)));
    SqlDatabaseRuntime runtime = runtimeResult.runtime();
    SqlRuntimeLeaseResult leaseResult = new SqlRuntimeLeaseResult();

    assertEquals(StatusCode.OK, runtime.acquire(leaseResult));
    SqlRuntimeLease first = leaseResult.lease();
    SqlMaterializedPagePool pages = first.materializedPages();
    Path scratchInstance = first.materializedScratch().instancePath();
    assertEquals(true, Files.isDirectory(scratchInstance));
    assertEquals(configResult.config().pageBytes(), pages.pageBytes());
    assertEquals(configResult.config().cachePages(), pages.frameCount());
    assertEquals(StatusCode.OK, runtime.acquire(leaseResult));
    SqlRuntimeLease second = leaseResult.lease();
    assertEquals(pages, second.materializedPages());
    assertEquals(StatusCode.CONFLICT, runtime.prepareClose());
    assertEquals(StatusCode.OK, first.close());
    assertEquals(StatusCode.CLOSED, first.close());
    assertEquals(StatusCode.CONFLICT, runtime.prepareClose());
    assertEquals(StatusCode.OK, second.close());

    assertEquals(StatusCode.OK, runtime.prepareClose());
    assertEquals(StatusCode.CLOSED, runtime.acquire(leaseResult));
    assertNull(leaseResult.lease());
    assertEquals(StatusCode.OK, runtime.completeClose());
    assertEquals(false, Files.exists(scratchInstance));
    assertEquals(StatusCode.CLOSED, pages.reserve(1, 1));
    assertEquals(StatusCode.CLOSED, runtime.prepareClose());
  }

  @Test
  void closeFenceWinsBlockedAcquireWithoutTiming(@TempDir Path root)
      throws IOException, InterruptedException {
    SqlDatabaseRuntime runtime = runtime(root);
    SqlRuntimeLeaseResult result = new SqlRuntimeLeaseResult();
    CountDownLatch attempting = new CountDownLatch(1);
    AtomicReference<StatusCode> acquired = new AtomicReference<>();
    Thread contender;

    synchronized (runtime) {
      contender = new Thread(() -> {
        attempting.countDown();
        acquired.set(runtime.acquire(result));
      });
      contender.start();
      attempting.await();
      assertEquals(StatusCode.OK, runtime.prepareClose());
    }
    contender.join();

    assertEquals(StatusCode.CLOSED, acquired.get());
    assertNull(result.lease());
    assertEquals(StatusCode.OK, runtime.completeClose());
  }

  @Test
  void invalidCreateClearsReusedResult(@TempDir Path root) throws IOException {
    SqlDatabaseRuntime runtime = runtime(root);
    RiverRuntimeConfig config = runtime.config();
    assertEquals(StatusCode.OK, runtime.prepareClose());
    assertEquals(StatusCode.OK, runtime.completeClose());
    SqlDatabaseRuntime.OpenResult reused = new SqlDatabaseRuntime.OpenResult();

    assertEquals(
        StatusCode.OK,
        SqlDatabaseRuntime.create(
            config, root, DATABASE, reused, new StatusDetail(256)));
    SqlDatabaseRuntime created = reused.runtime();
    assertEquals(StatusCode.OK, created.prepareClose());
    assertEquals(StatusCode.OK, created.completeClose());
    assertEquals(
        StatusCode.INVALID_EXTERNAL_INPUT,
        SqlDatabaseRuntime.create(
            null, root, DATABASE, reused, new StatusDetail(256)));
    assertNull(reused.runtime());
  }

  @Test
  void reservationsAreDatabaseWideTransactionalAndReleasedOnClose(
      @TempDir Path root) throws IOException {
    SqlDatabaseRuntime runtime = runtime(root, 64_000_000L);
    SqlRuntimeLeaseResult result = new SqlRuntimeLeaseResult();
    assertEquals(8_000_000L, runtime.sessionShapeCacheBudgetBytes());

    assertEquals(StatusCode.OK, runtime.acquire(result));
    SqlRuntimeLease first = result.lease();
    assertEquals(StatusCode.OK, runtime.acquire(result));
    SqlRuntimeLease second = result.lease();
    assertEquals(StatusCode.OK, first.reserve(6_000_000L));
    assertEquals(StatusCode.RESOURCE_EXHAUSTED, first.reserve(3_000_000L));
    assertEquals(6_000_000L, first.reservedBytes());
    assertEquals(6_000_000L, runtime.reservedShapeBytes());
    assertEquals(StatusCode.OK, second.reserve(2_000_000L));
    assertEquals(StatusCode.RESOURCE_EXHAUSTED, second.reserve(1));
    assertEquals(2_000_000L, second.reservedBytes());
    assertEquals(8_000_000L, runtime.reservedShapeBytes());

    assertEquals(StatusCode.INVALID_EXTERNAL_INPUT, first.releaseReserved(0));
    assertEquals(StatusCode.INVALID_EXTERNAL_INPUT, first.releaseReserved(6_000_001L));
    assertEquals(6_000_000L, first.reservedBytes());
    assertEquals(8_000_000L, runtime.reservedShapeBytes());
    assertEquals(StatusCode.OK, first.releaseReserved(1_000_000L));
    assertEquals(StatusCode.OK, second.reserve(1_000_000L));
    assertEquals(StatusCode.OK, first.close());
    assertEquals(3_000_000L, runtime.reservedShapeBytes());
    assertEquals(StatusCode.CLOSED, first.close());
    assertEquals(3_000_000L, runtime.reservedShapeBytes());
    assertEquals(StatusCode.OK, second.close());
    assertEquals(0, runtime.reservedShapeBytes());
    assertEquals(StatusCode.OK, runtime.prepareClose());
    assertEquals(StatusCode.OK, runtime.completeClose());
  }

  private static SqlDatabaseRuntime runtime(Path root) throws IOException {
    return runtime(root, 2_048_000_000L);
  }

  private static SqlDatabaseRuntime runtime(Path root, long maximumMemoryBytes)
      throws IOException {
    RiverRuntimeConfig.Result configResult = new RiverRuntimeConfig.Result();
    assertEquals(
        StatusCode.OK,
        RiverRuntimeConfig.load(
            root,
            maximumMemoryBytes,
            root.toString(),
            configResult,
            new StatusDetail(512)));
    SqlDatabaseRuntime.OpenResult runtimeResult = new SqlDatabaseRuntime.OpenResult();
    assertEquals(
        StatusCode.OK,
        SqlDatabaseRuntime.create(
            configResult.config(), root, DATABASE, runtimeResult,
            new StatusDetail(256)));
    return runtimeResult.runtime();
  }
}
