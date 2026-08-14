package io.riverdb.backup;

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
import io.riverdb.platform.file.nio.NioDurableDirectory;
import java.nio.ByteBuffer;
import java.security.DigestException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/** Owns the bounded backup inventory and its manifest representation. */
final class OfflineBackupCatalog {
  static final String MANIFEST_FILE_NAME = "river.backup.manifest";
  private static final String CONTROL_FILE_NAME = "river.control";
  private static final long MANIFEST_MAGIC = 0x5249564552424b50L; // RIVERBKP
  private static final int MANIFEST_VERSION = 1;
  private static final int MAXIMUM_FILES = 256;
  private static final int MAXIMUM_NAME_BYTES = 80;
  private static final int DIGEST_BYTES = 32;
  private static final int HEADER_BYTES = 48;
  private static final int ENTRY_BYTES = 128;
  private static final int MAXIMUM_MANIFEST_BYTES =
      HEADER_BYTES + MAXIMUM_FILES * ENTRY_BYTES + DIGEST_BYTES;

  private final DirectoryListResult entries =
      new DirectoryListResult(MAXIMUM_FILES + 1);
  private final DirectoryOperationResult operation =
      new DirectoryOperationResult();
  private final FileSizeResult fileSize = new FileSizeResult();
  private final IoResult io = new IoResult();
  private final ControlFileDecodeResult controlResult =
      new ControlFileDecodeResult();
  private final String[] fileNames = new String[MAXIMUM_FILES];
  private final long[] fileSizes = new long[MAXIMUM_FILES];
  private final byte[] fileDigests = new byte[MAXIMUM_FILES * DIGEST_BYTES];
  private final byte[] digestOutput = new byte[DIGEST_BYTES];
  private final ByteBuffer controlBuffer =
      ByteBuffer.allocateDirect(ControlFileCodec.RECORD_BYTES);
  private final ByteBuffer manifestBuffer =
      ByteBuffer.allocateDirect(MAXIMUM_MANIFEST_BYTES);
  private final MessageDigest digest;

  private int fileCount;
  private long totalBytes;
  private DatabaseIncarnation database;
  private WalGeneration walGeneration;
  private long manifestBytes;

  OfflineBackupCatalog() {
    digest = sha256();
  }

  boolean isAvailable() {
    return digest != null;
  }

  void reset() {
    for (int index = 0; index < fileCount; index++) {
      fileNames[index] = null;
      fileSizes[index] = 0;
    }
    fileCount = 0;
    totalBytes = 0;
    database = null;
    walGeneration = null;
  }

