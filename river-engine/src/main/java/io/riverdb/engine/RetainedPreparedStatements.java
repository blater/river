package io.riverdb.engine;

import io.riverdb.base.error.StatusCode;
import io.riverdb.engine.api.PreparedOpenResult;
import io.riverdb.engine.sql.SqlPreparedValidationResult;
import io.riverdb.engine.sql.SqlPreparedPlan;
import io.riverdb.engine.sql.SqlRetainedBudget;
import java.util.Arrays;

/**
 * Database-accounted immutable statement templates retained behind session handles.
 */
final class RetainedPreparedStatements {
  private static final int INITIAL_DIRECTORY_CAPACITY = 8;
  private static final long DIRECTORY_HEADER_BYTES = 24;
  private final SqlRetainedBudget budget;
  private final SessionHandleDirectory handles;
  private PreparedStatementChunk[] chunks = new PreparedStatementChunk[0];
  private int chunkCount;
  private int freeSlot;
  private long directoryBytes;

  RetainedPreparedStatements(
      SqlRetainedBudget retainedBudget, SessionHandleDirectory handleDirectory) {
    budget = retainedBudget;
    handles = handleDirectory;
  }

  StatusCode open(
      SqlPreparedValidationResult validation, PreparedOpenResult result) {
    if (validation == null || validation.plan() == null || result == null) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    long bytes = validation.transferReservation(budget);
    if (bytes <= 0) return StatusCode.INVALID_EXTERNAL_INPUT;
    StatusCode status = StatusCode.OK;
    if (freeSlot == 0) {
      status = appendChunk();
      if (!status.isOk()) {
        StatusCode cleanup = budget.releaseRetainedBytes(bytes);
        return cleanup.isOk() ? status : cleanup;
      }
    }
    int encodedSlot = freeSlot;
    int globalSlot = encodedSlot - 1;
    int chunkIndex = globalSlot / PreparedStatementChunk.SLOT_COUNT;
    int slot = globalSlot % PreparedStatementChunk.SLOT_COUNT;
    PreparedStatementChunk chunk = chunks[chunkIndex];
    freeSlot = chunk.nextFree(slot);
    long handle = handles.add(encodedSlot);
    if (handle == 0) {
      chunk.nextFree(slot, freeSlot);
      freeSlot = encodedSlot;
      StatusCode cleanup = budget.releaseRetainedBytes(bytes);
      return cleanup.isOk() ? StatusCode.RESOURCE_EXHAUSTED : cleanup;
    }
    chunk.open(
        slot, handle, validation.plan(), validation.query(), bytes);
    status = result.complete(handle, validation.parameterCount(), validation.query());
    if (!status.isOk()) {
      if (!handles.remove(handle)) return StatusCode.INVARIANT_BROKEN;
      if (!chunk.close(slot, handle)) return StatusCode.INVARIANT_BROKEN;
      chunk.nextFree(slot, freeSlot);
      freeSlot = encodedSlot;
      StatusCode cleanup = budget.releaseRetainedBytes(bytes);
      if (!cleanup.isOk()) return cleanup;
      return status;
    }
    return StatusCode.OK;
  }

  SqlPreparedPlan resolve(long handle, boolean query) {
    int encodedSlot = handles.resolve(handle);
    if (encodedSlot <= 0) return null;
    int globalSlot = encodedSlot - 1;
    int chunkIndex = globalSlot / PreparedStatementChunk.SLOT_COUNT;
    return chunkIndex >= chunkCount ? null : chunks[chunkIndex].resolve(
        globalSlot % PreparedStatementChunk.SLOT_COUNT, handle, query);
  }

  SqlPreparedPlan resolve(long handle) {
    SqlPreparedPlan plan = resolve(handle, false);
    return plan == null ? resolve(handle, true) : plan;
  }

  SqlPreparedPlan retain(long handle) {
    int encodedSlot = handles.resolve(handle);
    if (encodedSlot <= 0) return null;
    int globalSlot = encodedSlot - 1;
    int chunkIndex = globalSlot / PreparedStatementChunk.SLOT_COUNT;
    return chunkIndex >= chunkCount ? null : chunks[chunkIndex].retain(
        globalSlot % PreparedStatementChunk.SLOT_COUNT, handle);
  }

