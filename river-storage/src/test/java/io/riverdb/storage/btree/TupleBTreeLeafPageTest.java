package io.riverdb.storage.btree;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.riverdb.base.error.StatusCode;
import io.riverdb.base.tuple.TupleShape;
import io.riverdb.base.type.SqlTypeDescriptor;
import io.riverdb.format.btree.TupleBTreeLeafEntry;
import io.riverdb.format.btree.TupleBTreePageCodec;
import io.riverdb.format.btree.TupleBTreePageHeader;
import io.riverdb.format.btree.TupleBTreePageValidationProof;
import io.riverdb.format.btree.TupleKeyBuilder;
import io.riverdb.format.page.PageCodec;
import java.nio.ByteBuffer;
import org.junit.jupiter.api.Test;

final class TupleBTreeLeafPageTest {
  private static final long SCHEMA_ID = 41;

  @Test
  void insertsOutOfOrderSeeksPrefixScansAndDeletes() {
    TupleShape shape = shape(new int[] {
        SqlTypeDescriptor.BIGINT, SqlTypeDescriptor.BIGINT});
    TupleShape prefixShape = shape(new int[] {SqlTypeDescriptor.BIGINT});
    ByteBuffer page = page(shape);
    ByteBuffer keys = ByteBuffer.allocate(512);
    int key31 = key(keys, 0, 3, 1, 31);
    int key12 = key(keys, 64, 1, 2, 12);
    int key11 = key(keys, 128, 1, 1, 11);
    int key21 = key(keys, 192, 2, 1, 21);
    TupleBTreeWorkspace workspace = new TupleBTreeWorkspace();
    assertEquals(StatusCode.OK, TupleBTreeLeafPage.insert(
        page, 0, SCHEMA_ID, shape, keys, 0, key31, workspace));
    assertEquals(StatusCode.OK, TupleBTreeLeafPage.insert(
        page, 0, SCHEMA_ID, shape, keys, 64, key12, workspace));
    assertEquals(StatusCode.OK, TupleBTreeLeafPage.insert(
        page, 0, SCHEMA_ID, shape, keys, 128, key11, workspace));
    assertEquals(StatusCode.OK, TupleBTreeLeafPage.insert(
        page, 0, SCHEMA_ID, shape, keys, 192, key21, workspace));

    TupleBTreeLookupResult lookup = new TupleBTreeLookupResult();
    assertEquals(StatusCode.OK, TupleBTreeLeafPage.lookupExact(
        page, 0, SCHEMA_ID, shape, keys, 64, key12, workspace, lookup));
    assertEquals(12, lookup.logicalRowId());
    assertEquals(StatusCode.CONFLICT, TupleBTreeLeafPage.insert(
        page, 0, SCHEMA_ID, shape, keys, 64, key12, workspace));

    TupleKeyBuilder prefixBuilder = new TupleKeyBuilder();
    assertEquals(StatusCode.OK, prefixBuilder.beginTuple(keys, 320, 1));
    assertEquals(StatusCode.OK, prefixBuilder.addFixed(SqlTypeDescriptor.BIGINT, 1));
    assertEquals(StatusCode.OK, prefixBuilder.finishTuple());
    TupleBTreeRange range = new TupleBTreeRange();
    assertEquals(StatusCode.OK, TupleBTreeLeafPage.prefixRange(
        page, 0, SCHEMA_ID, shape,
        keys, 320, prefixBuilder.keyBytes(), prefixShape, workspace, range));
    assertEquals(2, range.count());
    TupleBTreeCursor cursor = new TupleBTreeCursor();
    assertEquals(StatusCode.OK, cursor.open(
        page, 0, workspace.header, range.first(), range.limit()));
    TupleBTreeLeafEntry entry = new TupleBTreeLeafEntry();
    assertEquals(StatusCode.OK, cursor.next(entry));
    assertEquals(11, entry.logicalRowId());
    assertEquals(StatusCode.OK, cursor.next(entry));
    assertEquals(12, entry.logicalRowId());
    assertEquals(StatusCode.CONFLICT, cursor.next(entry));

    assertEquals(StatusCode.OK, TupleBTreeLeafPage.delete(
        page, 0, SCHEMA_ID, shape, keys, 128, key11, workspace));
    assertEquals(StatusCode.CONFLICT, TupleBTreeLeafPage.lookupExact(
        page, 0, SCHEMA_ID, shape, keys, 128, key11, workspace, lookup));
    assertEquals(StatusCode.OK, TupleBTreePageCodec.validate(
        page, 0, SCHEMA_ID, shape, workspace.header));
  }

