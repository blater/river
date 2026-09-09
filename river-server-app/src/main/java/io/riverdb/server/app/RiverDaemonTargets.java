package io.riverdb.server.app;

import io.riverdb.base.error.StatusCode;
import io.riverdb.platform.file.DirectoryEntryType;
import io.riverdb.platform.file.DirectoryListResult;
import io.riverdb.platform.riverd.RiverDaemonFileSystem;
import io.riverdb.platform.riverd.RiverDirectory;
import io.riverdb.platform.riverd.RiverDirectoryResult;
import io.riverdb.platform.riverd.RiverFile;
import io.riverdb.platform.riverd.RiverFileResult;
import io.riverdb.platform.riverd.RiverOpenMode;
import java.io.PrintStream;
import java.nio.file.Path;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** Resolves and lists daemon targets through their verified local records. */
final class RiverDaemonTargets {
  private static final int INITIAL_LIST_CAPACITY = 16;

  private RiverDaemonTargets() { }

  static StatusCode resolve(
      RiverDaemonFileSystem filesystem,
      Path home,
      Path explicitDatadir,
      String endpoint,
      RiverDaemonTarget.Result result,
      PrintStream errors) {
    if (filesystem == null || home == null || result == null || errors == null) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    result.reset();
    RiverDaemonEndpoint requested = endpoint == null ? null : RiverDaemonEndpoint.parse(endpoint);
    if (endpoint != null && requested == null) return StatusCode.INVALID_EXTERNAL_INPUT;
    RiverDaemonPaths.Result paths = new RiverDaemonPaths.Result();
    StatusCode status = RiverDaemonPaths.resolve(explicitDatadir, null, home, paths);
    if (!status.isOk()) return status;
    if (explicitDatadir != null || requested == null) {
      return openExact(filesystem, paths.datadir, paths.runtimeRoot, requested, result);
    }
    return resolveEndpoint(filesystem, paths.runtimeRoot, requested, result, errors);
  }

  static StatusCode list(
      RiverDaemonFileSystem filesystem, Path home, PrintStream out, PrintStream errors) {
    if (filesystem == null || home == null || out == null || errors == null) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    RiverDaemonPaths.Result paths = new RiverDaemonPaths.Result();
    StatusCode status = RiverDaemonPaths.resolve(null, null, home, paths);
    if (!status.isOk()) return status;
    RiverDirectoryResult runtimeRootResult = new RiverDirectoryResult();
    status = filesystem.openDirectory(paths.runtimeRoot, runtimeRootResult);
    if (status == StatusCode.CONFLICT) {
      printHeader(out, serverColumnWidth(List.of()));
      out.println("No River servers are running.");
      out.println("Start one with: river server start");
      return StatusCode.OK;
    }
    if (!status.isOk()) return status;
    RiverDirectory runtimeRoot = runtimeRootResult.directory();
    try {
      EntryList listing = listEntries(runtimeRoot);
      if (!listing.status.isOk()) return listing.status;
      DirectoryListResult entries = listing.entries;
      List<Row> rows = new ArrayList<>();
      for (int index = 0; index < entries.size(); index++) {
        if (entries.type(index) != DirectoryEntryType.FILE
            || entries.name(index).startsWith(".")) continue;
        RuntimeValues values = readRuntime(runtimeRoot, entries.name(index));
        if (!values.status.isOk() || values.record == null) {
          warn(errors, entries.name(index), values.status.isOk()
              ? StatusCode.CORRUPTION : values.status);
          continue;
        }
        if (!validRuntime(values.record)
            || !RiverDaemonRuntimeRecords.runtimeName(values.record.datadir).equals(entries.name(index))) {
          warn(errors, entries.name(index), StatusCode.CORRUPTION);
          continue;
        }
        RiverDaemonTarget.Result targetResult = new RiverDaemonTarget.Result();
        StatusCode targetStatus = openRunning(
            filesystem, Path.of(values.record.datadir), paths.runtimeRoot, targetResult);
        if (targetStatus.isOk()) {
          RiverDaemonTarget target = targetResult.target();
          if (target == null) continue;
          if (target.runtime == null) {
            target.close();
            continue;
          }
          targetStatus = verifyRuntime(values.record, entries.name(index), target);
          if (targetStatus.isOk()) targetStatus = target.revalidate(true);
          if (targetStatus.isOk()) {
            rows.add(new Row(RiverDaemonEndpoint.of(target.runtime.address, target.runtime.port),
                paths.datadir.equals(target.datadir), target.datadir.toString()));
          }
          target.close();
        }
        if (!targetStatus.isOk()) warn(errors, entries.name(index), targetStatus);
      }
      rows.sort(Comparator.comparing(row -> row.datadir));
      int serverWidth = serverColumnWidth(rows);
      printHeader(out, serverWidth);
      for (Row row : rows) {
        out.printf("%-" + serverWidth + "s %-7s %s%n", row.endpoint,
            row.defaultTarget ? "yes" : "no", row.datadir);
      }
      if (rows.isEmpty()) {
        out.println("No River servers are running.");
        out.println("Start one with: river server start");
      }
      return StatusCode.OK;
    } finally {
      runtimeRoot.close();
    }
  }

