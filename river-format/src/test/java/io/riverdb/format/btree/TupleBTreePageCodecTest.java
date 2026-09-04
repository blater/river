package io.riverdb.format.btree;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.riverdb.base.error.StatusCode;
import io.riverdb.base.tuple.TupleShape;
import io.riverdb.base.type.SqlTypeDescriptor;
import io.riverdb.format.FormatBytes;
import io.riverdb.format.page.PageCodec;
import java.nio.ByteBuffer;
import org.junit.jupiter.api.Test;

final class TupleBTreePageCodecTest {
  @Test
  void mutationCapabilityIsBoundToOneExactPageAndConsumedByEveryAttempt() {
    int[] descriptors = {SqlTypeDescriptor.BIGINT};
    TupleShape shape = shape(descriptors);
    ByteBuffer first = ByteBuffer.allocate(PageCodec.MAX_PAYLOAD_BYTES);
    ByteBuffer second = ByteBuffer.allocate(PageCodec.MAX_PAYLOAD_BYTES);
    ByteBuffer key = ByteBuffer.allocate(128);
    int keyLength = key(key, 0, descriptors, 7, 71);
    assertEquals(StatusCode.OK, TupleBTreePageCodec.initialize(
        first, 0, TupleBTreePageCodec.TYPE_LEAF, 0,
        shape, 9, null, 0, 0));
    assertEquals(StatusCode.OK, TupleBTreePageCodec.initialize(
        second, 0, TupleBTreePageCodec.TYPE_LEAF, 0,
        shape, 9, null, 0, 0));
    TupleBTreePageHeader header = new TupleBTreePageHeader();
    TupleBTreePageMutationCapability capability =
        new TupleBTreePageMutationCapability();
    TupleBTreePageValidationProof proof = new TupleBTreePageValidationProof();
    assertEquals(StatusCode.OK, TupleBTreePageCodec.prepareLeafMutation(
        first, 0, 9, shape, header, proof, capability));

    assertEquals(StatusCode.INVALID_EXTERNAL_INPUT,
        TupleBTreePageCodec.insertPreparedLeaf(
            second, 0, 9, shape, key, 0, keyLength, 0, capability));
    assertEquals(StatusCode.INVALID_EXTERNAL_INPUT,
        TupleBTreePageCodec.insertPreparedLeaf(
            first, 0, 9, shape, key, 0, keyLength, 0, capability));
    assertEquals(StatusCode.OK, TupleBTreePageCodec.validate(
        first, 0, 9, shape, header));
    assertEquals(0, header.entryCount());
    assertEquals(StatusCode.OK, TupleBTreePageCodec.validate(
        second, 0, 9, shape, header));
    assertEquals(0, header.entryCount());

    assertEquals(StatusCode.OK, TupleBTreePageCodec.prepareLeafMutation(
        first, 0, 9, shape, header, proof, capability));
    assertEquals(StatusCode.OK, TupleBTreePageCodec.insertPreparedLeaf(
        first, 0, 9, shape, key, 0, keyLength, 0, capability));
    TupleBTreeLeafEntry leaf = new TupleBTreeLeafEntry();
    assertEquals(StatusCode.INVALID_EXTERNAL_INPUT,
        TupleBTreePageCodec.readValidatedLeaf(first, 0, header, 0, leaf));
    assertEquals(StatusCode.INVALID_EXTERNAL_INPUT,
        TupleBTreePageCodec.deletePreparedLeaf(
            first, 0, 9, shape, 0, capability));
    assertEquals(StatusCode.OK, TupleBTreePageCodec.validate(
        first, 0, 9, shape, header));
    assertEquals(1, header.entryCount());
  }

