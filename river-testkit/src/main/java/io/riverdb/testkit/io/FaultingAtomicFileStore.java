package io.riverdb.testkit.io;

import io.riverdb.base.error.StatusCode;
import io.riverdb.platform.fault.FaultAction;
import io.riverdb.platform.fault.FaultDecision;
import io.riverdb.platform.fault.FaultInjector;
import io.riverdb.platform.fault.FaultOperation;
import io.riverdb.platform.fault.FaultPoint;
import io.riverdb.platform.file.AtomicFileInstaller;
import io.riverdb.platform.file.AtomicInstallPhase;
import io.riverdb.platform.file.AtomicInstallProgress;
import io.riverdb.platform.file.AtomicInstallRequest;
import io.riverdb.platform.file.AtomicInstallResult;
import io.riverdb.platform.file.AtomicInstallStep;
import io.riverdb.platform.file.DirectoryDurability;
import io.riverdb.platform.file.DirectoryOperationResult;
import io.riverdb.platform.file.DurableDirectory;
import io.riverdb.platform.file.DurableFile;
import io.riverdb.platform.file.FileSizeResult;
import io.riverdb.platform.file.ForceMode;
import io.riverdb.platform.file.IoResult;
import java.nio.ByteBuffer;
import java.util.Arrays;

/**
 * Bounded namespace and file-content model for the atomic-install protocol.
 *
 * <p>File force publishes bytes. Directory force publishes names. Crash restores both independent
 * durable images and invalidates handles and in-flight completions. No page cache or stale-read
 * behavior is implied by this model.
 */
public final class FaultingAtomicFileStore implements DurableDirectory, AtomicFileInstaller {
  private final ModelFile[] files;
  private final int maxFileBytes;
  private final int maxOpenHandles;
  private final FaultInjector faultInjector;
  private final AtomicInstallFaultPoints points;
  private final AtomicInstallTrace trace;
  private final FaultDecision decision = new FaultDecision();
  private final DirectoryOperationResult directoryResult = new DirectoryOperationResult();
  private int fileCount;
  private int openHandles;
  private long operationSequence;
  private long generation = 1;
  private StatusCode traceStatus = StatusCode.OK;
  private boolean running = true;

  public FaultingAtomicFileStore(
      int maxFiles,
      int maxFileBytes,
      int maxOpenHandles,
      FaultInjector faultInjector,
      AtomicInstallFaultPoints points,
      AtomicInstallTrace trace) {
    files = new ModelFile[maxFiles];
    this.maxFileBytes = maxFileBytes;
    this.maxOpenHandles = maxOpenHandles;
    this.faultInjector = faultInjector;
    this.points = points;
    this.trace = trace;
  }

  @Override
  public synchronized StatusCode advance(
      AtomicInstallRequest request,
      AtomicInstallProgress progress,
      AtomicInstallResult result) {
    result.reset();
    if (!running) {
      return record(StatusCode.RETRY, AtomicInstallStep.NONE, progress, result, 0);
    }
    if (request.version() == 0 || !request.remainsReadable()) {
      return record(
          StatusCode.INVALID_EXTERNAL_INPUT,
          AtomicInstallStep.NONE,
          progress,
          result,
          0);
    }
    if (progress.phase() == AtomicInstallPhase.NEW && progress.requestVersion() == 0) {
      progress.begin(request.version(), generation);
    }
    if (progress.requestVersion() != request.version()) {
      return record(
          StatusCode.INVALID_EXTERNAL_INPUT,
          AtomicInstallStep.NONE,
          progress,
          result,
          0);
    }
    if (progress.providerGeneration() != generation) {
      progress.requireRecovery();
      return record(StatusCode.CANCELLED, AtomicInstallStep.NONE, progress, result, 0);
    }
    if (progress.phase() == AtomicInstallPhase.RECOVERY_REQUIRED) {
      return record(StatusCode.FENCED, AtomicInstallStep.NONE, progress, result, 0);
    }
    if (request.contentLength() > maxFileBytes) {
      return record(
          StatusCode.RESOURCE_EXHAUSTED,
          AtomicInstallStep.NONE,
          progress,
          result,
          0);
    }
    if (progress.completionPending()) {
      AtomicInstallPhase before = progress.phase();
      int bytesBefore = progress.bytesWritten();
      progress.completePending();
      return record(
          StatusCode.OK,
          stepFor(progress.phase()),
          before,
          progress,
          result,
          progress.bytesWritten() - bytesBefore);
    }
    if (progress.isComplete()) {
      return record(StatusCode.OK, AtomicInstallStep.NONE, progress, result, 0);
    }
    return switch (progress.phase()) {
      case NEW -> createStep(request, progress, result);
      case TEMP_CREATED -> writeStep(request, progress, result);
      case CONTENT_WRITTEN -> forceStep(request, progress, result);
      case CONTENT_FORCED -> replaceStep(request, progress, result);
      case DESTINATION_REPLACED -> directoryForceStep(progress, result);
      case DIRECTORY_FORCED -> verifyStep(request, progress, result);
      case VERIFIED, RECOVERY_REQUIRED ->
          record(StatusCode.INVARIANT_BROKEN, AtomicInstallStep.NONE, progress, result, 0);
    };
  }

