package io.riverdb.testsupport;

import io.riverdb.base.error.StatusCode;
import io.riverdb.base.id.DatabaseIncarnation;
import io.riverdb.platform.riverd.RiverDirectory;
import io.riverdb.platform.riverd.RiverDaemonFileSystem;
import io.riverdb.platform.riverd.RiverDirectoryResult;
import io.riverdb.platform.riverd.apfs.ApfsRiverDaemonFileSystem;
import io.riverdb.platform.riverd.linux.LinuxRiverDaemonFileSystem;
import io.riverdb.server.SecurityAuditLog;
import io.riverdb.server.SecurityAuditLogFactory;
import io.riverdb.server.SecurityAuditOpenResult;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.Set;

/** Test owner fixture that exercises the same verified directory seam as riverd. */
public final class SecurityAuditTestOwner {
  private SecurityAuditTestOwner() { }

  public static SecurityAuditLog create(Path root, DatabaseIncarnation incarnation,
      long credentialGeneration, long activeBytes, long pendingBytes) throws IOException {
    return open(root, incarnation, credentialGeneration, activeBytes, pendingBytes, true);
  }

  public static SecurityAuditLog reopen(Path root, DatabaseIncarnation incarnation,
      long credentialGeneration, long activeBytes, long pendingBytes) throws IOException {
    return open(root, incarnation, credentialGeneration, activeBytes, pendingBytes, false);
  }

  private static SecurityAuditLog open(Path root, DatabaseIncarnation incarnation,
      long credentialGeneration, long activeBytes, long pendingBytes, boolean create)
      throws IOException {
    Path auditPath = root.resolve("audit");
    Files.createDirectories(auditPath);
    Files.setPosixFilePermissions(auditPath, Set.of(
        PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE,
        PosixFilePermission.OWNER_EXECUTE));
    auditPath = auditPath.toRealPath();
    RiverDirectoryResult directoryResult = new RiverDirectoryResult();
    StatusCode status = provider().openDirectory(auditPath, directoryResult);
    if (!status.isOk()) throw new IOException("open audit directory: " + status);
    RiverDirectory directory = directoryResult.directory();
    SecurityAuditOpenResult auditResult = new SecurityAuditOpenResult();
    status = create
        ? SecurityAuditLogFactory.create(directory, incarnation, credentialGeneration,
            activeBytes, pendingBytes, auditResult)
        : SecurityAuditLogFactory.open(directory, incarnation, credentialGeneration,
            activeBytes, pendingBytes, auditResult);
    if (!status.isOk()) throw new IOException("open audit owner: " + status);
    return auditResult.audit();
  }

  private static RiverDaemonFileSystem provider() throws IOException {
    String name = System.getProperty("os.name");
    if ("Mac OS X".equals(name)) return new ApfsRiverDaemonFileSystem();
    if (name != null && name.startsWith("Linux")) return new LinuxRiverDaemonFileSystem();
    throw new IOException("unsupported riverd test provider: " + name);
  }
}