  @Test
  void revokedProofCannotAuthorizeOrRemintMutation() {
    int[] descriptors = {SqlTypeDescriptor.BIGINT};
    TupleShape shape = shape(descriptors);
    ByteBuffer page = ByteBuffer.allocate(PageCodec.MAX_PAYLOAD_BYTES);
    ByteBuffer key = ByteBuffer.allocate(64);
    int keyLength = key(key, 0, descriptors, 7, 71);
    assertEquals(StatusCode.OK, TupleBTreePageCodec.initializeLeaf(
        page, 0, 0, 0, shape, 31, null, 0, 0));
    TupleBTreePageHeader header = new TupleBTreePageHeader();
    TupleBTreePageValidationProof proof = new TupleBTreePageValidationProof();
    TupleBTreePageMutationCapability capability =
        new TupleBTreePageMutationCapability();

    assertEquals(StatusCode.OK, TupleBTreePageCodec.prepareLeafMutation(
        page, 0, 31, shape, header, proof, capability));
    proof.reset();
    assertEquals(StatusCode.INVALID_EXTERNAL_INPUT,
        TupleBTreePageCodec.insertPreparedLeaf(
            page, 0, 31, shape, key, 0, keyLength, 0, capability));
    assertEquals(StatusCode.OK,
        TupleBTreePageCodec.validate(page, 0, 31, shape, header));
    assertEquals(0, header.entryCount());
    assertEquals(StatusCode.INVALID_EXTERNAL_INPUT,
        TupleBTreePageCodec.readValidatedHeader(page, 0, proof, header));

    assertEquals(StatusCode.OK, TupleBTreePageCodec.prepareLeafMutation(
        page, 0, 31, shape, header, proof, capability));
    assertEquals(StatusCode.OK, TupleBTreePageCodec.insertPreparedLeaf(
        page, 0, 31, shape, key, 0, keyLength, 0, capability));
    assertEquals(StatusCode.OK, TupleBTreePageCodec.prepareLeafMutation(
        page, 0, 31, shape, header, proof, capability));
    proof.reset();
    assertEquals(StatusCode.INVALID_EXTERNAL_INPUT,
        TupleBTreePageCodec.deletePreparedLeaf(
            page, 0, 31, shape, 0, capability));
    assertEquals(StatusCode.OK,
        TupleBTreePageCodec.validate(page, 0, 31, shape, header));
    assertEquals(1, header.entryCount());

    assertEquals(StatusCode.OK, TupleBTreePageCodec.prepareLeafMutation(
        page, 0, 31, shape, header, proof, capability));
    proof.reset();
    assertEquals(StatusCode.INVALID_EXTERNAL_INPUT, capability.sealValidation());
    assertEquals(StatusCode.INVALID_EXTERNAL_INPUT,
        TupleBTreePageCodec.readValidatedHeader(page, 0, proof, header));
  }

  @Test
  void exhaustedProofGenerationCannotRecycleStaleAuthority() throws Exception {
    TupleShape shape = shape(new int[] {SqlTypeDescriptor.BIGINT});
    ByteBuffer page = ByteBuffer.allocate(PageCodec.MAX_PAYLOAD_BYTES);
    assertEquals(StatusCode.OK, TupleBTreePageCodec.initializeLeaf(
        page, 0, 0, 0, shape, 37, null, 0, 0));
    TupleBTreePageValidationProof proof = new TupleBTreePageValidationProof();
    java.lang.reflect.Field version =
        TupleBTreePageValidationProof.class.getDeclaredField("version");
    version.setAccessible(true);
    version.setLong(proof, Long.MAX_VALUE);
    TupleBTreePageHeader header = new TupleBTreePageHeader();

    assertEquals(StatusCode.FENCED,
        TupleBTreePageCodec.validateForRead(page, 0, 37, shape, header, proof));
    assertEquals(StatusCode.INVALID_EXTERNAL_INPUT,
        TupleBTreePageCodec.readValidatedHeader(page, 0, proof, header));
    assertEquals(StatusCode.FENCED,
        TupleBTreePageCodec.validateForRead(page, 0, 37, shape, header, proof));
  }

