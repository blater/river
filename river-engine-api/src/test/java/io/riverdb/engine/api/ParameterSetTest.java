package io.riverdb.engine.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.riverdb.base.error.StatusCode;
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
        parameters.appendText(SqlTypeDescriptor.varchar(4), "A\u00e9\ud83d\ude00"));
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
    assertEquals("A\u00e9\ud83d\ude00", new String(decoded, 1, 4));
    assertEquals('x', parameters.textByteAt(3, 0));
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
}
