package io.riverdb.platform.file.nio;

import io.riverdb.base.error.StatusCode;
import java.io.IOException;
import java.nio.channels.ClosedChannelException;
import java.nio.file.AccessDeniedException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.FileSystemException;
import java.nio.file.NoSuchFileException;
import java.nio.file.NotDirectoryException;

final class NioStatusMapper {
  private NioStatusMapper() {
  }

  static StatusCode known(IOException failure) {
    if (failure instanceof ClosedChannelException) {
      return StatusCode.CLOSED;
    }
    if (failure instanceof FileAlreadyExistsException
        || failure instanceof NoSuchFileException
        || failure instanceof NotDirectoryException) {
      return StatusCode.CONFLICT;
    }
    if (failure instanceof AccessDeniedException) {
      return StatusCode.IO_FAILURE;
    }
    if (failure instanceof FileSystemException fileSystemFailure) {
      String reason = fileSystemFailure.getReason();
      if (reason != null
          && (reason.contains("No space left") || reason.contains("quota"))) {
        return StatusCode.RESOURCE_EXHAUSTED;
      }
    }
    return StatusCode.IO_FAILURE;
  }
}