  @Test
  void mutationResealExhaustionIsFencedBeforeBytesChange() throws Exception {
    TupleShape shape = shape(new int[] {SqlTypeDescriptor.BIGINT});
    ByteBuffer page = ByteBuffer.allocate(PageCodec.MAX_PAYLOAD_BYTES);
    assertEquals(StatusCode.OK, TupleBTreePageCodec.initializeLeaf(
        page, 0, 0, 0, shape, 39, null, 0, 0));
    TupleBTreePageValidationProof proof = new TupleBTreePageValidationProof();
    java.lang.reflect.Field version =
        TupleBTreePageValidationProof.class.getDeclaredField("version");
    version.setAccessible(true);
    version.setLong(proof, Long.MAX_VALUE - 2);
    TupleBTreePageHeader header = new TupleBTreePageHeader();
    TupleBTreePageMutationCapability capability =
        new TupleBTreePageMutationCapability();

    assertEquals(StatusCode.FENCED, TupleBTreePageCodec.prepareLeafMutation(
        page, 0, 39, shape, header, proof, capability));
    assertEquals(StatusCode.OK, TupleBTreePageCodec.validate(
        page, 0, 39, shape, header));
    assertEquals(0, header.entryCount());
  }

  @Test
  void copiedLendRemainsDependentOnTheOriginalRoot() {
    TupleShape shape = shape(new int[] {SqlTypeDescriptor.BIGINT});
    ByteBuffer page = ByteBuffer.allocate(PageCodec.MAX_PAYLOAD_BYTES);
    assertEquals(StatusCode.OK, TupleBTreePageCodec.initializeLeaf(
        page, 0, 0, 0, shape, 43, null, 0, 0));
    TupleBTreePageHeader header = new TupleBTreePageHeader();
    TupleBTreePageValidationProof root = new TupleBTreePageValidationProof();
    TupleBTreePageValidationProof lent = new TupleBTreePageValidationProof();
    TupleBTreePageValidationProof copied = new TupleBTreePageValidationProof();
    assertEquals(StatusCode.OK,
        TupleBTreePageCodec.validateForRead(page, 0, 43, shape, header, root));
    assertEquals(StatusCode.OK, root.lendTo(page, 0, lent));
    assertEquals(StatusCode.OK, lent.copyTo(page, 0, copied));

    root.reset();

    assertEquals(StatusCode.INVALID_EXTERNAL_INPUT,
        TupleBTreePageCodec.readValidatedHeader(page, 0, lent, header));
    assertEquals(StatusCode.INVALID_EXTERNAL_INPUT,
        TupleBTreePageCodec.readValidatedHeader(page, 0, copied, header));
  }

  @Test
  void dependentProofCannotCopyIntoItsOwnActiveRoot() {
    TupleShape shape = shape(new int[] {SqlTypeDescriptor.BIGINT});
    ByteBuffer page = ByteBuffer.allocate(PageCodec.MAX_PAYLOAD_BYTES);
    assertEquals(StatusCode.OK, TupleBTreePageCodec.initializeLeaf(
        page, 0, 0, 0, shape, 45, null, 0, 0));
    TupleBTreePageHeader header = new TupleBTreePageHeader();
    TupleBTreePageValidationProof root = new TupleBTreePageValidationProof();
    TupleBTreePageValidationProof lent = new TupleBTreePageValidationProof();
    assertEquals(StatusCode.OK,
        TupleBTreePageCodec.validateForRead(page, 0, 45, shape, header, root));
    assertEquals(StatusCode.OK, root.lendTo(page, 0, lent));

    assertEquals(StatusCode.INVALID_EXTERNAL_INPUT, lent.copyTo(page, 0, root));

    assertEquals(StatusCode.OK,
        TupleBTreePageCodec.readValidatedHeader(page, 0, root, header));
    assertEquals(StatusCode.OK,
        TupleBTreePageCodec.readValidatedHeader(page, 0, lent, header));
  }

