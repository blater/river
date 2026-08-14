package io.riverdb.backup;

import io.riverdb.base.error.StatusCode;
import io.riverdb.platform.file.DirectoryOperationResult;
import io.riverdb.platform.file.DurableFile;
import io.riverdb.platform.file.FileSizeResult;
import io.riverdb.platform.file.ForceMode;
import io.riverdb.platform.file.IoResult;
import io.riverdb.platform.file.nio.NioDurableDirectory;
import java.nio.ByteBuffer;
import java.security.DigestException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/** Copies and verifies one backup payload while preserving close precedence. */
final class OfflineBackupFileCopier {
  private static final int COPY_BUFFER_BYTES = 64 * 1024;
  private static final int DIGEST_BYTES = 32;

  private final DirectoryOperationResult sourceOperation =
      new DirectoryOperationResult();
  private final DirectoryOperationResult targetOperation =
      new DirectoryOperationResult();
  private final FileSizeResult fileSize = new FileSizeResult();
  private final IoResult sourceIo = new IoResult();
  private final IoResult targetIo = new IoResult();
  private final ByteBuffer buffer = ByteBuffer.allocateDirect(COPY_BUFFER_BYTES);
  private final byte[] digestOutput = new byte[DIGEST_BYTES];
  private final MessageDigest digest;

  OfflineBackupFileCopier() {
    digest = sha256();
  }

  boolean isAvailable() {
    return digest != null;
  }

  StatusCode copy(
      NioDurableDirectory source,
      NioDurableDirectory target,
      OfflineBackupCatalog catalog,
      int index,
      boolean verifyExpected) {
    StatusCode status = source.reopen(catalog.fileName(index), sourceOperation);
    DurableFile input = status.isOk() ? sourceOperation.file() : null;
    if (status.isOk()) {
      status = input.size(fileSize);
    }
    long bytes = fileSize.sizeBytes();
    if (status.isOk()) {
      status = catalog.validateFileSize(index, bytes, verifyExpected);
    }
    if (status.isOk()) {
      status = target.createFile(catalog.fileName(index), targetOperation);
    }
    DurableFile output = status.isOk() ? targetOperation.file() : null;
    if (status.isOk()) {
      status = copyContents(input, output, bytes);
    }
    if (status.isOk()) {
      status = finishDigest();
    }
    if (status.isOk()) {
      status = catalog.acceptDigest(index, bytes, digestOutput, verifyExpected);
    }
    if (status.isOk()) {
      status = output.force(ForceMode.CONTENT_AND_METADATA);
    }
    return close(input, output, status);
  }

  private StatusCode copyContents(
      DurableFile input, DurableFile output, long bytes) {
    digest.reset();
    long position = 0;
    while (position < bytes) {
      int count = (int) Math.min(buffer.capacity(), bytes - position);
      StatusCode status = copyChunk(input, output, position, count);
      if (!status.isOk()) {
        return status;
      }
      position += count;
    }
    return StatusCode.OK;
  }

  private StatusCode copyChunk(
      DurableFile input, DurableFile output, long position, int bytes) {
    buffer.clear();
    buffer.limit(bytes);
    StatusCode status = OfflineBackupIo.readExact(
        input, buffer, position, sourceIo);
    if (!status.isOk()) {
      return status;
    }
    buffer.flip();
    digest.update(buffer);
    buffer.position(0);
    return OfflineBackupIo.writeExact(output, buffer, position, targetIo);
  }

  private StatusCode finishDigest() {
    try {
      int bytes = digest.digest(digestOutput, 0, DIGEST_BYTES);
      return bytes == DIGEST_BYTES
          ? StatusCode.OK : StatusCode.INVARIANT_BROKEN;
    } catch (DigestException failure) {
      return StatusCode.INVARIANT_BROKEN;
    }
  }

  private static StatusCode close(
      DurableFile input, DurableFile output, StatusCode status) {
    StatusCode inputClose = input == null ? StatusCode.OK : input.close();
    StatusCode outputClose = output == null ? StatusCode.OK : output.close();
    if (status.isOk()) {
      status = inputClose;
    }
    return status.isOk() ? outputClose : status;
  }

  private static MessageDigest sha256() {
    try {
      return MessageDigest.getInstance("SHA-256");
    } catch (NoSuchAlgorithmException unavailable) {
      return null;
    }
  }
}
