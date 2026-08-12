package io.riverdb.backup;

import io.riverdb.base.concurrent.FatalStateFence;
import io.riverdb.base.error.StatusCode;
import io.riverdb.base.id.DatabaseIncarnation;
import io.riverdb.base.id.WalGeneration;
import io.riverdb.format.control.ControlFile;
import io.riverdb.format.control.ControlFileCodec;
import io.riverdb.format.control.ControlFileDecodeResult;
import io.riverdb.platform.file.DirectoryEntryType;
import io.riverdb.platform.file.DirectoryListResult;
import io.riverdb.platform.file.DirectoryOperationResult;
import io.riverdb.platform.file.DurableFile;
import io.riverdb.platform.file.FileSizeResult;
import io.riverdb.platform.file.ForceMode;
import io.riverdb.platform.file.IoResult;
import io.riverdb.platform.file.nio.NioDirectoryOpenResult;
import io.riverdb.platform.file.nio.NioDurableDirectory;
import io.riverdb.platform.file.nio.NioIoCounters;
import java.nio.ByteBuffer;
import java.nio.file.Path;
import java.security.DigestException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/** Manifest-last backup and no-clobber restore for a quiescent River database directory. */
public final class OfflineDatabaseBackup {
  public static final String MANIFEST_FILE_NAME = "river.backup.manifest";
  private static final long MANIFEST_MAGIC = 0x5249564552424b50L; // RIVERBKP
  private static final int MANIFEST_VERSION = 1;
  private static final int MAXIMUM_FILES = 256;
  private static final int MAXIMUM_NAME_BYTES = 80;
  private static final int DIGEST_BYTES = 32;
  private static final int HEADER_BYTES = 48;
  private static final int ENTRY_BYTES = 128;
  private static final int MAXIMUM_MANIFEST_BYTES =
      HEADER_BYTES + MAXIMUM_FILES * ENTRY_BYTES + DIGEST_BYTES;
  private static final int COPY_BUFFER_BYTES = 64 * 1024;
  private static final String CONTROL_FILE_NAME = "river.control";

  private final DirectoryListResult entries = new DirectoryListResult(MAXIMUM_FILES + 1);
  private final DirectoryOperationResult sourceOperation = new DirectoryOperationResult();
  private final DirectoryOperationResult targetOperation = new DirectoryOperationResult();
  private final FileSizeResult fileSize = new FileSizeResult();
  private final IoResult sourceIo = new IoResult();
  private final IoResult targetIo = new IoResult();
  private final ControlFileDecodeResult controlResult = new ControlFileDecodeResult();
  private final String[] fileNames = new String[MAXIMUM_FILES];
  private final long[] fileSizes = new long[MAXIMUM_FILES];
  private final byte[] fileDigests = new byte[MAXIMUM_FILES * DIGEST_BYTES];
  private final byte[] digestOutput = new byte[DIGEST_BYTES];
  private final ByteBuffer copyBuffer = ByteBuffer.allocateDirect(COPY_BUFFER_BYTES);
  private final ByteBuffer controlBuffer = ByteBuffer.allocateDirect(ControlFileCodec.RECORD_BYTES);
  private final ByteBuffer manifestBuffer = ByteBuffer.allocateDirect(MAXIMUM_MANIFEST_BYTES);
  private final MessageDigest digest;

  private int fileCount;
  private long totalBytes;
  private DatabaseIncarnation database;
  private WalGeneration walGeneration;

  public OfflineDatabaseBackup() {
    MessageDigest available = null;
    try {
      available = MessageDigest.getInstance("SHA-256");
    } catch (NoSuchAlgorithmException unavailable) {
      // SHA-256 is mandatory in every supported JDK; methods fail closed if absent.
    }
    digest = available;
  }

  /** Copies a closed database directory into an existing empty backup directory. */
  public StatusCode create(Path sourcePath, Path backupPath, BackupResult result) {
    return transfer(sourcePath, backupPath, result, false);
  }

  /** Restores a complete backup into an existing empty database directory. */
  public StatusCode restore(Path backupPath, Path destinationPath, BackupResult result) {
    return transfer(backupPath, destinationPath, result, true);
  }

