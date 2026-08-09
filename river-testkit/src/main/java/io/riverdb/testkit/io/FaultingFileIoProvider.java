package io.riverdb.testkit.io;

import io.riverdb.base.error.StatusCode;
import io.riverdb.platform.fault.FaultAction;
import io.riverdb.platform.fault.FaultDecision;
import io.riverdb.platform.fault.FaultInjector;
import io.riverdb.platform.fault.FaultOperation;
import io.riverdb.platform.file.DurableFile;
import io.riverdb.platform.file.FileIoProvider;
import io.riverdb.platform.file.FileSizeResult;
import io.riverdb.platform.file.ForceMode;
import io.riverdb.platform.file.IoResult;
import io.riverdb.platform.file.OpenFileResult;
import java.nio.ByteBuffer;
import java.util.Arrays;

/**
 * Bounded in-memory persistence model. Writes change a volatile image; force publishes that image
 * as durable. Crash invalidates handles and restores the last durable image. A torn-write action
 * is deliberately harsher: it persists only the transferred prefix before returning failure.
 * All provider and handle operations serialize on the provider monitor, making lifecycle and
 * fault-script decisions atomic across handles.
 *
 * <p>Delayed completion and stale page-cache reads remain deliberately unmodeled until P06 fixes
 * their visibility and completion semantics. A short read must not be interpreted as either.
 */
public final class FaultingFileIoProvider implements FileIoProvider {
  private final ModelFile[] files;
  private final int maxFileBytes;
  private final int maxOpenHandles;
  private final FaultInjector faultInjector;
  private final FileFaultPoints faultPoints;
  private final FaultDecision faultDecision = new FaultDecision();
  private int fileCount;
  private int openHandles;
  private long operationSequence;
  private long generation = 1;
  private long handleAllocations;
  private long fileAllocations;
  private long readCopyBytes;
  private long writeCopyBytes;
  private long durableCopyBytes;
  private long recoveryCopyBytes;
  private boolean running = true;

  public FaultingFileIoProvider(
      int maxFiles,
      int maxFileBytes,
      int maxOpenHandles,
      FaultInjector faultInjector,
      FileFaultPoints faultPoints) {
    files = new ModelFile[maxFiles];
    this.maxFileBytes = maxFileBytes;
    this.maxOpenHandles = maxOpenHandles;
    this.faultInjector = faultInjector;
    this.faultPoints = faultPoints;
  }

  @Override
  public synchronized StatusCode open(String fileName, OpenFileResult result) {
    result.reset();
    if (!running) {
      return StatusCode.RETRY;
    }
    if (!validFileName(fileName)) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    FaultAction action = decide(faultPoints.open(), FaultOperation.OPEN, 0, 0);
    StatusCode actionStatus = providerAction(action, FaultOperation.OPEN);
    if (!actionStatus.isOk()) {
      return actionStatus;
    }
    if (openHandles == maxOpenHandles) {
      return StatusCode.RESOURCE_EXHAUSTED;
    }
    ModelFile modelFile = find(fileName);
    if (modelFile == null) {
      if (fileCount == files.length) {
        return StatusCode.RESOURCE_EXHAUSTED;
      }
      modelFile = new ModelFile(fileName, maxFileBytes);
      files[fileCount++] = modelFile;
      fileAllocations++;
    }
    openHandles++;
    handleAllocations++;
    result.setFile(new ModelHandle(modelFile, generation));
    return StatusCode.OK;
  }

  /** Simulates abrupt process loss: unforced state disappears and all handles become stale. */
  public synchronized StatusCode crash() {
    if (!running) {
      return StatusCode.OK;
    }
    FaultAction action = decide(faultPoints.crash(), FaultOperation.CRASH, 0, 0);
    if (!action.isCompatibleWith(FaultOperation.CRASH)) {
      return StatusCode.INVARIANT_BROKEN;
    }
    if (action == FaultAction.CANCEL) {
      return StatusCode.CANCELLED;
    }
    if (action != FaultAction.NONE && action != FaultAction.CRASH) {
      return StatusCode.INVARIANT_BROKEN;
    }
    performCrash();
    return StatusCode.OK;
  }

