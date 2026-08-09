package io.riverdb.testkit.io;

import io.riverdb.base.error.StatusCode;
import io.riverdb.platform.fault.FaultAction;
import io.riverdb.platform.fault.FaultBoundary;
import io.riverdb.platform.file.DirectoryEntryType;
import io.riverdb.platform.file.DirectoryListResult;
import io.riverdb.platform.file.DirectoryOperationResult;
import io.riverdb.platform.file.DurableDirectory;
import io.riverdb.platform.file.DurableFile;
import io.riverdb.platform.file.FileSizeResult;
import io.riverdb.platform.file.ForceMode;
import io.riverdb.platform.file.IoResult;
import java.nio.ByteBuffer;

/**
 * Reusable semantic suite for deterministic fakes and future NIO/native directory adapters.
 *
 * <p>Passing this suite proves bounded carrier and state-machine behavior only. Physical crash and
 * power-loss claims still require K01 qualification on the declared storage stack.
 */
public final class DurableDirectoryContractSuite {
  public static final int SCENARIO_SUCCESS = 1;
  public static final int SCENARIO_NAMESPACE_DURABILITY = 2;
  public static final int SCENARIO_TRUNCATION_DURABILITY = 3;
  public static final int SCENARIO_CRASH_BOUNDARIES = 4;
  public static final int SCENARIO_IO_FAILURES = 5;
  public static final int SCENARIO_LIST_BOUNDS = 6;
  public static final int SCENARIO_GENERATIONS = 7;
  public static final int SCENARIO_TRACE_BOUNDS = 8;

  private final DirectoryOperationResult operationResult = new DirectoryOperationResult();
  private final DirectoryListResult listResult = new DirectoryListResult(16);
  private final FileSizeResult sizeResult = new FileSizeResult();
  private final IoResult ioResult = new IoResult();
  private final ByteBuffer scratch = ByteBuffer.allocate(512);
  private int completed;

  public synchronized StatusCode run(
      DurableDirectoryContractProviderFactory factory,
      DurableDirectorySuiteResult result) {
    result.reset();
    completed = 0;
    StatusCode status = success(factory);
    if (!status.isOk()) {
      return fail(result, status, SCENARIO_SUCCESS);
    }
    completed++;
    status = namespaceDurability(factory);
    if (!status.isOk()) {
      return fail(result, status, SCENARIO_NAMESPACE_DURABILITY);
    }
    completed++;
    status = truncationDurability(factory);
    if (!status.isOk()) {
      return fail(result, status, SCENARIO_TRUNCATION_DURABILITY);
    }
    completed++;
    status = crashBoundaries(factory);
    if (!status.isOk()) {
      return fail(result, status, SCENARIO_CRASH_BOUNDARIES);
    }
    completed++;
    status = ioFailures(factory);
    if (!status.isOk()) {
      return fail(result, status, SCENARIO_IO_FAILURES);
    }
    completed++;
    status = listBounds(factory);
    if (!status.isOk()) {
      return fail(result, status, SCENARIO_LIST_BOUNDS);
    }
    completed++;
    status = generations(factory);
    if (!status.isOk()) {
      return fail(result, status, SCENARIO_GENERATIONS);
    }
    completed++;
    status = traceBounds(factory);
    if (!status.isOk()) {
      return fail(result, status, SCENARIO_TRACE_BOUNDS);
    }
    completed++;
    result.set(StatusCode.OK, 0, completed);
    return StatusCode.OK;
  }

  private StatusCode success(DurableDirectoryContractProviderFactory factory) {
    DurableDirectoryContractProvider provider = factory.create(0, 32);
    DurableDirectory directory = provider.directory();
    StatusCode status = directory.createDirectory("wal", operationResult);
    if (!status.isOk()) {
      return status;
    }
    status = createDurableFile(directory, "control", new byte[] {1, 2, 3, 4});
    if (!status.isOk()) {
      return status;
    }
    if (!provider.crash().isOk() || !provider.restart().isOk()) {
      return StatusCode.INVARIANT_BROKEN;
    }
    listResult.reset();
    status = directory.list(listResult);
    if (!status.isOk()
        || !listResult.complete()
        || listResult.size() != 2
        || !hasEntry(listResult, "wal", DirectoryEntryType.DIRECTORY)
        || !hasEntry(listResult, "control", DirectoryEntryType.FILE)) {
      return StatusCode.INVARIANT_BROKEN;
    }
    return fileEquals(directory, "control", new byte[] {1, 2, 3, 4});
  }

