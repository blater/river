package io.riverdb.engine.table;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.riverdb.base.error.StatusCode;
import io.riverdb.base.tuple.TupleShape;
import io.riverdb.base.type.SqlTypeDescriptor;
import io.riverdb.format.btree.TupleKeyBuilder;
import java.nio.ByteBuffer;
import org.junit.jupiter.api.Test;

final class IndexedTupleIntentJournalTest {
  @Test
  void maintainsNetActiveStateAcrossReinsertAndSavepointTruncate() {
    TupleShape.Result shape = new TupleShape.Result();
    assertEquals(
        StatusCode.OK,
        TupleShape.create(new int[] {SqlTypeDescriptor.BIGINT}, shape));
    ByteBuffer key = physicalKey(1, 7);
    IndexedTupleIntentJournal journal = new IndexedTupleIntentJournal();
    assertEquals(StatusCode.OK, journal.reserve(0, 3, 1, key.remaining() * 3));

    append(journal, IndexedRelationalMutation.TUPLE_INSERT, shape.value(), key, 1);
    assertTrue(journal.activeAt(0));
    append(journal, IndexedRelationalMutation.TUPLE_DELETE, shape.value(), key, 1);
    assertFalse(journal.activeAt(0));
    assertFalse(journal.activeAt(1));
    append(journal, IndexedRelationalMutation.TUPLE_INSERT, shape.value(), key, 1);
    assertFalse(journal.activeAt(0));
    assertFalse(journal.activeAt(1));
    assertTrue(journal.activeAt(2));
    assertEquals(
        StatusCode.OK,
        journal.prepareCompilation(0, 0, 0, new IndexedRelationalMutation[1]));

    journal.truncate(1, 1, key.remaining());
    assertTrue(journal.activeAt(0));
    append(journal, IndexedRelationalMutation.TUPLE_DELETE, shape.value(), key, 1);
    assertFalse(journal.activeAt(0));
    assertFalse(journal.activeAt(1));
  }

  @Test
  void keepsDifferentLogicalRowsIndependentWhenKeysSharePrefix() {
    TupleShape.Result shape = new TupleShape.Result();
    assertEquals(
        StatusCode.OK,
        TupleShape.create(new int[] {SqlTypeDescriptor.BIGINT}, shape));
    ByteBuffer first = physicalKey(1, 7);
    ByteBuffer second = physicalKey(2, 7);
    IndexedTupleIntentJournal journal = new IndexedTupleIntentJournal();
    assertEquals(StatusCode.OK, journal.reserve(0, 2, 1, first.remaining() * 2));
    append(journal, IndexedRelationalMutation.TUPLE_INSERT, shape.value(), first, 1);
    append(journal, IndexedRelationalMutation.TUPLE_INSERT, shape.value(), second, 2);
    assertTrue(journal.activeAt(0));
    assertTrue(journal.activeAt(1));
    assertEquals(
        1,
        journal.anyInsertPrefixRowId(
            1, shape.value(), first, 0, first.remaining(), 0));
  }

  @Test
  void restoresDeleteHeadWhenSavepointInsertIsTruncated() {
    TupleShape shape = shape();
    ByteBuffer key = physicalKey(1, 7);
    IndexedTupleIntentJournal journal = new IndexedTupleIntentJournal();
    assertEquals(StatusCode.OK, journal.reserve(0, 2, 1, key.remaining() * 2));

    append(journal, IndexedRelationalMutation.TUPLE_DELETE, shape, key, 1);
    append(journal, IndexedRelationalMutation.TUPLE_INSERT, shape, key, 1);
    assertFalse(journal.activeAt(0));
    assertFalse(journal.activeAt(1));

    journal.truncate(1, 1, key.remaining());
    assertTrue(journal.activeAt(0));
    assertEquals(
        StatusCode.OK,
        journal.prepareCompilation(0, 0, 0, new IndexedRelationalMutation[1]));
  }

  @Test
  void restoresEachNestedSavepointHead() {
    TupleShape shape = shape();
    ByteBuffer key = physicalKey(1, 7);
    IndexedTupleIntentJournal journal = new IndexedTupleIntentJournal();
    assertEquals(StatusCode.OK, journal.reserve(0, 3, 1, key.remaining() * 3));

    append(journal, IndexedRelationalMutation.TUPLE_INSERT, shape, key, 1);
    append(journal, IndexedRelationalMutation.TUPLE_DELETE, shape, key, 1);
    append(journal, IndexedRelationalMutation.TUPLE_INSERT, shape, key, 1);
    assertTrue(journal.activeAt(2));

    journal.truncate(2, 1, key.remaining() * 2);
    assertFalse(journal.activeAt(0));
    assertFalse(journal.activeAt(1));

    journal.truncate(1, 1, key.remaining());
    assertTrue(journal.activeAt(0));
  }

