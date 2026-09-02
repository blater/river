package io.riverdb.engine.table;

import io.riverdb.base.error.StatusCode;
import io.riverdb.base.tuple.TupleShape;
import io.riverdb.format.btree.TupleKeyCodec;

/** Exact-count durable descriptor vectors retained by one mutation buffer. */
final class IndexedRelationalMutationDescriptors {
  private final IndexedLongChunks keyIds;
  private final IndexedLongChunks ownerObjectIds;
  private final IndexedLongChunks schemaIds;
  private final IndexedLongChunks hashes;
  private final IndexedIntChunks partOffsets;
  private final IndexedIntChunks partCounts;
  private final IndexedIntChunks parts;
  private final IndexedTupleShapeChunks shapes;
  private final IndexedLongOrdinalIndex ordinalByKey;
  private final TupleShape[] shapeCache;
  private final TupleShape.Result shapeResult = new TupleShape.Result();
  private int count;
  private int usedParts;
  private int cachedShapes;
  private int nextCacheSlot;

  IndexedRelationalMutationDescriptors(int capacity, int partCapacity) {
    keyIds = new IndexedLongChunks(capacity);
    ownerObjectIds = new IndexedLongChunks(capacity);
    schemaIds = new IndexedLongChunks(capacity);
    hashes = new IndexedLongChunks(capacity);
    partOffsets = new IndexedIntChunks(capacity);
    partCounts = new IndexedIntChunks(capacity);
    parts = new IndexedIntChunks(partCapacity);
    shapeCache = new TupleShape[capacity == 0 ? 0 : Math.max(2, Math.min(capacity, 64))];
    shapes = new IndexedTupleShapeChunks(capacity);
    ordinalByKey = new IndexedLongOrdinalIndex(capacity);
  }

  StatusCode reserve(int additional, int additionalParts) {
    if (!canReserve(additional, additionalParts)) return StatusCode.RESOURCE_EXHAUSTED;
    StatusCode status = keyIds.reserve(count + additional);
    if (status.isOk()) status = ownerObjectIds.reserve(count + additional);
    if (status.isOk()) status = schemaIds.reserve(count + additional);
    if (status.isOk()) status = hashes.reserve(count + additional);
    if (status.isOk()) status = partOffsets.reserve(count + additional);
    if (status.isOk()) status = partCounts.reserve(count + additional);
    if (status.isOk()) status = parts.reserve(usedParts + additionalParts);
    if (status.isOk()) status = shapes.reserve(count + additional);
    if (status.isOk()) status = ordinalByKey.reserve(count + additional);
    return status;
  }

  StatusCode append(
      long ownerObjectId,
      long keyId,
      long schemaId,
      long hash,
      int[] source,
      int offset,
      int partCount) {
    if (!io.riverdb.format.catalog.CatalogKeyspace.validObjectHead(ownerObjectId)
        || !io.riverdb.format.catalog.CatalogKeyspace.validKeyId(keyId)
        || schemaId <= 0 || hash == 0 || source == null || offset < 0
        || partCount < 1 || partCount > TupleKeyCodec.MAX_INDEX_KEY_PARTS
        || offset > source.length - partCount || count >= keyIds.capacity()
        || count >= keyIds.allocatedCapacity() || partCount > parts.capacity() - usedParts
        || partCount > parts.allocatedCapacity() - usedParts || find(keyId) >= 0) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    TupleShape shape = cachedShape(source, offset, partCount);
    StatusCode status = StatusCode.OK;
    if (shape == null) {
      status = TupleShape.create(source, offset, partCount, shapeResult);
      if (!status.isOk()) return status;
      shape = shapeResult.value();
    }
    if (shape.descriptorHash() != hash
        || shape.maximumPhysicalEncodedBytes() > TupleKeyCodec.MAX_PHYSICAL_INDEX_KEY_BYTES) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    for (int index = 0; index < partCount; index++) parts.set(usedParts + index, source[offset + index]);
    if (!ordinalByKey.add(keyId, count)) return StatusCode.INVALID_EXTERNAL_INPUT;
    ownerObjectIds.set(count, ownerObjectId);
    keyIds.set(count, keyId);
    schemaIds.set(count, schemaId);
    hashes.set(count, hash);
    partOffsets.set(count, usedParts);
    partCounts.set(count, partCount);
    setShape(count, shape);
    cache(shape);
    usedParts += partCount;
    count++;
    return StatusCode.OK;
  }

