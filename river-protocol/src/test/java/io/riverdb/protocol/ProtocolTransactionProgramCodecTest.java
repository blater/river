package io.riverdb.protocol;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.riverdb.base.error.StatusCode;
import io.riverdb.base.type.SqlTypeDescriptor;
import io.riverdb.engine.api.ProgramOpenResult;
import io.riverdb.engine.api.IsolationLevel;
import io.riverdb.engine.api.TransactionProgram;
import io.riverdb.engine.api.TransactionProgramAction;
import io.riverdb.engine.api.TransactionProgramArguments;
import io.riverdb.engine.api.TransactionProgramResult;
import java.nio.ByteBuffer;
import org.junit.jupiter.api.Test;

final class ProtocolTransactionProgramCodecTest {
  private final ProtocolFrameCodec codec = new ProtocolFrameCodec();
  private final ProtocolFrame frame = new ProtocolFrame();

  @Test
  void preparesFrozenGraphAndExecutesHandleWithTypedArguments() {
    TransactionProgram source = program();
    ByteBuffer request = ByteBuffer.allocate(ProtocolFrameCodec.MAXIMUM_FRAME_BYTES);
    assertEquals(StatusCode.OK, codec.encodeProgramPrepareRequest(request, 41, source));
    assertEquals(StatusCode.OK, codec.decode(request, frame));
    ProtocolProgramRequestDecoder prepared = new ProtocolProgramRequestDecoder();
    assertEquals(StatusCode.OK, codec.decodeProgramRequest(frame, prepared));
    assertTrue(prepared.program().isFrozen());
    assertEquals(1, prepared.program().requiredArgumentSlots());
    assertEquals(1, prepared.program().minimumAffectedRows(0));
    assertEquals(4, prepared.program().maximumAffectedRows(0));
    assertEquals(0, request.get(ProtocolFrameCodec.HEADER_BYTES + 1));

    TransactionProgramArguments arguments = new TransactionProgramArguments();
    assertEquals(StatusCode.OK, arguments.setDecimal128(
        0, SqlTypeDescriptor.decimal(38, 18), 0, 17));
    assertEquals(StatusCode.OK, codec.encodeProgramExecuteRequest(
        request, 42, 77, IsolationLevel.REPEATABLE_READ, arguments,
        401, 9, 2));
    assertEquals(StatusCode.OK, codec.decode(request, frame));
    assertEquals(StatusCode.OK, prepared.decode(frame));
    assertEquals(77, prepared.handle());
    assertEquals(IsolationLevel.REPEATABLE_READ, prepared.isolationLevel());
    assertEquals(401, prepared.diagnosticTag());
    assertEquals(9, prepared.diagnosticStepTag());
    assertEquals(2, prepared.metricsEpoch());
    assertEquals(1, prepared.arguments().typeDescriptorAt(0) == 0 ? 0 : 1);
    assertEquals(17, prepared.arguments().valueAt(0));
  }

  @Test
  void roundTripsEveryStableProgramIsolationCode() {
    ByteBuffer request = ByteBuffer.allocate(ProtocolFrameCodec.MAXIMUM_FRAME_BYTES);
    ProtocolProgramRequestDecoder decoder = new ProtocolProgramRequestDecoder();
    long requestId = 80;
    for (IsolationLevel isolationLevel : IsolationLevel.values()) {
      assertEquals(StatusCode.OK, codec.encodeProgramExecuteRequest(
          request, requestId++, 77, isolationLevel, new TransactionProgramArguments(),
          0, 0, 0));
      assertEquals(StatusCode.OK, codec.decode(request, frame));
      assertEquals(StatusCode.OK, decoder.decode(frame));
      assertEquals(isolationLevel, decoder.isolationLevel());
    }
  }

  @Test
  void rejectsUnknownProgramIsolationCodeAndErasesPayload() {
    ByteBuffer request = ByteBuffer.allocate(ProtocolFrameCodec.MAXIMUM_FRAME_BYTES);
    assertEquals(StatusCode.OK, codec.encodeProgramExecuteRequest(
        request, 90, 77, IsolationLevel.SERIALIZABLE,
        new TransactionProgramArguments(), 0, 0, 0));
    request.putInt(
        ProtocolFrameCodec.HEADER_BYTES + Long.BYTES + Integer.BYTES, 99);
    assertEquals(StatusCode.OK, codec.decode(request, frame));
    ProtocolProgramRequestDecoder decoder = new ProtocolProgramRequestDecoder();
    assertEquals(StatusCode.INVALID_EXTERNAL_INPUT, decoder.decode(frame));
    for (int index = ProtocolFrameCodec.HEADER_BYTES; index < request.limit(); index++) {
      assertEquals(0, request.get(index));
    }
  }

