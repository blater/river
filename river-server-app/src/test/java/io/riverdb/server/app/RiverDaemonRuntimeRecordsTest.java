package io.riverdb.server.app;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.riverdb.base.error.StatusCode;
import io.riverdb.base.id.DatabaseIncarnation;
import io.riverdb.platform.riverd.RiverDaemonFileSystem;
import io.riverdb.platform.riverd.RiverDirectory;
import io.riverdb.platform.riverd.RiverDirectoryResult;
import io.riverdb.platform.riverd.RiverFile;
import io.riverdb.platform.riverd.RiverFileResult;
import io.riverdb.platform.riverd.RiverOpenMode;
import io.riverdb.platform.riverd.apfs.ApfsRiverDaemonFileSystem;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.security.SecureRandom;
import java.util.List;
import java.util.Set;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

@EnabledOnOs(OS.MAC)
final class RiverDaemonRuntimeRecordsTest {
  private static final DatabaseIncarnation INCARNATION = DatabaseIncarnation.of(17, 29);
  private static final Set<PosixFilePermission> PRIVATE_DIRECTORY = Set.of(
      PosixFilePermission.OWNER_READ,
      PosixFilePermission.OWNER_WRITE,
      PosixFilePermission.OWNER_EXECUTE);

  @Test
  void publishesAndCleansMatchingRuntimeRegistryAndReadyRecords(@TempDir Path root) throws Exception {
    Fixture fixture = fixture(root, true);
    try {
      for (int index = 0; index < 32; index++) {
        Files.writeString(
            fixture.readyPath.getParent().resolve("unrelated-" + index), "preserve");
      }
      assertEquals(StatusCode.OK, RiverDaemonRuntimeRecords.publishRuntime(
          fixture.identity.directory(), fixture.metadata));
      assertEquals(StatusCode.OK, RiverDaemonRuntimeRecords.publishRegistry(
          fixture.registry, fixture.metadata));
      assertEquals(StatusCode.OK, RiverDaemonRuntimeRecords.publishReady(
          fixture.filesystem, fixture.registryRoot, fixture.metadata, fixture.readyPath,
          "a".repeat(64)));
      assertTrue(Files.exists(fixture.datadir.resolve("runtime.properties")));
      assertTrue(Files.exists(fixture.registryRoot.resolve(fixture.registryName)));
      assertTrue(Files.exists(fixture.readyPath));

      assertEquals(StatusCode.OK, RiverDaemonRuntimeRecords.cleanupCurrent(
          fixture.filesystem, fixture.identity, fixture.registryRoot, fixture.metadata));
      assertFalse(Files.exists(fixture.datadir.resolve("runtime.properties")));
      assertFalse(Files.exists(fixture.registryRoot.resolve(fixture.registryName)));
      assertFalse(Files.exists(fixture.readyPath));
      for (int index = 0; index < 32; index++) {
        assertTrue(Files.exists(
            fixture.readyPath.getParent().resolve("unrelated-" + index)));
      }
    } finally {
      fixture.close();
    }
  }

  @Test
  void mismatchedReadyRecordPreservesAllCurrentRecords(@TempDir Path root) throws Exception {
    Fixture fixture = fixture(root, true);
    try {
      assertEquals(StatusCode.OK, RiverDaemonRuntimeRecords.publishRuntime(
          fixture.identity.directory(), fixture.metadata));
      assertEquals(StatusCode.OK, RiverDaemonRuntimeRecords.publishRegistry(
          fixture.registry, fixture.metadata));
      assertEquals(StatusCode.OK, RiverDaemonRuntimeRecords.publishReady(
          fixture.filesystem, fixture.registryRoot, fixture.metadata, fixture.readyPath,
          "a".repeat(64)));
      Files.writeString(fixture.readyPath, mismatchedReady(fixture));

      assertEquals(StatusCode.CORRUPTION, RiverDaemonRuntimeRecords.cleanupCurrent(
          fixture.filesystem, fixture.identity, fixture.registryRoot, fixture.metadata));
      assertTrue(Files.exists(fixture.datadir.resolve("runtime.properties")));
      assertTrue(Files.exists(fixture.registryRoot.resolve(fixture.registryName)));
      assertTrue(Files.exists(fixture.readyPath));
    } finally {
      fixture.close();
    }
  }

