package io.riverdb.engine.table;

import io.riverdb.base.collection.BoundedArrayGrowth;
import io.riverdb.base.error.StatusCode;
import io.riverdb.base.tuple.TupleShape;
import io.riverdb.format.btree.TupleKeyCodec;
import io.riverdb.format.catalog.CatalogKeyspace;

/** Session-owned actual-count requests for one atomic tuple-index lifecycle batch. */
final class IndexedTupleIndexLifecycleBatch {
  private static final long ARRAY_OVERHEAD_BYTES = 16;
  private static final long BYTES_PER_ENTRY = 6L * Long.BYTES;
  static final int CREATE_BUILDING = 1;
  static final int PUBLISH_READY = 2;
  static final int DETACH_DROPPING = 3;
  static final int RECLAIM_DROPPING = 4;
  static final int FINISH_DROPPING = 5;
  static final int APPEND_BUILDING = 6;
  private final int maximumDescriptors;
  private final IndexedLongOrdinalIndex ordinalByKey;
  private int[] operations = new int[0];
  private long[] owners = new long[0];
  private long[] keyIds = new long[0];
  private long[] schemaIds = new long[0];
  private long[] privateOwners = new long[0];
  private int[] cleanupEnds = new int[0];
  private TupleShape[] shapes = new TupleShape[0];
  private int count;

  IndexedTupleIndexLifecycleBatch() {
    this(IndexedTupleIntentJournal.MAX_DESCRIPTORS);
  }

  IndexedTupleIndexLifecycleBatch(int maximum) {
    if (maximum <= 0) throw new IllegalArgumentException("descriptor capacity must be positive");
    maximumDescriptors = maximum;
    ordinalByKey = new IndexedLongOrdinalIndex(maximum);
  }

  StatusCode reserve(int additional) {
    if (additional <= 0 || count > maximumDescriptors - additional) {
      return StatusCode.RESOURCE_EXHAUSTED;
    }
    int required = count + additional;
    StatusCode indexStatus = ordinalByKey.reserve(required);
    if (!indexStatus.isOk() || required <= operations.length) return indexStatus;
    int capacity = BoundedArrayGrowth.capacity(
        operations.length, required, maximumDescriptors, 4);
    if (capacity < 0) return StatusCode.RESOURCE_EXHAUSTED;
    try {
      int[] nextOperations = new int[capacity];
      long[] nextOwners = new long[capacity];
      long[] nextKeyIds = new long[capacity];
      long[] nextSchemaIds = new long[capacity];
      long[] nextPrivateOwners = new long[capacity];
      int[] nextCleanupEnds = new int[capacity];
      TupleShape[] nextShapes = new TupleShape[capacity];
      System.arraycopy(operations, 0, nextOperations, 0, count);
      System.arraycopy(owners, 0, nextOwners, 0, count);
      System.arraycopy(keyIds, 0, nextKeyIds, 0, count);
      System.arraycopy(schemaIds, 0, nextSchemaIds, 0, count);
      System.arraycopy(privateOwners, 0, nextPrivateOwners, 0, count);
      System.arraycopy(cleanupEnds, 0, nextCleanupEnds, 0, count);
      System.arraycopy(shapes, 0, nextShapes, 0, count);
      operations = nextOperations;
      owners = nextOwners;
      keyIds = nextKeyIds;
      schemaIds = nextSchemaIds;
      privateOwners = nextPrivateOwners;
      cleanupEnds = nextCleanupEnds;
      shapes = nextShapes;
      return StatusCode.OK;
    } catch (OutOfMemoryError error) {
      return StatusCode.RESOURCE_EXHAUSTED;
    }
  }

  long accountedBytesForReservation(int additional) {
    if (additional < 0 || count > maximumDescriptors - additional) {
      return -1;
    }
    int required = count + additional;
    int capacity = operations.length;
    if (required > capacity) {
      capacity = BoundedArrayGrowth.capacity(
          capacity, required, maximumDescriptors, 4);
      if (capacity < 0) return -1;
    }
    long indexBytes = ordinalByKey.accountedBytesForEntries(required);
    return indexBytes < 0 ? -1
        : 7L * ARRAY_OVERHEAD_BYTES + capacity * BYTES_PER_ENTRY + indexBytes;
  }

  long accountedBytes() { return accountedBytesForReservation(0); }