  private void performCrash() {
    for (int index = 0; index < fileCount; index++) {
      recoveryCopyBytes += files[index].durableSize;
      files[index].restoreDurable();
    }
    running = false;
    generation++;
    openHandles = 0;
  }

  /** Starts a new model process generation. Callers must reopen files. */
  public synchronized StatusCode restart() {
    if (running) {
      return StatusCode.OK;
    }
    FaultAction action = decide(faultPoints.restart(), FaultOperation.RESTART, 0, 0);
    if (!action.isCompatibleWith(FaultOperation.RESTART)) {
      return StatusCode.INVARIANT_BROKEN;
    }
    if (action == FaultAction.CANCEL) {
      return StatusCode.CANCELLED;
    }
    if (action == FaultAction.CRASH) {
      return StatusCode.IO_FAILURE;
    }
    if (action != FaultAction.NONE && action != FaultAction.RESTART) {
      return StatusCode.INVARIANT_BROKEN;
    }
    performRestart();
    return StatusCode.OK;
  }

  private void performRestart() {
    running = true;
  }

  public synchronized boolean isRunning() {
    return running;
  }

  public synchronized long generation() {
    return generation;
  }

  public synchronized int fileCount() {
    return fileCount;
  }

  public synchronized int openHandleCount() {
    return openHandles;
  }

  public synchronized StatusCode snapshotCounters(FileModelCounters result) {
    result.set(
        handleAllocations,
        fileAllocations,
        readCopyBytes,
        writeCopyBytes,
        durableCopyBytes,
        recoveryCopyBytes);
    return StatusCode.OK;
  }

  private FaultAction decide(
      io.riverdb.platform.fault.FaultPoint point,
      FaultOperation operation,
      long position,
      int requestedBytes) {
    faultInjector.evaluate(
        point,
        operation,
        ++operationSequence,
        position,
        requestedBytes,
        faultDecision);
    return faultDecision.action();
  }

  private StatusCode providerAction(FaultAction action, FaultOperation operation) {
    if (!action.isCompatibleWith(operation)) {
      return StatusCode.INVARIANT_BROKEN;
    }
    if (action == FaultAction.CANCEL) {
      return StatusCode.CANCELLED;
    }
    if (action == FaultAction.CRASH) {
      performCrash();
      return StatusCode.IO_FAILURE;
    }
    if (action == FaultAction.RESTART) {
      performCrash();
      performRestart();
      return StatusCode.CANCELLED;
    }
    return StatusCode.OK;
  }

  private ModelFile find(String fileName) {
    for (int index = 0; index < fileCount; index++) {
      if (files[index].name.equals(fileName)) {
        return files[index];
      }
    }
    return null;
  }

  private boolean validFileName(String fileName) {
    if (fileName == null || fileName.isBlank() || fileName.length() > 128) {
      return false;
    }
    return fileName.indexOf('/') < 0
        && fileName.indexOf('\\') < 0
        && !fileName.equals(".")
        && !fileName.equals("..");
  }

  private final class ModelHandle implements DurableFile {
    private final ModelFile file;
    private final long openedGeneration;
    private boolean closed;

    private ModelHandle(ModelFile file, long openedGeneration) {
      this.file = file;
      this.openedGeneration = openedGeneration;
    }

    @Override
    public StatusCode read(long position, ByteBuffer target, IoResult result) {
      synchronized (FaultingFileIoProvider.this) {
        return readLocked(position, target, result);
      }
    }

