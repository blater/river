package io.riverdb.inspect;

import io.riverdb.base.concurrent.FatalStateFence;
import io.riverdb.base.error.StatusCode;
import io.riverdb.base.id.DatabaseIncarnation;
import io.riverdb.format.control.ControlFile;
import io.riverdb.format.control.ControlFileCodec;
import io.riverdb.format.control.ControlFileDecodeResult;
import io.riverdb.format.page.PageCodec;
import io.riverdb.format.page.PageHeader;
import io.riverdb.format.wal.WalFileHeader;
import io.riverdb.format.wal.WalFileHeaderCodec;
import io.riverdb.format.wal.WalFileHeaderDecodeResult;
import io.riverdb.format.wal.WalRecordCodec;
import io.riverdb.format.wal.WalRecordHeader;
import io.riverdb.platform.file.DirectoryEntryType;
import io.riverdb.platform.file.DirectoryListResult;
import io.riverdb.platform.file.DirectoryOperationResult;
import io.riverdb.platform.file.DurableFile;
import io.riverdb.platform.file.FileSizeResult;
import io.riverdb.platform.file.IoResult;
import io.riverdb.platform.file.nio.NioDirectoryOpenResult;
import io.riverdb.platform.file.nio.NioDurableDirectory;
import io.riverdb.platform.file.nio.NioIoCounters;
import java.nio.ByteBuffer;
import java.nio.file.Path;
import java.util.zip.CRC32C;

/** Read-only, quiescent inspection of River control, WAL, and page files. */
public final class OfflineDatabaseInspector {
  private static final String CONTROL_FILE = "river.control";
  private static final String WAL_FILE = "river.wal";
  private static final String PAGE_FILE = "river.indexed.pages";
  private static final int MAXIMUM_DIRECTORY_ENTRIES = 256;

  private final DirectoryListResult entries =
      new DirectoryListResult(MAXIMUM_DIRECTORY_ENTRIES);
  private final DirectoryOperationResult operation = new DirectoryOperationResult();
  private final FileSizeResult fileSize = new FileSizeResult();
  private final IoResult io = new IoResult();
  private final ControlFileDecodeResult control = new ControlFileDecodeResult();
  private final WalFileHeaderDecodeResult walFileHeader = new WalFileHeaderDecodeResult();
  private final WalRecordHeader walRecordHeader = new WalRecordHeader();
  private final PageHeader pageHeader = new PageHeader();
  private final CRC32C checksum = new CRC32C();
  private final ByteBuffer controlBytes = ByteBuffer.allocateDirect(ControlFileCodec.RECORD_BYTES);
  private final ByteBuffer walBytes = ByteBuffer.allocateDirect(
      WalRecordCodec.HEADER_BYTES + WalRecordCodec.MAX_PAYLOAD_BYTES);
  private final ByteBuffer pageBytes = ByteBuffer.allocateDirect(PageCodec.PAGE_BYTES);

  public StatusCode inspect(Path directoryPath, DatabaseInspectionResult result) {
    if (directoryPath == null || result == null) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    result.reset();
    NioDirectoryOpenResult opened = new NioDirectoryOpenResult();
    StatusCode status = NioDurableDirectory.openExisting(
        directoryPath,
        new FatalStateFence(),
        new NioIoCounters(),
        2,
        opened);
    if (!status.isOk()) {
      return status;
    }
    NioDurableDirectory directory = opened.directory();
    status = inspectControl(directory, result);
    if (status.isOk()) {
      entries.reset();
      status = directory.list(entries);
    }
    for (int index = 0; status.isOk() && index < entries.size(); index++) {
      if (entries.type(index) != DirectoryEntryType.FILE) {
        result.addUnrecognizedEntry();
        continue;
      }
      String name = entries.name(index);
      if (CONTROL_FILE.equals(name)) {
        continue;
      }
      if (isPhysicalFile(name, WAL_FILE)) {
        status = inspectWal(directory, name, result);
      } else if (isPhysicalFile(name, PAGE_FILE)) {
        status = inspectPages(directory, name, result);
      } else {
        result.addUnrecognizedEntry();
      }
    }
    StatusCode close = directory.close();
    if (status.isOk()) {
      status = close;
    }
    if (status.isOk()) {
      result.complete();
    } else {
      result.reset();
    }
    return status;
  }

