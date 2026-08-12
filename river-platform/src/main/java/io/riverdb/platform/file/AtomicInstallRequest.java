package io.riverdb.platform.file;

import io.riverdb.base.error.StatusCode;
import java.nio.ByteBuffer;

/**
 * Caller-owned immutable-for-an-install description of bytes and provider-relative names.
 *
 * <p>Configuration requires a provider-issued {@link AtomicInstallId}. The buffer and its
 * position, limit, and bytes are borrowed until progress reaches {@code VERIFIED} or
 * {@code RECOVERY_REQUIRED} and is reset. Debug/contract providers validate the recorded
 * fingerprint before every boundary; production providers may instead retain a bounded stable
 * copy under the same SPI.
 */
public final class AtomicInstallRequest {
  private String temporaryFileName;
  private String destinationFileName;
  private ByteBuffer content;
  private int contentPosition;
  private int contentLength;
  private int contentLimit;
  private long contentFingerprint;
  private long version;
  private AtomicInstallId installId;

  public StatusCode configure(
      AtomicInstallId installId,
      String temporaryFileName,
      String destinationFileName,
      ByteBuffer content) {
    if (installId == null
        || !validFileName(temporaryFileName)
        || !validFileName(destinationFileName)
        || content == null
        || temporaryFileName.equals(destinationFileName)) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    int newContentPosition = content.position();
    int newContentLength = content.remaining();
    long newContentFingerprint = fingerprint(content, newContentPosition, newContentLength);
    StatusCode bindStatus = installId.bind(
        temporaryFileName,
        destinationFileName,
        newContentFingerprint,
        newContentLength);
    if (!bindStatus.isOk()) {
      return bindStatus;
    }
    this.installId = installId;
    this.temporaryFileName = temporaryFileName;
    this.destinationFileName = destinationFileName;
    this.content = content;
    contentPosition = newContentPosition;
    contentLength = newContentLength;
    contentLimit = content.limit();
    contentFingerprint = newContentFingerprint;
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
        && content.position() == contentPosition
        && content.limit() == contentLimit
        && content.limit() >= contentPosition + contentLength
        && fingerprint(content, contentPosition, contentLength) == contentFingerprint;
  }

  boolean issuedBy(Object capability) {
    return installId != null
        && installId.owner == capability
        && installId.value != 0
        && installId.bound;
  }

  long installValue() {
    return installId == null ? 0 : installId.value;
  }

  private static boolean validFileName(String fileName) {
    if (fileName == null || fileName.isBlank() || fileName.length() > 128) {
      return false;
    }
    return fileName.indexOf('/') < 0
        && fileName.indexOf('\\') < 0
        && !fileName.equals(".")
        && !fileName.equals("..");
  }

  private static long fingerprint(ByteBuffer content, int position, int length) {
    long hash = 0xcbf29ce484222325L;
    for (int index = 0; index < length; index++) {
      hash ^= content.get(position + index) & 0xffL;
      hash *= 0x100000001b3L;
    }
    return hash;
  }
}