  @Test
  void splitsFullVariableKeyPageAndPreservesFenceOrdering() {
    TupleShape shape = shape(new int[] {SqlTypeDescriptor.BIGINT});
    ByteBuffer source = page(shape);
    ByteBuffer keys = ByteBuffer.allocate(128);
    TupleBTreeWorkspace workspace = new TupleBTreeWorkspace();
    int value = 1;
    int length;
    while (true) {
      length = key(keys, 0, value, value);
      StatusCode status = TupleBTreePageCodec.appendLeaf(
          source, 0, shape, keys, 0, length);
      if (status == StatusCode.RESOURCE_EXHAUSTED) break;
      assertEquals(StatusCode.OK, status);
      value++;
    }
    ByteBuffer left = ByteBuffer.allocate(PageCodec.MAX_PAYLOAD_BYTES);
    ByteBuffer right = ByteBuffer.allocate(PageCodec.MAX_PAYLOAD_BYTES);
    TupleBTreeSplitResult split = new TupleBTreeSplitResult();
    assertEquals(StatusCode.OK, TupleBTreeLeafPage.splitInsert(
        source, 0, left, 0, right, 0, 16, 17, SCHEMA_ID, shape,
        keys, 0, length, workspace, split));
    assertTrue(split.leftCount() > 0);
    assertTrue(split.rightCount() > 0);
    assertEquals(StatusCode.OK, TupleBTreePageCodec.validate(
        left, 0, SCHEMA_ID, shape, workspace.header));
    assertEquals(0, workspace.header.leftSiblingPageId());
    assertEquals(17, workspace.header.rightSiblingPageId());
    assertEquals(StatusCode.OK, TupleBTreePageCodec.validate(
        right, 0, SCHEMA_ID, shape, workspace.header));
    assertEquals(16, workspace.header.leftSiblingPageId());
    assertEquals(0, workspace.header.rightSiblingPageId());
    TupleBTreeLookupResult lookup = new TupleBTreeLookupResult();
    assertEquals(StatusCode.OK, TupleBTreeLeafPage.lookupExact(
        right, 0, SCHEMA_ID, shape, keys, 0, length, workspace, lookup));
    assertEquals(value, lookup.logicalRowId());
  }

  @Test
  void oversizedIndexKeyLeavesPageUnchanged() {
    int text = SqlTypeDescriptor.varchar(255);
    TupleShape shape = shape(new int[] {text, text, text});
    ByteBuffer page = page(shape);
    ByteBuffer key = ByteBuffer.allocate(4_096);
    TupleKeyBuilder builder = new TupleKeyBuilder();
    assertEquals(StatusCode.OK, builder.beginIndex(key, 0, 3));
    assertEquals(StatusCode.OK, builder.addText(text, "a".repeat(255)));
    assertEquals(StatusCode.OK, builder.addText(text, "b".repeat(254)));
    assertEquals(StatusCode.OK, builder.addText(text, "c".repeat(254)));
    assertEquals(StatusCode.RESOURCE_EXHAUSTED, builder.finishPhysical(1));
    TupleBTreeWorkspace workspace = new TupleBTreeWorkspace();
    assertEquals(StatusCode.INVALID_EXTERNAL_INPUT, TupleBTreeLeafPage.insert(
        page, 0, SCHEMA_ID, shape, key, 0, 3_081, workspace));
    assertEquals(StatusCode.OK, TupleBTreePageCodec.validate(
        page, 0, SCHEMA_ID, shape, workspace.header));
    assertEquals(0, workspace.header.entryCount());
  }

