package io.riverdb.engine.page;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.riverdb.base.concurrent.FatalStateFence;
import io.riverdb.base.error.StatusCode;
import io.riverdb.base.id.DatabaseIncarnation;
import io.riverdb.base.id.WalGeneration;
import io.riverdb.format.page.PageCodec;
import io.riverdb.format.page.PageHeader;
import io.riverdb.platform.file.DirectoryOperationResult;
import io.riverdb.platform.file.DurableFile;
import io.riverdb.platform.file.ForceMode;
import io.riverdb.platform.file.IoResult;
import io.riverdb.platform.file.nio.NioDirectoryOpenResult;
import io.riverdb.platform.file.nio.NioDurableDirectory;
import io.riverdb.platform.file.nio.NioIoCounters;
import io.riverdb.wal.local.LocalWal;
import io.riverdb.wal.local.LocalWalOpenResult;
import java.nio.ByteBuffer;
import java.nio.file.Path;
import java.util.zip.CRC32C;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class SinglePageStoreTest {
  private static final DatabaseIncarnation DATABASE = DatabaseIncarnation.of(211, 223);
  private static final WalGeneration GENERATION = WalGeneration.of(1);

  @Test
  void logsBeforeFlushAndReopensMaterializedPage(@TempDir Path root) {
    NioDurableDirectory directory = openDirectory(root);
    LocalWal wal = openWal(directory);
    SinglePageStore store = createStore(directory, wal);
    byte[] expected = {6, 2, 6, 4, 3, 3, 8};
    PageUpdate update = new PageUpdate();
    assertEquals(StatusCode.OK, store.beginUpdate(expected.length, update));
    update.writablePayload().put(expected);
    assertEquals(StatusCode.OK, store.commit(update));
    assertTrue(store.isDirty());

    DirectoryOperationResult rawResult = new DirectoryOperationResult();
    assertEquals(StatusCode.OK, directory.reopen(SinglePageStore.FILE_NAME, rawResult));
    DurableFile raw = rawResult.file();
    ByteBuffer onDisk = ByteBuffer.allocate(PageCodec.PAGE_BYTES);
    IoResult io = new IoResult();
    assertEquals(StatusCode.OK, raw.read(0, onDisk, io));
    onDisk.flip();
    PageHeader diskHeader = new PageHeader();
    assertEquals(StatusCode.OK, PageCodec.validate(onDisk, diskHeader, new CRC32C()));
    assertEquals(0, diskHeader.payloadBytes());
    assertEquals(StatusCode.OK, raw.close());

    assertEquals(StatusCode.OK, store.flush());
    assertFalse(store.isDirty());
    assertEquals(2L * PageCodec.PAGE_BYTES, store.copiedPayloadBytes());
    assertEquals(StatusCode.OK, store.close());
    assertEquals(StatusCode.OK, wal.close());
    assertEquals(StatusCode.OK, directory.close());

    directory = openDirectory(root);
    wal = openWal(directory);
    SinglePageStoreOpenResult opened = new SinglePageStoreOpenResult();
    assertEquals(
        StatusCode.OK,
        SinglePageStore.open(directory, wal, DATABASE, GENERATION, opened));
    PageReadResult read = new PageReadResult();
    assertEquals(StatusCode.OK, opened.store().read(read));
    byte[] actual = new byte[expected.length];
    read.payload().get(actual);
    assertArrayEquals(expected, actual);
    assertEquals(StatusCode.OK, opened.store().close());
    assertEquals(StatusCode.OK, wal.close());
    assertEquals(StatusCode.OK, directory.close());
  }

  @Test
  void recoversCorruptPageFromForcedWalAfterImage(@TempDir Path root) {
    NioDurableDirectory directory = openDirectory(root);
    LocalWal wal = openWal(directory);
    SinglePageStore store = createStore(directory, wal);
    byte[] expected = {1, 4, 1, 4, 2, 1};
    PageUpdate update = new PageUpdate();
    assertEquals(StatusCode.OK, store.beginUpdate(expected.length, update));
    update.writablePayload().put(expected);
    assertEquals(StatusCode.OK, store.commit(update));
    assertEquals(StatusCode.OK, store.flush());
    assertEquals(StatusCode.OK, store.close());

    DirectoryOperationResult rawResult = new DirectoryOperationResult();
    assertEquals(StatusCode.OK, directory.reopen(SinglePageStore.FILE_NAME, rawResult));
    DurableFile raw = rawResult.file();
    IoResult io = new IoResult();
    assertEquals(
        StatusCode.OK,
        raw.write(PageCodec.HEADER_BYTES + 1, ByteBuffer.wrap(new byte[] {99}), io));
    assertEquals(StatusCode.OK, raw.force(ForceMode.CONTENT_AND_METADATA));
    assertEquals(StatusCode.OK, raw.close());

    SinglePageStoreOpenResult recovered = new SinglePageStoreOpenResult();
    assertEquals(
        StatusCode.OK,
        SinglePageStore.open(directory, wal, DATABASE, GENERATION, recovered));
    assertEquals(PageCodec.PAGE_BYTES, recovered.store().copiedPayloadBytes());
    PageReadResult read = new PageReadResult();
    assertEquals(StatusCode.OK, recovered.store().read(read));
    byte[] actual = new byte[expected.length];
    read.payload().get(actual);
    assertArrayEquals(expected, actual);
    assertEquals(StatusCode.OK, recovered.store().close());

    SinglePageStoreOpenResult verified = new SinglePageStoreOpenResult();
    assertEquals(
        StatusCode.OK,
        SinglePageStore.open(directory, wal, DATABASE, GENERATION, verified));
    assertEquals(0, verified.store().copiedPayloadBytes());
    assertEquals(StatusCode.OK, verified.store().close());

    DirectoryOperationResult remove = new DirectoryOperationResult();
    assertEquals(StatusCode.OK, directory.remove(SinglePageStore.FILE_NAME, remove));
    assertEquals(StatusCode.OK, directory.force(remove));
    SinglePageStoreOpenResult recreated = new SinglePageStoreOpenResult();
    assertEquals(
        StatusCode.OK,
        SinglePageStore.open(directory, wal, DATABASE, GENERATION, recreated));
    assertEquals(PageCodec.PAGE_BYTES, recreated.store().copiedPayloadBytes());
    assertEquals(StatusCode.OK, recreated.store().read(read));
    actual = new byte[expected.length];
    read.payload().get(actual);
    assertArrayEquals(expected, actual);
    assertEquals(StatusCode.OK, recreated.store().close());
    assertEquals(StatusCode.OK, wal.close());
    assertEquals(StatusCode.OK, directory.close());
  }

  private static NioDurableDirectory openDirectory(Path root) {
    NioDirectoryOpenResult result = new NioDirectoryOpenResult();
    assertEquals(
        StatusCode.OK,
        NioDurableDirectory.openExisting(
            root,
            new FatalStateFence(),
            new NioIoCounters(),
            8,
            result));
    return result.directory();
  }

  private static LocalWal openWal(NioDurableDirectory directory) {
    LocalWalOpenResult result = new LocalWalOpenResult();
    assertEquals(StatusCode.OK, LocalWal.open(directory, DATABASE, GENERATION, result));
    return result.wal();
  }

  private static SinglePageStore createStore(
      NioDurableDirectory directory,
      LocalWal wal) {
    SinglePageStoreOpenResult result = new SinglePageStoreOpenResult();
    assertEquals(
        StatusCode.OK,
        SinglePageStore.create(directory, wal, DATABASE, GENERATION, result));
    return result.store();
  }
}
