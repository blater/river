package io.riverdb.engine.table;

import io.riverdb.base.error.StatusCode;
import io.riverdb.format.FormatBytes;
import io.riverdb.format.catalog.CatalogKeyspace;
import java.nio.ByteBuffer;

/** Decodes variable-width base, scalar, and tuple mutation items. */
final class IndexedRelationalWalMutationDecoder {
  private final IndexedRelationalMutationBuffer destination;
  private long totalPayloadBytes;
  private long decodedPayloadBytes;
  private int descriptorCount;

  IndexedRelationalWalMutationDecoder(IndexedRelationalMutationBuffer target) {
    destination = target;
  }

  void begin(int descriptors, long payloadBytes) {
    descriptorCount = descriptors;
    totalPayloadBytes = payloadBytes;
    decodedPayloadBytes = 0;
  }

  StatusCode decode(
      ByteBuffer source, int offset, int itemBytes, int mutation) {
    if (itemBytes < IndexedRelationalWalCodec.MUTATION_ITEM_BYTES) {
      return StatusCode.CORRUPTION;
    }
    int ordinal = FormatBytes.getInt(source, offset + 8);
    int operation = Byte.toUnsignedInt(source.get(offset + 12));
    int descriptor = FormatBytes.getInt(source, offset + 16);
    int suboperation = FormatBytes.getInt(source, offset + 20);
    int payloadLength = FormatBytes.getInt(source, offset + 24);
    if (!validEnvelope(source, offset, itemBytes, mutation, ordinal, payloadLength)) {
      return StatusCode.CORRUPTION;
    }
    int payloadOffset = offset + IndexedRelationalWalCodec.MUTATION_ITEM_BYTES;
    StatusCode status = append(
        source, offset, payloadOffset, payloadLength, operation, descriptor,
        suboperation);
    if (status.isOk()) decodedPayloadBytes += payloadLength;
    return status;
  }

  long decodedPayloadBytes() { return decodedPayloadBytes; }

  private boolean validEnvelope(
      ByteBuffer source, int offset, int itemBytes, int mutation,
      int ordinal, int payloadLength) {
    return ordinal == mutation && payloadLength >= 0
        && source.get(offset + 13) == 0 && source.get(offset + 14) == 0
        && source.get(offset + 15) == 0 && FormatBytes.getInt(source, offset + 28) == 0
        && itemBytes == IndexedRelationalWalCodec.MUTATION_ITEM_BYTES + payloadLength
        && decodedPayloadBytes <= totalPayloadBytes - payloadLength;
  }

  private StatusCode append(
      ByteBuffer source, int offset, int payloadOffset, int payloadLength,
      int operation, int descriptor, int suboperation) {
    long logicalRowId = FormatBytes.getLong(source, offset + 32);
    long previousRowId = FormatBytes.getLong(source, offset + 40);
    long ownerObjectId = FormatBytes.getLong(source, offset + 48);
    long space = FormatBytes.getLong(source, offset + 56);
    if (operation >= IndexedRelationalMutationBuffer.BASE_INSERT
        && operation <= IndexedRelationalMutationBuffer.BASE_DELETE) {
      long expectedSpace = CatalogKeyspace.relationalBaseRowSpace(ownerObjectId);
      return descriptor == -1 && space == expectedSpace
          ? destination.appendBase(
              suboperation, ownerObjectId, operation, logicalRowId, previousRowId,
              source, payloadOffset, payloadLength)
          : StatusCode.CORRUPTION;
    }
    if (operation >= IndexedRelationalMutationBuffer.SCALAR_INSERT
        && operation <= IndexedRelationalMutationBuffer.SCALAR_DELETE) {
      return descriptor == IndexedRelationalMutationBuffer.SCALAR_SUBOPERATION
              && ownerObjectId == 0
          ? destination.appendScalar(
              suboperation, operation, space, logicalRowId, previousRowId,
              source, payloadOffset, payloadLength)
          : StatusCode.CORRUPTION;
    }
    if (descriptor < 0 || descriptor >= descriptorCount
        || space != CatalogKeyspace.relationalIndexSpace(destination.keyIdAt(descriptor))) {
      return StatusCode.CORRUPTION;
    }
    return destination.appendTuple(
        suboperation, ownerObjectId, operation, descriptor,
        logicalRowId, source, payloadOffset, payloadLength);
  }
}
