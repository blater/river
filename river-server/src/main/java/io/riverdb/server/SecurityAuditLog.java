package io.riverdb.server;

import io.riverdb.base.concurrent.CancellationToken;
import io.riverdb.base.error.StatusCode;
import io.riverdb.platform.riverd.RiverDirectory;
import io.riverdb.platform.riverd.RiverFile;
import io.riverdb.platform.file.ForceMode;
import io.riverdb.platform.file.IoResult;
import java.nio.ByteBuffer;

/** Opaque provider-owned durable security-audit owner. */
public final class SecurityAuditLog {
  public static final int AUTHENTICATION_DECISION = AuditRecord.AUTHENTICATION_DECISION;
  public static final int STATEMENT_ADMISSION_DECISION = AuditRecord.STATEMENT_ADMISSION_DECISION;
  private final RiverDirectory directory;
  private final RiverFile activeFile;
  private final RiverFile controlA;
  private final RiverFile controlB;
  private final AuditFormat format;
  private final long activeMaximumBytes;
  private final long pendingMaximumBytes;
  private final long auditGeneration;
  private final long instanceHigh;
  private final long instanceLow;
  private final long credentialGeneration;
  private final long firstSequence;
  private final byte[] predecessorDigest;
  private final long predecessorControlGeneration;
  private boolean controlAActive;
  private final byte[] oldNameDigest;
  private final byte[] newNameDigest;
  private final byte[] archiveNameDigest;
  private final byte[] encodedControlNames;
  private final Slot[] slots;
  private final Object lock = new Object();
  private final IoResult io = new IoResult();
  private final Slot[] cohort;
  private Thread coordinator;
  private boolean closing;
  private boolean closed;
  private boolean fenced;
  private boolean exhausting;
  private boolean exhausted;
  private StatusCode terminalFailure = StatusCode.OK;
  private long nextSequence = 1;
  private long durableFrontier;
  private long durableBytes;
  private long pendingBytes;
  private long activeBytes;
  private long decisions;
  private long appendedBytes;
  private long batches;
  private long forceCalls;
  private long forceNanos;
  private long pendingHighWater;
  private long capacityRejections;
  private long pressureRejections;
  private long cancellations;
  private long fences;
  private final long[] cohortHistogram = new long[SecurityAuditSnapshot.COHORT_HISTOGRAM_BUCKETS];
  // Coordinator-only observations; published under lock after each I/O attempt.
  private long batchWrittenBytes;
  private long batchForceCalls;
  private long batchForceNanos;

  SecurityAuditLog(
      RiverDirectory directory,
      RiverFile activeFile,
      RiverFile controlA,
      RiverFile controlB,
      AuditFormat format,
      long activeMaximumBytes,
      long pendingMaximumBytes,
      long auditGeneration,
      long instanceHigh,
      long instanceLow,
      long credentialGeneration,
      long firstSequence,
      long predecessorControlGeneration,
      boolean controlAActive,
      byte[] predecessorDigest,
      byte[] oldNameDigest,
      byte[] newNameDigest,
      byte[] archiveNameDigest,
      byte[] encodedControlNames,
      long recoveredDurableFrontier,
      long recoveredDurableBytes,
      boolean recoveredExhausted) {
    this.directory = directory;
    this.activeFile = activeFile;
    this.controlA = controlA;
    this.controlB = controlB;
    this.format = format;
    this.activeMaximumBytes = activeMaximumBytes;
    this.pendingMaximumBytes = pendingMaximumBytes;
    this.auditGeneration = auditGeneration;
    this.instanceHigh = instanceHigh;
    this.instanceLow = instanceLow;
    this.credentialGeneration = credentialGeneration;
    this.firstSequence = firstSequence;
    this.predecessorControlGeneration = predecessorControlGeneration;
    this.controlAActive = controlAActive;
    this.predecessorDigest = predecessorDigest.clone();
    this.oldNameDigest = oldNameDigest.clone();
    this.newNameDigest = newNameDigest.clone();
    this.archiveNameDigest = archiveNameDigest.clone();
    this.encodedControlNames = encodedControlNames.clone();
    durableFrontier = recoveredDurableFrontier;
    nextSequence = recoveredDurableFrontier + 1;
    durableBytes = recoveredDurableBytes;
    activeBytes = recoveredDurableBytes;
    exhausted = recoveredExhausted;
    int slotCount = format.validate(activeMaximumBytes, pendingMaximumBytes).isOk()
        ? (int) (pendingMaximumBytes / format.slotBytes()) : 0;
    slots = new Slot[slotCount];
    for (int index = 0; index < slots.length; index++) slots[index] = new Slot(format);
    cohort = new Slot[slotCount];
  }

