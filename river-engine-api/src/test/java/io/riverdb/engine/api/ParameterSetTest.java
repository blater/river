package io.riverdb.engine.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.riverdb.base.error.StatusCode;
import io.riverdb.base.sql.SqlShapeLimits;
import io.riverdb.base.type.SqlTypeDescriptor;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

final class ParameterSetTest {
  @Test
  void appendsTypedFixedNullAndCompactTextValues() {
    ParameterSet parameters = new ParameterSet(4, 32);
    assertEquals(
        StatusCode.OK,
        parameters.appendFixed(SqlTypeDescriptor.BIGINT, Long.MIN_VALUE));
    assertEquals(
        StatusCode.OK,
        parameters.appendNull(SqlTypeDescriptor.DATE));
    assertEquals(
        StatusCode.OK,
        parameters.appendText(SqlTypeDescriptor.varchar(4), "Aé😀"));
    ByteBuffer utf8 = ByteBuffer.wrap("xy".getBytes(StandardCharsets.UTF_8));
    assertEquals(
        StatusCode.OK,
        parameters.appendUtf8(SqlTypeDescriptor.varchar(2), utf8, 0, 2));

    assertEquals(4, parameters.count());
    assertEquals(Long.MIN_VALUE, parameters.valueAt(0));
    assertTrue(parameters.isNull(1));
    assertFalse(parameters.isNull(2));
    assertEquals(SqlTypeDescriptor.varchar(4), parameters.typeDescriptorAt(2));
    assertEquals(7, parameters.textLengthAt(2));
    char[] decoded = new char[8];
    assertEquals(4, parameters.copyTextAt(2, decoded, 1));
    assertEquals("Aé😀", new String(decoded, 1, 4));
    assertEquals('x', parameters.textByteAt(3, 0));
  }

  @Test
  void appendsTpcCDataWidthWithAnIntByteLength() {
    ParameterSet parameters = new ParameterSet(1, 512);
    String data = "x".repeat(500);
    assertEquals(StatusCode.OK,
        parameters.appendText(SqlTypeDescriptor.varchar(500), data));
    assertEquals(500, parameters.textLengthAt(0));
    char[] decoded = new char[500];
    assertEquals(500, parameters.copyTextAt(0, decoded, 0));
    assertEquals(data, new String(decoded));
  }

  @Test
  void rejectsInvalidDomainsAndEnforcesBothBounds() {
    ParameterSet parameters = new ParameterSet(2, 3);
    assertEquals(
        StatusCode.INVALID_EXTERNAL_INPUT,
        parameters.appendFixed(SqlTypeDescriptor.BOOLEAN, 2));
    assertEquals(
        StatusCode.STRING_DATA_RIGHT_TRUNCATION,
        parameters.appendText(SqlTypeDescriptor.varchar(1), "ab"));
    assertEquals(
        StatusCode.STRING_DATA_RIGHT_TRUNCATION,
        parameters.appendText(SqlTypeDescriptor.varchar(1), "x".repeat(256)));
    assertEquals(
        StatusCode.RESOURCE_EXHAUSTED,
        parameters.appendText(SqlTypeDescriptor.varchar(4), "four"));
    assertEquals(StatusCode.OK, parameters.appendNull(0));
    assertEquals(
        StatusCode.OK,
        parameters.appendFixed(SqlTypeDescriptor.BOOLEAN, 0));
    assertEquals(
        StatusCode.RESOURCE_EXHAUSTED,
        parameters.appendNull(SqlTypeDescriptor.BIGINT));
  }

  @Test
  void rejectsMalformedUtf8AndClearsPublishedState() {
    ParameterSet parameters = new ParameterSet(1, 256);
    ByteBuffer malformed = ByteBuffer.wrap(new byte[] {(byte) 0xc0, (byte) 0x80});
    assertEquals(
        StatusCode.INVALID_EXTERNAL_INPUT,
        parameters.appendUtf8(SqlTypeDescriptor.varchar(2), malformed, 0, 2));
    ByteBuffer tooMany = ByteBuffer.wrap("x".repeat(256).getBytes(StandardCharsets.UTF_8));
    assertEquals(
        StatusCode.STRING_DATA_RIGHT_TRUNCATION,
        parameters.appendUtf8(SqlTypeDescriptor.varchar(255), tooMany, 0, 256));
    assertEquals(StatusCode.OK, parameters.appendText(SqlTypeDescriptor.varchar(2), "ok"));
    parameters.reset();
    assertEquals(0, parameters.count());
    assertEquals(0, parameters.textBytes());
    assertEquals(-1, parameters.textLengthAt(0));
  }