  private static StatusCode openExact(
      RiverDaemonFileSystem filesystem,
      Path datadir,
      Path runtimeRootPath,
      RiverDaemonEndpoint requested,
      RiverDaemonTarget.Result result) {
    StatusCode status = openRunning(filesystem, datadir, runtimeRootPath, result);
    if (!status.isOk() || result.target() == null) return status;
    RiverDaemonTarget target = result.target();
    // A local caller can still join an accepted stop after runtime cleanup.
    status = target.revalidate(requested != null);
    if (status.isOk() && requested != null
        && !requested.matches(target.runtime.address, target.runtime.port)) {
      status = StatusCode.NOT_OWNER;
    }
    if (!status.isOk()) {
      target.close();
      result.reset();
    }
    return status;
  }

  private static StatusCode resolveEndpoint(
      RiverDaemonFileSystem filesystem,
      Path runtimeRootPath,
      RiverDaemonEndpoint requested,
      RiverDaemonTarget.Result result,
      PrintStream errors) {
    RiverDirectoryResult runtimeRootResult = new RiverDirectoryResult();
    StatusCode status = filesystem.openDirectory(runtimeRootPath, runtimeRootResult);
    if (status == StatusCode.CONFLICT) return StatusCode.OK;
    if (!status.isOk()) return status;
    RiverDirectory runtimeRoot = runtimeRootResult.directory();
    RiverDaemonTarget selected = null;
    try {
      EntryList listing = listEntries(runtimeRoot);
      if (!listing.status.isOk()) return listing.status;
      DirectoryListResult entries = listing.entries;
      for (int index = 0; index < entries.size(); index++) {
        if (entries.type(index) != DirectoryEntryType.FILE
            || entries.name(index).startsWith(".")) continue;
        RuntimeValues values = readRuntime(runtimeRoot, entries.name(index));
        if (!values.status.isOk() || values.record == null) {
          warn(errors, entries.name(index), values.status.isOk()
              ? StatusCode.CORRUPTION : values.status);
          continue;
        }
        if (!validRuntime(values.record)
            || !RiverDaemonRuntimeRecords.runtimeName(values.record.datadir).equals(entries.name(index))) {
          warn(errors, entries.name(index), StatusCode.CORRUPTION);
          continue;
        }
        if (!requested.matches(values.record.address, values.record.port)) continue;
        RiverDaemonTarget.Result candidateResult = new RiverDaemonTarget.Result();
        status = openRunning(
            filesystem, Path.of(values.record.datadir), runtimeRootPath, candidateResult);
        if (!status.isOk()) {
          warn(errors, entries.name(index), status);
          continue;
        }
        RiverDaemonTarget candidate = candidateResult.target();
        if (candidate == null) continue;
        if (candidate.runtime == null) {
          candidate.close();
          continue;
        }
        status = verifyRuntime(values.record, entries.name(index), candidate);
        if (status.isOk()) status = candidate.revalidate(true);
        if (!status.isOk()) {
          warn(errors, entries.name(index), status);
          candidate.close();
          continue;
        }
        if (selected != null) {
          selected.close();
          candidate.close();
          return StatusCode.CONFLICT;
        }
        selected = candidate;
      }
      if (selected == null) return StatusCode.OK;
      result.set(selected);
      return StatusCode.OK;
    } finally {
      runtimeRoot.close();
      if (selected == null) result.reset();
    }
  }