  private StatusCode namespaceDurability(DurableDirectoryContractProviderFactory factory) {
    DurableDirectoryContractProvider provider = factory.create(0, 64);
    DurableDirectory directory = provider.directory();
    StatusCode status = createDurableFile(directory, "old", new byte[] {7});
    if (!status.isOk()) {
      return status;
    }
    status = directory.rename("old", "new", operationResult);
    if (!status.isOk() || !provider.crash().isOk() || !provider.restart().isOk()) {
      return StatusCode.INVARIANT_BROKEN;
    }
    if (!exists(directory, "old") || exists(directory, "new")) {
      return StatusCode.INVARIANT_BROKEN;
    }
    status = directory.rename("old", "new", operationResult);
    if (!status.isOk() || !directory.force(operationResult).isOk()) {
      return StatusCode.INVARIANT_BROKEN;
    }
    provider.crash();
    provider.restart();
    if (exists(directory, "old") || !exists(directory, "new")) {
      return StatusCode.INVARIANT_BROKEN;
    }
    status = directory.remove("new", operationResult);
    if (!status.isOk()) {
      return status;
    }
    provider.crash();
    provider.restart();
    if (!exists(directory, "new")) {
      return StatusCode.INVARIANT_BROKEN;
    }
    status = directory.remove("new", operationResult);
    if (!status.isOk() || !directory.force(operationResult).isOk()) {
      return StatusCode.INVARIANT_BROKEN;
    }
    provider.crash();
    provider.restart();
    return exists(directory, "new") ? StatusCode.INVARIANT_BROKEN : StatusCode.OK;
  }

  private StatusCode truncationDurability(DurableDirectoryContractProviderFactory factory) {
    DurableDirectoryContractProvider provider = factory.create(0, 64);
    DurableDirectory directory = provider.directory();
    StatusCode status = createDurableFile(directory, "data", new byte[] {1, 2, 3, 4});
    if (!status.isOk()) {
      return status;
    }
    status = directory.truncate("data", 2, operationResult);
    if (!status.isOk()) {
      return status;
    }
    operationResult.file().close();
    directory.force(operationResult);
    provider.crash();
    provider.restart();
    if (fileSize(directory, "data") != 4) {
      return StatusCode.INVARIANT_BROKEN;
    }
    status = directory.truncate("data", 2, operationResult);
    if (!status.isOk()) {
      return status;
    }
    DurableFile truncated = operationResult.file();
    if (!truncated.force(ForceMode.CONTENT_AND_METADATA).isOk() || !truncated.close().isOk()) {
      return StatusCode.INVARIANT_BROKEN;
    }
    provider.crash();
    provider.restart();
    return fileSize(directory, "data") == 2 ? StatusCode.OK : StatusCode.INVARIANT_BROKEN;
  }

