package io.riverdb.platform.file.nio;

import io.riverdb.base.concurrent.FatalState;
import io.riverdb.base.error.StatusCode;
import io.riverdb.platform.file.DirectoryDurability;
import io.riverdb.platform.file.DirectoryEntryType;
import io.riverdb.platform.file.DirectoryListResult;
import io.riverdb.platform.file.DirectoryOperationResult;
import io.riverdb.platform.file.DurableDirectory;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.DirectoryNotEmptyException;
import java.nio.file.DirectoryStream;
import java.nio.file.DirectoryIteratorException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.OpenOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;

/** Java NIO implementation rooted at one resolved database directory. */
public final class NioDurableDirectory implements DurableDirectory {
  private static final OpenOption[] CREATE_OPTIONS = {
    StandardOpenOption.CREATE_NEW,
    StandardOpenOption.READ,
    StandardOpenOption.WRITE
  };
  private static final OpenOption[] REOPEN_OPTIONS = {
    StandardOpenOption.READ,
    StandardOpenOption.WRITE,
    LinkOption.NOFOLLOW_LINKS
  };

  private final Path root;
  private final FatalState fatalState;
  private final NioIoCounters counters;
  private final NioDurableFile[] handles;
  private final long[] slotEpochs;
  private long generation = 1;
  private long nextSlotEpoch = 1;
  private boolean closed;

  private NioDurableDirectory(
      Path root,
      FatalState fatalState,
      NioIoCounters counters,
      int maxOpenHandles) {
    this.root = root;
    this.fatalState = fatalState;
    this.counters = counters;
    handles = new NioDurableFile[maxOpenHandles];
    slotEpochs = new long[maxOpenHandles];
  }

  /** Opens an existing directory without creating namespace state. */
  public static StatusCode openExisting(
      Path root,
      FatalState fatalState,
      NioIoCounters counters,
      int maxOpenHandles,
      NioDirectoryOpenResult result) {
    if (result == null) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    result.reset();
    if (root == null || fatalState == null || counters == null || maxOpenHandles <= 0) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    try {
      Path resolved = root.toRealPath();
      if (!Files.isDirectory(resolved, LinkOption.NOFOLLOW_LINKS)) {
        return StatusCode.CONFLICT;
      }
      result.setDirectory(new NioDurableDirectory(
          resolved,
          fatalState,
          counters,
          maxOpenHandles));
      return StatusCode.OK;
    } catch (IOException failure) {
      return NioStatusMapper.known(failure);
    }
  }

  public Path root() {
    return root;
  }

  public synchronized long generation() {
    return generation;
  }

  public NioIoCounters counters() {
    return counters;
  }

  /**
   * Closes all issued handles and advances the provider generation after restart/recovery.
   * This does not simulate or claim a physical crash.
   */
  public synchronized StatusCode advanceGeneration() {
    if (closed) {
      return StatusCode.CLOSED;
    }
    StatusCode status = closeAllHandles();
    generation++;
    if (generation == 0) {
      generation = 1;
    }
    return status;
  }

  /** Closes every open channel and rejects subsequent operations. */
  public synchronized StatusCode close() {
    if (closed) {
      return StatusCode.CLOSED;
    }
    StatusCode status = closeAllHandles();
    closed = true;
    generation++;
    return status;
  }

  @Override
  public synchronized StatusCode createDirectory(
      String childDirectoryName,
      DirectoryOperationResult result) {
    StatusCode admission = begin(childDirectoryName, result);
    if (!admission.isOk()) {
      return admission;
    }
    try {
      Files.createDirectory(root.resolve(childDirectoryName));
      result.set(null, DirectoryDurability.VISIBLE_NOT_DURABLE);
      return StatusCode.OK;
    } catch (FileAlreadyExistsException failure) {
      return StatusCode.CONFLICT;
    } catch (IOException failure) {
      return unknownMutation(result, failure);
    }
  }

  @Override
  public synchronized StatusCode createFile(String fileName, DirectoryOperationResult result) {
    return createPhysicalFile(fileName, result);
  }

  @Override
  public synchronized StatusCode createTemporary(
      String temporaryFileName,
      DirectoryOperationResult result) {
    return createPhysicalFile(temporaryFileName, result);
  }

  @Override
  public synchronized StatusCode list(DirectoryListResult result) {
    if (result == null) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    result.reset();
    StatusCode admission = admission();
    if (!admission.isOk()) {
      return admission;
    }
    try (DirectoryStream<Path> entries = Files.newDirectoryStream(root)) {
      for (Path entry : entries) {
        BasicFileAttributes attributes = Files.readAttributes(
            entry,
            BasicFileAttributes.class,
            LinkOption.NOFOLLOW_LINKS);
        DirectoryEntryType type;
        if (attributes.isRegularFile()) {
          type = DirectoryEntryType.FILE;
        } else if (attributes.isDirectory()) {
          type = DirectoryEntryType.DIRECTORY;
        } else {
          fatalState.fence(StatusCode.CORRUPTION);
          return StatusCode.CORRUPTION;
        }
        StatusCode addStatus = result.add(entry.getFileName().toString(), type);
        if (!addStatus.isOk()) {
          return addStatus;
        }
      }
      result.finish(generation);
      return StatusCode.OK;
    } catch (DirectoryIteratorException failure) {
      return NioStatusMapper.known(failure.getCause());
    } catch (IOException failure) {
      return NioStatusMapper.known(failure);
    }
  }

