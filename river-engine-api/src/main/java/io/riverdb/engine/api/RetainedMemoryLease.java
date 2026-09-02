package io.riverdb.engine.api;

import io.riverdb.base.error.StatusCode;

/** Exact retained-memory admission owned by one reusable workspace. */
public interface RetainedMemoryLease {
  /** Replaces this workspace's retained-byte charge atomically. */
  StatusCode resize(long bytes);

  /** Waits for shared admission without changing the current charge on interruption. */
  StatusCode awaitResize(long bytes);

  /** Current charge held by this workspace. */
  long retainedBytes();

  /** Standalone callers that own their memory outside a shared resource envelope. */
  static RetainedMemoryLease unbounded() {
    return Unbounded.INSTANCE;
  }

  enum Unbounded implements RetainedMemoryLease {
    INSTANCE;

    @Override
    public StatusCode resize(long bytes) {
      return bytes < 0 ? StatusCode.INVALID_EXTERNAL_INPUT : StatusCode.OK;
    }

    @Override
    public StatusCode awaitResize(long bytes) {
      return resize(bytes);
    }

    @Override
    public long retainedBytes() {
      return 0;
    }
  }
}
