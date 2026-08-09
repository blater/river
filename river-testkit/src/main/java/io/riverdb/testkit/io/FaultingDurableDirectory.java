package io.riverdb.testkit.io;

import io.riverdb.base.error.StatusCode;
import io.riverdb.platform.fault.FaultAction;
import io.riverdb.platform.fault.FaultBoundary;
import io.riverdb.platform.fault.FaultDecision;
import io.riverdb.platform.fault.FaultInjector;
import io.riverdb.platform.fault.FaultOperation;
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
 * Bounded flat-directory persistence model for the general {@link DurableDirectory} contract.
 *
 * <p>File force publishes bytes and length. Directory force publishes child names and kinds.
 * Crash restores those images independently and invalidates all handles. This is a protocol fake,
 * not a model of page cache, controller cache, torn directory blocks, or device power removal.
 */
public final class FaultingDurableDirectory implements DurableDirectory {
  private final Entry[] entries;
  private final int maxFileBytes;
  private final int maxOpenHandles;
  private final FaultInjector injector;
  private final DirectoryFaultPoints points;
  private final DirectoryOperationTrace trace;
  private final FaultDecision decision = new FaultDecision();
  private int entryCount;
  private int openHandles;
  private long operationSequence;
  private long generation = 1;
  private StatusCode traceStatus = StatusCode.OK;
  private boolean running = true;

  public FaultingDurableDirectory(
      int maxEntries,
      int maxFileBytes,
      int maxOpenHandles,
      FaultInjector injector,
      DirectoryFaultPoints points,
      DirectoryOperationTrace trace) {
    entries = new Entry[Math.max(0, maxEntries)];
    this.maxFileBytes = Math.max(0, maxFileBytes);
    this.maxOpenHandles = Math.max(0, maxOpenHandles);
    this.injector = injector;
    this.points = points;
    this.trace = trace;
  }

  @Override
  public synchronized StatusCode createDirectory(
      String childDirectoryName,
      DirectoryOperationResult result) {
    return createEntry(childDirectoryName, true, DirectoryOperation.CREATE_DIRECTORY, result);
  }

  @Override
  public synchronized StatusCode createFile(
      String fileName,
      DirectoryOperationResult result) {
    return createEntry(fileName, false, DirectoryOperation.CREATE_FILE, result);
  }

  @Override
  public synchronized StatusCode createTemporary(
      String temporaryFileName,
      DirectoryOperationResult result) {
    return createEntry(temporaryFileName, false, DirectoryOperation.CREATE_FILE, result);
  }

  private StatusCode createEntry(
      String name,
      boolean directory,
      DirectoryOperation operation,
      DirectoryOperationResult result) {
    result.reset();
    if (!running) {
      return record(operation, StatusCode.RETRY, result.durability());
    }
    if (!validName(name)) {
      return record(operation, StatusCode.INVALID_EXTERNAL_INPUT, result.durability());
    }
    long started = generation;
    StatusCode status = before(operation, 0, 0);
    if (!status.isOk()) {
      unknownIfGenerationChanged(started, result);
      return record(operation, status, result.durability());
    }
    FaultAction action = decision.action();
    if (action == FaultAction.DISK_FULL) {
      return record(operation, StatusCode.RESOURCE_EXHAUSTED, result.durability());
    }
    if (find(name) != null) {
      return record(operation, StatusCode.CONFLICT, result.durability());
    }
    if (!directory && openHandles == maxOpenHandles) {
      return record(operation, StatusCode.RESOURCE_EXHAUSTED, result.durability());
    }
    Entry entry = allocate();
    if (entry == null) {
      return record(operation, StatusCode.RESOURCE_EXHAUSTED, result.durability());
    }
    entry.prepare(name, directory);
    DurableFile file = null;
    if (!directory) {
      openHandles++;
      file = new Handle(entry, generation);
    }
    result.set(file, DirectoryDurability.VISIBLE_NOT_DURABLE);
    status = after(operation, 0, 0);
    unknownIfGenerationChanged(started, result);
    return record(operation, status, result.durability());
  }

