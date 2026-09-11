package io.riverdb.server.app;

import io.riverdb.base.error.StatusCode;
import io.riverdb.platform.file.DirectoryEntryType;
import io.riverdb.platform.file.DirectoryListResult;
import io.riverdb.platform.riverd.RiverDaemonFileSystem;
import io.riverdb.platform.riverd.RiverDirectory;
import io.riverdb.platform.riverd.RiverDirectoryResult;
import java.io.PrintStream;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** Resolves and lists daemon targets through their verified local records. */
final class RiverDaemonTargets {
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
    RiverDaemonPathSelection.Result paths = new RiverDaemonPathSelection.Result();
    StatusCode status = RiverDaemonPathSelection.resolve(explicitDatadir, null, home, paths);
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
    RiverDaemonPathSelection.Result paths = new RiverDaemonPathSelection.Result();
    StatusCode status = RiverDaemonPathSelection.resolve(null, null, home, paths);
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
      RiverDaemonTargetAdmission.EntryList listing = RiverDaemonTargetAdmission.listEntries(runtimeRoot);
      if (!listing.status.isOk()) return listing.status;
      DirectoryListResult entries = listing.entries;
      List<Row> rows = new ArrayList<>();
      for (int index = 0; index < entries.size(); index++) {
        if (entries.type(index) != DirectoryEntryType.FILE
            || entries.name(index).startsWith(".")) continue;
        RiverDaemonTargetAdmission.RuntimeValues values =
            RiverDaemonTargetAdmission.readRuntime(runtimeRoot, entries.name(index));
        if (!values.status.isOk()) {
          warn(errors, entries.name(index), values.status);
          continue;
        }
        RiverDaemonTarget.Result targetResult = new RiverDaemonTarget.Result();
        StatusCode targetStatus = RiverDaemonTargetAdmission.openRunning(
            filesystem, Path.of(values.record.datadir), paths.runtimeRoot, targetResult);
        if (targetStatus.isOk()) {
          RiverDaemonTarget target = targetResult.target();
          if (target == null) continue;
          if (target.runtime == null) {
            target.close();
            continue;
          }
          targetStatus = RiverDaemonTargetAdmission.verifyRuntime(
              values.record, target);
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
    StatusCode status = RiverDaemonTargetAdmission.openRunning(
        filesystem, datadir, runtimeRootPath, result);
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
      RiverDaemonTargetAdmission.EntryList listing = RiverDaemonTargetAdmission.listEntries(runtimeRoot);
      if (!listing.status.isOk()) return listing.status;
      DirectoryListResult entries = listing.entries;
      for (int index = 0; index < entries.size(); index++) {
        if (entries.type(index) != DirectoryEntryType.FILE
            || entries.name(index).startsWith(".")) continue;
        RiverDaemonTargetAdmission.RuntimeValues values =
            RiverDaemonTargetAdmission.readRuntime(runtimeRoot, entries.name(index));
        if (!values.status.isOk()) {
          warn(errors, entries.name(index), values.status);
          continue;
        }
        if (!requested.matches(values.record.address, values.record.port)) continue;
        RiverDaemonTarget.Result candidateResult = new RiverDaemonTarget.Result();
        status = RiverDaemonTargetAdmission.openRunning(
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
        status = RiverDaemonTargetAdmission.verifyRuntime(
            values.record, candidate);
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

}
