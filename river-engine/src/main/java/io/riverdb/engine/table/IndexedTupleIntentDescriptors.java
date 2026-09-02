package io.riverdb.engine.table;

import io.riverdb.base.error.StatusCode;
import io.riverdb.base.tuple.TupleShape;
import java.util.Arrays;

/** Actual-count descriptor identities retained by one tuple-intent journal. */
final class IndexedTupleIntentDescriptors {
  private static final int CHUNK_SHIFT = 6;
  private static final int CHUNK_SIZE = 1 << CHUNK_SHIFT;
  private static final int CHUNK_MASK = CHUNK_SIZE - 1;
  private final int maximumDescriptors;
  private final IndexedLongOrdinalIndex ordinalByKey;
  private long[][] owners = new long[0][];
  private long[][] keyIds = new long[0][];
  private long[][] schemaIds = new long[0][];
  private long[][] hashes = new long[0][];
  private TupleShape[][] shapes = new TupleShape[0][];
  private int count;

  IndexedTupleIntentDescriptors() {
    this(IndexedTupleIntentJournal.MAX_DESCRIPTORS);
  }

  IndexedTupleIntentDescriptors(int maximumDescriptors) {
    if (maximumDescriptors <= 0) throw new IllegalArgumentException("descriptor capacity must be positive");
    this.maximumDescriptors = maximumDescriptors;
    ordinalByKey = new IndexedLongOrdinalIndex(maximumDescriptors);
  }

  StatusCode reserve(int additional) {
    if (additional < 0 || additional > maximumDescriptors - count) {
      return additional < 0 ? StatusCode.INVALID_EXTERNAL_INPUT : StatusCode.RESOURCE_EXHAUSTED;
    }
    try {
      ensureCapacity(count + additional);
      return ordinalByKey.reserve(count + additional);
    } catch (OutOfMemoryError failure) {
      return StatusCode.RESOURCE_EXHAUSTED;
    }
  }

  long accountedBytesForReservation(int additional) {
    if (additional < 0 || additional > maximumDescriptors - count) return -1;
    int requiredChunks = chunksFor(count + additional);
    if (requiredChunks < 0) return -1;
    int chunks = Math.max(owners.length, requiredChunks);
    long indexBytes = ordinalByKey.accountedBytesForEntries(count + additional);
    return indexBytes < 0 ? -1 : chunks * (4L * CHUNK_SIZE * Long.BYTES
        + CHUNK_SIZE * Long.BYTES) + indexBytes + 64L;
  }

  void release() {
    owners = keyIds = schemaIds = hashes = new long[0][];
    shapes = new TupleShape[0][];
    ordinalByKey.release();
    count = 0;
  }

  int register(long owner, long keyId, long schemaId, TupleShape shape) {
    int descriptor = find(keyId);
    if (descriptor >= 0) return same(descriptor, owner, schemaId, shape) ? descriptor : -1;
    if (count >= maximumDescriptors || count >= owners.length * CHUNK_SIZE) return -1;
    descriptor = count;
    if (!ordinalByKey.add(keyId, descriptor)) return -1;
    count++;
    setOwner(descriptor, owner);
    setKeyId(descriptor, keyId);
    setSchemaId(descriptor, schemaId);
    setHash(descriptor, shape.descriptorHash());
    setShape(descriptor, shape);
    return descriptor;
  }

  StatusCode status(long owner, long keyId, long schemaId, TupleShape shape) {
    int descriptor = find(keyId);
    if (descriptor < 0) return StatusCode.CONFLICT;
    return same(descriptor, owner, schemaId, shape) ? StatusCode.OK : StatusCode.INVARIANT_BROKEN;
  }

  void truncate(int retained) {
    if (retained < 0 || retained > count) return;
    for (int index = retained; index < count; index++) {
      setOwner(index, 0);
      setKeyId(index, 0);
      setSchemaId(index, 0);
      setHash(index, 0);
      setShape(index, null);
    }
    count = retained;
    ordinalByKey.clear();
    for (int index = 0; index < retained; index++) {
      ordinalByKey.add(keyIdAt(index), index);
    }
  }

  int count() { return count; }
  int findDescriptor(long keyId) { return find(keyId); }
  long ownerAt(int index) { return owners[index >> CHUNK_SHIFT][index & CHUNK_MASK]; }
  long keyIdAt(int index) { return keyIds[index >> CHUNK_SHIFT][index & CHUNK_MASK]; }
  long schemaIdAt(int index) { return schemaIds[index >> CHUNK_SHIFT][index & CHUNK_MASK]; }
  long hashAt(int index) { return hashes[index >> CHUNK_SHIFT][index & CHUNK_MASK]; }
  TupleShape shapeAt(int index) { return shapes[index >> CHUNK_SHIFT][index & CHUNK_MASK]; }

  private int find(long keyId) {
    return ordinalByKey.find(keyId);
  }

  private boolean same(int index, long owner, long schemaId, TupleShape shape) {
    return ownerAt(index) == owner && schemaIdAt(index) == schemaId
        && hashAt(index) == shape.descriptorHash() && shapeAt(index).sameDescriptors(shape);
  }

  private void ensureCapacity(int required) {
    int chunks = chunksFor(required);
    if (chunks <= owners.length) return;
    owners = growLongChunks(owners, chunks);
    keyIds = growLongChunks(keyIds, chunks);
    schemaIds = growLongChunks(schemaIds, chunks);
    hashes = growLongChunks(hashes, chunks);
    TupleShape[][] nextShapes = Arrays.copyOf(shapes, chunks);
    for (int index = shapes.length; index < chunks; index++) nextShapes[index] = new TupleShape[CHUNK_SIZE];
    shapes = nextShapes;
  }

  private static int chunksFor(int values) {
    if (values < 0 || values > Integer.MAX_VALUE - CHUNK_MASK) return -1;
    return (values + CHUNK_MASK) >> CHUNK_SHIFT;
  }

  private static long[][] growLongChunks(long[][] source, int needed) {
    long[][] next = Arrays.copyOf(source, needed);
    for (int index = source.length; index < needed; index++) next[index] = new long[CHUNK_SIZE];
    return next;
  }

  private void setOwner(int index, long value) { owners[index >> CHUNK_SHIFT][index & CHUNK_MASK] = value; }
  private void setKeyId(int index, long value) { keyIds[index >> CHUNK_SHIFT][index & CHUNK_MASK] = value; }
  private void setSchemaId(int index, long value) { schemaIds[index >> CHUNK_SHIFT][index & CHUNK_MASK] = value; }
  private void setHash(int index, long value) { hashes[index >> CHUNK_SHIFT][index & CHUNK_MASK] = value; }
  private void setShape(int index, TupleShape value) { shapes[index >> CHUNK_SHIFT][index & CHUNK_MASK] = value; }
}