  private StatusCode inspectControl(
      NioDurableDirectory directory,
      DatabaseInspectionResult result) {
    StatusCode status = openFile(directory, CONTROL_FILE);
    DurableFile file = status.isOk() ? operation.file() : null;
    if (status.isOk()) {
      status = exactSize(file, ControlFileCodec.RECORD_BYTES);
    }
    if (status.isOk()) {
      controlBytes.clear();
      status = readExact(file, 0, controlBytes);
    }
    if (status.isOk()) {
      controlBytes.flip();
      status = ControlFileCodec.decode(controlBytes, control);
    }
    if (status.isOk()) {
      ControlFile decoded = control.controlFile();
      result.setDatabase(decoded.databaseIncarnation());
      result.addControlBytes(ControlFileCodec.RECORD_BYTES);
    }
    return closeFile(file, status);
  }

  private StatusCode inspectWal(
      NioDurableDirectory directory,
      String name,
      DatabaseInspectionResult result) {
    StatusCode status = openFile(directory, name);
    DurableFile file = status.isOk() ? operation.file() : null;
    long bytes = 0;
    if (status.isOk()) {
      status = file.size(fileSize);
      bytes = fileSize.sizeBytes();
      if (status.isOk() && bytes < WalFileHeaderCodec.HEADER_BYTES) {
        status = StatusCode.CORRUPTION;
      }
    }
    if (status.isOk()) {
      walBytes.clear();
      walBytes.limit(WalFileHeaderCodec.HEADER_BYTES);
      status = readExact(file, 0, walBytes);
    }
    if (status.isOk()) {
      walBytes.flip();
      status = WalFileHeaderCodec.decode(walBytes, walFileHeader);
    }
    WalFileHeader header = status.isOk() ? walFileHeader.header() : null;
    if (status.isOk() && !result.database().equals(header.databaseIncarnation())) {
      status = StatusCode.FENCED;
    }
    long namedGeneration = physicalGeneration(name, WAL_FILE);
    if (status.isOk()
        && (!WAL_FILE.equals(name)
            && (namedGeneration <= 0
                || namedGeneration != header.walGeneration().value()))) {
      status = StatusCode.CORRUPTION;
    }
    long offset = WalFileHeaderCodec.HEADER_BYTES;
    long expectedSequence = 1;
    long lastCommitSequence = 0;
    while (status.isOk() && offset < bytes) {
      if (bytes - offset < WalRecordCodec.HEADER_BYTES) {
        status = StatusCode.CORRUPTION;
        break;
      }
      walBytes.clear();
      walBytes.limit(WalRecordCodec.HEADER_BYTES);
      status = readExact(file, offset, walBytes);
      if (status.isOk()) {
        walBytes.flip();
        status = WalRecordCodec.decodeHeader(walBytes, walRecordHeader);
      }
      if (status.isOk()
          && (walRecordHeader.journalSequence() != expectedSequence
              || walRecordHeader.totalBytes() > bytes - offset)) {
        status = StatusCode.CORRUPTION;
      }
      if (status.isOk()) {
        walBytes.clear();
        walBytes.limit(walRecordHeader.totalBytes());
        status = readExact(file, offset, walBytes);
      }
      if (status.isOk()) {
        walBytes.flip();
        status = WalRecordCodec.validate(walBytes, walRecordHeader, checksum);
      }
      if (status.isOk()
          && !validDecision(walRecordHeader, lastCommitSequence)) {
        status = StatusCode.CORRUPTION;
      }
      if (status.isOk()) {
        result.addWalRecord(
            walRecordHeader.journalSequence(),
            walRecordHeader.commitSequence());
        offset += walRecordHeader.totalBytes();
        expectedSequence++;
        if (walRecordHeader.decisionCode() == 1) {
          lastCommitSequence = walRecordHeader.commitSequence();
        }
      }
    }
    if (status.isOk()) {
      result.addWalFile(bytes);
    }
    return closeFile(file, status);
  }

