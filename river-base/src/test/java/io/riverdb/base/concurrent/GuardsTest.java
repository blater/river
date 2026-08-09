package io.riverdb.base.concurrent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.riverdb.base.error.StatusCode;
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
  void disabledCloseGuardIsOneAllocationFreeSingleton() {
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
  }

  @Test
  void fatalFenceIsFirstFailureWinsAndRejectsNonFatalCodes() {
    FatalState fence = new FatalStateFence();
    assertEquals(StatusCode.OK, fence.admissionStatus());
    assertEquals(StatusCode.INVALID_EXTERNAL_INPUT, fence.fence(StatusCode.IO_FAILURE));
    assertFalse(fence.isFenced());

    assertEquals(StatusCode.OK, fence.fence(StatusCode.CORRUPTION));
    assertTrue(fence.isFenced());
    assertEquals(StatusCode.FENCED, fence.admissionStatus());
    assertEquals(StatusCode.CORRUPTION, fence.fatalStatus());

    assertEquals(StatusCode.FENCED, fence.fence(StatusCode.INVARIANT_BROKEN));
    assertEquals(StatusCode.CORRUPTION, fence.fatalStatus());
  }
}
