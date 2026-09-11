package io.riverdb.server.app;

import io.riverdb.base.error.StatusCode;
import io.riverdb.platform.riverd.FileIdentity;
import io.riverdb.platform.riverd.RiverDirectory;
import io.riverdb.platform.riverd.RiverFile;
import io.riverdb.platform.riverd.RiverFileResult;
import io.riverdb.platform.riverd.RiverOpenMode;
import java.util.Arrays;

final class RiverDaemonStopRecordReader {
  private static final Result EMPTY = new Result(StatusCode.OK, null);

  private RiverDaemonStopRecordReader() {
  }

  static Result read(RiverDirectory directory, String name, RiverFileResult result,
      StatusCode missingStatus, boolean tolerateIncompleteStage) {
    result.reset();
    StatusCode status = directory.openFile(name, RiverOpenMode.EXISTING, result);
    if (status == StatusCode.CONFLICT) return missingStatus.isOk() ? EMPTY
        : new Result(missingStatus, null);
    if (!status.isOk()) return new Result(status, null);
    RiverFile file = result.file();
    FileIdentity identity = file.identity();
    RiverDaemonRuntimeModel.ReadResult read = RiverDaemonRuntimeStorage.read(file);
    StatusCode closeStatus = file.close();
    if (!read.status.isOk()) {
      return tolerateIncompleteStage ? EMPTY
          : new Result(read.status, null);
    }
    if (!closeStatus.isOk() && closeStatus != StatusCode.CLOSED) {
      wipe(read.bytes);
      return new Result(closeStatus, null);
    }
    RiverDaemonStopRequest.Record record = RiverDaemonStopRequest.parse(read.bytes);
    wipe(read.bytes);
    return record == null
        ? (tolerateIncompleteStage ? EMPTY
            : new Result(StatusCode.CORRUPTION, null))
        : new Result(StatusCode.OK, new RiverDaemonStopDirectory.ControlEntry(
            name, identity, record));
  }

  private static void wipe(byte[] bytes) {
    if (bytes != null) Arrays.fill(bytes, (byte) 0);
  }

  record Result(StatusCode status, RiverDaemonStopDirectory.ControlEntry entry) {
  }
}
