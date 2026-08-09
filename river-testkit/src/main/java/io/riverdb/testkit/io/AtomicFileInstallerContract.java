package io.riverdb.testkit.io;

import io.riverdb.base.error.StatusCode;
import io.riverdb.platform.file.AtomicFileInstaller;
import io.riverdb.platform.file.AtomicInstallProgress;
import io.riverdb.platform.file.AtomicInstallRequest;
import io.riverdb.platform.file.AtomicInstallResult;
import io.riverdb.platform.file.AtomicInstallSnapshot;
import io.riverdb.platform.file.DirectoryDurability;
import io.riverdb.platform.file.DirectoryOperationResult;
import io.riverdb.platform.file.DurableDirectory;
import io.riverdb.platform.file.DurableFile;
import io.riverdb.platform.file.FileSizeResult;
import io.riverdb.platform.file.IoResult;
import java.nio.ByteBuffer;

/** Reusable bounded driver for fake, NIO, mapped, or native installer contract suites. */
public final class AtomicFileInstallerContract {
  private final AtomicInstallSnapshot progressSnapshot = new AtomicInstallSnapshot();

  public synchronized StatusCode drive(
      AtomicFileInstaller installer,
      AtomicInstallRequest request,
      AtomicInstallProgress progress,
      AtomicInstallResult stepResult,
      int maxAdvances,
      AtomicInstallDriveResult result) {
    result.reset();
    if (maxAdvances < 1) {
      result.set(StatusCode.INVALID_EXTERNAL_INPUT, 0);
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    for (int advance = 1; advance <= maxAdvances; advance++) {
      StatusCode status = installer.advance(request, progress, stepResult);
      StatusCode inspectStatus = installer.inspect(progress, progressSnapshot);
      if (!inspectStatus.isOk()) {
        result.set(inspectStatus, advance);
        return inspectStatus;
      }
      if (progressSnapshot.isComplete()) {
        result.set(status, advance);
        return status;
      }
      if (!status.isOk() && status != StatusCode.RETRY) {
        result.set(status, advance);
        return status;
      }
    }
    result.set(StatusCode.TIMEOUT, maxAdvances);
    return StatusCode.TIMEOUT;
  }

  /** Reopens through the directory SPI and compares exact bytes without allocating scratch state. */
  public StatusCode verifyInstalled(
      DurableDirectory directory,
      String destinationFileName,
      ByteBuffer expected,
      ByteBuffer scratch,
      DirectoryOperationResult openResult,
      FileSizeResult sizeResult,
      IoResult ioResult) {
    openResult.reset();
    ioResult.reset();
    int expectedLength = expected.remaining();
    if (scratch.capacity() < expectedLength) {
      return StatusCode.RESOURCE_EXHAUSTED;
    }
    StatusCode status = directory.reopen(destinationFileName, openResult);
    if (status == StatusCode.CONFLICT) {
      return StatusCode.CORRUPTION;
    }
    if (!status.isOk()) {
      return status;
    }
    DurableFile file = openResult.file();
    if (file == null || openResult.durability() != DirectoryDurability.DURABLE) {
      if (file != null) {
        file.close();
      }
      return StatusCode.INVARIANT_BROKEN;
    }
    status = file.size(sizeResult);
    if (!status.isOk()) {
      file.close();
      return status;
    }
    if (sizeResult.sizeBytes() != expectedLength) {
      file.close();
      return StatusCode.CORRUPTION;
    }
    scratch.clear();
    scratch.limit(expectedLength);
    int transferred = 0;
    while (transferred < expectedLength) {
      status = file.read(transferred, scratch, ioResult);
      if (!status.isOk()) {
        file.close();
        return status;
      }
      int bytes = ioResult.bytesTransferred();
      if (bytes == 0) {
        file.close();
        return StatusCode.CORRUPTION;
      }
      transferred += bytes;
      ioResult.reset();
    }
    status = file.close();
    if (!status.isOk()) {
      return status;
    }
    int expectedPosition = expected.position();
    for (int index = 0; index < expectedLength; index++) {
      if (scratch.get(index) != expected.get(expectedPosition + index)) {
        return StatusCode.CORRUPTION;
      }
    }
    return StatusCode.OK;
  }
}
