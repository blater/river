package io.riverdb.engine.runtime;

import io.riverdb.base.error.StatusCode;

/** Caller-owned exact transaction Delivery vector, excluding reserved owner progress. */
public final class ResourceDemand {
  private long accountedBytes;
  private long writeEntries;
  private long stagedPages;
  private long walBytes;

  public StatusCode set(
      long requestedAccountedBytes,
      long requestedWriteEntries,
      long requestedStagedPages,
      long requestedWalBytes) {
    reset();
    if (requestedAccountedBytes < 0 || requestedWriteEntries < 0
        || requestedStagedPages < 0 || requestedWalBytes < 0
        || requestedAccountedBytes == 0 && requestedWriteEntries == 0
            && requestedStagedPages == 0 && requestedWalBytes == 0) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    accountedBytes = requestedAccountedBytes;
    writeEntries = requestedWriteEntries;
    stagedPages = requestedStagedPages;
    walBytes = requestedWalBytes;
    return StatusCode.OK;
  }

  public void reset() { accountedBytes = writeEntries = stagedPages = walBytes = 0; }
  public long accountedBytes() { return accountedBytes; }
  public long writeEntries() { return writeEntries; }
  public long stagedPages() { return stagedPages; }
  public long walBytes() { return walBytes; }

  boolean valid() {
    return accountedBytes >= 0 && writeEntries >= 0 && stagedPages >= 0 && walBytes >= 0
        && (accountedBytes | writeEntries | stagedPages | walBytes) != 0;
  }
}
