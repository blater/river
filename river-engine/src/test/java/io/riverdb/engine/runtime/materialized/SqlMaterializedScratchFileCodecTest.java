package io.riverdb.engine.runtime.materialized;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.riverdb.base.error.StatusCode;
import io.riverdb.base.error.StatusDetail;
import java.nio.ByteBuffer;
import org.junit.jupiter.api.Test;

final class SqlMaterializedScratchFileCodecTest {
  @Test
  void encodesExactHeadersAndRejectsVersionAndChecksumCorruption() {
    ByteBuffer file = ByteBuffer.allocate(64);
    assertEquals(
        StatusCode.OK,
        SqlMaterializedScratchFileCodec.encodeFileHeader(
            file, SqlMaterializedScratchFileKind.ROWS, 64, 0, 0, 9, 17, 123));
    SqlMaterializedScratchFileCodec.Header decoded =
        new SqlMaterializedScratchFileCodec.Header();
    StatusDetail detail = new StatusDetail(256);
    assertEquals(
        StatusCode.OK,
        SqlMaterializedScratchFileCodec.validateFileHeader(
            file, SqlMaterializedScratchFileKind.ROWS, 64, 9, decoded, detail));
    assertEquals(17, decoded.publishedCount());
    assertEquals(123, decoded.logicalLength());

    file.putInt(8, 2);
    assertEquals(
        StatusCode.CORRUPTION,
        SqlMaterializedScratchFileCodec.validateFileHeader(
            file, SqlMaterializedScratchFileKind.ROWS, 64, 9, decoded, detail));
    assertEquals(StatusCode.CORRUPTION, detail.code());
    assertEquals(0, decoded.logicalLength());

    assertEquals(
        StatusCode.OK,
        SqlMaterializedScratchFileCodec.encodeFileHeader(
            file, SqlMaterializedScratchFileKind.ROWS, 64, 0, 0, 9, 17, 123));
    file.put(33, (byte) (file.get(33) ^ 1));
    assertEquals(
        StatusCode.CORRUPTION,
        SqlMaterializedScratchFileCodec.validateFileHeader(
            file, SqlMaterializedScratchFileKind.ROWS, 64, 9, decoded, detail));
    assertEquals(0, decoded.logicalLength());
  }

  @Test
  void pageHeaderUsesPayloadChecksumAndIdentityAndNumber() {
    ByteBuffer page = ByteBuffer.allocate(64);
    page.put(32, (byte) 4);
    assertEquals(
        StatusCode.OK,
        SqlMaterializedScratchFileCodec.encodePageHeader(page, 7, 3, 1));
    SqlMaterializedScratchFileCodec.PageHeader decoded =
        new SqlMaterializedScratchFileCodec.PageHeader();
    StatusDetail detail = new StatusDetail(256);
    assertEquals(
        StatusCode.OK,
        SqlMaterializedScratchFileCodec.validatePageHeader(page, 7, 3, decoded, detail));
    assertEquals(1, decoded.usedBytes());
    page.put(32, (byte) 5);
    assertEquals(
        StatusCode.CORRUPTION,
        SqlMaterializedScratchFileCodec.validatePageHeader(page, 7, 3, decoded, detail));
    assertEquals(StatusCode.CORRUPTION, detail.code());
    assertEquals(
        StatusCode.CORRUPTION,
        SqlMaterializedScratchFileCodec.validatePageHeader(page, 8, 3, decoded, detail));
  }
}