  @Test
  void cleanupWithNoRecordsIsIdempotent(@TempDir Path root) throws Exception {
    Fixture fixture = fixture(root, false);
    try {
      assertEquals(StatusCode.OK, RiverDaemonRuntimeRecords.cleanupCurrent(
          fixture.filesystem, fixture.identity, fixture.registryRoot, fixture.metadata));
      assertEquals(StatusCode.OK, RiverDaemonRuntimeRecords.cleanupCurrent(
          fixture.filesystem, fixture.identity, fixture.registryRoot, fixture.metadata));
    } finally {
      fixture.close();
    }
  }

  @Test
  void stopRequestTimesOutWithoutOwnerControlAndLeavesRuntime(@TempDir Path root) throws Exception {
    Fixture fixture = fixture(root, false);
    RiverDaemonTarget target = null;
    try {
      assertEquals(StatusCode.OK, RiverDaemonRuntimeRecords.publishRuntime(
          fixture.identity.directory(), fixture.metadata));
      RiverDaemonTarget.Result targetResult = new RiverDaemonTarget.Result();
      assertEquals(StatusCode.OK, RiverDaemonTarget.open(
          fixture.filesystem, fixture.datadir, targetResult));
      target = targetResult.target();

      assertEquals(StatusCode.TIMEOUT, RiverDaemonStop.request(target, 100));
      assertTrue(Files.exists(fixture.datadir.resolve("runtime.properties")));
      assertNoStopControls(fixture.datadir);
    } finally {
      if (target != null) target.close();
      fixture.close();
    }
  }

  @Test
  void stopRequestReportsOwnerExitBeforeAcceptance(@TempDir Path root) throws Exception {
    Fixture fixture = fixture(root, false);
    RiverDaemonTarget target = null;
    FutureTask<StatusCode> caller = null;
    try {
      assertEquals(StatusCode.OK, RiverDaemonRuntimeRecords.publishRuntime(
          fixture.identity.directory(), fixture.metadata));
      RiverDaemonTarget.Result targetResult = new RiverDaemonTarget.Result();
      assertEquals(StatusCode.OK, RiverDaemonTarget.open(
          fixture.filesystem, fixture.datadir, targetResult));
      target = targetResult.target();
      RiverDaemonTarget openedTarget = target;
      caller = new FutureTask<>(() -> RiverDaemonStop.request(openedTarget, 2_000));
      Thread requestThread = new Thread(caller, "river-stop-owner-exit-test");
      requestThread.start();
      awaitPath(fixture.datadir.resolve(RiverDaemonStopRequest.REQUEST_NAME));

      assertEquals(StatusCode.OK, fixture.identity.close());
      assertEquals(StatusCode.NOT_OWNER, caller.get(2, TimeUnit.SECONDS));
      requestThread.join(2_000);
      assertNoStopControls(fixture.datadir);
      assertTrue(Files.exists(fixture.datadir.resolve(RiverDaemonRuntimeRecords.RUNTIME_NAME)));
    } finally {
      if (caller != null && !caller.isDone()) caller.cancel(true);
      if (target != null) target.close();
      fixture.close();
    }
  }