  @Override
  public synchronized StatusCode rename(
      String sourceName,
      String destinationName,
      DirectoryOperationResult result) {
    StatusCode admission = beginPair(sourceName, destinationName, result);
    if (!admission.isOk()) {
      return admission;
    }
    Path source = root.resolve(sourceName);
    Path destination = root.resolve(destinationName);
    if (!Files.exists(source, LinkOption.NOFOLLOW_LINKS)
        || Files.exists(destination, LinkOption.NOFOLLOW_LINKS)) {
      return StatusCode.CONFLICT;
    }
    try {
      Files.move(source, destination, StandardCopyOption.ATOMIC_MOVE);
      result.set(null, DirectoryDurability.VISIBLE_NOT_DURABLE);
      return StatusCode.OK;
    } catch (FileAlreadyExistsException | NoSuchFileException failure) {
      return StatusCode.CONFLICT;
    } catch (AtomicMoveNotSupportedException failure) {
      return StatusCode.IO_FAILURE;
    } catch (IOException failure) {
      return unknownMutation(result, failure);
    }
  }

  @Override
  public synchronized StatusCode replace(
      String temporaryFileName,
      String destinationFileName,
      DirectoryOperationResult result) {
    StatusCode admission = beginPair(temporaryFileName, destinationFileName, result);
    if (!admission.isOk()) {
      return admission;
    }
    Path temporary = root.resolve(temporaryFileName);
    Path destination = root.resolve(destinationFileName);
    try {
      if (!regularFile(temporary)
          || (Files.exists(destination, LinkOption.NOFOLLOW_LINKS)
              && !regularFile(destination))) {
        return StatusCode.CONFLICT;
      }
      Files.move(
          temporary,
          destination,
          StandardCopyOption.ATOMIC_MOVE,
          StandardCopyOption.REPLACE_EXISTING);
      result.set(null, DirectoryDurability.VISIBLE_NOT_DURABLE);
      return StatusCode.OK;
    } catch (NoSuchFileException failure) {
      return StatusCode.CONFLICT;
    } catch (AtomicMoveNotSupportedException failure) {
      return StatusCode.IO_FAILURE;
    } catch (IOException failure) {
      return unknownMutation(result, failure);
    }
  }

  @Override
  public synchronized StatusCode remove(String entryName, DirectoryOperationResult result) {
    StatusCode admission = begin(entryName, result);
    if (!admission.isOk()) {
      return admission;
    }
    try {
      Files.delete(root.resolve(entryName));
      result.set(null, DirectoryDurability.VISIBLE_NOT_DURABLE);
      return StatusCode.OK;
    } catch (NoSuchFileException | DirectoryNotEmptyException failure) {
      return StatusCode.CONFLICT;
    } catch (IOException failure) {
      return unknownMutation(result, failure);
    }
  }

  @Override
  public synchronized StatusCode truncate(
      String fileName,
      long sizeBytes,
      DirectoryOperationResult result) {
    StatusCode admission = begin(fileName, result);
    if (!admission.isOk()) {
      return admission;
    }
    if (sizeBytes < 0) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    Path path = root.resolve(fileName);
    StatusCode typeStatus = regularFileStatus(path);
    if (!typeStatus.isOk()) {
      return typeStatus;
    }
    StatusCode openStatus = openHandle(
        path, REOPEN_OPTIONS, DirectoryDurability.NOT_APPLIED, result);
    if (!openStatus.isOk()) {
      return openStatus;
    }
    StatusCode truncateStatus = result.file().truncate(sizeBytes);
    if (!truncateStatus.isOk()) {
      result.file().close();
      result.set(null, DirectoryDurability.UNKNOWN);
      fatalState.fence(truncateStatus);
      return truncateStatus;
    }
    result.set(result.file(), DirectoryDurability.VISIBLE_NOT_DURABLE);
    return StatusCode.OK;
  }

  @Override
  public synchronized StatusCode force(DirectoryOperationResult result) {
    if (result == null) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    result.reset();
    StatusCode admission = admission();
    if (!admission.isOk()) {
      return admission;
    }
    try (FileChannel directory = FileChannel.open(root, StandardOpenOption.READ)) {
      directory.force(true);
      counters.recordForce();
      result.set(null, DirectoryDurability.DURABLE);
      return StatusCode.OK;
    } catch (IOException failure) {
      return unknownMutation(result, failure);
    }
  }

  @Override
  public synchronized StatusCode reopen(String fileName, DirectoryOperationResult result) {
    StatusCode admission = begin(fileName, result);
    if (!admission.isOk()) {
      return admission;
    }
    Path path = root.resolve(fileName);
    StatusCode typeStatus = regularFileStatus(path);
    if (!typeStatus.isOk()) {
      return typeStatus;
    }
    return openHandle(path, REOPEN_OPTIONS, DirectoryDurability.NOT_APPLIED, result);
  }

