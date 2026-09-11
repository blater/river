package io.riverdb.tx;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.riverdb.base.error.StatusCode;
import io.riverdb.tx.api.lock.LockExecutionLane;
import io.riverdb.tx.api.lock.LockMode;
import io.riverdb.tx.api.lock.LockRequest;
import io.riverdb.tx.api.lock.LockScope;
import io.riverdb.tx.api.lock.LockToken;
import io.riverdb.tx.api.lock.LockWaitHandle;
import java.nio.ByteBuffer;

final class LockTupleIntervalTableTestSupport {
  static void cycle(Fixture fixture, LockRequest request, LockToken token) {
    if (fixture.table.tryAcquire(1, 1, 1, request, token) != StatusCode.OK) {
      throw new AssertionError("tuple acquisition failed");
    }
    if (fixture.table.release(token) != StatusCode.OK || token.reset() != StatusCode.OK) {
      throw new AssertionError("tuple release failed");
    }
  }

  static void reactiveCycle(
      Fixture fixture, LockRequest request, LockToken owner, LockToken granted, Wait wait) {
    if (fixture.table.tryAcquire(1, 1, 1, request, owner) != StatusCode.OK
        || fixture.table.enqueue(2, 1, 2, 1, 1, request, wait.lane, wait.handle)
            != StatusCode.RETRY
        || fixture.table.release(owner) != StatusCode.OK
        || fixture.table.consume(wait.lane, wait.handle, granted) != StatusCode.OK
        || fixture.table.release(granted) != StatusCode.OK
        || wait.lane.reset() != StatusCode.OK
        || wait.handle.reset() != StatusCode.OK) {
      throw new AssertionError("tuple reactive cycle failed");
    }
  }

  static LockSlotReservation reserveCommitted(LockExactResourceStore resources) {
    LockSlotReservation reservation = new LockSlotReservation();
    assertEquals(StatusCode.OK, resources.reserve(reservation));
    long slot = reservation.slot;
    resources.commit(reservation);
    reservation.slot = slot;
    return reservation;
  }

  static LockToken acquire(Fixture fixture, long transaction, LockRequest request) {
    LockToken token = new LockToken();
    assertEquals(StatusCode.OK,
        fixture.table.tryAcquire(transaction, 1, transaction, request, token));
    return token;
  }

  static StatusCode tryAcquire(Fixture fixture, long transaction, LockRequest request) {
    return fixture.table.tryAcquire(transaction, 1, transaction, request, new LockToken());
  }

  static Wait enqueue(
      Fixture fixture, long transaction, long lane, LockRequest request, StatusCode expected) {
    Wait wait = new Wait();
    assertEquals(expected, fixture.table.enqueue(
        transaction, 1, transaction, lane, 1, request, wait.lane, wait.handle));
    return wait;
  }

  static LockToken consume(Fixture fixture, Wait wait) {
    LockToken token = new LockToken();
    assertEquals(StatusCode.OK, fixture.table.consume(wait.lane, wait.handle, token));
    return token;
  }

  static LockRequest key(long namespace, ByteBuffer key) {
    return key(namespace, key, LockMode.EXCLUSIVE);
  }

  static LockRequest key(long namespace, ByteBuffer key, LockMode mode) {
    return new LockRequest().setTupleKey(
        namespace, key, 0, key.remaining(), mode, 0);
  }

  static LockRequest prefix(long namespace, ByteBuffer key, LockMode mode) {
    return range(namespace, key, true, key, true, mode);
  }

  static LockRequest range(
      long namespace, ByteBuffer lower, boolean lowerInclusive,
      ByteBuffer upper, boolean upperInclusive, LockMode mode) {
    return new LockRequest().setTupleRange(namespace,
        lower, 0, lower == null ? 0 : lower.remaining(), lowerInclusive,
        upper, 0, upper == null ? 0 : upper.remaining(), upperInclusive, mode, 0);
  }

  static LockRequest row(long identity) {
    return new LockRequest().setExact(
        LockScope.ROW, 71, identity, LockMode.EXCLUSIVE, 0);
  }

  static ByteBuffer bytes(int... values) {
    byte[] bytes = new byte[values.length];
    for (int index = 0; index < values.length; index++) bytes[index] = (byte) values[index];
    return ByteBuffer.wrap(bytes);
  }

  static ByteBuffer ordered(int value) {
    return ByteBuffer.wrap(new byte[] {(byte) (value >>> 8), (byte) value});
  }

  static final class Wait {
    final LockExecutionLane lane = new LockExecutionLane();
    final LockWaitHandle handle = new LockWaitHandle();
  }

  static final class Fixture {
    final LockSegmentArena arena = new LockSegmentArena(new LockMemoryEnvelope(32L << 20));
    final LockExactTable table = new LockExactTable(new Object(), 113, arena);
  }
}