  @Test
  void staleDependentProofCannotResetAReboundRoot() {
    TupleShape shape = shape(new int[] {SqlTypeDescriptor.BIGINT});
    ByteBuffer page = ByteBuffer.allocate(PageCodec.MAX_PAYLOAD_BYTES);
    assertEquals(StatusCode.OK, TupleBTreePageCodec.initializeLeaf(
        page, 0, 0, 0, shape, 47, null, 0, 0));
    TupleBTreePageHeader header = new TupleBTreePageHeader();
    TupleBTreePageValidationProof root = new TupleBTreePageValidationProof();
    TupleBTreePageValidationProof lent = new TupleBTreePageValidationProof();
    assertEquals(StatusCode.OK,
        TupleBTreePageCodec.validateForRead(page, 0, 47, shape, header, root));
    assertEquals(StatusCode.OK, root.lendTo(page, 0, lent));
    assertEquals(StatusCode.OK,
        TupleBTreePageCodec.validateForRead(page, 0, 47, shape, header, root));

    assertEquals(StatusCode.INVALID_EXTERNAL_INPUT, lent.copyTo(page, 0, root));

    assertEquals(StatusCode.OK,
        TupleBTreePageCodec.readValidatedHeader(page, 0, root, header));
    assertEquals(StatusCode.INVALID_EXTERNAL_INPUT,
        TupleBTreePageCodec.readValidatedHeader(page, 0, lent, header));
  }

  @Test
  void validates32PartLeafWithoutDuplicatingLogicalRowId() {
    int[] descriptors = new int[32];
    java.util.Arrays.fill(descriptors, SqlTypeDescriptor.BIGINT);
    TupleShape shape = shape(descriptors);
    ByteBuffer keys = ByteBuffer.allocate(1024);
    int firstBytes = key(keys, 0, descriptors, 1, 7);
    int secondBytes = key(keys, 512, descriptors, 2, 8);
    ByteBuffer page = ByteBuffer.allocate(PageCodec.MAX_PAYLOAD_BYTES);
    assertEquals(StatusCode.OK, TupleBTreePageCodec.initialize(
        page, 0, TupleBTreePageCodec.TYPE_LEAF, 0,
        shape, 11, null, 0, 0));
    assertEquals(StatusCode.OK, TupleBTreePageCodec.appendLeaf(
        page, 0, shape, keys, 0, firstBytes));
    assertEquals(StatusCode.OK, TupleBTreePageCodec.appendLeaf(
        page, 0, shape, keys, 512, secondBytes));
    TupleBTreePageHeader header = new TupleBTreePageHeader();
    TupleBTreePageValidationProof proof = new TupleBTreePageValidationProof();
    assertEquals(StatusCode.OK,
        TupleBTreePageCodec.validateForRead(page, 0, 11, shape, header, proof));
    assertEquals(32, header.keyArity());
    assertEquals(shape.descriptorHash(), header.descriptorHash());
    assertEquals(PageCodec.MAX_PAYLOAD_BYTES - firstBytes - secondBytes, header.freeEnd());
    TupleBTreeLeafEntry leaf = new TupleBTreeLeafEntry();
    assertEquals(StatusCode.OK,
        TupleBTreePageCodec.readValidatedLeaf(page, 0, header, 1, leaf));
    assertEquals(8, leaf.logicalRowId());
    assertEquals(StatusCode.OK, TupleBTreePageCodec.validateEnvelope(page, 0, header));
    assertEquals(StatusCode.INVALID_EXTERNAL_INPUT,
        TupleBTreePageCodec.readValidatedLeaf(page, 0, header, 1, leaf));
    int slot = TupleBTreePageCodec.HEADER_BYTES;
    assertEquals(0, FormatBytes.getInt(page, slot + 8));
    FormatBytes.putInt(page, slot + 8, 7);
    assertEquals(StatusCode.CORRUPTION,
        TupleBTreePageCodec.validate(page, 0, 11, shape, header));
    assertEquals(StatusCode.CORRUPTION,
        TupleBTreePageCodec.validateEnvelope(page, 0, header));
  }

