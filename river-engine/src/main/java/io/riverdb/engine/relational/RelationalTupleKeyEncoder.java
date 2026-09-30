package io.riverdb.engine.relational;

import io.riverdb.base.error.StatusCode;
import io.riverdb.engine.schema.KeyDescriptor;
import io.riverdb.base.type.SqlTypeDescriptor;
import io.riverdb.format.btree.TupleKeyBuilder;
import java.nio.ByteBuffer;

/** Reusable, allocation-free-after-warmup encoder from descriptor rows to canonical index keys. */
final class RelationalTupleKeyEncoder {
  private final TupleKeyBuilder builder = new TupleKeyBuilder();
  private final RelationalTupleKeyScratch scratch = new RelationalTupleKeyScratch();
  private final RelationalTupleKeyPartEncoder parts = new RelationalTupleKeyPartEncoder();
  private int length;
  private boolean containsNull;

  StatusCode encodeUser(KeyDescriptor key, SqlValueAccess values) {
    return encode(key, values, key == null ? 0 : key.partCount(), 0, false);
  }

  StatusCode encodeUser(KeyDescriptor key, SqlValueAccess values, int parts) {
    return encode(key, values, parts, 0, false);
  }

  StatusCode encodePhysical(
      KeyDescriptor key, SqlValueAccess values, long logicalRowId) {
    return encode(key, values, key == null ? 0 : key.partCount(), logicalRowId, true);
  }

  /** Borrowed read-only-by-convention bytes remain valid until the next encode attempt. */
  ByteBuffer bytes() {
    return scratch.bytes(length);
  }

  int length() { return length; }
  boolean containsNull() { return containsNull; }

  void clear() {
    scratch.clear(length);
    length = 0;
    containsNull = false;
    builder.reset();
  }

  private StatusCode encode(
      KeyDescriptor key, SqlValueAccess values, int parts,
      long logicalRowId, boolean physical) {
    length = 0;
    containsNull = false;
    builder.reset();
    if (key != null && key.kind() == KeyDescriptor.KIND_INTERNAL_IDENTITY) {
      if (!physical || logicalRowId <= 0 || parts != 1) {
        return StatusCode.INVALID_EXTERNAL_INPUT;
      }
      StatusCode identity = scratch.reserve(key, true);
      if (!identity.isOk()) return identity;
      ByteBuffer target = scratch.prepare();
      identity = builder.beginIndex(target, 0, 1);
      if (identity.isOk()) identity = builder.addFixed(
          SqlTypeDescriptor.BIGINT, logicalRowId);
      if (identity.isOk()) identity = builder.finishPhysical(logicalRowId);
      if (identity.isOk()) {
        length = builder.keyBytes();
        scratch.bytes(length);
      }
      return identity;
    }
    StatusCode status = RelationalTupleKeyValidation.validate(
        key, values, parts, logicalRowId, physical);
    if (!status.isOk()) return status;
    status = scratch.reserve(key, physical);
    if (!status.isOk()) return status;
    ByteBuffer bytes = scratch.prepare();
    status = physical
        ? builder.beginIndex(bytes, 0, parts)
        : builder.beginTuple(bytes, 0, parts);
    if (status.isOk()) status = this.parts.encode(builder, key, values, parts, scratch);
    if (status.isOk()) status = physical
        ? builder.finishPhysical(logicalRowId) : builder.finishTuple();
    if (!status.isOk()) return status;
    length = builder.keyBytes();
    containsNull = this.parts.containsNull();
    scratch.bytes(length);
    return StatusCode.OK;
  }
}