  private StatusCode createStep(
      AtomicInstallRequest request,
      AtomicInstallProgress progress,
      AtomicInstallResult result) {
    AtomicInstallPhase before = progress.phase();
    directoryResult.reset();
    StatusCode status = createTemporaryInternal(
        request.temporaryFileName(), directoryResult, false);
    if (directoryResult.durability() == DirectoryDurability.UNKNOWN) {
      progress.requireRecovery();
    } else if (directoryResult.durability() != DirectoryDurability.NOT_APPLIED) {
      if (directoryResult.completionPending()) {
        progress.delayCompletion(
            AtomicInstallPhase.TEMP_CREATED,
            DirectoryDurability.VISIBLE_NOT_DURABLE,
            0);
      } else {
        progress.advance(
            AtomicInstallPhase.TEMP_CREATED,
            DirectoryDurability.VISIBLE_NOT_DURABLE,
            0);
      }
    }
    return record(status, AtomicInstallStep.TEMP_CREATE, before, progress, result, 0);
  }

  private StatusCode writeStep(
      AtomicInstallRequest request,
      AtomicInstallProgress progress,
      AtomicInstallResult result) {
    AtomicInstallPhase before = progress.phase();
    ModelFile file = findVolatile(request.temporaryFileName());
    if (file == null) {
      progress.requireRecovery();
      return record(StatusCode.CORRUPTION, AtomicInstallStep.TEMP_WRITE, before, progress, result, 0);
    }
    int remaining = request.contentLength() - progress.bytesWritten();
    if (remaining == 0) {
      progress.advance(
          AtomicInstallPhase.CONTENT_WRITTEN,
          DirectoryDurability.VISIBLE_NOT_DURABLE,
          progress.bytesWritten());
      return record(StatusCode.OK, AtomicInstallStep.TEMP_WRITE, before, progress, result, 0);
    }
    FaultAction beforeAction = decide(
        points.tempWriteBefore(),
        FaultOperation.TEMP_WRITE,
        progress.bytesWritten(),
        remaining);
    StatusCode boundaryStatus = beforeBoundary(beforeAction, FaultOperation.TEMP_WRITE);
    if (!boundaryStatus.isOk()) {
      if (!running) {
        progress.requireRecovery();
      }
      return record(
          boundaryStatus,
          AtomicInstallStep.TEMP_WRITE,
          before,
          progress,
          result,
          0);
    }
    int transferred = remaining;
    StatusCode status = StatusCode.OK;
    if (beforeAction == FaultAction.SHORT_WRITE) {
      transferred = limitedTransfer(remaining, decision.argument());
    } else if (beforeAction == FaultAction.PARTIAL_WRITE
        || beforeAction == FaultAction.TORN_WRITE) {
      transferred = limitedTransfer(remaining, decision.argument());
      status = StatusCode.IO_FAILURE;
    } else if (beforeAction == FaultAction.DISK_FULL) {
      transferred = limitedTransfer(remaining, decision.argument());
      status = StatusCode.RESOURCE_EXHAUSTED;
    }
    int writeOffset = progress.bytesWritten();
    for (int index = 0; index < transferred; index++) {
      file.volatileBytes[writeOffset + index] =
          request.content().get(request.contentPosition() + writeOffset + index);
    }
    file.volatileSize = Math.max(file.volatileSize, writeOffset + transferred);
    if (beforeAction == FaultAction.TORN_WRITE) {
      System.arraycopy(
          file.volatileBytes,
          writeOffset,
          file.durableBytes,
          writeOffset,
          transferred);
      file.durableSize = Math.max(file.durableSize, writeOffset + transferred);
    }
    int written = writeOffset + transferred;
    AtomicInstallPhase next = written == request.contentLength()
        ? AtomicInstallPhase.CONTENT_WRITTEN
        : AtomicInstallPhase.TEMP_CREATED;
    FaultAction afterAction = decide(
        points.tempWriteAfter(),
        FaultOperation.TEMP_WRITE,
        writeOffset,
        transferred);
    StatusCode afterStatus = afterBoundary(afterAction, FaultOperation.TEMP_WRITE);
    if (!running) {
      progress.requireRecovery();
      return record(afterStatus, AtomicInstallStep.TEMP_WRITE, before, progress, result, transferred);
    }
    if (status.isOk() && afterAction == FaultAction.DELAY) {
      progress.delayCompletion(next, DirectoryDurability.VISIBLE_NOT_DURABLE, written);
      status = StatusCode.RETRY;
    } else {
      progress.advance(next, DirectoryDurability.VISIBLE_NOT_DURABLE, written);
      if (!afterStatus.isOk()) {
        status = afterStatus;
      }
    }
    return record(status, AtomicInstallStep.TEMP_WRITE, before, progress, result, transferred);
  }