  @Test
  void rebuildsNetHeadsAcrossKeyChangeAndChangeBack() {
    TupleShape shape = shape();
    ByteBuffer first = physicalKey(1, 7);
    ByteBuffer second = physicalKey(1, 8);
    IndexedTupleIntentJournal journal = new IndexedTupleIntentJournal();
    int keyBytes = first.remaining();
    assertEquals(StatusCode.OK, journal.reserve(0, 4, 1, keyBytes * 4));

    append(journal, IndexedRelationalMutation.TUPLE_DELETE, shape, first, 1);
    append(journal, IndexedRelationalMutation.TUPLE_INSERT, shape, second, 1);
    append(journal, IndexedRelationalMutation.TUPLE_DELETE, shape, second, 1);
    append(journal, IndexedRelationalMutation.TUPLE_INSERT, shape, first, 1);
    for (int index = 0; index < 4; index++) assertFalse(journal.activeAt(index));

    journal.truncate(2, 1, keyBytes * 2);
    assertTrue(journal.activeAt(0));
    assertTrue(journal.activeAt(1));
  }

  @Test
  void configuredJournalAcceptsMoreThanLegacyMutationLimit() {
    TupleShape shape = shape();
    IndexedTupleIntentJournal journal = new IndexedTupleIntentJournal(512, 1, 512 * 32);
    assertEquals(StatusCode.OK, journal.reserve(0, 385, 1, 385 * 32));
    for (int index = 0; index < 385; index++) {
      ByteBuffer key = physicalKey(index + 1L, index + 1L);
      append(journal, IndexedRelationalMutation.TUPLE_INSERT, shape, key, index + 1L);
    }
    assertEquals(385, journal.mutationCount());
    assertTrue(journal.activeAt(384));
  }

  @Test
  void configuredJournalRetainsMoreThanOneTablesLegacyDescriptorCeiling() {
    TupleShape shape = shape();
    int descriptors = 66;
    int keyBytes = physicalKey(1, 1).remaining();
    IndexedTupleIntentJournal journal =
        new IndexedTupleIntentJournal(descriptors, descriptors, descriptors * keyBytes);
    assertEquals(StatusCode.OK,
        journal.reserve(0, descriptors, descriptors, descriptors * keyBytes));
    for (int index = 0; index < descriptors; index++) {
      long keyId = index + 1L;
      ByteBuffer key = physicalKey(keyId, keyId);
      assertEquals(StatusCode.OK, journal.append(
          IndexedRelationalMutation.TUPLE_INSERT,
          index < 33 ? 1 : 2,
          keyId,
          1,
          shape,
          keyId,
          key,
          0,
          key.remaining()));
    }
    assertEquals(descriptors, journal.descriptorCount());
    assertEquals(StatusCode.OK, journal.descriptorStatus(2, 66, 1, shape));
    IndexedRelationalMutation[] compiled = new IndexedRelationalMutation[1];
    assertEquals(StatusCode.OK, journal.prepareCompilation(0, 0, 0, compiled));
    assertEquals(StatusCode.OK,
        new IndexedHybridDescriptorCompiler().append(journal, compiled[0]));

    journal.truncate(65, 65, 65 * keyBytes);
    assertEquals(StatusCode.CONFLICT, journal.descriptorStatus(2, 66, 1, shape));
    assertEquals(StatusCode.OK, journal.descriptorStatus(2, 65, 1, shape));
  }

  private static TupleShape shape() {
    TupleShape.Result result = new TupleShape.Result();
    assertEquals(
        StatusCode.OK,
        TupleShape.create(new int[] {SqlTypeDescriptor.BIGINT}, result));
    return result.value();
  }

  private static void append(
      IndexedTupleIntentJournal journal,
      int operation,
      TupleShape shape,
      ByteBuffer key,
      long rowId) {
    assertEquals(
        StatusCode.OK,
        journal.append(
            operation,
            1,
            1,
            1,
            shape,
            rowId,
            key,
            0,
            key.remaining()));
  }

  private static ByteBuffer physicalKey(long rowId, long value) {
    ByteBuffer key = ByteBuffer.allocate(32);
    TupleKeyBuilder builder = new TupleKeyBuilder();
    assertEquals(StatusCode.OK, builder.beginIndex(key, 0, 1));
    assertEquals(StatusCode.OK, builder.addFixed(SqlTypeDescriptor.BIGINT, value));
    assertEquals(StatusCode.OK, builder.finishPhysical(rowId));
    key.limit(builder.keyBytes());
    key.position(0);
    return key;
  }
}
