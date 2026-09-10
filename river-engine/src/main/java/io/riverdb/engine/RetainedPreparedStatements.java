package io.riverdb.engine;

import io.riverdb.base.error.StatusCode;
import io.riverdb.engine.api.PreparedOpenResult;
import io.riverdb.engine.sql.SqlPreparedValidationResult;
import io.riverdb.engine.sql.SqlPreparedPlan;
import io.riverdb.engine.sql.SqlRetainedBudget;
import io.riverdb.engine.sql.SqlSession;
import java.util.Arrays;

/**
 * Database-accounted immutable statement templates retained behind session handles.
 */
final class RetainedPreparedStatements {
  private static final int INITIAL_DIRECTORY_CAPACITY = 8;
  private static final long DIRECTORY_HEADER_BYTES = 24;
  private final SqlRetainedBudget budget;
  private final SessionHandleDirectory handles;
  private final RetainedPreparedTemplates templates;
  private PreparedStatementChunk[] chunks = new PreparedStatementChunk[0];
  private int chunkCount;
  private int freeSlot;
  private long directoryBytes;

  RetainedPreparedStatements(
      SqlRetainedBudget retainedBudget, SessionHandleDirectory handleDirectory) {
    budget = retainedBudget;
    handles = handleDirectory;
    templates = new RetainedPreparedTemplates(budget);
  }

  StatusCode prepare(
      String sql, SqlSession session, SqlPreparedValidationResult validation,
      PreparedOpenResult result) {
    result.reset();
    RetainedPreparedTemplate entry = templates.find(sql);
    SqlPreparedPlan candidate = entry == null ? null : entry.plan;
    StatusCode status = session.validatePrepared(sql, candidate, budget, validation);
    if (status.isOk()) {
      if (validation.plan() == candidate) status = templates.retain(entry);
      else {
        status = templates.create(sql, validation);
        entry = templates.opened();
      }
      if (status.isOk()) {
        status = open(entry, result);
        if (!status.isOk()) {
          StatusCode released = templates.release(entry);
          if (!released.isOk()) status = released;
        }
      }
    }
    StatusCode released = validation.reset();
    return status.isOk() ? released : status;
  }

  private StatusCode open(RetainedPreparedTemplate entry, PreparedOpenResult result) {
    if (freeSlot == 0) {
      StatusCode status = appendChunk();
      if (!status.isOk()) return status;
    }
    int encodedSlot = freeSlot;
    int globalSlot = encodedSlot - 1;
    PreparedStatementChunk chunk = chunks[globalSlot / PreparedStatementChunk.SLOT_COUNT];
    int slot = globalSlot % PreparedStatementChunk.SLOT_COUNT;
    long handle = handles.add(encodedSlot);
    if (handle == 0) return StatusCode.RESOURCE_EXHAUSTED;
    StatusCode status = result.complete(handle, entry.plan.parameterCount(), entry.plan.query());
    if (!status.isOk()) {
      return handles.remove(handle) ? status : StatusCode.INVARIANT_BROKEN;
    }
    freeSlot = chunk.nextFree(slot);
    chunk.open(slot, handle, entry);
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
    int encodedSlot = handles.resolve(handle);
    if (encodedSlot <= 0) return null;
    int globalSlot = encodedSlot - 1;
    int chunkIndex = globalSlot / PreparedStatementChunk.SLOT_COUNT;
    return chunkIndex >= chunkCount ? null : chunks[chunkIndex].resolve(
        globalSlot % PreparedStatementChunk.SLOT_COUNT, handle);
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
    RetainedPreparedTemplate entry = chunk.template(slot, handle);
    if (entry == null) return StatusCode.INVALID_EXTERNAL_INPUT;
    if (!chunk.canClose(slot, handle)) return StatusCode.CONFLICT;
    StatusCode status = templates.release(entry);
    if (status.isOk() && !chunk.close(slot, handle)) return StatusCode.INVARIANT_BROKEN;
    if (status.isOk()) {
      if (!handles.remove(handle)) return StatusCode.INVARIANT_BROKEN;
      chunk.nextFree(slot, freeSlot);
      freeSlot = encodedSlot;
    }
    return status;
  }

  StatusCode clear() {
    long bytes = directoryBytes + templates.retainedBytes();
    for (int index = 0; index < chunkCount; index++) {
      bytes += PreparedStatementChunk.ACCOUNTED_BYTES;
    }
    if (bytes == 0) return StatusCode.OK;
    StatusCode status = budget.releaseRetainedBytes(bytes);
    if (!status.isOk()) return status;
    templates.clearStorage();
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