  @Override
  public synchronized StatusCode list(DirectoryListResult result) {
    result.reset();
    if (!running) {
      return record(DirectoryOperation.LIST, StatusCode.RETRY, DirectoryDurability.NOT_APPLIED);
    }
    long started = generation;
    StatusCode status = before(DirectoryOperation.LIST, 0, entryCount);
    if (!status.isOk()) {
      return record(DirectoryOperation.LIST, status, DirectoryDurability.NOT_APPLIED);
    }
    for (int index = 0; index < entryCount; index++) {
      Entry entry = entries[index];
      if (entry.volatileName == null) {
        continue;
      }
      status = result.add(
          entry.volatileName,
          entry.volatileDirectory ? DirectoryEntryType.DIRECTORY : DirectoryEntryType.FILE);
      if (!status.isOk()) {
        return record(DirectoryOperation.LIST, status, namespaceDurability());
      }
    }
    result.finish(generation);
    status = after(DirectoryOperation.LIST, 0, result.size());
    if (generation != started) {
      result.reset();
    }
    return record(DirectoryOperation.LIST, status, namespaceDurability());
  }

  @Override
  public synchronized StatusCode rename(
      String sourceName,
      String destinationName,
      DirectoryOperationResult result) {
    return renameInternal(sourceName, destinationName, false, result);
  }

  @Override
  public synchronized StatusCode replace(
      String temporaryFileName,
      String destinationFileName,
      DirectoryOperationResult result) {
    return renameInternal(temporaryFileName, destinationFileName, true, result);
  }

  private StatusCode renameInternal(
      String sourceName,
      String destinationName,
      boolean replace,
      DirectoryOperationResult result) {
    result.reset();
    DirectoryOperation operation = DirectoryOperation.RENAME;
    if (!running) {
      return record(operation, StatusCode.RETRY, result.durability());
    }
    if (!validName(sourceName) || !validName(destinationName)) {
      return record(operation, StatusCode.INVALID_EXTERNAL_INPUT, result.durability());
    }
    long started = generation;
    StatusCode status = before(operation, 0, 0);
    if (!status.isOk()) {
      unknownIfGenerationChanged(started, result);
      return record(operation, status, result.durability());
    }
    Entry source = find(sourceName);
    Entry destination = find(destinationName);
    if (source == null || source == destination || !replace && destination != null) {
      return record(operation, StatusCode.CONFLICT, result.durability());
    }
    if (destination != null) {
      destination.volatileName = null;
    }
    source.volatileName = destinationName;
    result.set(null, DirectoryDurability.VISIBLE_NOT_DURABLE);
    status = after(operation, 0, 0);
    unknownIfGenerationChanged(started, result);
    return record(operation, status, result.durability());
  }

  @Override
  public synchronized StatusCode remove(String entryName, DirectoryOperationResult result) {
    result.reset();
    DirectoryOperation operation = DirectoryOperation.REMOVE;
    if (!running) {
      return record(operation, StatusCode.RETRY, result.durability());
    }
    if (!validName(entryName)) {
      return record(operation, StatusCode.INVALID_EXTERNAL_INPUT, result.durability());
    }
    long started = generation;
    StatusCode status = before(operation, 0, 0);
    if (!status.isOk()) {
      unknownIfGenerationChanged(started, result);
      return record(operation, status, result.durability());
    }
    Entry entry = find(entryName);
    if (entry == null) {
      return record(operation, StatusCode.CONFLICT, result.durability());
    }
    entry.volatileName = null;
    result.set(null, DirectoryDurability.VISIBLE_NOT_DURABLE);
    status = after(operation, 0, 0);
    unknownIfGenerationChanged(started, result);
    return record(operation, status, result.durability());
  }

