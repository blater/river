package io.riverdb.engine.sql;

import io.riverdb.base.error.StatusCode;
import io.riverdb.base.text.Utf8Text;
import io.riverdb.base.type.SqlValueDomain;
import io.riverdb.base.type.SqlTypeDescriptor;
import io.riverdb.engine.relational.TableDefinition;
import io.riverdb.storage.heap.HeapRowResult;
import java.nio.ByteBuffer;

/** Copies one validated physical row into synchronous block evaluator scratch. */
final class SqlBlockPhysicalRowReader {
  private ByteBuffer utf8;

  StatusCode read(
      long primaryKey,
      HeapRowResult source,
      TableDefinition table,
      SqlBlockRow destination) {
    if (source == null || table == null
        || source.length() < table.fixedRowBytes()
        || source.length() > table.maximumRowBytes()) return StatusCode.CORRUPTION;
    destination.reset(table.columnCount());
    destination.setValue(0, primaryKey);
    long nullMask = source.getLong(table.nullMaskOffset());
    if ((nullMask & ~((1L << table.columnCount()) - 1)) != 0) {
      return StatusCode.CORRUPTION;
    }
    for (int column = 0; column < table.columnCount(); column++) {
      if ((nullMask & 1L << column) != 0 && !table.isNullable(column)) {
        return StatusCode.CORRUPTION;
      }
    }
    for (int column = 1; column < table.columnCount(); column++) {
      if ((nullMask & 1L << column) != 0) {
        destination.setNull(column);
        continue;
      }
      long value = source.getLong((column - 1) * Long.BYTES);
      if (!table.isVarchar(column)) {
        if (!SqlValueDomain.validFixed(table.typeDescriptor(column), value)) {
          return StatusCode.CORRUPTION;
        }
        destination.setValue(column, value);
        continue;
      }
      int offset = (int) (value >>> 32);
      int length = (int) value;
      if (offset < table.fixedRowBytes()
          || length < 0
          || length > Utf8Text.MAXIMUM_BYTES
          || offset > source.length() - length) return StatusCode.CORRUPTION;
      if (utf8 == null) utf8 = ByteBuffer.allocateDirect(Utf8Text.MAXIMUM_BYTES);
      utf8.clear();
      for (int index = 0; index < length; index++) {
        utf8.put(source.getByte(offset + index));
      }
      utf8.flip();
      if (Utf8Text.validate(
          utf8,
          0,
          length,
          SqlTypeDescriptor.parameterOne(table.typeDescriptor(column))) < 0) {
        return StatusCode.CORRUPTION;
      }
      int characters = Utf8Text.decode(
          utf8, 0, length, destination.text(column), 0);
      if (characters < 0) return StatusCode.CORRUPTION;
      destination.setValue(column, 0);
      destination.setText(column, destination.text(column), 0, characters);
    }
    return StatusCode.OK;
  }

  void reset() {
    if (utf8 == null) return;
    utf8.clear();
    for (int index = 0; index < utf8.capacity(); index++) utf8.put(index, (byte) 0);
    utf8.clear();
  }
}