  StatusCode start() {
    StatusCode status = format.validate(activeMaximumBytes, pendingMaximumBytes);
    if (status.isOk() && exhausted) status = StatusCode.RESOURCE_EXHAUSTED;
    if (status.isOk() && (auditGeneration <= 0 || auditGeneration == Long.MAX_VALUE
        || firstSequence <= 0 || firstSequence == Long.MAX_VALUE
        || durableFrontier < firstSequence - 1 || durableFrontier == Long.MAX_VALUE
        || durableBytes < format.headerBytes() || durableBytes > activeMaximumBytes
        || (durableBytes - format.headerBytes()) % format.eventBytes() != 0
        || (durableBytes - format.headerBytes()) / format.eventBytes()
            != durableFrontier - firstSequence + 1)) {
      status = StatusCode.CORRUPTION;
    }
    if (status.isOk() && durableBytes > activeMaximumBytes - 2L * format.eventBytes()) {
      status = StatusCode.RESOURCE_EXHAUSTED;
    }
    if (status.isOk() && (predecessorDigest.length != 32 || oldNameDigest.length != 32
        || newNameDigest.length != 32 || archiveNameDigest.length != 32)) {
      status = StatusCode.CORRUPTION;
    }
    if (status.isOk() && encodedControlNames.length > format.controlBytes() - 244) {
      status = StatusCode.CORRUPTION;
    }
    if (!status.isOk() || slots.length < 2) return status.isOk()
        ? StatusCode.INVALID_EXTERNAL_INPUT : status;
    synchronized (lock) {
      if (coordinator != null) return StatusCode.CONFLICT;
      coordinator = Thread.ofPlatform().name("river-audit-coordinator").unstarted(this::runCoordinator);
      coordinator.start();
    }
    return StatusCode.OK;
  }

  StatusCode append(
      long principalId,
      int eventClass,
      long connectionCorrelation,
      long sessionCorrelation,
      long requestCorrelation,
      int phase,
      int programStep,
      int permission,
      boolean allowed,
      StatusCode decisionStatus,
      CancellationToken cancellation,
      long deadlineNanos) {
    CancellationToken token = cancellation == null ? CancellationToken.NONE : cancellation;
    StatusCode before = beforeWait(token, deadlineNanos);
    if (!before.isOk()) return before;
    Slot slot;
    synchronized (lock) {
      before = beforeWait(token, deadlineNanos);
      if (!before.isOk()) return before;
      if (closed || closing) return StatusCode.CLOSED;
      if (fenced) return StatusCode.FENCED;
      if (exhausted) return StatusCode.RESOURCE_EXHAUSTED;
      if (exhausting) return StatusCode.RETRY;
      if (nextSequence == Long.MAX_VALUE) {
        exhausting = true;
        lock.notifyAll();
        return awaitExhaustion(token, deadlineNanos);
      }
      if (activeBytes > activeMaximumBytes - format.eventBytes()
          || activeBytes > Long.MAX_VALUE - format.eventBytes()) {
        capacityRejections = add(capacityRejections, 1);
        return StatusCode.RESOURCE_EXHAUSTED;
      }
      if (pendingBytes > pendingMaximumBytes - format.slotBytes()
          || pendingBytes > Long.MAX_VALUE - format.slotBytes()) {
        pressureRejections = add(pressureRejections, 1);
        return StatusCode.RETRY;
      }
      slot = freeSlot();
      if (slot == null) {
        pressureRejections = add(pressureRejections, 1);
        return StatusCode.RETRY;
      }
      long sequence = nextSequence;
      StatusCode encoded = slot.record.encode(
          sequence, auditGeneration, instanceHigh, instanceLow, credentialGeneration,
          principalId, connectionCorrelation, sessionCorrelation,
          requestCorrelation, eventClass, phase,
          programStep, permission, allowed, decisionStatus.stableCode());
      if (!encoded.isOk()) {
        slot.state = Slot.FREE;
        return encoded;
      }
      nextSequence++;
      activeBytes += format.eventBytes();
      slot.sequence = sequence;
      slot.connectionCorrelation = connectionCorrelation;
      slot.sessionCorrelation = sessionCorrelation;
      slot.requestCorrelation = requestCorrelation;
      slot.allowed = allowed;
      slot.state = Slot.SEALED;
      pendingBytes += format.slotBytes();
      pendingHighWater = Math.max(pendingHighWater, pendingBytes);
      lock.notifyAll();
    }
    return await(slot, token, deadlineNanos);
  }

