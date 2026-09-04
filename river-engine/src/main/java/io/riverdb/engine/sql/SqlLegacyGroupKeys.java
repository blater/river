package io.riverdb.engine.sql;

import io.riverdb.base.error.StatusCode;
import io.riverdb.base.text.Utf8Text;
import io.riverdb.base.type.SqlNumericTypeRules;
import io.riverdb.base.type.SqlNumericValue;
import io.riverdb.base.type.SqlTypeDescriptor;
import io.riverdb.engine.relational.TableSchema;
import io.riverdb.storage.heap.HeapRowResult;
import java.nio.ByteBuffer;

/** Retained current, active, and lookahead grouping tuples for legacy scans. */
final class SqlLegacyGroupKeys {
  private final SqlRetainedArrayAllocator allocator;
  private final SqlBlockRow current;
  private final SqlBlockRow group;
  private final SqlBlockRow lookahead;
  private BoundSqlStatement bound;
  private ByteBuffer text;
  private int count;

  SqlLegacyGroupKeys() { this(SqlRetainedArrayAllocator.STANDARD); }

  SqlLegacyGroupKeys(SqlRetainedArrayAllocator retainedAllocator) {
    allocator = retainedAllocator;
    current = new SqlBlockRow(allocator);
    group = new SqlBlockRow(allocator);
    lookahead = new SqlBlockRow(allocator);
  }

  StatusCode prepare(BoundSqlStatement statement, int keys) {
    bound = statement;
    count = keys;
    StatusCode status = current.reset(keys);
    if (status.isOk()) status = group.reset(keys);
    if (status.isOk()) status = lookahead.reset(keys);
    boolean textual = false;
    for (int key = 0; status.isOk() && key < keys; key++) {
      if (textKey(key)) {
        textual = true;
        status = current.prepareText(key);
        if (status.isOk()) status = group.prepareText(key);
        if (status.isOk()) status = lookahead.prepareText(key);
      }
    }
    if (status.isOk() && textual && text == null) {
      try {
        text = allocator.direct(TableSchema.MAXIMUM_ROW_BYTES);
      } catch (OutOfMemoryError error) {
        status = StatusCode.RESOURCE_EXHAUSTED;
      }
    }
    return status;
  }

  StatusCode capture(HeapRowResult source, SqlProjectedRow projected) {
    StatusCode status = current.reset(count);
    for (int key = 0; status.isOk() && key < count; key++) {
      if (projected.isNull(key)) {
        current.setNull(key);
        continue;
      }
      current.setDecimal128(
          key, projected.highValue(key), projected.value(key));
      if (textKey(key)) status = captureText(source, projected, key);
    }
    return status;
  }

  boolean sameCurrentGroup() { return same(current, group); }

  StatusCode beginGroup() { return group.copyFrom(current); }

  StatusCode rememberLookahead() { return lookahead.copyFrom(current); }

  StatusCode restoreLookahead() { return group.copyFrom(lookahead); }

  SqlBlockRow group() { return group; }

  int count() { return count; }

  private StatusCode captureText(
      HeapRowResult source, SqlProjectedRow projected, int key) {
    int column = bound.projectionPrograms.rawColumn(key);
    if (column < 0) {
      current.setText(
          key, projected.text(key), 0, projected.textLength(key));
      return current.status();
    }
    long handle = projected.value(key);
    int offset = (int) (handle >>> 32);
    int length = (int) handle;
    if (source == null || offset < bound.table.fixedRowBytes() || length < 0
        || length > text.capacity() || offset > source.length() - length) {
      return StatusCode.CORRUPTION;
    }
    text.clear();
    for (int index = 0; index < length; index++) text.put(source.getByte(offset + index));
    int characters = Utf8Text.decode(text, 0, length, current.text(key), 0);
    if (characters < 0) return StatusCode.CORRUPTION;
    current.setTextLength(key, characters);
    return StatusCode.OK;
  }

  private boolean same(SqlBlockRow left, SqlBlockRow right) {
    for (int key = 0; key < count; key++) {
      if (left.nullValue(key) != right.nullValue(key)) return false;
      if (left.nullValue(key)) continue;
      int descriptor = bound.projectionPrograms.resultDescriptor(key);
      if (textKey(key)) {
        if (!sameText(left, right, key)) return false;
      } else if (SqlNumericTypeRules.isNumeric(descriptor)) {
        int comparison = SqlTypeDescriptor.isWideDecimal(descriptor)
            ? compare128(
                left.highValue(key), left.value(key),
                right.highValue(key), right.value(key))
            : SqlNumericValue.compare(
                left.value(key), descriptor, right.value(key), descriptor);
        if (comparison != 0) return false;
      } else if (left.value(key) != right.value(key)) return false;
    }
    return true;
  }

  private boolean textKey(int key) {
    return SqlTypeDescriptor.typeId(bound.projectionPrograms.resultDescriptor(key))
        == SqlTypeDescriptor.TYPE_ID_VARCHAR;
  }

  private static boolean sameText(SqlBlockRow left, SqlBlockRow right, int key) {
    int length = left.textLength(key);
    if (length != right.textLength(key)) return false;
    for (int index = 0; index < length; index++) {
      if (left.textCharacter(key, index) != right.textCharacter(key, index)) return false;
    }
    return true;
  }

  private static int compare128(
      long leftHigh, long leftLow, long rightHigh, long rightLow) {
    int high = Long.compare(leftHigh, rightHigh);
    return high != 0 ? high : Long.compareUnsigned(leftLow, rightLow);
  }
}