  private StatusCode crashBoundaries(DurableDirectoryContractProviderFactory factory) {
    for (FaultBoundary boundary : FaultBoundary.values()) {
      DurableDirectoryContractProvider create = factory.create(1, 16);
      StatusCode status = create.script(
          DirectoryOperation.CREATE_FILE,
          boundary,
          FaultAction.CRASH,
          0);
      if (!status.isOk()) {
        return status;
      }
      if (create.directory().createFile("new", operationResult) != StatusCode.IO_FAILURE
          || operationResult.durability() != io.riverdb.platform.file.DirectoryDurability.UNKNOWN
          || !create.restart().isOk()
          || exists(create.directory(), "new")) {
        return StatusCode.INVARIANT_BROKEN;
      }

      DurableDirectoryContractProvider force = factory.create(1, 16);
      status = force.directory().createFile("new", operationResult);
      if (!status.isOk()) {
        return status;
      }
      operationResult.file().close();
      status = force.script(
          DirectoryOperation.DIRECTORY_FORCE,
          boundary,
          FaultAction.CRASH,
          0);
      if (!status.isOk()
          || force.directory().force(operationResult) != StatusCode.IO_FAILURE
          || !force.restart().isOk()) {
        return StatusCode.INVARIANT_BROKEN;
      }
      boolean survives = exists(force.directory(), "new");
      if (survives != (boundary == FaultBoundary.AFTER)) {
        return StatusCode.INVARIANT_BROKEN;
      }
    }
    return StatusCode.OK;
  }

  private StatusCode ioFailures(DurableDirectoryContractProviderFactory factory) {
    DurableDirectoryContractProvider shortWrite = factory.create(1, 24);
    StatusCode status = shortWrite.script(
        DirectoryOperation.FILE_WRITE,
        FaultBoundary.BEFORE,
        FaultAction.SHORT_WRITE,
        2);
    if (!status.isOk()
        || !shortWrite.directory().createFile("data", operationResult).isOk()) {
      return StatusCode.INVARIANT_BROKEN;
    }
    ioResult.reset();
    status = operationResult.file().write(0, ByteBuffer.wrap(new byte[] {1, 2, 3, 4}), ioResult);
    if (!status.isOk() || ioResult.bytesTransferred() != 2) {
      return StatusCode.INVARIANT_BROKEN;
    }

    DurableDirectoryContractProvider diskFull = factory.create(1, 16);
    status = diskFull.script(
        DirectoryOperation.CREATE_FILE,
        FaultBoundary.BEFORE,
        FaultAction.DISK_FULL,
        0);
    if (!status.isOk()
        || diskFull.directory().createFile("data", operationResult)
            != StatusCode.RESOURCE_EXHAUSTED
        || exists(diskFull.directory(), "data")) {
      return StatusCode.INVARIANT_BROKEN;
    }

    DurableDirectoryContractProvider forceFailure = factory.create(1, 16);
    status = forceFailure.directory().createFile("data", operationResult);
    if (!status.isOk()) {
      return status;
    }
    status = forceFailure.script(
        DirectoryOperation.FILE_FORCE,
        FaultBoundary.BEFORE,
        FaultAction.FORCE_FAILURE,
        0);
    if (!status.isOk()
        || operationResult.file().force(ForceMode.CONTENT_AND_METADATA) != StatusCode.IO_FAILURE) {
      return StatusCode.INVARIANT_BROKEN;
    }
    return forceFailure.script(
               DirectoryOperation.LIST,
               FaultBoundary.BEFORE,
               FaultAction.SHORT_WRITE,
               1)
            == StatusCode.INVALID_EXTERNAL_INPUT
        ? StatusCode.OK
        : StatusCode.INVARIANT_BROKEN;
  }

  private StatusCode listBounds(DurableDirectoryContractProviderFactory factory) {
    DurableDirectoryContractProvider provider = factory.create(0, 16);
    DurableDirectory directory = provider.directory();
    if (!directory.createDirectory("a", operationResult).isOk()
        || !directory.createDirectory("b", operationResult).isOk()) {
      return StatusCode.INVARIANT_BROKEN;
    }
    DirectoryListResult bounded = new DirectoryListResult(1);
    StatusCode status = directory.list(bounded);
    return status == StatusCode.RESOURCE_EXHAUSTED
            && bounded.size() == 1
            && !bounded.complete()
        ? StatusCode.OK
        : StatusCode.INVARIANT_BROKEN;
  }

