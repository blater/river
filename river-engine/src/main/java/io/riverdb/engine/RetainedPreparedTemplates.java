package io.riverdb.engine;

import io.riverdb.base.error.StatusCode;
import io.riverdb.engine.sql.SqlPreparedValidationResult;
import io.riverdb.engine.sql.SqlRetainedBudget;

/** Exact-SQL lookup over live plans, including independently owned stale generations. */
final class RetainedPreparedTemplates {
  private final SqlRetainedBudget budget;
  private RetainedPreparedTemplate[] buckets = new RetainedPreparedTemplate[0];
  private int indexed;
  private long directoryBytes;
  private long entryBytes;
  private RetainedPreparedTemplate opened;

  RetainedPreparedTemplates(SqlRetainedBudget budget) { this.budget = budget; }

  RetainedPreparedTemplate find(String sql) {
    if (sql == null || buckets.length == 0) return null;
    RetainedPreparedTemplate newest = null;
    for (RetainedPreparedTemplate entry = buckets[bucket(sql)];
        entry != null; entry = entry.next) {
      if (sql.equals(entry.sql) && (newest == null
          || entry.plan.preparationGeneration() > newest.plan.preparationGeneration())) {
        newest = entry;
      }
    }
    return newest;
  }

  StatusCode create(String sql, SqlPreparedValidationResult validation) {
    opened = null;
    boolean shareable = validation.plan().preparationGeneration() > 0;
    if (shareable && (indexed + 1L) * 2 > buckets.length) {
      StatusCode status = grow();
      if (!status.isOk()) return status;
    }
    // String plus its backing array, conservatively charged as UTF-16 storage.
    long extra = RetainedPreparedTemplate.ACCOUNTED_BYTES
        + (shareable ? 64L + (long) sql.length() * Character.BYTES : 0);
    StatusCode status = budget.reserveRetainedBytes(extra);
    if (!status.isOk()) return status;
    RetainedPreparedTemplate entry;
    try {
      entry = new RetainedPreparedTemplate(validation.plan(), shareable ? sql : null,
          validation.plan().byteCharge() + extra);
    } catch (OutOfMemoryError failure) {
      StatusCode released = budget.releaseRetainedBytes(extra);
      return released.isOk() ? StatusCode.RESOURCE_EXHAUSTED : released;
    }
    if (validation.transferReservation(budget) != validation.plan().byteCharge()) {
      StatusCode released = budget.releaseRetainedBytes(extra);
      return released.isOk() ? StatusCode.INVARIANT_BROKEN : released;
    }
    if (shareable) {
      int bucket = bucket(sql);
      entry.next = buckets[bucket];
      buckets[bucket] = entry;
      indexed++;
    }
    entryBytes += entry.bytes;
    opened = entry;
    return StatusCode.OK;
  }

  RetainedPreparedTemplate opened() { return opened; }

  StatusCode retain(RetainedPreparedTemplate entry) {
    if (entry.references == Integer.MAX_VALUE) return StatusCode.RESOURCE_EXHAUSTED;
    entry.references++;
    return StatusCode.OK;
  }

  StatusCode release(RetainedPreparedTemplate entry) {
    if (entry.references > 1) {
      entry.references--;
      return StatusCode.OK;
    }
    StatusCode status = budget.releaseRetainedBytes(entry.bytes);
    if (!status.isOk()) return status;
    if (entry.sql != null) {
      int bucket = bucket(entry.sql);
      RetainedPreparedTemplate previous = null;
      for (RetainedPreparedTemplate current = buckets[bucket];
          current != entry; current = current.next) previous = current;
      if (previous == null) buckets[bucket] = entry.next;
      else previous.next = entry.next;
      indexed--;
    }
    entryBytes -= entry.bytes;
    entry.references = 0;
    entry.next = null;
    if (opened == entry) opened = null;
    return StatusCode.OK;
  }

  long retainedBytes() { return directoryBytes + entryBytes; }

  /** Called only after the owning statement store releases its aggregate reservation. */
  void clearStorage() {
    buckets = new RetainedPreparedTemplate[0];
    opened = null;
    indexed = 0;
    directoryBytes = entryBytes = 0;
  }

  private int bucket(String sql) { return sql.hashCode() & (buckets.length - 1); }

  private StatusCode grow() {
    int capacity = buckets.length == 0 ? 16 : buckets.length * 2;
    if (capacity <= buckets.length) return StatusCode.RESOURCE_EXHAUSTED;
    long bytes = 24L + (long) capacity * Long.BYTES;
    long added = bytes - directoryBytes;
    StatusCode status = budget.reserveRetainedBytes(added);
    if (!status.isOk()) return status;
    RetainedPreparedTemplate[] next;
    try {
      next = new RetainedPreparedTemplate[capacity];
    } catch (OutOfMemoryError failure) {
      StatusCode released = budget.releaseRetainedBytes(added);
      return released.isOk() ? StatusCode.RESOURCE_EXHAUSTED : released;
    }
    for (RetainedPreparedTemplate head : buckets) {
      while (head != null) {
        RetainedPreparedTemplate following = head.next;
        int bucket = head.sql.hashCode() & (capacity - 1);
        head.next = next[bucket];
        next[bucket] = head;
        head = following;
      }
    }
    buckets = next;
    directoryBytes = bytes;
    return StatusCode.OK;
  }
}
