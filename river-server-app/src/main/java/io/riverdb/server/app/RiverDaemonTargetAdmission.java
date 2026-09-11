package io.riverdb.server.app;

import io.riverdb.base.error.StatusCode;
import io.riverdb.platform.file.DirectoryListResult;
import io.riverdb.platform.riverd.RiverDaemonFileSystem;
import io.riverdb.platform.riverd.RiverDirectory;
import io.riverdb.platform.riverd.RiverFile;
import io.riverdb.platform.riverd.RiverFileResult;
import io.riverdb.platform.riverd.RiverOpenMode;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;

/** Shares runtime-record admission and owner-verified target acquisition. */
final class RiverDaemonTargetAdmission {
  private static final int INITIAL_LIST_CAPACITY = 16;

  private RiverDaemonTargetAdmission() { }

  static EntryList listEntries(RiverDirectory directory) {
    int capacity = INITIAL_LIST_CAPACITY;
    while (true) {
      DirectoryListResult result = new DirectoryListResult(capacity);
      StatusCode status = directory.list(result);
      if (status != StatusCode.RESOURCE_EXHAUSTED) {
        return status.isOk() ? new EntryList(status, result) : new EntryList(status, null);
      }
      if (capacity > Integer.MAX_VALUE / 2) {
        return new EntryList(StatusCode.RESOURCE_EXHAUSTED, null);
      }
      capacity *= 2;
    }
  }

  static RuntimeValues readRuntime(RiverDirectory runtimeRoot, String name) {
    RiverFileResult result = new RiverFileResult();
    StatusCode status = runtimeRoot.openFile(name, RiverOpenMode.EXISTING, result);
    if (!status.isOk()) return RuntimeValues.failure(status);
    RiverFile file = result.file();
    RiverDaemonRuntimeModel.ReadResult read = RiverDaemonRuntimeStorage.read(file);
    StatusCode closeStatus = file.close();
    if (!read.status.isOk()) return RuntimeValues.failure(read.status);
    if (!closeStatus.isOk() && closeStatus != StatusCode.CLOSED) {
      return RuntimeValues.failure(closeStatus);
    }
    RiverDaemonRuntimeModel.RuntimeRecord record =
        RiverDaemonRuntimeCodec.parseRuntime(read.bytes);
    if (record == null || !validRuntime(record)
        || !RiverDaemonRuntimeStorage.runtimeName(record.datadir).equals(name)) {
      return RuntimeValues.failure(StatusCode.CORRUPTION);
    }
    return new RuntimeValues(StatusCode.OK, record);
  }

  private static boolean validRuntime(RiverDaemonRuntimeModel.RuntimeRecord record) {
    return RiverDaemonIdentityRecords.validDatadir(record.datadir)
        && RiverDaemonRuntimeStorage.validAddress(record.address)
        && record.port >= 1 && record.port <= 65535
        && record.nonce != null && record.nonce.matches("[0-9a-f]{32}");
  }

  /** OK with no target means an absent directory or a released server lock. */
  static StatusCode openRunning(
      RiverDaemonFileSystem filesystem, Path datadir, Path runtimeRootPath,
      RiverDaemonTarget.Result result) {
    StatusCode status = RiverDaemonTarget.open(filesystem, datadir, runtimeRootPath, result);
    if (!status.isOk()) {
      return Files.notExists(datadir, LinkOption.NOFOLLOW_LINKS) ? StatusCode.OK : status;
    }
    RiverDaemonTarget target = result.target();
    status = target.lockHeld();
    if (status == StatusCode.OK) return status;
    StatusCode close = target.close();
    result.reset();
    if (!close.isOk() && close != StatusCode.CLOSED) return close;
    return status == StatusCode.NOT_OWNER ? StatusCode.OK : status;
  }

  static StatusCode verifyRuntime(
      RiverDaemonRuntimeModel.RuntimeRecord record, RiverDaemonTarget target) {
    return target.runtime != null
        && record.checksum.equals(target.runtime.checksum)
        ? StatusCode.OK : StatusCode.NOT_OWNER;
  }

  static final class EntryList {
    final StatusCode status;
    final DirectoryListResult entries;

    EntryList(StatusCode status, DirectoryListResult entries) {
      this.status = status;
      this.entries = entries;
    }
  }

  static final class RuntimeValues {
    final StatusCode status;
    final RiverDaemonRuntimeModel.RuntimeRecord record;

    RuntimeValues(StatusCode status, RiverDaemonRuntimeModel.RuntimeRecord record) {
      this.status = status;
      this.record = record;
    }

    static RuntimeValues failure(StatusCode status) {
      return new RuntimeValues(status, null);
    }
  }
}
