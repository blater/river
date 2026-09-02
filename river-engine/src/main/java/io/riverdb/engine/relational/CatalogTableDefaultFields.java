package io.riverdb.engine.relational;

import io.riverdb.base.type.SqlTypeDescriptor;
import java.nio.ByteBuffer;

/** Writes canonical default fields and any trailing default text. */
final class CatalogTableDefaultFields {
  private CatalogTableDefaultFields() { }

  static boolean write(
      ByteBuffer target, TableSchema schema, int column, boolean present) {
    boolean text = present && SqlTypeDescriptor.typeId(schema.typeDescriptor(column))
        == SqlTypeDescriptor.TYPE_ID_VARCHAR;
    long value = schema.defaultValue(column);
    target.put((byte) (present ? schema.defaultKind(column) : 0));
    target.putLong(present && !text ? value : 0);
    target.putInt(text ? (int) value : 0);
    return text;
  }

  static boolean write(
      ByteBuffer target, TableDefinition schema, int column, boolean present) {
    boolean text = present && schema.isVarchar(column);
    target.put((byte) (present ? schema.defaultKind(column) : 0));
    target.putLong(present && !text ? schema.defaultValue(column) : 0);
    target.putInt(text ? schema.defaultTextLength(column) : 0);
    return text;
  }

  static void writeText(
      ByteBuffer target, TableSchema schema, int column, boolean text) {
    if (!text) return;
    long handle = schema.defaultValue(column);
    int offset = (int) (handle >>> 32);
    int length = (int) handle;
    for (int index = 0; index < length; index++) {
      target.put(schema.defaultTextByte(offset + index));
    }
  }

  static void writeText(
      ByteBuffer target, TableDefinition schema, int column, boolean text) {
    if (text) schema.copyDefaultText(column, target);
  }
}
