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
   * effect for the operation. For mapped files, the call captures the covered range before
   * physical force. A positional write starting at or after {@code endOffset} may proceed against
   * the same active mapping while force is blocked; that later write remains dirty for a later
   * force and is not certified durable by this call even when the operating system writes a wider
   * range. Mapping retirement, truncate, and close join the captured force before releasing its
   * storage.
   */
  StatusCode force(long startOffset, long endOffset, ForceMode mode);

  StatusCode truncate(long sizeBytes);

  StatusCode size(FileSizeResult result);

  StatusCode close();
}