  @Test
  void roundTripsProgramCloseHandle() {
    ByteBuffer request = ByteBuffer.allocate(ProtocolFrameCodec.MAXIMUM_FRAME_BYTES);
    assertEquals(StatusCode.OK, codec.encodeProgramCloseRequest(request, 43, 77));
    assertEquals(StatusCode.OK, codec.decode(request, frame));
    ProtocolProgramRequestDecoder decoder = new ProtocolProgramRequestDecoder();
    assertEquals(StatusCode.OK, codec.decodeProgramRequest(frame, decoder));
    assertEquals(77, decoder.handle());
  }

  @Test
  void roundTripsProgramOpenAndResultDiagnostics() {
    ProgramOpenResult opened = new ProgramOpenResult();
    assertEquals(StatusCode.OK, opened.complete(81, 3));
    ByteBuffer responseBytes = ByteBuffer.allocate(ProtocolFrameCodec.MAXIMUM_FRAME_BYTES);
    assertEquals(StatusCode.OK, codec.encodeProgramOpenResponse(
        responseBytes, 51, StatusCode.OK, opened));
    ProgramOpenResult decodedOpen = new ProgramOpenResult();
    assertEquals(StatusCode.OK, codec.decodeProgramOpenResponse(
        responseBytes, frame, decodedOpen));
    assertEquals(StatusCode.OK, codec.decodeProgramOpenResponse(
        responseBytes, frame, decodedOpen));
    assertEquals(81, decodedOpen.handle());
    assertEquals(3, decodedOpen.requiredArgumentSlots());

    TransactionProgramResult source = new TransactionProgramResult();
    assertEquals(StatusCode.OK, source.beginStepResult(
        0, TransactionProgramAction.ROW_SET, 2));
    assertEquals(StatusCode.OK, source.beginRow(3));
    assertEquals(StatusCode.OK, source.appendFixed(SqlTypeDescriptor.INTEGER, 0, 17));
    assertEquals(StatusCode.OK, source.appendNull(SqlTypeDescriptor.decimal(22, 18)));
    assertEquals(StatusCode.OK, source.appendText(SqlTypeDescriptor.varchar(16), "Aé😀"));
    source.complete(99);
    assertEquals(StatusCode.OK, codec.encodeProgramResultResponse(
        responseBytes, 52, StatusCode.OK, source));
    TransactionProgramResult decoded = new TransactionProgramResult();
    assertEquals(StatusCode.OK, codec.decodeProgramResultResponse(
        responseBytes, frame, decoded));
    assertEquals(99, decoded.commitSequence());
    assertEquals(1, decoded.rowCount());
    assertEquals(3, decoded.columnCount(0));
    assertEquals(17, decoded.valueAt(0, 0));
    assertTrue(decoded.isNull(0, 1));
    assertEquals(4, decoded.textLengthAt(0, 2));
    assertEquals('é', decoded.textCharacterAt(0, 2, 1));
  }

  @Test
  void roundTripsMaximumDescriptorProgramTextBeyondUnsignedShortLength() {
    // Program results use the descriptor's scalar boundary and the logical-response byte budget;
    // a cell's encoded length is not constrained by a physical frame or legacy u16 width.
    String value = "\u0800".repeat(SqlTypeDescriptor.MAXIMUM_VARCHAR_SCALARS);
    int utf8Bytes = Math.multiplyExact(SqlTypeDescriptor.MAXIMUM_VARCHAR_SCALARS, 3);
    assertTrue(utf8Bytes > Short.toUnsignedInt((short) -1));
    assertTrue(utf8Bytes < ProtocolFrameCodec.MAXIMUM_LOGICAL_RESPONSE_PAYLOAD_BYTES);

    TransactionProgramResult source = new TransactionProgramResult();
    assertEquals(StatusCode.OK, source.beginStepResult(
        0, TransactionProgramAction.ROW_SET, 1));
    assertEquals(StatusCode.OK, source.beginRow(1));
    int descriptor = SqlTypeDescriptor.varchar(SqlTypeDescriptor.MAXIMUM_VARCHAR_SCALARS);
    assertEquals(StatusCode.OK, source.appendText(descriptor, value));
    source.complete(103);
    assertEquals(1, source.rowCount());
    assertEquals(1, source.columnCount(0));
    assertEquals(descriptor, source.typeDescriptorAt(0, 0));
    assertFalse(source.isNull(0, 0));
    assertEquals(value.length(), source.textLengthAt(0, 0));

    ByteBuffer encodedValue = ByteBuffer.allocate(ProtocolValueHeader.BYTES + utf8Bytes);
    assertEquals(utf8Bytes, ProtocolProgramResultValueCodec.bytes(source, 0, 0));
    assertEquals(encodedValue.capacity(),
        ProtocolProgramResultValueCodec.write(encodedValue, 0, source, 0, 0));
    assertEquals(utf8Bytes, encodedValue.getInt(ProtocolValueHeader.LENGTH_OFFSET));

    int required = ProtocolProgramResultEncoder.requiredWireBytes(StatusCode.OK, source);
    assertTrue(required > ProtocolFrameCodec.MAXIMUM_FRAME_BYTES);
    ByteBuffer response = ByteBuffer.allocate(required);
    assertEquals(StatusCode.OK, codec.encodeProgramResultResponse(
        response, 55, StatusCode.OK, source));
    TransactionProgramResult decoded = new TransactionProgramResult();
    assertEquals(StatusCode.OK, codec.decodeProgramResultResponse(response, frame, decoded));
    assertEquals(descriptor, decoded.typeDescriptorAt(0, 0));
    assertEquals(value.length(), decoded.textLengthAt(0, 0));
    assertEquals('\u0800', decoded.textCharacterAt(0, 0, 0));
    assertEquals('\u0800', decoded.textCharacterAt(
        0, 0, SqlTypeDescriptor.MAXIMUM_VARCHAR_SCALARS - 1));
  }