  StatusCode append(
      int operation, long owner, long keyId, long schemaId,
      long privateOwner, TupleShape shape, int cleanupEnd) {
    if (!valid(operation, owner, keyId, schemaId, privateOwner, shape, cleanupEnd)
        || ordinalByKey.find(keyId) >= 0) return StatusCode.INVALID_EXTERNAL_INPUT;
    StatusCode status = reserve(1);
    if (!status.isOk()) return status;
    if (!ordinalByKey.add(keyId, count)) return StatusCode.INVALID_EXTERNAL_INPUT;
    operations[count] = operation;
    owners[count] = owner;
    keyIds[count] = keyId;
    schemaIds[count] = schemaId;
    privateOwners[count] = privateOwner;
    shapes[count] = shape;
    cleanupEnds[count] = cleanupEnd;
    count++;
    return StatusCode.OK;
  }

  void reset() {
    truncate(0);
  }

  void release() {
    operations = null;
    owners = null;
    keyIds = null;
    schemaIds = null;
    privateOwners = null;
    cleanupEnds = null;
    shapes = null;
    ordinalByKey.release();
    count = 0;
  }

  void truncate(int retained) {
    if (retained < 0 || retained > count) return;
    for (int index = retained; index < count; index++) shapes[index] = null;
    count = retained;
    ordinalByKey.clear();
    for (int index = 0; index < retained; index++) ordinalByKey.add(keyIds[index], index);
  }

  boolean active() { return count > 0; }
  boolean acceptsTupleMutations() {
    for (int index = 0; index < count; index++) {
      if (operations[index] != PUBLISH_READY
          && operations[index] != APPEND_BUILDING) return false;
    }
    return true;
  }
  boolean publishes(
      long owner, long keyId, long schemaId, TupleShape shape) {
    return publishingPrivateOwner(owner, keyId, schemaId, shape) > 0;
  }

  long publishingPrivateOwner(
      long owner, long keyId, long schemaId, TupleShape shape) {
    for (int index = 0; index < count; index++) {
      if (operations[index] == PUBLISH_READY && owners[index] == owner
          && keyIds[index] == keyId && schemaIds[index] == schemaId
          && shapes[index].descriptorHash() == shape.descriptorHash()
          && shapes[index].sameDescriptors(shape)) return privateOwners[index];
    }
    return 0;
  }
  boolean appendsBuilding(int index) {
    return index >= 0 && index < count && operations[index] == APPEND_BUILDING;
  }
  boolean appendsBuilding(
      long owner, long keyId, long schemaId, TupleShape shape) {
    if (shape == null) return false;
    int index = ordinalByKey.find(keyId);
    return appendsBuilding(index) && owners[index] == owner
        && schemaIds[index] == schemaId
        && shapes[index].descriptorHash() == shape.descriptorHash()
        && shapes[index].sameDescriptors(shape);
  }
  int count() { return count; }
  int operationAt(int index) { return operations[index]; }
  long ownerAt(int index) { return owners[index]; }
  long keyIdAt(int index) { return keyIds[index]; }
  long schemaIdAt(int index) { return schemaIds[index]; }
  long privateOwnerAt(int index) { return privateOwners[index]; }
  TupleShape shapeAt(int index) { return shapes[index]; }
  int cleanupEndAt(int index) { return cleanupEnds[index]; }

  int partCount() {
    int parts = 0;
    for (int index = 0; index < count; index++) parts += shapes[index].partCount();
    return parts;
  }

  private static boolean valid(
      int operation, long owner, long keyId, long schemaId,
      long privateOwner, TupleShape shape, int cleanupEnd) {
    return operation >= CREATE_BUILDING && operation <= APPEND_BUILDING
        && CatalogKeyspace.validObjectHead(owner) && CatalogKeyspace.validKeyId(keyId)
        && schemaId > 0 && privateOwner > 0 && shape != null
        && shape.partCount() > 0
        && shape.partCount() <= TupleKeyCodec.MAX_INDEX_KEY_PARTS
        && shape.maximumPhysicalEncodedBytes() <= TupleKeyCodec.MAX_PHYSICAL_INDEX_KEY_BYTES
        && (operation == RECLAIM_DROPPING || operation == FINISH_DROPPING
            ? cleanupEnd >= 4 : cleanupEnd == 0);
  }
}