  @Test
  void rejectsConfigurationBeyondThePublicBound() {
    assertThrows(
        IllegalArgumentException.class,
        () -> new ParameterSet(ParameterSet.MAXIMUM_PARAMETERS + 1, 0));
    assertThrows(
        IllegalArgumentException.class,
        () -> new ParameterSet(0, ParameterSet.MAXIMUM_TEXT_BYTES + 1));
  }

  @Test
  void admitsTheUnsignedWireParameterCountWithoutAColumnLimit() {
    ParameterSet parameters = new ParameterSet(SqlShapeLimits.MAX_PARAMETERS, 0);
    for (int index = 0; index < SqlShapeLimits.MAX_PARAMETERS; index++) {
      assertEquals(StatusCode.OK, parameters.appendFixed(SqlTypeDescriptor.BIGINT, index));
    }
    assertEquals(SqlShapeLimits.MAX_PARAMETERS, parameters.count());
    assertEquals(StatusCode.RESOURCE_EXHAUSTED,
        parameters.appendFixed(SqlTypeDescriptor.BIGINT, 0));
  }

  @Test
  void carriesEveryNumericTypeAndCanonicalizesFiniteFloatingValues() {
    ParameterSet parameters = new ParameterSet(6, 0);
    assertEquals(StatusCode.OK, parameters.appendSmallint(Short.MIN_VALUE));
    assertEquals(StatusCode.OK, parameters.appendInteger(Integer.MAX_VALUE));
    assertEquals(StatusCode.OK, parameters.appendBigint(Long.MIN_VALUE));
    assertEquals(StatusCode.OK, parameters.appendDecimal(6, 2, -12_345));
    assertEquals(StatusCode.OK, parameters.appendReal(-0.0f));
    assertEquals(StatusCode.OK, parameters.appendDouble(12.5d));

    assertEquals(Short.MIN_VALUE, parameters.smallintAt(0));
    assertEquals(Integer.MAX_VALUE, parameters.integerAt(1));
    assertEquals(Long.MIN_VALUE, parameters.bigintAt(2));
    assertEquals(-12_345, parameters.decimalUnscaledAt(3));
    assertEquals(0, parameters.valueAt(4));
    assertEquals(0.0f, parameters.realAt(4));
    assertEquals(12.5d, parameters.doubleAt(5));
    assertEquals(StatusCode.INVALID_EXTERNAL_INPUT, parameters.appendReal(Float.NaN));
    assertEquals(StatusCode.INVALID_EXTERNAL_INPUT,
        parameters.appendDouble(Double.POSITIVE_INFINITY));
  }

  @Test
  void carriesSignedDecimal128WithoutNarrowingTheExistingLane() {
    ParameterSet parameters = new ParameterSet(2, 0);
    assertEquals(StatusCode.OK, parameters.appendDecimal128(
        38, 6, 669_260_594_276_348_691L, -4_302_749_291_975_740_594L));
    assertEquals(StatusCode.OK, parameters.appendDecimal128(
        38, 6, -669_260_594_276_348_692L, 4_302_749_291_975_740_594L));

    assertEquals(0, parameters.decimalUnscaledAt(0));
    assertEquals(669_260_594_276_348_691L, parameters.decimalUnscaledHighAt(0));
    assertEquals(-4_302_749_291_975_740_594L, parameters.decimalUnscaledLowAt(0));
    assertEquals(-669_260_594_276_348_692L, parameters.decimalUnscaledHighAt(1));
    assertEquals(4_302_749_291_975_740_594L, parameters.decimalUnscaledLowAt(1));
    assertEquals(StatusCode.INVALID_EXTERNAL_INPUT,
        parameters.appendDecimal128(38, 0, Long.MAX_VALUE, Long.MAX_VALUE));
  }
}