  @Test
  void preservesStepAndRowGroupingAcrossProgramResults() {
    TransactionProgramResult source = new TransactionProgramResult();
    assertEquals(StatusCode.OK, source.beginStepResult(
        2, TransactionProgramAction.COMMAND, 1));
    assertEquals(StatusCode.OK, source.beginRow(1));
    assertEquals(StatusCode.OK, source.appendFixed(SqlTypeDescriptor.INTEGER, 0, 11));
    assertEquals(StatusCode.OK, source.beginStepResult(
        5, TransactionProgramAction.ROW_SET, 2));
    assertEquals(StatusCode.OK, source.beginRow(1));
    assertEquals(StatusCode.OK, source.appendFixed(SqlTypeDescriptor.INTEGER, 0, 12));
    assertEquals(StatusCode.OK, source.beginRow(1));
    assertEquals(StatusCode.OK, source.appendFixed(SqlTypeDescriptor.INTEGER, 0, 13));
    source.complete(102);

    ByteBuffer responseBytes = ByteBuffer.allocate(ProtocolFrameCodec.MAXIMUM_FRAME_BYTES);
    assertEquals(StatusCode.OK, codec.encodeProgramResultResponse(
        responseBytes, 53, StatusCode.OK, source));
    TransactionProgramResult decoded = new TransactionProgramResult();
    assertEquals(StatusCode.OK, codec.decodeProgramResultResponse(
        responseBytes, frame, decoded));
    assertEquals(2, decoded.stepCount());
    assertEquals(1, decoded.rowCount(0));
    assertEquals(2, decoded.rowCount(1));
    assertEquals(11, decoded.valueAt(0, 0));
    assertEquals(13, decoded.valueAt(2, 0));
  }

  @Test
  void roundTripsFailureIndexWithoutPublishingRolledBackStepRows() {
    TransactionProgramResult source = new TransactionProgramResult();
    source.fail(7, StatusCode.UNIQUE_VIOLATION, StatusCode.OK, false);
    ByteBuffer responseBytes = ByteBuffer.allocate(ProtocolFrameCodec.MAXIMUM_FRAME_BYTES);
    assertEquals(StatusCode.OK, codec.encodeProgramResultResponse(
        responseBytes, 54, StatusCode.UNIQUE_VIOLATION, source));
    TransactionProgramResult decoded = new TransactionProgramResult();
    assertEquals(StatusCode.OK, codec.decodeProgramResultResponse(
        responseBytes, frame, decoded));
    assertEquals(StatusCode.UNIQUE_VIOLATION, codec.decodedProgramResultStatus());
    assertEquals(0, decoded.stepCount());
    assertEquals(7, decoded.failingStep());
    assertEquals(StatusCode.UNIQUE_VIOLATION, decoded.primaryStatus());
  }

