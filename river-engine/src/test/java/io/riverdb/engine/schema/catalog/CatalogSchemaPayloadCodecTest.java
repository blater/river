package io.riverdb.engine.schema.catalog;

import static io.riverdb.engine.schema.catalog.CatalogSchemaPayloadFixture.FIRST_CHILD;
import static io.riverdb.engine.schema.catalog.CatalogSchemaPayloadFixture.GENERATION;
import static io.riverdb.engine.schema.catalog.CatalogSchemaPayloadFixture.LAYOUT_ID;
import static io.riverdb.engine.schema.catalog.CatalogSchemaPayloadFixture.OBJECT_ID;
import static io.riverdb.engine.schema.catalog.CatalogSchemaPayloadFixture.assemble;
import static io.riverdb.engine.schema.catalog.CatalogSchemaPayloadFixture.columns;
import static io.riverdb.engine.schema.catalog.CatalogSchemaPayloadFixture.decodeManifest;
import static io.riverdb.engine.schema.catalog.CatalogSchemaPayloadFixture.duplicateKeyPayload;
import static io.riverdb.engine.schema.catalog.CatalogSchemaPayloadFixture.encode;
import static io.riverdb.engine.schema.catalog.CatalogSchemaPayloadFixture.encodeColumnsAndRawKey;
import static io.riverdb.engine.schema.catalog.CatalogSchemaPayloadFixture.encodeRawColumn;
import static io.riverdb.engine.schema.catalog.CatalogSchemaPayloadFixture.encodeWithIdentity;
import static io.riverdb.engine.schema.catalog.CatalogSchemaPayloadFixture.key;
import static io.riverdb.engine.schema.catalog.CatalogSchemaPayloadFixture.name;
import static io.riverdb.engine.schema.catalog.CatalogSchemaPayloadFixture.rawColumnName;
import static io.riverdb.engine.schema.catalog.CatalogSchemaPayloadFixture.rawKeyPayload;
import static io.riverdb.engine.schema.catalog.CatalogSchemaPayloadFixture.record;
import static io.riverdb.engine.schema.catalog.CatalogSchemaPayloadFixture.rowBoundaryColumns;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.riverdb.base.error.StatusCode;
import io.riverdb.base.error.StatusDetail;
import io.riverdb.base.type.SqlTypeDescriptor;
import io.riverdb.engine.schema.ColumnDescriptorSet;
import io.riverdb.engine.schema.KeyDescriptor;
import io.riverdb.engine.schema.TableDescriptor;
import io.riverdb.engine.schema.catalog.CatalogSchemaPayloadFixture.EncodedCatalog;
import io.riverdb.format.catalog.CatalogDefinitionRecord;
import io.riverdb.format.catalog.CatalogDefinitionRecordCodec;
import io.riverdb.storage.heap.HeapPage;
import java.lang.management.ManagementFactory;
import java.nio.ByteBuffer;
import java.util.zip.CRC32C;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

final class CatalogSchemaPayloadCodecTest {
  @Test
  void rejectsPersistedCheckTypeIncompatibleWithItsOwnerColumn() {
    CatalogColumnConstraintAssembly constraints = new CatalogColumnConstraintAssembly();
    assertEquals(StatusCode.OK, constraints.begin(1));
    constraints.put(
        0, SqlTypeDescriptor.BOOLEAN, 0, 0, 0,
        io.riverdb.engine.schema.ColumnConstraintDescriptorSet.CHECK_EQUAL,
        SqlTypeDescriptor.BIGINT, 0, 1);
    assertEquals(StatusCode.CORRUPTION, constraints.freeze(1));
    assertNull(constraints.value());
  }

  @Test
  void roundTripsColumnChunkBoundariesAndWideTables() {
    for (int count : new int[] {31, 32, 33, 1_023, 1_024}) {
      ColumnDescriptorSet columns = columns(count, false);
      EncodedCatalog catalog = encode(columns, null);
      TableDescriptor decoded = assemble(catalog);
      assertEquals(count, decoded.columnCount());
      assertEquals("c" + (count - 1), name(decoded.columns(), count - 1));
      assertEquals(count == 1_024 ? 32 : (count + 31) / 32, catalog.records().size());
    }
  }

