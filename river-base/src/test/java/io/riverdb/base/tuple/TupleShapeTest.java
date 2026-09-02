package io.riverdb.base.tuple;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import io.riverdb.base.error.StatusCode;
import io.riverdb.base.sql.SqlShapeLimits;
import io.riverdb.base.type.SqlTypeDescriptor;
import org.junit.jupiter.api.Test;

final class TupleShapeTest {
  @Test
  void ownsExactDescriptorsAndHashesAllParts() {
    int[] descriptors = {SqlTypeDescriptor.BIGINT, SqlTypeDescriptor.varchar(12)};
    TupleShape.Result result = new TupleShape.Result();
    assertEquals(StatusCode.OK, TupleShape.create(descriptors, result));
    TupleShape first = result.value();
    descriptors[1] = SqlTypeDescriptor.BOOLEAN;
    assertEquals(SqlTypeDescriptor.varchar(12), first.descriptorAt(1));
    assertEquals(StatusCode.OK, TupleShape.create(descriptors, result));
    assertNotEquals(first.descriptorHash(), result.value().descriptorHash());
  }

  @Test
  void admitsGenericMaximumButRejectsOneMore() {
    int[] maximum = new int[SqlShapeLimits.MAX_TUPLE_PARTS];
    java.util.Arrays.fill(maximum, SqlTypeDescriptor.BOOLEAN);
    TupleShape.Result result = new TupleShape.Result();
    assertEquals(StatusCode.OK, TupleShape.create(maximum, result));
    assertEquals(SqlShapeLimits.MAX_TUPLE_PARTS, result.value().partCount());
    assertEquals(
        StatusCode.RESOURCE_EXHAUSTED,
        TupleShape.create(new int[SqlShapeLimits.MAX_TUPLE_PARTS + 1], result));
  }

  @Test
  void computesCanonicalMaximumForFixedTextAndVarintArity() {
    TupleShape.Result result = new TupleShape.Result();
    assertEquals(StatusCode.OK, TupleShape.create(new int[] {
      SqlTypeDescriptor.BIGINT,
      SqlTypeDescriptor.BOOLEAN,
      SqlTypeDescriptor.DATE,
      SqlTypeDescriptor.varchar(3)
    }, result));
    assertEquals(51, result.value().maximumEncodedBytes());
    assertEquals(59, result.value().maximumPhysicalEncodedBytes());
    assertEquals(3, TupleEncodingSize.headerBytes(4));
    assertEquals(18, TupleEncodingSize.maximumPartBytes(SqlTypeDescriptor.varchar(3)));
    assertEquals(18, TupleEncodingSize.maximumPartBytes(SqlTypeDescriptor.decimal(38, 4)));
    assertEquals(18, TupleEncodingSize.maximumPartBytes(SqlTypeDescriptor.decimal(8, 2)));

    int[] descriptors = new int[128];
    java.util.Arrays.fill(descriptors, SqlTypeDescriptor.BOOLEAN);
    assertEquals(StatusCode.OK, TupleShape.create(descriptors, result));
    assertEquals(4 + 128 * 10, result.value().maximumEncodedBytes());
  }

  @Test
  void admitsAnEmptyNonKeyShapeWithoutInventingTupleBytes() {
    TupleShape.Result result = new TupleShape.Result();
    assertEquals(StatusCode.OK, TupleShape.create(new int[0], result));
    assertEquals(0, result.value().partCount());
    assertEquals(0, result.value().maximumEncodedBytes());
    assertEquals(0, result.value().maximumPhysicalEncodedBytes());
  }
}
