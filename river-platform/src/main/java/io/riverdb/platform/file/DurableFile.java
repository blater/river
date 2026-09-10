package io.riverdb.platform.file;

import io.riverdb.base.error.StatusCode;
import java.nio.ByteBuffer;

/** Positional durable-file SPI. Buffers and result slots remain caller-owned. */
public interface DurableFile {
  StatusCode read(long position, ByteBuffer target, IoResult result);

  StatusCode write(long position, ByteBuffer source, IoResult result);

  StatusCode force(ForceMode mode);

  /**
   * Synchronizes the nonnegative, non-empty half-open physical range
   * {@code [startOffset, endOffset)}. Providers synchronize all existing bytes in that range;
   * bytes outside it may also be synchronized. The force mode's metadata guarantee remains in
   * effect for the operation.
   */
  StatusCode force(long startOffset, long endOffset, ForceMode mode);

  StatusCode truncate(long sizeBytes);

  StatusCode size(FileSizeResult result);

  StatusCode close();
}
