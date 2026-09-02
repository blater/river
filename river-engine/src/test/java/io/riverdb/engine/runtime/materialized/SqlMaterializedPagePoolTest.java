package io.riverdb.engine.runtime.materialized;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.riverdb.base.error.StatusCode;
import java.nio.ByteBuffer;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

final class SqlMaterializedPagePoolTest {
  private static final int PAYLOAD = SqlMaterializedPageMapping.PAGE_HEADER_BYTES;

  @Test
  void reservesAtomicallyAndAllocatesDirectFramesLazily() {
    SqlMaterializedPagePool pool = pool(2);
    TestPageIo first = new TestPageIo(1, 64, 8);
    TestPageIo second = new TestPageIo(2, 64, 8);

    assertEquals(0, pool.allocatedFrameCount());
    assertEquals(StatusCode.OK, pool.reserve(11, 2));
    assertEquals(StatusCode.RESOURCE_EXHAUSTED, pool.reserve(22, 1));
    assertEquals(2, pool.reservationCount(11));
    assertEquals(0, pool.reservationCount(22));

    SqlMaterializedPagePin pin = new SqlMaterializedPagePin();
    assertEquals(StatusCode.OK, pool.pinNew(first, 11, 0, pin));
    assertTrue(pin.buffer().isDirect());
    pin.buffer().putLong(PAYLOAD, 41);
    assertEquals(StatusCode.OK, pool.markDirty(pin));
    assertEquals(StatusCode.OK, pool.unpin(pin));
    assertFalse(pin.active());
    assertNull(pin.buffer());

    assertEquals(StatusCode.OK, pool.pinNew(first, 11, 1, pin));
    pin.buffer().putLong(PAYLOAD, 42);
    assertEquals(StatusCode.OK, pool.markDirty(pin));
    assertEquals(StatusCode.OK, pool.unpin(pin));
    assertEquals(2, pool.allocatedFrameCount());
    assertEquals(StatusCode.RESOURCE_EXHAUSTED, pool.pinNew(second, 22, 0, pin));

    assertEquals(StatusCode.OK, pool.releaseReservation(11));
    assertEquals(StatusCode.OK, pool.pinNew(second, 22, 0, pin));
    assertEquals(0, first.lastWrittenPage);
    assertEquals(StatusCode.OK, pool.unpin(pin));

    assertEquals(StatusCode.OK, pool.pinExisting(first, 11, 0, pin));
    assertEquals(41, pin.buffer().getLong(PAYLOAD));
    assertEquals(StatusCode.OK, pool.unpin(pin));
    assertEquals(StatusCode.OK, pool.close());
  }

  @Test
  void releasesNestedReservationsByExactAcquisition() {
    SqlMaterializedPagePool pool = pool(6);

    assertEquals(StatusCode.OK, pool.reserve(11, 2));
    assertEquals(StatusCode.OK, pool.reserve(11, 3));
    assertEquals(5, pool.reservationCount(11));
    assertEquals(StatusCode.OK, pool.releaseReservation(11, 2));
    assertEquals(3, pool.reservationCount(11));
    assertEquals(StatusCode.RESOURCE_EXHAUSTED, pool.reserve(22, 4));
    assertEquals(StatusCode.OK, pool.reserve(22, 3));
    assertEquals(StatusCode.NOT_OWNER, pool.releaseReservation(11, 4));
    assertEquals(3, pool.reservationCount(11));
    assertEquals(StatusCode.OK, pool.releaseReservation(11, 3));
    assertEquals(StatusCode.OK, pool.releaseReservation(22));
    assertEquals(StatusCode.OK, pool.close());
  }

