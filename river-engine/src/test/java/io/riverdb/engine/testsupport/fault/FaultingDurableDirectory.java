package io.riverdb.engine.testsupport.fault;

import io.riverdb.base.error.StatusCode;
import io.riverdb.platform.file.DirectoryDurability;
import io.riverdb.platform.file.DirectoryEntryType;
import io.riverdb.platform.file.DirectoryListResult;
import io.riverdb.platform.file.DirectoryOperationResult;
import io.riverdb.platform.file.DurableDirectory;
import io.riverdb.platform.file.DurableFile;
import io.riverdb.platform.file.FileIoMode;
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
  private final FaultingDurableEntry[] entries;
  private final int maxFileBytes;
  private final int maxOpenHandles;
  private final FaultingDurableFaultBoundary faults;
  private int entryCount;
  private int openHandles;
  private long generation = 1;
  private boolean running = true;

  public FaultingDurableDirectory(
      int maxEntries,
      int maxFileBytes,
      int maxOpenHandles,
      FaultInjector injector,
      DirectoryFaultPoints points) {
    entries = new FaultingDurableEntry[Math.max(0, maxEntries)];
    this.maxFileBytes = Math.max(0, maxFileBytes);
    this.maxOpenHandles = Math.max(0, maxOpenHandles);
    faults = new FaultingDurableFaultBoundary(injector, points);
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
      FileIoMode mode,
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
      return StatusCode.RETRY;
    }
    if (!validName(name)) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    long started = generation;
    StatusCode status = faults.before(this, operation, 0, 0);
    if (!status.isOk()) {
      unknownIfGenerationChanged(started, result);
      return status;
    }
    FaultAction action = faults.action();
    if (action == FaultAction.DISK_FULL) {
      return StatusCode.RESOURCE_EXHAUSTED;
    }
    if (find(name) != null) {
      return StatusCode.CONFLICT;
    }
    if (!directory && openHandles == maxOpenHandles) {
      return StatusCode.RESOURCE_EXHAUSTED;
    }
    FaultingDurableEntry entry = allocate();
    if (entry == null) {
      return StatusCode.RESOURCE_EXHAUSTED;
    }
    entry.prepare(name, directory);
    DurableFile file = null;
    if (!directory) {
      openHandles++;
      file = new FaultingDurableFile(this, faults, entry, generation);
    }
    result.set(file, DirectoryDurability.VISIBLE_NOT_DURABLE);
    status = faults.after(this, operation, 0, 0);
    unknownIfGenerationChanged(started, result);
    return status;
  }

  @Override
  public synchronized StatusCode list(DirectoryListResult result) {
    result.reset();
    if (!running) {
      return StatusCode.RETRY;
    }
    long started = generation;
    StatusCode status = faults.before(this, DirectoryOperation.LIST, 0, entryCount);
    if (!status.isOk()) {
      return status;
    }
    for (int index = 0; index < entryCount; index++) {
      FaultingDurableEntry entry = entries[index];
      if (entry.volatileName == null) {
        continue;
      }
      status = result.add(
          entry.volatileName,
          entry.volatileDirectory ? DirectoryEntryType.DIRECTORY : DirectoryEntryType.FILE);
      if (!status.isOk()) {
        return status;
      }
    }
    result.finish(generation);
    status = faults.after(this, DirectoryOperation.LIST, 0, result.size());
    if (generation != started) {
      result.reset();
    }
    return status;
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
      return StatusCode.RETRY;
    }
    if (!validName(sourceName) || !validName(destinationName)) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    long started = generation;
    StatusCode status = faults.before(this, operation, 0, 0);
    if (!status.isOk()) {
      unknownIfGenerationChanged(started, result);
      return status;
    }
    FaultingDurableEntry source = find(sourceName);
    FaultingDurableEntry destination = find(destinationName);
    if (source == null
        || source == destination
        || replace && (source.volatileDirectory
            || destination != null && destination.volatileDirectory)
        || !replace && destination != null) {
      return StatusCode.CONFLICT;
    }
    if (destination != null) {
      destination.volatileName = null;
    }
    source.volatileName = destinationName;
    result.set(null, DirectoryDurability.VISIBLE_NOT_DURABLE);
    status = faults.after(this, operation, 0, 0);
    unknownIfGenerationChanged(started, result);
    return status;
  }

  @Override
  public synchronized StatusCode remove(String entryName, DirectoryOperationResult result) {
    result.reset();
    DirectoryOperation operation = DirectoryOperation.REMOVE;
    if (!running) {
      return StatusCode.RETRY;
    }
    if (!validName(entryName)) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    long started = generation;
    StatusCode status = faults.before(this, operation, 0, 0);
    if (!status.isOk()) {
      unknownIfGenerationChanged(started, result);
      return status;
    }
    FaultingDurableEntry entry = find(entryName);
    if (entry == null) {
      return StatusCode.CONFLICT;
    }
    entry.volatileName = null;
    result.set(null, DirectoryDurability.VISIBLE_NOT_DURABLE);
    status = faults.after(this, operation, 0, 0);
    unknownIfGenerationChanged(started, result);
    return status;
  }

  @Override
  public synchronized StatusCode truncate(
      String fileName,
      long sizeBytes,
      DirectoryOperationResult result) {
    result.reset();
    DirectoryOperation operation = DirectoryOperation.TRUNCATE;
    if (!running) {
      return StatusCode.RETRY;
    }
    if (!validName(fileName) || sizeBytes < 0 || sizeBytes > maxFileBytes) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    long started = generation;
    StatusCode status = faults.before(this, operation, sizeBytes, 0);
    if (!status.isOk()) {
      unknownIfGenerationChanged(started, result);
      return status;
    }
    FaultingDurableEntry entry = find(fileName);
    if (entry == null || entry.volatileDirectory) {
      return StatusCode.CONFLICT;
    }
    if (openHandles == maxOpenHandles) {
      return StatusCode.RESOURCE_EXHAUSTED;
    }
    if (sizeBytes < entry.volatileSize) {
      Arrays.fill(entry.volatileBytes, (int) sizeBytes, entry.volatileSize, (byte) 0);
    } else if (sizeBytes > entry.volatileSize) {
      Arrays.fill(entry.volatileBytes, entry.volatileSize, (int) sizeBytes, (byte) 0);
    }
    entry.volatileSize = (int) sizeBytes;
    openHandles++;
    result.set(
        new FaultingDurableFile(this, faults, entry, generation),
        DirectoryDurability.VISIBLE_NOT_DURABLE);
    status = faults.after(this, operation, sizeBytes, 0);
    unknownIfGenerationChanged(started, result);
    return status;
  }

  @Override
  public synchronized StatusCode force(DirectoryOperationResult result) {
    result.reset();
    DirectoryOperation operation = DirectoryOperation.DIRECTORY_FORCE;
    if (!running) {
      return StatusCode.RETRY;
    }
    long started = generation;
    StatusCode status = faults.before(this, operation, 0, 0);
    if (!status.isOk()) {
      unknownIfGenerationChanged(started, result);
      return status;
    }
    FaultAction action = faults.action();
    if (action == FaultAction.FORCE_FAILURE || action == FaultAction.DISK_FULL) {
      status = action == FaultAction.DISK_FULL
          ? StatusCode.RESOURCE_EXHAUSTED
          : StatusCode.IO_FAILURE;
      return status;
    }
    for (int index = 0; index < entryCount; index++) {
      entries[index].publishNamespace();
    }
    result.set(null, DirectoryDurability.DURABLE);
    status = faults.after(this, operation, 0, 0);
    unknownIfGenerationChanged(started, result);
    return status;
  }

  @Override
  public synchronized StatusCode reopen(
      String fileName, FileIoMode mode, DirectoryOperationResult result) {
    result.reset();
    DirectoryOperation operation = DirectoryOperation.REOPEN;
    if (!running) {
      return StatusCode.RETRY;
    }
    if (!validName(fileName)) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    long started = generation;
    StatusCode status = faults.before(this, operation, 0, 0);
    if (!status.isOk()) {
      unknownIfGenerationChanged(started, result);
      return status;
    }
    FaultingDurableEntry entry = find(fileName);
    if (entry == null || entry.volatileDirectory) {
      return StatusCode.CONFLICT;
    }
    if (openHandles == maxOpenHandles) {
      return StatusCode.RESOURCE_EXHAUSTED;
    }
    openHandles++;
    DirectoryDurability durability = fileName.equals(entry.durableName)
        ? DirectoryDurability.DURABLE
        : DirectoryDurability.VISIBLE_NOT_DURABLE;
    result.set(new FaultingDurableFile(this, faults, entry, generation), durability);
    status = faults.after(this, operation, 0, 0);
    unknownIfGenerationChanged(started, result);
    return status;
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

  void crashFromFault(boolean restart) {
    performCrash();
    if (restart) {
      running = true;
    }
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

  private FaultingDurableEntry allocate() {
    if (entryCount == entries.length) {
      return null;
    }
    FaultingDurableEntry entry = new FaultingDurableEntry(maxFileBytes);
    entries[entryCount++] = entry;
    return entry;
  }

  private FaultingDurableEntry find(String name) {
    for (int index = 0; index < entryCount; index++) {
      if (name.equals(entries[index].volatileName)) {
        return entries[index];
      }
    }
    return null;
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

  boolean isLive(long openedGeneration) {
    return running && openedGeneration == generation;
  }

  boolean closeHandle(long openedGeneration) {
    if (openedGeneration != generation) {
      return false;
    }
    openHandles--;
    return true;
  }




}
