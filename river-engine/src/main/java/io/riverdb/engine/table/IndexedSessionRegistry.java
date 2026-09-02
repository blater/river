package io.riverdb.engine.table;

import io.riverdb.base.collection.BoundedArrayGrowth;
import io.riverdb.base.error.StatusCode;
import java.util.Arrays;

/** Bounded database ownership index for published kernel sessions. */
public final class IndexedSessionRegistry {
  private final int maximumSessions;
  private IndexedTransactionSession[] sessions = new IndexedTransactionSession[0];
  private int count;

  public IndexedSessionRegistry(int maximum) {
    if (maximum <= 0) throw new IllegalArgumentException("invalid session capacity");
    maximumSessions = maximum;
  }

  public synchronized StatusCode register(IndexedTransactionSession session) {
    if (session == null) return StatusCode.INVALID_EXTERNAL_INPUT;
    if (contains(session)) return StatusCode.CONFLICT;
    if (count >= maximumSessions) return StatusCode.RESOURCE_EXHAUSTED;
    for (int index = 0; index < sessions.length; index++) {
      if (sessions[index] == null) {
        sessions[index] = session;
        count++;
        return StatusCode.OK;
      }
    }
    int capacity = BoundedArrayGrowth.capacity(
        sessions.length, count + 1, maximumSessions, 4);
    if (capacity < 0) return StatusCode.RESOURCE_EXHAUSTED;
    try {
      sessions = Arrays.copyOf(sessions, capacity);
      sessions[count] = session;
      count++;
      return StatusCode.OK;
    } catch (OutOfMemoryError failure) {
      return StatusCode.RESOURCE_EXHAUSTED;
    }
  }

  public synchronized StatusCode release(IndexedTransactionSession session) {
    if (session == null) return StatusCode.INVALID_EXTERNAL_INPUT;
    for (int index = 0; index < sessions.length; index++) {
      if (sessions[index] == session) {
        sessions[index] = null;
        count--;
        return StatusCode.OK;
      }
    }
    return StatusCode.NOT_OWNER;
  }

  public synchronized boolean contains(IndexedTransactionSession session) {
    for (IndexedTransactionSession registered : sessions) {
      if (registered == session) return true;
    }
    return false;
  }

  public synchronized int count() { return count; }

  public synchronized StatusCode closeAll() {
    for (int index = 0; index < sessions.length; index++) {
      IndexedTransactionSession session = sessions[index];
      if (session == null) continue;
      StatusCode status = session.close();
      if (!status.isOk() && status != StatusCode.CLOSED) return status;
    }
    return count == 0 ? StatusCode.OK : StatusCode.INVARIANT_BROKEN;
  }
}
