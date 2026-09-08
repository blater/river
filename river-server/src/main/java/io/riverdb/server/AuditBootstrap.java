package io.riverdb.server;

import io.riverdb.base.error.StatusCode;
import io.riverdb.platform.file.DirectoryOperationResult;
import io.riverdb.platform.file.ForceMode;
import io.riverdb.platform.file.IoResult;
import io.riverdb.platform.riverd.RiverDirectory;
import io.riverdb.platform.riverd.RiverFile;
import io.riverdb.platform.riverd.RiverFileResult;
import io.riverdb.platform.riverd.RiverOpenMode;
import java.nio.ByteBuffer;
import java.util.Arrays;

/** Descriptor-relative instance-bound creation/open seam for the credentials owner. */
final class AuditBootstrap {
  private static final String ACTIVE_NAME = "audit-1.log";
  private static final String CONTROL_A_NAME = "audit-control-a";
  private static final String CONTROL_B_NAME = "audit-control-b";

  private AuditBootstrap() { }

  static StatusCode create(
      RiverDirectory owner,
      AuditFormat format,
      long instanceHigh,
      long instanceLow,
      long credentialGeneration,
      long activeMaximumBytes,
      long pendingMaximumBytes,
      OpenResult result) {
    return openInternal(owner, format, instanceHigh, instanceLow, credentialGeneration,
        activeMaximumBytes, pendingMaximumBytes, true, result);
  }

  static StatusCode openExisting(
      RiverDirectory owner,
      AuditFormat format,
      long instanceHigh,
      long instanceLow,
      long credentialGeneration,
      long activeMaximumBytes,
      long pendingMaximumBytes,
      OpenResult result) {
    return openInternal(owner, format, instanceHigh, instanceLow, credentialGeneration,
        activeMaximumBytes, pendingMaximumBytes, false, result);
  }

  private static StatusCode openInternal(
      RiverDirectory owner,
      AuditFormat format,
      long instanceHigh,
      long instanceLow,
      long credentialGeneration,
      long activeMaximumBytes,
      long pendingMaximumBytes,
      boolean createMode,
      OpenResult result) {
    if (owner == null || format == null || result == null || instanceHigh == 0 && instanceLow == 0
        || credentialGeneration <= 0) return StatusCode.INVALID_EXTERNAL_INPUT;
    result.reset();
    RiverFileResult activeResult = new RiverFileResult();
    StatusCode status = createMode
        ? owner.createFile(ACTIVE_NAME, activeResult)
        : owner.openFile(ACTIVE_NAME, RiverOpenMode.EXISTING, activeResult);
    if (!createMode && status == StatusCode.CONFLICT) status = StatusCode.CORRUPTION;
    if (createMode && status == StatusCode.CONFLICT) status = StatusCode.CORRUPTION;
    if (!status.isOk()) {
      closeFiles(activeResult.file(), null, null);
      return status;
    }
    RiverFileResult controlAResult = new RiverFileResult();
    RiverFileResult controlBResult = new RiverFileResult();
    status = createMode
        ? createFresh(owner, CONTROL_A_NAME, controlAResult)
        : openExisting(owner, CONTROL_A_NAME, controlAResult);
    if (status.isOk()) {
      status = createMode
          ? createFresh(owner, CONTROL_B_NAME, controlBResult)
          : openExisting(owner, CONTROL_B_NAME, controlBResult);
    }
    if (!status.isOk()) {
      closeFiles(activeResult.file(), controlAResult.file(), controlBResult.file());
      return status;
    }
    RiverFile active = activeResult.file();
    RiverFile controlA = controlAResult.file();
    RiverFile controlB = controlBResult.file();
    if (createMode) {
      AuditHeader header = new AuditHeader(format);
      status = header.encode(1, instanceHigh, instanceLow, credentialGeneration, 1);
      if (status.isOk()) status = write(active, 0, header.bytes());
      if (status.isOk()) status = active.force(ForceMode.CONTENT_AND_METADATA);
      if (status.isOk()) status = publish(owner);
      if (status.isOk()) {
        AuditControl control = new AuditControl(format);
        byte[] activeDigest = AuditDigest.file(active);
        byte[] noPredecessor = AuditDigest.name("");
        status = control.encode(1, AuditControl.ACTIVE, instanceHigh, instanceLow, 1,
            1, 1, 0, format.headerBytes(), activeDigest, 0, noPredecessor,
            AuditDigest.name(ACTIVE_NAME), AuditDigest.name(ACTIVE_NAME),
            AuditDigest.name(""),
            AuditNames.encode(ACTIVE_NAME, ACTIVE_NAME, ""));
        if (status.isOk()) status = write(controlA, 0, control.bytes());
        if (status.isOk()) status = controlA.force(ForceMode.CONTENT_AND_METADATA);
        if (status.isOk()) status = write(controlB, 0, control.bytes());
        if (status.isOk()) status = controlB.force(ForceMode.CONTENT_AND_METADATA);
        if (status.isOk()) status = publish(owner);
      }
    } else {
      AuditControlRecovery.Result control = new AuditControlRecovery.Result();
      status = AuditControlRecovery.recover(
          controlA, controlB, format, instanceHigh, instanceLow, control);
      AuditRecovery.RecoveryResult recovered = new AuditRecovery.RecoveryResult();
      if (status.isOk()) {
        status = AuditRecovery.recoverActive(
            active, format, control.firstSequence(), control.auditGeneration(), instanceHigh, instanceLow,
            credentialGeneration, recovered);
      }
      if (status.isOk()) {
        long committedCount = control.durableSequence() - control.firstSequence() + 1;
        boolean countAddressable = committedCount >= 0
            && committedCount <= (Long.MAX_VALUE - format.headerBytes()) / format.eventBytes();
        long committedLength = countAddressable
            ? format.headerBytes() + committedCount * (long) format.eventBytes() : -1;
        if (!countAddressable
            || recovered.durableSequence() < control.durableSequence()
            || recovered.durableBytes() < control.durableLength()
            || committedLength != control.durableLength()) {
          status = StatusCode.CORRUPTION;
        } else {
          byte[] committedDigest = AuditDigest.prefix(active, control.durableLength());
          if (committedDigest == null) status = StatusCode.IO_FAILURE;
          else if (!Arrays.equals(committedDigest, control.activeDigest())) {
            status = StatusCode.CORRUPTION;
          }
        }
      }
      if (status.isOk() && control.state() == AuditControl.EXHAUSTED) {
        byte[] activeDigest = AuditDigest.file(active);
        if (activeDigest == null) {
          status = StatusCode.IO_FAILURE;
        } else if (recovered.durableBytes() != control.durableLength()
            || recovered.durableSequence() != control.durableSequence()
            || !Arrays.equals(activeDigest, control.activeDigest())) {
          status = StatusCode.CORRUPTION;
        }
      }
      if (status.isOk()) result.setRecovery(recovered.durableSequence(), recovered.durableBytes());
      if (status.isOk()) result.setAuthority(
          control.generation(), control.selectedSlot(), control.controlDigest(),
          control.state() == AuditControl.EXHAUSTED);
      if (status.isOk()) result.setAuditIdentity(control.auditGeneration(), control.firstSequence());
    }
    if (!status.isOk()) {
      closeFiles(active, controlA, controlB);
      return status;
    }
    if (createMode) {
      result.setRecovery(0, format.headerBytes());
      byte[] controlDigest = AuditDigest.file(controlA);
      if (controlDigest == null) {
        closeFiles(active, controlA, controlB);
        return StatusCode.IO_FAILURE;
      }
      result.setAuthority(1, 0, controlDigest, false);
      result.setAuditIdentity(1, 1);
    }
    result.set(active, controlA, controlB, owner);
    return StatusCode.OK;
  }