  @Test
  void validatedLeafReadRejectsForeignOffsetAndResetHeaders() {
    int[] descriptors = {SqlTypeDescriptor.BIGINT};
    TupleShape shape = shape(descriptors);
    ByteBuffer first = ByteBuffer.allocate(PageCodec.MAX_PAYLOAD_BYTES + 16);
    ByteBuffer second = ByteBuffer.allocate(PageCodec.MAX_PAYLOAD_BYTES + 16);
    ByteBuffer key = ByteBuffer.allocate(64);
    int keyLength = key(key, 0, descriptors, 3, 9);
    assertEquals(StatusCode.OK, TupleBTreePageCodec.initialize(
        first, 8, TupleBTreePageCodec.TYPE_LEAF, 0, shape, 13, null, 0, 0));
    assertEquals(StatusCode.OK, TupleBTreePageCodec.appendLeaf(
        first, 8, shape, key, 0, keyLength));
    assertEquals(StatusCode.OK, TupleBTreePageCodec.initialize(
        second, 8, TupleBTreePageCodec.TYPE_LEAF, 0, shape, 13, null, 0, 0));
    assertEquals(StatusCode.OK, TupleBTreePageCodec.appendLeaf(
        second, 8, shape, key, 0, keyLength));
    TupleBTreePageHeader header = new TupleBTreePageHeader();
    TupleBTreeLeafEntry leaf = new TupleBTreeLeafEntry();
    TupleBTreePageValidationProof proof = new TupleBTreePageValidationProof();
    assertEquals(StatusCode.OK,
        TupleBTreePageCodec.validateForRead(first, 8, 13, shape, header, proof));
    assertEquals(StatusCode.INVALID_EXTERNAL_INPUT,
        TupleBTreePageCodec.readValidatedLeaf(second, 8, header, 0, leaf));
    assertEquals(StatusCode.INVALID_EXTERNAL_INPUT,
        TupleBTreePageCodec.readValidatedLeaf(first, 7, header, 0, leaf));
    header.reset();
    assertEquals(StatusCode.INVALID_EXTERNAL_INPUT,
        TupleBTreePageCodec.readValidatedLeaf(first, 8, header, 0, leaf));
  }

  @Test
  void unboundProofCannotAuthenticateHeaderAndAliasMutationConsumesCapability() {
    int[] descriptors = {SqlTypeDescriptor.BIGINT};
    TupleShape shape = shape(descriptors);
    ByteBuffer page = ByteBuffer.allocate(PageCodec.MAX_PAYLOAD_BYTES);
    ByteBuffer key = ByteBuffer.allocate(64);
    int keyLength = key(key, 0, descriptors, 5, 17);
    assertEquals(StatusCode.OK, TupleBTreePageCodec.initializeLeaf(
        page, 0, 0, 0, shape, 19, null, 0, 0));
    assertEquals(StatusCode.OK,
        TupleBTreePageCodec.appendLeaf(page, 0, shape, key, 0, keyLength));
    TupleBTreePageHeader header = new TupleBTreePageHeader();
    TupleBTreePageValidationProof proof = new TupleBTreePageValidationProof();
    assertEquals(StatusCode.INVALID_EXTERNAL_INPUT,
        TupleBTreePageCodec.readValidatedHeader(page, 0, proof, header));

    TupleBTreePageMutationCapability mutation =
        new TupleBTreePageMutationCapability();
    assertEquals(StatusCode.OK, TupleBTreePageCodec.prepareLeafMutation(
        page, 0, 19, shape, header, proof, mutation));
    ByteBuffer alias = page.duplicate();
    assertEquals(StatusCode.INVALID_EXTERNAL_INPUT,
        TupleBTreePageCodec.insertPreparedLeaf(
            alias, 0, 19, shape, key, 0, keyLength, 1, mutation));
    TupleBTreeLeafEntry leaf = new TupleBTreeLeafEntry();
    assertEquals(StatusCode.INVALID_EXTERNAL_INPUT,
        TupleBTreePageCodec.readValidatedLeaf(page, 0, header, 0, leaf));
  }