  @Test
  void noOpMutationsRevokeValidatedReadState() {
    TupleShape shape = shape(new int[] {SqlTypeDescriptor.BIGINT});
    ByteBuffer page = page(shape);
    ByteBuffer keys = ByteBuffer.allocate(128);
    int present = key(keys, 0, 7, 71);
    int missing = key(keys, 64, 8, 81);
    TupleBTreeWorkspace workspace = new TupleBTreeWorkspace();
    TupleBTreeLeafEntry entry = new TupleBTreeLeafEntry();
    assertEquals(StatusCode.OK, TupleBTreeLeafPage.insert(
        page, 0, SCHEMA_ID, shape, keys, 0, present, workspace));

    assertEquals(StatusCode.CONFLICT, TupleBTreeLeafPage.insert(
        page, 0, SCHEMA_ID, shape, keys, 0, present, workspace));
    assertEquals(StatusCode.INVALID_EXTERNAL_INPUT,
        TupleBTreePageCodec.readValidatedLeaf(page, 0, workspace.header, 0, entry));

    assertEquals(StatusCode.CONFLICT, TupleBTreeLeafPage.delete(
        page, 0, SCHEMA_ID, shape, keys, 64, missing, workspace));
    assertEquals(StatusCode.INVALID_EXTERNAL_INPUT,
        TupleBTreePageCodec.readValidatedLeaf(page, 0, workspace.header, 0, entry));
  }

  @Test
  void releasedAndReusedBorrowCannotRetainValidationAuthority() {
    TupleShape shape = shape(new int[] {SqlTypeDescriptor.BIGINT});
    TupleBTreeTestPageProvider provider = new TupleBTreeTestPageProvider(2);
    TupleBTreePageReference reference = new TupleBTreePageReference();
    assertEquals(StatusCode.OK, provider.allocate(reference));
    assertEquals(StatusCode.OK, TupleBTreePageCodec.initializeLeaf(
        reference.page(), reference.start(), 0, 0,
        shape, SCHEMA_ID, null, 0, 0));
    ByteBuffer key = ByteBuffer.allocate(64);
    int keyLength = key(key, 0, 9, 91);
    assertEquals(StatusCode.OK, TupleBTreePageCodec.appendLeaf(
        reference.page(), reference.start(), shape, key, 0, keyLength));
    assertEquals(StatusCode.OK,
        TupleBTreeProviderAccess.release(provider, reference, StatusCode.OK));

    TupleBTreePageHeader releasedHeader = new TupleBTreePageHeader();
    assertEquals(StatusCode.OK, provider.pin(1, false, reference));
    assertEquals(StatusCode.OK, TupleBTreePageAdmission.validate(
        reference.page(), reference.start(), SCHEMA_ID, shape,
        TupleBTreePageCodec.TYPE_LEAF, releasedHeader, provider, reference));
    ByteBuffer page = reference.page();
    TupleBTreeLeafEntry entry = new TupleBTreeLeafEntry();
    assertEquals(StatusCode.OK,
        TupleBTreeProviderAccess.release(provider, reference, StatusCode.OK));
    assertEquals(StatusCode.INVALID_EXTERNAL_INPUT,
        TupleBTreePageCodec.readValidatedLeaf(page, 0, releasedHeader, 0, entry));

    assertEquals(StatusCode.OK, provider.bumpPageGeneration(1));
    TupleBTreePageHeader currentHeader = new TupleBTreePageHeader();
    assertEquals(StatusCode.OK, provider.pin(1, false, reference));
    assertEquals(StatusCode.OK, TupleBTreePageAdmission.validate(
        reference.page(), reference.start(), SCHEMA_ID, shape,
        TupleBTreePageCodec.TYPE_LEAF, currentHeader, provider, reference));
    assertEquals(StatusCode.INVALID_EXTERNAL_INPUT,
        TupleBTreePageCodec.readValidatedLeaf(page, 0, releasedHeader, 0, entry));
    assertEquals(StatusCode.OK,
        TupleBTreeProviderAccess.release(provider, reference, StatusCode.OK));
  }

