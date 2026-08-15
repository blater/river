package io.riverdb.engine.table;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.riverdb.base.error.StatusCode;
import io.riverdb.format.page.PageCodec;
import java.nio.ByteBuffer;
import org.junit.jupiter.api.Test;

final class IndexedWalCodecStructuralTest {
  @Test
  void commonHeaderAndFixedFieldsRejectStructuralCorruption() {
    ByteBuffer insert = ByteBuffer.allocate(IndexedWalCodec.insertOperationBytes(1));
    IndexedWalCodec.encodeInsertHeader(insert, 3, 7, 1, 1);
    assertEquals(StatusCode.OK, IndexedWalCodec.validateInsert(insert));
    assertEquals(3, IndexedWalCodec.insertSpace(insert));
    IndexedWalCodec.putInt(insert, 32, -1);
    assertEquals(StatusCode.CORRUPTION, IndexedWalCodec.validateInsert(insert));
    IndexedWalCodec.putInt(insert, 32, 3);

    IndexedWalCodec.putLong(insert, 0, 0);
    assertEquals(StatusCode.CORRUPTION, IndexedWalCodec.validateInsert(insert));
    IndexedWalCodec.putLong(insert, 0, IndexedWalCodec.OPERATION_MAGIC);
    IndexedWalCodec.putInt(insert, 8, IndexedWalCodec.FORMAT_VERSION + 1);
    assertEquals(StatusCode.CORRUPTION, IndexedWalCodec.validateInsert(insert));
    IndexedWalCodec.putInt(insert, 8, IndexedWalCodec.FORMAT_VERSION);
    IndexedWalCodec.putInt(insert, 12, IndexedWalCodec.OPERATION_TYPE_INSERT_BATCH);
    assertEquals(StatusCode.CORRUPTION, IndexedWalCodec.validateInsert(insert));
    IndexedWalCodec.putInt(insert, 12, IndexedWalCodec.OPERATION_TYPE_INSERT);
    IndexedWalCodec.putInt(insert, 28, 2);
    assertEquals(StatusCode.CORRUPTION, IndexedWalCodec.validateInsert(insert));
    IndexedWalCodec.putInt(insert, 28, 1);
    IndexedWalCodec.putInt(insert, 36, 1);
    assertEquals(StatusCode.CORRUPTION, IndexedWalCodec.validateInsert(insert));

    int pageBytes = IndexedWalCodec.pageOperationBytes(1, 1);
    ByteBuffer pages = ByteBuffer.allocate(pageBytes);
    IndexedWalCodec.encodePageOperationHeader(pages, 1, 1);
    int versionOffset = IndexedWalCodec.PAGE_OPERATION_HEADER_BYTES + PageCodec.PAGE_BYTES;
    IndexedWalCodec.encodePageOperationVersion(pages, versionOffset, 0, false);
    assertEquals(StatusCode.OK, IndexedWalCodec.validatePageOperation(pages, 2, 2));
    assertTrue(IndexedWalCodec.validPageOperationVersion(pages, versionOffset));

    IndexedWalCodec.putInt(pages, 16, 0);
    assertEquals(StatusCode.CORRUPTION, IndexedWalCodec.validatePageOperation(pages, 2, 2));
    IndexedWalCodec.putInt(pages, 16, 1);
    IndexedWalCodec.putInt(pages, 20, 3);
    assertEquals(StatusCode.CORRUPTION, IndexedWalCodec.validatePageOperation(pages, 2, 2));
    IndexedWalCodec.putInt(pages, 20, 1);
    IndexedWalCodec.putInt(pages, versionOffset, -1);
    assertFalse(IndexedWalCodec.validPageOperationVersion(pages, versionOffset));
    IndexedWalCodec.putInt(pages, versionOffset, 0);
    IndexedWalCodec.putInt(pages, versionOffset + 4, 2);
    assertFalse(IndexedWalCodec.validPageOperationVersion(pages, versionOffset));
    assertFalse(IndexedWalCodec.validPageOperationVersion(pages, pageBytes));
  }

