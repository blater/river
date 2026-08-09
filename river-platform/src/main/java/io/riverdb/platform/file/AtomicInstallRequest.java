package io.riverdb.platform.file;

import io.riverdb.base.error.StatusCode;
import java.nio.ByteBuffer;

/** Caller-owned immutable-for-an-install description of bytes and provider-relative names. */
public final class AtomicInstallRequest {
  private String temporaryFileName;
  private String destinationFileName;
  private ByteBuffer content;
  private int contentPosition;
  private int contentLength;
  private long version;

  public StatusCode configure(
      String temporaryFileName,
      String destinationFileName,
      ByteBuffer content) {
    if (temporaryFileName == null
        || destinationFileName == null
        || content == null
        || temporaryFileName.equals(destinationFileName)) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    this.temporaryFileName = temporaryFileName;
    this.destinationFileName = destinationFileName;
    this.content = content;
    contentPosition = content.position();
    contentLength = content.remaining();
    version++;
    if (version == 0) {
      version = 1;
    }
    return StatusCode.OK;
  }

  public String temporaryFileName() {
    return temporaryFileName;
  }

  public String destinationFileName() {
    return destinationFileName;
  }

  public ByteBuffer content() {
    return content;
  }

  public int contentPosition() {
    return contentPosition;
  }

  public int contentLength() {
    return contentLength;
  }

  public long version() {
    return version;
  }

  public boolean remainsReadable() {
    return content != null
        && contentPosition >= 0
        && contentLength >= 0
        && content.limit() >= contentPosition + contentLength;
  }
}
