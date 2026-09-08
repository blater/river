package io.riverdb.server.app;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.riverdb.base.error.StatusCode;
import io.riverdb.base.id.DatabaseIncarnation;
import io.riverdb.platform.file.DirectoryOperationResult;
import io.riverdb.platform.riverd.apfs.ApfsRiverDaemonFileSystem;
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
  void interruptedFirstCreateResumesRecordedIdentityThroughApfs(@TempDir Path root) throws Exception {
    Assumptions.assumeTrue("Mac OS X".equals(System.getProperty("os.name")));
    Path datadir = root.toRealPath().resolve("instance");
    ApfsRiverDaemonFileSystem filesystem = new ApfsRiverDaemonFileSystem();
    RiverDaemonIdentity.IdentityResult first = new RiverDaemonIdentity.IdentityResult();
    assertEquals(StatusCode.OK, RiverDaemonIdentity.beginCreate(
        datadir, filesystem, INCARNATION, new SecureRandom(), 999_999_999L, 0,
        "/usr/bin/java", first));
    assertEquals(StatusCode.OK, first.close());

    RiverDaemonIdentity.IdentityResult resumed = new RiverDaemonIdentity.IdentityResult();
    try {
      assertEquals(StatusCode.OK, RiverDaemonIdentity.beginCreate(
          datadir, filesystem, DatabaseIncarnation.of(31, 47), new SecureRandom(),
          currentPid(), currentStart(), currentCommand(), resumed));
      assertEquals(INCARNATION, resumed.incarnation());
      assertEquals(StatusCode.OK, RiverDaemonIdentity.completeCreate(resumed));
      assertTrue(Files.isRegularFile(datadir.resolve(RiverDaemonIdentity.INSTANCE_FILE)));
    } finally {
      resumed.close();
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
        "/usr/bin/java", first));
    assertEquals(StatusCode.OK, first.close());
    Path unknown = datadir.resolve("unexpected");
    writePrivate(unknown, "preserve");

    RiverDaemonIdentity.IdentityResult retry = new RiverDaemonIdentity.IdentityResult();
    assertEquals(StatusCode.CORRUPTION, RiverDaemonIdentity.beginCreate(
        datadir, filesystem, DatabaseIncarnation.of(31, 47), new SecureRandom(),
        999_999_999L, 0, "/usr/bin/java", retry));
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
        "/usr/bin/java", first));
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
          currentPid(), currentStart(), currentCommand(), resumed));
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
        "/usr/bin/java", first));
    String nonce = first.nonce();
    assertEquals(StatusCode.OK, first.close());
    Path staging = datadir.resolve(".riverd-bootstrap-" + nonce);
    Files.delete(staging.resolve(RiverDaemonIdentity.SECURITY_NAME));
    Path unknown = staging.resolve("unexpected");
    writePrivate(unknown, "preserve");

    RiverDaemonIdentity.IdentityResult retry = new RiverDaemonIdentity.IdentityResult();
    assertEquals(StatusCode.CORRUPTION, RiverDaemonIdentity.beginCreate(
        datadir, filesystem, DatabaseIncarnation.of(31, 47), new SecureRandom(),
        999_999_999L, 0, "/usr/bin/java", retry));
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
        "/usr/bin/java", first));
    assertEquals(StatusCode.OK, first.close());
    Files.writeString(datadir.resolve(RiverDaemonIdentity.LOCK_FILE), "torn");

    RiverDaemonIdentity.IdentityResult resumed = new RiverDaemonIdentity.IdentityResult();
    try {
      assertEquals(StatusCode.OK, RiverDaemonIdentity.beginCreate(
          datadir, filesystem, DatabaseIncarnation.of(31, 47), new SecureRandom(),
          currentPid(), currentStart(), currentCommand(), resumed));
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
        currentCommand(), owner));
    assertEquals(StatusCode.OK, owner.close());
    RiverDaemonIdentity.IdentityResult competing = new RiverDaemonIdentity.IdentityResult();
    try {
      assertEquals(StatusCode.CONFLICT, RiverDaemonIdentity.beginCreate(
          datadir, filesystem, DatabaseIncarnation.of(31, 47), new SecureRandom(),
          currentPid(), currentStart(), currentCommand(), competing));
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
        "/usr/bin/java", first));
    String nonce = first.nonce();
    for (String name : new String[] {RiverDaemonIdentity.DATABASE_NAME,
        RiverDaemonIdentity.SECURITY_NAME, RiverDaemonIdentity.AUDIT_NAME}) {
      assertEquals(StatusCode.OK, first.directory().publishDirectoryExclusive(
          first.staging(), name.equals(RiverDaemonIdentity.DATABASE_NAME) ? first.database()
              : name.equals(RiverDaemonIdentity.SECURITY_NAME) ? first.security() : first.audit(),
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
        currentPid(), currentStart(), currentCommand(), rejected));
    assertEquals("partial", Files.readString(stage));
    assertEquals(StatusCode.OK, rejected.close());
    Files.delete(unexpected);

    RiverDaemonIdentity.IdentityResult resumed = new RiverDaemonIdentity.IdentityResult();
    try {
      assertEquals(StatusCode.OK, RiverDaemonIdentity.beginCreate(
          datadir, filesystem, DatabaseIncarnation.of(31, 47), new SecureRandom(),
          currentPid(), currentStart(), currentCommand(), resumed));
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
        "/usr/bin/java", first));
    String nonce = first.nonce();
    for (String name : new String[] {RiverDaemonIdentity.DATABASE_NAME,
        RiverDaemonIdentity.SECURITY_NAME, RiverDaemonIdentity.AUDIT_NAME}) {
      assertEquals(StatusCode.OK, first.directory().publishDirectoryExclusive(
          first.staging(), name.equals(RiverDaemonIdentity.DATABASE_NAME) ? first.database()
              : name.equals(RiverDaemonIdentity.SECURITY_NAME) ? first.security() : first.audit(),
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
        currentPid(), currentStart(), currentCommand(), retry));
    assertTrue(Files.exists(stage));
    retry.close();
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

  private static void writePrivate(Path path, String value) throws Exception {
    Files.createFile(path, PosixFilePermissions.asFileAttribute(
        PosixFilePermissions.fromString("rw-------")));
    Files.writeString(path, value);
  }
}