  @Test
  void failedValidationNeverIssuesAReadableProof() {
    int[] descriptors = {SqlTypeDescriptor.BIGINT};
    TupleShape shape = shape(descriptors);
    TupleBTreePageHeader header = new TupleBTreePageHeader();
    TupleBTreePageValidationProof proof = new TupleBTreePageValidationProof();
    ByteBuffer truncated = ByteBuffer.allocate(PageCodec.MAX_PAYLOAD_BYTES - 1);
    assertEquals(StatusCode.INVALID_EXTERNAL_INPUT, TupleBTreePageCodec.validateForRead(
        truncated, 0, 23, shape, header, proof));
    assertEquals(StatusCode.INVALID_EXTERNAL_INPUT,
        TupleBTreePageCodec.readValidatedHeader(truncated, 0, proof, header));

    ByteBuffer page = ByteBuffer.allocate(PageCodec.MAX_PAYLOAD_BYTES);
    ByteBuffer key = ByteBuffer.allocate(64);
    int keyLength = key(key, 0, descriptors, 4, 41);
    assertEquals(StatusCode.OK, TupleBTreePageCodec.initializeLeaf(
        page, 0, 0, 0, shape, 23, null, 0, 0));
    assertEquals(StatusCode.OK,
        TupleBTreePageCodec.appendLeaf(page, 0, shape, key, 0, keyLength));
    assertEquals(StatusCode.OK,
        TupleBTreePageCodec.validateForRead(page, 0, 23, shape, header, proof));
    TupleBTreePageHeader priorHeader = header;
    assertEquals(StatusCode.INVALID_EXTERNAL_INPUT,
        TupleBTreePageCodec.validateForRead(page, 0, 23, shape, null, proof));
    TupleBTreeLeafEntry entry = new TupleBTreeLeafEntry();
    assertEquals(StatusCode.INVALID_EXTERNAL_INPUT,
        TupleBTreePageCodec.readValidatedLeaf(page, 0, priorHeader, 0, entry));
    assertEquals(StatusCode.OK,
        TupleBTreePageCodec.validateForRead(page, 0, 23, shape, header, proof));
    int limit = page.limit();
    page.limit(limit - 1);
    assertEquals(StatusCode.INVALID_EXTERNAL_INPUT,
        TupleBTreePageCodec.readValidatedHeader(page, 0, proof, header));
    assertEquals(StatusCode.INVALID_EXTERNAL_INPUT, proof.copyValidatedPayloadTo(
        ByteBuffer.allocate(PageCodec.MAX_PAYLOAD_BYTES), 0,
        new TupleBTreePageValidationProof()));
    page.limit(limit);
    FormatBytes.putInt(page, TupleBTreePageCodec.HEADER_BYTES + 4, -1);
    assertEquals(StatusCode.CORRUPTION,
        TupleBTreePageCodec.validateForRead(page, 0, 23, shape, header, proof));
    assertEquals(StatusCode.INVALID_EXTERNAL_INPUT,
        TupleBTreePageCodec.readValidatedHeader(page, 0, proof, header));
  }

  @Test
  void validatedPayloadCopyTransfersBytesAndProofTogether() {
    int[] descriptors = {SqlTypeDescriptor.BIGINT};
    TupleShape shape = shape(descriptors);
    ByteBuffer source = ByteBuffer.allocate(PageCodec.MAX_PAYLOAD_BYTES);
    ByteBuffer target = ByteBuffer.allocate(PageCodec.MAX_PAYLOAD_BYTES);
    ByteBuffer key = ByteBuffer.allocate(64);
    int keyLength = key(key, 0, descriptors, 6, 61);
    assertEquals(StatusCode.OK, TupleBTreePageCodec.initializeLeaf(
        source, 0, 0, 0, shape, 29, null, 0, 0));
    assertEquals(StatusCode.OK,
        TupleBTreePageCodec.appendLeaf(source, 0, shape, key, 0, keyLength));
    TupleBTreePageHeader sourceHeader = new TupleBTreePageHeader();
    TupleBTreePageValidationProof sourceProof = new TupleBTreePageValidationProof();
    assertEquals(StatusCode.OK, TupleBTreePageCodec.validateForRead(
        source, 0, 29, shape, sourceHeader, sourceProof));

    TupleBTreePageValidationProof targetProof = new TupleBTreePageValidationProof();
    assertEquals(StatusCode.INVALID_EXTERNAL_INPUT,
        sourceProof.copyValidatedPayloadTo(source, 0, targetProof));
    assertEquals(StatusCode.OK,
        sourceProof.copyValidatedPayloadTo(target, 0, targetProof));
    TupleBTreePageHeader targetHeader = new TupleBTreePageHeader();
    assertEquals(StatusCode.OK,
        TupleBTreePageCodec.readValidatedHeader(target, 0, targetProof, targetHeader));
    TupleBTreeLeafEntry entry = new TupleBTreeLeafEntry();
    assertEquals(StatusCode.OK,
        TupleBTreePageCodec.readValidatedLeaf(target, 0, targetHeader, 0, entry));
    assertEquals(61, entry.logicalRowId());
  }