  private StatusCode generations(DurableDirectoryContractProviderFactory factory) {
    DurableDirectoryContractProvider provider = factory.create(0, 24);
    DurableDirectory directory = provider.directory();
    if (!directory.createFile("data", operationResult).isOk()) {
      return StatusCode.INVARIANT_BROKEN;
    }
    DurableFile stale = operationResult.file();
    if (!directory.force(operationResult).isOk()) {
      return StatusCode.INVARIANT_BROKEN;
    }
    listResult.reset();
    if (!directory.list(listResult).isOk()) {
      return StatusCode.INVARIANT_BROKEN;
    }
    long listedGeneration = listResult.providerGeneration();
    provider.crash();
    provider.restart();
    scratch.clear();
    ioResult.reset();
    return stale.read(0, scratch, ioResult) == StatusCode.CANCELLED
            && listedGeneration != provider.generation()
        ? StatusCode.OK
        : StatusCode.INVARIANT_BROKEN;
  }

  private StatusCode traceBounds(DurableDirectoryContractProviderFactory factory) {
    DurableDirectoryContractProvider provider = factory.create(0, 1);
    DurableDirectory directory = provider.directory();
    if (!directory.createDirectory("a", operationResult).isOk()
        || !directory.createDirectory("b", operationResult).isOk()) {
      return StatusCode.INVARIANT_BROKEN;
    }
    return provider.traceSize() == 1
            && provider.traceStatus() == StatusCode.RESOURCE_EXHAUSTED
        ? StatusCode.OK
        : StatusCode.INVARIANT_BROKEN;
  }

  private StatusCode createDurableFile(
      DurableDirectory directory,
      String name,
      byte[] bytes) {
    StatusCode status = directory.createFile(name, operationResult);
    if (!status.isOk()) {
      return status;
    }
    DurableFile file = operationResult.file();
    ioResult.reset();
    status = file.write(0, ByteBuffer.wrap(bytes), ioResult);
    if (!status.isOk() || ioResult.bytesTransferred() != bytes.length) {
      file.close();
      return StatusCode.INVARIANT_BROKEN;
    }
    status = file.force(ForceMode.CONTENT_AND_METADATA);
    if (!status.isOk()) {
      file.close();
      return status;
    }
    status = file.close();
    if (!status.isOk()) {
      return status;
    }
    return directory.force(operationResult);
  }

  private boolean exists(DurableDirectory directory, String name) {
    listResult.reset();
    if (!directory.list(listResult).isOk()) {
      return false;
    }
    for (int index = 0; index < listResult.size(); index++) {
      if (name.equals(listResult.name(index))) {
        return true;
      }
    }
    return false;
  }

  private long fileSize(DurableDirectory directory, String name) {
    if (!directory.reopen(name, operationResult).isOk()) {
      return -1;
    }
    DurableFile file = operationResult.file();
    sizeResult.setSizeBytes(0);
    StatusCode status = file.size(sizeResult);
    file.close();
    return status.isOk() ? sizeResult.sizeBytes() : -1;
  }

  private StatusCode fileEquals(DurableDirectory directory, String name, byte[] expected) {
    if (!directory.reopen(name, operationResult).isOk()) {
      return StatusCode.INVARIANT_BROKEN;
    }
    DurableFile file = operationResult.file();
    scratch.clear();
    scratch.limit(expected.length);
    ioResult.reset();
    StatusCode status = file.read(0, scratch, ioResult);
    file.close();
    if (!status.isOk() || ioResult.bytesTransferred() != expected.length) {
      return StatusCode.INVARIANT_BROKEN;
    }
    scratch.flip();
    for (byte value : expected) {
      if (scratch.get() != value) {
        return StatusCode.INVARIANT_BROKEN;
      }
    }
    return StatusCode.OK;
  }

  private static boolean hasEntry(
      DirectoryListResult result,
      String name,
      DirectoryEntryType type) {
    for (int index = 0; index < result.size(); index++) {
      if (name.equals(result.name(index)) && result.type(index) == type) {
        return true;
      }
    }
    return false;
  }

  private StatusCode fail(
      DurableDirectorySuiteResult result,
      StatusCode status,
      int scenario) {
    result.set(status, scenario, completed);
    return status;
  }
}