  synchronized StatusCode admit(
      NioDurableFile handle,
      long openedGeneration,
      int slot,
      long slotEpoch,
      boolean handleClosed) {
    StatusCode admission = admission();
    if (!admission.isOk()) {
      return admission;
    }
    if (openedGeneration != generation
        || slot < 0
        || slot >= handles.length
        || handles[slot] != handle
        || slotEpochs[slot] != slotEpoch) {
      return StatusCode.CANCELLED;
    }
    if (handleClosed) {
      return StatusCode.CLOSED;
    }
    return StatusCode.OK;
  }

  synchronized StatusCode closeHandle(
      NioDurableFile handle,
      FileChannel channel,
      long openedGeneration,
      int slot,
      long slotEpoch) {
    boolean current = openedGeneration == generation
        && slot >= 0
        && slot < handles.length
        && handles[slot] == handle
        && slotEpochs[slot] == slotEpoch;
    if (current) {
      handles[slot] = null;
    }
    try {
      channel.close();
      return current ? StatusCode.OK : StatusCode.CANCELLED;
    } catch (IOException failure) {
      return NioStatusMapper.known(failure);
    }
  }

  private StatusCode createPhysicalFile(String fileName, DirectoryOperationResult result) {
    StatusCode admission = begin(fileName, result);
    if (!admission.isOk()) {
      return admission;
    }
    return openHandle(
        root.resolve(fileName),
        CREATE_OPTIONS,
        DirectoryDurability.VISIBLE_NOT_DURABLE,
        result);
  }

  private StatusCode openHandle(
      Path path,
      OpenOption[] options,
      DirectoryDurability durability,
      DirectoryOperationResult result) {
    int slot = availableSlot();
    if (slot < 0) {
      return StatusCode.RESOURCE_EXHAUSTED;
    }
    try {
      FileChannel channel = FileChannel.open(path, options);
      long epoch = nextSlotEpoch++;
      if (epoch == 0) {
        epoch = nextSlotEpoch++;
      }
      NioDurableFile handle = new NioDurableFile(this, channel, generation, slot, epoch);
      handles[slot] = handle;
      slotEpochs[slot] = epoch;
      counters.recordHandleOpened();
      result.set(handle, durability);
      return StatusCode.OK;
    } catch (FileAlreadyExistsException | NoSuchFileException failure) {
      return StatusCode.CONFLICT;
    } catch (IOException failure) {
      if (options == CREATE_OPTIONS) {
        return unknownMutation(result, failure);
      }
      return NioStatusMapper.known(failure);
    }
  }

  private StatusCode begin(String name, DirectoryOperationResult result) {
    if (result == null) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    result.reset();
    StatusCode admission = admission();
    if (!admission.isOk()) {
      return admission;
    }
    return validChildName(name) ? StatusCode.OK : StatusCode.INVALID_EXTERNAL_INPUT;
  }

  private StatusCode beginPair(
      String source,
      String destination,
      DirectoryOperationResult result) {
    StatusCode status = begin(source, result);
    if (!status.isOk()) {
      return status;
    }
    if (!validChildName(destination) || source.equals(destination)) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    return StatusCode.OK;
  }

  private StatusCode admission() {
    if (closed) {
      return StatusCode.CLOSED;
    }
    return fatalState.admissionStatus();
  }

  private StatusCode unknownMutation(
      DirectoryOperationResult result,
      IOException failure) {
    StatusCode status = NioStatusMapper.known(failure);
    result.set(null, DirectoryDurability.UNKNOWN);
    fatalState.fence(status);
    return status;
  }

  private StatusCode closeAllHandles() {
    StatusCode status = StatusCode.OK;
    for (int slot = 0; slot < handles.length; slot++) {
      NioDurableFile handle = handles[slot];
      if (handle != null) {
        StatusCode closeStatus = handle.closeForGenerationChange();
        if (!closeStatus.isOk() && status.isOk()) {
          status = closeStatus;
        }
        handles[slot] = null;
      }
    }
    return status;
  }

  private int availableSlot() {
    for (int slot = 0; slot < handles.length; slot++) {
      if (handles[slot] == null) {
        return slot;
      }
    }
    return -1;
  }

  private static boolean regularFile(Path path) throws IOException {
    return Files.readAttributes(
        path,
        BasicFileAttributes.class,
        LinkOption.NOFOLLOW_LINKS).isRegularFile();
  }

  private static StatusCode regularFileStatus(Path path) {
    try {
      return regularFile(path) ? StatusCode.OK : StatusCode.CONFLICT;
    } catch (NoSuchFileException failure) {
      return StatusCode.CONFLICT;
    } catch (IOException failure) {
      return NioStatusMapper.known(failure);
    }
  }

  private static boolean validChildName(String name) {
    if (name == null || name.isBlank() || name.length() > 128) {
      return false;
    }
    if (name.equals(".") || name.equals("..")) {
      return false;
    }
    for (int index = 0; index < name.length(); index++) {
      char value = name.charAt(index);
      if (value == '/' || value == '\\' || value == 0) {
        return false;
      }
    }
    return true;
  }
}
