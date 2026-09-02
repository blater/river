package io.riverdb.engine.sql;

import io.riverdb.base.error.StatusCode;
import io.riverdb.base.text.Utf8Text;
import io.riverdb.base.type.SqlValueDomain;
import io.riverdb.base.type.SqlTypeDescriptor;
import io.riverdb.engine.relational.TableDefinition;
import io.riverdb.storage.heap.HeapRowResult;
import java.nio.ByteBuffer;

/** Copies one validated physical row into synchronous block evaluator scratch. */
final class SqlBlockPhysicalRowDecoding {
  private final SqlRetainedArrayAllocator allocator;
  private ByteBuffer utf8;

  SqlBlockPhysicalRowDecoding() { this(SqlRetainedArrayAllocator.STANDARD); }

  SqlBlockPhysicalRowDecoding(SqlRetainedArrayAllocator retainedAllocator) {
    allocator = retainedAllocator;
  }

  StatusCode prepare(TableDefinition table, SqlBlockRow destination) {
    if (table == null || destination == null) return StatusCode.INVALID_EXTERNAL_INPUT;
    StatusCode status = destination.reset(table.columnCount());
    if (!status.isOk()) return status;
    boolean text = false;
    for (int column = 0; column < table.columnCount(); column++) {
      if (!table.isVarchar(column)) continue;
      text = true;
    }
    if (!text || utf8 != null) return StatusCode.OK;
    try {
      ByteBuffer next = allocator.direct(Utf8Text.MAXIMUM_BYTES);
      utf8 = next;
      return StatusCode.OK;
    } catch (OutOfMemoryError error) {
      return StatusCode.RESOURCE_EXHAUSTED;
    }
  }

  StatusCode read(
      long primaryKey,
      HeapRowResult source,
      TableDefinition table,
      SqlBlockRow destination) {
    if (source == null || table == null
        || source.length() < table.fixedRowBytes()
        || source.length() > table.maximumRowBytes()) return StatusCode.CORRUPTION;
    StatusCode admitted = destination.reset(table.columnCount());
    if (!admitted.isOk()) return admitted;
    destination.setKey(primaryKey);
    destination.setValue(0, primaryKey);
    for (int column = 0; column < table.columnCount(); column++) {
      if (SqlPhysicalRowNulls.get(source, table, column) && !table.isNullable(column)) {
        return StatusCode.CORRUPTION;
      }
    }
    for (int column = 1; column < table.columnCount(); column++) {
      if (SqlPhysicalRowNulls.get(source, table, column)) {
        destination.setNull(column);
        continue;
      }
      long value = source.getLong(table.valueOffset(column));
      if (!table.isVarchar(column)) {
        int descriptor = table.typeDescriptor(column);
        if (SqlTypeDescriptor.isWideDecimal(descriptor)) {
          long high = source.getLong(table.highValueOffset(column));
          if (!io.riverdb.base.type.ExactDecimal128.fits(
              high, value, SqlTypeDescriptor.parameterOne(descriptor))) {
            return StatusCode.CORRUPTION;
          }
          destination.setDecimal128(column, high, value);
          continue;
        }
        if (!SqlValueDomain.validFixed(descriptor, value)) {
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
      if (utf8 == null) return StatusCode.INVARIANT_BROKEN;
      if (length == 0) {
        destination.setValue(column, 0);
        destination.setTextLength(column, 0);
        continue;
      }
      StatusCode prepared = destination.prepareText(column, length);
      if (!prepared.isOk()) return prepared;
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
      destination.setTextLength(column, characters);
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