  @Test
  void coldWideAssemblyRetainsOnePackedNameArena() {
    EncodedCatalog catalog = encode(columns(1_024, false), null);
    assemble(catalog);
    com.sun.management.ThreadMXBean allocations =
        (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();
    Assumptions.assumeTrue(allocations.isThreadAllocatedMemorySupported());
    allocations.setThreadAllocatedMemoryEnabled(true);
    long thread = Thread.currentThread().threadId();
    long before = allocations.getThreadAllocatedBytes(thread);
    TableDescriptor decoded = assemble(catalog);
    long allocated = allocations.getThreadAllocatedBytes(thread) - before;
    assertEquals(1_024, decoded.columnCount());
    assertTrue(allocated <= 96 * 1_024,
        "cold 1,024-column catalog assembly allocated bytes: " + allocated);
  }

  @Test
  void preservesUtf8NullabilityTypesAndCompositeKeyIdentity() {
    String maximumName = "😀".repeat(255);
    ColumnDescriptorSet.Result columns = new ColumnDescriptorSet.Result();
    assertEquals(StatusCode.OK, ColumnDescriptorSet.create(
        new int[] {SqlTypeDescriptor.BIGINT, SqlTypeDescriptor.varchar(12),
            SqlTypeDescriptor.BOOLEAN},
        new CharSequence[] {maximumName, "café😀", "active"},
        new boolean[] {false, false, true}, columns));
    KeyDescriptor.Result primary = new KeyDescriptor.Result();
    assertEquals(StatusCode.OK, KeyDescriptor.create(
        71, KeyDescriptor.KIND_PRIMARY, true, columns.value(), new int[] {0, 1},
        0, primary, null));
    EncodedCatalog catalog = encode(columns.value(), primary.value());
    TableDescriptor decoded = assemble(catalog);
    assertEquals(maximumName, name(decoded.columns(), 0));
    assertEquals("café😀", name(decoded.columns(), 1));
    assertTrue(decoded.isNullable(2));
    assertEquals(SqlTypeDescriptor.varchar(12), decoded.typeDescriptorAt(1));
    assertEquals(71, decoded.primaryKey().keyId());
    assertEquals(2, decoded.primaryKey().partCount());
    assertEquals(1, decoded.primaryKey().columnOrdinalAt(1));
  }

  @Test
  void rejectsCorruptPayloadSwapDuplicateOrderAndWrongIdentity() {
    EncodedCatalog catalog = encode(columns(33, false), null);
    CatalogTableAssemblyBuilder builder = new CatalogTableAssemblyBuilder();
    assertEquals(StatusCode.OK, builder.begin(OBJECT_ID, catalog.head(), catalog.manifest()));
    ByteBuffer corrupt = copy(catalog.records().get(0));
    int payload = CatalogDefinitionRecordCodec.HEADER_BYTES + 8;
    corrupt.put(payload, (byte) (corrupt.get(payload) ^ 1));
    assertEquals(StatusCode.CORRUPTION,
        builder.accept(corrupt, 0, corrupt.limit()));

    assertEquals(StatusCode.OK, builder.begin(OBJECT_ID, catalog.head(), catalog.manifest()));
    ByteBuffer second = catalog.records().get(1);
    assertEquals(StatusCode.CORRUPTION, builder.accept(second, 0, second.limit()));

    assertEquals(StatusCode.OK, builder.begin(OBJECT_ID, catalog.head(), catalog.manifest()));
    ByteBuffer first = catalog.records().get(0);
    assertEquals(StatusCode.OK, builder.accept(first, 0, first.limit()));
    assertEquals(StatusCode.CORRUPTION, builder.accept(first, 0, first.limit()));

    EncodedCatalog other = encodeWithIdentity(columns(33, false), null, OBJECT_ID + 1, 40);
    assertEquals(StatusCode.OK, builder.begin(OBJECT_ID, catalog.head(), catalog.manifest()));
    ByteBuffer otherFirst = other.records().get(0);
    assertEquals(StatusCode.CORRUPTION,
        builder.accept(otherFirst, 0, otherFirst.limit()));
  }

  @Test
  void childSetChecksumRejectsARechecksummedButSubstitutedPayload() {
    EncodedCatalog catalog = encode(columns(33, false), null);
    ByteBuffer original = catalog.records().get(0);
    int payloadBytes = original.limit() - CatalogDefinitionRecordCodec.HEADER_BYTES;
    ByteBuffer changedPayload = ByteBuffer.allocate(payloadBytes);
    for (int index = 0; index < payloadBytes; index++) {
      changedPayload.put(index,
          original.get(CatalogDefinitionRecordCodec.HEADER_BYTES + index));
    }
    int firstName = CatalogColumnPayloadCodec.headerBytes()
        + CatalogColumnPayloadCodec.entryBytes();
    changedPayload.put(firstName, (byte) 'd');
    ByteBuffer changedRecord = record(
        FIRST_CHILD, OBJECT_ID, CatalogDefinitionRecordCodec.KIND_COLUMNS,
        0, 0, 32, changedPayload);
    CatalogDefinitionRecord decoded = new CatalogDefinitionRecord();
    assertEquals(StatusCode.OK, CatalogDefinitionRecordCodec.decode(
        changedRecord, 0, changedRecord.limit(), decoded, new CRC32C()));

    CatalogTableAssemblyBuilder builder = new CatalogTableAssemblyBuilder();
    assertEquals(StatusCode.OK, builder.begin(OBJECT_ID, catalog.head(), catalog.manifest()));
    assertEquals(StatusCode.OK,
        builder.accept(changedRecord, 0, changedRecord.limit()));
    ByteBuffer second = catalog.records().get(1);
    assertEquals(StatusCode.OK, builder.accept(second, 0, second.limit()));
    TableDescriptor.Result result = new TableDescriptor.Result();
    assertEquals(StatusCode.CORRUPTION,
        builder.finish(result, new StatusDetail(64)));
    assertNull(result.value());
  }

  @Test
  void headAdmissionRejectsSwappedManifestAndMutableCarrierCannotChangeAssembly() {
    EncodedCatalog first = encode(columns(2, false), null);
    EncodedCatalog other = encodeWithIdentity(columns(2, false), null, OBJECT_ID + 1, 50);
    CatalogTableAssemblyBuilder builder = new CatalogTableAssemblyBuilder();
    assertEquals(StatusCode.CORRUPTION,
        builder.begin(OBJECT_ID, first.head(), other.manifest()));
    assertEquals(StatusCode.CORRUPTION,
        builder.begin(OBJECT_ID + 1, first.head(), first.manifest()));

    assertEquals(StatusCode.OK, builder.begin(OBJECT_ID, first.head(), first.manifest()));
    decodeManifest(other.manifestBytes(), first.manifest());
    for (ByteBuffer record : first.records()) {
      assertEquals(StatusCode.OK, builder.accept(record, 0, record.limit()));
    }
    TableDescriptor.Result result = new TableDescriptor.Result();
    assertEquals(StatusCode.OK, builder.finish(result, null));
    assertEquals(OBJECT_ID, result.value().tableId());
  }

  @Test
  void rejectsTrailingUtf8PayloadAndPublishesNothingWhenIncomplete() {
    ColumnDescriptorSet columns = columns(1, true);
    CatalogPayloadSize size = new CatalogPayloadSize();
    assertEquals(StatusCode.OK,
        CatalogColumnPayloadCodec.payloadBytes(columns, 0, 1, size));
    ByteBuffer payload = ByteBuffer.allocate(size.bytes() + 1);
    assertEquals(StatusCode.OK,
        CatalogColumnPayloadCodec.encode(columns, 0, 1, payload, 0));
    payload.put(size.bytes(), (byte) 0);
    EncodedCatalog catalog = encodeRawColumn(payload, 1);
    CatalogTableAssemblyBuilder builder = new CatalogTableAssemblyBuilder();
    assertEquals(StatusCode.OK, builder.begin(OBJECT_ID, catalog.head(), catalog.manifest()));
    ByteBuffer record = catalog.records().get(0);
    assertEquals(StatusCode.CORRUPTION, builder.accept(record, 0, record.limit()));

    EncodedCatalog complete = encode(columns(2, false), null);
    TableDescriptor.Result result = new TableDescriptor.Result();
    assertEquals(StatusCode.OK, builder.begin(OBJECT_ID, complete.head(), complete.manifest()));
    assertEquals(StatusCode.CORRUPTION, builder.finish(result, null));
    assertNull(result.value());
  }

  @Test
  void rejectsChecksummedOverlongUtf8AndInvalidForeignReferenceSemantics() {
    CatalogTableAssemblyBuilder builder = new CatalogTableAssemblyBuilder();
    for (byte[] malformed : new byte[][] {
        {(byte) 0xc0, (byte) 0xaf},
        {(byte) 0xed, (byte) 0xa0, (byte) 0x80},
        {(byte) 0xf0, (byte) 0x9f, (byte) 0x98}}) {
      EncodedCatalog invalidNameCatalog = encodeRawColumn(rawColumnName(malformed), 1);
      assertEquals(StatusCode.OK, builder.begin(
          OBJECT_ID, invalidNameCatalog.head(), invalidNameCatalog.manifest()));
      ByteBuffer nameRecord = invalidNameCatalog.records().get(0);
      assertEquals(StatusCode.CORRUPTION,
          builder.accept(nameRecord, 0, nameRecord.limit()));
    }

    ColumnDescriptorSet columns = columns(1, false);
    ByteBuffer foreign = rawKeyPayload(
        88, 0, KeyDescriptor.KIND_FOREIGN, 0);
    EncodedCatalog invalidForeign = encodeColumnsAndRawKey(columns, foreign, 1);
    assertEquals(StatusCode.OK,
        builder.begin(OBJECT_ID, invalidForeign.head(), invalidForeign.manifest()));
    assertEquals(StatusCode.OK, builder.accept(
        invalidForeign.records().get(0), 0, invalidForeign.records().get(0).limit()));
    assertEquals(StatusCode.CORRUPTION, builder.accept(
        invalidForeign.records().get(1), 0, invalidForeign.records().get(1).limit()));
  }

  @Test
  void exactSingleHeapRowPublishesAndOversizeRemainsPrivate() {
    ColumnDescriptorSet exact = rowBoundaryColumns(3);
    EncodedCatalog exactCatalog = encode(exact, null);
    TableDescriptor exactTable = assemble(exactCatalog);
    assertEquals(HeapPage.MAXIMUM_ROW_BYTES, exactTable.encodedMaximumRowBytes());

    ColumnDescriptorSet over = rowBoundaryColumns(4);
    EncodedCatalog overCatalog = encode(over, null);
    CatalogTableAssemblyBuilder builder = new CatalogTableAssemblyBuilder();
    assertEquals(StatusCode.OK,
        builder.begin(OBJECT_ID, overCatalog.head(), overCatalog.manifest()));
    for (ByteBuffer record : overCatalog.records()) {
      assertEquals(StatusCode.OK, builder.accept(record, 0, record.limit()));
    }
    TableDescriptor.Result result = new TableDescriptor.Result();
    assertEquals(StatusCode.RESOURCE_EXHAUSTED,
        builder.finish(result, new StatusDetail(64)));
    assertNull(result.value());
  }

  @Test
  void checksummedDuplicateKeyIdentityIsCatalogCorruptionWithNormalizedDetail() {
    ColumnDescriptorSet columns = columns(2, false);
    ByteBuffer keyPayload = duplicateKeyPayload();
    EncodedCatalog catalog = encodeColumnsAndRawKey(columns, keyPayload, 2);
    CatalogTableAssemblyBuilder builder = new CatalogTableAssemblyBuilder();
    assertEquals(StatusCode.OK, builder.begin(OBJECT_ID, catalog.head(), catalog.manifest()));
    for (ByteBuffer record : catalog.records()) {
      assertEquals(StatusCode.OK, builder.accept(record, 0, record.limit()));
    }
    TableDescriptor.Result result = new TableDescriptor.Result();
    StatusDetail detail = new StatusDetail(64);
    assertEquals(StatusCode.CORRUPTION, builder.finish(result, detail));
    assertEquals(StatusCode.CORRUPTION, detail.code());
    assertFalse(detail.toString().isEmpty());
    assertNull(result.value());
  }

  @Test
  void canonicalPackerAdaptsToMaximumUtf8Names() {
    int count = 64;
    int[] types = new int[count];
    CharSequence[] names = new CharSequence[count];
    boolean[] nullable = new boolean[count];
    String prefix = "😀".repeat(254);
    for (int index = 0; index < count; index++) {
      types[index] = SqlTypeDescriptor.BOOLEAN;
      names[index] = prefix + (char) ('A' + index);
    }
    ColumnDescriptorSet.Result columns = new ColumnDescriptorSet.Result();
    assertEquals(StatusCode.OK,
        ColumnDescriptorSet.create(types, names, nullable, columns));
    TableDescriptor.Result table = new TableDescriptor.Result();
    assertEquals(StatusCode.OK, TableDescriptor.create(
        OBJECT_ID, LAYOUT_ID, GENERATION, columns.value(), null,
        null, null, table, null));
    CatalogTablePayloadPacker packer = new CatalogTablePayloadPacker();
    CatalogTablePayloadPlan plan = new CatalogTablePayloadPlan();
    assertEquals(StatusCode.OK, packer.plan(table.value(), plan));
    assertTrue(plan.chunkCount() > (count + 31) / 32);
    for (int chunk = 0; chunk < plan.chunkCount(); chunk++) {
      assertTrue(plan.payloadBytesAt(chunk)
          <= CatalogDefinitionRecordCodec.MAX_PAYLOAD_BYTES);
    }
    TableDescriptor decoded = assemble(encode(table.value(), packer, plan));
    assertEquals(names[count - 1], name(decoded.columns(), count - 1));
  }

  @Test
  void canonicalPackerRoundTripsMaximumMixedCompositeKeysAcrossChunks() {
    int[] types = new int[32];
    CharSequence[] names = new CharSequence[32];
    for (int index = 0; index < types.length; index++) {
      types[index] = SqlTypeDescriptor.BOOLEAN;
      names[index] = "c" + index;
    }
    ColumnDescriptorSet.Result columnResult = new ColumnDescriptorSet.Result();
    assertEquals(StatusCode.OK, ColumnDescriptorSet.create(
        types, names, new boolean[32], columnResult));
    ColumnDescriptorSet columns = columnResult.value();
    int[] ordinals = new int[32];
    for (int index = 0; index < ordinals.length; index++) ordinals[index] = index;
    KeyDescriptor primary = key(1, KeyDescriptor.KIND_PRIMARY, true, columns, ordinals, 0);
    KeyDescriptor[] secondary = new KeyDescriptor[64];
    KeyDescriptor[] foreign = new KeyDescriptor[64];
    for (int index = 0; index < 64; index++) {
      secondary[index] = key(2 + index, KeyDescriptor.KIND_SECONDARY,
          false, columns, ordinals, 0);
      foreign[index] = key(66 + index, KeyDescriptor.KIND_FOREIGN,
          false, columns, ordinals, 1);
    }
    TableDescriptor.Result table = new TableDescriptor.Result();
    assertEquals(StatusCode.OK, TableDescriptor.create(
        OBJECT_ID, LAYOUT_ID, GENERATION, columns, primary,
        secondary, foreign, table, null));
    CatalogTablePayloadPacker packer = new CatalogTablePayloadPacker();
    CatalogTablePayloadPlan plan = new CatalogTablePayloadPlan();
    assertEquals(StatusCode.OK, packer.plan(table.value(), plan));
    assertTrue(plan.chunkCount() > 2);
    int keyParts = 0;
    int keyChunks = 0;
    for (int chunk = 0; chunk < plan.chunkCount(); chunk++) {
      if (plan.kindAt(chunk) == CatalogDefinitionRecordCodec.KIND_KEY) {
        keyParts += plan.logicalCountAt(chunk);
        keyChunks++;
      }
    }
    assertEquals(4_128, keyParts);
    assertTrue(keyChunks > 1);
    TableDescriptor decoded = assemble(encode(table.value(), packer, plan));
    assertEquals(32, decoded.primaryKey().partCount());
    assertEquals(64, decoded.secondaryKeyCount());
    assertEquals(64, decoded.foreignKeyCount());
    assertEquals(32, decoded.foreignKeyAt(63).partCount());
    assertEquals(1, decoded.foreignKeyAt(63).referencedKeyId());

    KeyDescriptor[] over = new KeyDescriptor[65];
    System.arraycopy(secondary, 0, over, 0, secondary.length);
    over[64] = key(130, KeyDescriptor.KIND_SECONDARY,
        false, columns, ordinals, 0);
    TableDescriptor.Result rejected = new TableDescriptor.Result();
    assertEquals(StatusCode.RESOURCE_EXHAUSTED, TableDescriptor.create(
        OBJECT_ID, LAYOUT_ID, GENERATION, columns, primary,
        over, foreign, rejected, null));
    assertNull(rejected.value());
  }

  private static ByteBuffer copy(ByteBuffer source) {
    ByteBuffer copied = ByteBuffer.allocate(source.limit());
    for (int index = 0; index < source.limit(); index++) copied.put(index, source.get(index));
    return copied;
  }

}
