package io.riverdb.platform.file;

import io.riverdb.base.error.StatusCode;

/**
 * Caller-owned opaque identity for exactly one logical atomic install.
 *
 * <p>An installer issues this carrier through {@link AtomicFileInstaller#issueInstallId}. The
 * carrier may then be shared by reconstructed {@link AtomicInstallRequest} instances describing
 * the same immutable names and content. It cannot be issued twice or rebound to a different
 * request. Callers cannot manufacture, inspect, or mutate its provider authority.
 */
public final class AtomicInstallId {
  Object owner;
  long value;
  String temporaryFileName;
  String destinationFileName;
  long contentFingerprint;
  int contentLength;
  boolean bound;

  StatusCode bind(
      String temporaryFileName,
      String destinationFileName,
      long contentFingerprint,
      int contentLength) {
    if (owner == null || value == 0) {
      return StatusCode.NOT_OWNER;
    }
    if (bound) {
      return this.contentLength == contentLength
              && this.contentFingerprint == contentFingerprint
              && this.temporaryFileName.equals(temporaryFileName)
              && this.destinationFileName.equals(destinationFileName)
          ? StatusCode.OK
          : StatusCode.CONFLICT;
    }
    this.temporaryFileName = temporaryFileName;
    this.destinationFileName = destinationFileName;
    this.contentFingerprint = contentFingerprint;
    this.contentLength = contentLength;
    bound = true;
    return StatusCode.OK;
  }
}