    private StatusCode readLocked(long position, ByteBuffer target, IoResult result) {
      result.reset();
      StatusCode stateStatus = checkState();
      if (!stateStatus.isOk()) {
        return stateStatus;
      }
      if (position < 0 || position > Integer.MAX_VALUE) {
        return StatusCode.INVALID_EXTERNAL_INPUT;
      }
      int requested = target.remaining();
      FaultAction action = decide(faultPoints.read(), FaultOperation.READ, position, requested);
      StatusCode actionStatus = providerAction(action, FaultOperation.READ);
      if (!actionStatus.isOk()) {
        return actionStatus;
      }
      int available = Math.max(0, file.volatileSize - (int) position);
      int transferred = Math.min(requested, available);
      if (action == FaultAction.SHORT_READ) {
        transferred = limitedTransfer(transferred, faultDecision.argument());
      }
      boolean corrupted = action == FaultAction.CORRUPT_READ
          || action == FaultAction.DETECTED_CORRUPTION;
      int xorMask = corrupted
          ? (int) (faultDecision.argument() == 0 ? 1 : faultDecision.argument())
          : 0;
      for (int index = 0; index < transferred; index++) {
        target.put((byte) (file.volatileBytes[(int) position + index] ^ xorMask));
      }
      readCopyBytes += transferred;
      result.setBytesTransferred(transferred);
      return action == FaultAction.DETECTED_CORRUPTION
          ? StatusCode.CORRUPTION
          : StatusCode.OK;
    }

    @Override
    public StatusCode write(long position, ByteBuffer source, IoResult result) {
      synchronized (FaultingFileIoProvider.this) {
        return writeLocked(position, source, result);
      }
    }

    private StatusCode writeLocked(long position, ByteBuffer source, IoResult result) {
      result.reset();
      StatusCode stateStatus = checkState();
      if (!stateStatus.isOk()) {
        return stateStatus;
      }
      if (position < 0 || position > maxFileBytes) {
        return StatusCode.INVALID_EXTERNAL_INPUT;
      }
      int requested = source.remaining();
      FaultAction action = decide(faultPoints.write(), FaultOperation.WRITE, position, requested);
      StatusCode actionStatus = providerAction(action, FaultOperation.WRITE);
      if (!actionStatus.isOk()) {
        return actionStatus;
      }
      int capacity = maxFileBytes - (int) position;
      int transferred = Math.min(requested, capacity);
      StatusCode resultStatus = requested > capacity
          ? StatusCode.RESOURCE_EXHAUSTED
          : StatusCode.OK;
      if (action == FaultAction.SHORT_WRITE) {
        transferred = limitedTransfer(transferred, faultDecision.argument());
      } else if (action == FaultAction.PARTIAL_WRITE || action == FaultAction.TORN_WRITE) {
        transferred = limitedTransfer(transferred, faultDecision.argument());
        resultStatus = StatusCode.IO_FAILURE;
      } else if (action == FaultAction.DISK_FULL) {
        transferred = limitedTransfer(transferred, faultDecision.argument());
        resultStatus = StatusCode.RESOURCE_EXHAUSTED;
      }
      source.get(file.volatileBytes, (int) position, transferred);
      writeCopyBytes += transferred;
      file.volatileSize = Math.max(file.volatileSize, (int) position + transferred);
      result.setBytesTransferred(transferred);
      if (action == FaultAction.TORN_WRITE) {
        System.arraycopy(
            file.volatileBytes,
            (int) position,
            file.durableBytes,
            (int) position,
            transferred);
        durableCopyBytes += transferred;
        file.durableSize = Math.max(file.durableSize, (int) position + transferred);
      }
      return resultStatus;
    }

    @Override
    public StatusCode force(ForceMode mode) {
      synchronized (FaultingFileIoProvider.this) {
        return forceLocked(mode);
      }
    }

    private StatusCode forceLocked(ForceMode mode) {
      StatusCode stateStatus = checkState();
      if (!stateStatus.isOk()) {
        return stateStatus;
      }
      FaultAction action = decide(faultPoints.force(), FaultOperation.FORCE, 0, file.volatileSize);
      StatusCode actionStatus = providerAction(action, FaultOperation.FORCE);
      if (!actionStatus.isOk()) {
        return actionStatus;
      }
      if (action == FaultAction.FORCE_FAILURE || action == FaultAction.DISK_FULL) {
        return action == FaultAction.DISK_FULL
            ? StatusCode.RESOURCE_EXHAUSTED
            : StatusCode.IO_FAILURE;
      }
      durableCopyBytes += file.volatileSize;
      file.publishDurable();
      return StatusCode.OK;
    }

