package io.riverdb.server.app;

import io.riverdb.base.error.StatusCode;
import io.riverdb.platform.file.DirectoryListResult;
import io.riverdb.platform.riverd.*;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.Arrays;

final class RiverDaemonIdentityNamespace {
  private RiverDaemonIdentityNamespace() {}
  static boolean validDatadir(Path path) {
    return path != null && RiverDaemonIdentityRecords.validDatadir(path.toString());
  }

  static String canonicalPath(Path path) {
    return path.toAbsolutePath().normalize().toString();
  }

  static String nonce(SecureRandom random) {
    byte[] bytes = new byte[16];
    random.nextBytes(bytes);
    String value = java.util.HexFormat.of().formatHex(bytes);
    Arrays.fill(bytes, (byte) 0);
    return value;
  }

  static StatusCode proveOwnerAbsent(RiverDaemonIdentityRecords.LockRecord owner) {
    var process = ProcessHandle.of(owner.pid);
    if (process.isEmpty() || !process.get().isAlive()) return StatusCode.OK;
    var info = process.get().info();
    if (info.startInstant().isEmpty()) {
      return StatusCode.FEATURE_NOT_SUPPORTED;
    }
    long start = info.startInstant().get().toEpochMilli();
    return owner.start == start
        ? StatusCode.CONFLICT : StatusCode.OK;
  }

  static RiverDaemonIdentityRecords.LockRecord bootstrapOwner(
      Path datadir, RiverDaemonIdentityRecords.BootstrapRecord bootstrap) {
    return new RiverDaemonIdentityRecords.LockRecord(
        canonicalPath(datadir), bootstrap.high, bootstrap.low, bootstrap.pid, bootstrap.start,
        bootstrap.nonce);
  }

  static boolean hasEntry(DirectoryListResult entries, String wanted) {
    for (int index = 0; index < entries.size(); index++) {
      if (wanted.equals(entries.name(index))) return true;
    }
    return false;
  }

  static boolean isLifecycleEntry(String name) {
    if ("stop.request".equals(name)) return true;
    if (name.startsWith(".stop-request-") && name.endsWith(".stage")) {
      String nonce = name.substring(".stop-request-".length(), name.length() - ".stage".length());
      return nonce.matches("[0-9a-f]{32}");
    }
    if (name.startsWith(".stop-accepted-")) {
      String nonce = name.substring(".stop-accepted-".length());
      return nonce.matches("[0-9a-f]{32}");
    }
    return false;
  }

  static boolean isIdentityResidueEntry(String name) {
    if (name.startsWith(".bootstrap-") && name.endsWith(".stage")) {
      String nonce = name.substring(".bootstrap-".length(), name.length() - ".stage".length());
      return nonce.matches("[0-9a-f]{32}");
    }
    if (name.startsWith(".riverd-bootstrap-")) {
      String nonce = name.substring(".riverd-bootstrap-".length());
      return nonce.matches("[0-9a-f]{32}");
    }
    if (name.startsWith(".instance-") && name.endsWith(".stage")) {
      String nonce = name.substring(".instance-".length(), name.length() - ".stage".length());
      return nonce.matches("[0-9a-f]{32}");
    }
    return false;
  }

  static String prebootstrapStageName(
      DirectoryListResult entries, boolean hasBootstrap, boolean hasLock) {
    if (hasBootstrap || !hasLock || entries.size() != 2) return null;
    String stage = null;
    for (int index = 0; index < entries.size(); index++) {
      String name = entries.name(index);
      if (RiverDaemonIdentity.LOCK_FILE.equals(name)) continue;
      if (stage != null || !name.startsWith(".bootstrap-") || !name.endsWith(".stage")) {
        return null;
      }
      String value = name.substring(".bootstrap-".length(), name.length() - ".stage".length());
      if (!value.matches("[0-9a-f]{32}")) return null;
      stage = name;
    }
    return stage;
  }

}
