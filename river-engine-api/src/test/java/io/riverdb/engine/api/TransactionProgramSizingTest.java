package io.riverdb.engine.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.riverdb.base.error.StatusCode;
import io.riverdb.base.type.SqlTypeDescriptor;
import org.junit.jupiter.api.Test;

final class TransactionProgramSizingTest {
  @Test
  void boundsStepParameterNodeAndCapturePeaks() {
    assertProgramPeak(stepHeavy(256), 256, 0, 0, 0, 0);
    assertProgramPeak(parameterHeavy(256), 1, 256, 0, 256, 256);
    assertProgramPeak(captureHeavy(1_024), 1, 0, 1_024, 0, 0);
  }

  @Test
  void boundsChunkRoundedArgumentPeak() {
    TrackingLease memory = new TrackingLease();
    TransactionProgramArguments arguments = new TransactionProgramArguments(memory);
    for (int slot = 0; slot < 1_000; slot++) {
      assertEquals(StatusCode.OK, arguments.setNull(slot, SqlTypeDescriptor.BIGINT));
    }
    String text = "x".repeat(4_096);
    assertEquals(StatusCode.OK,
        arguments.setText(1_000, SqlTypeDescriptor.varchar(4_096), text));
    assertTrue(memory.maximum <= TransactionProgramArguments.maximumRetainedBytes(1_001, 4_096));
  }

  @Test
  void boundsNullCellEmptyRowAndAsciiTextResultPeaks() {
    TrackingLease memory = new TrackingLease();
    TransactionProgramResult result = new TransactionProgramResult(memory);
    assertEquals(StatusCode.OK,
        result.beginStepResult(0, TransactionProgramAction.ROW_SET, 0));
    int cells = 0;
    for (int row = 0; row < 256; row++) {
      int columns = row % 2 == 0 ? 0 : 16;
      assertEquals(StatusCode.OK, result.beginRow(columns));
      for (int column = 0; column < columns; column++) {
        assertEquals(StatusCode.OK, result.appendNull(SqlTypeDescriptor.BIGINT));
        cells++;
      }
    }
    String text = "a".repeat(8_192);
    assertEquals(StatusCode.OK, result.beginRow(1));
    assertEquals(StatusCode.OK,
        result.appendText(SqlTypeDescriptor.varchar(8_192), text));
    assertTrue(memory.maximum <= TransactionProgramResult.maximumRetainedBytes(
        1, 257, cells + 1, 8_192));
  }

  private static TrackingLease stepHeavy(int steps) {
    TrackingLease memory = new TrackingLease();
    TransactionProgram program = new TransactionProgram(memory);
    for (int step = 0; step < steps; step++) {
      assertEquals(StatusCode.OK,
          program.beginStep(step + 1L, TransactionProgramAction.COMMAND));
      assertEquals(StatusCode.OK, program.endStep());
    }
    assertEquals(StatusCode.OK, program.freeze());
    return memory;
  }

  private static TrackingLease parameterHeavy(int parameters) {
    TrackingLease memory = new TrackingLease();
    TransactionProgram program = new TransactionProgram(memory);
    assertEquals(StatusCode.OK,
        program.beginStep(1, TransactionProgramAction.COMMAND));
    for (int parameter = 0; parameter < parameters; parameter++) {
      assertEquals(StatusCode.OK, program.beginParameter());
      assertEquals(StatusCode.OK, program.argument(parameter, SqlTypeDescriptor.BIGINT));
      assertEquals(StatusCode.OK, program.endExpression());
    }
    assertEquals(StatusCode.OK, program.endStep());
    assertEquals(StatusCode.OK, program.freeze());
    return memory;
  }

  private static TrackingLease captureHeavy(int captures) {
    TrackingLease memory = new TrackingLease();
    TransactionProgram program = new TransactionProgram(memory);
    assertEquals(StatusCode.OK,
        program.beginStep(1, TransactionProgramAction.ROW_SET));
    for (int capture = 0; capture < captures; capture++) {
      assertEquals(StatusCode.OK, program.captureColumn(capture));
    }
    assertEquals(StatusCode.OK, program.endStep());
    assertEquals(StatusCode.OK, program.freeze());
    return memory;
  }

  private static void assertProgramPeak(TrackingLease memory,
      int steps, int parameters, int captures, int expressions, int nodes) {
    assertTrue(memory.maximum <= TransactionProgram.maximumRetainedBytes(
        steps, parameters, captures, expressions, nodes));
  }

  private static final class TrackingLease implements RetainedMemoryLease {
    private long current;
    private long maximum;

    @Override
    public StatusCode resize(long bytes) {
      if (bytes < 0) return StatusCode.INVALID_EXTERNAL_INPUT;
      current = bytes;
      maximum = Math.max(maximum, bytes);
      return StatusCode.OK;
    }

    @Override
    public StatusCode awaitResize(long bytes) { return resize(bytes); }

    @Override
    public long retainedBytes() { return current; }
  }
}
