package io.riverdb.base.concurrent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.riverdb.base.error.StatusCode;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

final class GuardsTest {
  @Test
  void cancellationIsCooperativeAndReusable() {
    MutableCancellationToken token = new MutableCancellationToken();
    assertEquals(StatusCode.OK, token.status());
    token.cancel();
    assertEquals(StatusCode.CANCELLED, token.status());
    token.reset();
    assertEquals(StatusCode.OK, token.status());
    assertEquals(StatusCode.OK, CancellationToken.NONE.status());
  }

  @Test
  void closeGuardReportsExpectedMisuseAsStatus() {
    CloseGuard guard = CloseGuard.enabled();
    assertEquals(StatusCode.OK, guard.checkOpen());
    assertEquals(StatusCode.OK, guard.close());
    assertTrue(guard.isClosed());
    assertEquals(StatusCode.CLOSED, guard.checkOpen());
    assertEquals(StatusCode.CLOSED, guard.close());
  }

  @Test
  void disabledCloseGuardIsSharedSingletonAndNoOp() {
    CloseGuard first = CloseGuard.disabled();
    CloseGuard second = CloseGuard.disabled();
    assertSame(first, second);
    assertEquals(StatusCode.OK, first.close());
    assertFalse(first.isClosed());
  }

  @Test
  void ownershipGuardMakesTransferAndReleaseExplicit() {
    OwnershipGuard guard = OwnershipGuard.ownedBy(10);
    assertEquals(StatusCode.OK, guard.checkOwnedBy(10));
    assertEquals(StatusCode.OK, guard.transfer(10, 20));
    assertEquals(StatusCode.NOT_OWNER, guard.checkOwnedBy(10));
    assertEquals(StatusCode.OK, guard.release(20));
    assertTrue(guard.isReleased());
    assertEquals(StatusCode.NOT_OWNER, guard.release(20));
    assertEquals(StatusCode.NOT_OWNER, guard.transfer(20, 30));
    assertTrue(guard.isReleased());
  }

  @Test
  void releasedOwnerTokenIsAlwaysRejectedAndCannotResurrectOwnership() {
    OwnershipGuard guard = OwnershipGuard.ownedBy(10);
    assertEquals(StatusCode.OK, guard.release(10));

    assertEquals(StatusCode.INVARIANT_BROKEN, guard.checkOwnedBy(OwnershipGuard.RELEASED));
    assertEquals(
        StatusCode.INVARIANT_BROKEN,
        guard.transfer(OwnershipGuard.RELEASED, 20));
    assertEquals(
        StatusCode.INVARIANT_BROKEN,
        guard.transfer(20, OwnershipGuard.RELEASED));
    assertEquals(StatusCode.INVARIANT_BROKEN, guard.release(OwnershipGuard.RELEASED));
    assertTrue(guard.isReleased());
  }

  @Test
  void transferAndReleaseRaceHasOneWinnerAndReleaseIsIrreversible() throws Exception {
    try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
      for (int iteration = 0; iteration < 100; iteration++) {
        OwnershipGuard guard = OwnershipGuard.ownedBy(10);
        CountDownLatch start = new CountDownLatch(1);
        Future<StatusCode> transfer = executor.submit(() -> {
          start.await();
          return guard.transfer(10, 20);
        });
        Future<StatusCode> release = executor.submit(() -> {
          start.await();
          return guard.release(10);
        });

        start.countDown();
        StatusCode transferStatus = transfer.get();
        StatusCode releaseStatus = release.get();
        assertTrue(
            transferStatus == StatusCode.OK && releaseStatus == StatusCode.NOT_OWNER
                || transferStatus == StatusCode.NOT_OWNER && releaseStatus == StatusCode.OK);

        if (guard.isReleased()) {
          assertEquals(StatusCode.NOT_OWNER, guard.transfer(10, 30));
          assertEquals(StatusCode.INVARIANT_BROKEN, guard.transfer(0, 30));
          assertTrue(guard.isReleased());
        } else {
          assertEquals(StatusCode.OK, guard.checkOwnedBy(20));
        }
      }
    }
  }

  @Test
  void fatalFenceRetainsContextualIoCauseWithoutGloballyMakingIoFatal() {
    FatalState fence = new FatalStateFence();
    assertEquals(StatusCode.OK, fence.admissionStatus());
    assertFalse(StatusCode.IO_FAILURE.isFatal());
    assertEquals(StatusCode.OK, fence.fence(StatusCode.IO_FAILURE));
    assertTrue(fence.isFenced());
    assertEquals(StatusCode.FENCED, fence.admissionStatus());
    assertEquals(StatusCode.IO_FAILURE, fence.fatalStatus());

    assertEquals(StatusCode.OK, fence.fence(StatusCode.IO_FAILURE));
    assertEquals(StatusCode.FENCED, fence.fence(StatusCode.CORRUPTION));
    assertEquals(StatusCode.IO_FAILURE, fence.fatalStatus());
  }

  @Test
  void fencingWithOkIsInternalMisuseAndFailsSafe() {
    FatalState fence = new FatalStateFence();
    assertEquals(StatusCode.INVARIANT_BROKEN, fence.fence(StatusCode.OK));
    assertEquals(StatusCode.INVARIANT_BROKEN, fence.fence(StatusCode.OK));
    assertEquals(StatusCode.INVARIANT_BROKEN, fence.fatalStatus());
    assertEquals(StatusCode.FENCED, fence.admissionStatus());
  }

  @Test
  void concurrentFatalCausesAreFirstWinsAndVisibleToAdmission() throws Exception {
    try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
      for (int iteration = 0; iteration < 100; iteration++) {
        FatalState fence = new FatalStateFence();
        CountDownLatch start = new CountDownLatch(1);
        Future<StatusCode> io = executor.submit(() -> {
          start.await();
          return fence.fence(StatusCode.IO_FAILURE);
        });
        Future<StatusCode> corruption = executor.submit(() -> {
          start.await();
          return fence.fence(StatusCode.CORRUPTION);
        });

        start.countDown();
        StatusCode ioResult = io.get();
        StatusCode corruptionResult = corruption.get();
        assertTrue(
            ioResult == StatusCode.OK && corruptionResult == StatusCode.FENCED
                || ioResult == StatusCode.FENCED && corruptionResult == StatusCode.OK);

        StatusCode winner = fence.fatalStatus();
        assertTrue(winner == StatusCode.IO_FAILURE || winner == StatusCode.CORRUPTION);
        assertEquals(StatusCode.FENCED, fence.admissionStatus());
        assertTrue(fence.isFenced());
      }
    }
  }

  @Test
  void admissionObserverSeesFenceCausePublishedBeforeFencedState() throws Exception {
    FatalState fence = new FatalStateFence();
    try (ExecutorService executor = Executors.newSingleThreadExecutor()) {
      Future<StatusCode> observedCause = executor.submit(() -> {
        while (fence.admissionStatus() == StatusCode.OK) {
          Thread.onSpinWait();
        }
        return fence.fatalStatus();
      });

      assertEquals(StatusCode.OK, fence.fence(StatusCode.IO_FAILURE));
      assertEquals(StatusCode.IO_FAILURE, observedCause.get(2, TimeUnit.SECONDS));
    }
  }
}