  /** OK with no target means an absent directory or a released server lock. */
  private static StatusCode openRunning(
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

  private static StatusCode verifyRuntime(
      RiverDaemonRuntimeRecords.RuntimeRecord record,
      String name,
      RiverDaemonTarget target) {
    return target.runtime != null
        && RiverDaemonRuntimeRecords.runtimeName(record.datadir).equals(name)
        && record.checksum.equals(target.runtime.checksum)
        ? StatusCode.OK : StatusCode.NOT_OWNER;
  }

  private static boolean validRuntime(RiverDaemonRuntimeRecords.RuntimeRecord record) {
    return RiverDaemonIdentityRecords.validDatadir(record.datadir)
        && RiverDaemonRuntimeRecords.validAddress(record.address)
        && record.port >= 1 && record.port <= 65535
        && record.nonce != null && record.nonce.matches("[0-9a-f]{32}");
  }

  private static RuntimeValues readRuntime(RiverDirectory runtimeRoot, String name) {
    RiverFileResult result = new RiverFileResult();
    StatusCode status = runtimeRoot.openFile(name, RiverOpenMode.EXISTING, result);
    if (!status.isOk()) return RuntimeValues.failure(status);
    RiverFile file = result.file();
    RiverDaemonRuntimeRecords.ReadResult read = RiverDaemonRuntimeRecords.read(file);
    StatusCode closeStatus = file.close();
    if (!read.status.isOk()) return RuntimeValues.failure(read.status);
    if (!closeStatus.isOk() && closeStatus != StatusCode.CLOSED) {
      return RuntimeValues.failure(closeStatus);
    }
    RiverDaemonRuntimeRecords.RuntimeRecord record =
        RiverDaemonRuntimeRecords.parseRuntime(read.bytes);
    return record == null
        ? RuntimeValues.failure(StatusCode.CORRUPTION)
        : new RuntimeValues(StatusCode.OK, record);
  }

  private static EntryList listEntries(RiverDirectory directory) {
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

  private static void warn(PrintStream errors, String name, StatusCode status) {
    errors.println("warning: ignoring runtime record " + name + " (" + status + ")");
  }

  private static int serverColumnWidth(List<Row> rows) {
    int width = Math.max("SERVER".length(), "[::1]:65535".length());
    for (Row row : rows) width = Math.max(width, row.endpoint.toString().length());
    return width;
  }

  private static void printHeader(PrintStream out, int serverWidth) {
    out.printf("%-" + serverWidth + "s %-7s %s%n", "SERVER", "DEFAULT", "DATA DIRECTORY");
  }

  private record Row(RiverDaemonEndpoint endpoint, boolean defaultTarget, String datadir) { }

  private static final class EntryList {
    final StatusCode status;
    final DirectoryListResult entries;

    EntryList(StatusCode status, DirectoryListResult entries) {
      this.status = status;
      this.entries = entries;
    }
  }

  private static final class RuntimeValues {
    final StatusCode status;
    final RiverDaemonRuntimeRecords.RuntimeRecord record;

    RuntimeValues(StatusCode status, RiverDaemonRuntimeRecords.RuntimeRecord record) {
      this.status = status;
      this.record = record;
    }

    static RuntimeValues failure(StatusCode status) {
      return new RuntimeValues(status, null);
    }
  }
}