  @Test
  void testProviderFencesTerminalPageGenerationWithoutChangingThePage()
      throws Exception {
    TupleShape shape = shape(new int[] {SqlTypeDescriptor.BIGINT});
    TupleBTreeTestPageProvider provider = new TupleBTreeTestPageProvider(2);
    TupleBTreePageReference reference = new TupleBTreePageReference();
    assertEquals(StatusCode.OK, provider.allocate(reference));
    assertEquals(StatusCode.OK, TupleBTreePageCodec.initializeLeaf(
        reference.page(), reference.start(), 0, 0,
        shape, SCHEMA_ID, null, 0, 0));
    ByteBuffer key = ByteBuffer.allocate(64);
    int keyLength = key(key, 0, 15, 151);
    assertEquals(StatusCode.OK, TupleBTreePageCodec.appendLeaf(
        reference.page(), reference.start(), shape, key, 0, keyLength));
    assertEquals(StatusCode.OK,
        TupleBTreeProviderAccess.release(provider, reference, StatusCode.OK));

    java.lang.reflect.Field field =
        TupleBTreeTestPageProvider.class.getDeclaredField("generations");
    field.setAccessible(true);
    long[] generations = (long[]) field.get(provider);
    generations[1] = Long.MAX_VALUE - 1;
    byte[] before = new byte[PageCodec.MAX_PAYLOAD_BYTES];
    ByteBuffer page = provider.page(1);
    for (int index = 0; index < before.length; index++) before[index] = page.get(index);

    assertEquals(StatusCode.OK, provider.bumpPageGeneration(1));
    assertEquals(StatusCode.FENCED, provider.bumpPageGeneration(1));
    assertEquals(StatusCode.FENCED, provider.bumpPageGeneration(1));

    byte[] after = new byte[before.length];
    for (int index = 0; index < after.length; index++) after[index] = page.get(index);
    assertArrayEquals(before, after);
    assertEquals(StatusCode.OK, provider.pin(1, false, reference));
    assertEquals(Long.MAX_VALUE, reference.pageGeneration());
    TupleBTreeWorkspace workspace = new TupleBTreeWorkspace();
    assertEquals(StatusCode.OK, TupleBTreePageAdmission.validate(
        reference.page(), reference.start(), SCHEMA_ID, shape,
        TupleBTreePageCodec.TYPE_LEAF, workspace, provider, reference));
    TupleBTreeLeafEntry entry = new TupleBTreeLeafEntry();
    assertEquals(StatusCode.OK, TupleBTreePageCodec.readValidatedLeaf(
        reference.page(), reference.start(), workspace.header, 0, entry));
    assertEquals(151, entry.logicalRowId());
    assertEquals(StatusCode.OK,
        TupleBTreeProviderAccess.release(provider, reference, StatusCode.OK));
  }

