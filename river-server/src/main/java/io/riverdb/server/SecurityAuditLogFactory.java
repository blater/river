package io.riverdb.server;

import io.riverdb.base.error.StatusCode;
import io.riverdb.base.id.DatabaseIncarnation;
import io.riverdb.platform.riverd.RiverDirectory;

/** Descriptor-relative construction and recovery for the opaque audit owner. */
public final class SecurityAuditLogFactory {
  public static final long DEFAULT_ACTIVE_MAXIMUM_BYTES = 64L * 1024L * 1024L;
  public static final long DEFAULT_PENDING_MAXIMUM_BYTES = 256L * 1024L;

  private SecurityAuditLogFactory() { }

  /** Creates a new instance-bound audit set; any pre-existing authority is corruption. */
  public static StatusCode create(
      RiverDirectory directory,
      DatabaseIncarnation incarnation,
      long credentialGeneration,
      long activeMaximumBytes,
      long pendingMaximumBytes,
      SecurityAuditOpenResult result) {
    return open(directory, incarnation, credentialGeneration, activeMaximumBytes,
        pendingMaximumBytes, result, true);
  }

  /** Reopens an existing instance-bound audit set without creating or mutating children. */
  public static StatusCode open(
      RiverDirectory directory,
      DatabaseIncarnation incarnation,
      long credentialGeneration,
      long activeMaximumBytes,
      long pendingMaximumBytes,
      SecurityAuditOpenResult result) {
    return open(directory, incarnation, credentialGeneration, activeMaximumBytes,
        pendingMaximumBytes, result, false);
  }

  private static StatusCode open(
      RiverDirectory directory,
      DatabaseIncarnation incarnation,
      long credentialGeneration,
      long activeMaximumBytes,
      long pendingMaximumBytes,
      SecurityAuditOpenResult result,
      boolean create) {
    if (result != null) result.reset();
    if (directory == null || incarnation == null || !incarnation.isValid()
        || credentialGeneration <= 0 || result == null) {
      if (directory != null) directory.close();
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    AuditFormat format = new AuditFormat();
    StatusCode status = format.validate(activeMaximumBytes, pendingMaximumBytes);
    if (!status.isOk()) {
      directory.close();
      return status;
    }
    AuditBootstrap.OpenResult opened = new AuditBootstrap.OpenResult();
    status = create
        ? AuditBootstrap.create(directory, format, incarnation.high(), incarnation.low(),
            credentialGeneration, activeMaximumBytes, pendingMaximumBytes, opened)
        : AuditBootstrap.openExisting(directory, format, incarnation.high(), incarnation.low(),
            credentialGeneration, activeMaximumBytes, pendingMaximumBytes, opened);
    if (!status.isOk()) {
      directory.close();
      return status;
    }
    SecurityAuditLog audit = null;
    try {
      audit = new SecurityAuditLog(
          opened.owner(), opened.active(), opened.controlA(), opened.controlB(), format,
          activeMaximumBytes, pendingMaximumBytes, opened.auditGeneration(),
          incarnation.high(), incarnation.low(), credentialGeneration, opened.firstSequence(),
          opened.controlGeneration(), opened.controlSlot() == 0, opened.controlDigest(),
          AuditDigest.name("audit-1.log"), AuditDigest.name("audit-1.log"),
          AuditDigest.name(""), encodedNames(), opened.durableSequence(),
          opened.durableBytes(), opened.exhausted());
      status = audit.start();
      if (!status.isOk()) {
        closeAudit(audit);
        return status;
      }
      result.set(audit);
      return StatusCode.OK;
    } catch (RuntimeException failure) {
      if (audit != null) closeAudit(audit);
      else {
        closeFile(opened.active());
        closeFile(opened.controlA());
        closeFile(opened.controlB());
        directory.close();
      }
      return StatusCode.INVARIANT_BROKEN;
    }
  }

  private static void closeAudit(SecurityAuditLog audit) {
    audit.beginClose();
    audit.finishClose();
  }

  private static void closeFile(io.riverdb.platform.riverd.RiverFile file) {
    if (file != null) file.close();
  }

  private static byte[] encodedNames() {
    java.nio.ByteBuffer source = AuditNames.encode("audit-1.log", "audit-1.log", "");
    byte[] names = new byte[source.remaining()];
    source.get(names);
    return names;
  }
}
