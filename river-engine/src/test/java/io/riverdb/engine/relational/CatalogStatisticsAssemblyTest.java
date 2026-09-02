package io.riverdb.engine.relational;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import io.riverdb.base.error.StatusCode;
import io.riverdb.format.catalog.CatalogAssemblyValidator;
import io.riverdb.format.catalog.CatalogDefinitionManifest;
import io.riverdb.format.catalog.CatalogDefinitionManifestCodec;
import io.riverdb.format.catalog.CatalogDefinitionRecord;
import io.riverdb.format.catalog.CatalogDefinitionRecordCodec;
import java.nio.ByteBuffer;
import java.util.zip.CRC32C;
import org.junit.jupiter.api.Test;

/** Byte-exact complete-set rejection for segmented statistics generations. */
final class CatalogStatisticsAssemblyTest {
  @Test
  void rejectsMissingCorruptAndWrongGenerationChildren() {
    Fixture fixture = new Fixture();
    CatalogAssemblyValidator assembly = new CatalogAssemblyValidator();
    assertEquals(StatusCode.OK, assembly.begin(fixture.manifest, new CRC32C()));
    assertEquals(StatusCode.OK, assembly.accept(fixture.first));
    assertFalse(assembly.complete());

    assembly.reset();
    assertEquals(StatusCode.OK, assembly.begin(fixture.manifest, new CRC32C()));
    assertEquals(StatusCode.OK, assembly.accept(fixture.first));
    assertEquals(StatusCode.CORRUPTION, assembly.accept(fixture.wrongGeneration));

    fixture.secondBytes.put(
        CatalogDefinitionRecordCodec.HEADER_BYTES,
        (byte) (fixture.secondBytes.get(CatalogDefinitionRecordCodec.HEADER_BYTES) ^ 1));
    assertEquals(StatusCode.CORRUPTION, CatalogDefinitionRecordCodec.decode(
        fixture.secondBytes, 0, fixture.secondBytes.limit(),
        new CatalogDefinitionRecord(), new CRC32C()));
  }

  private static final class Fixture {
    private static final long OBJECT = 7;
    private static final long SCHEMA = 11;
    private static final long GENERATION = 13;
    private static final long MANIFEST = 17;
    private static final long FIRST_CHILD = 18;
    final CatalogDefinitionRecord first = new CatalogDefinitionRecord();
    final CatalogDefinitionRecord second = new CatalogDefinitionRecord();
    final CatalogDefinitionRecord wrongGeneration = new CatalogDefinitionRecord();
    final CatalogDefinitionManifest manifest = new CatalogDefinitionManifest();
    final ByteBuffer secondBytes = ByteBuffer.allocate(128);

    Fixture() {
      ByteBuffer firstBytes = ByteBuffer.allocate(128);
      encodeChild(firstBytes, FIRST_CHILD, GENERATION, 0, 0, 128, first);
      encodeChild(secondBytes, FIRST_CHILD + 1, GENERATION, 1, 128, 1, second);
      ByteBuffer wrong = ByteBuffer.allocate(128);
      encodeChild(wrong, FIRST_CHILD + 1, GENERATION + 1, 1, 128, 1, wrongGeneration);
      CRC32C children = new CRC32C();
      CatalogDefinitionRecordCodec.updateChildSetChecksum(children, first.recordChecksum());
      CatalogDefinitionRecordCodec.updateChildSetChecksum(children, second.recordChecksum());
      ByteBuffer bytes = ByteBuffer.allocate(CatalogDefinitionManifestCodec.BYTES);
      assertEquals(StatusCode.OK, CatalogDefinitionManifestCodec.encode(
          bytes, 0, CatalogDefinitionManifestCodec.KIND_STATISTICS,
          MANIFEST, OBJECT, SCHEMA, 12, GENERATION, FIRST_CHILD, 2,
          129, 0, 129, 2, (int) children.getValue(), new CRC32C()));
      bytes.position(0).limit(CatalogDefinitionManifestCodec.BYTES);
      assertEquals(StatusCode.OK, CatalogDefinitionManifestCodec.decode(
          bytes, 0, manifest, new CRC32C()));
    }

    private static void encodeChild(
        ByteBuffer target, long id, long generation, int ordinal,
        int logicalStart, int logicalCount, CatalogDefinitionRecord result) {
      ByteBuffer payload = ByteBuffer.allocate(1);
      payload.put(0, (byte) ordinal).position(0).limit(1);
      assertEquals(StatusCode.OK, CatalogDefinitionRecordCodec.encode(
          target, 0, id, OBJECT, SCHEMA, generation,
          CatalogDefinitionRecordCodec.KIND_STATISTICS,
          ordinal, logicalStart, logicalCount, payload, new CRC32C()));
      target.position(0).limit(CatalogDefinitionRecordCodec.HEADER_BYTES + 1);
      assertEquals(StatusCode.OK, CatalogDefinitionRecordCodec.decode(
          target, 0, target.limit(), result, new CRC32C()));
    }
  }
}
