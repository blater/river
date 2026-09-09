package io.riverdb.server.app;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import io.riverdb.base.error.StatusCode;
import io.riverdb.base.id.DatabaseIncarnation;
import io.riverdb.client.RiverClientConfiguration;
import io.riverdb.client.RiverClientConfigurationResult;
import io.riverdb.client.RiverClientOpenResult;
import io.riverdb.platform.riverd.RiverDirectory;
import io.riverdb.platform.riverd.RiverDirectoryResult;
import io.riverdb.platform.riverd.apfs.ApfsRiverDaemonFileSystem;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HexFormat;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class RiverDaemonCredentialsTest {
  private static final DatabaseIncarnation INCARNATION = DatabaseIncarnation.of(17, 29);

  @Test
  void generatedMaterialIsValidatedAndDestroyIsIdempotent() {
    RiverDaemonCredentials.CredentialResult result = new RiverDaemonCredentials.CredentialResult();
    Instant created = Instant.now().minusSeconds(30);
    assertEquals(StatusCode.OK, RiverDaemonCredentials.generate(
        INCARNATION, 1, new SecureRandom(), created, result));
    RiverDaemonCredentials.Material material = result.material();
    assertNotNull(material);
    try {
      assertEquals(StatusCode.OK, RiverDaemonCredentials.validate(material, INCARNATION, created));
      assertEquals(StatusCode.OK, material.destroy());
      assertEquals(StatusCode.OK, material.destroy());
      assertEquals(StatusCode.CORRUPTION,
          RiverDaemonCredentials.validate(material, INCARNATION, created));
    } finally {
      material.destroy();
    }
  }

  @Test
  void validationRejectsPrivateKeyThatDoesNotMatchCertificate() {
    RiverDaemonCredentials.CredentialResult firstResult = new RiverDaemonCredentials.CredentialResult();
    RiverDaemonCredentials.CredentialResult secondResult = new RiverDaemonCredentials.CredentialResult();
    Instant created = Instant.now().minusSeconds(30);
    assertEquals(StatusCode.OK, RiverDaemonCredentials.generate(
        INCARNATION, 1, new SecureRandom(), created, firstResult));
    assertEquals(StatusCode.OK, RiverDaemonCredentials.generate(
        INCARNATION, 2, new SecureRandom(), created, secondResult));
    RiverDaemonCredentials.Material first = firstResult.material();
    RiverDaemonCredentials.Material second = secondResult.material();
    RiverDaemonCredentials.Material mismatch = new RiverDaemonCredentials.Material(
        1, new byte[RiverDaemonCredentials.TOKEN_BYTES], second.privateKey(),
        first.certificate(), first.provider());
    try {
      assertEquals(StatusCode.CORRUPTION,
          RiverDaemonCredentials.validate(mismatch, INCARNATION, created));
    } finally {
      mismatch.destroy();
      first.destroy();
      second.destroy();
    }
  }

  @Test
  void validationRejectsDifferentCreationTimeAndExpiredMaterial() {
    RiverDaemonCredentials.CredentialResult result = new RiverDaemonCredentials.CredentialResult();
    Instant created = Instant.now().minusSeconds(30);
    assertEquals(StatusCode.OK, RiverDaemonCredentials.generate(
        INCARNATION, 1, new SecureRandom(), created, result));
    RiverDaemonCredentials.Material material = result.material();
    try {
      assertEquals(StatusCode.CORRUPTION,
          RiverDaemonCredentials.validate(material, INCARNATION, created.plusSeconds(1)));
    } finally {
      material.destroy();
    }
    RiverDaemonCredentials.CredentialResult expired = new RiverDaemonCredentials.CredentialResult();
    assertEquals(StatusCode.CORRUPTION, RiverDaemonCredentials.generate(
        INCARNATION, 2, new SecureRandom(),
        Instant.now().minus(366, ChronoUnit.DAYS), expired));
  }

  @Test
  void persistsAndReloadsGenerationThroughApfsDirectory(@TempDir Path root) throws Exception {
    Assumptions.assumeTrue("Mac OS X".equals(System.getProperty("os.name")));
    Path securityPath = root.toRealPath().resolve("security");
    Files.createDirectory(securityPath, PosixFilePermissions.asFileAttribute(
        PosixFilePermissions.fromString("rwx------")));
    ApfsRiverDaemonFileSystem filesystem = new ApfsRiverDaemonFileSystem();
    RiverDirectoryResult openedDirectory = new RiverDirectoryResult();
    assertEquals(StatusCode.OK, filesystem.openDirectory(securityPath, openedDirectory));
    RiverDirectory security = openedDirectory.directory();
    Instant created = Instant.now().minusSeconds(30);
    RiverDaemonCredentials.CredentialResult generated = new RiverDaemonCredentials.CredentialResult();
    assertEquals(StatusCode.OK, RiverDaemonCredentials.generate(
        INCARNATION, 1, new SecureRandom(), created, generated));
    RiverDaemonCredentials.Material material = generated.material();
    try {
      assertEquals(StatusCode.OK,
          RiverDaemonCredentials.persist(material, security, INCARNATION, created));
      assertEquals(StatusCode.OK, security.close());
      RiverDirectoryResult reopenedDirectory = new RiverDirectoryResult();
      assertEquals(StatusCode.OK, filesystem.openDirectory(securityPath, reopenedDirectory));
      RiverDaemonCredentials.CredentialResult reloaded = new RiverDaemonCredentials.CredentialResult();
      try {
        assertEquals(StatusCode.OK,
            RiverDaemonCredentials.load(reopenedDirectory.directory(), INCARNATION, reloaded));
        assertNotNull(reloaded.material());
      } finally {
        if (reloaded.material() != null) reloaded.material().destroy();
        reopenedDirectory.directory().close();
      }
    } finally {
      material.destroy();
      security.close();
    }
  }

  @Test
  void firstCreateAndRestartReopenCredentialsThroughApfs(@TempDir Path root) throws Exception {
    Assumptions.assumeTrue("Mac OS X".equals(System.getProperty("os.name")));
    Path datadir = root.toRealPath().resolve("instance");
    ApfsRiverDaemonFileSystem filesystem = new ApfsRiverDaemonFileSystem();
    Instant created = Instant.now().minusSeconds(30);
    RiverDaemonCredentials.CredentialResult generated = new RiverDaemonCredentials.CredentialResult();
    assertEquals(StatusCode.OK, RiverDaemonCredentials.generate(
        INCARNATION, 1, new SecureRandom(), created, generated));
    RiverDaemonCredentials.Material material = generated.material();
    RiverDaemonIdentity.IdentityResult identity = new RiverDaemonIdentity.IdentityResult();
    try {
      assertEquals(StatusCode.OK, RiverDaemonIdentity.beginCreate(
          datadir, filesystem, INCARNATION, new SecureRandom(), 999_999_999L, 0,
          identity));
      assertEquals(StatusCode.OK, RiverDaemonCredentials.persist(
          material, identity.security(), INCARNATION, created));
      assertEquals(StatusCode.OK, RiverDaemonIdentity.completeCreate(identity));
      assertEquals(StatusCode.OK, identity.close());

      RiverDaemonIdentity.IdentityResult reopened = new RiverDaemonIdentity.IdentityResult();
      try {
        ProcessHandle current = ProcessHandle.current();
        assertEquals(StatusCode.OK, RiverDaemonIdentity.openExisting(
            datadir, filesystem, new SecureRandom(), current.pid(),
            current.info().startInstant().orElseThrow().toEpochMilli(),
            reopened));
        RiverDirectoryResult securityResult = new RiverDirectoryResult();
        assertEquals(StatusCode.OK, reopened.directory().openDirectory(
            RiverDaemonIdentity.SECURITY_NAME, securityResult));
        RiverDaemonCredentials.CredentialResult loaded = new RiverDaemonCredentials.CredentialResult();
        try {
          assertEquals(StatusCode.OK, RiverDaemonCredentials.load(
              securityResult.directory(), INCARNATION, loaded));
          assertNotNull(loaded.material());
        } finally {
          if (loaded.material() != null) loaded.material().destroy();
          securityResult.directory().close();
        }
      } finally {
        reopened.close();
      }
    } finally {
      material.destroy();
      identity.close();
    }
  }

  @Test
  void publishesAndLoadsClientConfigurationAndRejectsPreflightTampering(@TempDir Path root)
      throws Exception {
    Assumptions.assumeTrue("Mac OS X".equals(System.getProperty("os.name")));
    Path securityPath = root.toRealPath().resolve("security");
    Files.createDirectory(securityPath, PosixFilePermissions.asFileAttribute(
        PosixFilePermissions.fromString("rwx------")));
    ApfsRiverDaemonFileSystem filesystem = new ApfsRiverDaemonFileSystem();
    RiverDirectoryResult opened = new RiverDirectoryResult();
    assertEquals(StatusCode.OK, filesystem.openDirectory(securityPath, opened));
    RiverDirectory security = opened.directory();
    RiverDaemonCredentials.CredentialResult generated =
        new RiverDaemonCredentials.CredentialResult();
    Instant created = Instant.now().minusSeconds(30);
    assertEquals(StatusCode.OK, RiverDaemonCredentials.generate(
        INCARNATION, 7, new SecureRandom(), created, generated));
    RiverDaemonCredentials.Material material = generated.material();
    try {
      assertEquals(StatusCode.OK,
          RiverDaemonCredentials.persist(material, security, INCARNATION, created));
      assertEquals(StatusCode.OK, RiverDaemonCredentials.publishClientConfiguration(
          material, security, securityPath, INCARNATION, "localhost", 43117,
          "0123456789abcdef0123456789abcdef"));
      assertEquals(StatusCode.OK, security.close());

      Path clientPath = securityPath.resolve("client.properties");
      RiverClientConfigurationResult loaded = new RiverClientConfigurationResult();
      assertEquals(StatusCode.OK, RiverClientConfiguration.load(clientPath, filesystem, loaded));
      RiverClientConfiguration configuration = loaded.configuration();
      assertNotNull(configuration);
      assertEquals(INCARNATION, configuration.incarnation());
      assertEquals(7, configuration.credentialGeneration());
      assertEquals("localhost", configuration.host());
      assertEquals(43117, configuration.port());

      Path tokenPath = securityPath.resolve("generations").resolve("7").resolve("token.bin");
      Files.setPosixFilePermissions(tokenPath, PosixFilePermissions.fromString("rw-r--r--"));
      RiverClientOpenResult openedClient = new RiverClientOpenResult();
      assertEquals(StatusCode.ACCESS_DENIED, configuration.connect(openedClient));

      String valid = Files.readString(clientPath, StandardCharsets.UTF_8);
      String certificatePath = securityPath.resolve("generations").resolve("7")
          .resolve("server-certificate.der").toString();
      String unsafe = replaceLine(valid, "token-file=", "token-file=" + certificatePath);
      Files.writeString(clientPath, withRecordChecksum(unsafe), StandardCharsets.UTF_8);
      RiverClientConfigurationResult unsafeResult = new RiverClientConfigurationResult();
      assertEquals(StatusCode.OK, RiverClientConfiguration.load(
          clientPath, filesystem, unsafeResult));
      assertEquals(StatusCode.CORRUPTION, unsafeResult.configuration().connect(openedClient));

      String uppercaseDigest = replaceLine(valid, "server-certificate-sha256=",
          "server-certificate-sha256=" + value(valid, "server-certificate-sha256=").toUpperCase());
      Files.writeString(clientPath, withRecordChecksum(uppercaseDigest), StandardCharsets.UTF_8);
      RiverClientConfigurationResult noncanonical = new RiverClientConfigurationResult();
      assertEquals(StatusCode.CORRUPTION, RiverClientConfiguration.load(
          clientPath, filesystem, noncanonical));
    } finally {
      material.destroy();
      security.close();
    }
  }

  private static String replaceLine(String record, String key, String replacement) {
    String[] lines = record.split("\\n", -1);
    for (int index = 0; index < lines.length; index++) {
      if (lines[index].startsWith(key)) lines[index] = replacement;
    }
    return String.join("\n", lines);
  }

  private static String value(String record, String key) {
    for (String line : record.split("\\n", -1)) {
      if (line.startsWith(key)) return line.substring(key.length());
    }
    throw new IllegalArgumentException("missing " + key);
  }

  private static String withRecordChecksum(String record) throws Exception {
    int checksum = record.lastIndexOf("record-sha256=");
    String prefix = record.substring(0, checksum);
    byte[] digest = MessageDigest.getInstance("SHA-256")
        .digest(prefix.getBytes(StandardCharsets.UTF_8));
    try {
      return prefix + "record-sha256=" + HexFormat.of().formatHex(digest) + "\n";
    } finally {
      java.util.Arrays.fill(digest, (byte) 0);
    }
  }
}