  private StatusCode transfer(
      Path sourcePath,
      Path targetPath,
      BackupResult result,
      boolean restoring) {
    if (sourcePath == null || targetPath == null || result == null || digest == null) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    result.reset();
    resetState();
    NioDirectoryOpenResult sourceResult = new NioDirectoryOpenResult();
    NioDirectoryOpenResult targetResult = new NioDirectoryOpenResult();
    StatusCode status = openDirectory(sourcePath, sourceResult);
    if (status.isOk()) {
      status = openDirectory(targetPath, targetResult);
    }
    NioDurableDirectory source = sourceResult.directory();
    NioDurableDirectory target = targetResult.directory();
    if (status.isOk() && source.root().equals(target.root())) {
      status = StatusCode.CONFLICT;
    }
    if (status.isOk()) {
      status = requireEmpty(target);
    }
    if (status.isOk()) {
      status = restoring ? readManifest(source) : collectDatabaseFiles(source);
    }
    if (status.isOk() && restoring) {
      status = validateBackupEntries(source);
    }
    for (int index = 0; status.isOk() && index < fileCount; index++) {
      status = copyFile(source, target, index, restoring);
    }
    if (status.isOk()) {
      status = target.force(targetOperation);
    }
    if (status.isOk() && !restoring) {
      status = writeManifest(target);
    }
    if (status.isOk()) {
      result.complete(database, walGeneration, fileCount, totalBytes);
    }
    status = closeDirectories(source, target, status);
    if (!status.isOk()) {
      result.reset();
    }
    return status;
  }

  private static StatusCode openDirectory(Path path, NioDirectoryOpenResult result) {
    return NioDurableDirectory.openExisting(
        path, new FatalStateFence(), new NioIoCounters(), 4, result);
  }

  private StatusCode requireEmpty(NioDurableDirectory directory) {
    entries.reset();
    StatusCode status = directory.list(entries);
    return status.isOk() && entries.size() != 0 ? StatusCode.CONFLICT : status;
  }

  private StatusCode collectDatabaseFiles(NioDurableDirectory source) {
    entries.reset();
    StatusCode status = source.list(entries);
    if (!status.isOk()) {
      return status;
    }
    if (entries.size() == 0 || entries.size() > MAXIMUM_FILES) {
      return StatusCode.CORRUPTION;
    }
    boolean controlFound = false;
    for (int index = 0; index < entries.size(); index++) {
      String name = entries.name(index);
      if (entries.type(index) != DirectoryEntryType.FILE
          || !validName(name)
          || MANIFEST_FILE_NAME.equals(name)) {
        return StatusCode.CORRUPTION;
      }
      fileNames[fileCount++] = name;
      controlFound |= CONTROL_FILE_NAME.equals(name);
    }
    if (!controlFound) {
      return StatusCode.CORRUPTION;
    }
    sortNames();
    return readControl(source);
  }

  private StatusCode readControl(NioDurableDirectory source) {
    StatusCode status = source.reopen(CONTROL_FILE_NAME, sourceOperation);
    DurableFile file = status.isOk() ? sourceOperation.file() : null;
    if (status.isOk()) {
      status = file.size(fileSize);
    }
    if (status.isOk() && fileSize.sizeBytes() != ControlFileCodec.RECORD_BYTES) {
      status = StatusCode.CORRUPTION;
    }
    if (status.isOk()) {
      controlBuffer.clear();
      status = readExact(file, controlBuffer, 0, sourceIo);
    }
    if (status.isOk()) {
      controlBuffer.flip();
      status = ControlFileCodec.decode(controlBuffer, controlResult);
    }
    StatusCode close = file == null ? StatusCode.OK : file.close();
    if (status.isOk()) {
      status = close;
    }
    if (status.isOk()) {
      ControlFile control = controlResult.controlFile();
      database = control.databaseIncarnation();
      walGeneration = control.walGeneration();
    }
    return status;
  }