  @Test
  void targetAllowsLockHeldRevalidationAfterRuntimeRemoval(@TempDir Path root) throws Exception {
    Fixture fixture = fixture(root, false);
    RiverDaemonTarget target = null;
    try {
      assertEquals(StatusCode.OK, RiverDaemonRuntimeRecords.publishRuntime(
          fixture.identity.directory(), fixture.metadata));
      RiverDaemonTarget.Result targetResult = new RiverDaemonTarget.Result();
      assertEquals(StatusCode.OK, RiverDaemonTarget.open(
          fixture.filesystem, fixture.datadir, targetResult));
      target = targetResult.target();
      Files.delete(fixture.datadir.resolve("runtime.properties"));

      assertEquals(StatusCode.OK, target.revalidate(false));
      assertEquals(StatusCode.NOT_OWNER, target.revalidate(true));
    } finally {
      if (target != null) target.close();
      fixture.close();
    }
  }

  @Test
  void stalePendingStopIsReclaimedAfterRestartWithoutReplay(@TempDir Path root) throws Exception {
    Fixture fixture = fixture(root, false, 999_999_999L, 0L, "/usr/bin/riverd");
    RiverDaemonTarget target = null;
    RiverDaemonIdentity.IdentityResult restarted = null;
    try {
      assertEquals(StatusCode.OK, RiverDaemonRuntimeRecords.publishRuntime(
          fixture.identity.directory(), fixture.metadata));
      RiverDaemonTarget.Result targetResult = new RiverDaemonTarget.Result();
      assertEquals(StatusCode.OK, RiverDaemonTarget.open(
          fixture.filesystem, fixture.datadir, targetResult));
      target = targetResult.target();
      RiverFileResult requestResult = new RiverFileResult();
      assertEquals(StatusCode.OK, fixture.identity.directory().openFile(
          RiverDaemonStopRequest.REQUEST_NAME, RiverOpenMode.CREATE_NEW, requestResult));
      RiverFile requestFile = requestResult.file();
      try {
        byte[] request = RiverDaemonStopRequest.encode(
            target.owner.high, target.owner.low, target.owner.nonce,
            "22222222222222222222222222222222", target.runtimeChecksum,
            System.currentTimeMillis()).getBytes(StandardCharsets.UTF_8);
        assertEquals(StatusCode.OK, RiverDaemonRuntimeRecords.write(requestFile, request));
      } finally {
        assertEquals(StatusCode.OK, requestFile.close());
      }
      assertEquals(StatusCode.OK, RiverDaemonRuntimeRecords.force(fixture.identity.directory()));
      assertTrue(Files.exists(fixture.datadir.resolve(RiverDaemonStopRequest.REQUEST_NAME)));
      assertEquals(StatusCode.OK, target.close());
      target = null;
      assertEquals(StatusCode.OK, fixture.identity.close());

      restarted = new RiverDaemonIdentity.IdentityResult();
      assertEquals(StatusCode.OK, RiverDaemonIdentity.openExisting(
          fixture.datadir, fixture.filesystem, new SecureRandom(), currentPid(), currentStart(),
          currentCommand(), restarted));
      assertEquals(StatusCode.OK, RiverDaemonStop.recoverStale(fixture.filesystem, restarted));
      assertFalse(Files.exists(fixture.datadir.resolve(RiverDaemonStopRequest.REQUEST_NAME)));
      assertFalse(Files.exists(fixture.datadir.resolve(
          RiverDaemonStopRequest.ACCEPTED_PREFIX + "22222222222222222222222222222222")));
      assertTrue(Files.exists(fixture.datadir.resolve(RiverDaemonRuntimeRecords.RUNTIME_NAME)));
    } finally {
      if (restarted != null) restarted.close();
      if (target != null) target.close();
      fixture.close();
    }
  }

