package io.riverdb.platform.file;

import io.riverdb.base.error.StatusCode;

/** Provider boundary for named durable files. Production NIO belongs to K01, not this SPI. */
public interface FileIoProvider {
  /**
   * Opens or creates a validated provider-relative file name. On success ownership of the handle
   * is written to {@code result}; callers must close it.
   */
  StatusCode open(String fileName, OpenFileResult result);
}
