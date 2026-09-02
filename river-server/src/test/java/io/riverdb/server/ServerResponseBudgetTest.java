package io.riverdb.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import io.riverdb.base.error.StatusCode;
import io.riverdb.base.sql.SqlShapeLimits;
import io.riverdb.base.type.SqlTypeDescriptor;
import io.riverdb.engine.api.CommandResult;
import io.riverdb.engine.api.IsolationLevel;
import io.riverdb.engine.api.ParameterSet;
import io.riverdb.engine.api.PreparedOpenResult;
import io.riverdb.engine.api.ProgramOpenResult;
import io.riverdb.engine.api.QueryOpenResult;
import io.riverdb.engine.api.RiverDatabase;
import io.riverdb.engine.api.RiverSession;
import io.riverdb.engine.api.SessionOpenResult;
import io.riverdb.engine.api.TransactionProgram;
import io.riverdb.engine.api.TransactionProgramAction;
import io.riverdb.engine.api.TransactionProgramArguments;
import io.riverdb.engine.api.TransactionProgramResult;
import io.riverdb.protocol.ProtocolMemoryBudget;
import io.riverdb.protocol.ProtocolFrameCodec;
import io.riverdb.protocol.ProtocolMessageType;
import java.nio.ByteBuffer;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

final class ServerResponseBudgetTest {
  @Test
  void returnsProgramDecoderMemoryToBaselineAcrossReconnects() {
    ProtocolMemoryBudget budget = ProtocolMemoryBudget.forServer(1);
    ServerConnectionMemory memory = new ServerConnectionMemory(budget);
    ServerResponseBuffer responses = new ServerResponseBuffer(memory.lease());
    long baseline = budget.retainedBytes();
    WideDatabase database = new WideDatabase();

    for (int cycle = 0; cycle < 5; cycle++) {
      SessionEndpoint endpoint = openedEndpoint(database, responses, memory);
      ByteBuffer request = ByteBuffer.allocate(ProtocolFrameCodec.MAXIMUM_FRAME_BYTES);
      assertEquals(StatusCode.OK, new ProtocolFrameCodec().encodeProgramPrepareRequest(
          request, 3, commandProgram()));
      assertEquals(StatusCode.OK, responses.process(endpoint, request));
      assertFalse(budget.retainedBytes() == baseline);
      assertEquals(StatusCode.OK, endpoint.close());
      assertEquals(baseline, budget.retainedBytes());
    }
  }

  @Test
  void scrubsPublishedProgramValuesWhileKeepingWarmCapacity() {
    ProtocolMemoryBudget budget = ProtocolMemoryBudget.forServer(1);
    ServerConnectionMemory memory = new ServerConnectionMemory(budget);
    ServerResponseBuffer responses = new ServerResponseBuffer(memory.lease());
    WideDatabase database = new WideDatabase();
    SessionEndpoint endpoint = openedEndpoint(database, responses, memory);
    ProtocolFrameCodec codec = new ProtocolFrameCodec();
    ByteBuffer request = ByteBuffer.allocate(ProtocolFrameCodec.MAXIMUM_FRAME_BYTES);
    assertEquals(StatusCode.OK, codec.encodeProgramPrepareRequest(
        request, 3, commandProgram()));
    assertEquals(StatusCode.OK, responses.process(endpoint, request));
    assertEquals(StatusCode.OK, codec.encodeProgramExecuteRequest(
        request, 4, 44, IsolationLevel.REPEATABLE_READ,
        new TransactionProgramArguments()));
    assertEquals(StatusCode.OK, responses.process(endpoint, request));
    TransactionProgramResult published = database.session.programResult;
    assertEquals(IsolationLevel.REPEATABLE_READ, database.session.programIsolation);
    long warmBytes = published.retainedBytes();
    assertEquals(1, published.stepCount());
    assertFalse(warmBytes == 0);

    endpoint.releasePublishedHighWater();

    assertEquals(0, published.stepCount());
    assertEquals(warmBytes, published.retainedBytes());
    assertEquals(StatusCode.OK, endpoint.close());
  }

