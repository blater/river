package io.riverdb.server.app;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.riverdb.base.error.StatusCode;
import io.riverdb.base.id.DatabaseIncarnation;
import io.riverdb.platform.file.DirectoryListResult;
import io.riverdb.platform.file.DirectoryOperationResult;
import io.riverdb.platform.file.ForceMode;
import io.riverdb.platform.file.FileSizeResult;
import io.riverdb.platform.file.IoResult;
import io.riverdb.platform.riverd.FileIdentity;
import io.riverdb.platform.riverd.RiverDaemonFileSystem;
import io.riverdb.platform.riverd.RiverDirectory;
import io.riverdb.platform.riverd.RiverDirectoryResult;
import io.riverdb.platform.riverd.RiverFile;
import io.riverdb.platform.riverd.RiverFileResult;
import io.riverdb.platform.riverd.RiverLockResult;
import io.riverdb.platform.riverd.RiverOpenMode;
import io.riverdb.platform.riverd.apfs.ApfsRiverDaemonFileSystem;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.HexFormat;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class RiverDaemonIdentityTest {
  private static final DatabaseIncarnation INCARNATION = DatabaseIncarnation.of(17, 29);

  @Test
  void identityCodecRejectsNoncanonicalNumbersAndMalformedUtf8WithValidChecksum() throws Exception {
    byte[] negativeZero = RiverDaemonIdentityRecords.record(java.util.List.of(
        "format=riverd-instance-v1",
        "database-incarnation-high=-0",
        "database-incarnation-low=29",
        "initial-wal-generation=1")).getBytes(StandardCharsets.UTF_8);
    assertNull(RiverDaemonIdentityRecords.parseInstance(negativeZero));

    byte[] malformedPrefix = ("format=riverd-instance-v1\n"
        + "database-incarnation-high=17\n"
        + "database-incarnation-low=29\n"
        + "initial-wal-generation=1\n").getBytes(StandardCharsets.UTF_8);
    int highValue = new String(malformedPrefix, StandardCharsets.UTF_8).indexOf("high=17") + 5;
    malformedPrefix[highValue] = (byte) 0xc3;
    byte[] digest = MessageDigest.getInstance("SHA-256").digest(malformedPrefix);
    byte[] suffix = ("record-sha256=" + HexFormat.of().formatHex(digest) + "\n")
        .getBytes(StandardCharsets.UTF_8);
    byte[] malformed = Arrays.copyOf(malformedPrefix, malformedPrefix.length + suffix.length);
    System.arraycopy(suffix, 0, malformed, malformedPrefix.length, suffix.length);
    assertNull(RiverDaemonIdentityRecords.parseInstance(malformed));
  }

  @Test
  void restartHandoffRetainsPriorRecordThenPublishesCurrentOwnerThroughApfs(@TempDir Path root)
      throws Exception {
    Assumptions.assumeTrue("Mac OS X".equals(System.getProperty("os.name")));
    Path datadir = root.toRealPath().resolve("instance");
    ApfsRiverDaemonFileSystem filesystem = new ApfsRiverDaemonFileSystem();
    RiverDaemonIdentity.IdentityResult created = new RiverDaemonIdentity.IdentityResult();
    assertEquals(StatusCode.OK, RiverDaemonIdentity.beginCreate(
        datadir, filesystem, INCARNATION, new SecureRandom(), 999_999_999L, 0,
        created));
    assertEquals(StatusCode.OK, RiverDaemonIdentity.completeCreate(created));
    assertEquals(StatusCode.OK, created.close());
    Path lockPath = datadir.resolve(RiverDaemonIdentity.LOCK_FILE);
    String priorBytes = Files.readString(lockPath);

    RiverDaemonIdentity.IdentityResult reopened = new RiverDaemonIdentity.IdentityResult();
    try {
      assertEquals(StatusCode.OK, RiverDaemonIdentity.openExisting(
          datadir, filesystem, new SecureRandom(), currentPid(), currentStart(),
          reopened));
      assertEquals(INCARNATION, reopened.incarnation());
      assertTrue(reopened.needsOwnerHandoff());
      assertEquals(priorBytes, Files.readString(lockPath));
      assertEquals(StatusCode.OK, RiverDaemonIdentity.handoffOwner(reopened));
      RiverDaemonIdentityRecords.LockRecord current = RiverDaemonIdentityRecords.parseLock(
          Files.readAllBytes(lockPath));
      assertEquals(currentPid(), current.pid);
      assertEquals(currentStart(), current.start);
      assertEquals(INCARNATION.high(), current.high);
      assertEquals(INCARNATION.low(), current.low);
      assertTrue(!priorBytes.equals(Files.readString(lockPath)));
    } finally {
      reopened.close();
    }
  }

  @Test
  void interruptedFirstCreateResumesRecordedIdentityThroughApfs(@TempDir Path root) throws Exception {
    Assumptions.assumeTrue("Mac OS X".equals(System.getProperty("os.name")));
    Path datadir = root.toRealPath().resolve("instance");
    ApfsRiverDaemonFileSystem filesystem = new ApfsRiverDaemonFileSystem();
    RiverDaemonIdentity.IdentityResult first = new RiverDaemonIdentity.IdentityResult();
    assertEquals(StatusCode.OK, RiverDaemonIdentity.beginCreate(
        datadir, filesystem, INCARNATION, new SecureRandom(), 999_999_999L, 0,
        first));
    assertEquals(StatusCode.OK, first.close());

    RiverDaemonIdentity.IdentityResult resumed = new RiverDaemonIdentity.IdentityResult();
    try {
      assertEquals(StatusCode.OK, RiverDaemonIdentity.beginCreate(
          datadir, filesystem, DatabaseIncarnation.of(31, 47), new SecureRandom(),
          currentPid(), currentStart(), resumed));
      assertEquals(INCARNATION, resumed.incarnation());
      assertEquals(StatusCode.OK, RiverDaemonIdentity.completeCreate(resumed));
      assertTrue(Files.isRegularFile(datadir.resolve(RiverDaemonIdentity.INSTANCE_FILE)));
    } finally {
      resumed.close();
    }
  }

  @Test
  void firstCreateOnlyLockPreflightHandlesTornEmptyAndLiveOwnersThroughApfs(@TempDir Path root)
      throws Exception {
    Assumptions.assumeTrue("Mac OS X".equals(System.getProperty("os.name")));
    ApfsRiverDaemonFileSystem filesystem = new ApfsRiverDaemonFileSystem();

    Path torn = root.toRealPath().resolve("torn");
    createPrivateDirectory(torn);
    writePrivate(torn.resolve(RiverDaemonIdentity.LOCK_FILE), "torn" + "x".repeat(2048));
    RiverDaemonIdentity.IdentityResult tornResult = new RiverDaemonIdentity.IdentityResult();
    try {
      assertEquals(StatusCode.OK, RiverDaemonIdentity.beginCreate(
          torn, filesystem, INCARNATION, new SecureRandom(), 999_999_999L, 0,
          tornResult));
      assertTrue(Files.exists(torn.resolve("bootstrap.properties")));
      RiverDaemonIdentityRecords.LockRecord rewritten = RiverDaemonIdentityRecords.parseLock(
          Files.readAllBytes(torn.resolve(RiverDaemonIdentity.LOCK_FILE)));
      assertTrue(rewritten != null);
      assertEquals(StatusCode.OK, RiverDaemonIdentity.completeCreate(tornResult));
      assertEquals(StatusCode.OK, tornResult.close());
      RiverDaemonIdentity.IdentityResult reopened = new RiverDaemonIdentity.IdentityResult();
      try {
        assertEquals(StatusCode.OK, RiverDaemonIdentity.openExisting(
            torn, filesystem, new SecureRandom(), currentPid(), currentStart(),
            reopened));
        assertEquals(INCARNATION, reopened.incarnation());
      } finally {
        reopened.close();
      }
    } finally {
      tornResult.close();
    }

    Path empty = root.toRealPath().resolve("empty");
    createPrivateDirectory(empty);
    writePrivate(empty.resolve(RiverDaemonIdentity.LOCK_FILE), "");
    RiverDaemonIdentity.IdentityResult emptyResult = new RiverDaemonIdentity.IdentityResult();
    try {
      assertEquals(StatusCode.OK, RiverDaemonIdentity.beginCreate(
          empty, filesystem, INCARNATION, new SecureRandom(), currentPid(), currentStart(),
          emptyResult));
      assertTrue(Files.exists(empty.resolve("bootstrap.properties")));
    } finally {
      emptyResult.close();
    }

    Path live = root.toRealPath().resolve("live");
    createPrivateDirectory(live);
    writePrivate(live.resolve(RiverDaemonIdentity.LOCK_FILE), RiverDaemonIdentityRecords.record(
        java.util.List.of(
            "format=" + RiverDaemonIdentityRecords.LOCK_FORMAT,
            "datadir=" + live.toAbsolutePath().normalize(),
            "database-incarnation-high=" + INCARNATION.high(),
            "database-incarnation-low=" + INCARNATION.low(),
            "pid=" + currentPid(),
            "process-start-epoch-millis=" + currentStart(),
            "owner-nonce=0123456789abcdef0123456789abcdef")));
    RiverDaemonIdentity.IdentityResult liveResult = new RiverDaemonIdentity.IdentityResult();
    try {
      assertEquals(StatusCode.CONFLICT, RiverDaemonIdentity.beginCreate(
          live, filesystem, INCARNATION, new SecureRandom(), 999_999_999L, 0,
          liveResult));
      assertFalse(Files.exists(live.resolve("bootstrap.properties")));
    } finally {
      liveResult.close();
    }
  }

  @Test
  void firstCreateUnknownNamespaceEntryRefusesBeforeCreatingLockThroughApfs(@TempDir Path root)
      throws Exception {
    Assumptions.assumeTrue("Mac OS X".equals(System.getProperty("os.name")));
    Path datadir = root.toRealPath().resolve("unknown");
    createPrivateDirectory(datadir);
    Path unknown = datadir.resolve("unexpected");
    writePrivate(unknown, "preserve");
    RiverDaemonIdentity.IdentityResult result = new RiverDaemonIdentity.IdentityResult();
    try {
      assertEquals(StatusCode.CORRUPTION, RiverDaemonIdentity.beginCreate(
          datadir, new ApfsRiverDaemonFileSystem(), INCARNATION, new SecureRandom(),
          currentPid(), currentStart(), result));
      assertFalse(Files.exists(datadir.resolve(RiverDaemonIdentity.LOCK_FILE)));
      assertFalse(Files.exists(datadir.resolve("bootstrap.properties")));
      assertEquals("preserve", Files.readString(unknown));
    } finally {
      result.close();
    }
  }

  @Test
  void prebootstrapStageIsRemovedOnlyAfterBindingAndOwnerProofThroughApfs(@TempDir Path root)
      throws Exception {
    Assumptions.assumeTrue("Mac OS X".equals(System.getProperty("os.name")));
    Path datadir = root.toRealPath().resolve("instance");
    createPrivateDirectory(datadir);
    String nonce = "0123456789abcdef0123456789abcdef";
    writePrivate(datadir.resolve(RiverDaemonIdentity.LOCK_FILE), RiverDaemonIdentityRecords.record(
        java.util.List.of(
            "format=" + RiverDaemonIdentityRecords.LOCK_FORMAT,
            "datadir=" + datadir,
            "database-incarnation-high=" + INCARNATION.high(),
            "database-incarnation-low=" + INCARNATION.low(),
            "pid=999999999",
            "process-start-epoch-millis=0",
            "owner-nonce=" + nonce)));
    writePrivate(datadir.resolve(".bootstrap-" + nonce + ".stage"),
        RiverDaemonIdentityRecords.record(java.util.List.of(
            "format=" + RiverDaemonIdentityRecords.BOOTSTRAP_FORMAT,
            "database-incarnation-high=" + INCARNATION.high(),
            "database-incarnation-low=" + INCARNATION.low(),
            "pid=999999999",
            "process-start-epoch-millis=0",
            "attempt-nonce=" + nonce,
            "database-name=database",
            "security-name=security",
            "staging-name=.riverd-bootstrap-" + nonce,
            "instance-stage-name=.instance-" + nonce + ".stage")));

    RiverDaemonIdentity.IdentityResult result = new RiverDaemonIdentity.IdentityResult();
    try {
      assertEquals(StatusCode.OK, RiverDaemonIdentity.beginCreate(
          datadir, new ApfsRiverDaemonFileSystem(), DatabaseIncarnation.of(31, 47),
          new SecureRandom(), currentPid(), currentStart(), result));
      assertFalse(Files.exists(datadir.resolve(".bootstrap-" + nonce + ".stage")));
      assertTrue(Files.exists(datadir.resolve("bootstrap.properties")));
    } finally {
      result.close();
    }
  }

  @Test
  void prebootstrapStageWithMismatchedCanonicalLockIsPreservedThroughApfs(@TempDir Path root)
      throws Exception {
    Assumptions.assumeTrue("Mac OS X".equals(System.getProperty("os.name")));
    Path datadir = root.toRealPath().resolve("mismatch");
    createPrivateDirectory(datadir);
    String lockNonce = "0123456789abcdef0123456789abcdef";
    String stageNonce = "fedcba9876543210fedcba9876543210";
    writePrivate(datadir.resolve(RiverDaemonIdentity.LOCK_FILE), RiverDaemonIdentityRecords.record(
        java.util.List.of(
            "format=" + RiverDaemonIdentityRecords.LOCK_FORMAT,
            "datadir=" + datadir,
            "database-incarnation-high=" + INCARNATION.high(),
            "database-incarnation-low=" + INCARNATION.low(),
            "pid=999999999",
            "process-start-epoch-millis=0",
            "owner-nonce=" + lockNonce)));
    writePrivate(datadir.resolve(".bootstrap-" + stageNonce + ".stage"),
        RiverDaemonIdentityRecords.record(java.util.List.of(
            "format=" + RiverDaemonIdentityRecords.BOOTSTRAP_FORMAT,
            "database-incarnation-high=" + INCARNATION.high(),
            "database-incarnation-low=" + INCARNATION.low(),
            "pid=999999999",
            "process-start-epoch-millis=0",
            "attempt-nonce=" + stageNonce,
            "database-name=database",
            "security-name=security",
            "staging-name=.riverd-bootstrap-" + stageNonce,
            "instance-stage-name=.instance-" + stageNonce + ".stage")));

    RiverDaemonIdentity.IdentityResult result = new RiverDaemonIdentity.IdentityResult();
    try {
      assertEquals(StatusCode.CORRUPTION, RiverDaemonIdentity.beginCreate(
          datadir, new ApfsRiverDaemonFileSystem(), DatabaseIncarnation.of(31, 47),
          new SecureRandom(), currentPid(), currentStart(), result));
      assertTrue(Files.exists(datadir.resolve(".bootstrap-" + stageNonce + ".stage")));
      assertFalse(Files.exists(datadir.resolve("bootstrap.properties")));
    } finally {
      result.close();
    }
  }

  @Test
  void instancePublicationForceFailurePreservesBootstrapEvidenceThroughApfs(@TempDir Path root)
      throws Exception {
    Assumptions.assumeTrue("Mac OS X".equals(System.getProperty("os.name")));
    Path datadir = root.toRealPath().resolve("instance");
    FailingForceFileSystem filesystem = new FailingForceFileSystem();
    RiverDaemonIdentity.IdentityResult result = new RiverDaemonIdentity.IdentityResult();
    try {
      assertEquals(StatusCode.OK, RiverDaemonIdentity.beginCreate(
          datadir, filesystem, INCARNATION, new SecureRandom(), 999_999_999L, 0,
          result));
      filesystem.armCommitForceFailure();
      assertEquals(StatusCode.IO_FAILURE, RiverDaemonIdentity.completeCreate(result));
      assertTrue(Files.exists(datadir.resolve(RiverDaemonIdentity.INSTANCE_FILE)));
      assertTrue(Files.exists(datadir.resolve("bootstrap.properties")));
    } finally {
      result.close();
    }
  }

  @Test
  void closedExistingInstanceStageContinuesPublicationThroughApfs(@TempDir Path root)
      throws Exception {
    Assumptions.assumeTrue("Mac OS X".equals(System.getProperty("os.name")));
    Path datadir = root.toRealPath().resolve("instance");
    FailingForceFileSystem filesystem = new FailingForceFileSystem();
    RiverDaemonIdentity.IdentityResult first = new RiverDaemonIdentity.IdentityResult();
    assertEquals(StatusCode.OK, RiverDaemonIdentity.beginCreate(
        datadir, filesystem, INCARNATION, new SecureRandom(), 999_999_999L, 0, first));
    String stageName = ".instance-" + first.nonce() + ".stage";
    RiverFileResult stage = new RiverFileResult();
    assertEquals(StatusCode.OK, first.directory().createFile(stageName, stage));
    writeRecord(stage.file(), RiverDaemonIdentityRecords.record(java.util.List.of(
        "format=" + RiverDaemonIdentityRecords.INSTANCE_FORMAT,
        "database-incarnation-high=" + INCARNATION.high(),
        "database-incarnation-low=" + INCARNATION.low(),
        "initial-wal-generation=1")));
    assertEquals(StatusCode.OK, stage.file().close());
    assertEquals(StatusCode.OK, first.directory().force(new DirectoryOperationResult()));

    filesystem.armCloseExistingStage();
    try {
      assertEquals(StatusCode.OK, RiverDaemonIdentity.completeCreate(first));
      assertEquals(1, filesystem.existingStageCloseCalls);
      assertTrue(Files.exists(datadir.resolve(RiverDaemonIdentity.INSTANCE_FILE)));
      assertFalse(Files.exists(datadir.resolve("bootstrap.properties")));
    } finally {
      first.close();
    }
  }

  @Test
  void invalidExistingInstanceStageIsClosedBeforePublicationReturnsThroughApfs(@TempDir Path root)
      throws Exception {
    Assumptions.assumeTrue("Mac OS X".equals(System.getProperty("os.name")));
    Path datadir = root.toRealPath().resolve("instance");
    FailingForceFileSystem filesystem = new FailingForceFileSystem();
    RiverDaemonIdentity.IdentityResult result = new RiverDaemonIdentity.IdentityResult();
    try {
      assertEquals(StatusCode.OK, RiverDaemonIdentity.beginCreate(
          datadir, filesystem, INCARNATION, new SecureRandom(), 999_999_999L, 0, result));
      String stageName = ".instance-" + result.nonce() + ".stage";
      RiverFileResult stage = new RiverFileResult();
      assertEquals(StatusCode.OK, result.directory().createFile(stageName, stage));
      writeRecord(stage.file(), "read-failed");
      assertEquals(StatusCode.OK, stage.file().close());
      assertEquals(StatusCode.OK, result.directory().force(new DirectoryOperationResult()));
      filesystem.armCloseExistingStage();
      assertEquals(StatusCode.CORRUPTION, RiverDaemonIdentity.completeCreate(result));
      assertEquals(1, filesystem.existingStageCloseCalls);
    } finally {
      result.close();
    }
  }

  @Test
  void committedInstanceCleansValidatedBootstrapResidueAfterConsumerChecksThroughApfs(
      @TempDir Path root) throws Exception {
    Assumptions.assumeTrue("Mac OS X".equals(System.getProperty("os.name")));
    Path datadir = root.toRealPath().resolve("instance");
    ApfsRiverDaemonFileSystem filesystem = new ApfsRiverDaemonFileSystem();
    RiverDaemonIdentity.IdentityResult first = new RiverDaemonIdentity.IdentityResult();
    assertEquals(StatusCode.OK, RiverDaemonIdentity.beginCreate(
        datadir, filesystem, INCARNATION, new SecureRandom(), 999_999_999L, 0,
        first));
    String nonce = first.nonce();
    for (String name : new String[] {RiverDaemonIdentity.DATABASE_NAME,
        RiverDaemonIdentity.SECURITY_NAME}) {
      RiverDirectory child = name.equals(RiverDaemonIdentity.DATABASE_NAME)
          ? first.database() : first.security();
      assertEquals(StatusCode.OK, first.directory().publishDirectoryExclusive(
          first.staging(), child, name, name, new DirectoryOperationResult()));
    }
    String stageName = ".instance-" + nonce + ".stage";
    RiverFileResult stageResult = new RiverFileResult();
    assertEquals(StatusCode.OK, first.directory().createFile(stageName, stageResult));
    writeRecord(stageResult.file(), RiverDaemonIdentityRecords.record(java.util.List.of(
        "format=" + RiverDaemonIdentityRecords.INSTANCE_FORMAT,
        "database-incarnation-high=" + INCARNATION.high(),
        "database-incarnation-low=" + INCARNATION.low(),
        "initial-wal-generation=1")));
    assertEquals(StatusCode.OK, first.directory().publishExclusive(
        stageResult.file(), stageName, RiverDaemonIdentity.INSTANCE_FILE,
        new DirectoryOperationResult()));
    assertEquals(StatusCode.OK, stageResult.file().close());
    assertEquals(StatusCode.OK, first.directory().force(new DirectoryOperationResult()));
    assertEquals(StatusCode.OK, first.close());

    RiverDaemonIdentity.IdentityResult reopened = new RiverDaemonIdentity.IdentityResult();
    try {
      assertEquals(StatusCode.OK, RiverDaemonIdentity.openExisting(
          datadir, filesystem, new SecureRandom(), currentPid(), currentStart(),
          reopened));
      // The owning component consumers have validated database and security here.
      assertEquals(StatusCode.OK, RiverDaemonIdentity.cleanupCommittedResidue(reopened));
      assertFalse(Files.exists(datadir.resolve("bootstrap.properties")));
      assertFalse(Files.exists(datadir.resolve(".riverd-bootstrap-" + nonce)));
    } finally {
      reopened.close();
      first.close();
    }
  }

  @Test
  void recoveryPreservesUnknownDirectEntryThroughApfs(@TempDir Path root) throws Exception {
    Assumptions.assumeTrue("Mac OS X".equals(System.getProperty("os.name")));
    Path datadir = root.toRealPath().resolve("instance");
    ApfsRiverDaemonFileSystem filesystem = new ApfsRiverDaemonFileSystem();
    RiverDaemonIdentity.IdentityResult first = new RiverDaemonIdentity.IdentityResult();
    assertEquals(StatusCode.OK, RiverDaemonIdentity.beginCreate(
        datadir, filesystem, INCARNATION, new SecureRandom(), 999_999_999L, 0,
        first));
    assertEquals(StatusCode.OK, first.close());
    Path unknown = datadir.resolve("unexpected");
    writePrivate(unknown, "preserve");

    RiverDaemonIdentity.IdentityResult retry = new RiverDaemonIdentity.IdentityResult();
    assertEquals(StatusCode.CORRUPTION, RiverDaemonIdentity.beginCreate(
        datadir, filesystem, DatabaseIncarnation.of(31, 47), new SecureRandom(),
        999_999_999L, 0, retry));
    assertEquals("preserve", Files.readString(unknown));
    retry.close();
  }

  @Test
  void recoveryResumesAfterOrderedDatabasePublicationThroughApfs(@TempDir Path root)
      throws Exception {
    Assumptions.assumeTrue("Mac OS X".equals(System.getProperty("os.name")));
    Path datadir = root.toRealPath().resolve("instance");
    ApfsRiverDaemonFileSystem filesystem = new ApfsRiverDaemonFileSystem();
    RiverDaemonIdentity.IdentityResult first = new RiverDaemonIdentity.IdentityResult();
    assertEquals(StatusCode.OK, RiverDaemonIdentity.beginCreate(
        datadir, filesystem, INCARNATION, new SecureRandom(), 999_999_999L, 0,
        first));
    DirectoryOperationResult publication = new DirectoryOperationResult();
    assertEquals(StatusCode.OK, first.directory().publishDirectoryExclusive(
        first.staging(), first.database(), RiverDaemonIdentity.DATABASE_NAME,
        RiverDaemonIdentity.DATABASE_NAME, publication));
    assertEquals(StatusCode.OK, first.directory().force(new DirectoryOperationResult()));
    assertEquals(StatusCode.OK, first.close());

    RiverDaemonIdentity.IdentityResult resumed = new RiverDaemonIdentity.IdentityResult();
    try {
      assertEquals(StatusCode.OK, RiverDaemonIdentity.beginCreate(
          datadir, filesystem, DatabaseIncarnation.of(31, 47), new SecureRandom(),
          currentPid(), currentStart(), resumed));
      assertEquals(INCARNATION, resumed.incarnation());
      assertEquals(StatusCode.OK, RiverDaemonIdentity.completeCreate(resumed));
    } finally {
      resumed.close();
    }
  }

  @Test
  void invalidStagingEntryIsPreservedWithoutCreatingMissingChildThroughApfs(@TempDir Path root)
      throws Exception {
    Assumptions.assumeTrue("Mac OS X".equals(System.getProperty("os.name")));
    Path datadir = root.toRealPath().resolve("instance");
    ApfsRiverDaemonFileSystem filesystem = new ApfsRiverDaemonFileSystem();
    RiverDaemonIdentity.IdentityResult first = new RiverDaemonIdentity.IdentityResult();
    assertEquals(StatusCode.OK, RiverDaemonIdentity.beginCreate(
        datadir, filesystem, INCARNATION, new SecureRandom(), 999_999_999L, 0,
        first));
    String nonce = first.nonce();
    assertEquals(StatusCode.OK, first.close());
    Path staging = datadir.resolve(".riverd-bootstrap-" + nonce);
    Files.delete(staging.resolve(RiverDaemonIdentity.SECURITY_NAME));
    Path unknown = staging.resolve("unexpected");
    writePrivate(unknown, "preserve");

    RiverDaemonIdentity.IdentityResult retry = new RiverDaemonIdentity.IdentityResult();
    assertEquals(StatusCode.CORRUPTION, RiverDaemonIdentity.beginCreate(
        datadir, filesystem, DatabaseIncarnation.of(31, 47), new SecureRandom(),
        999_999_999L, 0, retry));
    assertTrue(Files.exists(unknown));
    assertTrue(!Files.exists(staging.resolve(RiverDaemonIdentity.SECURITY_NAME)));
    retry.close();
  }

  @Test
  void tornLockIsReboundOnlyAfterBootstrapOwnerIsAbsentThroughApfs(@TempDir Path root)
      throws Exception {
    Assumptions.assumeTrue("Mac OS X".equals(System.getProperty("os.name")));
    Path datadir = root.toRealPath().resolve("instance");
    ApfsRiverDaemonFileSystem filesystem = new ApfsRiverDaemonFileSystem();
    RiverDaemonIdentity.IdentityResult first = new RiverDaemonIdentity.IdentityResult();
    assertEquals(StatusCode.OK, RiverDaemonIdentity.beginCreate(
        datadir, filesystem, INCARNATION, new SecureRandom(), 999_999_999L, 0,
        first));
    assertEquals(StatusCode.OK, first.close());
    Files.writeString(datadir.resolve(RiverDaemonIdentity.LOCK_FILE), "torn");

    RiverDaemonIdentity.IdentityResult resumed = new RiverDaemonIdentity.IdentityResult();
    try {
      assertEquals(StatusCode.OK, RiverDaemonIdentity.beginCreate(
          datadir, filesystem, DatabaseIncarnation.of(31, 47), new SecureRandom(),
          currentPid(), currentStart(), resumed));
      assertEquals(INCARNATION, resumed.incarnation());
      assertEquals(StatusCode.OK, RiverDaemonIdentity.completeCreate(resumed));
    } finally {
      resumed.close();
    }
  }

  @Test
  void canonicalLiveLockOwnerRefusesCompetingCreateThroughApfs(@TempDir Path root)
      throws Exception {
    Assumptions.assumeTrue("Mac OS X".equals(System.getProperty("os.name")));
    Path datadir = root.toRealPath().resolve("instance");
    ApfsRiverDaemonFileSystem filesystem = new ApfsRiverDaemonFileSystem();
    RiverDaemonIdentity.IdentityResult owner = new RiverDaemonIdentity.IdentityResult();
    assertEquals(StatusCode.OK, RiverDaemonIdentity.beginCreate(
        datadir, filesystem, INCARNATION, new SecureRandom(), currentPid(), currentStart(),
        owner));
    assertEquals(StatusCode.OK, owner.close());
    RiverDaemonIdentity.IdentityResult competing = new RiverDaemonIdentity.IdentityResult();
    try {
      assertEquals(StatusCode.CONFLICT, RiverDaemonIdentity.beginCreate(
          datadir, filesystem, DatabaseIncarnation.of(31, 47), new SecureRandom(),
          currentPid(), currentStart(), competing));
    } finally {
      competing.close();
      owner.close();
    }
  }

  @Test
  void boundPartialInstanceStageIsRemovedByIdentityAndRecreatedThroughApfs(@TempDir Path root)
      throws Exception {
    Assumptions.assumeTrue("Mac OS X".equals(System.getProperty("os.name")));
    Path datadir = root.toRealPath().resolve("instance");
    ApfsRiverDaemonFileSystem filesystem = new ApfsRiverDaemonFileSystem();
    RiverDaemonIdentity.IdentityResult first = new RiverDaemonIdentity.IdentityResult();
    assertEquals(StatusCode.OK, RiverDaemonIdentity.beginCreate(
        datadir, filesystem, INCARNATION, new SecureRandom(), 999_999_999L, 0,
        first));
    String nonce = first.nonce();
    for (String name : new String[] {RiverDaemonIdentity.DATABASE_NAME,
        RiverDaemonIdentity.SECURITY_NAME}) {
      assertEquals(StatusCode.OK, first.directory().publishDirectoryExclusive(
          first.staging(), name.equals(RiverDaemonIdentity.DATABASE_NAME) ? first.database()
              : first.security(),
          name, name, new DirectoryOperationResult()));
      assertEquals(StatusCode.OK, first.directory().force(new DirectoryOperationResult()));
    }
    assertEquals(StatusCode.OK, first.close());
    Path stage = datadir.resolve(".instance-" + nonce + ".stage");
    writePrivate(stage, "partial");

    Path unexpected = datadir.resolve(".riverd-bootstrap-" + nonce).resolve("unexpected");
    writePrivate(unexpected, "preserve");
    RiverDaemonIdentity.IdentityResult rejected = new RiverDaemonIdentity.IdentityResult();
    assertEquals(StatusCode.CORRUPTION, RiverDaemonIdentity.beginCreate(
        datadir, filesystem, INCARNATION, new SecureRandom(),
        currentPid(), currentStart(), rejected));
    assertEquals("partial", Files.readString(stage));
    assertEquals(StatusCode.OK, rejected.close());
    Files.delete(unexpected);

    RiverDaemonIdentity.IdentityResult resumed = new RiverDaemonIdentity.IdentityResult();
    try {
      assertEquals(StatusCode.OK, RiverDaemonIdentity.beginCreate(
          datadir, filesystem, DatabaseIncarnation.of(31, 47), new SecureRandom(),
          currentPid(), currentStart(), resumed));
      assertTrue(Files.exists(stage));
      assertEquals(StatusCode.OK, RiverDaemonIdentity.completeCreate(resumed));
      assertTrue(!Files.exists(stage));
    } finally {
      resumed.close();
    }
  }

  @Test
  void canonicalForeignInstanceStageIsPreservedAndRejectedThroughApfs(@TempDir Path root)
      throws Exception {
    Assumptions.assumeTrue("Mac OS X".equals(System.getProperty("os.name")));
    Path datadir = root.toRealPath().resolve("instance");
    ApfsRiverDaemonFileSystem filesystem = new ApfsRiverDaemonFileSystem();
    RiverDaemonIdentity.IdentityResult first = new RiverDaemonIdentity.IdentityResult();
    assertEquals(StatusCode.OK, RiverDaemonIdentity.beginCreate(
        datadir, filesystem, INCARNATION, new SecureRandom(), 999_999_999L, 0,
        first));
    String nonce = first.nonce();
    for (String name : new String[] {RiverDaemonIdentity.DATABASE_NAME,
        RiverDaemonIdentity.SECURITY_NAME}) {
      assertEquals(StatusCode.OK, first.directory().publishDirectoryExclusive(
          first.staging(), name.equals(RiverDaemonIdentity.DATABASE_NAME) ? first.database()
              : first.security(),
          name, name, new DirectoryOperationResult()));
      assertEquals(StatusCode.OK, first.directory().force(new DirectoryOperationResult()));
    }
    assertEquals(StatusCode.OK, first.close());
    Path stage = datadir.resolve(".instance-" + nonce + ".stage");
    writePrivate(stage, RiverDaemonIdentityRecords.record(java.util.List.of(
        "format=riverd-instance-v1",
        "database-incarnation-high=31",
        "database-incarnation-low=47",
        "initial-wal-generation=1")));

    RiverDaemonIdentity.IdentityResult retry = new RiverDaemonIdentity.IdentityResult();
    assertEquals(StatusCode.CORRUPTION, RiverDaemonIdentity.beginCreate(
        datadir, filesystem, DatabaseIncarnation.of(31, 47), new SecureRandom(),
        currentPid(), currentStart(), retry));
    assertTrue(Files.exists(stage));
    retry.close();
  }

  private static long currentPid() {
    return ProcessHandle.current().pid();
  }

  private static long currentStart() {
    return ProcessHandle.current().info().startInstant().orElseThrow().toEpochMilli();
  }



  private static void writeRecord(RiverFile file, String record) {
    ByteBuffer source = ByteBuffer.wrap(record.getBytes(StandardCharsets.UTF_8));
    IoResult io = new IoResult();
    long position = 0;
    while (source.hasRemaining()) {
      assertEquals(StatusCode.OK, file.write(position, source, io));
      assertTrue(io.bytesTransferred() > 0);
      position += io.bytesTransferred();
    }
    assertEquals(StatusCode.OK, file.force(ForceMode.CONTENT_AND_METADATA));
  }

  private static final class FailingForceFileSystem implements RiverDaemonFileSystem {
    private final ApfsRiverDaemonFileSystem delegate = new ApfsRiverDaemonFileSystem();
    private boolean armed;
    private boolean closeExistingStage;
    private int existingStageCloseCalls;
    private int forceCalls;

    void armCloseExistingStage() {
      closeExistingStage = true;
    }

    void armCommitForceFailure() {
      armed = true;
      forceCalls = 0;
    }

    @Override
    public StatusCode openAncestor(Path path, RiverDirectoryResult result) {
      return wrap(delegate.openAncestor(path, result), result);
    }

    @Override
    public StatusCode openDirectory(Path path, RiverDirectoryResult result) {
      return wrap(delegate.openDirectory(path, result), result);
    }

    @Override
    public StatusCode acquireExclusive(RiverFile file, RiverLockResult result) {
      return delegate.acquireExclusive(file, result);
    }

    private StatusCode wrap(StatusCode status, RiverDirectoryResult result) {
      if (status.isOk() && result.directory() != null) {
        result.set(new FailingForceDirectory(this, result.directory()));
      }
      return status;
    }
  }

  private static final class FailingForceDirectory implements RiverDirectory {
    private final FailingForceFileSystem filesystem;
    private final RiverDirectory delegate;

    private FailingForceDirectory(FailingForceFileSystem filesystem, RiverDirectory delegate) {
      this.filesystem = filesystem;
      this.delegate = delegate;
    }

    @Override
    public FileIdentity identity() {
      return delegate.identity();
    }

    @Override
    public StatusCode createDirectory(String childName, RiverDirectoryResult result) {
      return wrap(delegate.createDirectory(childName, result), result);
    }

    @Override
    public StatusCode openDirectory(String childName, RiverDirectoryResult result) {
      return wrap(delegate.openDirectory(childName, result), result);
    }

    @Override
    public StatusCode openFile(String childName, RiverOpenMode mode, RiverFileResult result) {
      StatusCode status = delegate.openFile(childName, mode, result);
      if (status.isOk() && filesystem.closeExistingStage && mode == RiverOpenMode.EXISTING
          && childName.startsWith(".instance-")) {
        result.set(new ClosedStageFile(this.filesystem, result.file()));
      }
      return status;
    }

    @Override
    public StatusCode list(DirectoryListResult result) {
      return delegate.list(result);
    }

    @Override
    public StatusCode publishExclusive(
        RiverFile stage, String stageName, String targetName, DirectoryOperationResult result) {
      return delegate.publishExclusive(raw(stage), stageName, targetName, result);
    }

    @Override
    public StatusCode publishReplacement(
        RiverFile stage, String stageName, String targetName, DirectoryOperationResult result) {
      return delegate.publishReplacement(raw(stage), stageName, targetName, result);
    }

    @Override
    public StatusCode publishDirectoryExclusive(
        RiverDirectory sourceParent,
        RiverDirectory stage,
        String stageName,
        String targetName,
        DirectoryOperationResult result) {
      return delegate.publishDirectoryExclusive(
          raw(sourceParent), raw(stage), stageName, targetName, result);
    }

    @Override
    public StatusCode removeOwned(
        String childName, FileIdentity expectedIdentity, DirectoryOperationResult result) {
      return delegate.removeOwned(childName, expectedIdentity, result);
    }

    @Override
    public StatusCode force(DirectoryOperationResult result) {
      if (result == null) return StatusCode.INVALID_EXTERNAL_INPUT;
      if (filesystem.armed && ++filesystem.forceCalls == 4) {
        result.reset();
        return StatusCode.IO_FAILURE;
      }
      return delegate.force(result);
    }

    @Override
    public StatusCode close() {
      return delegate.close();
    }

    private StatusCode wrap(StatusCode status, RiverDirectoryResult result) {
      if (status.isOk() && result.directory() != null) {
        result.set(new FailingForceDirectory(filesystem, result.directory()));
      }
      return status;
    }

    private static RiverDirectory raw(RiverDirectory value) {
      return value instanceof FailingForceDirectory wrapped ? wrapped.delegate : value;
    }

    private static RiverFile raw(RiverFile value) {
      return value instanceof ClosedStageFile wrapped ? wrapped.delegate : value;
    }
  }

  private static final class ClosedStageFile implements RiverFile {
    private final FailingForceFileSystem filesystem;
    private final RiverFile delegate;

    private ClosedStageFile(FailingForceFileSystem filesystem, RiverFile delegate) {
      this.filesystem = filesystem;
      this.delegate = delegate;
    }

    @Override
    public FileIdentity identity() { return delegate.identity(); }
    @Override
    public StatusCode read(long position, ByteBuffer target, IoResult result) {
      return delegate.read(position, target, result);
    }
    @Override
    public StatusCode write(long position, ByteBuffer source, IoResult result) {
      return delegate.write(position, source, result);
    }
    @Override
    public StatusCode force(ForceMode mode) { return delegate.force(mode); }
    @Override
    public StatusCode force(long startOffset, long endOffset, ForceMode mode) {
      return delegate.force(startOffset, endOffset, mode);
    }
    @Override
    public StatusCode truncate(long sizeBytes) { return delegate.truncate(sizeBytes); }
    @Override
    public StatusCode size(FileSizeResult result) { return delegate.size(result); }
    @Override
    public StatusCode close() {
      StatusCode status = delegate.close();
      filesystem.existingStageCloseCalls++;
      return status.isOk() ? StatusCode.CLOSED : status;
    }
  }

  private static void writePrivate(Path path, String value) throws Exception {
    Files.createFile(path, PosixFilePermissions.asFileAttribute(
        PosixFilePermissions.fromString("rw-------")));
    Files.writeString(path, value);
  }

  private static void createPrivateDirectory(Path path) throws Exception {
    Files.createDirectory(path, PosixFilePermissions.asFileAttribute(
        PosixFilePermissions.fromString("rwx------")));
  }
}
