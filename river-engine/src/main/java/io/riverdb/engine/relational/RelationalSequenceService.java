package io.riverdb.engine.relational;

import io.riverdb.base.error.StatusCode;
import io.riverdb.storage.heap.HeapRowResult;
import io.riverdb.tx.api.IsolationLevel;
import io.riverdb.tx.api.TransactionOutcome;
import io.riverdb.tx.api.TransactionState;
import java.nio.ByteBuffer;

/** Owns bounded sequence reservations and their schema-versioned value cache. */
final class RelationalSequenceService {
  private static final int CACHE_SLOTS = 64;
  private static final int RESERVATION_VALUES = 64;

  private final RelationalSchemaGate schemaGate;
  private final HeapRowResult catalogRow = new HeapRowResult();
  private final ByteBuffer catalogScratch = ByteBuffer.allocateDirect(
      CatalogRecord.MAXIMUM_BYTES);
  private final ByteBuffer catalogOutput = ByteBuffer.allocateDirect(
      CatalogRecord.MAXIMUM_BYTES);
  private final CatalogSequenceCodec.SequenceResult sequence =
      new CatalogSequenceCodec.SequenceResult();
  private final long[] keys = new long[CACHE_SLOTS];
  private final long[] nextValues = new long[CACHE_SLOTS];
  private final long[] increments = new long[CACHE_SLOTS];
  private final long[] commitSequences = new long[CACHE_SLOTS];
  private final int[] remaining = new int[CACHE_SLOTS];
  private int nextReplacement;
  private long schemaVersion = 1;

  RelationalSequenceService(RelationalSchemaGate gate) {
    schemaGate = gate;
  }

  boolean consumeCached(long sequenceKey, SequenceValueResult result) {
    refreshSchemaVersion();
    int slot = cacheSlot(sequenceKey);
    if (slot < 0) {
      return false;
    }
    long value = nextValues[slot];
    remaining[slot]--;
    if (remaining[slot] > 0) {
      nextValues[slot] = value + increments[slot];
    }
    result.set(value, commitSequences[slot]);
    return true;
  }

  StatusCode reserve(
      RelationalSession session,
      long sequenceKey,
      CharSequence name,
      int identityTableId,
      long minimum,
      long maximum,
      SequenceValueResult result) {
    TransactionOutcome outcome = new TransactionOutcome();
    StatusCode status = readForUpdate(
        session, sequenceKey, name, identityTableId);
    if (status.isOk() && sequence.isExhausted()) {
      status = StatusCode.RESOURCE_EXHAUSTED;
    }
    long value = status.isOk() ? sequence.nextValue() : 0;
    long increment = status.isOk() ? sequence.increment() : 0;
    int reservation = status.isOk()
        ? reservation(value, increment, minimum, maximum) : 0;
    int reserved = Math.abs(reservation);
    boolean exhausted = reservation < 0;
    long next = status.isOk()
        ? reservationEnd(value, increment, reserved, exhausted) : value;
    if (status.isOk()) {
      status = update(
          session, sequenceKey, name, identityTableId, next, increment, exhausted);
    }
    status = finish(session, outcome, status);
    if (status.isOk()) {
      result.set(value, outcome.commitSequence());
      if (reserved > 1) {
        cache(sequenceKey, value, increment, outcome.commitSequence(), reserved);
      }
    }
    return status;
  }

  private StatusCode readForUpdate(
      RelationalSession session,
      long sequenceKey,
      CharSequence name,
      int identityTableId) {
    StatusCode status = session.begin(IsolationLevel.SERIALIZABLE);
    if (status.isOk()) {
      status = session.indexedSession().fetchByKey(sequenceKey, catalogRow);
    }
    if (!status.isOk()) {
      return status;
    }
    return identityTableId > 0
        ? CatalogSequenceCodec.decodeIdentity(
            catalogRow, catalogScratch, identityTableId, sequence)
        : CatalogSequenceCodec.decodeUser(
            catalogRow, catalogScratch, name, sequence);
  }

  private StatusCode update(
      RelationalSession session,
      long sequenceKey,
      CharSequence name,
      int identityTableId,
      long next,
      long increment,
      boolean exhausted) {
    if (identityTableId > 0) {
      CatalogSequenceCodec.encodeIdentity(
          catalogOutput, identityTableId, next, exhausted);
    } else {
      CatalogSequenceCodec.encodeUser(
          catalogOutput, name, next, increment, exhausted);
    }
    return session.indexedSession().update(sequenceKey, catalogOutput);
  }

  private StatusCode finish(
      RelationalSession session,
      TransactionOutcome outcome,
      StatusCode bodyStatus) {
    if (bodyStatus.isOk()) {
      return session.commit(outcome);
    }
    if (session.indexedSession().transaction().state() != TransactionState.ACTIVE) {
      return bodyStatus;
    }
    StatusCode abort = session.abort(outcome);
    return abort.isOk() ? bodyStatus : abort;
  }

  private static int reservation(
      long value, long increment, long minimum, long maximum) {
    long next = value;
    int reserved = 0;
    while (reserved < RESERVATION_VALUES) {
      reserved++;
      if (additionOverflows(next, increment)) {
        return -reserved;
      }
      long candidate = next + increment;
      if (candidate < minimum || candidate > maximum) {
        return -reserved;
      }
      next = candidate;
    }
    return reserved;
  }

  private static long reservationEnd(
      long value, long increment, int reserved, boolean exhausted) {
    long next = value;
    int successfulIncrements = exhausted ? reserved - 1 : reserved;
    for (int index = 0; index < successfulIncrements; index++) {
      next += increment;
    }
    return next;
  }

  private static boolean additionOverflows(long value, long increment) {
    return increment > 0 && value > Long.MAX_VALUE - increment
        || increment < 0 && value < Long.MIN_VALUE - increment;
  }

  private void cache(
      long sequenceKey,
      long value,
      long increment,
      long commitSequence,
      int reserved) {
    int slot = writableSlot(sequenceKey);
    keys[slot] = sequenceKey;
    nextValues[slot] = value + increment;
    increments[slot] = increment;
    commitSequences[slot] = commitSequence;
    remaining[slot] = reserved - 1;
  }

  private int cacheSlot(long sequenceKey) {
    for (int slot = 0; slot < CACHE_SLOTS; slot++) {
      if (remaining[slot] > 0 && keys[slot] == sequenceKey) {
        return slot;
      }
    }
    return -1;
  }

  private int writableSlot(long sequenceKey) {
    for (int slot = 0; slot < CACHE_SLOTS; slot++) {
      if (keys[slot] == sequenceKey || remaining[slot] == 0) {
        return slot;
      }
    }
    int slot = nextReplacement;
    nextReplacement = (slot + 1) % CACHE_SLOTS;
    return slot;
  }

  private void refreshSchemaVersion() {
    long current = schemaGate.version();
    if (schemaVersion == current) {
      return;
    }
    for (int slot = 0; slot < CACHE_SLOTS; slot++) {
      remaining[slot] = 0;
    }
    schemaVersion = current;
  }
}