  private StatusCode forceStep(
      AtomicInstallRequest request,
      AtomicInstallProgress progress,
      AtomicInstallResult result) {
    AtomicInstallPhase before = progress.phase();
    ModelFile file = findVolatile(request.temporaryFileName());
    if (file == null) {
      progress.requireRecovery();
      return record(StatusCode.CORRUPTION, AtomicInstallStep.TEMP_FORCE, before, progress, result, 0);
    }
    FaultAction beforeAction = decide(
        points.tempForceBefore(), FaultOperation.TEMP_FORCE, 0, file.volatileSize);
    StatusCode status = beforeBoundary(beforeAction, FaultOperation.TEMP_FORCE);
    if (!status.isOk()) {
      if (!running) {
        progress.requireRecovery();
      }
      return record(status, AtomicInstallStep.TEMP_FORCE, before, progress, result, 0);
    }
    if (beforeAction == FaultAction.FORCE_FAILURE || beforeAction == FaultAction.DISK_FULL) {
      status = beforeAction == FaultAction.DISK_FULL
          ? StatusCode.RESOURCE_EXHAUSTED
          : StatusCode.IO_FAILURE;
      return record(status, AtomicInstallStep.TEMP_FORCE, before, progress, result, 0);
    }
    file.publishDurable();
    FaultAction afterAction = decide(
        points.tempForceAfter(), FaultOperation.TEMP_FORCE, 0, file.volatileSize);
    StatusCode afterStatus = afterBoundary(afterAction, FaultOperation.TEMP_FORCE);
    return finishAppliedStep(
        afterAction,
        afterStatus,
        AtomicInstallStep.TEMP_FORCE,
        before,
        AtomicInstallPhase.CONTENT_FORCED,
        DirectoryDurability.VISIBLE_NOT_DURABLE,
        progress,
        result,
        0);
  }

  private StatusCode replaceStep(
      AtomicInstallRequest request,
      AtomicInstallProgress progress,
      AtomicInstallResult result) {
    AtomicInstallPhase before = progress.phase();
    directoryResult.reset();
    StatusCode status = replace(
        request.temporaryFileName(), request.destinationFileName(), directoryResult);
    if (directoryResult.durability() == DirectoryDurability.UNKNOWN) {
      progress.requireRecovery();
    } else if (directoryResult.durability() != DirectoryDurability.NOT_APPLIED) {
      if (directoryResult.completionPending()) {
        progress.delayCompletion(
            AtomicInstallPhase.DESTINATION_REPLACED,
            DirectoryDurability.VISIBLE_NOT_DURABLE,
            progress.bytesWritten());
      } else {
        progress.advance(
            AtomicInstallPhase.DESTINATION_REPLACED,
            DirectoryDurability.VISIBLE_NOT_DURABLE,
            progress.bytesWritten());
      }
    }
    return record(status, AtomicInstallStep.DESTINATION_REPLACE, before, progress, result, 0);
  }

