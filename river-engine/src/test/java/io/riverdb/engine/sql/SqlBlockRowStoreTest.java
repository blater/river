package io.riverdb.engine.sql;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.riverdb.base.error.StatusCode;
import io.riverdb.base.type.SqlTypeDescriptor;
import org.junit.jupiter.api.Test;

final class SqlBlockRowStoreTest {
  @Test
  void textCopyReturnsResourcePressureAndCanRetry() {
    SqlBlockRow source = new SqlBlockRow();
    assertEquals(StatusCode.OK, source.reset(1));
    char[] text = "retained".toCharArray();
    assertEquals(StatusCode.OK, source.setText(0, text, 0, text.length));
    FailingCharacters allocator = new FailingCharacters();
    SqlBlockRow target = new SqlBlockRow(allocator);
    allocator.fail = true;
    assertEquals(StatusCode.RESOURCE_EXHAUSTED, target.copyFrom(source));
    allocator.fail = false;
    assertEquals(StatusCode.OK, target.copyFrom(source));
    assertEquals(text.length, target.textLength(0));
  }

  @Test
  void codecDecodesWideUtf8IntoExactUtf16Scratch() {
    SqlBlockSchema schema = new SqlBlockSchema();
    schema.set(1);
    schema.setColumn(0, "value", SqlTypeDescriptor.varchar(8_192), false);
    char[] text = new char[7_000];
    for (int index = 0; index < 5_000; index++) text[index] = '猫';
    for (int index = 5_000; index < text.length; index += 2) {
      text[index] = Character.highSurrogate(0x1f30a);
      text[index + 1] = Character.lowSurrogate(0x1f30a);
    }
    SqlBlockRow source = new SqlBlockRow();
    SqlBlockRow decoded = new SqlBlockRow();
    assertEquals(StatusCode.OK, source.reset(1));
    assertEquals(StatusCode.OK, source.setText(0, text, 0, text.length));
    SqlBlockRowCodec codec = new SqlBlockRowCodec();

    assertEquals(StatusCode.OK, codec.encode(source, schema, 0));
    assertTrue(codec.buffer().remaining() > 8_192);
    assertEquals(StatusCode.OK, codec.decode(decoded, schema, 0));
    assertEquals(text.length, decoded.textLength(0));
    for (int index = 0; index < text.length; index++) {
      assertEquals(text[index], decoded.textCharacter(0, index));
    }
  }

  private static final class FailingCharacters extends SqlRetainedArrayAllocator {
    private boolean fail;

    @Override char[] characters(int capacity) {
      if (fail) throw new OutOfMemoryError("injected");
      return super.characters(capacity);
    }
  }
}