  @Test
  void allPinnedAcquisitionPreservesEveryOwnerPage() {
    SqlMaterializedPagePool pool = pool(2);
    TestPageIo first = new TestPageIo(1, 64, 4);
    TestPageIo second = new TestPageIo(2, 64, 4);
    TestPageIo third = new TestPageIo(3, 64, 4);
    SqlMaterializedPagePin firstPin = new SqlMaterializedPagePin();
    SqlMaterializedPagePin secondPin = new SqlMaterializedPagePin();
    SqlMaterializedPagePin failedPin = new SqlMaterializedPagePin();

    assertEquals(StatusCode.OK, pool.pinNew(first, 1, 0, firstPin));
    firstPin.buffer().putLong(PAYLOAD, 101);
    assertEquals(StatusCode.OK, pool.pinNew(second, 2, 0, secondPin));
    secondPin.buffer().putLong(PAYLOAD, 202);
    assertEquals(
        StatusCode.RESOURCE_EXHAUSTED,
        pool.pinNew(third, 3, 0, failedPin));
    assertFalse(failedPin.active());
    assertEquals(101, firstPin.buffer().getLong(PAYLOAD));
    assertEquals(202, secondPin.buffer().getLong(PAYLOAD));

    assertEquals(StatusCode.OK, pool.unpin(firstPin));
    assertEquals(StatusCode.OK, pool.unpin(secondPin));
    assertEquals(StatusCode.OK, pool.close());
  }

  @Test
  void cachedPageHasOneExclusivePinWithoutCursorMutation() {
    SqlMaterializedPagePool pool = pool(1);
    TestPageIo io = new TestPageIo(1, 64, 2);
    SqlMaterializedPagePin first = new SqlMaterializedPagePin();
    SqlMaterializedPagePin second = new SqlMaterializedPagePin();

    assertEquals(StatusCode.OK, pool.pinNew(io, 1, 0, first));
    first.buffer().position(19);
    assertEquals(
        StatusCode.RESOURCE_EXHAUSTED,
        pool.pinExisting(io, 1, 0, second));
    assertEquals(19, first.buffer().position());
    assertFalse(second.active());
    assertEquals(StatusCode.OK, pool.unpin(first));
    assertEquals(StatusCode.OK, pool.pinExisting(io, 1, 0, second));
    assertEquals(StatusCode.OK, pool.unpin(second));
    assertEquals(StatusCode.OK, pool.close());
  }

  @Test
  void clockGivesReferencedHandFrameASecondChance() {
    SqlMaterializedPagePool pool = pool(3);
    TestPageIo io = new TestPageIo(1, 64, 8);
    SqlMaterializedPagePin pin = new SqlMaterializedPagePin();
    for (int page = 0; page < 3; page++) {
      assertEquals(StatusCode.OK, pool.pinNew(io, 1, page, pin));
      assertEquals(StatusCode.OK, pool.markDirty(pin));
      assertEquals(StatusCode.OK, pool.unpin(pin));
    }
    assertEquals(StatusCode.OK, pool.pinNew(io, 1, 3, pin));
    assertEquals(0, io.lastWrittenPage);
    assertEquals(StatusCode.OK, pool.unpin(pin));
    assertEquals(StatusCode.OK, pool.pinExisting(io, 1, 1, pin));
    assertEquals(StatusCode.OK, pool.unpin(pin));

    assertEquals(StatusCode.OK, pool.pinNew(io, 1, 4, pin));
    assertEquals(2, io.lastWrittenPage);
    assertEquals(StatusCode.OK, pool.unpin(pin));
    assertEquals(StatusCode.OK, pool.close());
  }

  @Test
  void dirtyWriteFailureDoesNotEvictThatOwnersFrame() {
    SqlMaterializedPagePool pool = pool(2);
    TestPageIo failing = new TestPageIo(1, 64, 4);
    TestPageIo cold = new TestPageIo(2, 64, 4);
    TestPageIo incoming = new TestPageIo(3, 64, 4);
    SqlMaterializedPagePin pin = new SqlMaterializedPagePin();

    assertEquals(StatusCode.OK, pool.pinNew(failing, 1, 0, pin));
    pin.buffer().putLong(PAYLOAD, 77);
    assertEquals(StatusCode.OK, pool.markDirty(pin));
    assertEquals(StatusCode.OK, pool.unpin(pin));
    assertEquals(StatusCode.OK, pool.pinNew(cold, 2, 0, pin));
    assertEquals(StatusCode.OK, pool.unpin(pin));

    failing.failWrites = true;
    assertEquals(StatusCode.OK, pool.pinNew(incoming, 3, 0, pin));
    assertEquals(1, failing.writeAttempts);
    assertEquals(StatusCode.OK, pool.unpin(pin));
    assertEquals(StatusCode.RESOURCE_EXHAUSTED, pool.reserve(4, 2));
    assertEquals(0, pool.reservationCount(4));
    assertEquals(StatusCode.IO_FAILURE, pool.pinExisting(failing, 1, 0, pin));
    assertFalse(pin.active());
    assertEquals(StatusCode.OK, pool.invalidateOwner(1));
    assertEquals(StatusCode.OK, pool.close());
  }

