package io.riverdb.engine.sql;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.riverdb.base.error.StatusCode;
import io.riverdb.base.type.SqlTypeDescriptor;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

final class SqlPredicateOperandTest {
  @Test
  void decodesMaximumSupplementaryTextAndPoisonsMalformedUtf8() {
    SqlPredicateOperand operand = new SqlPredicateOperand();
    String maximum = "😀".repeat(255);
    byte[] encoded = maximum.getBytes(StandardCharsets.UTF_8);
    assertEquals(1_020, encoded.length);
    assertEquals(
        StatusCode.OK,
        operand.setUtf8(encoded, 0, encoded.length, SqlTypeDescriptor.varchar(255)));
    assertEquals(510, operand.textLength());
    assertEquals(maximum.charAt(0), operand.textCharacter(0));
    assertEquals(maximum.charAt(509), operand.textCharacter(509));

    assertEquals(
        StatusCode.CORRUPTION,
        operand.setUtf8(
            new byte[] {(byte) 0xf0, (byte) 0x9f},
            0,
            2,
            SqlTypeDescriptor.varchar(255)));
    assertEquals(0, operand.textLength());
    assertEquals(
        StatusCode.CORRUPTION,
        operand.setUtf8(
            new byte[] {(byte) 0xc0, (byte) 0x80},
            0,
            2,
            SqlTypeDescriptor.varchar(255)));
    assertEquals(0, operand.textLength());

    byte[] exhausted = "x".repeat(511).getBytes(StandardCharsets.UTF_8);
    assertEquals(
        StatusCode.CORRUPTION,
        operand.setUtf8(
            exhausted,
            0,
            exhausted.length,
            SqlTypeDescriptor.varchar(255)));
    assertEquals(0, operand.textLength());

    byte[] reused = "river".getBytes(StandardCharsets.UTF_8);
    assertEquals(
        StatusCode.OK,
        operand.setUtf8(reused, 0, reused.length, SqlTypeDescriptor.varchar(5)));
    assertEquals(5, operand.textLength());
    operand.clear();
    assertEquals(0, operand.textLength());
  }
}