  private StatusCode inspectPages(
      NioDurableDirectory directory,
      String name,
      DatabaseInspectionResult result) {
    StatusCode status = openFile(directory, name);
    DurableFile file = status.isOk() ? operation.file() : null;
    long bytes = 0;
    if (status.isOk()) {
      status = file.size(fileSize);
      bytes = fileSize.sizeBytes();
      if (status.isOk()
          && (bytes == 0 || bytes % PageCodec.PAGE_BYTES != 0)) {
        status = StatusCode.CORRUPTION;
      }
    }
    long pageTotal = bytes / PageCodec.PAGE_BYTES;
    if (status.isOk() && pageTotal > Integer.MAX_VALUE) {
      status = StatusCode.RESOURCE_EXHAUSTED;
    }
    int pages = status.isOk() ? (int) pageTotal : 0;
    for (int index = 0; status.isOk() && index < pages; index++) {
      pageBytes.clear();
      status = readExact(file, (long) index * PageCodec.PAGE_BYTES, pageBytes);
      if (status.isOk()) {
        pageBytes.flip();
        status = PageCodec.validate(pageBytes, pageHeader, checksum);
      }
      if (status.isOk()
          && (pageHeader.databaseHigh() != result.database().high()
              || pageHeader.databaseLow() != result.database().low()
              || pageHeader.pageId() != index + 1L)) {
        status = StatusCode.CORRUPTION;
      }
      long namedGeneration = physicalGeneration(name, PAGE_FILE);
      if (status.isOk()
          && (!PAGE_FILE.equals(name)
              && (namedGeneration <= 0
                  || namedGeneration != pageHeader.walGeneration()))) {
        status = StatusCode.CORRUPTION;
      }
    }
    if (status.isOk()) {
      result.addPageFile(bytes, pages);
    }
    return closeFile(file, status);
  }

  private StatusCode openFile(NioDurableDirectory directory, String name) {
    operation.reset();
    StatusCode status = directory.reopen(name, operation);
    return status == StatusCode.CONFLICT ? StatusCode.CORRUPTION : status;
  }

  private StatusCode exactSize(DurableFile file, long expected) {
    StatusCode status = file.size(fileSize);
    return status.isOk() && fileSize.sizeBytes() != expected
        ? StatusCode.CORRUPTION : status;
  }

  private StatusCode readExact(DurableFile file, long offset, ByteBuffer target) {
    int expected = target.remaining();
    StatusCode status = file.read(offset, target, io);
    return status.isOk() && io.bytesTransferred() != expected
        ? StatusCode.CORRUPTION : status;
  }

  private static StatusCode closeFile(DurableFile file, StatusCode status) {
    if (file == null) {
      return status;
    }
    StatusCode close = file.close();
    return status.isOk() ? close : status;
  }

  private static boolean isPhysicalFile(String name, String base) {
    if (base.equals(name)) {
      return true;
    }
    String prefix = base + '.';
    if (!name.startsWith(prefix) || name.length() == prefix.length()) {
      return false;
    }
    for (int index = prefix.length(); index < name.length(); index++) {
      char value = name.charAt(index);
      if (value < '0' || value > '9') {
        return PAGE_FILE.equals(base) && name.startsWith(base + ".checkpoint.")
            && decimalSuffix(name, base.length() + ".checkpoint.".length());
      }
    }
    return true;
  }

  private static boolean decimalSuffix(String value, int start) {
    if (start >= value.length()) {
      return false;
    }
    for (int index = start; index < value.length(); index++) {
      char character = value.charAt(index);
      if (character < '0' || character > '9') {
        return false;
      }
    }
    return true;
  }

  private static long physicalGeneration(String name, String base) {
    int start;
    if (base.equals(name)) {
      return 0;
    }
    if (PAGE_FILE.equals(base) && name.startsWith(base + ".checkpoint.")) {
      start = base.length() + ".checkpoint.".length();
    } else {
      start = base.length() + 1;
    }
    long generation = 0;
    for (int index = start; index < name.length(); index++) {
      int digit = name.charAt(index) - '0';
      if (generation > (Long.MAX_VALUE - digit) / 10) {
        return -1;
      }
      generation = generation * 10 + digit;
    }
    return generation;
  }

  private static boolean validDecision(WalRecordHeader header, long lastCommitSequence) {
    return switch (header.decisionCode()) {
      case 0 -> header.commitSequence() == 0;
      case 1 -> header.transactionId() > 0
          && header.commitSequence() > lastCommitSequence;
      case 2 -> header.transactionId() > 0 && header.commitSequence() == 0;
      default -> false;
    };
  }
}