  boolean canReserve(int additional, int additionalParts) {
    return additional >= 0 && additional <= keyIds.capacity() - count
        && additionalParts >= 0 && additionalParts <= parts.capacity() - usedParts;
  }

  void reset() { count = 0; usedParts = 0; ordinalByKey.clear(); }
  long accountedBytes() {
    return keyIds.allocatedBytes() + ownerObjectIds.allocatedBytes()
        + schemaIds.allocatedBytes() + hashes.allocatedBytes()
        + partOffsets.allocatedBytes() + partCounts.allocatedBytes()
        + parts.allocatedBytes() + shapes.accountedBytes()
        + ordinalByKey.accountedBytes() + (long) shapeCache.length * 8L + 64L;
  }
  long accountedBytesForReservation(int additional, int additionalParts) {
    if (!canReserve(additional, additionalParts)) return -1;
    return Math.max(accountedBytes(), accountedBytesForCapacity(count + additional,
        usedParts + additionalParts));
  }
  int capacity() { return keyIds.capacity(); }
  int partCapacity() { return parts.capacity(); }
  int count() { return count; }
  long ownerObjectIdAt(int ordinal) { return ownerObjectIds.get(ordinal); }
  long keyIdAt(int ordinal) { return keyIds.get(ordinal); }
  long schemaIdAt(int ordinal) { return schemaIds.get(ordinal); }
  long hashAt(int ordinal) { return hashes.get(ordinal); }
  int partCountAt(int ordinal) { return partCounts.get(ordinal); }
  TupleShape shapeAt(int ordinal) { return shapes.get(ordinal); }
  int partAt(int ordinal, int part) { return parts.get(partOffsets.get(ordinal) + part); }

  private int find(long keyId) {
    return ordinalByKey.find(keyId);
  }

  private TupleShape cachedShape(int[] source, int offset, int partCount) {
    for (int index = 0; index < cachedShapes; index++) {
      if (shapeCache[index].matchesDescriptors(source, offset, partCount)) return shapeCache[index];
    }
    return null;
  }

  private void cache(TupleShape shape) {
    for (int index = 0; index < cachedShapes; index++) if (shapeCache[index] == shape) return;
    if (shapeCache.length == 0) return;
    int slot = cachedShapes < shapeCache.length ? cachedShapes++ : nextCacheSlot;
    shapeCache[slot] = shape;
    nextCacheSlot = (slot + 1) % shapeCache.length;
  }

  private void setShape(int index, TupleShape shape) {
    shapes.set(index, shape);
  }

  private long accountedBytesForCapacity(int required, int requiredParts) {
    long longBytes = keyIds.accountedBytesForCapacity(required);
    long intBytes = partOffsets.accountedBytesForCapacity(required);
    long partBytes = parts.accountedBytesForCapacity(requiredParts);
    long shapeBytes = shapes.accountedBytesForCapacity(required);
    long indexBytes = ordinalByKey.accountedBytesForEntries(required);
    if (longBytes < 0 || intBytes < 0 || partBytes < 0
        || shapeBytes < 0 || indexBytes < 0) return -1;
    return 4L * longBytes + 2L * intBytes + partBytes + shapeBytes
        + indexBytes + (long) shapeCache.length * 8L + 64L;
  }

  void release() {
    keyIds.release();
    ownerObjectIds.release();
    schemaIds.release();
    hashes.release();
    partOffsets.release();
    partCounts.release();
    parts.release();
    shapes.release();
    ordinalByKey.release();
    java.util.Arrays.fill(shapeCache, null);
    count = usedParts = cachedShapes = nextCacheSlot = 0;
  }
}
