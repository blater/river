package io.riverdb.server.app;

import io.riverdb.base.error.StatusCode;
import io.riverdb.base.id.DatabaseIncarnation;
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
      return openExact(filesystem, paths.datadir, requested, result);
    }
    return resolveEndpoint(filesystem, paths.registry, requested, result, errors);
  }

  static StatusCode list(
      RiverDaemonFileSystem filesystem, Path home, PrintStream out, PrintStream errors) {
    if (filesystem == null || home == null || out == null || errors == null) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    RiverDaemonPaths.Result paths = new RiverDaemonPaths.Result();
    StatusCode status = RiverDaemonPaths.resolve(null, null, home, paths);
    if (!status.isOk()) return status;
    RiverDirectoryResult registryResult = new RiverDirectoryResult();
    status = filesystem.openDirectory(paths.registry, registryResult);
    if (status == StatusCode.CONFLICT) {
      printHeader(out, serverColumnWidth(List.of()));
      out.println("No River servers are registered.");
      out.println("Start one with: river server start");
      return StatusCode.OK;
    }
    if (!status.isOk()) return status;
    RiverDirectory registry = registryResult.directory();
    try {
      EntryList listing = listEntries(registry);
      if (!listing.status.isOk()) return listing.status;
      DirectoryListResult entries = listing.entries;
      List<Row> rows = new ArrayList<>();
      for (int index = 0; index < entries.size(); index++) {
        if (entries.type(index) != DirectoryEntryType.FILE
            || entries.name(index).startsWith(".")) continue;
        RegistryValues values = readRegistry(registry, entries.name(index));
        if (!values.status.isOk() || values.record == null) {
          warn(errors, entries.name(index), values.status.isOk()
              ? StatusCode.CORRUPTION : values.status);
          continue;
        }
        if (!validRegistry(values.record)) {
          warn(errors, entries.name(index), StatusCode.CORRUPTION);
          continue;
        }
        RiverDaemonTarget.Result targetResult = new RiverDaemonTarget.Result();
        StatusCode targetStatus = RiverDaemonTarget.open(
            filesystem, Path.of(values.record.datadir), targetResult);
        if (targetStatus.isOk()) {
          RiverDaemonTarget target = targetResult.target();
          targetStatus = verifyRegistry(values.record, entries.name(index), target);
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
      if (rows.isEmpty()) out.println("Start one with: river server start");
      return StatusCode.OK;
    } finally {
      registry.close();
    }
  }

  private static StatusCode openExact(
      RiverDaemonFileSystem filesystem,
      Path datadir,
      RiverDaemonEndpoint requested,
      RiverDaemonTarget.Result result) {
    StatusCode status = RiverDaemonTarget.open(filesystem, datadir, result);
    if (status == StatusCode.CONFLICT) return StatusCode.NOT_OWNER;
    if (!status.isOk()) return status;
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
      Path registryPath,
      RiverDaemonEndpoint requested,
      RiverDaemonTarget.Result result,
      PrintStream errors) {
    RiverDirectoryResult registryResult = new RiverDirectoryResult();
    StatusCode status = filesystem.openDirectory(registryPath, registryResult);
    if (status == StatusCode.CONFLICT) return StatusCode.NOT_OWNER;
    if (!status.isOk()) return status;
    RiverDirectory registry = registryResult.directory();
    RiverDaemonTarget selected = null;
    try {
      EntryList listing = listEntries(registry);
      if (!listing.status.isOk()) return listing.status;
      DirectoryListResult entries = listing.entries;
      for (int index = 0; index < entries.size(); index++) {
        if (entries.type(index) != DirectoryEntryType.FILE
            || entries.name(index).startsWith(".")) continue;
        RegistryValues values = readRegistry(registry, entries.name(index));
        if (!values.status.isOk() || values.record == null) {
          warn(errors, entries.name(index), values.status.isOk()
              ? StatusCode.CORRUPTION : values.status);
          continue;
        }
        if (!validRegistry(values.record)) {
          warn(errors, entries.name(index), StatusCode.CORRUPTION);
          continue;
        }
        if (!requested.matches(values.record.address, values.record.port)) continue;
        RiverDaemonTarget.Result candidateResult = new RiverDaemonTarget.Result();
        status = RiverDaemonTarget.open(
            filesystem, Path.of(values.record.datadir), candidateResult);
        if (!status.isOk()) {
          warn(errors, entries.name(index), status);
          continue;
        }
        RiverDaemonTarget candidate = candidateResult.target();
        status = verifyRegistry(values.record, entries.name(index), candidate);
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
      if (selected == null) return StatusCode.NOT_OWNER;
      result.set(selected);
      return StatusCode.OK;
    } finally {
      registry.close();
      if (selected == null) result.reset();
    }
  }

  private static StatusCode verifyRegistry(
      RiverDaemonRuntimeRecords.RegistryRecord record,
      String registryName,
      RiverDaemonTarget target) {
    if (target == null || target.runtime == null || target.owner == null
        || !RiverDaemonRuntimeRecords.registryName(record.datadir).equals(registryName)
        || !record.runtimeFile.equals(
            Path.of(record.datadir).resolve(RiverDaemonRuntimeRecords.RUNTIME_NAME).toString())
        || !record.matches(target.datadir.toString(),
            DatabaseIncarnation.of(target.owner.high, target.owner.low), target.owner,
            Path.of(record.datadir).resolve(RiverDaemonRuntimeRecords.RUNTIME_NAME).toString())
        || !record.address.equals(target.runtime.address) || record.port != target.runtime.port) {
      return StatusCode.CORRUPTION;
    }
    return StatusCode.OK;
  }

  private static boolean validRegistry(RiverDaemonRuntimeRecords.RegistryRecord record) {
    return RiverDaemonIdentityRecords.validDatadir(record.datadir)
        && RiverDaemonRuntimeRecords.validAddress(record.address)
        && record.port >= 1 && record.port <= 65535
        && RiverDaemonIdentityRecords.validCommand(record.command)
        && record.version != null && !record.version.isBlank()
        && record.launcher.equals("riverd-v1")
        && record.protocol.equals("river-v" + io.riverdb.protocol.ProtocolFrameCodec.VERSION)
        && record.runtimeFile != null && record.nonce.matches("[0-9a-f]{32}");
  }

  private static RegistryValues readRegistry(RiverDirectory registry, String name) {
    RiverFileResult result = new RiverFileResult();
    StatusCode status = registry.openFile(name, RiverOpenMode.EXISTING, result);
    if (!status.isOk()) return RegistryValues.failure(status);
    RiverFile file = result.file();
    RiverDaemonRuntimeRecords.ReadResult read = RiverDaemonRuntimeRecords.read(file);
    StatusCode closeStatus = file.close();
    if (!read.status.isOk()) return RegistryValues.failure(read.status);
    if (!closeStatus.isOk() && closeStatus != StatusCode.CLOSED) {
      return RegistryValues.failure(closeStatus);
    }
    RiverDaemonRuntimeRecords.RegistryRecord record =
        RiverDaemonRuntimeRecords.parseRegistry(read.bytes);
    return record == null
        ? RegistryValues.failure(StatusCode.CORRUPTION)
        : new RegistryValues(StatusCode.OK, record);
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
    errors.println("warning: ignoring registry record " + name + " (" + status + ")");
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

  private static final class RegistryValues {
    final StatusCode status;
    final RiverDaemonRuntimeRecords.RegistryRecord record;

    RegistryValues(StatusCode status, RiverDaemonRuntimeRecords.RegistryRecord record) {
      this.status = status;
      this.record = record;
    }

    static RegistryValues failure(StatusCode status) {
      return new RegistryValues(status, null);
    }
  }
}