  @Test
  void acceptedStopCanJoinAfterRuntimeCleanupUntilOwnerReleases(@TempDir Path root)
      throws Exception {
    Fixture fixture = fixture(root, false);
    RiverDaemonTarget target = null;
    FutureTask<StatusCode> caller = null;
    Path foreignStage = fixture.datadir.resolve(
        ".stop-request-11111111111111111111111111111111.stage");
    try {
      assertEquals(StatusCode.OK, RiverDaemonRuntimeRecords.publishRuntime(
          fixture.identity.directory(), fixture.metadata));
      Files.createFile(foreignStage);
      RiverDaemonTarget.Result targetResult = new RiverDaemonTarget.Result();
      assertEquals(StatusCode.OK, RiverDaemonTarget.open(
          fixture.filesystem, fixture.datadir, targetResult));
      target = targetResult.target();
      RiverDaemonStop.Control control = new RiverDaemonStop.Control(
          fixture.filesystem, fixture.identity, fixture.metadata);
      RiverDaemonTarget openedTarget = target;
      caller = new FutureTask<>(() -> RiverDaemonStop.request(openedTarget, 2_000));
      Thread requestThread = new Thread(caller, "river-stop-join-test");
      requestThread.start();
      awaitPath(fixture.datadir.resolve("stop.request"));

      assertEquals(StatusCode.CANCELLED, control.poll());
      awaitEntryPrefix(fixture.datadir, ".stop-accepted-");
      assertTrue(Files.exists(foreignStage));
      assertEquals(0, Files.size(foreignStage));
      Files.delete(fixture.datadir.resolve("runtime.properties"));
      assertEquals(StatusCode.OK, control.cleanup());
      assertFalse(caller.isDone());

      assertEquals(StatusCode.OK, fixture.identity.close());
      assertEquals(StatusCode.OK, caller.get(2, TimeUnit.SECONDS));
      requestThread.join(2_000);
      assertNoStopControlsExcept(fixture.datadir, foreignStage);
    } finally {
      if (caller != null && !caller.isDone()) caller.cancel(true);
      if (target != null) target.close();
      fixture.close();
    }
  }

  private static void awaitPath(Path path) throws Exception {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
    while (!Files.exists(path) && System.nanoTime() < deadline) {
      Thread.sleep(10);
    }
    assertTrue(Files.exists(path), "expected path: " + path);
  }

  private static void awaitEntryPrefix(Path directory, String prefix) throws Exception {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
    while (System.nanoTime() < deadline) {
      try (var entries = Files.newDirectoryStream(directory)) {
        for (Path entry : entries) {
          if (entry.getFileName().toString().startsWith(prefix)) return;
        }
      }
      Thread.sleep(10);
    }
    assertTrue(false, "expected entry prefix: " + prefix);
  }

  private static void assertNoStopControls(Path datadir) throws Exception {
    assertNoStopControlsExcept(datadir, null);
  }

  private static void assertNoStopControlsExcept(Path datadir, Path preserved)
      throws Exception {
    try (var entries = Files.newDirectoryStream(datadir)) {
      for (Path entry : entries) {
        String name = entry.getFileName().toString();
        if (entry.equals(preserved)) continue;
        assertFalse("stop.request".equals(name)
            || name.startsWith(".stop-request-")
            || name.startsWith(".stop-accepted-"), "unexpected stop control: " + entry);
      }
    }
  }

  private static Fixture fixture(Path root, boolean withReady) throws Exception {
    return fixture(root, withReady, currentPid(), currentStart(), currentCommand());
  }