  private StatusCode copyFile(
      NioDurableDirectory source,
      NioDurableDirectory target,
      int index,
      boolean verifyExpectedDigest) {
    String name = fileNames[index];
    StatusCode status = source.reopen(name, sourceOperation);
    DurableFile input = status.isOk() ? sourceOperation.file() : null;
    if (status.isOk()) {
      status = input.size(fileSize);
      if (fileSize.sizeBytes() < 0) {
        status = StatusCode.CORRUPTION;
      }
    }
    long copiedBytes = fileSize.sizeBytes();
    if (status.isOk() && verifyExpectedDigest && copiedBytes != fileSizes[index]) {
      status = StatusCode.CORRUPTION;
    }
    if (status.isOk() && !verifyExpectedDigest) {
      fileSizes[index] = copiedBytes;
    }
    if (status.isOk()) {
      status = target.createFile(name, targetOperation);
    }
    DurableFile output = status.isOk() ? targetOperation.file() : null;
    digest.reset();
    long position = 0;
    while (status.isOk() && position < copiedBytes) {
      int bytes = (int) Math.min(copyBuffer.capacity(), copiedBytes - position);
      copyBuffer.clear();
      copyBuffer.limit(bytes);
      status = readExact(input, copyBuffer, position, sourceIo);
      if (status.isOk()) {
        copyBuffer.flip();
        digest.update(copyBuffer);
        copyBuffer.position(0);
        status = writeExact(output, copyBuffer, position, targetIo);
      }
      position += status.isOk() ? bytes : 0;
    }
    if (status.isOk()) {
      status = finishDigest(digestOutput, 0);
    }
    if (status.isOk() && verifyExpectedDigest
        && !sameDigest(digestOutput, 0, fileDigests, index * DIGEST_BYTES)) {
      status = StatusCode.CORRUPTION;
    }
    if (status.isOk() && !verifyExpectedDigest) {
      System.arraycopy(digestOutput, 0, fileDigests, index * DIGEST_BYTES, DIGEST_BYTES);
      if (Long.MAX_VALUE - totalBytes < copiedBytes) {
        status = StatusCode.RESOURCE_EXHAUSTED;
      } else {
        totalBytes += copiedBytes;
      }
    }
    if (status.isOk()) {
      status = output.force(ForceMode.CONTENT_AND_METADATA);
    }
    StatusCode inputClose = input == null ? StatusCode.OK : input.close();
    StatusCode outputClose = output == null ? StatusCode.OK : output.close();
    if (status.isOk()) {
      status = inputClose;
    }
    if (status.isOk()) {
      status = outputClose;
    }
    return status;
  }

  private StatusCode writeManifest(NioDurableDirectory target) {
    int bytes = HEADER_BYTES + fileCount * ENTRY_BYTES + DIGEST_BYTES;
    manifestBuffer.clear();
    manifestBuffer.limit(bytes);
    zero(manifestBuffer, bytes);
    manifestBuffer.putLong(0, MANIFEST_MAGIC);
    manifestBuffer.putInt(8, MANIFEST_VERSION);
    manifestBuffer.putInt(12, bytes);
    manifestBuffer.putLong(16, database.high());
    manifestBuffer.putLong(24, database.low());
    manifestBuffer.putLong(32, walGeneration.value());
    manifestBuffer.putInt(40, fileCount);
    for (int index = 0; index < fileCount; index++) {
      int offset = HEADER_BYTES + index * ENTRY_BYTES;
      String name = fileNames[index];
      manifestBuffer.putInt(offset, name.length());
      manifestBuffer.putLong(offset + 8, fileSizes[index]);
      for (int digestIndex = 0; digestIndex < DIGEST_BYTES; digestIndex++) {
        manifestBuffer.put(offset + 16 + digestIndex,
            fileDigests[index * DIGEST_BYTES + digestIndex]);
      }
      for (int character = 0; character < name.length(); character++) {
        manifestBuffer.put(offset + 48 + character, (byte) name.charAt(character));
      }
    }
    digest.reset();
    manifestBuffer.position(0);
    manifestBuffer.limit(bytes - DIGEST_BYTES);
    digest.update(manifestBuffer);
    StatusCode status = finishDigest(digestOutput, 0);
    manifestBuffer.limit(bytes);
    for (int index = 0; status.isOk() && index < DIGEST_BYTES; index++) {
      manifestBuffer.put(bytes - DIGEST_BYTES + index, digestOutput[index]);
    }
    manifestBuffer.position(0);
    manifestBuffer.limit(bytes);
    if (status.isOk()) {
      status = target.createFile(MANIFEST_FILE_NAME, targetOperation);
    }
    DurableFile file = status.isOk() ? targetOperation.file() : null;
    if (status.isOk()) {
      status = writeExact(file, manifestBuffer, 0, targetIo);
    }
    if (status.isOk()) {
      status = file.force(ForceMode.CONTENT_AND_METADATA);
    }
    StatusCode close = file == null ? StatusCode.OK : file.close();
    if (status.isOk()) {
      status = close;
    }
    if (status.isOk()) {
      status = target.force(targetOperation);
    }
    return status;
  }