    @Override
    public StatusCode truncate(long sizeBytes) {
      synchronized (FaultingFileIoProvider.this) {
        return truncateLocked(sizeBytes);
      }
    }

    private StatusCode truncateLocked(long sizeBytes) {
      StatusCode stateStatus = checkState();
      if (!stateStatus.isOk()) {
        return stateStatus;
      }
      if (sizeBytes < 0 || sizeBytes > maxFileBytes) {
        return StatusCode.INVALID_EXTERNAL_INPUT;
      }
      FaultAction action = decide(faultPoints.truncate(), FaultOperation.TRUNCATE, sizeBytes, 0);
      StatusCode actionStatus = providerAction(action, FaultOperation.TRUNCATE);
      if (!actionStatus.isOk()) {
        return actionStatus;
      }
      if (sizeBytes < file.volatileSize) {
        Arrays.fill(file.volatileBytes, (int) sizeBytes, file.volatileSize, (byte) 0);
      }
      file.volatileSize = (int) sizeBytes;
      return StatusCode.OK;
    }

    @Override
    public StatusCode size(FileSizeResult result) {
      synchronized (FaultingFileIoProvider.this) {
        return sizeLocked(result);
      }
    }

    private StatusCode sizeLocked(FileSizeResult result) {
      StatusCode stateStatus = checkState();
      if (!stateStatus.isOk()) {
        return stateStatus;
      }
      FaultAction action = decide(faultPoints.size(), FaultOperation.SIZE, 0, 0);
      StatusCode actionStatus = providerAction(action, FaultOperation.SIZE);
      if (!actionStatus.isOk()) {
        return actionStatus;
      }
      result.setSizeBytes(file.volatileSize);
      return StatusCode.OK;
    }

    @Override
    public StatusCode close() {
      synchronized (FaultingFileIoProvider.this) {
        return closeLocked();
      }
    }

    private StatusCode closeLocked() {
      if (closed) {
        return StatusCode.CLOSED;
      }
      if (openedGeneration != generation) {
        closed = true;
        return StatusCode.CANCELLED;
      }
      FaultAction action = decide(faultPoints.close(), FaultOperation.CLOSE, 0, 0);
      StatusCode actionStatus = providerAction(action, FaultOperation.CLOSE);
      if (!actionStatus.isOk()) {
        return actionStatus;
      }
      closed = true;
      openHandles--;
      return StatusCode.OK;
    }

    private StatusCode checkState() {
      if (closed) {
        return StatusCode.CLOSED;
      }
      if (openedGeneration != generation || !running) {
        return StatusCode.CANCELLED;
      }
      return StatusCode.OK;
    }
  }

  private static int limitedTransfer(int available, long requestedLimit) {
    long bounded = Math.min(available, requestedLimit);
    return (int) Math.max(0, bounded);
  }

  private static final class ModelFile {
    private final String name;
    private final byte[] durableBytes;
    private final byte[] volatileBytes;
    private int durableSize;
    private int volatileSize;

    private ModelFile(String name, int capacity) {
      this.name = name;
      durableBytes = new byte[capacity];
      volatileBytes = new byte[capacity];
    }

    private void publishDurable() {
      System.arraycopy(volatileBytes, 0, durableBytes, 0, volatileSize);
      if (durableSize > volatileSize) {
        Arrays.fill(durableBytes, volatileSize, durableSize, (byte) 0);
      }
      durableSize = volatileSize;
    }

    private void restoreDurable() {
      System.arraycopy(durableBytes, 0, volatileBytes, 0, durableSize);
      if (volatileSize > durableSize) {
        Arrays.fill(volatileBytes, durableSize, volatileSize, (byte) 0);
      }
      volatileSize = durableSize;
    }
  }
}
