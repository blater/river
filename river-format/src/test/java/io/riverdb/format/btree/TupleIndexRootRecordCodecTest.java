package io.riverdb.format.btree;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.riverdb.base.error.StatusCode;
import io.riverdb.format.FormatBytes;
import io.riverdb.base.tuple.TupleShape;
import io.riverdb.base.type.SqlTypeDescriptor;
import io.riverdb.format.catalog.CatalogKeyspace;
import java.nio.ByteBuffer;
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
        TupleIndexRootRecordCodec.decode(bytes, 0, result));
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
        TupleIndexRootRecordCodec.decode(bytes, 0, result));
    assertEquals(23, result.privateOwner());

    assertEquals(StatusCode.OK,
        encode(bytes, TupleIndexRootRecordCodec.STATE_DROPPING, 17, 23));
    assertEquals(StatusCode.OK,
        TupleIndexRootRecordCodec.decode(bytes, 0, result));
    assertEquals(TupleIndexRootRecordCodec.STATE_DROPPING, result.state());
    assertEquals(StatusCode.OK,
        encode(bytes, TupleIndexRootRecordCodec.STATE_ABSENT, 0, 0));
    assertEquals(StatusCode.OK,
        TupleIndexRootRecordCodec.decode(bytes, 0, result));
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
            DESCRIPTORS, 0, DESCRIPTORS.length));
    assertEquals(StatusCode.OK,
        TupleIndexRootRecordCodec.decode(bytes, 0, result));
    assertEquals(7, result.keyId());
    assertEquals(13, result.schemaId());
    assertEquals(StatusCode.INVALID_EXTERNAL_INPUT,
        TupleIndexRootRecordCodec.encode(bytes, 0,
            TupleIndexRootRecordCodec.STATE_READY, 17,
            CatalogKeyspace.KEY_ID_EXHAUSTED, 11, 13, descriptorHash(), 0, 29,
            DESCRIPTORS, 0, DESCRIPTORS.length));
    assertEquals(StatusCode.OK, encode(bytes, TupleIndexRootRecordCodec.STATE_READY, 17, 0));
    FormatBytes.putLong(bytes, 40, 0);
    assertEquals(StatusCode.CORRUPTION,
        TupleIndexRootRecordCodec.decode(bytes, 0, result));
    assertEquals(0, result.keyId());
  }

  @Test
  void rejectsPreviousVersionAndNonzeroUnusedDescriptors() {
    ByteBuffer bytes = ByteBuffer.allocate(TupleIndexRootRecordCodec.BYTES);
    TupleIndexRootRecord result = new TupleIndexRootRecord();
    assertEquals(StatusCode.OK, encode(bytes, TupleIndexRootRecordCodec.STATE_READY, 17, 0));
    FormatBytes.putInt(bytes, 8, 3);
    assertEquals(StatusCode.CORRUPTION,
        TupleIndexRootRecordCodec.decode(bytes, 0, result));
    assertEquals(StatusCode.OK, encode(bytes, TupleIndexRootRecordCodec.STATE_READY, 17, 0));
    FormatBytes.putInt(bytes, 80 + DESCRIPTORS.length * Integer.BYTES, SqlTypeDescriptor.BIGINT);
    assertEquals(StatusCode.CORRUPTION,
        TupleIndexRootRecordCodec.decode(bytes, 0, result));
  }

  @Test
  void roundTripsOnlyCanonicalDroppingCleanupCursors() {
    ByteBuffer bytes = ByteBuffer.allocate(TupleIndexRootRecordCodec.BYTES);
    TupleIndexRootRecord result = new TupleIndexRootRecord();
    assertEquals(StatusCode.OK,
        TupleIndexRootRecordCodec.encode(
            bytes, 0, TupleIndexRootRecordCodec.STATE_DROPPING, 0,
            7, 11, 13, descriptorHash(), 23, 29, 20,
            DESCRIPTORS, 0, DESCRIPTORS.length));
    assertEquals(StatusCode.OK,
        TupleIndexRootRecordCodec.decode(bytes, 0, result));
    assertEquals(20, result.cleanupCursor());
    assertEquals(13, result.schemaId());

    assertEquals(StatusCode.INVALID_EXTERNAL_INPUT,
        TupleIndexRootRecordCodec.encode(
            bytes, 0, TupleIndexRootRecordCodec.STATE_DROPPING, 0,
            7, 11, 13, descriptorHash(), 23, 29, 3,
            DESCRIPTORS, 0, DESCRIPTORS.length));
    assertEquals(StatusCode.INVALID_EXTERNAL_INPUT,
        TupleIndexRootRecordCodec.encode(
            bytes, 0, TupleIndexRootRecordCodec.STATE_READY, 17,
            7, 11, 13, descriptorHash(), 0, 29, 4,
            DESCRIPTORS, 0, DESCRIPTORS.length));
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
        DESCRIPTORS, 0, DESCRIPTORS.length);
  }

  private static long descriptorHash() {
    TupleShape.Result result = new TupleShape.Result();
    assertEquals(StatusCode.OK, TupleShape.create(DESCRIPTORS, result));
    return result.value().descriptorHash();
  }
}