  private StatusCode readManifest(NioDurableDirectory source) {
    StatusCode status = source.reopen(MANIFEST_FILE_NAME, sourceOperation);
    DurableFile file = status.isOk() ? sourceOperation.file() : null;
    if (status.isOk()) {
      status = file.size(fileSize);
    }
    long bytes = fileSize.sizeBytes();
    if (status.isOk()
        && (bytes < HEADER_BYTES + DIGEST_BYTES || bytes > MAXIMUM_MANIFEST_BYTES)) {
      status = StatusCode.CORRUPTION;
    }
    if (status.isOk()) {
      manifestBuffer.clear();
      manifestBuffer.limit((int) bytes);
      status = readExact(file, manifestBuffer, 0, sourceIo);
    }
    StatusCode close = file == null ? StatusCode.OK : file.close();
    if (status.isOk()) {
      status = close;
    }
    if (!status.isOk()) {
      return status;
    }
    manifestBuffer.flip();
    int declaredBytes = manifestBuffer.getInt(12);
    fileCount = manifestBuffer.getInt(40);
    if (manifestBuffer.getLong(0) != MANIFEST_MAGIC
        || manifestBuffer.getInt(8) != MANIFEST_VERSION
        || declaredBytes != bytes
        || fileCount <= 0
        || fileCount > MAXIMUM_FILES
        || bytes != HEADER_BYTES + (long) fileCount * ENTRY_BYTES + DIGEST_BYTES) {
      return StatusCode.CORRUPTION;
    }
    digest.reset();
    manifestBuffer.position(0);
    manifestBuffer.limit((int) bytes - DIGEST_BYTES);
    digest.update(manifestBuffer);
    status = finishDigest(digestOutput, 0);
    manifestBuffer.limit((int) bytes);
    if (!status.isOk()
        || !sameDigest(
            digestOutput,
            0,
            manifestBuffer,
            (int) bytes - DIGEST_BYTES)) {
      return StatusCode.CORRUPTION;
    }
    long databaseHigh = manifestBuffer.getLong(16);
    long databaseLow = manifestBuffer.getLong(24);
    long generation = manifestBuffer.getLong(32);
    if ((databaseHigh == 0 && databaseLow == 0) || generation <= 0) {
      return StatusCode.CORRUPTION;
    }
    database = DatabaseIncarnation.of(databaseHigh, databaseLow);
    walGeneration = WalGeneration.of(generation);
    totalBytes = 0;
    String previous = null;
    for (int index = 0; index < fileCount; index++) {
      int offset = HEADER_BYTES + index * ENTRY_BYTES;
      int nameLength = manifestBuffer.getInt(offset);
      long size = manifestBuffer.getLong(offset + 8);
      if (nameLength <= 0 || nameLength > MAXIMUM_NAME_BYTES || size < 0) {
        return StatusCode.CORRUPTION;
      }
      StringBuilder name = new StringBuilder(nameLength);
      for (int character = 0; character < nameLength; character++) {
        int value = manifestBuffer.get(offset + 48 + character) & 0xff;
        if (value < 33 || value > 126) {
          return StatusCode.CORRUPTION;
        }
        name.append((char) value);
      }
      String decodedName = name.toString();
      if (!validName(decodedName)
          || MANIFEST_FILE_NAME.equals(decodedName)
          || previous != null && previous.compareTo(decodedName) >= 0) {
        return StatusCode.CORRUPTION;
      }
      previous = decodedName;
      fileNames[index] = decodedName;
      fileSizes[index] = size;
      if (Long.MAX_VALUE - totalBytes < size) {
        return StatusCode.CORRUPTION;
      }
      totalBytes += size;
      for (int digestIndex = 0; digestIndex < DIGEST_BYTES; digestIndex++) {
        fileDigests[index * DIGEST_BYTES + digestIndex] =
            manifestBuffer.get(offset + 16 + digestIndex);
      }
    }
    DatabaseIncarnation manifestDatabase = database;
    WalGeneration manifestGeneration = walGeneration;
    status = readControl(source);
    return status.isOk()
            && (!manifestDatabase.equals(database)
                || !manifestGeneration.equals(walGeneration))
        ? StatusCode.CORRUPTION : status;
  }

