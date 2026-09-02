package io.riverdb.engine.runtime;

import io.riverdb.base.error.StatusCode;
import io.riverdb.base.error.StatusDetail;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;

/** Bounded byte reader and strict UTF-8 decoder for river.properties. */
final class RuntimeConfigFileParser {
  private final byte[] encoded = new byte[RiverRuntimeConfig.MAXIMUM_CONFIG_BYTES + 1];
  private int encodedBytes;

  private RuntimeConfigFileParser() {}

  static StatusCode parse(
      Path source,
      RuntimeConfigProperties properties,
      StatusDetail detail) {
    RuntimeConfigFileParser parser = new RuntimeConfigFileParser();
    StatusCode status = parser.read(source, detail);
    if (!status.isOk()) return status;
    status = parser.validateLineLengths(detail);
    if (!status.isOk()) return status;
    String decoded = parser.decode(detail);
    if (decoded == null) return detail.code();
    return properties.parse(decoded, detail);
  }

  private StatusCode read(Path source, StatusDetail detail) {
    try (InputStream input = Files.newInputStream(source)) {
      while (encodedBytes < encoded.length) {
        int count = input.read(encoded, encodedBytes, encoded.length - encodedBytes);
        if (count < 0) break;
        if (count == 0) {
          detail.set(StatusCode.IO_FAILURE)
              .append("zero-progress configuration read: ")
              .append(source.toString());
          return StatusCode.IO_FAILURE;
        }
        encodedBytes += count;
      }
      if (encodedBytes > RiverRuntimeConfig.MAXIMUM_CONFIG_BYTES) {
        detail.set(StatusCode.INVALID_EXTERNAL_INPUT)
            .append("configuration exceeds 16384 bytes: ")
            .append(source.toString());
        return StatusCode.INVALID_EXTERNAL_INPUT;
      }
      return StatusCode.OK;
    } catch (NoSuchFileException missing) {
      return StatusCode.OK;
    } catch (IOException | SecurityException failure) {
      detail.set(StatusCode.IO_FAILURE)
          .append("cannot read configuration: ")
          .append(source.toString());
      return StatusCode.IO_FAILURE;
    }
  }

  private StatusCode validateLineLengths(StatusDetail detail) {
    int lineStart = 0;
    for (int index = 0; index <= encodedBytes; index++) {
      if (index != encodedBytes && encoded[index] != '\n') continue;
      int lineEnd = index;
      if (lineEnd > lineStart && encoded[lineEnd - 1] == '\r') lineEnd--;
      if (lineEnd - lineStart > RiverRuntimeConfig.MAXIMUM_LINE_BYTES) {
        detail.set(StatusCode.INVALID_EXTERNAL_INPUT)
            .append("river.properties line exceeds 4096 bytes");
        return StatusCode.INVALID_EXTERNAL_INPUT;
      }
      lineStart = index + 1;
    }
    return StatusCode.OK;
  }

  private String decode(StatusDetail detail) {
    try {
      return StandardCharsets.UTF_8.newDecoder()
          .onMalformedInput(CodingErrorAction.REPORT)
          .onUnmappableCharacter(CodingErrorAction.REPORT)
          .decode(ByteBuffer.wrap(encoded, 0, encodedBytes))
          .toString();
    } catch (CharacterCodingException failure) {
      detail.set(StatusCode.INVALID_EXTERNAL_INPUT)
          .append("river.properties is not valid UTF-8");
      return null;
    }
  }
}