  private StatusCode directoryForceStep(
      AtomicInstallProgress progress,
      AtomicInstallResult result) {
    AtomicInstallPhase before = progress.phase();
    directoryResult.reset();
    StatusCode status = force(directoryResult);
    if (directoryResult.durability() == DirectoryDurability.UNKNOWN) {
      progress.requireRecovery();
    } else if (directoryResult.durability() == DirectoryDurability.DURABLE) {
      if (directoryResult.completionPending()) {
        progress.delayCompletion(
            AtomicInstallPhase.DIRECTORY_FORCED,
            DirectoryDurability.DURABLE,
            progress.bytesWritten());
      } else {
        progress.advance(
            AtomicInstallPhase.DIRECTORY_FORCED,
            DirectoryDurability.DURABLE,
            progress.bytesWritten());
      }
    }
    return record(
        status,
        AtomicInstallStep.PARENT_DIRECTORY_FORCE,
        before,
        progress,
        result,
        0);
  }

  private StatusCode verifyStep(
      AtomicInstallRequest request,
      AtomicInstallProgress progress,
      AtomicInstallResult result) {
    AtomicInstallPhase before = progress.phase();
    FaultAction beforeAction = decide(
        points.reopenVerifyBefore(),
        FaultOperation.REOPEN_VERIFY,
        0,
        request.contentLength());
    StatusCode status = beforeBoundary(beforeAction, FaultOperation.REOPEN_VERIFY);
    if (!status.isOk()) {
      if (!running) {
        progress.requireRecovery();
      }
      return record(status, AtomicInstallStep.REOPEN_VERIFY, before, progress, result, 0);
    }
    ModelFile file = findVolatile(request.destinationFileName());
    boolean valid = file != null && file.volatileSize == request.contentLength();
    if (valid) {
      int xorMask = beforeAction == FaultAction.CORRUPT_READ
          || beforeAction == FaultAction.DETECTED_CORRUPTION
          ? (int) (decision.argument() == 0 ? 1 : decision.argument())
          : 0;
      for (int index = 0; index < request.contentLength(); index++) {
        byte actual = (byte) (file.volatileBytes[index] ^ xorMask);
        if (actual != request.content().get(request.contentPosition() + index)) {
          valid = false;
          break;
        }
      }
    }
    if (!valid) {
      return record(StatusCode.CORRUPTION, AtomicInstallStep.REOPEN_VERIFY, before, progress, result, 0);
    }
    FaultAction afterAction = decide(
        points.reopenVerifyAfter(),
        FaultOperation.REOPEN_VERIFY,
        0,
        request.contentLength());
    StatusCode afterStatus = afterBoundary(afterAction, FaultOperation.REOPEN_VERIFY);
    return finishAppliedStep(
        afterAction,
        afterStatus,
        AtomicInstallStep.REOPEN_VERIFY,
        before,
        AtomicInstallPhase.VERIFIED,
        DirectoryDurability.DURABLE,
        progress,
        result,
        0);
  }

  @Override
  public synchronized StatusCode createTemporary(
      String temporaryFileName,
      DirectoryOperationResult result) {
    return createTemporaryInternal(temporaryFileName, result, true);
  }

  private StatusCode createTemporaryInternal(
      String temporaryFileName,
      DirectoryOperationResult result,
      boolean openHandle) {
    result.reset();
    if (!running) {
      return StatusCode.RETRY;
    }
    if (!validFileName(temporaryFileName)) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    FaultAction beforeAction = decide(
        points.tempCreateBefore(), FaultOperation.TEMP_CREATE, 0, 0);
    StatusCode status = beforeBoundary(beforeAction, FaultOperation.TEMP_CREATE);
    if (!status.isOk()) {
      if (!running) {
        result.set(null, DirectoryDurability.UNKNOWN, false);
      }
      return status;
    }
    if (beforeAction == FaultAction.DISK_FULL) {
      return StatusCode.RESOURCE_EXHAUSTED;
    }
    if (findVolatile(temporaryFileName) != null) {
      return StatusCode.CONFLICT;
    }
    ModelFile file = allocateFile();
    if (file == null || openHandle && openHandles == maxOpenHandles) {
      return StatusCode.RESOURCE_EXHAUSTED;
    }
    file.prepare(temporaryFileName);
    DurableFile handle = null;
    if (openHandle) {
      openHandles++;
      handle = new ModelHandle(file, generation);
    }
    result.set(handle, DirectoryDurability.VISIBLE_NOT_DURABLE, false);
    FaultAction afterAction = decide(
        points.tempCreateAfter(), FaultOperation.TEMP_CREATE, 0, 0);
    StatusCode afterStatus = afterBoundary(afterAction, FaultOperation.TEMP_CREATE);
    if (!running) {
      result.set(null, DirectoryDurability.UNKNOWN, false);
    } else if (afterAction == FaultAction.DELAY) {
      result.set(handle, DirectoryDurability.VISIBLE_NOT_DURABLE, true);
    }
    return afterStatus;
  }

