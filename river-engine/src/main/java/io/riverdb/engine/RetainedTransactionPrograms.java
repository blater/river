package io.riverdb.engine;

import io.riverdb.base.error.StatusCode;
import io.riverdb.engine.api.ProgramOpenResult;
import io.riverdb.engine.api.TransactionProgram;
import io.riverdb.engine.sql.SqlRetainedBudget;
import java.util.Arrays;

/** Session-owned stale-safe directory of canonical transaction programs. */
final class RetainedTransactionPrograms {
  private static final long DIRECTORY_HEADER_BYTES = 64L;
  private final SqlRetainedBudget budget;
  private final RetainedPreparedStatements prepared;
  private final SessionHandleDirectory handleDirectory;
  private RetainedTransactionProgram[] entries = new RetainedTransactionProgram[0];
  private long[] handles = new long[0];
  private int[] nextFree = new int[0];
  private int freeSlot;
  private long directoryBytes;

  RetainedTransactionPrograms(
      SqlRetainedBudget retainedBudget,
      RetainedPreparedStatements retainedPrepared,
      SessionHandleDirectory handles) {
    budget = retainedBudget;
    prepared = retainedPrepared;
    handleDirectory = handles;
  }

  StatusCode open(TransactionProgram source, ProgramOpenResult result) {
    if (source == null || result == null || !source.isFrozen()) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    if (freeSlot == 0) {
      StatusCode growth = grow();
      if (!growth.isOk()) return growth;
    }
    int encodedSlot = freeSlot;
    int slot = encodedSlot - 1;
    RetainedTransactionProgram entry = new RetainedTransactionProgram(budget, prepared);
    StatusCode status = entry.initialize(source);
    if (!status.isOk()) return status;
    long handle = handleDirectory.add(-encodedSlot);
    if (handle == 0) {
      StatusCode cleanup = entry.close();
      return cleanup.isOk() ? StatusCode.RESOURCE_EXHAUSTED : cleanup;
    }
    status = result.complete(handle, source.requiredArgumentSlots());
    if (!status.isOk()) {
      if (!handleDirectory.remove(handle)) return StatusCode.INVARIANT_BROKEN;
      entry.close();
      return status;
    }
    freeSlot = nextFree[slot];
    entries[slot] = entry;
    handles[slot] = handle;
    return StatusCode.OK;
  }

  RetainedTransactionProgram resolve(long handle) {
    int slot = slot(handleDirectory.resolve(handle));
    return slot < 0 || handles[slot] != handle ? null : entries[slot];
  }

  StatusCode close(long handle) {
    int slot = slot(handleDirectory.resolve(handle));
    if (slot < 0 || handles[slot] != handle || entries[slot] == null) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    StatusCode status = entries[slot].close();
    if (!status.isOk()) return status;
    entries[slot] = null;
    handles[slot] = 0;
    if (!handleDirectory.remove(handle)) return StatusCode.INVARIANT_BROKEN;
    nextFree[slot] = freeSlot;
    freeSlot = slot + 1;
    return StatusCode.OK;
  }

  StatusCode clear() {
    for (int slot = 0; slot < entries.length; slot++) {
      if (entries[slot] == null) continue;
      StatusCode status = entries[slot].close();
      if (!status.isOk()) return status;
      entries[slot] = null;
      if (!handleDirectory.remove(handles[slot])) return StatusCode.INVARIANT_BROKEN;
      handles[slot] = 0;
    }
    StatusCode status = directoryBytes == 0
        ? StatusCode.OK : budget.releaseRetainedBytes(directoryBytes);
    if (!status.isOk()) return status;
    entries = new RetainedTransactionProgram[0];
    handles = new long[0];
    nextFree = new int[0];
    freeSlot = 0;
    directoryBytes = 0;
    return StatusCode.OK;
  }

  private StatusCode grow() {
    int current = entries.length;
    int capacity = current == 0 ? 8 : current << 1;
    if (capacity <= current) return StatusCode.RESOURCE_EXHAUSTED;
    long bytes = DIRECTORY_HEADER_BYTES
        + (long) capacity * (Long.BYTES * 2 + Integer.BYTES);
    StatusCode status = budget.reserveRetainedBytes(bytes - directoryBytes);
    if (!status.isOk()) return status;
    try {
      RetainedTransactionProgram[] nextEntries = Arrays.copyOf(entries, capacity);
      long[] nextHandles = Arrays.copyOf(handles, capacity);
      int[] nextFreeSlots = Arrays.copyOf(nextFree, capacity);
      for (int slot = capacity - 1; slot >= current; slot--) {
        nextFreeSlots[slot] = freeSlot;
        freeSlot = slot + 1;
      }
      entries = nextEntries;
      handles = nextHandles;
      nextFree = nextFreeSlots;
      directoryBytes = bytes;
      return StatusCode.OK;
    } catch (OutOfMemoryError failure) {
      StatusCode cleanup = budget.releaseRetainedBytes(bytes - directoryBytes);
      return cleanup.isOk() ? StatusCode.RESOURCE_EXHAUSTED : cleanup;
    }
  }

  private int slot(int resource) {
    if (resource >= 0) return -1;
    int encoded = -resource;
    return encoded == 0 || encoded > entries.length ? -1 : encoded - 1;
  }
}