  @Override
  public synchronized StatusCode truncate(
      String fileName,
      long sizeBytes,
      DirectoryOperationResult result) {
    result.reset();
    DirectoryOperation operation = DirectoryOperation.TRUNCATE;
    if (!running) {
      return record(operation, StatusCode.RETRY, result.durability());
    }
    if (!validName(fileName) || sizeBytes < 0 || sizeBytes > maxFileBytes) {
      return record(operation, StatusCode.INVALID_EXTERNAL_INPUT, result.durability());
    }
    long started = generation;
    StatusCode status = before(operation, sizeBytes, 0);
    if (!status.isOk()) {
      unknownIfGenerationChanged(started, result);
      return record(operation, status, result.durability());
    }
    Entry entry = find(fileName);
    if (entry == null || entry.volatileDirectory) {
      return record(operation, StatusCode.CONFLICT, result.durability());
    }
    if (openHandles == maxOpenHandles) {
      return record(operation, StatusCode.RESOURCE_EXHAUSTED, result.durability());
    }
    if (sizeBytes < entry.volatileSize) {
      Arrays.fill(entry.volatileBytes, (int) sizeBytes, entry.volatileSize, (byte) 0);
    } else if (sizeBytes > entry.volatileSize) {
      Arrays.fill(entry.volatileBytes, entry.volatileSize, (int) sizeBytes, (byte) 0);
    }
    entry.volatileSize = (int) sizeBytes;
    openHandles++;
    result.set(new Handle(entry, generation), DirectoryDurability.VISIBLE_NOT_DURABLE);
    status = after(operation, sizeBytes, 0);
    unknownIfGenerationChanged(started, result);
    return record(operation, status, result.durability());
  }

  @Override
  public synchronized StatusCode force(DirectoryOperationResult result) {
    result.reset();
    DirectoryOperation operation = DirectoryOperation.DIRECTORY_FORCE;
    if (!running) {
      return record(operation, StatusCode.RETRY, result.durability());
    }
    long started = generation;
    StatusCode status = before(operation, 0, 0);
    if (!status.isOk()) {
      unknownIfGenerationChanged(started, result);
      return record(operation, status, result.durability());
    }
    FaultAction action = decision.action();
    if (action == FaultAction.FORCE_FAILURE || action == FaultAction.DISK_FULL) {
      status = action == FaultAction.DISK_FULL
          ? StatusCode.RESOURCE_EXHAUSTED
          : StatusCode.IO_FAILURE;
      return record(operation, status, result.durability());
    }
    for (int index = 0; index < entryCount; index++) {
      entries[index].publishNamespace();
    }
    result.set(null, DirectoryDurability.DURABLE);
    status = after(operation, 0, 0);
    unknownIfGenerationChanged(started, result);
    return record(operation, status, result.durability());
  }

  @Override
  public synchronized StatusCode reopen(String fileName, DirectoryOperationResult result) {
    result.reset();
    DirectoryOperation operation = DirectoryOperation.REOPEN;
    if (!running) {
      return record(operation, StatusCode.RETRY, result.durability());
    }
    if (!validName(fileName)) {
      return record(operation, StatusCode.INVALID_EXTERNAL_INPUT, result.durability());
    }
    long started = generation;
    StatusCode status = before(operation, 0, 0);
    if (!status.isOk()) {
      unknownIfGenerationChanged(started, result);
      return record(operation, status, result.durability());
    }
    Entry entry = find(fileName);
    if (entry == null || entry.volatileDirectory) {
      return record(operation, StatusCode.CONFLICT, result.durability());
    }
    if (openHandles == maxOpenHandles) {
      return record(operation, StatusCode.RESOURCE_EXHAUSTED, result.durability());
    }
    openHandles++;
    DirectoryDurability durability = fileName.equals(entry.durableName)
        ? DirectoryDurability.DURABLE
        : DirectoryDurability.VISIBLE_NOT_DURABLE;
    result.set(new Handle(entry, generation), durability);
    status = after(operation, 0, 0);
    unknownIfGenerationChanged(started, result);
    return record(operation, status, result.durability());
  }

