package io.riverdb.format.btree;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.riverdb.base.error.StatusCode;
import io.riverdb.base.tuple.TupleShape;
import io.riverdb.base.type.SqlTypeDescriptor;
import io.riverdb.format.catalog.CatalogKeyspace;
import java.nio.ByteBuffer;
import java.util.zip.CRC32C;
import org.junit.jupiter.api.Test;

final class TupleIndexRootRecordCodecTest {
  private static final int[] DESCRIPTORS = {
      SqlTypeDescriptor.BIGINT, SqlTypeDescriptor.varchar(16)};
  @Test
  void roundTripsReadyAndBuildingStates() {
    ByteBuffer bytes = ByteBuffer.allocate(TupleIndexRootRecordCodec.BYTES);
    TupleIndexRootRecord result = new TupleIndexRootRecord();
    assertEquals(StatusCode.OK, encode(bytes, TupleIndexRootRecordCodec.STATE_READY, 17, 0));
    assertEquals(StatusCode.OK,
        TupleIndexRootRecordCodec.decode(bytes, 0, result, new CRC32C()));
    assertEquals(7, result.keyId());
    assertEquals(11, result.ownerObjectId());
    assertEquals(7, result.schemaId());
    assertEquals(17, result.rootPageId());
    assertEquals(descriptorHash(), result.descriptorHash());
    assertEquals(0, result.privateOwner());
    assertEquals(29, result.generation());
    assertEquals(2, result.descriptorCount());
    assertEquals(DESCRIPTORS[0], result.descriptorAt(0));
    assertEquals(DESCRIPTORS[1], result.descriptorAt(1));

    assertEquals(StatusCode.OK,
        encode(bytes, TupleIndexRootRecordCodec.STATE_BUILDING, 0, 23));
    assertEquals(StatusCode.OK,
        TupleIndexRootRecordCodec.decode(bytes, 0, result, new CRC32C()));
    assertEquals(23, result.privateOwner());

    assertEquals(StatusCode.OK,
        encode(bytes, TupleIndexRootRecordCodec.STATE_DROPPING, 17, 23));
    assertEquals(StatusCode.OK,
        TupleIndexRootRecordCodec.decode(bytes, 0, result, new CRC32C()));
    assertEquals(TupleIndexRootRecordCodec.STATE_DROPPING, result.state());
    assertEquals(StatusCode.OK,
        encode(bytes, TupleIndexRootRecordCodec.STATE_ABSENT, 0, 0));
    assertEquals(StatusCode.OK,
        TupleIndexRootRecordCodec.decode(bytes, 0, result, new CRC32C()));
    assertEquals(TupleIndexRootRecordCodec.STATE_ABSENT, result.state());
  }

  @Test
  void rejectsInvalidStateIdentityAndCorruptionAtomically() {
    ByteBuffer bytes = ByteBuffer.allocate(TupleIndexRootRecordCodec.BYTES);
    TupleIndexRootRecord result = new TupleIndexRootRecord();
    assertEquals(StatusCode.INVALID_EXTERNAL_INPUT,
        encode(bytes, TupleIndexRootRecordCodec.STATE_READY, 0, 0));
    assertEquals(StatusCode.OK,
        TupleIndexRootRecordCodec.encode(bytes, 0,
            TupleIndexRootRecordCodec.STATE_READY, 17,
            7, 11, 13, descriptorHash(), 0, 29,
            DESCRIPTORS, 0, DESCRIPTORS.length, new CRC32C()));
    assertEquals(StatusCode.OK,
        TupleIndexRootRecordCodec.decode(bytes, 0, result, new CRC32C()));
    assertEquals(7, result.keyId());
    assertEquals(13, result.schemaId());
    assertEquals(StatusCode.INVALID_EXTERNAL_INPUT,
        TupleIndexRootRecordCodec.encode(bytes, 0,
            TupleIndexRootRecordCodec.STATE_READY, 17,
            CatalogKeyspace.KEY_ID_EXHAUSTED, 11, 13, descriptorHash(), 0, 29,
            DESCRIPTORS, 0, DESCRIPTORS.length, new CRC32C()));
    assertEquals(StatusCode.OK, encode(bytes, TupleIndexRootRecordCodec.STATE_READY, 17, 0));
    bytes.put(40, (byte) (bytes.get(40) ^ 1));
    assertEquals(StatusCode.CORRUPTION,
        TupleIndexRootRecordCodec.decode(bytes, 0, result, new CRC32C()));
    assertEquals(0, result.keyId());
  }

