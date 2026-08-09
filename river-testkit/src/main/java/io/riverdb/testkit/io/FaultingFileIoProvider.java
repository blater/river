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
  public StatusCode open(String fileName, OpenFileResult result) {
    result.reset();
    if (!running) {
      return StatusCode.RETRY;
    }
    if (!validFileName(fileName)) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    FaultAction action = decide(faultPoints.open(), FaultOperation.OPEN, 0, 0);
    StatusCode actionStatus = providerAction(action);
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
    }
    openHandles++;
    result.setFile(new ModelHandle(modelFile, generation));
    return StatusCode.OK;
  }

  /** Simulates abrupt process loss: unforced state disappears and all handles become stale. */
  public StatusCode crash() {
    if (!running) {
      return StatusCode.OK;
    }
    for (int index = 0; index < fileCount; index++) {
      files[index].restoreDurable();
    }
    running = false;
    generation++;
    openHandles = 0;
    return StatusCode.OK;
  }

  /** Starts a new model process generation. Callers must reopen files. */
  public StatusCode restart() {
    if (running) {
      return StatusCode.OK;
    }
    running = true;
    return StatusCode.OK;
  }

  public boolean isRunning() {
    return running;
  }

  public long generation() {
    return generation;
  }

  public int fileCount() {
    return fileCount;
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

  private StatusCode providerAction(FaultAction action) {
    if (action == FaultAction.CANCEL) {
      return StatusCode.CANCELLED;
    }
    if (action == FaultAction.CRASH) {
      crash();
      return StatusCode.IO_FAILURE;
    }
    if (action == FaultAction.RESTART) {
      crash();
      restart();
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
      StatusCode actionStatus = providerAction(action);
      if (!actionStatus.isOk()) {
        return actionStatus;
      }
      int available = Math.max(0, file.volatileSize - (int) position);
      int transferred = Math.min(requested, available);
      if (action == FaultAction.SHORT_READ) {
        transferred = limitedTransfer(transferred, faultDecision.argument());
      }
      int xorMask = action == FaultAction.CORRUPT_READ
          ? (int) (faultDecision.argument() == 0 ? 1 : faultDecision.argument())
          : 0;
      for (int index = 0; index < transferred; index++) {
        target.put((byte) (file.volatileBytes[(int) position + index] ^ xorMask));
      }
      result.setBytesTransferred(transferred);
      return action == FaultAction.CORRUPT_READ
          ? StatusCode.CORRUPTION
          : StatusCode.OK;
    }

    @Override
    public StatusCode write(long position, ByteBuffer source, IoResult result) {
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
      StatusCode actionStatus = providerAction(action);
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
      file.volatileSize = Math.max(file.volatileSize, (int) position + transferred);
      result.setBytesTransferred(transferred);
      if (action == FaultAction.TORN_WRITE) {
        System.arraycopy(
            file.volatileBytes,
            (int) position,
            file.durableBytes,
            (int) position,
            transferred);
        file.durableSize = Math.max(file.durableSize, (int) position + transferred);
      }
      return resultStatus;
    }

    @Override
    public StatusCode force(ForceMode mode) {
      StatusCode stateStatus = checkState();
      if (!stateStatus.isOk()) {
        return stateStatus;
      }
      FaultAction action = decide(faultPoints.force(), FaultOperation.FORCE, 0, file.volatileSize);
      StatusCode actionStatus = providerAction(action);
      if (!actionStatus.isOk()) {
        return actionStatus;
      }
      if (action == FaultAction.FORCE_FAILURE || action == FaultAction.DISK_FULL) {
        return action == FaultAction.DISK_FULL
            ? StatusCode.RESOURCE_EXHAUSTED
            : StatusCode.IO_FAILURE;
      }
      file.publishDurable();
      return StatusCode.OK;
    }

    @Override
    public StatusCode truncate(long sizeBytes) {
      StatusCode stateStatus = checkState();
      if (!stateStatus.isOk()) {
        return stateStatus;
      }
      if (sizeBytes < 0 || sizeBytes > maxFileBytes) {
        return StatusCode.INVALID_EXTERNAL_INPUT;
      }
      FaultAction action = decide(faultPoints.truncate(), FaultOperation.TRUNCATE, sizeBytes, 0);
      StatusCode actionStatus = providerAction(action);
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
      StatusCode stateStatus = checkState();
      if (!stateStatus.isOk()) {
        return stateStatus;
      }
      result.setSizeBytes(file.volatileSize);
      return StatusCode.OK;
    }

    @Override
    public StatusCode close() {
      if (closed) {
        return StatusCode.OK;
      }
      if (openedGeneration != generation) {
        closed = true;
        return StatusCode.CANCELLED;
      }
      FaultAction action = decide(faultPoints.close(), FaultOperation.CLOSE, 0, 0);
      StatusCode actionStatus = providerAction(action);
      if (!actionStatus.isOk()) {
        return actionStatus;
      }
      closed = true;
      openHandles--;
      return StatusCode.OK;
    }

    private StatusCode checkState() {
      if (closed || openedGeneration != generation || !running) {
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