  @Test
  void batchCountsReservedFieldsAndEntryBoundsAreValidated() {
    ByteBuffer inserts = ByteBuffer.allocate(
        IndexedWalCodec.INSERT_BATCH_HEADER_BYTES
            + 2 * IndexedWalCodec.insertBatchEntryBytes(1));
    IndexedWalCodec.encodeInsertBatchHeader(inserts, 2);
    int firstInsert = IndexedWalCodec.INSERT_BATCH_HEADER_BYTES;
    int secondInsert = firstInsert + IndexedWalCodec.insertBatchEntryBytes(1);
    IndexedWalCodec.encodeInsertBatchEntry(inserts, firstInsert, 3, 11, 1, 1);
    IndexedWalCodec.encodeInsertBatchEntry(inserts, secondInsert, 4, 12, 2, 1);
    assertEquals(StatusCode.OK, IndexedWalCodec.validateInsertBatch(inserts, 2));
    assertTrue(IndexedWalCodec.validInsertBatchEntry(inserts, firstInsert));
    assertTrue(IndexedWalCodec.validInsertBatchEntry(inserts, secondInsert));
    assertEquals(3, IndexedWalCodec.insertBatchSpace(inserts, firstInsert));
    assertEquals(4, IndexedWalCodec.insertBatchSpace(inserts, secondInsert));
    IndexedWalCodec.putInt(inserts, firstInsert + 16, -1);
    assertFalse(IndexedWalCodec.validInsertBatchEntry(inserts, firstInsert));
    IndexedWalCodec.putInt(inserts, firstInsert + 16, 3);

    IndexedWalCodec.putInt(inserts, 16, 1);
    assertEquals(StatusCode.CORRUPTION, IndexedWalCodec.validateInsertBatch(inserts, 2));
    IndexedWalCodec.putInt(inserts, 16, 3);
    assertEquals(StatusCode.CORRUPTION, IndexedWalCodec.validateInsertBatch(inserts, 2));
    IndexedWalCodec.putInt(inserts, 16, 2);
    IndexedWalCodec.putInt(inserts, 20, 1);
    assertEquals(StatusCode.CORRUPTION, IndexedWalCodec.validateInsertBatch(inserts, 2));
    IndexedWalCodec.putInt(inserts, 20, 0);
    IndexedWalCodec.putInt(inserts, firstInsert + 8, 0);
    assertFalse(IndexedWalCodec.validInsertBatchEntry(inserts, firstInsert));
    IndexedWalCodec.putInt(inserts, firstInsert + 8, 1);
    IndexedWalCodec.putInt(inserts, firstInsert + 12, inserts.limit());
    assertFalse(IndexedWalCodec.validInsertBatchEntry(inserts, firstInsert));
    assertFalse(IndexedWalCodec.validInsertBatchEntry(inserts, -1));
    assertFalse(IndexedWalCodec.validInsertBatchEntry(inserts, inserts.limit()));

    ByteBuffer mutations = ByteBuffer.allocate(
        IndexedWalCodec.MUTATION_BATCH_HEADER_BYTES
            + IndexedWalCodec.mutationBatchEntryBytes(1));
    IndexedWalCodec.encodeMutationBatchHeader(mutations, 1);
    int mutation = IndexedWalCodec.MUTATION_BATCH_HEADER_BYTES;
    IndexedWalCodec.encodeMutationBatchEntry(
        mutations, mutation, IndexedWalCodec.MUTATION_UPDATE, 3, 11, 2, 1, 1);
    assertEquals(StatusCode.OK, IndexedWalCodec.validateMutationBatch(mutations, 1));
    assertTrue(IndexedWalCodec.validMutationBatchEntry(mutations, mutation));
    assertEquals(3, IndexedWalCodec.mutationSpace(mutations, mutation));
    IndexedWalCodec.putInt(mutations, mutation + 24, -1);
    assertFalse(IndexedWalCodec.validMutationBatchEntry(mutations, mutation));
    IndexedWalCodec.putInt(mutations, mutation + 24, 3);

    IndexedWalCodec.putInt(mutations, 16, 0);
    assertEquals(StatusCode.CORRUPTION, IndexedWalCodec.validateMutationBatch(mutations, 1));
    IndexedWalCodec.putInt(mutations, 16, 1);
    IndexedWalCodec.putInt(mutations, 20, 1);
    assertEquals(StatusCode.CORRUPTION, IndexedWalCodec.validateMutationBatch(mutations, 1));
    IndexedWalCodec.putInt(mutations, 20, 0);
    IndexedWalCodec.putInt(mutations, mutation, 0);
    assertFalse(IndexedWalCodec.validMutationBatchEntry(mutations, mutation));
    IndexedWalCodec.putInt(mutations, mutation, IndexedWalCodec.MUTATION_UPDATE);
    IndexedWalCodec.putInt(mutations, mutation + 16, -1);
    assertFalse(IndexedWalCodec.validMutationBatchEntry(mutations, mutation));
    IndexedWalCodec.putInt(mutations, mutation + 16, 1);
    IndexedWalCodec.putInt(mutations, mutation + 20, mutations.limit());
    assertFalse(IndexedWalCodec.validMutationBatchEntry(mutations, mutation));
    assertFalse(IndexedWalCodec.validMutationBatchEntry(mutations, mutations.limit()));
  }