  @Override
  public synchronized StatusCode replace(
      String temporaryFileName,
      String destinationFileName,
      DirectoryOperationResult result) {
    result.reset();
    if (!running) {
      return StatusCode.RETRY;
    }
    if (!validFileName(temporaryFileName) || !validFileName(destinationFileName)) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    FaultAction beforeAction = decide(points.replaceBefore(), FaultOperation.REPLACE, 0, 0);
    StatusCode status = beforeBoundary(beforeAction, FaultOperation.REPLACE);
    if (!status.isOk()) {
      if (!running) {
        result.set(null, DirectoryDurability.UNKNOWN, false);
      }
      return status;
    }
    ModelFile temporary = findVolatile(temporaryFileName);
    if (temporary == null) {
      return StatusCode.CORRUPTION;
    }
    ModelFile destination = findVolatile(destinationFileName);
    if (destination != null) {
      destination.volatileName = null;
    }
    temporary.volatileName = destinationFileName;
    result.set(null, DirectoryDurability.VISIBLE_NOT_DURABLE, false);
    FaultAction afterAction = decide(points.replaceAfter(), FaultOperation.REPLACE, 0, 0);
    StatusCode afterStatus = afterBoundary(afterAction, FaultOperation.REPLACE);
    if (!running) {
      result.set(null, DirectoryDurability.UNKNOWN, false);
    } else if (afterAction == FaultAction.DELAY) {
      result.set(null, DirectoryDurability.VISIBLE_NOT_DURABLE, true);
    }
    return afterStatus;
  }

  @Override
  public synchronized StatusCode force(DirectoryOperationResult result) {
    result.reset();
    if (!running) {
      return StatusCode.RETRY;
    }
    FaultAction beforeAction = decide(
        points.directoryForceBefore(), FaultOperation.DIRECTORY_FORCE, 0, 0);
    StatusCode status = beforeBoundary(beforeAction, FaultOperation.DIRECTORY_FORCE);
    if (!status.isOk()) {
      if (!running) {
        result.set(null, DirectoryDurability.UNKNOWN, false);
      }
      return status;
    }
    if (beforeAction == FaultAction.FORCE_FAILURE || beforeAction == FaultAction.DISK_FULL) {
      return beforeAction == FaultAction.DISK_FULL
          ? StatusCode.RESOURCE_EXHAUSTED
          : StatusCode.IO_FAILURE;
    }
    for (int index = 0; index < fileCount; index++) {
      files[index].durableName = files[index].volatileName;
    }
    result.set(null, DirectoryDurability.DURABLE, false);
    FaultAction afterAction = decide(
        points.directoryForceAfter(), FaultOperation.DIRECTORY_FORCE, 0, 0);
    StatusCode afterStatus = afterBoundary(afterAction, FaultOperation.DIRECTORY_FORCE);
    if (!running) {
      result.set(null, DirectoryDurability.UNKNOWN, false);
    } else if (afterAction == FaultAction.DELAY) {
      result.set(null, DirectoryDurability.DURABLE, true);
    }
    return afterStatus;
  }

  @Override
  public synchronized StatusCode reopen(String fileName, DirectoryOperationResult result) {
    result.reset();
    if (!running) {
      return StatusCode.RETRY;
    }
    if (!validFileName(fileName)) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    ModelFile file = findVolatile(fileName);
    if (file == null) {
      return StatusCode.CORRUPTION;
    }
    if (openHandles == maxOpenHandles) {
      return StatusCode.RESOURCE_EXHAUSTED;
    }
    openHandles++;
    DirectoryDurability durability = fileName.equals(file.durableName)
        ? DirectoryDurability.DURABLE
        : DirectoryDurability.VISIBLE_NOT_DURABLE;
    result.set(new ModelHandle(file, generation), durability, false);
    return StatusCode.OK;
  }

