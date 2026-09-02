package io.riverdb.engine.sql;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.riverdb.base.error.StatusCode;
import io.riverdb.base.type.ExactDecimal128;
import io.riverdb.base.type.SqlTypeDescriptor;
import java.math.BigInteger;
import org.junit.jupiter.api.Test;

final class SqlJoinHashKeyTest {
  private final SqlJoinHashKey keys = new SqlJoinHashKey();
  private final SqlBlockRow left = new SqlBlockRow();
  private final SqlBlockRow right = new SqlBlockRow();

  @Test
  void canonicalizesDecimal128AcrossScalesAndExactTypes() {
    BigInteger integer = new BigInteger("1234567890123456789012345678901234567");
    int scaleZero = SqlTypeDescriptor.decimal(37, 0);
    int scaleOne = SqlTypeDescriptor.decimal(38, 1);
    setWide(left, integer);
    setWide(right, integer.multiply(BigInteger.TEN));

    assertExactEqual(scaleZero, scaleOne);
    assertEquals(
        hash(left, scaleZero, scaleOne),
        hash(right, scaleOne, scaleZero));

    int scaleEighteen = SqlTypeDescriptor.decimal(38, 18);
    setWide(left, BigInteger.valueOf(42).multiply(BigInteger.TEN.pow(18)));
    setFixed(right, 42);
    assertExactEqual(scaleEighteen, SqlTypeDescriptor.BIGINT);
    assertEquals(
        hash(left, scaleEighteen, SqlTypeDescriptor.BIGINT),
        hash(right, SqlTypeDescriptor.BIGINT, scaleEighteen));
  }

  @Test
  void canonicalizesNumericFamiliesAndSignedZero() {
    setFixed(left, 15);
    setFixed(right, Double.doubleToRawLongBits(1.5d));
    assertEquals(
        hash(left, SqlTypeDescriptor.decimal(2, 1), SqlTypeDescriptor.DOUBLE),
        hash(right, SqlTypeDescriptor.DOUBLE, SqlTypeDescriptor.decimal(2, 1)));

    setFixed(left, Integer.toUnsignedLong(Float.floatToRawIntBits(1.5f)));
    assertEquals(
        hash(left, SqlTypeDescriptor.REAL, SqlTypeDescriptor.DOUBLE),
        hash(right, SqlTypeDescriptor.DOUBLE, SqlTypeDescriptor.REAL));

    setFixed(left, Integer.toUnsignedLong(Float.floatToRawIntBits(-0.0f)));
    setFixed(right, Double.doubleToRawLongBits(0.0d));
    assertEquals(
        hash(left, SqlTypeDescriptor.REAL, SqlTypeDescriptor.DOUBLE),
        hash(right, SqlTypeDescriptor.DOUBLE, SqlTypeDescriptor.REAL));
  }

  @Test
  void followsCompactExactApproximateComparisonRounding() {
    int decimal = SqlTypeDescriptor.decimal(18, 1);
    long unscaled = 360_287_970_189_639_650L;
    double compared = (double) unscaled / 10.0d;
    setFixed(left, unscaled);
    setFixed(right, Double.doubleToRawLongBits(compared));

    assertEquals(
        0,
        SqlNumericComparison.compare(
            0, left.value(0), decimal,
            0, right.value(0), SqlTypeDescriptor.DOUBLE,
            new ExactDecimal128.Scratch()));
    assertEquals(
        hash(left, decimal, SqlTypeDescriptor.DOUBLE),
        hash(right, SqlTypeDescriptor.DOUBLE, decimal));
  }

  @Test
  void preservesCompactExactEqualityBeyondBinary64Precision() {
    int scaleOne = SqlTypeDescriptor.decimal(18, 1);
    int scaleTwo = SqlTypeDescriptor.decimal(18, 2);
    setFixed(left, 63_238_467_486_136_620L);
    setFixed(right, 632_384_674_861_366_200L);

    assertExactEqual(scaleOne, scaleTwo);
    assertEquals(
        hash(left, scaleOne, scaleTwo),
        hash(right, scaleTwo, scaleOne));
  }

  @Test
  void hashesTextByUnicodeScalarIndependentOfDeclaredWidth() {
    String text = "A\ud83c\udf0a\ud800\udf48";
    setText(left, text);
    setText(right, text);

    assertEquals(
        hash(left, SqlTypeDescriptor.varchar(3)),
        hash(right, SqlTypeDescriptor.varchar(65_535)));
    assertEquals(scalarHash(text), hash(left, SqlTypeDescriptor.varchar(3)));
  }

  private void assertExactEqual(int leftDescriptor, int rightDescriptor) {
    assertEquals(
        0,
        SqlNumericComparison.compare(
            left.highValue(0), left.value(0), leftDescriptor,
            right.highValue(0), right.value(0), rightDescriptor,
            new ExactDecimal128.Scratch()));
  }

  private long hash(SqlBlockRow row, int descriptor) {
    return keys.decoded(row, 0, descriptor);
  }

  private long hash(SqlBlockRow row, int descriptor, int comparedDescriptor) {
    return keys.decoded(row, 0, descriptor, comparedDescriptor);
  }

  private static void setFixed(SqlBlockRow row, long value) {
    assertEquals(StatusCode.OK, row.reset(1));
    row.setValue(0, value);
  }

  private static void setWide(SqlBlockRow row, BigInteger value) {
    assertEquals(StatusCode.OK, row.reset(1));
    row.setDecimal128(0, value.shiftRight(64).longValue(), value.longValue());
  }

  private static void setText(SqlBlockRow row, String value) {
    assertEquals(StatusCode.OK, row.reset(1));
    char[] characters = value.toCharArray();
    assertEquals(StatusCode.OK, row.setText(0, characters, 0, characters.length));
  }

  private static long scalarHash(String value) {
    long hash = 0xcbf29ce484222325L;
    for (int index = 0; index < value.length();) {
      int scalar = value.codePointAt(index);
      hash = (hash ^ scalar) * 0x100000001b3L;
      index += Character.charCount(scalar);
    }
    return hash;
  }
}
