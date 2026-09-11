package io.riverdb.server.app;

import io.riverdb.base.error.StatusCode;
import io.riverdb.platform.file.DirectoryEntryType;
import io.riverdb.platform.file.DirectoryListResult;
import io.riverdb.platform.riverd.FileIdentity;
import io.riverdb.platform.riverd.RiverDirectory;
import io.riverdb.platform.riverd.RiverFileResult;

final class RiverDaemonStopDirectory {
  private static final int UNRELATED = 0;
  private static final int REQUEST = 1;
  private static final int STAGE = 2;
  private static final int ACCEPTED = 3;
  private static final int INVALID = 4;

  private RiverDaemonStopDirectory() {
  }

  static RiverDaemonStopRecordReader.Result probeRequest(RiverDirectory directory,
      RiverFileResult result) {
    return RiverDaemonStopRecordReader.read(directory, RiverDaemonStopRequest.REQUEST_NAME,
        result, StatusCode.OK, false);
  }

  static Scan scan(RiverDirectory directory, boolean includeStages) {
    if (directory == null) return Scan.failure(StatusCode.INVALID_EXTERNAL_INPUT);
    int capacity = 16;
    DirectoryListResult listing;
    StatusCode status;
    while (true) {
      listing = new DirectoryListResult(capacity);
      status = directory.list(listing);
      if (status != StatusCode.RESOURCE_EXHAUSTED) break;
      if (capacity > Integer.MAX_VALUE / 2) {
        return Scan.failure(StatusCode.RESOURCE_EXHAUSTED);
      }
      capacity *= 2;
    }
    // A directory entry may disappear between enumeration and its metadata read.
    if (status == StatusCode.CONFLICT) return Scan.failure(StatusCode.RETRY);
    if (!status.isOk()) return Scan.failure(status);
    Scan scan = new Scan(listing.size());
    for (int index = 0; index < listing.size(); index++) {
      StatusCode entryStatus = collect(directory, listing, index, includeStages, scan);
      if (!entryStatus.isOk()) return Scan.failure(entryStatus);
    }
    return scan;
  }

  private static StatusCode collect(RiverDirectory directory, DirectoryListResult listing,
      int index, boolean includeStages, Scan scan) {
    String name = listing.name(index);
    int kind = classify(name, includeStages);
    if (kind == UNRELATED) return StatusCode.OK;
    if (kind == INVALID || listing.type(index) != DirectoryEntryType.FILE) {
      return StatusCode.CORRUPTION;
    }
    String expectedNonce = kind == REQUEST ? null : controlNonce(name,
        kind == STAGE ? RiverDaemonStopRequest.STAGE_PREFIX : RiverDaemonStopRequest.ACCEPTED_PREFIX,
        kind == STAGE ? RiverDaemonStopRequest.STAGE_SUFFIX : "");
    if (kind != REQUEST && (expectedNonce == null || !expectedNonce.matches("[0-9a-f]{32}"))) {
      return StatusCode.CORRUPTION;
    }
    return readEntry(directory, name, includeStages, kind, expectedNonce, scan);
  }

  private static int classify(String name, boolean includeStages) {
    if (RiverDaemonStopRequest.REQUEST_NAME.equals(name)) return REQUEST;
    boolean stagePrefix = name.startsWith(RiverDaemonStopRequest.STAGE_PREFIX);
    if (stagePrefix) {
      if (!includeStages) return UNRELATED;
      return name.endsWith(RiverDaemonStopRequest.STAGE_SUFFIX) ? STAGE : INVALID;
    }
    if (name.startsWith(RiverDaemonStopRequest.ACCEPTED_PREFIX)) return ACCEPTED;
    return UNRELATED;
  }

  private static StatusCode readEntry(RiverDirectory directory, String name,
      boolean includeStages, int kind, String expectedNonce, Scan scan) {
    RiverDaemonStopRecordReader.Result read = RiverDaemonStopRecordReader.read(
        directory, name, new RiverFileResult(), StatusCode.RETRY, kind == STAGE && includeStages);
    if (!read.status().isOk()) return read.status();
    if (read.entry() == null) return StatusCode.OK;
    ControlEntry entry = read.entry();
    if (kind != REQUEST && !entry.record.requestNonce.equals(expectedNonce)) {
      return kind == STAGE && includeStages ? StatusCode.OK : StatusCode.CORRUPTION;
    }
    return scan.add(entry, kind);
  }

  private static String controlNonce(String name, String prefix, String suffix) {
    int end = suffix.isEmpty() ? name.length() : name.length() - suffix.length();
    if (!name.startsWith(prefix) || end <= prefix.length()) return null;
    return name.substring(prefix.length(), end);
  }

  static final class ControlEntry {
    final String name;
    final FileIdentity identity;
    final RiverDaemonStopRequest.Record record;

    ControlEntry(String name, FileIdentity identity, RiverDaemonStopRequest.Record record) {
      this.name = name;
      this.identity = identity;
      this.record = record;
    }
  }

  static final class Scan {
    final ControlEntry[] entries;
    int entryCount;
    ControlEntry request;
    ControlEntry accepted;
    int acceptedCount;
    StatusCode status = StatusCode.OK;

    Scan(int capacity) {
      entries = new ControlEntry[Math.max(1, capacity)];
    }

    static Scan failure(StatusCode status) {
      Scan scan = new Scan(1);
      scan.status = status;
      return scan;
    }

    StatusCode add(ControlEntry entry, int kind) {
      entries[entryCount++] = entry;
      if (kind == REQUEST) {
        if (request != null) return StatusCode.CORRUPTION;
        request = entry;
      } else if (kind == ACCEPTED) {
        acceptedCount++;
        if (accepted == null) accepted = entry;
      }
      return StatusCode.OK;
    }
  }
}