  @Test
  void boundsSharedGrowthAndReturnsReleasedHighWaterToTheServer() {
    long base = 2L * ProtocolFrameCodec.MAXIMUM_FRAME_BYTES;
    long maximum = base + ProtocolFrameCodec.MAXIMUM_RESPONSE_BYTES
        - ProtocolFrameCodec.MAXIMUM_FRAME_BYTES;
    ProtocolMemoryBudget budget = new ProtocolMemoryBudget(maximum);
    ServerResponseBuffer first = new ServerResponseBuffer(budget.lease());
    ServerResponseBuffer second = new ServerResponseBuffer(budget.lease());
    assertEquals(base, budget.retainedBytes());

    growToMaximum(first);
    assertEquals(budget.maximumBytes(), budget.retainedBytes());
    assertEquals(StatusCode.RESOURCE_EXHAUSTED, growOnce(second));

    assertEquals(StatusCode.OK, first.releaseHighWater());
    assertEquals(base, budget.retainedBytes());
    assertEquals(StatusCode.OK, growOnce(second));
    assertEquals(base + ProtocolFrameCodec.MAXIMUM_FRAME_BYTES,
        budget.retainedBytes());
  }

  @Test
  void responsePressureWaitsOnStagedResultsWithoutReexecutingCommands() throws Exception {
    long base = 2L * ProtocolFrameCodec.MAXIMUM_FRAME_BYTES;
    ProtocolMemoryBudget budget = new ProtocolMemoryBudget(
        base + ProtocolFrameCodec.MAXIMUM_RESPONSE_BYTES
            - ProtocolFrameCodec.MAXIMUM_FRAME_BYTES);
    ServerResponseBuffer firstBuffer = new ServerResponseBuffer(budget.lease());
    ServerResponseBuffer secondBuffer = new ServerResponseBuffer(budget.lease());
    WideDatabase firstDatabase = new WideDatabase();
    WideDatabase secondDatabase = new WideDatabase();
    SessionEndpoint first = openedEndpoint(firstDatabase, firstBuffer);
    SessionEndpoint second = openedEndpoint(secondDatabase, secondBuffer);
    ByteBuffer firstRequest = executeRequest(3);
    ByteBuffer secondRequest = executeRequest(3);

    assertEquals(StatusCode.OK, firstBuffer.process(first, firstRequest));
    AtomicReference<StatusCode> secondStatus = new AtomicReference<>();
    Thread waiter = Thread.ofPlatform().start(
        () -> secondStatus.set(secondBuffer.process(second, secondRequest)));
    long deadline = System.nanoTime() + 2_000_000_000L;
    while (secondDatabase.session.executions == 0 && System.nanoTime() < deadline) {
      Thread.onSpinWait();
    }
    assertEquals(1, secondDatabase.session.executions);
    assertFalse(secondStatus.get() != null);

    assertEquals(StatusCode.OK, firstBuffer.releaseHighWater());
    waiter.join(2_000);
    assertFalse(waiter.isAlive());
    assertEquals(StatusCode.OK, secondStatus.get());
    assertEquals(1, firstDatabase.session.executions);
    assertEquals(1, secondDatabase.session.executions);
    second.releasePublishedHighWater();
    assertEquals(StatusCode.OK, secondBuffer.releaseHighWater());
    assertEquals(base, budget.retainedBytes());
  }

  private static void growToMaximum(ServerResponseBuffer buffer) {
    while (buffer.buffer().capacity() < ProtocolFrameCodec.MAXIMUM_RESPONSE_BYTES) {
      assertEquals(StatusCode.OK, growOnce(buffer));
    }
  }

  private static StatusCode growOnce(ServerResponseBuffer buffer) {
    return buffer.ensureCapacity(buffer.buffer().capacity() + 1);
  }

  private static SessionEndpoint openedEndpoint(
      WideDatabase database, ServerResponseBuffer responses) {
    return openedEndpoint(database, responses, null);
  }

  private static SessionEndpoint openedEndpoint(
      WideDatabase database, ServerResponseBuffer responses, ServerConnectionMemory memory) {
    SessionEndpoint endpoint = memory == null ? new SessionEndpoint(database)
        : new SessionEndpoint(database, null, 0, 0, null, null, memory, responses);
    ProtocolFrameCodec codec = new ProtocolFrameCodec();
    ByteBuffer request = ByteBuffer.allocate(ProtocolFrameCodec.MAXIMUM_FRAME_BYTES);
    assertEquals(StatusCode.OK, codec.encodeRequest(request, ProtocolMessageType.HELLO, 1));
    assertEquals(StatusCode.OK, responses.process(endpoint, request));
    assertEquals(StatusCode.OK, codec.encodeRequest(request, ProtocolMessageType.OPEN_SESSION, 2));
    assertEquals(StatusCode.OK, responses.process(endpoint, request));
    return endpoint;
  }

