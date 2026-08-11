package io.riverdb.base.error;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;

final class StatusDetailTest {
  @Test
  void stableCodesAreUniqueAndRetryPolicyIsCodeSpecific() {
    Set<Integer> stableCodes = new HashSet<>();
    for (StatusCode code : StatusCode.values()) {
      assertTrue(stableCodes.add(code.stableCode()), () -> "duplicate code " + code.stableCode());
      boolean expectedRetryable = switch (code) {
        case RETRY, CONFLICT -> true;
        case OK, FENCED, CLOSED, CANCELLED, INVALID_EXTERNAL_INPUT,
            CARDINALITY_VIOLATION, NOT_OWNER,
            RESOURCE_EXHAUSTED, QUERY_TOO_COMPLEX, TIMEOUT, IO_FAILURE, CORRUPTION,
            INVARIANT_BROKEN -> false;
      };
      assertEquals(expectedRetryable, code.isRetryable(), code.name());
    }

    assertEquals(StatusFamily.OK, StatusCode.OK.family());
    assertTrue(StatusCode.OK.isOk());
    assertTrue(StatusCode.RETRY.isRetryable());
    assertTrue(StatusCode.CONFLICT.isRetryable());
    assertFalse(StatusCode.CLOSED.isRetryable());
    assertFalse(StatusCode.NOT_OWNER.isRetryable());
    assertTrue(StatusCode.CORRUPTION.isFatal());
    assertTrue(StatusCode.INVARIANT_BROKEN.isFatal());
    assertFalse(StatusCode.IO_FAILURE.isFatal());
    assertNotEquals(StatusCode.CORRUPTION.stableCode(), StatusCode.IO_FAILURE.stableCode());
  }

  @Test
  void detailIsResetAndReusedByItsCaller() {
    StatusDetail detail = new StatusDetail(32);

    assertSame(
        detail,
        detail.set(StatusCode.IO_FAILURE).append("file=").append(12).append(" errno=").append(-5));
    assertEquals(StatusCode.IO_FAILURE, detail.code());
    assertEquals("file=12 errno=-5", detail.asString());
    assertFalse(detail.truncated());

    assertSame(detail, detail.reset());
    assertEquals(StatusCode.OK, detail.code());
    assertEquals(0, detail.length());
    assertFalse(detail.truncated());
  }

  @Test
  void detailTruncatesAtItsFixedCapacity() {
    StatusDetail detail = new StatusDetail(5);
    detail.set(StatusCode.INVALID_EXTERNAL_INPUT).append("abcdef");

    assertEquals("abcde", detail.asString());
    assertEquals(5, detail.length());
    assertTrue(detail.truncated());
  }

  @Test
  void zeroAndExactCapacityHaveUnambiguousTruncationSemantics() {
    StatusDetail empty = new StatusDetail(0);
    empty.append('x');
    assertEquals(0, empty.length());
    assertTrue(empty.truncated());

    StatusDetail exact = new StatusDetail(3);
    exact.append("123");
    assertEquals("123", exact.asString());
    assertFalse(exact.truncated());
    exact.append('4');
    assertEquals("123", exact.asString());
    assertTrue(exact.truncated());
  }

  @Test
  void detailSupportsPartialNumbersCopiesAndBounds() {
    StatusDetail detail = new StatusDetail(4).append(-12345);
    assertEquals("-123", detail.asString());
    assertTrue(detail.truncated());

    char[] destination = new char[8];
    assertEquals(4, detail.copyTo(destination, 2));
    assertEquals('-', destination[2]);
    assertEquals('3', destination[5]);
    assertThrows(IndexOutOfBoundsException.class, () -> detail.charAt(-1));
    assertThrows(IndexOutOfBoundsException.class, () -> detail.charAt(4));
    assertThrows(IndexOutOfBoundsException.class, () -> detail.subSequence(3, 2));
  }

  @Test
  void primitiveAppendHandlesLongBoundsWithoutFormattingObjects() {
    StatusDetail detail = new StatusDetail(64);
    detail.append(Long.MIN_VALUE).append(',').append(Long.MAX_VALUE);
    assertEquals("-9223372036854775808,9223372036854775807", detail.asString());
  }

  @Test
  void coldExceptionCopiesTheBoundaryDetail() {
    StatusDetail detail = new StatusDetail(16).set(StatusCode.CONFLICT).append("write conflict");
    RiverException exception = new RiverException(detail, "40001");
    detail.reset();

    assertEquals(StatusCode.CONFLICT, exception.statusCode());
    assertEquals("40001", exception.sqlState());
    assertEquals("write conflict", exception.getMessage());
    assertThrows(IllegalArgumentException.class, () -> new StatusDetail(-1));
  }
}