  private StatusCode validateBackupEntries(NioDurableDirectory source) {
    entries.reset();
    StatusCode status = source.list(entries);
    if (!status.isOk() || entries.size() != fileCount + 1) {
      return status.isOk() ? StatusCode.CORRUPTION : status;
    }
    for (int index = 0; index < entries.size(); index++) {
      if (entries.type(index) != DirectoryEntryType.FILE) {
        return StatusCode.CORRUPTION;
      }
      String name = entries.name(index);
      if (MANIFEST_FILE_NAME.equals(name)) {
        continue;
      }
      if (findFile(name) < 0) {
        return StatusCode.CORRUPTION;
      }
    }
    return findFile(CONTROL_FILE_NAME) < 0 ? StatusCode.CORRUPTION : StatusCode.OK;
  }

  private int findFile(String name) {
    int lower = 0;
    int upper = fileCount;
    while (lower < upper) {
      int middle = (lower + upper) >>> 1;
      int comparison = fileNames[middle].compareTo(name);
      if (comparison < 0) {
        lower = middle + 1;
      } else if (comparison > 0) {
        upper = middle;
      } else {
        return middle;
      }
    }
    return -1;
  }

  private static StatusCode readExact(
      DurableFile file,
      ByteBuffer target,
      long position,
      IoResult io) {
    int expected = target.remaining();
    StatusCode status = file.read(position, target, io);
    return status.isOk() && io.bytesTransferred() != expected
        ? StatusCode.CORRUPTION : status;
  }

  private static StatusCode writeExact(
      DurableFile file,
      ByteBuffer source,
      long position,
      IoResult io) {
    int expected = source.remaining();
    StatusCode status = file.write(position, source, io);
    return status.isOk() && io.bytesTransferred() != expected
        ? StatusCode.IO_FAILURE : status;
  }

  private StatusCode finishDigest(byte[] output, int offset) {
    try {
      int bytes = digest.digest(output, offset, DIGEST_BYTES);
      return bytes == DIGEST_BYTES ? StatusCode.OK : StatusCode.INVARIANT_BROKEN;
    } catch (DigestException failure) {
      return StatusCode.INVARIANT_BROKEN;
    }
  }

  private static boolean sameDigest(
      byte[] left,
      int leftOffset,
      byte[] right,
      int rightOffset) {
    int difference = 0;
    for (int index = 0; index < DIGEST_BYTES; index++) {
      difference |= left[leftOffset + index] ^ right[rightOffset + index];
    }
    return difference == 0;
  }

  private static boolean sameDigest(
      byte[] left,
      int leftOffset,
      ByteBuffer right,
      int rightOffset) {
    int difference = 0;
    for (int index = 0; index < DIGEST_BYTES; index++) {
      difference |= left[leftOffset + index] ^ right.get(rightOffset + index);
    }
    return difference == 0;
  }

  private void sortNames() {
    for (int index = 1; index < fileCount; index++) {
      String value = fileNames[index];
      int destination = index;
      while (destination > 0
          && fileNames[destination - 1].compareTo(value) > 0) {
        fileNames[destination] = fileNames[destination - 1];
        destination--;
      }
      fileNames[destination] = value;
    }
  }

  private static boolean validName(String name) {
    if (name == null || name.isEmpty() || name.length() > MAXIMUM_NAME_BYTES) {
      return false;
    }
    for (int index = 0; index < name.length(); index++) {
      char character = name.charAt(index);
      if (character < 33 || character > 126 || character == '/' || character == '\\') {
        return false;
      }
    }
    return true;
  }

  private static void zero(ByteBuffer buffer, int bytes) {
    for (int index = 0; index < bytes; index++) {
      buffer.put(index, (byte) 0);
    }
  }

  private void resetState() {
    for (int index = 0; index < fileCount; index++) {
      fileNames[index] = null;
      fileSizes[index] = 0;
    }
    fileCount = 0;
    totalBytes = 0;
    database = null;
    walGeneration = null;
  }

  private static StatusCode closeDirectories(
      NioDurableDirectory source,
      NioDurableDirectory target,
      StatusCode status) {
    StatusCode sourceClose = source == null ? StatusCode.OK : source.close();
    StatusCode targetClose = target == null ? StatusCode.OK : target.close();
    if (status.isOk()) {
      status = sourceClose;
    }
    return status.isOk() ? targetClose : status;
  }
}
