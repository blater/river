package io.riverdb.engine.runtime.materialized;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.riverdb.base.error.StatusCode;
import io.riverdb.base.error.StatusDetail;
import io.riverdb.base.id.DatabaseIncarnation;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class SqlMaterializedScratchRuntimeTest {
  private static final DatabaseIncarnation DATABASE =
      DatabaseIncarnation.of(0x0102030405060708L, 0x8182838485868788L);

  @TempDir Path temporary;

  @Test
  void deterministicNamespaceSurvivesNormalCloseAndReopen() throws Exception {
    Path primary = Files.createDirectory(temporary.resolve("primary"));
    Path spill = Files.createDirectory(temporary.resolve("spill"));
    SqlMaterializedPagePool pool = pool();
    SqlMaterializedScratchRuntime first = runtime(spill, primary, pool);

    byte[] digest = MessageDigest.getInstance("SHA-256")
        .digest(primary.toRealPath().toString().getBytes(StandardCharsets.UTF_8));
    String expected = "river-sql-01020304050607088182838485868788-"
        + HexFormat.of().formatHex(digest, 0, 8);
    assertEquals(expected, first.namespacePath().getFileName().toString());
    assertEquals(first.namespacePath(), first.instancePath().getParent());

    Path firstInstance = first.instancePath();
    Path namespace = first.namespacePath();
    assertEquals(StatusCode.OK, first.close(new StatusDetail(256)));
    assertEquals(StatusCode.OK, first.close(new StatusDetail(256)));
    assertFalse(Files.exists(firstInstance));
    SqlMaterializedScratchRuntime second = runtime(spill, primary, pool);
    assertEquals(namespace, second.namespacePath());
    assertNotEquals(firstInstance, second.instancePath());
    assertEquals(StatusCode.OK, second.close(new StatusDetail(256)));
    assertTrue(Files.isDirectory(namespace));
    assertEquals(StatusCode.OK, pool.close());
  }

  @Test
  void concurrentOpenIsRejectedWithPreciseConflict() throws Exception {
    Path primary = Files.createDirectory(temporary.resolve("primary-owned"));
    Path spill = Files.createDirectory(temporary.resolve("spill-owned"));
    SqlMaterializedPagePool pool = pool();
    SqlMaterializedScratchRuntime first = runtime(spill, primary, pool);
    Path retained = Files.createDirectory(first.namespacePath().resolve("open-retained"));
    SqlMaterializedScratchRuntime.OpenResult result =
        new SqlMaterializedScratchRuntime.OpenResult();
    StatusDetail detail = new StatusDetail(256);

    assertEquals(
        StatusCode.CONFLICT,
        SqlMaterializedScratchRuntime.create(
            spill, primary, DATABASE, pool, result, detail));
    assertNull(result.runtime());
    assertTrue(detail.asString().contains("scratch namespace is already owned"));
    assertTrue(Files.isDirectory(retained));

    assertEquals(StatusCode.OK, first.close(detail));
    assertEquals(StatusCode.OK, pool.close());
  }

  @Test
  void openReclaimsStaleInstancesAfterTakingOwnership() throws Exception {
    Path primary = Files.createDirectory(temporary.resolve("primary-stale"));
    Path spill = Files.createDirectory(temporary.resolve("spill-stale"));
    SqlMaterializedPagePool pool = pool();
    SqlMaterializedScratchRuntime first = runtime(spill, primary, pool);
    Path namespace = first.namespacePath();
    assertEquals(StatusCode.OK, first.close(new StatusDetail(256)));
    Path stale = Files.createDirectories(namespace.resolve("open-stale/query-old"));
    Files.write(stale.resolve("data.rows"), new byte[] {1, 2, 3});

    SqlMaterializedScratchRuntime reopened = runtime(spill, primary, pool);

    assertFalse(Files.exists(namespace.resolve("open-stale")));
    assertTrue(Files.isDirectory(reopened.instancePath()));
    assertEquals(StatusCode.OK, reopened.close(new StatusDetail(256)));
    assertEquals(StatusCode.OK, pool.close());
  }

  @Test
  void staleSymlinkIsDeletedWithoutFollowingIt() throws Exception {
    Path primary = Files.createDirectory(temporary.resolve("primary-stale-link"));
    Path spill = Files.createDirectory(temporary.resolve("spill-stale-link"));
    Path external = Files.createDirectory(temporary.resolve("external-stale-link"));
    Path marker = Files.write(external.resolve("marker"), new byte[] {1});
    SqlMaterializedPagePool pool = pool();
    SqlMaterializedScratchRuntime first = runtime(spill, primary, pool);
    Path namespace = first.namespacePath();
    assertEquals(StatusCode.OK, first.close(new StatusDetail(256)));
    Path staleLink = namespace.resolve("open-stale-link");
    Files.createSymbolicLink(staleLink, external);

    SqlMaterializedScratchRuntime reopened = runtime(spill, primary, pool);

    assertFalse(Files.exists(staleLink, java.nio.file.LinkOption.NOFOLLOW_LINKS));
    assertTrue(Files.exists(marker));
    assertEquals(StatusCode.OK, reopened.close(new StatusDetail(256)));
    assertEquals(StatusCode.OK, pool.close());
  }

  @Test
  void namespaceRejectsAStoredSymlinkInsteadOfFollowingIt() throws Exception {
    Path primary = Files.createDirectory(temporary.resolve("primary-link-namespace"));
    Path spill = Files.createDirectory(temporary.resolve("spill-link-namespace"));
    Path external = Files.createDirectory(temporary.resolve("external-namespace"));
    SqlMaterializedPagePool pool = pool();
    SqlMaterializedScratchRuntime first = runtime(spill, primary, pool);
    Path namespace = first.namespacePath();
    assertEquals(StatusCode.OK, first.close(new StatusDetail(256)));
    Files.delete(namespace.resolve(".owner.lock"));
    Files.delete(namespace);
    Files.createSymbolicLink(namespace, external);

    SqlMaterializedScratchRuntime.OpenResult result =
        new SqlMaterializedScratchRuntime.OpenResult();
    StatusDetail detail = new StatusDetail(256);
    assertEquals(
        StatusCode.IO_FAILURE,
        SqlMaterializedScratchRuntime.create(
            spill, primary, DATABASE, pool, result, detail));
    assertNull(result.runtime());
    try (var contents = Files.list(external)) {
      assertEquals(0, contents.count());
    }
    assertEquals(StatusCode.OK, pool.close());
  }

  @Test
  void ownerRetainsCreateNewChannelsAndCleansAllPaths() throws Exception {
    Path primary = Files.createDirectory(temporary.resolve("database"));
    Path spill = Files.createDirectory(temporary.resolve("scratch"));
    SqlMaterializedPagePool pool = pool();
    SqlMaterializedScratchRuntime runtime = runtime(spill, primary, pool);
    SqlMaterializedScratchOwner owner = owner(runtime);
    SqlMaterializedScratchStore store = store(owner);
    SqlMaterializedScratchFile.Result fileResult = new SqlMaterializedScratchFile.Result();
    StatusDetail detail = new StatusDetail(256);
    long previousIdentity = 0;

    for (SqlMaterializedScratchFileKind kind : SqlMaterializedScratchFileKind.values()) {
      assertEquals(StatusCode.OK, store.open(kind, fileResult, detail));
      SqlMaterializedScratchFile file = fileResult.file();
      assertEquals(owner.identity(), file.ownerIdentity());
      assertTrue(file.fileIdentity() > previousIdentity);
      assertTrue(file.channel().isOpen());
      assertEquals(store.path(), file.path().getParent());
      previousIdentity = file.fileIdentity();
    }
    assertEquals(
        StatusCode.CONFLICT,
        store.open(SqlMaterializedScratchFileKind.ROWS, fileResult, detail));
    assertNull(fileResult.file());

    Path ownerPath = owner.path();
    assertEquals(StatusCode.OK, owner.close(detail));
    assertTrue(owner.isClosed());
    assertFalse(Files.exists(ownerPath));
    assertEquals(StatusCode.OK, runtime.close(detail));
    assertEquals(StatusCode.OK, pool.close());
  }

  @Test
  void existingRuntimeNameIsNeverOpenedOrReplaced() throws Exception {
    Path primary = Files.createDirectory(temporary.resolve("db-create-new"));
    Path spill = Files.createDirectory(temporary.resolve("spill-create-new"));
    SqlMaterializedPagePool pool = pool();
    SqlMaterializedScratchRuntime runtime = runtime(spill, primary, pool);
    SqlMaterializedScratchOwner owner = owner(runtime);
    SqlMaterializedScratchStore store = store(owner);
    Path rows = store.path().resolve("data.rows");
    Files.write(rows, new byte[] {7, 9});

    SqlMaterializedScratchFile.Result result = new SqlMaterializedScratchFile.Result();
    StatusDetail detail = new StatusDetail(256);
    assertEquals(
        StatusCode.IO_FAILURE,
        store.open(SqlMaterializedScratchFileKind.ROWS, result, detail));
    assertNull(result.file());
    assertEquals(2, Files.size(rows));

    assertEquals(StatusCode.OK, owner.close(detail));
    assertEquals(StatusCode.OK, runtime.close(detail));
    assertEquals(StatusCode.OK, pool.close());
  }

  @Test
  void ownerCloseInvalidatesPinnedPagesWithoutWriteback() throws Exception {
    Path primary = Files.createDirectory(temporary.resolve("db-pinned"));
    Path spill = Files.createDirectory(temporary.resolve("spill-pinned"));
    SqlMaterializedPagePool pool = pool();
    SqlMaterializedScratchRuntime runtime = runtime(spill, primary, pool);
    SqlMaterializedScratchOwner owner = owner(runtime);
    SqlMaterializedScratchStore store = store(owner);
    SqlMaterializedScratchFile.Result fileResult = new SqlMaterializedScratchFile.Result();
    StatusDetail detail = new StatusDetail(256);
    assertEquals(StatusCode.OK, store.open(SqlMaterializedScratchFileKind.ROWS, fileResult, detail));
    SqlMaterializedPagePin pin = new SqlMaterializedPagePin();
    assertEquals(StatusCode.OK, owner.pinNew(fileResult.file(), 0, pin));
    pin.buffer().putLong(0, 77);
    assertEquals(StatusCode.OK, owner.markDirty(pin));

    assertEquals(StatusCode.INVARIANT_BROKEN, owner.close(detail));
    assertFalse(Files.exists(owner.path()));
    assertEquals(
        StatusCode.CLOSED,
        owner.pinNew(fileResult.file(), 1, new SqlMaterializedPagePin()));
    assertEquals(StatusCode.OK, owner.unpin(pin));
    assertFalse(pin.active());
    assertEquals(StatusCode.OK, owner.close(detail));
    assertEquals(StatusCode.OK, runtime.close(detail));
    assertEquals(StatusCode.OK, pool.close());
  }

  @Test
  void ownerCloseGateRejectsAWaitingPin() throws Exception {
    Path primary = Files.createDirectory(temporary.resolve("db-close-gate"));
    Path spill = Files.createDirectory(temporary.resolve("spill-close-gate"));
    SqlMaterializedPagePool pool = pool();
    SqlMaterializedScratchRuntime runtime = runtime(spill, primary, pool);
    SqlMaterializedScratchOwner owner = owner(runtime);
    SqlMaterializedScratchStore store = store(owner);
    SqlMaterializedScratchFile.Result fileResult = new SqlMaterializedScratchFile.Result();
    StatusDetail detail = new StatusDetail(256);
    assertEquals(StatusCode.OK, store.open(SqlMaterializedScratchFileKind.ROWS, fileResult, detail));
    CountDownLatch attempting = new CountDownLatch(1);
    AtomicReference<StatusCode> pinStatus = new AtomicReference<>();
    Thread pin = new Thread(() -> {
      attempting.countDown();
      pinStatus.set(owner.pinNew(fileResult.file(), 0, new SqlMaterializedPagePin()));
    });

    synchronized (owner) {
      pin.start();
      attempting.await();
      assertEquals(StatusCode.OK, owner.close(detail));
    }
    pin.join();

    assertEquals(StatusCode.CLOSED, pinStatus.get());
    assertEquals(StatusCode.OK, runtime.close(detail));
    assertEquals(StatusCode.OK, pool.close());
  }

  @Test
  void recursiveCleanupDeletesSymlinkWithoutFollowingIt() throws Exception {
    Path primary = Files.createDirectory(temporary.resolve("db-link"));
    Path spill = Files.createDirectory(temporary.resolve("spill-link"));
    Path external = Files.createDirectory(temporary.resolve("external"));
    Path marker = Files.write(external.resolve("marker"), new byte[] {1});
    SqlMaterializedPagePool pool = pool();
    SqlMaterializedScratchRuntime runtime = runtime(spill, primary, pool);
    SqlMaterializedScratchOwner owner = owner(runtime);
    Files.createSymbolicLink(owner.path().resolve("outside"), external);

    assertEquals(StatusCode.OK, owner.close(new StatusDetail(256)));
    assertTrue(Files.exists(marker));
    assertEquals(StatusCode.OK, runtime.close(new StatusDetail(256)));
    assertEquals(StatusCode.OK, pool.close());
  }

  @Test
  void cleanupPreservesFirstFailureAndAppendsLaterPath() {
    StatusDetail detail = new StatusDetail(256);
    SqlMaterializedScratchCleanup.State state =
        new SqlMaterializedScratchCleanup.State().begin(detail);
    Path first = Path.of("first-path");
    Path second = Path.of("second-path");

    state.record(StatusCode.INVARIANT_BROKEN, first);
    state.record(StatusCode.IO_FAILURE, second);

    assertEquals(StatusCode.INVARIANT_BROKEN, state.status());
    assertEquals(StatusCode.INVARIANT_BROKEN, detail.code());
    assertTrue(detail.asString().contains(first.toString()));
    assertTrue(detail.asString().contains(second.toString()));
  }

  private SqlMaterializedScratchRuntime runtime(
      Path spill, Path primary, SqlMaterializedPagePool pool) {
    SqlMaterializedScratchRuntime.OpenResult result =
        new SqlMaterializedScratchRuntime.OpenResult();
    StatusDetail detail = new StatusDetail(256);
    assertEquals(
        StatusCode.OK,
        SqlMaterializedScratchRuntime.create(spill, primary, DATABASE, pool, result, detail),
        detail::asString);
    assertNotNull(result.runtime());
    return result.runtime();
  }

  private SqlMaterializedScratchOwner owner(SqlMaterializedScratchRuntime runtime) {
    SqlMaterializedScratchOwner.Result result = new SqlMaterializedScratchOwner.Result();
    StatusDetail detail = new StatusDetail(256);
    assertEquals(StatusCode.OK, runtime.openOwner(result, detail), detail::asString);
    assertTrue(result.owner().identity() > 0);
    return result.owner();
  }

  private SqlMaterializedScratchStore store(SqlMaterializedScratchOwner owner) {
    SqlMaterializedScratchStore.Result result = new SqlMaterializedScratchStore.Result();
    StatusDetail detail = new StatusDetail(256);
    assertEquals(StatusCode.OK, owner.openStore(result, detail), detail::asString);
    return result.store();
  }

  private SqlMaterializedPagePool pool() {
    SqlMaterializedPagePoolResult result = new SqlMaterializedPagePoolResult();
    assertEquals(StatusCode.OK, SqlMaterializedPagePool.create(64, 8, result));
    return result.pool();
  }
}