  @Test
  void vacuumStructureAndDuplicatePageDetectionAreBounded() {
    ByteBuffer chunk = ByteBuffer.allocate(
        IndexedWalCodec.VACUUM_CHUNK_HEADER_BYTES + IndexedWalCodec.vacuumEntryBytes(1));
    IndexedWalCodec.encodeVacuumChunkHeader(chunk, 1, 0, 1, 0, 1);
    int entry = IndexedWalCodec.VACUUM_CHUNK_HEADER_BYTES;
    IndexedWalCodec.encodeVacuumEntry(chunk, entry, 3, 17, 1, 1, false);
    assertEquals(StatusCode.OK, IndexedWalCodec.validateVacuumChunk(chunk, 1, 1));
    assertTrue(IndexedWalCodec.validVacuumEntry(chunk, entry));
    assertEquals(3, IndexedWalCodec.vacuumEntrySpace(chunk, entry));

    IndexedWalCodec.putInt(chunk, 24, 2);
    assertEquals(StatusCode.CORRUPTION, IndexedWalCodec.validateVacuumChunk(chunk, 1, 1));
    IndexedWalCodec.putInt(chunk, 24, 1);
    IndexedWalCodec.putInt(chunk, 32, 0);
    assertEquals(StatusCode.CORRUPTION, IndexedWalCodec.validateVacuumChunk(chunk, 1, 1));
    IndexedWalCodec.putInt(chunk, 32, 1);
    IndexedWalCodec.putInt(chunk, 36, 1);
    assertEquals(StatusCode.CORRUPTION, IndexedWalCodec.validateVacuumChunk(chunk, 1, 1));
    IndexedWalCodec.putInt(chunk, 36, 0);
    IndexedWalCodec.putInt(chunk, entry + 16, 2);
    assertFalse(IndexedWalCodec.validVacuumEntry(chunk, entry));
    IndexedWalCodec.putInt(chunk, entry + 16, 0);
    IndexedWalCodec.putInt(chunk, entry + 20, -1);
    assertFalse(IndexedWalCodec.validVacuumEntry(chunk, entry));
    IndexedWalCodec.putInt(chunk, entry + 20, 3);
    IndexedWalCodec.putInt(chunk, entry + 12, chunk.limit());
    assertFalse(IndexedWalCodec.validVacuumEntry(chunk, entry));
    assertFalse(IndexedWalCodec.validVacuumEntry(chunk, chunk.limit()));

    ByteBuffer commit = ByteBuffer.allocate(IndexedWalCodec.VACUUM_COMMIT_PAYLOAD_BYTES);
    IndexedWalCodec.encodeVacuumCommit(commit, 1, 1, 2);
    assertEquals(StatusCode.OK, IndexedWalCodec.validateVacuumCommit(commit, 2, 1));
    IndexedWalCodec.putInt(commit, 20, 0);
    assertEquals(StatusCode.CORRUPTION, IndexedWalCodec.validateVacuumCommit(commit, 2, 1));
    IndexedWalCodec.putInt(commit, 20, 1);
    IndexedWalCodec.putInt(commit, 24, 0);
    assertEquals(StatusCode.CORRUPTION, IndexedWalCodec.validateVacuumCommit(commit, 2, 1));
    IndexedWalCodec.putInt(commit, 24, 2);
    IndexedWalCodec.putInt(commit, 28, 1);
    assertEquals(StatusCode.CORRUPTION, IndexedWalCodec.validateVacuumCommit(commit, 2, 1));

    int[] pageIds = {3, 7};
    assertFalse(IndexedWalCodec.containsEarlierPageId(pageIds, 0, 3));
    assertTrue(IndexedWalCodec.containsEarlierPageId(pageIds, 1, 3));
    assertFalse(IndexedWalCodec.containsEarlierPageId(pageIds, 2, 5));
    assertTrue(IndexedWalCodec.containsEarlierPageId(pageIds, 2, 7));
  }
}