  @Test
  void rejectsMalformedGraphAndErasesRequestPayload() {
    ByteBuffer request = ByteBuffer.allocate(ProtocolFrameCodec.MAXIMUM_FRAME_BYTES);
    assertEquals(StatusCode.OK,
        codec.encodeProgramPrepareRequest(request, 61, program()));
    request.putInt(ProtocolFrameCodec.HEADER_BYTES, 99);
    assertEquals(StatusCode.OK, codec.decode(request, frame));
    ProtocolProgramRequestDecoder decoder = new ProtocolProgramRequestDecoder();
    assertEquals(StatusCode.INVALID_EXTERNAL_INPUT, codec.decodeProgramRequest(frame, decoder));
    for (int index = ProtocolFrameCodec.HEADER_BYTES; index < request.limit(); index++) {
      assertEquals(0, request.get(index));
    }
  }

  @Test
  void rejectsNegativeAndEnvelopeOverrunU32ProgramResultLengths() {
    // Typed result cells accept only nonnegative lengths wholly contained by the admitted result
    // payload, before any row or cell is published to the caller-owned result.
    TransactionProgramResult source = new TransactionProgramResult();
    assertEquals(StatusCode.OK, source.beginStepResult(
        0, TransactionProgramAction.ROW_SET, 1));
    assertEquals(StatusCode.OK, source.beginRow(1));
    assertEquals(StatusCode.OK, source.appendText(
        SqlTypeDescriptor.varchar(SqlTypeDescriptor.MAXIMUM_VARCHAR_SCALARS), "ok"));
    source.complete(104);

    ByteBuffer response = ByteBuffer.allocate(ProtocolFrameCodec.MAXIMUM_FRAME_BYTES);
    int value = ProtocolFrameCodec.HEADER_BYTES
        + ProtocolProgramResultEncoder.HEADER_BYTES
        + ProtocolProgramResultEncoder.STEP_BYTES
        + ProtocolProgramResultEncoder.ROW_BYTES;
    TransactionProgramResult decoded = new TransactionProgramResult();

    assertEquals(StatusCode.OK, codec.encodeProgramResultResponse(
        response, 56, StatusCode.OK, source));
    response.putInt(value + ProtocolValueHeader.LENGTH_OFFSET, -1);
    assertEquals(StatusCode.INVALID_EXTERNAL_INPUT,
        codec.decodeProgramResultResponse(response, frame, decoded));
    assertEquals(0, decoded.rowCount());

    assertEquals(StatusCode.OK, codec.encodeProgramResultResponse(
        response, 56, StatusCode.OK, source));
    response.putInt(value + ProtocolValueHeader.LENGTH_OFFSET,
        ProtocolProgramResultValueCodec.bytes(source, 0, 0) + 1);
    assertEquals(StatusCode.INVALID_EXTERNAL_INPUT,
        codec.decodeProgramResultResponse(response, frame, decoded));
    assertEquals(0, decoded.rowCount());
  }

  @Test
  void assemblesContinuedProgramResultBeforePublishingRows() {
    TransactionProgramResult source = new TransactionProgramResult();
    assertEquals(StatusCode.OK, source.beginStepResult(
        0, TransactionProgramAction.ROW_SET, 2_000));
    for (int row = 0; row < 2_000; row++) {
      assertEquals(StatusCode.OK, source.beginRow(1));
      assertEquals(StatusCode.OK, source.appendText(
          SqlTypeDescriptor.varchar(64), "row-" + row + "-payload"));
    }
    source.complete(101);
    ByteBuffer responseBytes = ByteBuffer.allocate(ProtocolFrameCodec.MAXIMUM_RESPONSE_BYTES);
    assertEquals(StatusCode.OK, codec.encodeProgramResultResponse(
        responseBytes, 71, StatusCode.OK, source));
    assertTrue(responseBytes.remaining() > ProtocolFrameCodec.MAXIMUM_FRAME_BYTES);
    TransactionProgramResult decoded = new TransactionProgramResult();
    assertEquals(StatusCode.OK, codec.decodeProgramResultResponse(
        responseBytes, frame, decoded));
    assertEquals(2_000, decoded.rowCount());
    assertEquals("row-1999-payload".length(), decoded.textLengthAt(1_999, 0));
  }

  private static TransactionProgram program() {
    TransactionProgram program = new TransactionProgram();
    assertEquals(StatusCode.OK, program.beginStep(7, TransactionProgramAction.COMMAND));
    assertEquals(StatusCode.OK, program.beginParameter());
    assertEquals(StatusCode.OK, program.argument(0, SqlTypeDescriptor.INTEGER));
    assertEquals(StatusCode.OK, program.endExpression());
    assertEquals(StatusCode.OK, program.requireAffectedRows(1, 4));
    assertEquals(StatusCode.OK, program.endStep());
    assertEquals(StatusCode.OK, program.freeze());
    return program;
  }
}
