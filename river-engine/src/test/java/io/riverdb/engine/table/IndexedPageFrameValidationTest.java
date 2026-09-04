package io.riverdb.engine.table;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.riverdb.base.error.StatusCode;
import io.riverdb.base.id.DatabaseIncarnation;
import io.riverdb.base.id.WalGeneration;
import io.riverdb.base.tuple.TupleShape;
import io.riverdb.base.type.SqlTypeDescriptor;
import io.riverdb.format.btree.TupleBTreePageCodec;
import io.riverdb.format.btree.TupleBTreePageHeader;
import io.riverdb.format.btree.TupleBTreePageValidationProof;
import io.riverdb.format.btree.TupleBTreeLeafEntry;
import io.riverdb.format.btree.TupleKeyBuilder;
import java.nio.ByteBuffer;
import org.junit.jupiter.api.Test;

final class IndexedPageFrameValidationTest {
  @Test
  void exactCopyTransfersValidationIntoOneExclusiveWritableBorrow() {
    TupleShape shape = shape();
    IndexedPageFrame source = validatedFrame(11, 17, shape);
    IndexedPageFrame staging = new IndexedPageFrame();
    staging.beginPageGeneration(12);
    staging.copyPageFrom(source);
    TupleBTreePageValidationProof restored = new TupleBTreePageValidationProof();
    assertEquals(StatusCode.OK,
        staging.restorePageValidation(12, 17, shape.descriptorHash(), 1, restored));
    TupleBTreePageHeader header = new TupleBTreePageHeader();
    TupleBTreeLeafEntry entry = new TupleBTreeLeafEntry();
    assertEquals(StatusCode.OK,
        TupleBTreePageCodec.readValidatedHeader(staging.payload, 0, restored, header));
    assertEquals(StatusCode.OK,
        TupleBTreePageCodec.readValidatedLeaf(staging.payload, 0, header, 0, entry));
    assertEquals(1701, entry.logicalRowId());

    assertEquals(true, staging.beginWritableBorrow());
    assertEquals(false, staging.beginWritableBorrow());
    assertEquals(StatusCode.CONFLICT,
        staging.restorePageValidation(12, 17, shape.descriptorHash(), 1, restored));
    assertEquals(StatusCode.OK,
        staging.consumeMutationInputValidation(
            12, 17, shape.descriptorHash(), 1, restored));
    TupleBTreePageValidationProof miss = new TupleBTreePageValidationProof();
    assertEquals(StatusCode.CONFLICT,
        staging.consumeMutationInputValidation(
            12, 17, shape.descriptorHash(), 1, miss));
    assertEquals(StatusCode.OK,
        staging.sealMutationValidation(
            12, 17, shape.descriptorHash(), 1, restored));
    assertEquals(StatusCode.CONFLICT,
        staging.restorePageValidation(12, 17, shape.descriptorHash(), 1, miss));
    assertEquals(StatusCode.OK, staging.endWritableBorrow());

    assertEquals(StatusCode.OK,
        staging.restorePageValidation(12, 17, shape.descriptorHash(), 1, restored));
    assertEquals(true, staging.beginWritableBorrow());
    assertEquals(StatusCode.OK,
        staging.consumeMutationInputValidation(
            12, 17, shape.descriptorHash(), 1, restored));
    assertEquals(StatusCode.OK, staging.endWritableBorrow());
  }

  @Test
  void mismatchedLineageIsConsumedAndCannotAuthorizeALaterMutation() {
    TupleShape shape = shape();
    IndexedPageFrame frame = validatedFrame(23, 29, shape);
    TupleBTreePageValidationProof restored = new TupleBTreePageValidationProof();
    assertEquals(true, frame.beginWritableBorrow());

    assertEquals(StatusCode.CONFLICT,
        frame.consumeMutationInputValidation(
            23, 30, shape.descriptorHash(), 1, restored));
    assertEquals(StatusCode.CONFLICT,
        frame.consumeMutationInputValidation(
            23, 29, shape.descriptorHash(), 1, restored));
    frame.endWritableBorrow();
  }

  @Test
  void pageGenerationChangeInvalidatesValidationLineage() {
    TupleShape shape = shape();
    IndexedPageFrame frame = validatedFrame(37, 41, shape);
    TupleBTreePageValidationProof restored = new TupleBTreePageValidationProof();
    assertEquals(StatusCode.OK,
        frame.restorePageValidation(37, 41, shape.descriptorHash(), 1, restored));

    frame.beginPageGeneration(38);

    assertEquals(StatusCode.CONFLICT,
        frame.restorePageValidation(37, 41, shape.descriptorHash(), 1, restored));
    assertEquals(StatusCode.CONFLICT,
        frame.restorePageValidation(38, 41, shape.descriptorHash(), 1, restored));
  }