  @Test
  void dirtyReservationFailurePublishesNoCapacity() {
    SqlMaterializedPagePool pool = pool(1);
    TestPageIo io = new TestPageIo(1, 64, 2);
    SqlMaterializedPagePin pin = new SqlMaterializedPagePin();
    assertEquals(StatusCode.OK, pool.pinNew(io, 1, 0, pin));
    pin.buffer().putLong(PAYLOAD, 55);
    assertEquals(StatusCode.OK, pool.markDirty(pin));
    assertEquals(StatusCode.OK, pool.unpin(pin));

    io.failWrites = true;
    assertEquals(StatusCode.IO_FAILURE, pool.reserve(2, 1));
    assertEquals(0, pool.reservationCount(2));
    assertEquals(1, io.writeAttempts);
    assertEquals(StatusCode.OK, pool.invalidateOwner(1));
    assertEquals(StatusCode.OK, pool.close());
  }

  @Test
  void concurrentReservationsHaveOneCompleteWinner() throws Exception {
    SqlMaterializedPagePool pool = pool(4);
    CountDownLatch start = new CountDownLatch(1);
    AtomicReference<StatusCode> first = new AtomicReference<>();
    AtomicReference<StatusCode> second = new AtomicReference<>();
    Thread left = new Thread(() -> reserveAfter(start, pool, 1, first));
    Thread right = new Thread(() -> reserveAfter(start, pool, 2, second));
    left.start();
    right.start();
    start.countDown();
    left.join();
    right.join();

    int successes = (first.get() == StatusCode.OK ? 1 : 0)
        + (second.get() == StatusCode.OK ? 1 : 0);
    assertEquals(1, successes);
    assertEquals(3, pool.reservationCount(first.get() == StatusCode.OK ? 1 : 2));
    assertEquals(0, pool.reservationCount(first.get() == StatusCode.OK ? 2 : 1));
    assertEquals(StatusCode.OK, pool.close());
  }

  @Test
  void ownerInvalidationRetiresPinUntilFinalUnpinThenReusesFrame() {
    SqlMaterializedPagePool pool = pool(1);
    TestPageIo io = new TestPageIo(1, 64, 2);
    SqlMaterializedPagePin pin = new SqlMaterializedPagePin();
    SqlMaterializedPagePin blocked = new SqlMaterializedPagePin();
    assertEquals(StatusCode.OK, pool.reserve(9, 1));
    assertEquals(StatusCode.OK, pool.pinNew(io, 9, 0, pin));
    pin.buffer().putLong(PAYLOAD, 909);

    assertEquals(StatusCode.INVARIANT_BROKEN, pool.invalidateOwner(9));
    assertEquals(1, pool.pinnedCount(9));
    assertEquals(0, pool.reservationCount(9));
    assertEquals(909, pin.buffer().getLong(PAYLOAD));
    assertEquals(
        StatusCode.RESOURCE_EXHAUSTED,
        pool.pinNew(new TestPageIo(2, 64, 2), 10, 0, blocked));
    assertEquals(StatusCode.OK, pool.unpin(pin));
    assertFalse(pin.active());
    assertEquals(StatusCode.OK,
        pool.pinNew(new TestPageIo(2, 64, 2), 10, 0, blocked));
    assertEquals(StatusCode.OK, pool.unpin(blocked));
    assertEquals(StatusCode.OK, pool.close());
  }