  /** Abruptly restores the last separately forced file and directory images. */
  public synchronized StatusCode crash() {
    if (!running) {
      return StatusCode.OK;
    }
    performCrash();
    return StatusCode.OK;
  }

  public synchronized StatusCode restart() {
    running = true;
    return StatusCode.OK;
  }

  public synchronized long generation() {
    return generation;
  }

  public synchronized StatusCode traceStatus() {
    return traceStatus;
  }

  private StatusCode finishAppliedStep(
      FaultAction afterAction,
      StatusCode afterStatus,
      AtomicInstallStep step,
      AtomicInstallPhase before,
      AtomicInstallPhase next,
      DirectoryDurability durability,
      AtomicInstallProgress progress,
      AtomicInstallResult result,
      int bytesTransferred) {
    if (!running) {
      progress.requireRecovery();
    } else if (afterAction == FaultAction.DELAY) {
      progress.delayCompletion(next, durability, progress.bytesWritten());
    } else {
      progress.advance(next, durability, progress.bytesWritten());
    }
    return record(afterStatus, step, before, progress, result, bytesTransferred);
  }

  private FaultAction decide(
      FaultPoint point,
      FaultOperation operation,
      long position,
      int requestedBytes) {
    faultInjector.evaluate(
        point,
        operation,
        ++operationSequence,
        position,
        requestedBytes,
        decision);
    return decision.action();
  }

  private StatusCode beforeBoundary(FaultAction action, FaultOperation operation) {
    if (!action.isCompatibleWith(operation)) {
      return StatusCode.INVARIANT_BROKEN;
    }
    if (action == FaultAction.DELAY) {
      return StatusCode.RETRY;
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
      running = true;
      return StatusCode.CANCELLED;
    }
    return StatusCode.OK;
  }

  private StatusCode afterBoundary(FaultAction action, FaultOperation operation) {
    if (!action.isCompatibleWith(operation)) {
      return StatusCode.INVARIANT_BROKEN;
    }
    if (action == FaultAction.DELAY) {
      return StatusCode.RETRY;
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
      running = true;
      return StatusCode.CANCELLED;
    }
    if (action != FaultAction.NONE) {
      return StatusCode.INVARIANT_BROKEN;
    }
    return StatusCode.OK;
  }

  private void performCrash() {
    for (int index = 0; index < fileCount; index++) {
      files[index].restoreDurable();
      files[index].volatileName = files[index].durableName;
    }
    running = false;
    generation++;
    openHandles = 0;
  }

  private StatusCode record(
      StatusCode status,
      AtomicInstallStep step,
      AtomicInstallProgress progress,
      AtomicInstallResult result,
      int bytesTransferred) {
    return record(status, step, progress.phase(), progress, result, bytesTransferred);
  }

  private StatusCode record(
      StatusCode status,
      AtomicInstallStep step,
      AtomicInstallPhase before,
      AtomicInstallProgress progress,
      AtomicInstallResult result,
      int bytesTransferred) {
    result.set(
        step,
        before,
        progress.appliedPhase(),
        progress.appliedDurability(),
        bytesTransferred,
        progress.completionPending());
    if (trace != null) {
      StatusCode appendStatus = trace.append(
          step,
          before,
          progress.appliedPhase(),
          progress.appliedDurability(),
          status,
          progress.completionPending());
      if (!appendStatus.isOk()) {
        traceStatus = appendStatus;
      }
    }
    return status;
  }

  private ModelFile allocateFile() {
    for (int index = 0; index < fileCount; index++) {
      if (files[index].volatileName == null && files[index].durableName == null) {
        return files[index];
      }
    }
    if (fileCount == files.length) {
      return null;
    }
    ModelFile file = new ModelFile(maxFileBytes);
    files[fileCount++] = file;
    return file;
  }