  StatusCode releaseReference(long handle) {
    int encodedSlot = handles.resolve(handle);
    if (encodedSlot <= 0) return StatusCode.INVALID_EXTERNAL_INPUT;
    int globalSlot = encodedSlot - 1;
    int chunkIndex = globalSlot / PreparedStatementChunk.SLOT_COUNT;
    return chunkIndex < chunkCount && chunks[chunkIndex].release(
        globalSlot % PreparedStatementChunk.SLOT_COUNT, handle)
        ? StatusCode.OK : StatusCode.INVARIANT_BROKEN;
  }

  StatusCode close(long handle) {
    int encodedSlot = handles.resolve(handle);
    if (encodedSlot <= 0) return StatusCode.INVALID_EXTERNAL_INPUT;
    int globalSlot = encodedSlot - 1;
    int chunkIndex = globalSlot / PreparedStatementChunk.SLOT_COUNT;
    if (chunkIndex >= chunkCount) return StatusCode.INVALID_EXTERNAL_INPUT;
    int slot = globalSlot % PreparedStatementChunk.SLOT_COUNT;
    PreparedStatementChunk chunk = chunks[chunkIndex];
    long bytes = chunk.retainedBytes(slot, handle);
    if (bytes == 0) return StatusCode.INVALID_EXTERNAL_INPUT;
    if (!chunk.canClose(slot, handle)) return StatusCode.CONFLICT;
    StatusCode status = budget.releaseRetainedBytes(bytes);
    if (status.isOk() && !chunk.close(slot, handle)) return StatusCode.INVARIANT_BROKEN;
    if (status.isOk()) {
      if (!handles.remove(handle)) return StatusCode.INVARIANT_BROKEN;
      chunk.nextFree(slot, freeSlot);
      freeSlot = encodedSlot;
    }
    return status;
  }

  StatusCode clear() {
    long bytes = directoryBytes;
    for (int index = 0; index < chunkCount; index++) {
      bytes += PreparedStatementChunk.ACCOUNTED_BYTES + chunks[index].activeRetainedBytes();
    }
    if (bytes == 0) return StatusCode.OK;
    StatusCode status = budget.releaseRetainedBytes(bytes);
    if (!status.isOk()) return status;
    for (int index = 0; index < chunkCount; index++) chunks[index].clear();
    chunks = new PreparedStatementChunk[0];
    chunkCount = 0;
    freeSlot = 0;
    directoryBytes = 0;
    return StatusCode.OK;
  }

  private StatusCode appendChunk() {
    int nextCapacity = chunks.length;
    if (chunkCount == chunks.length) {
      nextCapacity = chunks.length == 0 ? INITIAL_DIRECTORY_CAPACITY : chunks.length * 2;
      if (nextCapacity <= chunks.length) return StatusCode.RESOURCE_EXHAUSTED;
    }
    long nextDirectoryBytes = DIRECTORY_HEADER_BYTES + (long) nextCapacity * Long.BYTES;
    long addedDirectoryBytes = nextDirectoryBytes - directoryBytes;
    long charge = PreparedStatementChunk.ACCOUNTED_BYTES + addedDirectoryBytes;
    StatusCode status = budget.reserveRetainedBytes(charge);
    if (!status.isOk()) return status;
    try {
      PreparedStatementChunk[] nextChunks = nextCapacity == chunks.length
          ? chunks : Arrays.copyOf(chunks, nextCapacity);
      PreparedStatementChunk chunk = new PreparedStatementChunk();
      nextChunks[chunkCount] = chunk;
      int nextFreeSlot = freeSlot;
      for (int slot = PreparedStatementChunk.SLOT_COUNT - 1; slot >= 0; slot--) {
        int encodedSlot = chunkCount * PreparedStatementChunk.SLOT_COUNT + slot + 1;
        chunk.nextFree(slot, nextFreeSlot);
        nextFreeSlot = encodedSlot;
      }
      chunks = nextChunks;
      freeSlot = nextFreeSlot;
      chunkCount++;
      directoryBytes = nextDirectoryBytes;
      return StatusCode.OK;
    } catch (OutOfMemoryError failure) {
      StatusCode cleanup = budget.releaseRetainedBytes(charge);
      return cleanup.isOk() ? StatusCode.RESOURCE_EXHAUSTED : cleanup;
    }
  }
}
