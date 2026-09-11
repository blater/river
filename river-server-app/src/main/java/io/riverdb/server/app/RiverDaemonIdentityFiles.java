package io.riverdb.server.app;

import io.riverdb.base.error.StatusCode;
import io.riverdb.base.id.DatabaseIncarnation;
import io.riverdb.platform.file.*;
import io.riverdb.platform.riverd.*;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.List;

final class RiverDaemonIdentityFiles {
  private static final int MAX_RECORD_BYTES = RiverDaemonRecordEnvelope.MAX_RECORD_BYTES;
  private RiverDaemonIdentityFiles() {}
  static StatusCode readRecord(RiverFile file, byte[] target, String name) {
    FileSizeResult size = new FileSizeResult();
    StatusCode sizeStatus = file.size(size);
    if (!sizeStatus.isOk()) return sizeStatus;
    if (size.sizeBytes() <= 0 || size.sizeBytes() > target.length) return StatusCode.CORRUPTION;
    IoResult io = new IoResult();
    ByteBuffer bytes = ByteBuffer.wrap(target);
    bytes.limit((int) size.sizeBytes());
    int position = 0;
    while (position < size.sizeBytes()) {
      bytes.position(position);
      StatusCode status = file.read(position, bytes, io);
      if (!status.isOk()) return status;
      int count = io.bytesTransferred();
      if (count == 0) break;
      position += count;
    }
    if (position == 0 || position != size.sizeBytes()) return StatusCode.CORRUPTION;
    for (int index = 0; index < position; index++) {
      if (target[index] == 0) return StatusCode.CORRUPTION;
    }
    Arrays.fill(target, position, target.length, (byte) 0);
    return StatusCode.OK;
  }

  static StatusCode writeLock(
      RiverFile file,
      Path datadir,
      DatabaseIncarnation incarnation,
      long pid,
      long start,
      String nonce) {
    return writeLockCanonical(file, RiverDaemonIdentityNamespace.canonicalPath(datadir), incarnation,
        pid, start, nonce);
  }

  static StatusCode writeLockCanonical(
      RiverFile file,
      String datadir,
      DatabaseIncarnation incarnation,
      long pid,
      long start,
      String nonce) {
    String body = RiverDaemonRecordEnvelope.record(List.of(
      "format=" + RiverDaemonIdentityRecords.LOCK_FORMAT,
      "datadir=" + datadir,
        "database-incarnation-high=" + incarnation.high(),
        "database-incarnation-low=" + incarnation.low(),
        "pid=" + pid,
        "process-start-epoch-millis=" + start,
        "owner-nonce=" + nonce));
    return write(file, body.getBytes(StandardCharsets.UTF_8));
  }

  static StatusCode writeBootstrap(
      RiverDirectory directory,
      DatabaseIncarnation incarnation,
      long pid,
      long start,
      String nonce) {
    String stageName = ".bootstrap-" + nonce + ".stage";
    String body = RiverDaemonRecordEnvelope.record(List.of(
        "format=" + RiverDaemonIdentityRecords.BOOTSTRAP_FORMAT,
        "database-incarnation-high=" + incarnation.high(),
        "database-incarnation-low=" + incarnation.low(),
        "pid=" + pid,
        "process-start-epoch-millis=" + start,
        "attempt-nonce=" + nonce,
        "database-name=" + RiverDaemonIdentity.DATABASE_NAME,
        "security-name=" + RiverDaemonIdentity.SECURITY_NAME,
        "staging-name=.riverd-bootstrap-" + nonce,
        "instance-stage-name=.instance-" + nonce + ".stage"));
    RiverFileResult stageResult = new RiverFileResult();
    StatusCode status = directory.openFile(stageName, RiverOpenMode.CREATE_NEW, stageResult);
    if (!status.isOk()) return status;
    RiverFile stage = stageResult.file();
    status = write(stage, body.getBytes(StandardCharsets.UTF_8));
    if (status.isOk()) {
      DirectoryOperationResult publication = new DirectoryOperationResult();
      status = directory.publishExclusive(stage, stageName, "bootstrap.properties", publication);
      if (status.isOk()) {
        DirectoryOperationResult forced = new DirectoryOperationResult();
        status = directory.force(forced);
      }
    } else {
      stage.close();
    }
    StatusCode closeStatus = stage.close();
    if (status.isOk() && !closeStatus.isOk() && closeStatus != StatusCode.CLOSED) {
      status = closeStatus;
    }
    return status;
  }

  static StatusCode write(RiverFile file, byte[] bytes) {
    try {
      ByteBuffer source = ByteBuffer.wrap(bytes);
      IoResult io = new IoResult();
      long position = 0;
      while (source.hasRemaining()) {
        StatusCode status = file.write(position, source, io);
        if (!status.isOk()) return status;
        if (io.bytesTransferred() <= 0) return StatusCode.IO_FAILURE;
        position += io.bytesTransferred();
      }
      return file.force(ForceMode.CONTENT_AND_METADATA);
    } finally {
      Arrays.fill(bytes, (byte) 0);
    }
  }

  static StatusCode forceDirectory(RiverDirectory directory) {
    DirectoryOperationResult forced = new DirectoryOperationResult();
    return directory.force(forced);
  }

  static StatusCode openOrCreateDirectory(
      Path datadir, RiverDaemonFileSystem filesystem, RiverDirectoryResult result) {
    Path parentPath = datadir.getParent();
    if (parentPath == null) return StatusCode.INVALID_EXTERNAL_INPUT;
    RiverDirectoryResult parentResult = new RiverDirectoryResult();
    StatusCode status = filesystem.openAncestor(parentPath, parentResult);
    if (!status.isOk()) return status;
    RiverDirectory parent = parentResult.directory();
    try {
      status = parent.createDirectory(datadir.getFileName().toString(), result);
      if (status == StatusCode.CONFLICT) {
        status = filesystem.openDirectory(datadir, result);
      }
      return status;
    } finally {
      closeQuiet(parent);
    }
  }

  static void closeQuiet(RiverDirectory directory) {
    if (directory != null) directory.close();
  }

  static void closeDirectory(RiverDirectory directory, StatusCode status) {
    if (directory != null) directory.close();
  }
  static StatusCode removeOwned(
      RiverDirectory directory, String name, io.riverdb.platform.riverd.FileIdentity knownIdentity) {
    io.riverdb.platform.riverd.FileIdentity identity = knownIdentity;
    if (identity == null) {
      RiverFileResult fileResult = new RiverFileResult();
      StatusCode status = directory.openFile(name, RiverOpenMode.EXISTING, fileResult);
      if (!status.isOk()) return status;
      RiverFile file = fileResult.file();
      identity = file.identity();
      status = file.close();
      if (!status.isOk() && status != StatusCode.CLOSED) return status;
    }
    DirectoryOperationResult removed = new DirectoryOperationResult();
    return directory.removeOwned(name, identity, removed);
  }

  static StatusCode publishDirectory(
      RiverDirectory directory,
      RiverDirectory staging,
      RiverDirectory child,
      String name) {
    DirectoryOperationResult publication = new DirectoryOperationResult();
    StatusCode status = directory.publishDirectoryExclusive(
        staging, child, name, name, publication);
    if (!status.isOk()) return status;
    DirectoryOperationResult forced = new DirectoryOperationResult();
    return directory.force(forced);
  }
}
