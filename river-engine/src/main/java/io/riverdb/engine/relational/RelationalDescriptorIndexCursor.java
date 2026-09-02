package io.riverdb.engine.relational;

import io.riverdb.base.error.StatusCode;
import io.riverdb.base.tuple.TupleShape;
import io.riverdb.base.type.SqlValueBuffer;
import io.riverdb.engine.schema.KeyDescriptor;
import io.riverdb.engine.table.IndexedTransactionSession;
import io.riverdb.format.btree.TupleKeyCodec;
import io.riverdb.storage.btree.TupleBTreeScanBounds;
import java.nio.ByteBuffer;

/** Cursor-owned encoded bounds and residual index-key recheck state. */
final class RelationalDescriptorIndexCursor {
  private final RelationalTupleKeyEncoder lower = new RelationalTupleKeyEncoder();
  private final RelationalTupleKeyEncoder upper = new RelationalTupleKeyEncoder();
  private final RelationalTupleKeyEncoder candidate = new RelationalTupleKeyEncoder();
  private final TupleShape.Result lowerShape = new TupleShape.Result();
  private final TupleShape.Result upperShape = new TupleShape.Result();
  private final int[] descriptors = new int[KeyDescriptor.MAXIMUM_PARTS];
  private final TupleBTreeScanBounds storage = new TupleBTreeScanBounds();
  private KeyDescriptor key;
  private KeyDescriptor prefixKey;
  private TupleShape prefixShape;
  private int prefixParts;
  private int lowerParts;
  private int upperParts;
  private boolean lowerInclusive;
  private boolean upperInclusive;
  private boolean exactUnique;
  private boolean empty;
  private boolean matches;

  StatusCode prepare(RelationalDescriptorIndexBounds request) {
    if (key != null || request == null || request.key() == null) return StatusCode.CONFLICT;
    key = request.key();
    lowerParts = request.lowerParts();
    upperParts = request.upperParts();
    lowerInclusive = request.lowerInclusive();
    upperInclusive = request.upperInclusive();
    StatusCode status = encode(lower, lowerShape, request.lower(), lowerParts);
    if (status.isOk()) status = encode(upper, upperShape, request.upper(), upperParts);
    if (status.isOk()) {
      exactUnique = exactUnique();
      empty = emptyRange();
      status = empty || lowerParts == 0 && upperParts == 0
          ? storage.setAll(request.direction())
          : storage.setRange(
              lowerParts == 0 ? null : lower.bytes(), 0, lower.length(), lowerShape.value(),
              lowerInclusive, upperParts == 0 ? null : upper.bytes(), 0, upper.length(),
              upperShape.value(), upperInclusive, request.direction());
    }
    if (!status.isOk()) clear();
    return status;
  }

  private StatusCode encode(
      RelationalTupleKeyEncoder encoder, TupleShape.Result shape,
      SqlValueBuffer values, int parts) {
    shape.reset();
    if (parts == 0) return StatusCode.OK;
    StatusCode status = encoder.encodeUser(key, values, parts);
    if (!status.isOk()) return status;
    if (parts == key.partCount()) return shape.use(key.shape());
    if (prefixKey == key && prefixParts == parts && prefixShape != null) {
      return shape.use(prefixShape);
    }
    status = TupleShape.create(descriptors(key, parts), 0, parts, shape);
    if (status.isOk()) {
      prefixKey = key;
      prefixParts = parts;
      prefixShape = shape.value();
    }
    return status;
  }

  private int[] descriptors(KeyDescriptor source, int parts) {
    for (int part = 0; part < parts; part++) descriptors[part] = source.typeDescriptorAt(part);
    return descriptors;
  }

  StatusCode recheck(SqlValueBuffer values) {
    StatusCode status = candidate.encodeUser(key, values);
    matches = status.isOk() && inside(candidate, lower, lowerParts, lowerInclusive, false)
        && inside(candidate, upper, upperParts, upperInclusive, true);
    return status;
  }

  private static boolean inside(
      RelationalTupleKeyEncoder value, RelationalTupleKeyEncoder bound,
      int parts, boolean inclusive, boolean upperSide) {
    if (parts == 0) return true;
    int compared = TupleKeyCodec.comparePrefix(
        value.bytes(), 0, value.length(), bound.bytes(), 0, bound.length(), parts);
    return upperSide ? compared < 0 || compared == 0 && inclusive
        : compared > 0 || compared == 0 && inclusive;
  }

  TupleBTreeScanBounds storage() { return storage; }
  KeyDescriptor key() { return key; }
  boolean matches() { return matches; }
  boolean empty() { return empty; }

  void clear() {
    lower.clear();
    upper.clear();
    candidate.clear();
    lowerShape.reset();
    upperShape.reset();
    key = null;
    lowerParts = 0;
    upperParts = 0;
    exactUnique = false;
    empty = false;
    matches = false;
  }

  private boolean emptyRange() {
    if (lowerParts == 0 || lowerParts != upperParts) return false;
    int compared = TupleKeyCodec.comparePrefix(
        lower.bytes(), 0, lower.length(), upper.bytes(), 0, upper.length(), lowerParts);
    return compared > 0 || compared == 0 && (!lowerInclusive || !upperInclusive);
  }

  private boolean exactUnique() {
    if (!key.isUnique() || lowerParts != key.partCount() || upperParts != key.partCount()
        || lower.containsNull() || upper.containsNull()
        || !lowerInclusive || !upperInclusive || lower.length() != upper.length()) return false;
    ByteBuffer left = lower.bytes();
    ByteBuffer right = upper.bytes();
    for (int index = 0; index < lower.length(); index++) {
      if (left.get(index) != right.get(index)) return false;
    }
    return true;
  }
}
