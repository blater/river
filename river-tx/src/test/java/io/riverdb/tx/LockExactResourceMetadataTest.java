package io.riverdb.tx;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.riverdb.base.error.StatusCode;
import io.riverdb.tx.api.lock.LockExecutionLane;
import io.riverdb.tx.api.lock.LockMode;
import io.riverdb.tx.api.lock.LockRequest;
import io.riverdb.tx.api.lock.LockScope;
import io.riverdb.tx.api.lock.LockToken;
import io.riverdb.tx.api.lock.LockWaitHandle;
import org.junit.jupiter.api.Test;

final class LockExactResourceMetadataTest {
  @Test
  void modeQueuesTrackArbitraryUnlinkWithoutChangingCanonicalFifo() {
    LockSegmentArena arena = new LockSegmentArena(new LockMemoryEnvelope(8L << 20));
    LockExactTable table = new LockExactTable(new Object(), 91, arena);
    LockRequest exclusive = request(100, LockMode.EXCLUSIVE);
    LockToken owner = new LockToken();
    assertEquals(StatusCode.OK, table.tryAcquire(1, 1, 1, exclusive, owner));

    LockExecutionLane firstSharedLane = new LockExecutionLane();
    LockExecutionLane updateLane = new LockExecutionLane();
    LockExecutionLane secondSharedLane = new LockExecutionLane();
    LockExecutionLane exclusiveLane = new LockExecutionLane();
    LockWaitHandle firstShared = new LockWaitHandle();
    LockWaitHandle update = new LockWaitHandle();
    LockWaitHandle secondShared = new LockWaitHandle();
    LockWaitHandle queuedExclusive = new LockWaitHandle();
    assertEquals(StatusCode.RETRY, table.enqueue(
        2, 1, 2, 1, 1,
        request(100, LockMode.SHARED), firstSharedLane, firstShared));
    assertEquals(StatusCode.RETRY, table.enqueue(
        3, 1, 3, 2, 1,
        request(100, LockMode.UPDATE), updateLane, update));
    assertEquals(StatusCode.RETRY, table.enqueue(
        4, 1, 4, 3, 1,
        request(100, LockMode.SHARED), secondSharedLane, secondShared));
    assertEquals(StatusCode.RETRY, table.enqueue(
        5, 1, 5, 4, 1, exclusive, exclusiveLane, queuedExclusive));

    long resource = table.state.directory.resource(exclusive);
    LockExactResourceStore.Chunk resources = table.state.resources.record(resource);
    int ro = LockTypedSlots.offset(resource);
    long firstSharedRequest = firstSharedLane.requestSlot();
    long updateRequest = updateLane.requestSlot();
    long secondSharedRequest = secondSharedLane.requestSlot();
    long exclusiveRequest = exclusiveLane.requestSlot();
    assertModeQueue(resources, ro, LockMode.SHARED, firstSharedRequest, secondSharedRequest);
    assertModeQueue(resources, ro, LockMode.UPDATE, updateRequest, updateRequest);
    assertModeQueue(resources, ro, LockMode.EXCLUSIVE, exclusiveRequest, exclusiveRequest);
    assertEquals(LockTypedSlots.encode(updateRequest), table.state.requests
        .record(firstSharedRequest).nextResource[LockTypedSlots.offset(firstSharedRequest)]);
    assertEquals(LockTypedSlots.encode(secondSharedRequest), table.state.requests
        .record(firstSharedRequest).nextMode[LockTypedSlots.offset(firstSharedRequest)]);
    assertEquals(0, resources.schedulerWorkNext[ro]);
    assertEquals(0, resources.scheduled[ro]);

    assertEquals(StatusCode.CANCELLED,
        table.cancel(updateLane, update, StatusCode.CANCELLED));
    assertModeQueue(resources, ro, LockMode.UPDATE, -1, -1);
    assertEquals(LockTypedSlots.encode(secondSharedRequest), table.state.requests
        .record(firstSharedRequest).nextResource[LockTypedSlots.offset(firstSharedRequest)]);
    assertEquals(StatusCode.CANCELLED, table.acknowledge(updateLane, update));

    assertEquals(StatusCode.CANCELLED,
        table.cancel(firstSharedLane, firstShared, StatusCode.CANCELLED));
    assertModeQueue(resources, ro, LockMode.SHARED, secondSharedRequest, secondSharedRequest);
    assertEquals(0, table.state.requests.record(secondSharedRequest)
        .previousMode[LockTypedSlots.offset(secondSharedRequest)]);
    assertEquals(StatusCode.CANCELLED, table.acknowledge(firstSharedLane, firstShared));

    assertEquals(StatusCode.CANCELLED,
        table.cancel(secondSharedLane, secondShared, StatusCode.CANCELLED));
    assertModeQueue(resources, ro, LockMode.SHARED, -1, -1);
    assertEquals(StatusCode.CANCELLED, table.acknowledge(secondSharedLane, secondShared));

    assertEquals(StatusCode.OK, table.release(owner));
    assertModeQueue(resources, ro, LockMode.EXCLUSIVE, -1, -1);
    assertEquals(0, resources.waitHeads[ro]);
    assertEquals(0, resources.waitTails[ro]);
    LockToken granted = new LockToken();
    assertEquals(StatusCode.OK, table.consume(exclusiveLane, queuedExclusive, granted));
    assertEquals(StatusCode.OK, table.release(granted));

    LockToken reused = new LockToken();
    LockRequest next = request(101, LockMode.EXCLUSIVE);
    assertEquals(StatusCode.OK, table.tryAcquire(6, 1, 6, next, reused));
    long reusedResource = table.state.directory.resource(next);
    assertEquals(resource, reusedResource);
    LockExactResourceStore.Chunk reusedChunk = table.state.resources.record(reusedResource);
    int reusedOffset = LockTypedSlots.offset(reusedResource);
    assertEquals(0, reusedChunk.schedulerWorkNext[reusedOffset]);
    assertEquals(0, reusedChunk.scheduled[reusedOffset]);
    assertEquals(StatusCode.OK, table.release(reused));
  }

  private static void assertModeQueue(
      LockExactResourceStore.Chunk chunk,
      int resourceOffset,
      LockMode mode,
      long expectedHead,
      long expectedTail) {
    int offset = LockExactResourceStore.modeOffset(mode.ordinal(), resourceOffset);
    assertEquals(LockTypedSlots.encode(expectedHead), chunk.modeWaitHeads[offset]);
    assertEquals(LockTypedSlots.encode(expectedTail), chunk.modeWaitTails[offset]);
  }

  private static LockRequest request(long identity, LockMode mode) {
    return new LockRequest().setExact(LockScope.ROW, 51, identity, mode, 0);
  }
}
