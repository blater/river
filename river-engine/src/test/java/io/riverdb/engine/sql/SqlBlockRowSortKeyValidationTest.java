package io.riverdb.engine.sql;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.riverdb.base.error.StatusCode;
import io.riverdb.base.type.SqlTypeDescriptor;
import java.nio.ByteBuffer;
import org.junit.jupiter.api.Test;

final class SqlBlockRowSortKeyValidationTest {
  @Test
  void acceptsExactMultipartShape() {
    SqlBlockRowSortKeyCodec shape = shape();
    ByteBuffer key = ByteBuffer.allocate(1 + Long.BYTES + 1 + Integer.BYTES + 3);
    key.put((byte) 1).putLong(42).put((byte) 1).putInt(3).put((byte) 'a');
    key.put((byte) 'b').put((byte) 'c').flip();
    assertEquals(StatusCode.OK, SqlBlockRowSortKeyValidation.validate(key, shape));
  }

  @Test
  void rejectsInvalidMarkersTruncationLengthsAndTrailingBytes() {
    SqlBlockRowSortKeyCodec shape = shape();
    assertCorrupt(shape, bytes(2));
    assertCorrupt(shape, bytes(1, 0, 0, 0));
    assertCorrupt(shape, numericThen(1, -1));
    assertCorrupt(shape, numericThen(1, 3, 'a'));
    assertCorrupt(shape, numericThen(0, 99));
  }

  private static SqlBlockRowSortKeyCodec shape() {
    SqlBlockSchema schema = new SqlBlockSchema();
    schema.set(2);
    schema.setColumn(0, "number", SqlTypeDescriptor.BIGINT, false);
    schema.setColumn(1, "text", SqlTypeDescriptor.varchar(32), false);
    SqlBlockRowSortKeyCodec shape = new SqlBlockRowSortKeyCodec(null);
    if (!shape.beginTuple(schema, new int[] {0, 1}, new boolean[] {false, true}, 2)) {
      throw new AssertionError("shape rejected");
    }
    return shape;
  }

  private static void assertCorrupt(SqlBlockRowSortKeyCodec shape, ByteBuffer key) {
    assertEquals(StatusCode.CORRUPTION, SqlBlockRowSortKeyValidation.validate(key, shape));
  }

  private static ByteBuffer bytes(int... values) {
    ByteBuffer bytes = ByteBuffer.allocate(values.length);
    for (int value : values) bytes.put((byte) value);
    return bytes.flip();
  }

  private static ByteBuffer numericThen(int marker, int length, int... text) {
    ByteBuffer bytes = ByteBuffer.allocate(1 + Long.BYTES + 1 + Integer.BYTES + text.length);
    bytes.put((byte) 1).putLong(7).put((byte) marker).putInt(length);
    for (int value : text) bytes.put((byte) value);
    return bytes.flip();
  }
}