  @Test
  void restoredDependentCannotBeRememberedBackIntoItsCacheRoot() {
    TupleShape shape = shape();
    IndexedPageFrame frame = validatedFrame(39, 43, shape);
    TupleBTreePageValidationProof restored = new TupleBTreePageValidationProof();
    assertEquals(StatusCode.OK,
        frame.restorePageValidation(39, 43, shape.descriptorHash(), 1, restored));

    assertEquals(StatusCode.INVALID_EXTERNAL_INPUT, frame.rememberPageValidation(
        43, shape.descriptorHash(), TupleBTreePageCodec.TYPE_LEAF, restored));

    TupleBTreePageValidationProof second = new TupleBTreePageValidationProof();
    assertEquals(StatusCode.OK,
        frame.restorePageValidation(39, 43, shape.descriptorHash(), 1, second));
    TupleBTreePageHeader header = new TupleBTreePageHeader();
    assertEquals(StatusCode.OK,
        TupleBTreePageCodec.readValidatedHeader(frame.payload, 0, second, header));
  }

  @Test
  void revokedSourceCopiesBytesButCannotTransferOrPreserveAuthority() {
    TupleShape shape = shape();
    IndexedPageFrame source = validatedFrame(43, 47, shape);
    IndexedPageFrame target = validatedFrame(44, 47, shape);
    TupleBTreePageValidationProof oldTargetProof = new TupleBTreePageValidationProof();
    assertEquals(StatusCode.OK,
        target.restorePageValidation(44, 47, shape.descriptorHash(), 1, oldTargetProof));
    TupleBTreePageHeader oldTargetHeader = new TupleBTreePageHeader();
    assertEquals(StatusCode.OK, TupleBTreePageCodec.readValidatedHeader(
        target.payload, 0, oldTargetProof, oldTargetHeader));

    source.validation.invalidateReadable();
    target.copyPageFrom(source);

    TupleBTreeLeafEntry entry = new TupleBTreeLeafEntry();
    assertEquals(StatusCode.INVALID_EXTERNAL_INPUT, TupleBTreePageCodec.readValidatedLeaf(
        target.payload, 0, oldTargetHeader, 0, entry));
    TupleBTreePageValidationProof restored = new TupleBTreePageValidationProof();
    assertEquals(StatusCode.CONFLICT,
        target.restorePageValidation(44, 47, shape.descriptorHash(), 1, restored));
    TupleBTreePageHeader validated = new TupleBTreePageHeader();
    assertEquals(StatusCode.OK, TupleBTreePageCodec.validateForRead(
        target.payload, 0, 47, shape, validated, restored));
    assertEquals(StatusCode.OK,
        TupleBTreePageCodec.readValidatedLeaf(target.payload, 0, validated, 0, entry));
    assertEquals(1701, entry.logicalRowId());
  }

  @Test
  void pageGenerationExhaustionFencesInsteadOfReusingAnIdentity() throws Exception {
    io.riverdb.engine.runtime.DatabasePageCachePlan config =
        io.riverdb.engine.runtime.DatabasePageCacheTestPlan.geometry(2, 2, 2);
    IndexedPageFrameCache cache = new IndexedPageFrameCache(
        null, null, DatabaseIncarnation.of(1, 2), WalGeneration.of(1),
        new IndexedPageState(config), config);
    java.lang.reflect.Field clock =
        IndexedPageFrameCache.class.getDeclaredField("pageGenerationClock");
    clock.setAccessible(true);
    clock.setLong(cache, Long.MAX_VALUE - 1);

    assertEquals(Long.MAX_VALUE, cache.nextPageGeneration());
    assertEquals(0, cache.nextPageGeneration());
    assertEquals(StatusCode.FENCED, cache.lastStatus());
    assertEquals(0, cache.nextPageGeneration());
  }

  private static IndexedPageFrame validatedFrame(
      long generation, long schemaId, TupleShape shape) {
    IndexedPageFrame frame = new IndexedPageFrame();
    frame.beginPageGeneration(generation);
    assertEquals(StatusCode.OK, TupleBTreePageCodec.initializeLeaf(
        frame.payload, 0, 0, 0, shape, schemaId, null, 0, 0));
    ByteBuffer key = ByteBuffer.allocate(64);
    TupleKeyBuilder builder = new TupleKeyBuilder();
    assertEquals(StatusCode.OK, builder.beginIndex(key, 0, 1));
    assertEquals(StatusCode.OK, builder.addFixed(SqlTypeDescriptor.BIGINT, 17));
    assertEquals(StatusCode.OK, builder.finishPhysical(1701));
    assertEquals(StatusCode.OK, TupleBTreePageCodec.appendLeaf(
        frame.payload, 0, shape, key, 0, builder.keyBytes()));
    TupleBTreePageHeader header = new TupleBTreePageHeader();
    TupleBTreePageValidationProof proof = new TupleBTreePageValidationProof();
    assertEquals(StatusCode.OK, TupleBTreePageCodec.validateForRead(
        frame.payload, 0, schemaId, shape, header, proof));
    assertEquals(StatusCode.OK, frame.rememberPageValidation(
        schemaId, shape.descriptorHash(), TupleBTreePageCodec.TYPE_LEAF, proof));
    return frame;
  }

  private static TupleShape shape() {
    TupleShape.Result result = new TupleShape.Result();
    assertEquals(StatusCode.OK,
        TupleShape.create(new int[] {SqlTypeDescriptor.BIGINT}, result));
    return result.value();
  }
}