  public void beginClose() {
    synchronized (lock) {
      if (closed) return;
      closing = true;
      for (Slot slot : slots) {
        if (slot.state == Slot.SEALED || slot.state == Slot.APPENDED) slot.closeRejected = true;
      }
      lock.notifyAll();
    }
  }

  public SecurityAuditSnapshot snapshot() {
    synchronized (lock) {
      return new SecurityAuditSnapshot(decisions, appendedBytes, batches, forceCalls,
          forceNanos, cohortHistogram, pendingHighWater, capacityRejections,
          pressureRejections, cancellations, fences, durableFrontier);
    }
  }

  public long recordCount() {
    synchronized (lock) {
      return durableFrontier - firstSequence + 1;
    }
  }

  /** Called by the connection cancellation owner; it wakes the borrowed caller without I/O. */
  public void cancelRequest(long connectionCorrelation, long sessionCorrelation, long requestCorrelation) {
    if (connectionCorrelation < 0 || sessionCorrelation < 0 || requestCorrelation < 0) return;
    synchronized (lock) {
      for (Slot slot : slots) {
        if ((slot.state == Slot.SEALED || slot.state == Slot.APPENDED)
            && slot.connectionCorrelation == connectionCorrelation
            && slot.sessionCorrelation == sessionCorrelation
            && slot.requestCorrelation == requestCorrelation) {
          // The production slot also stores connection/session identity; the
          // The owner matches the full connection/session/request identity.
          slot.cancelRequested = true;
        }
      }
      lock.notifyAll();
    }
  }

  public void cancelConnection(long connectionCorrelation) {
    synchronized (lock) {
      for (Slot slot : slots) {
        if ((slot.state == Slot.SEALED || slot.state == Slot.APPENDED)
            && slot.connectionCorrelation == connectionCorrelation) {
          slot.cancelRequested = true;
        }
      }
      lock.notifyAll();
    }
  }

  // Called with lock held; waiting releases it so the coordinator owns the transition.
  private StatusCode awaitExhaustion(CancellationToken token, long deadlineNanos) {
    while (!exhausted && !fenced) {
      StatusCode status = beforeWait(token, deadlineNanos);
      if (!status.isOk()) return status;
      try {
        if (deadlineNanos == 0) lock.wait();
        else {
          long remaining = deadlineNanos - System.nanoTime();
          if (remaining <= 0) return StatusCode.TIMEOUT;
          lock.wait(remaining / 1_000_000L, (int) (remaining % 1_000_000L));
        }
      } catch (InterruptedException interrupted) {
        Thread.currentThread().interrupt();
        return StatusCode.CANCELLED;
      }
    }
    return fenced ? terminalFailure : StatusCode.RESOURCE_EXHAUSTED;
  }

  public StatusCode finishClose() {
    beginClose();
    Thread worker;
    StatusCode status = StatusCode.OK;
    synchronized (lock) {
      worker = coordinator;
    }
    if (worker != null) {
      boolean interrupted = false;
      while (worker.isAlive()) {
        try {
          worker.join();
        } catch (InterruptedException interruption) {
          interrupted = true;
          status = StatusCode.CANCELLED;
        }
      }
      if (interrupted) Thread.currentThread().interrupt();
    }
    StatusCode activeStatus = activeFile.close();
    StatusCode controlAStatus = controlA.close();
    StatusCode controlBStatus = controlB.close();
    StatusCode directoryStatus = directory.close();
    if (status.isOk()) status = terminalFailure;
    if (status.isOk()) status = activeStatus;
    if (status.isOk()) status = controlAStatus;
    if (status.isOk()) status = controlBStatus;
    if (status.isOk()) status = directoryStatus;
    synchronized (lock) {
      closed = true;
    }
    return status.isOk() ? directoryStatus : status;
  }