  @Test
  void providerValidationRejectsDetachedForeignAndRevokedPageState() {
    TupleShape shape = shape(new int[] {SqlTypeDescriptor.BIGINT});
    TupleBTreeTestPageProvider provider = new TupleBTreeTestPageProvider(2);
    TupleBTreePageReference reference = new TupleBTreePageReference();
    assertEquals(StatusCode.OK, provider.allocate(reference));
    assertEquals(StatusCode.OK, TupleBTreePageCodec.initializeLeaf(
        reference.page(), reference.start(), 0, 0,
        shape, SCHEMA_ID, null, 0, 0));
    ByteBuffer key = ByteBuffer.allocate(64);
    int keyLength = key(key, 0, 9, 91);
    assertEquals(StatusCode.OK, TupleBTreePageCodec.appendLeaf(
        reference.page(), reference.start(), shape, key, 0, keyLength));
    TupleBTreeWorkspace workspace = new TupleBTreeWorkspace();
    assertEquals(StatusCode.INVALID_EXTERNAL_INPUT, TupleBTreePageAdmission.validate(
        reference.page().duplicate(), reference.start(), SCHEMA_ID, shape,
        TupleBTreePageCodec.TYPE_LEAF, workspace, provider, reference));
    ByteBuffer page = reference.page();
    assertEquals(StatusCode.OK,
        TupleBTreeProviderAccess.release(provider, reference, StatusCode.OK));
    assertEquals(StatusCode.INVALID_EXTERNAL_INPUT, TupleBTreePageAdmission.validate(
        page, 0, SCHEMA_ID, shape, TupleBTreePageCodec.TYPE_LEAF,
        workspace, provider, reference));

    assertEquals(StatusCode.OK, provider.pin(1, false, reference));
    assertEquals(StatusCode.OK, TupleBTreePageAdmission.validate(
        reference.page(), reference.start(), SCHEMA_ID, shape,
        TupleBTreePageCodec.TYPE_LEAF, workspace, provider, reference));
    assertEquals(StatusCode.INVALID_EXTERNAL_INPUT, TupleBTreePageAdmission.validate(
        reference.page(), reference.start(), SCHEMA_ID, shape,
        TupleBTreePageCodec.TYPE_INTERNAL, workspace, provider, reference));
    TupleBTreeLeafEntry entry = new TupleBTreeLeafEntry();
    assertEquals(StatusCode.INVALID_EXTERNAL_INPUT, TupleBTreePageCodec.readValidatedLeaf(
        reference.page(), reference.start(), workspace.header, 0, entry));
    assertEquals(StatusCode.OK, TupleBTreePageAdmission.validate(
        reference.page(), reference.start(), SCHEMA_ID, shape,
        TupleBTreePageCodec.TYPE_LEAF, workspace, provider, reference));
    reference.validation().reset();
    assertEquals(-1, TupleBTreePageSupport.lowerBoundLeaf(
        reference.page(), reference.start(), key, 0, keyLength, workspace));
    assertEquals(StatusCode.OK,
        TupleBTreeProviderAccess.release(provider, reference, StatusCode.OK));
  }

  @Test
  void releaseRevokesPreparedMutationAndGenerationCannotChangeDuringBorrow() {
    TupleShape shape = shape(new int[] {SqlTypeDescriptor.BIGINT});
    TupleBTreeTestPageProvider provider = new TupleBTreeTestPageProvider(2);
    TupleBTreePageReference reference = new TupleBTreePageReference();
    assertEquals(StatusCode.OK, provider.allocate(reference));
    assertEquals(StatusCode.OK, TupleBTreePageCodec.initializeLeaf(
        reference.page(), reference.start(), 0, 0,
        shape, SCHEMA_ID, null, 0, 0));
    assertEquals(StatusCode.OK,
        TupleBTreeProviderAccess.release(provider, reference, StatusCode.OK));

    TupleBTreeWorkspace workspace = new TupleBTreeWorkspace();
    assertEquals(StatusCode.OK, provider.pin(1, false, reference));
    assertEquals(StatusCode.OK, TupleBTreePageAdmission.validate(
        reference.page(), reference.start(), SCHEMA_ID, shape,
        TupleBTreePageCodec.TYPE_LEAF, workspace, provider, reference));
    assertEquals(StatusCode.OK,
        TupleBTreeProviderAccess.release(provider, reference, StatusCode.OK));

    assertEquals(StatusCode.OK, provider.pin(1, true, reference));
    assertEquals(StatusCode.CONFLICT, provider.bumpPageGeneration(1));
    assertEquals(StatusCode.OK, provider.consumeCanonicalMutationValidation(
        reference, SCHEMA_ID, shape.descriptorHash(),
        TupleBTreePageCodec.TYPE_LEAF, reference.validation()));
    assertEquals(StatusCode.OK, TupleBTreePageCodec.prepareAuthenticatedLeafMutation(
        reference.page(), reference.start(), SCHEMA_ID, shape,
        workspace.header, reference.validation(), workspace.mutation));
    ByteBuffer page = reference.page();
    assertEquals(StatusCode.OK,
        TupleBTreeProviderAccess.release(provider, reference, StatusCode.OK));

    ByteBuffer key = ByteBuffer.allocate(64);
    int keyLength = key(key, 0, 11, 111);
    assertEquals(StatusCode.INVALID_EXTERNAL_INPUT,
        TupleBTreePageCodec.insertPreparedLeaf(
            page, 0, SCHEMA_ID, shape, key, 0, keyLength, 0, workspace.mutation));
    assertEquals(StatusCode.OK, provider.bumpPageGeneration(1));
    assertEquals(StatusCode.OK,
        TupleBTreePageCodec.validate(page, 0, SCHEMA_ID, shape, workspace.header));
    assertEquals(0, workspace.header.entryCount());
  }

