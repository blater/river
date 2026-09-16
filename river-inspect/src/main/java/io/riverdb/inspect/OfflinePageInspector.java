package io.riverdb.inspect;

import io.riverdb.base.error.StatusCode;
import io.riverdb.format.page.PageCodec;
import io.riverdb.format.page.PageHeader;
import io.riverdb.platform.file.nio.NioDurableDirectory;
import java.nio.ByteBuffer;
import java.util.zip.CRC32C;

/** Validates page headers and database/generation identity, not payload integrity. */
final class OfflinePageInspector {
  private final OfflineInspectionFile file;
  private final PageHeader header = new PageHeader();
  private final CRC32C checksum = new CRC32C();
  private final ByteBuffer bytes = ByteBuffer.allocateDirect(PageCodec.PAGE_BYTES);

  OfflinePageInspector(OfflineInspectionFile inspectedFile) {
    file = inspectedFile;
  }

  StatusCode inspect(
      NioDurableDirectory directory,
      String name,
      DatabaseInspectionResult result) {
    StatusCode status = file.open(directory, name);
    if (status.isOk()) status = inspectOpenFile(name, result);
    return file.close(status);
  }

  private StatusCode inspectOpenFile(String name, DatabaseInspectionResult result) {
    StatusCode status = file.readSize();
    if (!status.isOk()) return status;
    long size = file.sizeBytes();
    if (size == 0 || size % PageCodec.PAGE_BYTES != 0) return StatusCode.CORRUPTION;
    long pageTotal = size / PageCodec.PAGE_BYTES;
    if (pageTotal > Integer.MAX_VALUE) return StatusCode.RESOURCE_EXHAUSTED;
    int pages = (int) pageTotal;
    for (int index = 0; index < pages; index++) {
      status = inspectPage(name, result, index);
      if (!status.isOk()) return status;
    }
    result.addPageFile(size, pages);
    return StatusCode.OK;
  }

  private StatusCode inspectPage(
      String name, DatabaseInspectionResult result, int index) {
    bytes.clear();
    StatusCode status = file.read((long) index * PageCodec.PAGE_BYTES, bytes);
    if (status.isOk()) {
      bytes.flip();
      status = PageCodec.validate(bytes, header, checksum);
    }
    if (status.isOk() && (header.databaseHigh() != result.database().high()
        || header.databaseLow() != result.database().low()
        || header.pageId() != index + 1L)) {
      return StatusCode.CORRUPTION;
    }
    if (!status.isOk() || OfflinePhysicalFileNames.PAGE_FILE.equals(name)) {
      return status;
    }
    long generation = OfflinePhysicalFileNames.generation(
        name, OfflinePhysicalFileNames.PAGE_FILE);
    return generation <= 0 || generation != header.walGeneration()
        ? StatusCode.CORRUPTION : StatusCode.OK;
  }
}