  private static Fixture fixture(Path root, boolean withReady, long ownerPid, long ownerStart,
      String ownerCommand) throws Exception {
    Path canonicalRoot = root.toRealPath();
    Files.setPosixFilePermissions(canonicalRoot, PRIVATE_DIRECTORY);
    Path datadir = canonicalRoot.resolve("instance");
    Path registryRoot = canonicalRoot.resolve("registry");
    Files.createDirectory(registryRoot);
    Files.setPosixFilePermissions(registryRoot, PRIVATE_DIRECTORY);
    Path readyParent = canonicalRoot.resolve("ready");
    Files.createDirectory(readyParent);
    Files.setPosixFilePermissions(readyParent, PRIVATE_DIRECTORY);
    Path readyPath = readyParent.resolve("riverd.ready");

    RiverDaemonFileSystem filesystem = new ApfsRiverDaemonFileSystem();
    RiverDaemonIdentity.IdentityResult identity = new RiverDaemonIdentity.IdentityResult();
    assertEquals(StatusCode.OK, RiverDaemonIdentity.beginCreate(
        datadir, filesystem, INCARNATION, new SecureRandom(), ownerPid, ownerStart, ownerCommand,
        identity));
    assertEquals(StatusCode.OK, RiverDaemonIdentity.completeCreate(identity));
    RiverDaemonIdentityRecords.LockRecord owner = RiverDaemonIdentityRecords.parseLock(
        Files.readAllBytes(datadir.resolve(RiverDaemonIdentity.LOCK_FILE)));
    RiverDirectoryResult registryResult = new RiverDirectoryResult();
    assertEquals(StatusCode.OK, filesystem.openDirectory(registryRoot, registryResult));
    RiverDirectory registry = registryResult.directory();
    RiverDaemonRuntimeRecords.Metadata metadata = new RiverDaemonRuntimeRecords.Metadata(
        datadir.toString(), INCARNATION, owner, "localhost", 4321, 1, "test-version",
        canonicalRoot.resolve("client.properties").toString(), withReady ? readyPath : null);
    return new Fixture(filesystem, identity, registry, registryRoot, datadir,
        withReady ? readyPath : null, metadata,
        RiverDaemonRuntimeRecordsTest.registryName(datadir.toString()));
  }

  private static String mismatchedReady(Fixture fixture) {
    Path datadir = fixture.datadir;
    return RiverDaemonIdentityRecords.record(List.of(
        "format=riverd-ready-v1",
        "datadir=" + datadir,
        "database-incarnation-high=" + INCARNATION.high(),
        "database-incarnation-low=" + INCARNATION.low(),
        "data=" + datadir.resolve("database"),
        "identity=" + datadir.resolve("instance.properties"),
        "runtime-file=" + datadir.resolve("runtime.properties"),
        "registry-record=" + fixture.registryRoot.resolve(fixture.registryName),
        "listen-address=localhost",
        "listen-port=4321",
        "pid=" + currentPid(),
        "protocol=river-v4",
        "transport=tls-v1.3",
        "client-config=" + datadir.resolve("other-client.properties"),
        "server-certificate-sha256=" + "a".repeat(64),
        "owner-nonce=" + fixture.metadata.owner.nonce,
        "status=ready"));
  }

  private static String registryName(String datadir) {
    return java.util.HexFormat.of().formatHex(
        digest(datadir.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
  }

  private static byte[] digest(byte[] value) {
    try {
      return java.security.MessageDigest.getInstance("SHA-256").digest(value);
    } catch (java.security.NoSuchAlgorithmException failure) {
      throw new AssertionError(failure);
    }
  }

  private static long currentPid() {
    return ProcessHandle.current().pid();
  }

  private static long currentStart() {
    return ProcessHandle.current().info().startInstant().orElseThrow().toEpochMilli();
  }

  private static String currentCommand() {
    return ProcessHandle.current().info().command().orElseThrow();
  }

  private static final class Fixture implements AutoCloseable {
    final RiverDaemonFileSystem filesystem;
    final RiverDaemonIdentity.IdentityResult identity;
    final RiverDirectory registry;
    final Path registryRoot;
    final Path datadir;
    final Path readyPath;
    final RiverDaemonRuntimeRecords.Metadata metadata;
    final String registryName;

    Fixture(RiverDaemonFileSystem filesystem, RiverDaemonIdentity.IdentityResult identity,
        RiverDirectory registry, Path registryRoot, Path datadir, Path readyPath,
        RiverDaemonRuntimeRecords.Metadata metadata, String registryName) {
      this.filesystem = filesystem;
      this.identity = identity;
      this.registry = registry;
      this.registryRoot = registryRoot;
      this.datadir = datadir;
      this.readyPath = readyPath;
      this.metadata = metadata;
      this.registryName = registryName;
    }

    @Override
    public void close() {
      registry.close();
      identity.close();
    }
  }
}