  @Test
  void failedReleaseRetainsAuthorityUntilTheBorrowActuallyEnds() {
    TupleShape shape = shape(new int[] {SqlTypeDescriptor.BIGINT});
    TupleBTreeTestPageProvider provider = new TupleBTreeTestPageProvider(2);
    TupleBTreePageReference reference = new TupleBTreePageReference();
    assertEquals(StatusCode.OK, provider.allocate(reference));
    assertEquals(StatusCode.OK, TupleBTreePageCodec.initializeLeaf(
        reference.page(), reference.start(), 0, 0,
        shape, SCHEMA_ID, null, 0, 0));
    ByteBuffer key = ByteBuffer.allocate(64);
    int keyLength = key(key, 0, 13, 131);
    assertEquals(StatusCode.OK, TupleBTreePageCodec.appendLeaf(
        reference.page(), reference.start(), shape, key, 0, keyLength));
    assertEquals(StatusCode.OK,
        TupleBTreeProviderAccess.release(provider, reference, StatusCode.OK));

    TupleBTreeWorkspace workspace = new TupleBTreeWorkspace();
    assertEquals(StatusCode.OK, provider.pin(1, false, reference));
    assertEquals(StatusCode.OK, TupleBTreePageAdmission.validate(
        reference.page(), reference.start(), SCHEMA_ID, shape,
        TupleBTreePageCodec.TYPE_LEAF, workspace, provider, reference));
    ByteBuffer page = reference.page();
    TupleBTreeLeafEntry entry = new TupleBTreeLeafEntry();
    provider.failNextRelease();
    assertEquals(StatusCode.IO_FAILURE,
        TupleBTreeProviderAccess.release(provider, reference, StatusCode.OK));
    assertTrue(reference.isAttached());
    assertEquals(StatusCode.OK,
        TupleBTreePageCodec.readValidatedLeaf(page, 0, workspace.header, 0, entry));
    assertEquals(131, entry.logicalRowId());

    assertEquals(StatusCode.OK,
        TupleBTreeProviderAccess.release(provider, reference, StatusCode.OK));
    assertEquals(StatusCode.INVALID_EXTERNAL_INPUT,
        TupleBTreePageCodec.readValidatedLeaf(page, 0, workspace.header, 0, entry));
  }