  /** Abruptly discards volatile images and invalidates every open handle. */
  public synchronized StatusCode crash() {
    if (running) {
      performCrash();
    }
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

  private StatusCode before(DirectoryOperation operation, long position, int requestedBytes) {
    return boundary(operation, FaultBoundary.BEFORE, position, requestedBytes);
  }

  private StatusCode after(DirectoryOperation operation, long position, int requestedBytes) {
    return boundary(operation, FaultBoundary.AFTER, position, requestedBytes);
  }

  private StatusCode boundary(
      DirectoryOperation operation,
      FaultBoundary boundary,
      long position,
      int requestedBytes) {
    FaultOperation faultOperation = faultOperation(operation);
    injector.evaluate(
        points.point(operation, boundary),
        faultOperation,
        boundary,
        ++operationSequence,
        position,
        requestedBytes,
        decision);
    FaultAction action = decision.action();
    if (!action.isCompatibleWith(faultOperation, boundary)) {
      return StatusCode.INVARIANT_BROKEN;
    }
    if (action == FaultAction.DELAY) {
      return boundary == FaultBoundary.BEFORE ? StatusCode.RETRY : StatusCode.OK;
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

  private void performCrash() {
    for (int index = 0; index < entryCount; index++) {
      entries[index].restoreDurable();
    }
    running = false;
    generation++;
    openHandles = 0;
  }

  private void unknownIfGenerationChanged(long started, DirectoryOperationResult result) {
    if (generation != started) {
      result.set(null, DirectoryDurability.UNKNOWN);
    }
  }

  private StatusCode record(
      DirectoryOperation operation,
      StatusCode status,
      DirectoryDurability durability) {
    if (trace != null) {
      StatusCode appended = trace.append(operation, status, durability, generation);
      if (!appended.isOk()) {
        traceStatus = appended;
      }
    }
    return status;
  }

  private DirectoryDurability namespaceDurability() {
    for (int index = 0; index < entryCount; index++) {
      Entry entry = entries[index];
      if (!same(entry.volatileName, entry.durableName)
          || entry.volatileDirectory != entry.durableDirectory) {
        return DirectoryDurability.VISIBLE_NOT_DURABLE;
      }
    }
    return DirectoryDurability.DURABLE;
  }

  private Entry allocate() {
    if (entryCount == entries.length) {
      return null;
    }
    Entry entry = new Entry(maxFileBytes);
    entries[entryCount++] = entry;
    return entry;
  }

  private Entry find(String name) {
    for (int index = 0; index < entryCount; index++) {
      if (name.equals(entries[index].volatileName)) {
        return entries[index];
      }
    }
    return null;
  }

  private static boolean same(String first, String second) {
    return first == null ? second == null : first.equals(second);
  }

  private static boolean validName(String name) {
    if (name == null || name.isBlank() || name.length() > 128) {
      return false;
    }
    return name.indexOf('/') < 0
        && name.indexOf('\\') < 0
        && !name.equals(".")
        && !name.equals("..");
  }

  private static int limitedTransfer(int available, long requestedLimit) {
    return (int) Math.max(0, Math.min(available, requestedLimit));
  }

  static FaultOperation faultOperation(DirectoryOperation operation) {
    return switch (operation) {
      case CREATE_DIRECTORY -> FaultOperation.DIRECTORY_CREATE;
      case CREATE_FILE -> FaultOperation.FILE_CREATE;
      case LIST -> FaultOperation.DIRECTORY_LIST;
      case RENAME -> FaultOperation.FILE_RENAME;
      case REMOVE -> FaultOperation.FILE_REMOVE;
      case TRUNCATE -> FaultOperation.NAMED_TRUNCATE;
      case FILE_READ -> FaultOperation.DIRECTORY_FILE_READ;
      case FILE_WRITE -> FaultOperation.DIRECTORY_FILE_WRITE;
      case FILE_FORCE -> FaultOperation.DIRECTORY_FILE_FORCE;
      case DIRECTORY_FORCE -> FaultOperation.DIRECTORY_FORCE;
      case REOPEN -> FaultOperation.DIRECTORY_REOPEN;
    };
  }

  private final class Handle implements DurableFile {
    private final Entry entry;
    private final long openedGeneration;
    private boolean closed;

    private Handle(Entry entry, long openedGeneration) {
      this.entry = entry;
      this.openedGeneration = openedGeneration;
    }

    @Override
    public StatusCode read(long position, ByteBuffer target, IoResult result) {
      synchronized (FaultingDurableDirectory.this) {
        result.reset();
        StatusCode status = checkState();
        if (!status.isOk()) {
          return record(DirectoryOperation.FILE_READ, status, DirectoryDurability.NOT_APPLIED);
        }
        if (position < 0 || position > entry.volatileSize) {
          return record(
              DirectoryOperation.FILE_READ,
              StatusCode.INVALID_EXTERNAL_INPUT,
              DirectoryDurability.NOT_APPLIED);
        }
        status = before(DirectoryOperation.FILE_READ, position, target.remaining());
        if (!status.isOk()) {
          return record(DirectoryOperation.FILE_READ, status, DirectoryDurability.NOT_APPLIED);
        }
        int transferred = Math.min(target.remaining(), entry.volatileSize - (int) position);
        FaultAction action = decision.action();
        if (action == FaultAction.SHORT_READ) {
          transferred = limitedTransfer(transferred, decision.argument());
        }
        int xor = action == FaultAction.CORRUPT_READ
                || action == FaultAction.DETECTED_CORRUPTION
            ? (int) (decision.argument() == 0 ? 1 : decision.argument())
            : 0;
        for (int index = 0; index < transferred; index++) {
          target.put((byte) (entry.volatileBytes[(int) position + index] ^ xor));
        }
        result.setBytesTransferred(transferred);
        status = action == FaultAction.DETECTED_CORRUPTION
            ? StatusCode.CORRUPTION
            : StatusCode.OK;
        if (status.isOk()) {
          status = after(DirectoryOperation.FILE_READ, position, transferred);
        }
        return record(DirectoryOperation.FILE_READ, status, DirectoryDurability.NOT_APPLIED);
      }
    }

    @Override
    public StatusCode write(long position, ByteBuffer source, IoResult result) {
      synchronized (FaultingDurableDirectory.this) {
        result.reset();
        StatusCode status = checkState();
        if (!status.isOk()) {
          return record(DirectoryOperation.FILE_WRITE, status, DirectoryDurability.NOT_APPLIED);
        }
        if (position < 0 || position > maxFileBytes) {
          return record(
              DirectoryOperation.FILE_WRITE,
              StatusCode.INVALID_EXTERNAL_INPUT,
              DirectoryDurability.NOT_APPLIED);
        }
        int requested = source.remaining();
        status = before(DirectoryOperation.FILE_WRITE, position, requested);
        if (!status.isOk()) {
          return record(DirectoryOperation.FILE_WRITE, status, DirectoryDurability.NOT_APPLIED);
        }
        FaultAction action = decision.action();
        int available = maxFileBytes - (int) position;
        int transferred = Math.min(requested, available);
        status = requested > available ? StatusCode.RESOURCE_EXHAUSTED : StatusCode.OK;
        if (action == FaultAction.SHORT_WRITE) {
          transferred = limitedTransfer(transferred, decision.argument());
        } else if (action == FaultAction.PARTIAL_WRITE || action == FaultAction.TORN_WRITE) {
          transferred = limitedTransfer(transferred, decision.argument());
          status = StatusCode.IO_FAILURE;
        } else if (action == FaultAction.DISK_FULL) {
          transferred = limitedTransfer(transferred, decision.argument());
          status = StatusCode.RESOURCE_EXHAUSTED;
        }
        source.get(entry.volatileBytes, (int) position, transferred);
        entry.volatileSize = Math.max(entry.volatileSize, (int) position + transferred);
        result.setBytesTransferred(transferred);
        if (action == FaultAction.TORN_WRITE) {
          System.arraycopy(
              entry.volatileBytes,
              (int) position,
              entry.durableBytes,
              (int) position,
              transferred);
          entry.durableSize = Math.max(entry.durableSize, (int) position + transferred);
        }
        if (status.isOk()) {
          status = after(DirectoryOperation.FILE_WRITE, position, transferred);
        }
        return record(
            DirectoryOperation.FILE_WRITE,
            status,
            DirectoryDurability.VISIBLE_NOT_DURABLE);
      }
    }

    @Override
    public StatusCode force(ForceMode mode) {
      synchronized (FaultingDurableDirectory.this) {
        StatusCode status = checkState();
        if (!status.isOk()) {
          return record(DirectoryOperation.FILE_FORCE, status, DirectoryDurability.NOT_APPLIED);
        }
        long started = generation;
        status = before(DirectoryOperation.FILE_FORCE, 0, entry.volatileSize);
        if (!status.isOk()) {
          return record(DirectoryOperation.FILE_FORCE, status, DirectoryDurability.NOT_APPLIED);
        }
        FaultAction action = decision.action();
        if (action == FaultAction.FORCE_FAILURE || action == FaultAction.DISK_FULL) {
          status = action == FaultAction.DISK_FULL
              ? StatusCode.RESOURCE_EXHAUSTED
              : StatusCode.IO_FAILURE;
          return record(DirectoryOperation.FILE_FORCE, status, DirectoryDurability.NOT_APPLIED);
        }
        entry.publishContent(mode);
        status = after(DirectoryOperation.FILE_FORCE, 0, entry.volatileSize);
        DirectoryDurability durability = generation == started
            ? DirectoryDurability.DURABLE
            : DirectoryDurability.UNKNOWN;
        return record(DirectoryOperation.FILE_FORCE, status, durability);
      }
    }

    @Override
    public StatusCode truncate(long sizeBytes) {
      synchronized (FaultingDurableDirectory.this) {
        StatusCode status = checkState();
        if (!status.isOk()) {
          return record(DirectoryOperation.TRUNCATE, status, DirectoryDurability.NOT_APPLIED);
        }
        if (sizeBytes < 0 || sizeBytes > maxFileBytes) {
          return record(
              DirectoryOperation.TRUNCATE,
              StatusCode.INVALID_EXTERNAL_INPUT,
              DirectoryDurability.NOT_APPLIED);
        }
        long started = generation;
        status = before(DirectoryOperation.TRUNCATE, sizeBytes, 0);
        if (!status.isOk()) {
          return record(DirectoryOperation.TRUNCATE, status, DirectoryDurability.NOT_APPLIED);
        }
        if (sizeBytes < entry.volatileSize) {
          Arrays.fill(entry.volatileBytes, (int) sizeBytes, entry.volatileSize, (byte) 0);
        } else if (sizeBytes > entry.volatileSize) {
          Arrays.fill(entry.volatileBytes, entry.volatileSize, (int) sizeBytes, (byte) 0);
        }
        entry.volatileSize = (int) sizeBytes;
        status = after(DirectoryOperation.TRUNCATE, sizeBytes, 0);
        DirectoryDurability durability = generation == started
            ? DirectoryDurability.VISIBLE_NOT_DURABLE
            : DirectoryDurability.UNKNOWN;
        return record(DirectoryOperation.TRUNCATE, status, durability);
      }
    }

    @Override
    public StatusCode size(FileSizeResult result) {
      synchronized (FaultingDurableDirectory.this) {
        StatusCode status = checkState();
        if (!status.isOk()) {
          return status;
        }
        result.setSizeBytes(entry.volatileSize);
        return StatusCode.OK;
      }
    }

    @Override
    public StatusCode close() {
      synchronized (FaultingDurableDirectory.this) {
        if (closed) {
          return StatusCode.CLOSED;
        }
        closed = true;
        if (openedGeneration != generation) {
          return StatusCode.CANCELLED;
        }
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

  private static final class Entry {
    private final byte[] volatileBytes;
    private final byte[] durableBytes;
    private String volatileName;
    private String durableName;
    private boolean volatileDirectory;
    private boolean durableDirectory;
    private int volatileSize;
    private int durableSize;

    private Entry(int maxFileBytes) {
      volatileBytes = new byte[maxFileBytes];
      durableBytes = new byte[maxFileBytes];
    }

    private void prepare(String name, boolean directory) {
      volatileName = name;
      durableName = null;
      volatileDirectory = directory;
      durableDirectory = false;
      volatileSize = 0;
      durableSize = 0;
      Arrays.fill(volatileBytes, (byte) 0);
      Arrays.fill(durableBytes, (byte) 0);
    }

    private void publishNamespace() {
      durableName = volatileName;
      durableDirectory = volatileDirectory;
    }

    private void publishContent(ForceMode mode) {
      switch (mode) {
        case CONTENT -> {
          int publishedSize = Math.min(volatileSize, durableSize);
          System.arraycopy(volatileBytes, 0, durableBytes, 0, publishedSize);
        }
        case CONTENT_AND_METADATA -> {
          System.arraycopy(volatileBytes, 0, durableBytes, 0, volatileSize);
          if (durableSize > volatileSize) {
            Arrays.fill(durableBytes, volatileSize, durableSize, (byte) 0);
          }
          durableSize = volatileSize;
        }
      }
    }

    private void restoreDurable() {
      volatileName = durableName;
      volatileDirectory = durableDirectory;
      System.arraycopy(durableBytes, 0, volatileBytes, 0, durableSize);
      if (volatileSize > durableSize) {
        Arrays.fill(volatileBytes, durableSize, volatileSize, (byte) 0);
      }
      volatileSize = durableSize;
    }
  }
}
