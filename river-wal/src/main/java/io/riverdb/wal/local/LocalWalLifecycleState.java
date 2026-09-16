package io.riverdb.wal.local;

import io.riverdb.base.error.StatusCode;
import io.riverdb.format.wal.WalFileHeader;
import io.riverdb.format.wal.WalFileHeaderCodec;
import io.riverdb.platform.file.DirectoryOperationResult;
import io.riverdb.platform.file.DurableDirectory;
import java.nio.ByteBuffer;

/** Owns file initialization and provider shutdown ordering. */
final class LocalWalLifecycleState {
  private final LocalWal owner;
  private final LocalWalAppendState append;
  private final LocalWalForceState force;

  LocalWalLifecycleState(LocalWal owner, LocalWalAppendState append, LocalWalForceState force) {
    this.owner = owner;
    this.append = append;
    this.force = force;
  }

  StatusCode initialize(DurableDirectory directory, LocalWalForceCause cause) {
    ByteBuffer header = ByteBuffer.allocate(WalFileHeaderCodec.HEADER_BYTES);
    StatusCode status = WalFileHeaderCodec.encode(
        new WalFileHeader(owner.databaseIncarnation(), owner.walGeneration()), header);
    if (!status.isOk()) return status;
    header.flip();
    status = append.file().write(0, header, append.ioResult());
    if (status.isOk() && append.ioResult().bytesTransferred() != WalFileHeaderCodec.HEADER_BYTES) {
      status = StatusCode.IO_FAILURE;
    }
    if (status.isOk()) status = force.forceFile(cause, WalFileHeaderCodec.HEADER_BYTES);
    if (status.isOk()) status = directory.force(new DirectoryOperationResult());
    return status;
  }

  StatusCode close() {
    if (owner.closed) return StatusCode.CLOSED;
    if (!owner.failed && (owner.hasPendingRecords()
        || owner.hasRetainedForceTarget() || owner.hasOpenLogicalStream())) {
      return StatusCode.CONFLICT;
    }
    if (force.hasWorker()) {
      StatusCode status = force.closeWorker();
      if (!status.isOk() && status != StatusCode.CLOSED) return status;
    } else if (force.inProgress()) {
      return StatusCode.CONFLICT;
    }
    owner.closed = true;
    if (force.hasTarget()) force.releaseTarget();
    return append.file().close();
  }
}
