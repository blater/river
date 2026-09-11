package io.riverdb.platform.file.nio;

import io.riverdb.base.concurrent.FatalState;
import io.riverdb.base.error.StatusCode;
import io.riverdb.platform.file.DirectoryEntryType;
import io.riverdb.platform.file.DirectoryListResult;
import java.io.IOException;
import java.nio.file.DirectoryIteratorException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;

/** Scans one admitted NIO directory and publishes its entries. */
final class NioDirectoryListing {
  private NioDirectoryListing() {
  }

  static StatusCode list(
      Path root,
      long generation,
      FatalState fatalState,
      DirectoryListResult result) {
    try (DirectoryStream<Path> entries = Files.newDirectoryStream(root)) {
      for (Path entry : entries) {
        BasicFileAttributes attributes = Files.readAttributes(
            entry,
            BasicFileAttributes.class,
            LinkOption.NOFOLLOW_LINKS);
        DirectoryEntryType type;
        if (attributes.isRegularFile()) {
          type = DirectoryEntryType.FILE;
        } else if (attributes.isDirectory()) {
          type = DirectoryEntryType.DIRECTORY;
        } else {
          fatalState.fence(StatusCode.CORRUPTION);
          return StatusCode.CORRUPTION;
        }
        StatusCode addStatus = result.add(entry.getFileName().toString(), type);
        if (!addStatus.isOk()) {
          return addStatus;
        }
      }
      result.finish(generation);
      return StatusCode.OK;
    } catch (DirectoryIteratorException failure) {
      return NioStatusMapper.known(failure.getCause());
    } catch (IOException failure) {
      return NioStatusMapper.known(failure);
    }
  }
}