  @Test
  void fileInvalidationRetainsLiveTokenUntilUnpin() {
    SqlMaterializedPagePool pool = pool(1);
    TestPageIo closingFile = new TestPageIo(1, 64, 2);
    TestPageIo nextFile = new TestPageIo(2, 64, 2);
    SqlMaterializedPagePin pin = new SqlMaterializedPagePin();
    SqlMaterializedPagePin next = new SqlMaterializedPagePin();
    assertEquals(StatusCode.OK, pool.pinNew(closingFile, 7, 0, pin));
    pin.buffer().putLong(PAYLOAD, 707);

    assertEquals(StatusCode.INVARIANT_BROKEN, pool.invalidateFile(7, 1));
    assertEquals(707, pin.buffer().getLong(PAYLOAD));
    assertEquals(StatusCode.RESOURCE_EXHAUSTED, pool.pinNew(nextFile, 8, 0, next));
    assertEquals(StatusCode.OK, pool.unpin(pin));
    assertEquals(StatusCode.OK, pool.pinNew(nextFile, 8, 0, next));
    assertEquals(StatusCode.OK, pool.unpin(next));
    assertEquals(StatusCode.OK, pool.close());
  }

  @Test
  void closeRetainsPinnedBufferUntilUnpin() {
    SqlMaterializedPagePool pool = pool(1);
    TestPageIo io = new TestPageIo(1, 64, 2);
    SqlMaterializedPagePin pin = new SqlMaterializedPagePin();
    SqlMaterializedPagePin blocked = new SqlMaterializedPagePin();
    assertEquals(StatusCode.OK, pool.pinNew(io, 1, 0, pin));
    pin.buffer().putLong(PAYLOAD, 303);

    assertEquals(StatusCode.INVARIANT_BROKEN, pool.close());
    assertEquals(1, pool.allocatedFrameCount());
    assertEquals(303, pin.buffer().getLong(PAYLOAD));
    assertEquals(StatusCode.CLOSED, pool.pinNew(io, 1, 1, blocked));
    assertEquals(StatusCode.OK, pool.unpin(pin));
    assertEquals(0, pool.allocatedFrameCount());
  }

  private static SqlMaterializedPagePool pool(int frames) {
    SqlMaterializedPagePoolResult result = new SqlMaterializedPagePoolResult();
    assertEquals(StatusCode.OK, SqlMaterializedPagePool.create(64, frames, result));
    return result.pool();
  }

  private static void reserveAfter(
      CountDownLatch start, SqlMaterializedPagePool pool, long owner,
      AtomicReference<StatusCode> result) {
    try {
      start.await();
      result.set(pool.reserve(owner, 3));
    } catch (InterruptedException failure) {
      Thread.currentThread().interrupt();
      result.set(StatusCode.CANCELLED);
    }
  }

  private static final class TestPageIo implements SqlMaterializedPageIo {
    private final long identity;
    private final int pageBytes;
    private final byte[][] pages;
    private final boolean[] present;
    private boolean failWrites;
    private int writeAttempts;
    private long lastWrittenPage = -1;

    private TestPageIo(long fileIdentity, int bytes, int pageCount) {
      identity = fileIdentity;
      pageBytes = bytes;
      pages = new byte[pageCount][bytes];
      present = new boolean[pageCount];
    }

    @Override
    public long fileIdentity() { return identity; }

    @Override
    public StatusCode read(long filePosition, ByteBuffer target) {
      int page = page(filePosition);
      if (page < 0 || page >= pages.length || !present[page]) {
        return StatusCode.CORRUPTION;
      }
      target.clear();
      target.put(pages[page]);
      target.flip();
      return StatusCode.OK;
    }

    @Override
    public StatusCode write(long filePosition, ByteBuffer source) {
      writeAttempts++;
      if (failWrites) return StatusCode.IO_FAILURE;
      int page = page(filePosition);
      if (page < 0 || page >= pages.length) return StatusCode.IO_FAILURE;
      source.clear();
      source.get(pages[page]);
      source.clear();
      present[page] = true;
      lastWrittenPage = page;
      return StatusCode.OK;
    }

    private int page(long filePosition) {
      long offset = filePosition - SqlMaterializedPageMapping.FILE_HEADER_BYTES;
      if (offset < 0 || offset % pageBytes != 0) return -1;
      long page = offset / pageBytes;
      return page > Integer.MAX_VALUE ? -1 : (int) page;
    }
  }
}
