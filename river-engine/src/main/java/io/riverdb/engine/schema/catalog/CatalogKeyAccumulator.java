package io.riverdb.engine.schema.catalog;

import io.riverdb.base.error.StatusCode;
import io.riverdb.engine.schema.ColumnDescriptorSet;
import io.riverdb.engine.schema.KeyDescriptor;
import io.riverdb.engine.schema.TableDescriptor;
import io.riverdb.base.text.Utf8Text;
import java.nio.ByteBuffer;

final class CatalogKeyAccumulator {
  private static final KeyDescriptor[] EMPTY = new KeyDescriptor[0];
  private KeyDescriptor primary;
  private KeyDescriptor[] secondary = EMPTY;
  private KeyDescriptor[] foreign = EMPTY;
  private int secondaryCount;
  private int foreignCount;
  private int lastKind;
  private final char[] nameChars = new char[KeyDescriptor.MAXIMUM_NAME_LENGTH];
  private String decodedName;

  StatusCode add(
      long keyId, int kind, boolean unique, long referencedId,
      int[] ordinals, CharSequence name, ColumnDescriptorSet columns) {
    if (!ordered(kind)) return StatusCode.CORRUPTION;
    KeyDescriptor.Result result;
    StatusCode status;
    try {
      result = new KeyDescriptor.Result();
      status = name == null
          ? KeyDescriptor.create(
              keyId, kind, unique, columns, ordinals, referencedId, result, null)
          : KeyDescriptor.createNamed(
              keyId, kind, unique, columns, ordinals, referencedId, name, result, null);
    } catch (OutOfMemoryError error) {
      return StatusCode.RESOURCE_EXHAUSTED;
    }
    if (!status.isOk()) {
      return status == StatusCode.RESOURCE_EXHAUSTED ? status : StatusCode.CORRUPTION;
    }
    KeyDescriptor key = result.value();
    if (kind == KeyDescriptor.KIND_PRIMARY) {
      if (primary != null) return StatusCode.CORRUPTION;
      primary = key;
    } else if (kind == KeyDescriptor.KIND_FOREIGN) {
      if (foreignCount == TableDescriptor.MAXIMUM_FOREIGN_KEYS) {
        return StatusCode.RESOURCE_EXHAUSTED;
      }
      foreign = append(foreign, foreignCount, key);
      if (foreign == null) return StatusCode.RESOURCE_EXHAUSTED;
      foreignCount++;
    } else {
      if (secondaryCount == TableDescriptor.MAXIMUM_SECONDARY_KEYS) {
        return StatusCode.RESOURCE_EXHAUSTED;
      }
      secondary = append(secondary, secondaryCount, key);
      if (secondary == null) return StatusCode.RESOURCE_EXHAUSTED;
      secondaryCount++;
    }
    lastKind = kind;
    return StatusCode.OK;
  }

  KeyDescriptor primary() { return primary; }

  StatusCode decodeName(ByteBuffer source, int start, int bytes) {
    decodedName = null;
    if (bytes == 0) return StatusCode.OK;
    int chars = Utf8Text.decode(source, start, bytes, nameChars, 0);
    if (chars <= 0) return StatusCode.CORRUPTION;
    try {
      decodedName = new String(nameChars, 0, chars);
      return StatusCode.OK;
    } catch (OutOfMemoryError error) {
      return StatusCode.RESOURCE_EXHAUSTED;
    }
  }

  CharSequence decodedName() { return decodedName; }

  KeyDescriptor[] secondary() {
    return exact(secondary, secondaryCount);
  }

  KeyDescriptor[] foreign() {
    return exact(foreign, foreignCount);
  }

  void reset() {
    primary = null;
    secondary = EMPTY;
    foreign = EMPTY;
    secondaryCount = 0;
    foreignCount = 0;
    lastKind = 0;
    decodedName = null;
  }

  private boolean ordered(int kind) {
    int order = kind == KeyDescriptor.KIND_PRIMARY ? 1
        : kind == KeyDescriptor.KIND_FOREIGN ? 3 : 2;
    int lastOrder = lastKind == 0 ? 0
        : lastKind == KeyDescriptor.KIND_PRIMARY ? 1
        : lastKind == KeyDescriptor.KIND_FOREIGN ? 3 : 2;
    return order >= lastOrder;
  }

  private static KeyDescriptor[] append(
      KeyDescriptor[] values, int count, KeyDescriptor value) {
    if (count < values.length) {
      values[count] = value;
      return values;
    }
    int capacity = Math.min(64, Math.max(4, values.length * 2));
    try {
      KeyDescriptor[] grown = new KeyDescriptor[capacity];
      System.arraycopy(values, 0, grown, 0, count);
      grown[count] = value;
      return grown;
    } catch (OutOfMemoryError error) {
      return null;
    }
  }

  private static KeyDescriptor[] exact(KeyDescriptor[] values, int count) {
    if (count == values.length) return values;
    KeyDescriptor[] copied = new KeyDescriptor[count];
    System.arraycopy(values, 0, copied, 0, count);
    return copied;
  }
}