  @Test
  void rejectsV1AndNonzeroUnusedDescriptors() {
    ByteBuffer bytes = ByteBuffer.allocate(TupleIndexRootRecordCodec.BYTES);
    TupleIndexRootRecord result = new TupleIndexRootRecord();
    assertEquals(StatusCode.OK, encode(bytes, TupleIndexRootRecordCodec.STATE_READY, 17, 0));
    bytes.putInt(8, 1);
    assertEquals(StatusCode.CORRUPTION,
        TupleIndexRootRecordCodec.decode(bytes, 0, result, new CRC32C()));
    assertEquals(StatusCode.OK, encode(bytes, TupleIndexRootRecordCodec.STATE_READY, 17, 0));
    bytes.putInt(80 + DESCRIPTORS.length * Integer.BYTES, SqlTypeDescriptor.BIGINT);
    assertEquals(StatusCode.CORRUPTION,
        TupleIndexRootRecordCodec.decode(bytes, 0, result, new CRC32C()));
  }

  @Test
  void roundTripsOnlyCanonicalDroppingCleanupCursors() {
    ByteBuffer bytes = ByteBuffer.allocate(TupleIndexRootRecordCodec.BYTES);
    TupleIndexRootRecord result = new TupleIndexRootRecord();
    assertEquals(StatusCode.OK,
        TupleIndexRootRecordCodec.encode(
            bytes, 0, TupleIndexRootRecordCodec.STATE_DROPPING, 0,
            7, 11, 13, descriptorHash(), 23, 29, 20,
            DESCRIPTORS, 0, DESCRIPTORS.length, new CRC32C()));
    assertEquals(StatusCode.OK,
        TupleIndexRootRecordCodec.decode(bytes, 0, result, new CRC32C()));
    assertEquals(20, result.cleanupCursor());
    assertEquals(13, result.schemaId());

    assertEquals(StatusCode.INVALID_EXTERNAL_INPUT,
        TupleIndexRootRecordCodec.encode(
            bytes, 0, TupleIndexRootRecordCodec.STATE_DROPPING, 0,
            7, 11, 13, descriptorHash(), 23, 29, 3,
            DESCRIPTORS, 0, DESCRIPTORS.length, new CRC32C()));
    assertEquals(StatusCode.INVALID_EXTERNAL_INPUT,
        TupleIndexRootRecordCodec.encode(
            bytes, 0, TupleIndexRootRecordCodec.STATE_READY, 17,
            7, 11, 13, descriptorHash(), 0, 29, 4,
            DESCRIPTORS, 0, DESCRIPTORS.length, new CRC32C()));
  }

  @Test
  void indexNamespacesRemainDisjointFromRootRegistry() {
    assertEquals(
        CatalogKeyspace.INDEX_ROOT_SPACE - 1,
        CatalogKeyspace.relationalIndexSpace(CatalogKeyspace.MAXIMUM_KEY_ID));
  }

  private static StatusCode encode(ByteBuffer bytes, int state, int root, long owner) {
    return TupleIndexRootRecordCodec.encode(
        bytes, 0, state, root, 7, 11, 7, descriptorHash(), owner, 29,
        DESCRIPTORS, 0, DESCRIPTORS.length, new CRC32C());
  }

  private static long descriptorHash() {
    TupleShape.Result result = new TupleShape.Result();
    assertEquals(StatusCode.OK, TupleShape.create(DESCRIPTORS, result));
    return result.value().descriptorHash();
  }
}