  private static TransactionProgram commandProgram() {
    TransactionProgram program = new TransactionProgram();
    assertEquals(StatusCode.OK,
        program.beginStep(7, TransactionProgramAction.COMMAND));
    assertEquals(StatusCode.OK, program.endStep());
    assertEquals(StatusCode.OK, program.freeze());
    return program;
  }

  private static ByteBuffer executeRequest(long requestId) {
    ProtocolFrameCodec codec = new ProtocolFrameCodec();
    ByteBuffer request = ByteBuffer.allocate(ProtocolFrameCodec.MAXIMUM_FRAME_BYTES);
    assertEquals(StatusCode.OK, codec.encodeSqlRequest(
        request, ProtocolMessageType.EXECUTE, requestId, "SELECT wide", null));
    return request;
  }

  private static final class WideDatabase implements RiverDatabase {
    private final WideSession session = new WideSession();
    @Override
    public StatusCode createSession(SessionOpenResult result) { return result.complete(session); }
    @Override
    public StatusCode deferTerminalClose(RiverSession value) { return StatusCode.OK; }
    @Override
    public StatusCode close() { return StatusCode.OK; }
  }

  private static final class WideSession implements RiverSession {
    private final long[] values = new long[SqlShapeLimits.MAX_RESULT_COLUMNS];
    private final int[] descriptors = new int[SqlShapeLimits.MAX_RESULT_COLUMNS];
    private final long[] nulls = new long[
        (SqlShapeLimits.MAX_RESULT_COLUMNS + Long.SIZE - 1) / Long.SIZE];
    private int executions;
    private TransactionProgramResult programResult;
    private IsolationLevel programIsolation;

    private WideSession() {
      for (int index = 0; index < descriptors.length; index++) {
        descriptors[index] = SqlTypeDescriptor.BIGINT;
      }
    }

    @Override
    public StatusCode execute(String sql, ParameterSet parameters, CommandResult result) {
      executions++;
      return result.complete(
          1, 1, false, true, 1,
          values, nulls, nulls.length, descriptors, descriptors.length);
    }

    @Override
    public StatusCode close() { return StatusCode.OK; }
    @Override
    public StatusCode prepare(String sql, PreparedOpenResult result) { return StatusCode.CLOSED; }
    @Override
    public StatusCode executePrepared(long handle, ParameterSet parameters, CommandResult result) {
      return StatusCode.CLOSED;
    }
    @Override
    public StatusCode beginPreparedQuery(
        long handle, ParameterSet parameters, QueryOpenResult result) {
      return StatusCode.CLOSED;
    }
    @Override
    public StatusCode closePrepared(long handle) { return StatusCode.CLOSED; }
    @Override
    public StatusCode prepareProgram(TransactionProgram program, ProgramOpenResult result) {
      return result.complete(44, 0);
    }
    @Override
    public StatusCode executeProgram(long handle, IsolationLevel isolationLevel,
        TransactionProgramArguments arguments,
        TransactionProgramResult result) {
      programIsolation = isolationLevel;
      programResult = result;
      assertEquals(StatusCode.OK,
          result.beginStepResult(0, TransactionProgramAction.COMMAND, 1));
      assertEquals(StatusCode.OK, result.beginRow(1));
      assertEquals(StatusCode.OK,
          result.appendText(SqlTypeDescriptor.varchar(16), "sensitive"));
      result.complete(1);
      return StatusCode.OK;
    }
    @Override
    public StatusCode closeProgram(long handle) { return StatusCode.CLOSED; }
    @Override
    public StatusCode execute(String sql, CommandResult result) { return StatusCode.CLOSED; }
    @Override
    public StatusCode beginQuery(String sql, QueryOpenResult result) { return StatusCode.CLOSED; }
    @Override
    public StatusCode beginQuery(String sql, ParameterSet parameters, QueryOpenResult result) {
      return StatusCode.CLOSED;
    }
  }
}
