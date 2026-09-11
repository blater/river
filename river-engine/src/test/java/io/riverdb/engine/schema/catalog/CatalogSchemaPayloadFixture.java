package io.riverdb.engine.schema.catalog;

import static org.junit.jupiter.api.Assertions.assertEquals;

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
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.CRC32C;

/** Encoded catalog carriers shared by schema payload tests. */
final class CatalogSchemaPayloadFixture {
  static final long OBJECT_ID = 10;
  static final long SCHEMA_ID = 11;
  static final long LAYOUT_ID = 12;
  static final long GENERATION = 13;
  static final long MANIFEST_ID = 900;
  static final long FIRST_CHILD = 1_000;

  static EncodedCatalog encode(ColumnDescriptorSet columns, KeyDescriptor primary) {
    return encodeWithIdentity(columns, primary, OBJECT_ID, FIRST_CHILD);
  }

  static EncodedCatalog encode(
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

  static EncodedCatalog encodeWithIdentity(
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

  static EncodedCatalog encodeRawColumn(ByteBuffer payload, int columns) {
    List<ByteBuffer> records = new ArrayList<>();
    records.add(record(FIRST_CHILD, OBJECT_ID, CatalogDefinitionRecordCodec.KIND_COLUMNS,
        0, 0, columns, payload));
    return finishCatalog(OBJECT_ID, FIRST_CHILD, columns, 0,
        payload.limit(), records);
  }

  static ByteBuffer rawColumnName(byte[] name) {
    ByteBuffer payload = ByteBuffer.allocate(8 + 12 + name.length);
    FormatBytes.putInt(payload, 0, CatalogColumnPayloadCodec.VERSION);
    FormatBytes.putInt(payload, 4, 1);
    FormatBytes.putInt(payload, 8, SqlTypeDescriptor.BOOLEAN);
    FormatBytes.putInt(payload, 12, 0);
    FormatBytes.putInt(payload, 16, name.length);
    for (int index = 0; index < name.length; index++) payload.put(20 + index, name[index]);
    return payload;
  }

  static EncodedCatalog encodeColumnsAndRawKey(
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

  static EncodedCatalog finishCatalog(
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

  static ByteBuffer record(
      long recordId, long objectId, int kind, int ordinal,
      int logicalStart, int logicalCount, ByteBuffer payload) {
    ByteBuffer encoded = ByteBuffer.allocate(
        CatalogDefinitionRecordCodec.HEADER_BYTES + payload.limit());
    assertEquals(StatusCode.OK, CatalogDefinitionRecordCodec.encode(encoded, 0,
        recordId, objectId, SCHEMA_ID, GENERATION, kind, ordinal, logicalStart,
        logicalCount, payload, new CRC32C()));
    return encoded;
  }

  static TableDescriptor assemble(EncodedCatalog catalog) {
    CatalogTableAssemblyBuilder builder = new CatalogTableAssemblyBuilder();
    assertEquals(StatusCode.OK, builder.begin(catalog.head().objectId(), catalog.head(),
        catalog.manifest()));
    for (ByteBuffer record : catalog.records()) {
      assertEquals(StatusCode.OK, builder.accept(record, 0, record.limit()));
    }
    TableDescriptor.Result result = new TableDescriptor.Result();
    assertEquals(StatusCode.OK, builder.finish(result, new StatusDetail(64)));
    return result.value();
  }

  static ColumnDescriptorSet columns(int count, boolean unicode) {
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

  static ColumnDescriptorSet rowBoundaryColumns(int trailingBooleans) {
    int count = 1 + trailingBooleans;
    int[] types = new int[count];
    CharSequence[] names = new CharSequence[count];
    boolean[] nullable = new boolean[count];
    types[0] = SqlTypeDescriptor.varchar(4_043);
    names[0] = "v0";
    for (int index = 1; index < count; index++) {
      types[index] = SqlTypeDescriptor.BOOLEAN;
      names[index] = "b" + index;
    }
    ColumnDescriptorSet.Result result = new ColumnDescriptorSet.Result();
    assertEquals(StatusCode.OK, ColumnDescriptorSet.create(types, names, nullable, result));
    return result.value();
  }
  static ByteBuffer duplicateKeyPayload() {
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
  static ByteBuffer rawKeyPayload(
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

  static KeyDescriptor key(
      long keyId, int kind, boolean unique, ColumnDescriptorSet columns,
      int[] ordinals, long referencedKeyId) {
    KeyDescriptor.Result result = new KeyDescriptor.Result();
    assertEquals(StatusCode.OK, KeyDescriptor.create(
        keyId, kind, unique, columns, ordinals, referencedKeyId, result, null));
    return result.value();
  }

  static void decodeManifest(
      ByteBuffer bytes, CatalogDefinitionManifest result) {
    assertEquals(StatusCode.OK,
        CatalogDefinitionManifestCodec.decode(bytes, 0, result, new CRC32C()));
  }

  static String name(ColumnDescriptorSet columns, int ordinal) {
    char[] chars = new char[columns.nameByteLength(ordinal)];
    int count = columns.copyNameChars(ordinal, chars, 0);
    return new String(chars, 0, count);
  }

  record EncodedCatalog(
      CatalogObjectHead head,
      CatalogDefinitionManifest manifest,
      ByteBuffer manifestBytes,
      List<ByteBuffer> records) {
  }
}
