package io.riverdb.engine.schema.catalog;

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
import io.riverdb.format.FormatBytes;
import io.riverdb.format.catalog.CatalogDefinitionManifest;
import io.riverdb.format.catalog.CatalogDefinitionManifestCodec;
import io.riverdb.format.catalog.CatalogDefinitionRecord;
import io.riverdb.format.catalog.CatalogDefinitionRecordCodec;
import io.riverdb.format.catalog.CatalogObjectHead;
import io.riverdb.format.catalog.CatalogObjectHeadCodec;
import java.lang.management.ManagementFactory;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
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
      assertEquals(count == 1_024 ? 32 : (count + 31) / 32, catalog.records.size());
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
    assertEquals(StatusCode.OK, builder.begin(OBJECT_ID, catalog.head, catalog.manifest));
    ByteBuffer corrupt = copy(catalog.records.get(0));
    int payload = CatalogDefinitionRecordCodec.HEADER_BYTES + 8;
    corrupt.put(payload, (byte) (corrupt.get(payload) ^ 1));
    assertEquals(StatusCode.CORRUPTION,
        builder.accept(corrupt, 0, corrupt.limit()));

    assertEquals(StatusCode.OK, builder.begin(OBJECT_ID, catalog.head, catalog.manifest));
    ByteBuffer second = catalog.records.get(1);
    assertEquals(StatusCode.CORRUPTION, builder.accept(second, 0, second.limit()));

    assertEquals(StatusCode.OK, builder.begin(OBJECT_ID, catalog.head, catalog.manifest));
    ByteBuffer first = catalog.records.get(0);
    assertEquals(StatusCode.OK, builder.accept(first, 0, first.limit()));
    assertEquals(StatusCode.CORRUPTION, builder.accept(first, 0, first.limit()));

    EncodedCatalog other = encodeWithIdentity(columns(33, false), null, OBJECT_ID + 1, 40);
    assertEquals(StatusCode.OK, builder.begin(OBJECT_ID, catalog.head, catalog.manifest));
    ByteBuffer otherFirst = other.records.get(0);
    assertEquals(StatusCode.CORRUPTION,
        builder.accept(otherFirst, 0, otherFirst.limit()));
  }

  @Test
  void childSetChecksumRejectsARechecksummedButSubstitutedPayload() {
    EncodedCatalog catalog = encode(columns(33, false), null);
    ByteBuffer original = catalog.records.get(0);
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
    assertEquals(StatusCode.OK, builder.begin(OBJECT_ID, catalog.head, catalog.manifest));
    assertEquals(StatusCode.OK,
        builder.accept(changedRecord, 0, changedRecord.limit()));
    ByteBuffer second = catalog.records.get(1);
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
        builder.begin(OBJECT_ID, first.head, other.manifest));
    assertEquals(StatusCode.CORRUPTION,
        builder.begin(OBJECT_ID + 1, first.head, first.manifest));

    assertEquals(StatusCode.OK, builder.begin(OBJECT_ID, first.head, first.manifest));
    decodeManifest(other.manifestBytes, first.manifest);
    for (ByteBuffer record : first.records) {
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
    assertEquals(StatusCode.OK, builder.begin(OBJECT_ID, catalog.head, catalog.manifest));
    ByteBuffer record = catalog.records.get(0);
    assertEquals(StatusCode.CORRUPTION, builder.accept(record, 0, record.limit()));

    EncodedCatalog complete = encode(columns(2, false), null);
    TableDescriptor.Result result = new TableDescriptor.Result();
    assertEquals(StatusCode.OK, builder.begin(OBJECT_ID, complete.head, complete.manifest));
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
          OBJECT_ID, invalidNameCatalog.head, invalidNameCatalog.manifest));
      ByteBuffer nameRecord = invalidNameCatalog.records.get(0);
      assertEquals(StatusCode.CORRUPTION,
          builder.accept(nameRecord, 0, nameRecord.limit()));
    }

    ColumnDescriptorSet columns = columns(1, false);
    ByteBuffer foreign = rawKeyPayload(
        88, 0, KeyDescriptor.KIND_FOREIGN, 0);
    EncodedCatalog invalidForeign = encodeColumnsAndRawKey(columns, foreign, 1);
    assertEquals(StatusCode.OK,
        builder.begin(OBJECT_ID, invalidForeign.head, invalidForeign.manifest));
    assertEquals(StatusCode.OK, builder.accept(
        invalidForeign.records.get(0), 0, invalidForeign.records.get(0).limit()));
    assertEquals(StatusCode.CORRUPTION, builder.accept(
        invalidForeign.records.get(1), 0, invalidForeign.records.get(1).limit()));
  }

  @Test
  void exactEightKilobyteRowPublishesAndOversizeRemainsPrivate() {
    ColumnDescriptorSet exact = rowBoundaryColumns(2);
    EncodedCatalog exactCatalog = encode(exact, null);
    TableDescriptor exactTable = assemble(exactCatalog);
    assertEquals(8_192, exactTable.encodedMaximumRowBytes());

    ColumnDescriptorSet over = rowBoundaryColumns(3);
    EncodedCatalog overCatalog = encode(over, null);
    CatalogTableAssemblyBuilder builder = new CatalogTableAssemblyBuilder();
    assertEquals(StatusCode.OK,
        builder.begin(OBJECT_ID, overCatalog.head, overCatalog.manifest));
    for (ByteBuffer record : overCatalog.records) {
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
    assertEquals(StatusCode.OK, builder.begin(OBJECT_ID, catalog.head, catalog.manifest));
    for (ByteBuffer record : catalog.records) {
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

  private static final long OBJECT_ID = 10;
  private static final long SCHEMA_ID = 11;
  private static final long LAYOUT_ID = 12;
  private static final long GENERATION = 13;
  private static final long MANIFEST_ID = 900;
  private static final long FIRST_CHILD = 1_000;

  private static EncodedCatalog encode(ColumnDescriptorSet columns, KeyDescriptor primary) {
    return encodeWithIdentity(columns, primary, OBJECT_ID, FIRST_CHILD);
  }

  private static EncodedCatalog encode(
      TableDescriptor table,
      CatalogTablePayloadPacker packer,
      CatalogTablePayloadPlan plan) {
    List<ByteBuffer> records = new ArrayList<>();
    int keyParts = 0;
    for (int chunk = 0; chunk < plan.chunkCount(); chunk++) {
      ByteBuffer payload = ByteBuffer.allocate(plan.payloadBytesAt(chunk));
      assertEquals(StatusCode.OK,
          packer.encodeChunk(table, plan, chunk, payload, 0));
      int kind = plan.kindAt(chunk);
      records.add(record(FIRST_CHILD + chunk, OBJECT_ID, kind, chunk,
          plan.logicalStartAt(chunk), plan.logicalCountAt(chunk), payload));
      if (kind == CatalogDefinitionRecordCodec.KIND_KEY) {
        keyParts += plan.logicalCountAt(chunk);
      }
    }
    return finishCatalog(OBJECT_ID, FIRST_CHILD, table.columnCount(), keyParts,
        plan.totalPayloadBytes(), records);
  }

  private static EncodedCatalog encodeWithIdentity(
      ColumnDescriptorSet columns, KeyDescriptor primary, long objectId, long firstChild) {
    TableDescriptor.Result table = new TableDescriptor.Result();
    if (primary != null) {
      assertEquals(StatusCode.OK, TableDescriptor.create(
          objectId, LAYOUT_ID, GENERATION, columns, primary, null, null, table, null));
    }
    List<ByteBuffer> records = new ArrayList<>();
    int payloadBytes = 0;
    int ordinal = 0;
    for (int first = 0; first < columns.count(); first += 32) {
      int count = Math.min(32, columns.count() - first);
      CatalogPayloadSize size = new CatalogPayloadSize();
      assertEquals(StatusCode.OK,
          CatalogColumnPayloadCodec.payloadBytes(columns, first, count, size));
      ByteBuffer payload = ByteBuffer.allocate(size.bytes());
      assertEquals(StatusCode.OK,
          CatalogColumnPayloadCodec.encode(columns, first, count, payload, 0));
      records.add(record(firstChild + ordinal, objectId, CatalogDefinitionRecordCodec.KIND_COLUMNS,
          ordinal, first, count, payload));
      payloadBytes += size.bytes();
      ordinal++;
    }
    int keyParts = primary == null ? 0 : primary.partCount();
    if (primary != null) {
      CatalogPayloadSize size = new CatalogPayloadSize();
      assertEquals(StatusCode.OK,
          CatalogKeyPayloadCodec.payloadBytes(table.value(), 0, 1, size));
      ByteBuffer payload = ByteBuffer.allocate(size.bytes());
      assertEquals(StatusCode.OK,
          CatalogKeyPayloadCodec.encode(table.value(), 0, 1, payload, 0));
      records.add(record(firstChild + ordinal, objectId, CatalogDefinitionRecordCodec.KIND_KEY,
          ordinal, 0, keyParts, payload));
      payloadBytes += size.bytes();
    }
    return finishCatalog(objectId, firstChild, columns.count(), keyParts,
        payloadBytes, records);
  }

  private static EncodedCatalog encodeRawColumn(ByteBuffer payload, int columns) {
    List<ByteBuffer> records = new ArrayList<>();
    records.add(record(FIRST_CHILD, OBJECT_ID, CatalogDefinitionRecordCodec.KIND_COLUMNS,
        0, 0, columns, payload));
    return finishCatalog(OBJECT_ID, FIRST_CHILD, columns, 0,
        payload.limit(), records);
  }

  private static ByteBuffer rawColumnName(byte[] name) {
    ByteBuffer payload = ByteBuffer.allocate(8 + 12 + name.length);
    FormatBytes.putInt(payload, 0, CatalogColumnPayloadCodec.VERSION);
    FormatBytes.putInt(payload, 4, 1);
    FormatBytes.putInt(payload, 8, SqlTypeDescriptor.BOOLEAN);
    FormatBytes.putInt(payload, 12, 0);
    FormatBytes.putInt(payload, 16, name.length);
    for (int index = 0; index < name.length; index++) payload.put(20 + index, name[index]);
    return payload;
  }

  private static EncodedCatalog encodeColumnsAndRawKey(
      ColumnDescriptorSet columns, ByteBuffer keyPayload, int keyParts) {
    CatalogPayloadSize size = new CatalogPayloadSize();
    assertEquals(StatusCode.OK,
        CatalogColumnPayloadCodec.payloadBytes(columns, 0, columns.count(), size));
    ByteBuffer columnPayload = ByteBuffer.allocate(size.bytes());
    assertEquals(StatusCode.OK,
        CatalogColumnPayloadCodec.encode(columns, 0, columns.count(), columnPayload, 0));
    List<ByteBuffer> records = new ArrayList<>();
    records.add(record(FIRST_CHILD, OBJECT_ID, CatalogDefinitionRecordCodec.KIND_COLUMNS,
        0, 0, columns.count(), columnPayload));
    records.add(record(FIRST_CHILD + 1, OBJECT_ID, CatalogDefinitionRecordCodec.KIND_KEY,
        1, 0, keyParts, keyPayload));
    return finishCatalog(OBJECT_ID, FIRST_CHILD, columns.count(), keyParts,
        columnPayload.limit() + keyPayload.limit(), records);
  }

  private static EncodedCatalog finishCatalog(
      long objectId, long firstChild, int columns, int keyParts,
      int payloadBytes, List<ByteBuffer> records) {
    CRC32C children = new CRC32C();
    CatalogDefinitionRecord decoded = new CatalogDefinitionRecord();
    for (ByteBuffer record : records) {
      assertEquals(StatusCode.OK, CatalogDefinitionRecordCodec.decode(
          record, 0, record.limit(), decoded, new CRC32C()));
      CatalogDefinitionRecordCodec.updateChildSetChecksum(children, decoded.recordChecksum());
    }
    ByteBuffer manifestBytes = ByteBuffer.allocate(CatalogDefinitionManifestCodec.BYTES);
    assertEquals(StatusCode.OK, CatalogDefinitionManifestCodec.encode(manifestBytes, 0,
        CatalogDefinitionManifestCodec.KIND_TABLE, MANIFEST_ID, objectId, SCHEMA_ID,
        LAYOUT_ID, GENERATION, firstChild, records.size(), columns, keyParts,
        columns + keyParts, payloadBytes, (int) children.getValue(), new CRC32C()));
    CatalogDefinitionManifest manifest = new CatalogDefinitionManifest();
    decodeManifest(manifestBytes, manifest);
    ByteBuffer headBytes = ByteBuffer.allocate(CatalogObjectHeadCodec.BYTES);
    assertEquals(StatusCode.OK, CatalogObjectHeadCodec.encode(headBytes, 0,
        CatalogObjectHeadCodec.STATE_READY, objectId, SCHEMA_ID, GENERATION,
        MANIFEST_ID, new CRC32C()));
    CatalogObjectHead head = new CatalogObjectHead();
    assertEquals(StatusCode.OK,
        CatalogObjectHeadCodec.decode(headBytes, 0, head, new CRC32C()));
    return new EncodedCatalog(head, manifest, manifestBytes, records);
  }

  private static ByteBuffer record(
      long recordId, long objectId, int kind, int ordinal,
      int logicalStart, int logicalCount, ByteBuffer payload) {
    ByteBuffer encoded = ByteBuffer.allocate(
        CatalogDefinitionRecordCodec.HEADER_BYTES + payload.limit());
    assertEquals(StatusCode.OK, CatalogDefinitionRecordCodec.encode(encoded, 0,
        recordId, objectId, SCHEMA_ID, GENERATION, kind, ordinal, logicalStart,
        logicalCount, payload, new CRC32C()));
    return encoded;
  }

  private static TableDescriptor assemble(EncodedCatalog catalog) {
    CatalogTableAssemblyBuilder builder = new CatalogTableAssemblyBuilder();
    assertEquals(StatusCode.OK, builder.begin(catalog.head.objectId(), catalog.head,
        catalog.manifest));
    for (ByteBuffer record : catalog.records) {
      assertEquals(StatusCode.OK, builder.accept(record, 0, record.limit()));
    }
    TableDescriptor.Result result = new TableDescriptor.Result();
    assertEquals(StatusCode.OK, builder.finish(result, new StatusDetail(64)));
    return result.value();
  }

  private static ColumnDescriptorSet columns(int count, boolean unicode) {
    int[] types = new int[count];
    CharSequence[] names = new CharSequence[count];
    boolean[] nullable = new boolean[count];
    for (int index = 0; index < count; index++) {
      types[index] = index == 1 && unicode ? SqlTypeDescriptor.varchar(12)
          : SqlTypeDescriptor.BOOLEAN;
      names[index] = index == 1 && unicode ? "café😀" : "c" + index;
      nullable[index] = (index & 1) != 0;
    }
    ColumnDescriptorSet.Result result = new ColumnDescriptorSet.Result();
    assertEquals(StatusCode.OK, ColumnDescriptorSet.create(types, names, nullable, result));
    return result.value();
  }

  private static ColumnDescriptorSet rowBoundaryColumns(int trailingBooleans) {
    int count = 8 + trailingBooleans;
    int[] types = new int[count];
    CharSequence[] names = new CharSequence[count];
    boolean[] nullable = new boolean[count];
    for (int index = 0; index < 8; index++) {
      types[index] = SqlTypeDescriptor.varchar(index == 0 ? 238 : 255);
      names[index] = "v" + index;
    }
    for (int index = 8; index < count; index++) {
      types[index] = SqlTypeDescriptor.BOOLEAN;
      names[index] = "b" + index;
    }
    ColumnDescriptorSet.Result result = new ColumnDescriptorSet.Result();
    assertEquals(StatusCode.OK, ColumnDescriptorSet.create(types, names, nullable, result));
    return result.value();
  }
  private static ByteBuffer duplicateKeyPayload() {
    ByteBuffer payload = ByteBuffer.allocate(16 + 2 * (32 + 4));
    FormatBytes.putInt(payload, 0, CatalogKeyPayloadCodec.VERSION);
    FormatBytes.putInt(payload, 4, 2);
    FormatBytes.putLong(payload, 8, 1);
    int cursor = 16;
    for (int index = 0; index < 2; index++) {
      FormatBytes.putLong(payload, cursor, 88);
      FormatBytes.putLong(payload, cursor + 8, 0);
      FormatBytes.putInt(payload, cursor + 16, KeyDescriptor.KIND_SECONDARY);
      FormatBytes.putInt(payload, cursor + 20, 0);
      FormatBytes.putInt(payload, cursor + 24, 1);
      FormatBytes.putInt(payload, cursor + 28, 0);
      FormatBytes.putInt(payload, cursor + 32, index);
      cursor += 36;
    }
    return payload;
  }
  private static ByteBuffer rawKeyPayload(
      long keyId, long referencedKeyId, int kind, int ordinal) {
    ByteBuffer payload = ByteBuffer.allocate(16 + 32 + 4);
    FormatBytes.putInt(payload, 0, CatalogKeyPayloadCodec.VERSION);
    FormatBytes.putInt(payload, 4, 1);
    FormatBytes.putLong(payload, 8, 0);
    FormatBytes.putLong(payload, 16, keyId);
    FormatBytes.putLong(payload, 24, referencedKeyId);
    FormatBytes.putInt(payload, 32, kind);
    FormatBytes.putInt(payload, 36, 0);
    FormatBytes.putInt(payload, 40, 1);
    FormatBytes.putInt(payload, 44, 0);
    FormatBytes.putInt(payload, 48, ordinal);
    return payload;
  }

  private static KeyDescriptor key(
      long keyId, int kind, boolean unique, ColumnDescriptorSet columns,
      int[] ordinals, long referencedKeyId) {
    KeyDescriptor.Result result = new KeyDescriptor.Result();
    assertEquals(StatusCode.OK, KeyDescriptor.create(
        keyId, kind, unique, columns, ordinals, referencedKeyId, result, null));
    return result.value();
  }

  private static void decodeManifest(
      ByteBuffer bytes, CatalogDefinitionManifest result) {
    assertEquals(StatusCode.OK,
        CatalogDefinitionManifestCodec.decode(bytes, 0, result, new CRC32C()));
  }

  private static ByteBuffer copy(ByteBuffer source) {
    ByteBuffer copied = ByteBuffer.allocate(source.limit());
    for (int index = 0; index < source.limit(); index++) copied.put(index, source.get(index));
    return copied;
  }

  private static String name(ColumnDescriptorSet columns, int ordinal) {
    char[] chars = new char[columns.nameByteLength(ordinal)];
    int count = columns.copyNameChars(ordinal, chars, 0);
    return new String(chars, 0, count);
  }

  private record EncodedCatalog(
      CatalogObjectHead head,
      CatalogDefinitionManifest manifest,
      ByteBuffer manifestBytes,
      List<ByteBuffer> records) {
  }
}
