package io.riverdb.testkit.io;

import io.riverdb.base.error.StatusCode;
import io.riverdb.platform.fault.FaultAction;
import io.riverdb.platform.fault.FaultBoundary;
import io.riverdb.platform.fault.FaultDecision;
import io.riverdb.platform.fault.FaultInjector;
import io.riverdb.platform.fault.FaultOperation;
import io.riverdb.platform.fault.FaultPoint;
import io.riverdb.platform.file.AtomicFileInstaller;
import io.riverdb.platform.file.AtomicInstallId;
import io.riverdb.platform.file.AtomicInstallPhase;
import io.riverdb.platform.file.AtomicInstallProgress;
import io.riverdb.platform.file.AtomicInstallRequest;
import io.riverdb.platform.file.AtomicInstallResult;
import io.riverdb.platform.file.AtomicInstallSnapshot;
import io.riverdb.platform.file.AtomicInstallStateMachine;
import io.riverdb.platform.file.AtomicInstallStep;
import io.riverdb.platform.file.DirectoryDurability;
import io.riverdb.platform.file.DirectoryEntryType;
import io.riverdb.platform.file.DirectoryListResult;
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
  private final AtomicInstallStateMachine progressState = new AtomicInstallStateMachine();
  private final AtomicInstallSnapshot progressSnapshot = new AtomicInstallSnapshot();
  private int fileCount;
  private int openHandles;
  private long operationSequence;
  private long generation = 1;
  private StatusCode traceStatus = StatusCode.OK;
  private boolean operationCompletionPending;
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
  public synchronized StatusCode issueInstallId(AtomicInstallId result) {
    if (!running) {
      return StatusCode.RETRY;
    }
    return progressState.issueInstallId(result);
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
    if (request.contentLength() > maxFileBytes) {
      return record(
          StatusCode.RESOURCE_EXHAUSTED,
          AtomicInstallStep.NONE,
          progress,
          result,
          0);
    }
    if (progressState.validateOwner(progress).isOk()
        && progressState.phase(progress) == AtomicInstallPhase.RECOVERY_REQUIRED) {
      return record(StatusCode.FENCED, AtomicInstallStep.NONE, progress, result, 0);
    }
    StatusCode resumeStatus = progressState.resume(progress, request, generation);
    if (!resumeStatus.isOk()) {
      return record(resumeStatus, AtomicInstallStep.NONE, progress, result, 0);
    }
    if (progressState.completionPending(progress)) {
      AtomicInstallPhase before = progressState.phase(progress);
      int bytesBefore = progressState.bytesWritten(progress);
      StatusCode completionStatus = progressState.completePending(progress);
      if (!completionStatus.isOk()) {
        return record(
            completionStatus,
            AtomicInstallStep.NONE,
            before,
            progress,
            result,
            0);
      }
      return record(
          StatusCode.OK,
          stepFor(progressState.phase(progress)),
          before,
          progress,
          result,
          progressState.bytesWritten(progress) - bytesBefore);
    }
    if (progressState.isComplete(progress)) {
      return record(StatusCode.OK, AtomicInstallStep.NONE, progress, result, 0);
    }
    return switch (progressState.phase(progress)) {
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
    AtomicInstallPhase before = progressState.phase(progress);
    directoryResult.reset();
    StatusCode status = createTemporaryInternal(
        request.temporaryFileName(), directoryResult, false, true);
    StatusCode progressStatus = StatusCode.OK;
    if (directoryResult.durability() == DirectoryDurability.UNKNOWN) {
      progressStatus = progressState.requireRecovery(progress);
    } else if (directoryResult.durability() != DirectoryDurability.NOT_APPLIED) {
      if (operationCompletionPending) {
        progressStatus = progressState.delayCompletion(
            progress,
            before,
            AtomicInstallPhase.TEMP_CREATED,
            DirectoryDurability.VISIBLE_NOT_DURABLE,
            0);
      } else {
        progressStatus = progressState.transition(
            progress,
            before,
            AtomicInstallPhase.TEMP_CREATED,
            DirectoryDurability.VISIBLE_NOT_DURABLE,
            0);
      }
    }
    if (!progressStatus.isOk()) {
      status = progressStatus;
    }
    return record(status, AtomicInstallStep.TEMP_CREATE, before, progress, result, 0);
  }

  private StatusCode writeStep(
      AtomicInstallRequest request,
      AtomicInstallProgress progress,
      AtomicInstallResult result) {
    AtomicInstallPhase before = progressState.phase(progress);
    ModelFile file = findVolatile(request.temporaryFileName());
    if (file == null) {
      progressState.requireRecovery(progress);
      return record(StatusCode.CORRUPTION, AtomicInstallStep.TEMP_WRITE, before, progress, result, 0);
    }
    int remaining = request.contentLength() - progressState.bytesWritten(progress);
    if (remaining == 0) {
      StatusCode progressStatus = progressState.transition(
          progress,
          before,
          AtomicInstallPhase.CONTENT_WRITTEN,
          DirectoryDurability.VISIBLE_NOT_DURABLE,
          progressState.bytesWritten(progress));
      return record(progressStatus, AtomicInstallStep.TEMP_WRITE, before, progress, result, 0);
    }
    FaultAction beforeAction = decide(
        points.tempWriteBefore(),
        FaultOperation.TEMP_WRITE,
        FaultBoundary.BEFORE,
        progressState.bytesWritten(progress),
        remaining);
    StatusCode boundaryStatus = beforeBoundary(beforeAction, FaultOperation.TEMP_WRITE);
    if (!boundaryStatus.isOk()) {
      if (!running || progressState.providerGeneration(progress) != generation) {
        progressState.requireRecovery(progress);
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
    int writeOffset = progressState.bytesWritten(progress);
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
        FaultBoundary.AFTER,
        writeOffset,
        transferred);
    StatusCode afterStatus = afterBoundary(afterAction, FaultOperation.TEMP_WRITE);
    if (!running || progressState.providerGeneration(progress) != generation) {
      progressState.requireRecovery(progress);
      return record(afterStatus, AtomicInstallStep.TEMP_WRITE, before, progress, result, transferred);
    }
    StatusCode progressStatus;
    if (status.isOk() && afterAction == FaultAction.DELAY) {
      progressStatus = progressState.delayCompletion(
          progress,
          before,
          next,
          DirectoryDurability.VISIBLE_NOT_DURABLE,
          written);
      status = StatusCode.RETRY;
    } else {
      progressStatus = progressState.transition(
          progress,
          before,
          next,
          DirectoryDurability.VISIBLE_NOT_DURABLE,
          written);
      if (!afterStatus.isOk()) {
        status = afterStatus;
      }
    }
    if (!progressStatus.isOk()) {
      status = progressStatus;
    }
    return record(status, AtomicInstallStep.TEMP_WRITE, before, progress, result, transferred);
  }

  private StatusCode forceStep(
      AtomicInstallRequest request,
      AtomicInstallProgress progress,
      AtomicInstallResult result) {
    AtomicInstallPhase before = progressState.phase(progress);
    ModelFile file = findVolatile(request.temporaryFileName());
    if (file == null) {
      progressState.requireRecovery(progress);
      return record(StatusCode.CORRUPTION, AtomicInstallStep.TEMP_FORCE, before, progress, result, 0);
    }
    FaultAction beforeAction = decide(
        points.tempForceBefore(),
        FaultOperation.TEMP_FORCE,
        FaultBoundary.BEFORE,
        0,
        file.volatileSize);
    StatusCode status = beforeBoundary(beforeAction, FaultOperation.TEMP_FORCE);
    if (!status.isOk()) {
      if (!running || progressState.providerGeneration(progress) != generation) {
        progressState.requireRecovery(progress);
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
        points.tempForceAfter(),
        FaultOperation.TEMP_FORCE,
        FaultBoundary.AFTER,
        0,
        file.volatileSize);
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
    AtomicInstallPhase before = progressState.phase(progress);
    directoryResult.reset();
    StatusCode status = replaceInternal(
        request.temporaryFileName(), request.destinationFileName(), directoryResult, true);
    if (status == StatusCode.CONFLICT
        && directoryResult.durability() == DirectoryDurability.NOT_APPLIED) {
      progressState.requireRecovery(progress);
      return record(
          StatusCode.CORRUPTION,
          AtomicInstallStep.DESTINATION_REPLACE,
          before,
          progress,
          result,
          0);
    }
    StatusCode progressStatus = StatusCode.OK;
    if (directoryResult.durability() == DirectoryDurability.UNKNOWN) {
      progressStatus = progressState.requireRecovery(progress);
    } else if (directoryResult.durability() != DirectoryDurability.NOT_APPLIED) {
      if (operationCompletionPending) {
        progressStatus = progressState.delayCompletion(
            progress,
            before,
            AtomicInstallPhase.DESTINATION_REPLACED,
            DirectoryDurability.VISIBLE_NOT_DURABLE,
            progressState.bytesWritten(progress));
      } else {
        progressStatus = progressState.transition(
            progress,
            before,
            AtomicInstallPhase.DESTINATION_REPLACED,
            DirectoryDurability.VISIBLE_NOT_DURABLE,
            progressState.bytesWritten(progress));
      }
    }
    if (!progressStatus.isOk()) {
      status = progressStatus;
    }
    return record(status, AtomicInstallStep.DESTINATION_REPLACE, before, progress, result, 0);
  }

  private StatusCode directoryForceStep(
      AtomicInstallProgress progress,
      AtomicInstallResult result) {
    AtomicInstallPhase before = progressState.phase(progress);
    directoryResult.reset();
    StatusCode status = forceInternal(directoryResult, true);
    StatusCode progressStatus = StatusCode.OK;
    if (directoryResult.durability() == DirectoryDurability.UNKNOWN) {
      progressStatus = progressState.requireRecovery(progress);
    } else if (directoryResult.durability() == DirectoryDurability.DURABLE) {
      if (operationCompletionPending) {
        progressStatus = progressState.delayCompletion(
            progress,
            before,
            AtomicInstallPhase.DIRECTORY_FORCED,
            DirectoryDurability.DURABLE,
            progressState.bytesWritten(progress));
      } else {
        progressStatus = progressState.transition(
            progress,
            before,
            AtomicInstallPhase.DIRECTORY_FORCED,
            DirectoryDurability.DURABLE,
            progressState.bytesWritten(progress));
      }
    }
    if (!progressStatus.isOk()) {
      status = progressStatus;
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
    AtomicInstallPhase before = progressState.phase(progress);
    FaultAction beforeAction = decide(
        points.reopenVerifyBefore(),
        FaultOperation.REOPEN_VERIFY,
        FaultBoundary.BEFORE,
        0,
        request.contentLength());
    StatusCode status = beforeBoundary(beforeAction, FaultOperation.REOPEN_VERIFY);
    if (!status.isOk()) {
      if (!running || progressState.providerGeneration(progress) != generation) {
        progressState.requireRecovery(progress);
      }
      return record(status, AtomicInstallStep.REOPEN_VERIFY, before, progress, result, 0);
    }
    if (beforeAction == FaultAction.SHORT_READ) {
      int transferred = limitedTransfer(request.contentLength(), decision.argument());
      ModelFile shortFile = findVolatile(request.destinationFileName());
      if (shortFile == null || shortFile.volatileSize < transferred) {
        return record(
            StatusCode.CORRUPTION,
            AtomicInstallStep.REOPEN_VERIFY,
            before,
            progress,
            result,
            transferred);
      }
      for (int index = 0; index < transferred; index++) {
        if (shortFile.volatileBytes[index]
            != request.content().get(request.contentPosition() + index)) {
          return record(
              StatusCode.CORRUPTION,
              AtomicInstallStep.REOPEN_VERIFY,
              before,
              progress,
              result,
              transferred);
        }
      }
      return record(
          StatusCode.RETRY,
          AtomicInstallStep.REOPEN_VERIFY,
          before,
          progress,
          result,
          transferred);
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
        FaultBoundary.AFTER,
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
  public synchronized StatusCode createDirectory(
      String childDirectoryName,
      DirectoryOperationResult result) {
    result.reset();
    if (!running) {
      return StatusCode.RETRY;
    }
    if (!validFileName(childDirectoryName)) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    if (findVolatile(childDirectoryName) != null) {
      return StatusCode.CONFLICT;
    }
    ModelFile entry = allocateFile();
    if (entry == null) {
      return StatusCode.RESOURCE_EXHAUSTED;
    }
    entry.prepare(childDirectoryName, true);
    result.set(null, DirectoryDurability.VISIBLE_NOT_DURABLE);
    return StatusCode.OK;
  }

  @Override
  public synchronized StatusCode createFile(
      String fileName,
      DirectoryOperationResult result) {
    return createNamedFile(fileName, result);
  }

  private StatusCode createNamedFile(
      String fileName,
      DirectoryOperationResult result) {
    result.reset();
    if (!running) {
      return StatusCode.RETRY;
    }
    if (!validFileName(fileName)) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    if (findVolatile(fileName) != null) {
      return StatusCode.CONFLICT;
    }
    if (openHandles == maxOpenHandles) {
      return StatusCode.RESOURCE_EXHAUSTED;
    }
    ModelFile file = allocateFile();
    if (file == null) {
      return StatusCode.RESOURCE_EXHAUSTED;
    }
    file.prepare(fileName, false);
    openHandles++;
    result.set(new ModelHandle(file, generation), DirectoryDurability.VISIBLE_NOT_DURABLE);
    return StatusCode.OK;
  }

  @Override
  public synchronized StatusCode createTemporary(
      String temporaryFileName,
      DirectoryOperationResult result) {
    return createTemporaryInternal(temporaryFileName, result, true, false);
  }

  private StatusCode createTemporaryInternal(
      String temporaryFileName,
      DirectoryOperationResult result,
      boolean openHandle,
      boolean exposePending) {
    result.reset();
    operationCompletionPending = false;
    long startingGeneration = generation;
    if (!running) {
      return StatusCode.RETRY;
    }
    if (!validFileName(temporaryFileName)) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    FaultAction beforeAction = decide(
        points.tempCreateBefore(), FaultOperation.TEMP_CREATE, FaultBoundary.BEFORE, 0, 0);
    StatusCode status = beforeBoundary(beforeAction, FaultOperation.TEMP_CREATE);
    if (!status.isOk()) {
      if (!running || generation != startingGeneration) {
        result.set(null, DirectoryDurability.UNKNOWN);
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
    file.prepare(temporaryFileName, false);
    DurableFile handle = null;
    if (openHandle) {
      openHandles++;
      handle = new ModelHandle(file, generation);
    }
    result.set(handle, DirectoryDurability.VISIBLE_NOT_DURABLE);
    FaultAction afterAction = decide(
        points.tempCreateAfter(), FaultOperation.TEMP_CREATE, FaultBoundary.AFTER, 0, 0);
    StatusCode afterStatus = afterBoundary(afterAction, FaultOperation.TEMP_CREATE);
    if (!running || generation != startingGeneration) {
      result.set(null, DirectoryDurability.UNKNOWN);
    } else if (afterAction == FaultAction.DELAY) {
      if (exposePending) {
        operationCompletionPending = true;
      } else {
        afterStatus = StatusCode.OK;
      }
    }
    return afterStatus;
  }

  @Override
  public synchronized StatusCode list(DirectoryListResult result) {
    result.reset();
    if (!running) {
      return StatusCode.RETRY;
    }
    for (int index = 0; index < fileCount; index++) {
      ModelFile entry = files[index];
      if (entry.volatileName == null) {
        continue;
      }
      StatusCode status = result.add(
          entry.volatileName,
          entry.directory ? DirectoryEntryType.DIRECTORY : DirectoryEntryType.FILE);
      if (!status.isOk()) {
        return status;
      }
    }
    result.finish(generation);
    return StatusCode.OK;
  }

  @Override
  public synchronized StatusCode rename(
      String sourceName,
      String destinationName,
      DirectoryOperationResult result) {
    result.reset();
    if (!running) {
      return StatusCode.RETRY;
    }
    if (!validFileName(sourceName) || !validFileName(destinationName)) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    ModelFile source = findVolatile(sourceName);
    if (source == null || findVolatile(destinationName) != null) {
      return StatusCode.CONFLICT;
    }
    source.volatileName = destinationName;
    result.set(null, DirectoryDurability.VISIBLE_NOT_DURABLE);
    return StatusCode.OK;
  }

  @Override
  public synchronized StatusCode replace(
      String temporaryFileName,
      String destinationFileName,
      DirectoryOperationResult result) {
    return replaceInternal(temporaryFileName, destinationFileName, result, false);
  }

  @Override
  public synchronized StatusCode remove(
      String entryName,
      DirectoryOperationResult result) {
    result.reset();
    if (!running) {
      return StatusCode.RETRY;
    }
    if (!validFileName(entryName)) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    ModelFile entry = findVolatile(entryName);
    if (entry == null) {
      return StatusCode.CONFLICT;
    }
    entry.volatileName = null;
    result.set(null, DirectoryDurability.VISIBLE_NOT_DURABLE);
    return StatusCode.OK;
  }

  @Override
  public synchronized StatusCode truncate(
      String fileName,
      long sizeBytes,
      DirectoryOperationResult result) {
    result.reset();
    if (!running) {
      return StatusCode.RETRY;
    }
    if (!validFileName(fileName) || sizeBytes < 0 || sizeBytes > maxFileBytes) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    ModelFile file = findVolatile(fileName);
    if (file == null || file.directory) {
      return StatusCode.CONFLICT;
    }
    if (openHandles == maxOpenHandles) {
      return StatusCode.RESOURCE_EXHAUSTED;
    }
    if (sizeBytes < file.volatileSize) {
      Arrays.fill(file.volatileBytes, (int) sizeBytes, file.volatileSize, (byte) 0);
    }
    file.volatileSize = (int) sizeBytes;
    openHandles++;
    result.set(new ModelHandle(file, generation), DirectoryDurability.VISIBLE_NOT_DURABLE);
    return StatusCode.OK;
  }

  private StatusCode replaceInternal(
      String temporaryFileName,
      String destinationFileName,
      DirectoryOperationResult result,
      boolean exposePending) {
    result.reset();
    operationCompletionPending = false;
    long startingGeneration = generation;
    if (!running) {
      return StatusCode.RETRY;
    }
    if (!validFileName(temporaryFileName) || !validFileName(destinationFileName)) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    FaultAction beforeAction = decide(
        points.replaceBefore(), FaultOperation.REPLACE, FaultBoundary.BEFORE, 0, 0);
    StatusCode status = beforeBoundary(beforeAction, FaultOperation.REPLACE);
    if (!status.isOk()) {
      if (!running || generation != startingGeneration) {
        result.set(null, DirectoryDurability.UNKNOWN);
      }
      return status;
    }
    ModelFile temporary = findVolatile(temporaryFileName);
    if (temporary == null || temporary.directory) {
      return StatusCode.CONFLICT;
    }
    ModelFile destination = findVolatile(destinationFileName);
    if (destination != null && destination.directory) {
      return StatusCode.CONFLICT;
    }
    if (destination != null) {
      destination.volatileName = null;
    }
    temporary.volatileName = destinationFileName;
    result.set(null, DirectoryDurability.VISIBLE_NOT_DURABLE);
    FaultAction afterAction = decide(
        points.replaceAfter(), FaultOperation.REPLACE, FaultBoundary.AFTER, 0, 0);
    StatusCode afterStatus = afterBoundary(afterAction, FaultOperation.REPLACE);
    if (!running || generation != startingGeneration) {
      result.set(null, DirectoryDurability.UNKNOWN);
    } else if (afterAction == FaultAction.DELAY) {
      if (exposePending) {
        operationCompletionPending = true;
      } else {
        afterStatus = StatusCode.OK;
      }
    }
    return afterStatus;
  }

  @Override
  public synchronized StatusCode force(DirectoryOperationResult result) {
    return forceInternal(result, false);
  }

  private StatusCode forceInternal(
      DirectoryOperationResult result,
      boolean exposePending) {
    result.reset();
    operationCompletionPending = false;
    long startingGeneration = generation;
    if (!running) {
      return StatusCode.RETRY;
    }
    FaultAction beforeAction = decide(
        points.directoryForceBefore(),
        FaultOperation.DIRECTORY_FORCE,
        FaultBoundary.BEFORE,
        0,
        0);
    StatusCode status = beforeBoundary(beforeAction, FaultOperation.DIRECTORY_FORCE);
    if (!status.isOk()) {
      if (!running || generation != startingGeneration) {
        result.set(null, DirectoryDurability.UNKNOWN);
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
    result.set(null, DirectoryDurability.DURABLE);
    FaultAction afterAction = decide(
        points.directoryForceAfter(),
        FaultOperation.DIRECTORY_FORCE,
        FaultBoundary.AFTER,
        0,
        0);
    StatusCode afterStatus = afterBoundary(afterAction, FaultOperation.DIRECTORY_FORCE);
    if (!running || generation != startingGeneration) {
      result.set(null, DirectoryDurability.UNKNOWN);
    } else if (afterAction == FaultAction.DELAY) {
      if (exposePending) {
        operationCompletionPending = true;
      } else {
        afterStatus = StatusCode.OK;
      }
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
    if (file == null || file.directory) {
      return StatusCode.CONFLICT;
    }
    if (openHandles == maxOpenHandles) {
      return StatusCode.RESOURCE_EXHAUSTED;
    }
    openHandles++;
    DirectoryDurability durability = fileName.equals(file.durableName)
        ? DirectoryDurability.DURABLE
        : DirectoryDurability.VISIBLE_NOT_DURABLE;
    result.set(new ModelHandle(file, generation), durability);
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

  @Override
  public synchronized StatusCode inspect(
      AtomicInstallProgress progress,
      AtomicInstallSnapshot result) {
    return progressState.snapshot(progress, result);
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
    StatusCode progressStatus;
    if (!running || progressState.providerGeneration(progress) != generation) {
      progressStatus = progressState.requireRecovery(progress);
    } else if (afterAction == FaultAction.DELAY) {
      progressStatus = progressState.delayCompletion(
          progress,
          before,
          next,
          durability,
          progressState.bytesWritten(progress));
    } else {
      progressStatus = progressState.transition(
          progress,
          before,
          next,
          durability,
          progressState.bytesWritten(progress));
    }
    if (!progressStatus.isOk()) {
      afterStatus = progressStatus;
    }
    return record(afterStatus, step, before, progress, result, bytesTransferred);
  }

  private FaultAction decide(
      FaultPoint point,
      FaultOperation operation,
      FaultBoundary boundary,
      long position,
      int requestedBytes) {
    faultInjector.evaluate(
        point,
        operation,
        boundary,
        ++operationSequence,
        position,
        requestedBytes,
        decision);
    return decision.action();
  }

  private StatusCode beforeBoundary(FaultAction action, FaultOperation operation) {
    if (!action.isCompatibleWith(operation, FaultBoundary.BEFORE)) {
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
    if (!action.isCompatibleWith(operation, FaultBoundary.AFTER)) {
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
    return record(
        status,
        step,
        progressState.phase(progress),
        progress,
        result,
        bytesTransferred);
  }

  private StatusCode record(
      StatusCode status,
      AtomicInstallStep step,
      AtomicInstallPhase before,
      AtomicInstallProgress progress,
      AtomicInstallResult result,
      int bytesTransferred) {
    progressSnapshot.reset();
    progressState.snapshot(progress, progressSnapshot);
    result.set(
        step,
        before,
        progressSnapshot.appliedPhase(),
        progressSnapshot.appliedDurability(),
        bytesTransferred,
        progressSnapshot.completionPending());
    if (trace != null) {
      StatusCode appendStatus = trace.append(
          step,
          before,
          progressSnapshot.appliedPhase(),
          progressSnapshot.appliedDurability(),
          status,
          progressSnapshot.completionPending());
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
    private final long openedFileEpoch;
    private boolean closed;

    private ModelHandle(ModelFile file, long openedGeneration) {
      this.file = file;
      this.openedGeneration = openedGeneration;
      openedFileEpoch = file.epoch;
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
        file.publishDurable(mode);
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
        if (openedFileEpoch != file.epoch) {
          closed = true;
          openHandles--;
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
      return running && openedGeneration == generation && openedFileEpoch == file.epoch
          ? StatusCode.OK
          : StatusCode.CANCELLED;
    }
  }

  private static final class ModelFile {
    private final byte[] volatileBytes;
    private final byte[] durableBytes;
    private String volatileName;
    private String durableName;
    private boolean directory;
    private int volatileSize;
    private int durableSize;
    private long epoch;

    private ModelFile(int capacity) {
      volatileBytes = new byte[capacity];
      durableBytes = new byte[capacity];
    }

    private void prepare(String name, boolean isDirectory) {
      Arrays.fill(volatileBytes, (byte) 0);
      Arrays.fill(durableBytes, (byte) 0);
      volatileName = name;
      durableName = null;
      directory = isDirectory;
      volatileSize = 0;
      durableSize = 0;
      epoch++;
    }

    private void publishDurable() {
      System.arraycopy(volatileBytes, 0, durableBytes, 0, volatileSize);
      if (durableSize > volatileSize) {
        Arrays.fill(durableBytes, volatileSize, durableSize, (byte) 0);
      }
      durableSize = volatileSize;
    }

    private void publishDurable(ForceMode mode) {
      switch (mode) {
        case CONTENT -> {
          int publishedSize = Math.min(volatileSize, durableSize);
          System.arraycopy(volatileBytes, 0, durableBytes, 0, publishedSize);
        }
        case CONTENT_AND_METADATA -> publishDurable();
      }
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
