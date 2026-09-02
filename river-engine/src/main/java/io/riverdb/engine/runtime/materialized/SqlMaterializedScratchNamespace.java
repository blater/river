package io.riverdb.engine.runtime.materialized;

import io.riverdb.base.error.StatusCode;
import io.riverdb.base.error.StatusDetail;
import io.riverdb.base.id.DatabaseIncarnation;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryIteratorException;
import java.nio.file.DirectoryStream;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/** Creates the deterministic database namespace and one retained open-instance path. */
final class SqlMaterializedScratchNamespace {
  private static final char[] HEX = "0123456789abcdef".toCharArray();
  private static final String PREFIX = "river-sql-";

  private SqlMaterializedScratchNamespace() {}

  static StatusCode create(
      Path spillRoot,
      Path authoritativePrimaryPath,
      DatabaseIncarnation database,
      Result target,
      StatusDetail detail) {
    target.reset();
    if (spillRoot == null || authoritativePrimaryPath == null
        || database == null || !database.isValid()) {
      return fail(detail, StatusCode.INVALID_EXTERNAL_INPUT, "invalid scratch namespace identity");
    }
    Path primary;
    String name;
    try {
      primary = authoritativePrimaryPath.toRealPath();
      name = name(database, primary);
    } catch (IOException | SecurityException failure) {
      return fail(detail, StatusCode.IO_FAILURE, "cannot resolve primary database path");
    } catch (NoSuchAlgorithmException failure) {
      return fail(detail, StatusCode.INVARIANT_BROKEN, "SHA-256 is unavailable");
    } catch (OutOfMemoryError failure) {
      return fail(detail, StatusCode.RESOURCE_EXHAUSTED, "cannot create scratch namespace identity");
    }
    Path namespace;
    Path instance = null;
    SqlMaterializedScratchOwnership ownership = null;
    try {
      namespace = spillRoot.resolve(name);
      createNamespace(namespace);
      SqlMaterializedScratchOwnership.Result ownershipResult =
          new SqlMaterializedScratchOwnership.Result();
      StatusCode status = SqlMaterializedScratchOwnership.acquire(
          namespace, ownershipResult, detail);
      if (!status.isOk()) return status;
      ownership = ownershipResult.ownership();
      SqlMaterializedScratchCleanup.State cleanup =
          new SqlMaterializedScratchCleanup.State().begin(detail);
      reclaimOpenInstances(namespace, cleanup);
      if (!cleanup.status().isOk()) {
        ownership.close(cleanup);
        return cleanup.status();
      }
      instance = Files.createTempDirectory(namespace, "open-");
      target.set(namespace, instance, primary, ownership);
      return StatusCode.OK;
    } catch (IOException | SecurityException failure) {
      if (instance != null) SqlMaterializedScratchCleanup.deleteUnreported(instance);
      closeUnreported(ownership);
      return fail(detail, StatusCode.IO_FAILURE, "cannot create materialized scratch instance");
    } catch (OutOfMemoryError failure) {
      if (instance != null) SqlMaterializedScratchCleanup.deleteUnreported(instance);
      closeUnreported(ownership);
      return fail(detail, StatusCode.RESOURCE_EXHAUSTED, "cannot retain materialized scratch instance");
    }
  }

  private static void reclaimOpenInstances(
      Path namespace, SqlMaterializedScratchCleanup.State cleanup) {
    try (DirectoryStream<Path> entries = Files.newDirectoryStream(namespace)) {
      for (Path entry : entries) {
        Path name = entry.getFileName();
        if (name != null && name.toString().startsWith("open-")) {
          SqlMaterializedScratchCleanup.deleteTree(entry, cleanup);
        }
      }
    } catch (DirectoryIteratorException failure) {
      cleanup.record(StatusCode.IO_FAILURE, namespace);
    } catch (IOException | SecurityException failure) {
      cleanup.record(StatusCode.IO_FAILURE, namespace);
    }
  }

  private static void closeUnreported(SqlMaterializedScratchOwnership ownership) {
    if (ownership == null) return;
    ownership.close(new SqlMaterializedScratchCleanup.State().begin(null));
  }

  private static void createNamespace(Path namespace) throws IOException {
    try {
      Files.createDirectory(namespace);
    } catch (FileAlreadyExistsException exists) {
      if (!Files.isDirectory(namespace, LinkOption.NOFOLLOW_LINKS)) throw exists;
    }
  }

  private static String name(DatabaseIncarnation database, Path primary)
      throws NoSuchAlgorithmException {
    byte[] digest = MessageDigest.getInstance("SHA-256")
        .digest(primary.toString().getBytes(StandardCharsets.UTF_8));
    char[] text = new char[PREFIX.length() + 32 + 1 + 16];
    PREFIX.getChars(0, PREFIX.length(), text, 0);
    int offset = PREFIX.length();
    offset = hex(database.high(), text, offset);
    offset = hex(database.low(), text, offset);
    text[offset++] = '-';
    for (int index = 0; index < 8; index++) {
      int value = digest[index] & 0xff;
      text[offset++] = HEX[value >>> 4];
      text[offset++] = HEX[value & 0x0f];
    }
    return new String(text);
  }

  private static int hex(long value, char[] target, int offset) {
    for (int shift = 60; shift >= 0; shift -= 4) {
      target[offset++] = HEX[(int) (value >>> shift) & 0x0f];
    }
    return offset;
  }

  private static StatusCode fail(StatusDetail detail, StatusCode code, String message) {
    if (detail != null) detail.set(code).append(message);
    return code;
  }

  static final class Result {
    private Path namespace;
    private Path instance;
    private Path primary;
    private SqlMaterializedScratchOwnership ownership;

    void reset() {
      namespace = null;
      instance = null;
      primary = null;
      ownership = null;
    }

    void set(
        Path namespacePath,
        Path instancePath,
        Path primaryPath,
        SqlMaterializedScratchOwnership namespaceOwnership) {
      namespace = namespacePath;
      instance = instancePath;
      primary = primaryPath;
      ownership = namespaceOwnership;
    }

    Path namespace() { return namespace; }
    Path instance() { return instance; }
    Path primary() { return primary; }
    SqlMaterializedScratchOwnership ownership() { return ownership; }
  }
}