  private StatusCode await(Slot slot, CancellationToken token, long deadlineNanos) {
    synchronized (lock) {
      while (slot.state != Slot.RELEASED && slot.state != Slot.FREE) {
        if (slot.cancelRequested) {
          slot.detached = true;
          cancellations = add(cancellations, 1);
          return StatusCode.CANCELLED;
        }
        StatusCode status = beforeWait(token, deadlineNanos);
        if (!status.isOk()) {
          slot.detached = true;
          cancellations = add(cancellations, 1);
          return status;
        }
        try {
          if (deadlineNanos == 0) lock.wait();
          else {
            long remaining = deadlineNanos - System.nanoTime();
            if (remaining <= 0) {
              slot.detached = true;
              cancellations = add(cancellations, 1);
              return StatusCode.TIMEOUT;
            }
            lock.wait(remaining / 1_000_000L, (int) (remaining % 1_000_000L));
          }
        } catch (InterruptedException interrupted) {
          Thread.currentThread().interrupt();
          slot.detached = true;
          cancellations = add(cancellations, 1);
          return StatusCode.CANCELLED;
        }
      }
      StatusCode result = slot.result;
      if (result.isOk()) {
        if (closing) result = StatusCode.CLOSED;
        else if (slot.cancelRequested) result = StatusCode.CANCELLED;
        else result = beforeWait(token, deadlineNanos);
      }
      if (result == StatusCode.CANCELLED || result == StatusCode.TIMEOUT) {
        cancellations = add(cancellations, 1);
      }
      release(slot);
      return result;
    }
  }

  private void drain() {
    for (;;) {
      int count;
      boolean terminalWork = false;
      synchronized (lock) {
        count = select(this.cohort);
        if (count == 0) {
          if ((closing || exhausting) && pendingBytes == 0) {
            terminalWork = exhausting && !exhausted && !fenced;
            if (!terminalWork && (closing || exhausted || fenced)) return;
          } else {
            try {
              lock.wait();
            } catch (InterruptedException interrupted) {
              Thread.currentThread().interrupt();
              fence(StatusCode.IO_FAILURE);
              return;
            }
            continue;
          }
        }
      }
      if (terminalWork) {
        StatusCode terminal = persistExhausted();
        synchronized (lock) {
          if (terminal.isOk()) exhausted = true;
          else fence(terminal == StatusCode.INVARIANT_BROKEN
              ? terminal : StatusCode.IO_FAILURE);
          lock.notifyAll();
        }
        continue;
      }
      StatusCode status = writeCohort(cohort, count);
      synchronized (lock) {
        appendedBytes = add(appendedBytes, batchWrittenBytes);
        forceCalls = add(forceCalls, batchForceCalls);
        forceNanos = add(forceNanos, batchForceNanos);
        batches = add(batches, 1);
        if (!status.isOk()) {
          fence(status == StatusCode.INVARIANT_BROKEN ? status : StatusCode.IO_FAILURE);
        } else {
          durableFrontier = cohort[count - 1].sequence;
          durableBytes += (long) count * format.eventBytes();
          decisions = add(decisions, count);
          int bucket = Math.min(count, cohortHistogram.length) - 1;
          cohortHistogram[bucket] = add(cohortHistogram[bucket], 1);
          for (int index = 0; index < count; index++) complete(cohort[index],
              cohort[index].closeRejected ? StatusCode.CLOSED : StatusCode.OK);
        }
        lock.notifyAll();
      }
    }
  }

  private void runCoordinator() {
    try {
      drain();
    } finally {
      synchronized (lock) {
        if (!closing && !exhausted && !fenced) fence(StatusCode.INVARIANT_BROKEN);
        for (Slot slot : slots) {
          if (slot.state == Slot.SEALED || slot.state == Slot.APPENDED) {
            fence(StatusCode.INVARIANT_BROKEN);
            break;
          }
        }
        lock.notifyAll();
      }
    }
  }

  private int select(Slot[] cohort) {
    long sequence = durableFrontier + 1;
    int count = 0;
    for (;;) {
      Slot found = null;
      for (Slot slot : slots) {
        if (slot.state == Slot.SEALED && slot.sequence == sequence) {
          found = slot;
          break;
        }
      }
      if (found == null) return count;
      found.state = Slot.APPENDED;
      cohort[count++] = found;
      sequence++;
      if (count == cohort.length) return count;
    }
  }