  private static StatusCode createFresh(RiverDirectory owner, String name, RiverFileResult result) {
    StatusCode status = owner.createFile(name, result);
    return status == StatusCode.CONFLICT ? StatusCode.CORRUPTION : status;
  }

  private static StatusCode openExisting(
      RiverDirectory owner, String name, RiverFileResult result) {
    StatusCode status = owner.openFile(name, RiverOpenMode.EXISTING, result);
    return status == StatusCode.CONFLICT ? StatusCode.CORRUPTION : status;
  }

  private static StatusCode write(RiverFile file, long position, ByteBuffer source) {
    IoResult io = new IoResult();
    while (source.hasRemaining()) {
      io.reset();
      StatusCode status = file.write(position, source, io);
      if (!status.isOk() || io.bytesTransferred() <= 0) {
        return status.isOk() ? StatusCode.IO_FAILURE : status;
      }
      position += io.bytesTransferred();
    }
    return StatusCode.OK;
  }

  private static StatusCode publish(RiverDirectory owner) {
    return owner.force(new DirectoryOperationResult());
  }

  private static void closeFiles(RiverFile active, RiverFile controlA, RiverFile controlB) {
    if (active != null) active.close();
    if (controlA != null) controlA.close();
    if (controlB != null) controlB.close();
  }

  static final class OpenResult {
    private RiverDirectory owner;
    private RiverFile active;
    private RiverFile controlA;
    private RiverFile controlB;
    private long durableSequence;
    private long durableBytes;
    private long controlGeneration;
    private int controlSlot;
    private byte[] controlDigest;
    private boolean exhausted;
    private long auditGeneration;
    private long firstSequence;

    void reset() {
      owner = null; active = null; controlA = null; controlB = null;
      durableSequence = 0; durableBytes = 0;
      controlGeneration = 0; controlSlot = -1; controlDigest = null; exhausted = false;
      auditGeneration = 0; firstSequence = 0;
    }
    void set(RiverFile a, RiverFile ca, RiverFile cb, RiverDirectory d) {
      active = a; controlA = ca; controlB = cb; owner = d;
    }
    void setRecovery(long sequence, long bytes) { durableSequence = sequence; durableBytes = bytes; }
    void setAuthority(long generation, int slot, byte[] digest, boolean terminal) {
      controlGeneration = generation; controlSlot = slot;
      controlDigest = digest == null ? null : digest.clone(); exhausted = terminal;
    }
    void setAuditIdentity(long audit, long first) {
      auditGeneration = audit; firstSequence = first;
    }
    RiverDirectory owner() { return owner; }
    RiverFile active() { return active; }
    RiverFile controlA() { return controlA; }
    RiverFile controlB() { return controlB; }
    long durableSequence() { return durableSequence; }
    long durableBytes() { return durableBytes; }
    long controlGeneration() { return controlGeneration; }
    int controlSlot() { return controlSlot; }
    byte[] controlDigest() { return controlDigest == null ? null : controlDigest.clone(); }
    boolean exhausted() { return exhausted; }
    long auditGeneration() { return auditGeneration; }
    long firstSequence() { return firstSequence; }
  }
}
