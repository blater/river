package io.riverdb.engine.sql;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.riverdb.base.error.StatusCode;
import io.riverdb.base.type.SqlTypeDescriptor;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

final class SqlPredicateOperandTest {
  @Test
  void borrowsTrustedUtf8AndWalksSupplementaryCharactersOnDemand() {
    SqlPredicateOperand operand = new SqlPredicateOperand();
    String maximum = "😀".repeat(255);
    byte[] encoded = maximum.getBytes(StandardCharsets.UTF_8);
    assertEquals(1_020, encoded.length);
    assertEquals(
        StatusCode.OK,
        operand.setUtf8(encoded, 0, encoded.length, SqlTypeDescriptor.varchar(255)));
    assertEquals(true, operand.hasBorrowedUtf8());
    assertEquals(510, operand.textLength());
    assertEquals(maximum.charAt(0), operand.textCharacter(0));
    assertEquals(maximum.charAt(509), operand.textCharacter(509));

    assertEquals(StatusCode.CORRUPTION,
        operand.setUtf8(encoded, encoded.length - 1, 2, SqlTypeDescriptor.varchar(255)));
    assertEquals(0, operand.textLength());

    byte[] reused = "river".getBytes(StandardCharsets.UTF_8);
    assertEquals(
        StatusCode.OK,
        operand.setUtf8(reused, 0, reused.length, SqlTypeDescriptor.varchar(5)));
    assertEquals(5, operand.textLength());
    operand.clear();
    assertEquals(0, operand.textLength());
  }

  @Test
  void comparesBorrowedUtf8InUnicodeScalarOrder() {
    SqlPredicateOperand left = new SqlPredicateOperand();
    SqlPredicateOperand right = new SqlPredicateOperand();
    byte[] smaller = "多🌊".getBytes(StandardCharsets.UTF_8);
    byte[] larger = "多🙂".getBytes(StandardCharsets.UTF_8);
    int type = SqlTypeDescriptor.varchar(2);
    assertEquals(StatusCode.OK, left.setUtf8(smaller, 0, smaller.length, type));
    assertEquals(StatusCode.OK, right.setUtf8(larger, 0, larger.length, type));
    assertTrue(SqlBooleanTextComparator.compare(left, right) < 0);
    assertTrue(SqlBooleanTextComparator.compare(right, left) > 0);
    assertEquals(StatusCode.OK, right.setUtf8(smaller, 0, smaller.length, type));
    assertEquals(0, SqlBooleanTextComparator.compare(left, right));
  }
}