  private StatusCode writeCohort(Slot[] cohort, int count) {
    batchWrittenBytes = 0;
    batchForceCalls = 0;
    batchForceNanos = 0;
    for (int index = 0; index < count; index++) {
      Slot slot = cohort[index];
      ByteBuffer source = slot.ioBytes;
      source.clear();
      long offset = format.headerBytes()
          + (slot.sequence - firstSequence) * (long) format.eventBytes();
      while (source.hasRemaining()) {
        io.reset();
        StatusCode status = activeFile.write(offset, source, io);
        if (!status.isOk() || io.bytesTransferred() <= 0) return status.isOk()
            ? StatusCode.IO_FAILURE : status;
        offset += io.bytesTransferred();
        batchWrittenBytes += io.bytesTransferred();
      }
    }
    batchForceCalls = 1;
    long started = System.nanoTime();
    StatusCode forced = activeFile.force(ForceMode.CONTENT_AND_METADATA);
    batchForceNanos = Math.max(0, System.nanoTime() - started);
    return forced;
  }

  private StatusCode persistExhausted() {
    AuditControl control = new AuditControl(format);
    StatusCode status = control.encode(
        Long.MAX_VALUE,
        AuditControl.EXHAUSTED,
        instanceHigh,
        instanceLow,
        auditGeneration,
        firstSequence,
        nextSequence,
        durableFrontier,
        activeBytes,
        AuditDigest.file(activeFile),
        predecessorControlGeneration,
        predecessorDigest,
        oldNameDigest,
        newNameDigest,
        archiveNameDigest,
        ByteBuffer.wrap(encodedControlNames));
    if (!status.isOk()) return status;
    RiverFile target = controlAActive ? controlB : controlA;
    ByteBuffer source = control.bytes();
    long position = 0;
    while (source.hasRemaining()) {
      io.reset();
      status = target.write(position, source, io);
      if (!status.isOk() || io.bytesTransferred() <= 0) {
        return status.isOk() ? StatusCode.IO_FAILURE : status;
      }
      position += io.bytesTransferred();
    }
    status = target.force(ForceMode.CONTENT_AND_METADATA);
    if (!status.isOk()) return status;
    controlAActive = !controlAActive;
    io.reset();
    return directory.force(new io.riverdb.platform.file.DirectoryOperationResult());
  }

  private void fence(StatusCode failure) {
    if (!fenced) fences = add(fences, 1);
    fenced = true;
    if (terminalFailure.isOk()) terminalFailure = failure;
    for (Slot slot : slots) {
      if (slot.state == Slot.APPENDED || slot.state == Slot.SEALED) complete(slot, failure);
    }
  }

  private void complete(Slot slot, StatusCode status) {
    slot.result = status;
    slot.state = Slot.RELEASED;
    if (slot.detached) release(slot);
    lock.notifyAll();
  }

  private void release(Slot slot) {
    if (slot.state == Slot.FREE) return;
    slot.state = Slot.FREE;
    slot.closeRejected = false;
    slot.cancelRequested = false;
    slot.detached = false;
    pendingBytes -= format.slotBytes();
    lock.notifyAll();
  }

  private Slot freeSlot() {
    for (Slot slot : slots) if (slot.state == Slot.FREE) return slot;
    return null;
  }

  private static StatusCode beforeWait(CancellationToken token, long deadlineNanos) {
    if (token.isCancellationRequested()) return StatusCode.CANCELLED;
    return deadlineNanos != 0 && deadlineNanos <= System.nanoTime()
        ? StatusCode.TIMEOUT : StatusCode.OK;
  }

  private static long add(long value, long increment) {
    return increment > Long.MAX_VALUE - value ? Long.MAX_VALUE : value + increment;
  }

  private static final class Slot {
    final AuditRecord record;
    final ByteBuffer ioBytes;
    static final int FREE = 0;
    static final int SEALED = 1;
    static final int APPENDED = 2;
    static final int RELEASED = 3;
    int state = FREE;
    long sequence;
    long connectionCorrelation;
    long sessionCorrelation;
    long requestCorrelation;
    boolean allowed;
    boolean detached;
    boolean closeRejected;
    boolean cancelRequested;
    StatusCode result = StatusCode.OK;

    Slot(AuditFormat format) {
      record = new AuditRecord(format);
      ioBytes = record.bytes();
    }
  }
}