  @Test
  void descriptorHashRejectsSameArityDifferentShapeAndCorruptSlack() {
    TupleShape shape = shape(new int[] {
        SqlTypeDescriptor.BIGINT, SqlTypeDescriptor.varchar(12)});
    TupleShape wrong = shape(new int[] {
        SqlTypeDescriptor.BIGINT, SqlTypeDescriptor.varchar(13)});
    ByteBuffer page = ByteBuffer.allocate(PageCodec.MAX_PAYLOAD_BYTES);
    assertEquals(StatusCode.OK, TupleBTreePageCodec.initialize(
        page, 0, TupleBTreePageCodec.TYPE_INTERNAL, 3,
        shape, 4, null, 0, 0));
    TupleBTreePageHeader header = new TupleBTreePageHeader();
    assertEquals(StatusCode.CORRUPTION,
        TupleBTreePageCodec.validate(page, 0, 4, wrong, header));
    assertEquals(StatusCode.OK,
        TupleBTreePageCodec.validateEnvelope(page, 0, header));
    assertEquals(StatusCode.OK,
        TupleBTreePageCodec.validate(page, 0, 4, shape, header));
    page.put(TupleBTreePageCodec.HEADER_BYTES, (byte) 1);
    assertEquals(StatusCode.CORRUPTION,
        TupleBTreePageCodec.validate(page, 0, 4, shape, header));
  }

  @Test
  void validatesInternalChildrenAndPhysicalFence() {
    int[] descriptors = {SqlTypeDescriptor.BIGINT};
    TupleShape shape = shape(descriptors);
    ByteBuffer keys = ByteBuffer.allocate(128);
    int separator = key(keys, 0, descriptors, 4, 1);
    int high = key(keys, 64, descriptors, 9, 1);
    ByteBuffer page = ByteBuffer.allocate(PageCodec.MAX_PAYLOAD_BYTES);
    assertEquals(StatusCode.OK, TupleBTreePageCodec.initialize(
        page, 0, TupleBTreePageCodec.TYPE_INTERNAL, 2,
        shape, 5, keys, 64, high));
    assertEquals(StatusCode.OK, TupleBTreePageCodec.appendInternal(
        page, 0, shape, keys, 0, separator, 3));
    TupleBTreePageHeader header = new TupleBTreePageHeader();
    assertEquals(StatusCode.OK, TupleBTreePageCodec.validate(page, 0, 5, shape, header));
    assertEquals(PageCodec.MAX_PAYLOAD_BYTES - high - separator, header.freeEnd());
    TupleBTreeInternalEntry entry = new TupleBTreeInternalEntry();
    assertEquals(StatusCode.OK, TupleBTreePageCodec.readInternal(page, 0, header, 0, entry));
    assertEquals(3, entry.rightChildPageId());
  }

  private static int key(
      ByteBuffer target, int offset, int[] descriptors, long first, long logicalRowId) {
    TupleKeyBuilder builder = new TupleKeyBuilder();
    assertEquals(StatusCode.OK, builder.beginIndex(target, offset, descriptors.length));
    for (int index = 0; index < descriptors.length; index++) {
      assertEquals(StatusCode.OK, builder.addFixed(descriptors[index], first + index));
    }
    assertEquals(StatusCode.OK, builder.finishPhysical(logicalRowId));
    return builder.keyBytes();
  }

  private static TupleShape shape(int[] descriptors) {
    TupleShape.Result result = new TupleShape.Result();
    assertEquals(StatusCode.OK, TupleShape.create(descriptors, result));
    return result.value();
  }
}
