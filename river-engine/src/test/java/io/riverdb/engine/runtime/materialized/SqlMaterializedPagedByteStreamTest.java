package io.riverdb.engine.runtime.materialized;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.riverdb.base.error.StatusCode;
import io.riverdb.base.error.StatusDetail;
import io.riverdb.base.id.DatabaseIncarnation;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class SqlMaterializedPagedByteStreamTest {
  private static final DatabaseIncarnation DATABASE =
      DatabaseIncarnation.of(11, 22);

  @Test
  void appendsAndReadsAcrossPagesWithLongLogicalPositions(@TempDir Path root) throws Exception {
    SqlMaterializedPagePoolResult poolResult = new SqlMaterializedPagePoolResult();
    assertEquals(StatusCode.OK, SqlMaterializedPagePool.create(64, 2, poolResult));
    SqlMaterializedPagePool pool = poolResult.pool();
    Path primary = Files.createDirectory(root.resolve("database"));
    Path spill = Files.createDirectory(root.resolve("spill"));
    SqlMaterializedScratchRuntime.OpenResult runtimeResult =
        new SqlMaterializedScratchRuntime.OpenResult();
    StatusDetail detail = new StatusDetail(256);
    assertEquals(
        StatusCode.OK,
        SqlMaterializedScratchRuntime.create(
            spill, primary, DATABASE, pool, runtimeResult, detail));
    SqlMaterializedScratchOwner.Result ownerResult =
        new SqlMaterializedScratchOwner.Result();
    assertEquals(StatusCode.OK, runtimeResult.runtime().openOwner(ownerResult, detail));
    SqlMaterializedScratchStore.Result storeResult =
        new SqlMaterializedScratchStore.Result();
    assertEquals(StatusCode.OK, ownerResult.owner().openStore(storeResult, detail));
    SqlMaterializedScratchFile.Result fileResult =
        new SqlMaterializedScratchFile.Result();
    assertEquals(
        StatusCode.OK,
        storeResult.store().open(SqlMaterializedScratchFileKind.ROWS, fileResult, detail));

    SqlMaterializedPagedByteStream.Result streamResult =
        new SqlMaterializedPagedByteStream.Result();
    assertEquals(
        StatusCode.OK,
        SqlMaterializedPagedByteStream.createNew(
            ownerResult.owner(), fileResult.file(), SqlMaterializedScratchFileKind.ROWS,
            64, 0, 0, streamResult, detail));
    SqlMaterializedPagedByteStream stream = streamResult.stream();
    byte[] inputBytes = new byte[100];
    for (int index = 0; index < inputBytes.length; index++) inputBytes[index] = (byte) index;
    ByteBuffer input = ByteBuffer.wrap(inputBytes);
    SqlMaterializedPagedByteStream.AppendResult append =
        new SqlMaterializedPagedByteStream.AppendResult();
    assertEquals(StatusCode.OK, stream.append(input, append, detail), detail::asString);
    assertEquals(100, append.bytes());
    assertEquals(1, append.newCount());
    assertEquals(100, stream.logicalLength());
    assertEquals(100, input.position());

    ByteBuffer output = ByteBuffer.allocate(70);
    assertEquals(StatusCode.OK, stream.read(30, output, detail), detail::asString);
    assertEquals(70, output.position());
    byte[] expected = new byte[70];
    System.arraycopy(inputBytes, 30, expected, 0, expected.length);
    assertArrayEquals(expected, output.array());
    byte[] replacement = new byte[16];
    for (int index = 0; index < replacement.length; index++) replacement[index] = (byte) -index;
    assertEquals(StatusCode.OK, stream.overwrite(
        28, ByteBuffer.wrap(replacement), detail), detail::asString);
    ByteBuffer replaced = ByteBuffer.allocate(replacement.length);
    assertEquals(StatusCode.OK, stream.read(28, replaced, detail), detail::asString);
    assertArrayEquals(replacement, replaced.array());
    assertEquals(StatusCode.OK, stream.seal(detail));
    assertEquals(StatusCode.OK, ownerResult.owner().invalidate(fileResult.file()));
    SqlMaterializedPagedByteStream.Result reopened =
        new SqlMaterializedPagedByteStream.Result();
    assertEquals(StatusCode.OK, SqlMaterializedPagedByteStream.openExisting(
        ownerResult.owner(), fileResult.file(), SqlMaterializedScratchFileKind.ROWS,
        64, reopened, detail));
    replaced.clear();
    assertEquals(StatusCode.OK, reopened.stream().read(28, replaced, detail));
    assertArrayEquals(replacement, replaced.array());
    assertEquals(StatusCode.OK, reopened.stream().close(detail));
    assertEquals(StatusCode.OK, ownerResult.owner().close(detail));
    assertEquals(StatusCode.OK, runtimeResult.runtime().close(detail));
    assertEquals(StatusCode.OK, pool.close());
  }

  @Test
  void readsSparseProductionPageBeyondFormer256MiBBoundary(@TempDir Path root)
      throws Exception {
    int pageBytes = 64;
    long logicalOffset = 268_435_456L + 17;
    SqlMaterializedPageLocation location = new SqlMaterializedPageLocation();
    assertEquals(StatusCode.OK,
        SqlMaterializedPageMapping.map(logicalOffset, pageBytes, location));
    SqlMaterializedPagePoolResult poolResult = new SqlMaterializedPagePoolResult();
    assertEquals(StatusCode.OK, SqlMaterializedPagePool.create(pageBytes, 2, poolResult));
    Path primary = Files.createDirectory(root.resolve("database"));
    Path spill = Files.createDirectory(root.resolve("spill"));
    SqlMaterializedScratchRuntime.OpenResult runtime =
        new SqlMaterializedScratchRuntime.OpenResult();
    StatusDetail detail = new StatusDetail(256);
    assertEquals(StatusCode.OK, SqlMaterializedScratchRuntime.create(
        spill, primary, DATABASE, poolResult.pool(), runtime, detail));
    SqlMaterializedScratchOwner.Result owner = new SqlMaterializedScratchOwner.Result();
    assertEquals(StatusCode.OK, runtime.runtime().openOwner(owner, detail));
    SqlMaterializedScratchStore.Result store = new SqlMaterializedScratchStore.Result();
    assertEquals(StatusCode.OK, owner.owner().openStore(store, detail));
    SqlMaterializedScratchFile.Result file = new SqlMaterializedScratchFile.Result();
    assertEquals(StatusCode.OK, store.store().open(
        SqlMaterializedScratchFileKind.ROWS, file, detail));
    SqlMaterializedPagedByteStream.Result created =
        new SqlMaterializedPagedByteStream.Result();
    assertEquals(StatusCode.OK, SqlMaterializedPagedByteStream.createNew(
        owner.owner(), file.file(), SqlMaterializedScratchFileKind.ROWS,
        pageBytes, 0, 0, created, detail));

    SqlMaterializedPagePin pin = new SqlMaterializedPagePin();
    assertEquals(StatusCode.OK,
        owner.owner().pinNew(file.file(), location.pageNumber(), pin));
    pin.buffer().put(location.payloadOffset(), (byte) 0x5a);
    int used = location.payloadOffset() - SqlMaterializedPageMapping.PAGE_HEADER_BYTES + 1;
    assertEquals(StatusCode.OK, SqlMaterializedScratchFileCodec.updateResidentPageHeader(
        pin.buffer(), file.file().fileIdentity(), location.pageNumber(), used));
    assertEquals(StatusCode.OK, owner.owner().markDirty(pin));
    assertEquals(StatusCode.OK, owner.owner().unpin(pin));
    assertEquals(StatusCode.OK, owner.owner().flush(file.file()));
    ByteBuffer header = ByteBuffer.allocate(SqlMaterializedScratchFileCodec.FILE_HEADER_BYTES);
    assertEquals(StatusCode.OK, SqlMaterializedScratchFileCodec.encodeFileHeader(
        header, SqlMaterializedScratchFileKind.ROWS, pageBytes, 0, 0,
        file.file().fileIdentity(), 1, logicalOffset + 1));
    header.clear();
    long headerOffset = 0;
    while (header.hasRemaining()) {
      int written = file.file().channel().write(header, headerOffset);
      assertTrue(written > 0);
      headerOffset += written;
    }
    assertEquals(SqlMaterializedScratchFileCodec.FILE_HEADER_BYTES, headerOffset);
    assertEquals(StatusCode.OK, owner.owner().invalidate(file.file()));

    SqlMaterializedPagedByteStream.Result reopened =
        new SqlMaterializedPagedByteStream.Result();
    assertEquals(StatusCode.OK, SqlMaterializedPagedByteStream.openExisting(
        owner.owner(), file.file(), SqlMaterializedScratchFileKind.ROWS,
        pageBytes, reopened, detail));
    ByteBuffer read = ByteBuffer.allocate(1);
    assertEquals(StatusCode.OK,
        reopened.stream().read(logicalOffset, read, detail), detail::asString);
    assertEquals((byte) 0x5a, read.get(0));
    assertEquals(StatusCode.OK, reopened.stream().close(detail));
    assertEquals(StatusCode.OK, owner.owner().close(detail));
    assertEquals(StatusCode.OK, runtime.runtime().close(detail));
    assertEquals(StatusCode.OK, poolResult.pool().close());
  }

  @Test
  void resetTruncatesAndReusesTheSameStream(@TempDir Path root) throws Exception {
    SqlMaterializedPagePoolResult poolResult = new SqlMaterializedPagePoolResult();
    assertEquals(StatusCode.OK, SqlMaterializedPagePool.create(64, 2, poolResult));
    Path primary = Files.createDirectory(root.resolve("database"));
    Path spill = Files.createDirectory(root.resolve("spill"));
    SqlMaterializedScratchRuntime.OpenResult runtime =
        new SqlMaterializedScratchRuntime.OpenResult();
    StatusDetail detail = new StatusDetail(128);
    assertEquals(StatusCode.OK, SqlMaterializedScratchRuntime.create(
        spill, primary, DATABASE, poolResult.pool(), runtime, detail));
    SqlMaterializedScratchOwner.Result owner = new SqlMaterializedScratchOwner.Result();
    assertEquals(StatusCode.OK, runtime.runtime().openOwner(owner, detail));
    SqlMaterializedScratchStore.Result store = new SqlMaterializedScratchStore.Result();
    assertEquals(StatusCode.OK, owner.owner().openStore(store, detail));
    SqlMaterializedScratchFile.Result file = new SqlMaterializedScratchFile.Result();
    assertEquals(StatusCode.OK, store.store().open(
        SqlMaterializedScratchFileKind.INDEX, file, detail));
    SqlMaterializedPagedByteStream.Result stream = new SqlMaterializedPagedByteStream.Result();
    assertEquals(StatusCode.OK, SqlMaterializedPagedByteStream.createNew(
        owner.owner(), file.file(), SqlMaterializedScratchFileKind.INDEX,
        64, Long.BYTES, 0, stream, detail));
    SqlMaterializedPagedByteStream retained = stream.stream();
    SqlMaterializedPagedByteStream.AppendResult append =
        new SqlMaterializedPagedByteStream.AppendResult();
    assertEquals(StatusCode.INVALID_EXTERNAL_INPUT, retained.appendBytes(
        ByteBuffer.allocate(Long.BYTES), 2, append, detail));
    assertEquals(StatusCode.OK, retained.append(ByteBuffer.allocate(Long.BYTES), append, detail));
    assertEquals(StatusCode.OK, retained.close(detail));
    assertEquals(StatusCode.OK, retained.resetForReuse(stream, detail));
    assertSame(retained, stream.stream());
    assertEquals(0, retained.logicalLength());
    assertEquals(StatusCode.OK, retained.close(detail));
    assertEquals(StatusCode.OK, owner.owner().close(detail));
    assertEquals(StatusCode.OK, runtime.runtime().close(detail));
    assertEquals(StatusCode.OK, poolResult.pool().close());
  }

  @Test
  void failedHeaderVersionNeverPublishesAnOpenStream(@TempDir Path root) throws Exception {
    SqlMaterializedPagePoolResult poolResult = new SqlMaterializedPagePoolResult();
    assertEquals(StatusCode.OK, SqlMaterializedPagePool.create(64, 2, poolResult));
    SqlMaterializedPagePool pool = poolResult.pool();
    Path primary = Files.createDirectory(root.resolve("database"));
    Path spill = Files.createDirectory(root.resolve("spill"));
    SqlMaterializedScratchRuntime.OpenResult runtimeResult =
        new SqlMaterializedScratchRuntime.OpenResult();
    StatusDetail detail = new StatusDetail(256);
    assertEquals(StatusCode.OK, SqlMaterializedScratchRuntime.create(
        spill, primary, DATABASE, pool, runtimeResult, detail));
    SqlMaterializedScratchOwner.Result ownerResult = new SqlMaterializedScratchOwner.Result();
    assertEquals(StatusCode.OK, runtimeResult.runtime().openOwner(ownerResult, detail));
    SqlMaterializedScratchStore.Result storeResult = new SqlMaterializedScratchStore.Result();
    assertEquals(StatusCode.OK, ownerResult.owner().openStore(storeResult, detail));
    SqlMaterializedScratchFile.Result fileResult = new SqlMaterializedScratchFile.Result();
    assertEquals(StatusCode.OK, storeResult.store().open(
        SqlMaterializedScratchFileKind.INDEX, fileResult, detail));
    SqlMaterializedPagedByteStream.Result created = new SqlMaterializedPagedByteStream.Result();
    assertEquals(StatusCode.OK, SqlMaterializedPagedByteStream.createNew(
        ownerResult.owner(), fileResult.file(), SqlMaterializedScratchFileKind.INDEX,
        64, 48, 0, created, detail));
    fileResult.file().channel().write(ByteBuffer.wrap(new byte[] {0, 0, 0, 2}), 8);
    SqlMaterializedPagedByteStream.Result opened = new SqlMaterializedPagedByteStream.Result();
    assertEquals(StatusCode.CORRUPTION, SqlMaterializedPagedByteStream.openExisting(
        ownerResult.owner(), fileResult.file(), SqlMaterializedScratchFileKind.INDEX,
        64, opened, detail));
    assertNull(opened.stream());
    assertFalse(detail.asString().isEmpty());
    assertEquals(StatusCode.OK, ownerResult.owner().close(detail));
    assertEquals(StatusCode.OK, runtimeResult.runtime().close(detail));
    assertEquals(StatusCode.OK, pool.close());
  }
}