  private ModelFile findVolatile(String fileName) {
    for (int index = 0; index < fileCount; index++) {
      if (fileName.equals(files[index].volatileName)) {
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

  private static int limitedTransfer(int available, long requestedLimit) {
    long bounded = Math.min(available, requestedLimit);
    return (int) Math.max(0, bounded);
  }

  private static AtomicInstallStep stepFor(AtomicInstallPhase phase) {
    return switch (phase) {
      case TEMP_CREATED -> AtomicInstallStep.TEMP_CREATE;
      case CONTENT_WRITTEN -> AtomicInstallStep.TEMP_WRITE;
      case CONTENT_FORCED -> AtomicInstallStep.TEMP_FORCE;
      case DESTINATION_REPLACED -> AtomicInstallStep.DESTINATION_REPLACE;
      case DIRECTORY_FORCED -> AtomicInstallStep.PARENT_DIRECTORY_FORCE;
      case VERIFIED -> AtomicInstallStep.REOPEN_VERIFY;
      case NEW, RECOVERY_REQUIRED -> AtomicInstallStep.NONE;
    };
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
      synchronized (FaultingAtomicFileStore.this) {
        result.reset();
        StatusCode status = checkState();
        if (!status.isOk()) {
          return status;
        }
        if (position < 0 || position > file.volatileSize) {
          return StatusCode.INVALID_EXTERNAL_INPUT;
        }
        int transferred = Math.min(target.remaining(), file.volatileSize - (int) position);
        target.put(file.volatileBytes, (int) position, transferred);
        result.setBytesTransferred(transferred);
        return StatusCode.OK;
      }
    }

    @Override
    public StatusCode write(long position, ByteBuffer source, IoResult result) {
      synchronized (FaultingAtomicFileStore.this) {
        result.reset();
        StatusCode status = checkState();
        if (!status.isOk()) {
          return status;
        }
        if (position < 0 || position > maxFileBytes) {
          return StatusCode.INVALID_EXTERNAL_INPUT;
        }
        int transferred = Math.min(source.remaining(), maxFileBytes - (int) position);
        source.get(file.volatileBytes, (int) position, transferred);
        file.volatileSize = Math.max(file.volatileSize, (int) position + transferred);
        result.setBytesTransferred(transferred);
        return source.hasRemaining() ? StatusCode.RESOURCE_EXHAUSTED : StatusCode.OK;
      }
    }

    @Override
    public StatusCode force(ForceMode mode) {
      synchronized (FaultingAtomicFileStore.this) {
        StatusCode status = checkState();
        if (!status.isOk()) {
          return status;
        }
        file.publishDurable();
        return StatusCode.OK;
      }
    }

    @Override
    public StatusCode truncate(long sizeBytes) {
      synchronized (FaultingAtomicFileStore.this) {
        StatusCode status = checkState();
        if (!status.isOk()) {
          return status;
        }
        if (sizeBytes < 0 || sizeBytes > maxFileBytes) {
          return StatusCode.INVALID_EXTERNAL_INPUT;
        }
        if (sizeBytes < file.volatileSize) {
          Arrays.fill(file.volatileBytes, (int) sizeBytes, file.volatileSize, (byte) 0);
        }
        file.volatileSize = (int) sizeBytes;
        return StatusCode.OK;
      }
    }

    @Override
    public StatusCode size(FileSizeResult result) {
      synchronized (FaultingAtomicFileStore.this) {
        StatusCode status = checkState();
        if (!status.isOk()) {
          return status;
        }
        result.setSizeBytes(file.volatileSize);
        return StatusCode.OK;
      }
    }

    @Override
    public StatusCode close() {
      synchronized (FaultingAtomicFileStore.this) {
        if (closed) {
          return StatusCode.CLOSED;
        }
        if (openedGeneration != generation) {
          closed = true;
          return StatusCode.CANCELLED;
        }
        closed = true;
        openHandles--;
        return StatusCode.OK;
      }
    }

    private StatusCode checkState() {
      if (closed) {
        return StatusCode.CLOSED;
      }
      return running && openedGeneration == generation
          ? StatusCode.OK
          : StatusCode.CANCELLED;
    }
  }

  private static final class ModelFile {
    private final byte[] volatileBytes;
    private final byte[] durableBytes;
    private String volatileName;
    private String durableName;
    private int volatileSize;
    private int durableSize;

    private ModelFile(int capacity) {
      volatileBytes = new byte[capacity];
      durableBytes = new byte[capacity];
    }

    private void prepare(String name) {
      Arrays.fill(volatileBytes, (byte) 0);
      Arrays.fill(durableBytes, (byte) 0);
      volatileName = name;
      durableName = null;
      volatileSize = 0;
      durableSize = 0;
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