  StatusCode collectDatabaseFiles(NioDurableDirectory source) {
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
      if (!isPayloadEntry(index, name)) {
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

  StatusCode readManifest(NioDurableDirectory source) {
    StatusCode status = readManifestBytes(source);
    if (!status.isOk()) {
      return status;
    }
    status = validateHeader(manifestBytes);
    if (status.isOk()) {
      status = validateManifestDigest((int) manifestBytes);
    }
    if (status.isOk()) {
      status = decodeIdentity();
    }
    if (status.isOk()) {
      status = decodeEntries();
    }
    if (!status.isOk()) {
      return status;
    }
    DatabaseIncarnation manifestDatabase = database;
    WalGeneration manifestGeneration = walGeneration;
    status = readControl(source);
    return status.isOk()
            && (!manifestDatabase.equals(database)
                || !manifestGeneration.equals(walGeneration))
        ? StatusCode.CORRUPTION : status;
  }

  StatusCode writeManifest(NioDurableDirectory target) {
    int bytes = HEADER_BYTES + fileCount * ENTRY_BYTES + DIGEST_BYTES;
    encodeManifest(bytes);
    StatusCode status = appendManifestDigest(bytes);
    if (!status.isOk()) {
      return status;
    }
    status = target.createFile(MANIFEST_FILE_NAME, operation);
    DurableFile file = status.isOk() ? operation.file() : null;
    if (status.isOk()) {
      status = OfflineBackupIo.writeExact(file, manifestBuffer, 0, io);
    }
    if (status.isOk()) {
      status = file.force(ForceMode.CONTENT_AND_METADATA);
    }
    StatusCode close = file == null ? StatusCode.OK : file.close();
    if (status.isOk()) {
      status = close;
    }
    return status.isOk() ? target.force(operation) : status;
  }

  StatusCode validateBackupEntries(NioDurableDirectory source) {
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
      if (!MANIFEST_FILE_NAME.equals(name) && findFile(name) < 0) {
        return StatusCode.CORRUPTION;
      }
    }
    return findFile(CONTROL_FILE_NAME) < 0
        ? StatusCode.CORRUPTION : StatusCode.OK;
  }

  StatusCode validateFileSize(int index, long bytes, boolean verifyExpected) {
    if (bytes < 0 || verifyExpected && bytes != fileSizes[index]) {
      return StatusCode.CORRUPTION;
    }
    if (!verifyExpected) {
      fileSizes[index] = bytes;
    }
    return StatusCode.OK;
  }

  StatusCode acceptDigest(
      int index, long bytes, byte[] value, boolean verifyExpected) {
    int offset = index * DIGEST_BYTES;
    if (verifyExpected) {
      return sameDigest(value, 0, fileDigests, offset)
          ? StatusCode.OK : StatusCode.CORRUPTION;
    }
    if (Long.MAX_VALUE - totalBytes < bytes) {
      return StatusCode.RESOURCE_EXHAUSTED;
    }
    System.arraycopy(value, 0, fileDigests, offset, DIGEST_BYTES);
    totalBytes += bytes;
    return StatusCode.OK;
  }

  String fileName(int index) {
    return fileNames[index];
  }

  int fileCount() {
    return fileCount;
  }

  long totalBytes() {
    return totalBytes;
  }

  DatabaseIncarnation database() {
    return database;
  }

  WalGeneration walGeneration() {
    return walGeneration;
  }

  private boolean isPayloadEntry(int index, String name) {
    return entries.type(index) == DirectoryEntryType.FILE
        && validName(name) && !MANIFEST_FILE_NAME.equals(name);
  }

  private StatusCode readControl(NioDurableDirectory source) {
    StatusCode status = source.reopen(CONTROL_FILE_NAME, operation);
    DurableFile file = status.isOk() ? operation.file() : null;
    if (status.isOk()) {
      status = file.size(fileSize);
    }
    if (status.isOk() && fileSize.sizeBytes() != ControlFileCodec.RECORD_BYTES) {
      status = StatusCode.CORRUPTION;
    }
    if (status.isOk()) {
      controlBuffer.clear();
      status = OfflineBackupIo.readExact(file, controlBuffer, 0, io);
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

  private StatusCode readManifestBytes(NioDurableDirectory source) {
    StatusCode status = source.reopen(MANIFEST_FILE_NAME, operation);
    DurableFile file = status.isOk() ? operation.file() : null;
    if (status.isOk()) {
      status = file.size(fileSize);
    }
    manifestBytes = fileSize.sizeBytes();
    if (status.isOk() && (manifestBytes < HEADER_BYTES + DIGEST_BYTES
        || manifestBytes > MAXIMUM_MANIFEST_BYTES)) {
      status = StatusCode.CORRUPTION;
    }
    if (status.isOk()) {
      manifestBuffer.clear();
      manifestBuffer.limit((int) manifestBytes);
      status = OfflineBackupIo.readExact(file, manifestBuffer, 0, io);
    }
    StatusCode close = file == null ? StatusCode.OK : file.close();
    if (status.isOk()) {
      status = close;
    }
    if (status.isOk()) {
      manifestBuffer.flip();
    }
    return status;
  }

  private StatusCode validateHeader(long bytes) {
    fileCount = manifestBuffer.getInt(40);
    boolean valid = manifestBuffer.getLong(0) == MANIFEST_MAGIC
        && manifestBuffer.getInt(8) == MANIFEST_VERSION
        && manifestBuffer.getInt(12) == bytes
        && fileCount > 0 && fileCount <= MAXIMUM_FILES
        && bytes == HEADER_BYTES + (long) fileCount * ENTRY_BYTES + DIGEST_BYTES;
    return valid ? StatusCode.OK : StatusCode.CORRUPTION;
  }

  private StatusCode validateManifestDigest(int bytes) {
    digest.reset();
    manifestBuffer.position(0);
    manifestBuffer.limit(bytes - DIGEST_BYTES);
    digest.update(manifestBuffer);
    StatusCode status = finishDigest();
    manifestBuffer.limit(bytes);
    return status.isOk()
            && sameDigest(digestOutput, 0, manifestBuffer, bytes - DIGEST_BYTES)
        ? StatusCode.OK : StatusCode.CORRUPTION;
  }

  private StatusCode decodeIdentity() {
    long high = manifestBuffer.getLong(16);
    long low = manifestBuffer.getLong(24);
    long generation = manifestBuffer.getLong(32);
    if (high == 0 && low == 0 || generation <= 0) {
      return StatusCode.CORRUPTION;
    }
    database = DatabaseIncarnation.of(high, low);
    walGeneration = WalGeneration.of(generation);
    return StatusCode.OK;
  }

  private StatusCode decodeEntries() {
    totalBytes = 0;
    String previous = null;
    for (int index = 0; index < fileCount; index++) {
      int offset = HEADER_BYTES + index * ENTRY_BYTES;
      String name = decodeName(offset);
      long size = manifestBuffer.getLong(offset + 8);
      if (name == null || size < 0
          || previous != null && previous.compareTo(name) >= 0
          || Long.MAX_VALUE - totalBytes < size) {
        return StatusCode.CORRUPTION;
      }
      previous = name;
      fileNames[index] = name;
      fileSizes[index] = size;
      totalBytes += size;
      copyDigestFromManifest(index, offset);
    }
    return StatusCode.OK;
  }

  private String decodeName(int offset) {
    int length = manifestBuffer.getInt(offset);
    if (length <= 0 || length > MAXIMUM_NAME_BYTES) {
      return null;
    }
    StringBuilder name = new StringBuilder(length);
    for (int index = 0; index < length; index++) {
      int value = manifestBuffer.get(offset + 48 + index) & 0xff;
      if (value < 33 || value > 126) {
        return null;
      }
      name.append((char) value);
    }
    String decoded = name.toString();
    return validName(decoded) && !MANIFEST_FILE_NAME.equals(decoded)
        ? decoded : null;
  }

  private void copyDigestFromManifest(int index, int offset) {
    for (int digestIndex = 0; digestIndex < DIGEST_BYTES; digestIndex++) {
      fileDigests[index * DIGEST_BYTES + digestIndex] =
          manifestBuffer.get(offset + 16 + digestIndex);
    }
  }

  private void encodeManifest(int bytes) {
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
      encodeEntry(index);
    }
  }

  private void encodeEntry(int index) {
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

  private StatusCode appendManifestDigest(int bytes) {
    digest.reset();
    manifestBuffer.position(0);
    manifestBuffer.limit(bytes - DIGEST_BYTES);
    digest.update(manifestBuffer);
    StatusCode status = finishDigest();
    manifestBuffer.limit(bytes);
    for (int index = 0; status.isOk() && index < DIGEST_BYTES; index++) {
      manifestBuffer.put(bytes - DIGEST_BYTES + index, digestOutput[index]);
    }
    manifestBuffer.position(0);
    manifestBuffer.limit(bytes);
    return status;
  }

  private StatusCode finishDigest() {
    try {
      int bytes = digest.digest(digestOutput, 0, DIGEST_BYTES);
      return bytes == DIGEST_BYTES
          ? StatusCode.OK : StatusCode.INVARIANT_BROKEN;
    } catch (DigestException failure) {
      return StatusCode.INVARIANT_BROKEN;
    }
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

  private boolean validName(String name) {
    if (name == null || name.isEmpty() || name.length() > MAXIMUM_NAME_BYTES) {
      return false;
    }
    for (int index = 0; index < name.length(); index++) {
      char character = name.charAt(index);
      if (character < 33 || character > 126
          || character == '/' || character == '\\') {
        return false;
      }
    }
    return true;
  }

  private static boolean sameDigest(
      byte[] left, int leftOffset, byte[] right, int rightOffset) {
    int difference = 0;
    for (int index = 0; index < DIGEST_BYTES; index++) {
      difference |= left[leftOffset + index] ^ right[rightOffset + index];
    }
    return difference == 0;
  }

  private static boolean sameDigest(
      byte[] left, int leftOffset, ByteBuffer right, int rightOffset) {
    int difference = 0;
    for (int index = 0; index < DIGEST_BYTES; index++) {
      difference |= left[leftOffset + index] ^ right.get(rightOffset + index);
    }
    return difference == 0;
  }

  private static void zero(ByteBuffer buffer, int bytes) {
    for (int index = 0; index < bytes; index++) {
      buffer.put(index, (byte) 0);
    }
  }

  private static MessageDigest sha256() {
    try {
      return MessageDigest.getInstance("SHA-256");
    } catch (NoSuchAlgorithmException unavailable) {
      return null;
    }
  }
}