  @Test
  void writableValidationIsPublishedOnlyWhenReleaseSucceeds() {
    TupleShape shape = shape(new int[] {SqlTypeDescriptor.BIGINT});
    TupleBTreeTestPageProvider provider = new TupleBTreeTestPageProvider(2);
    TupleBTreePageReference reference = new TupleBTreePageReference();
    assertEquals(StatusCode.OK, provider.allocate(reference));
    assertEquals(StatusCode.OK, TupleBTreePageCodec.initializeLeaf(
        reference.page(), reference.start(), 0, 0,
        shape, SCHEMA_ID, null, 0, 0));
    assertEquals(StatusCode.OK,
        TupleBTreeProviderAccess.release(provider, reference, StatusCode.OK));

    TupleBTreeWorkspace workspace = new TupleBTreeWorkspace();
    assertEquals(StatusCode.OK, provider.pin(1, false, reference));
    assertEquals(StatusCode.OK, TupleBTreePageAdmission.validate(
        reference.page(), reference.start(), SCHEMA_ID, shape,
        TupleBTreePageCodec.TYPE_LEAF, workspace, provider, reference));
    int validations = provider.validationCount();
    assertEquals(StatusCode.OK,
        TupleBTreeProviderAccess.release(provider, reference, StatusCode.OK));

    ByteBuffer keys = ByteBuffer.allocate(128);
    int firstLength = key(keys, 0, 17, 171);
    int secondLength = key(keys, 64, 19, 191);
    assertEquals(StatusCode.OK, provider.pin(1, true, reference));
    assertEquals(StatusCode.OK, TupleBTreeLeafPage.insertBorrowed(
        reference.page(), reference.start(), SCHEMA_ID, shape,
        keys, 0, firstLength, workspace, provider, reference));
    assertEquals(StatusCode.OK, provider.sealCanonicalMutation(
        reference, SCHEMA_ID, shape.descriptorHash(),
        TupleBTreePageCodec.TYPE_LEAF, reference.validation()));
    provider.failNextRelease();
    assertEquals(StatusCode.IO_FAILURE,
        TupleBTreeProviderAccess.release(provider, reference, StatusCode.OK));
    TupleBTreePageValidationProof restored = new TupleBTreePageValidationProof();
    assertEquals(StatusCode.CONFLICT, provider.restorePageValidation(
        reference, SCHEMA_ID, shape.descriptorHash(),
        TupleBTreePageCodec.TYPE_LEAF, restored));

    assertEquals(StatusCode.OK, TupleBTreeLeafPage.insertBorrowed(
        reference.page(), reference.start(), SCHEMA_ID, shape,
        keys, 64, secondLength, workspace, provider, reference));
    assertEquals(StatusCode.OK, provider.sealCanonicalMutation(
        reference, SCHEMA_ID, shape.descriptorHash(),
        TupleBTreePageCodec.TYPE_LEAF, reference.validation()));
    assertEquals(StatusCode.OK,
        TupleBTreeProviderAccess.release(provider, reference, StatusCode.OK));
    assertEquals(1, provider.canonicalSealCount());

    assertEquals(StatusCode.OK, provider.pin(1, false, reference));
    assertEquals(StatusCode.OK, TupleBTreePageAdmission.validate(
        reference.page(), reference.start(), SCHEMA_ID, shape,
        TupleBTreePageCodec.TYPE_LEAF, workspace, provider, reference));
    assertEquals(validations, provider.validationCount());
    assertEquals(2, workspace.header.entryCount());
    assertEquals(StatusCode.OK,
        TupleBTreeProviderAccess.release(provider, reference, StatusCode.OK));
  }

  private static ByteBuffer page(TupleShape shape) {
    ByteBuffer page = ByteBuffer.allocate(PageCodec.MAX_PAYLOAD_BYTES);
    assertEquals(StatusCode.OK, TupleBTreePageCodec.initialize(
        page, 0, TupleBTreePageCodec.TYPE_LEAF, 0,
        shape, SCHEMA_ID, null, 0, 0));
    return page;
  }

  private static int key(
      ByteBuffer target, int offset, long first, long second, long rowId) {
    TupleKeyBuilder builder = new TupleKeyBuilder();
    assertEquals(StatusCode.OK, builder.beginIndex(target, offset, 2));
    assertEquals(StatusCode.OK, builder.addFixed(SqlTypeDescriptor.BIGINT, first));
    assertEquals(StatusCode.OK, builder.addFixed(SqlTypeDescriptor.BIGINT, second));
    assertEquals(StatusCode.OK, builder.finishPhysical(rowId));
    return builder.keyBytes();
  }

  private static int key(ByteBuffer target, int offset, long value, long rowId) {
    TupleKeyBuilder builder = new TupleKeyBuilder();
    assertEquals(StatusCode.OK, builder.beginIndex(target, offset, 1));
    assertEquals(StatusCode.OK, builder.addFixed(SqlTypeDescriptor.BIGINT, value));
    assertEquals(StatusCode.OK, builder.finishPhysical(rowId));
    return builder.keyBytes();
  }

  private static TupleShape shape(int[] descriptors) {
    TupleShape.Result result = new TupleShape.Result();
    assertEquals(StatusCode.OK, TupleShape.create(descriptors, result));
    return result.value();
  }
}
