package io.riverdb.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.riverdb.base.error.StatusCode;
import io.riverdb.base.id.DatabaseIncarnation;
import io.riverdb.base.id.WalGeneration;
import io.riverdb.base.type.SqlTypeDescriptor;
import io.riverdb.base.type.SqlApproximateNumeric;
import io.riverdb.engine.EmbeddedRiver;
import io.riverdb.engine.api.CommandResult;
import io.riverdb.engine.api.DatabaseOpenResult;
import io.riverdb.engine.api.IsolationLevel;
import io.riverdb.engine.api.ParameterSet;
import io.riverdb.engine.api.PreparedOpenResult;
import io.riverdb.engine.api.ProgramOpenResult;
import io.riverdb.engine.api.QueryOpenResult;
import io.riverdb.engine.api.QueryMetadata;
import io.riverdb.engine.api.RetainedMemoryLease;
import io.riverdb.engine.api.RiverDatabase;
import io.riverdb.engine.api.RiverQuery;
import io.riverdb.engine.api.RiverSession;
import io.riverdb.engine.api.RowResult;
import io.riverdb.engine.api.SessionOpenResult;
import io.riverdb.engine.api.TransactionProgram;
import io.riverdb.engine.api.TransactionProgramAction;
import io.riverdb.engine.api.TransactionProgramArguments;
import io.riverdb.engine.api.TransactionProgramResult;
import io.riverdb.protocol.ProtocolFrameCodec;
import io.riverdb.protocol.ProtocolMessageType;
import io.riverdb.protocol.ProtocolQueryMetadata;
import io.riverdb.server.LoopbackRiverServer;
import io.riverdb.server.LoopbackServerOpenResult;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class RiverClientConnectionTest {
  private static final DatabaseIncarnation DATABASE =
      DatabaseIncarnation.of(0x434c49454e544442L, 0x5445535430303031L);
  private static final WalGeneration GENERATION = WalGeneration.of(1);

  @Test
  void publishesRemoteQueryMetadataAndAdmitsRowsBeforeFetch(@TempDir Path root) {
    DatabaseOpenResult opened = new DatabaseOpenResult();
    assertEquals(StatusCode.OK, EmbeddedRiver.create(root, DATABASE, GENERATION, 8, opened));
    RiverDatabase database = opened.database();
    LoopbackRiverServer server = start(database);
    RiverClientConnection client = connect(server);
    SessionOpenResult sessionResult = new SessionOpenResult();
    assertEquals(StatusCode.OK, client.createSession(sessionResult));
    RiverSession session = sessionResult.session();
    CommandResult command = new CommandResult();
    assertEquals(
        StatusCode.OK,
        session.execute(
            "CREATE TABLE remote_metadata "
                + "(id BIGINT PRIMARY KEY, label VARCHAR(7) NOT NULL)",
            command));
    assertEquals(
        StatusCode.OK,
        session.execute("INSERT INTO remote_metadata VALUES (1, 'alpha')", command));
    QueryOpenResult openedQuery = new QueryOpenResult();
    assertEquals(
        StatusCode.OK,
        session.beginQuery("SELECT id, label FROM remote_metadata", openedQuery));
    RiverQuery query = openedQuery.query();
    QueryMetadata metadata = openedQuery.metadata();
    assertTrue(metadata != null);
    assertEquals(2, metadata.columnCount());
    assertEquals(28, metadata.maximumEncodedTextBytes());
    assertTrue(metadata.reservationGeneration() > 0);
    RowResult row = new RowResult();
    assertEquals(StatusCode.OK, row.reserve(metadata, null));
    assertTrue(row.isReservedFor(metadata));
    long requests = client.completedRequests();
    assertEquals(StatusCode.OK, query.next(row));
    assertEquals(requests, client.completedRequests());
    assertEquals(1, row.valueAt(0));
    assertText(row, 1, "alpha");
    assertEquals(StatusCode.OK, query.close(command));
    assertEquals(requests, client.completedRequests());

    assertEquals(StatusCode.OK, session.beginQuery(
        "SELECT id, label FROM remote_metadata WHERE id=99", openedQuery));
    query = openedQuery.query();
    requests = client.completedRequests();
    assertEquals(StatusCode.OK, query.next(row));
    assertFalse(row.isAvailable());
    assertEquals(requests, client.completedRequests());
    assertEquals(StatusCode.OK, query.close(command));
    assertEquals(requests, client.completedRequests());
    assertEquals(StatusCode.OK, session.close());
    assertEquals(StatusCode.OK, client.close());
    assertEquals(StatusCode.OK, server.close());
    assertEquals(StatusCode.OK, database.close());
  }

  @Test
  void executesPreparedTransactionProgramInOneRequest(@TempDir Path root) {
    DatabaseOpenResult opened = new DatabaseOpenResult();
    assertEquals(StatusCode.OK, EmbeddedRiver.create(root, DATABASE, GENERATION, 8, opened));
    RiverDatabase database = opened.database();
    LoopbackRiverServer server = start(database);
    RiverClientConnection client = connect(server);
    SessionOpenResult sessionResult = new SessionOpenResult();
    assertEquals(StatusCode.OK, client.createSession(sessionResult));
    RiverSession session = sessionResult.session();
    CommandResult command = new CommandResult();
    assertEquals(StatusCode.OK, session.execute(
        "CREATE TABLE program_accounts (id BIGINT PRIMARY KEY, balance BIGINT)", command));
    assertEquals(StatusCode.OK, session.execute(
        "INSERT INTO program_accounts VALUES (1, 100)", command));

    PreparedOpenResult update = new PreparedOpenResult();
    PreparedOpenResult select = new PreparedOpenResult();
    assertEquals(StatusCode.OK, session.prepare(
        "UPDATE program_accounts SET balance=balance+? WHERE id=?", update));
    assertEquals(StatusCode.OK, session.prepare(
        "SELECT balance FROM program_accounts WHERE id=?", select));
    TransactionProgram program = new TransactionProgram();
    assertEquals(StatusCode.OK, program.beginStep(
        update.handle(), TransactionProgramAction.COMMAND));
    appendArgument(program, 0);
    appendArgument(program, 1);
    assertEquals(StatusCode.OK, program.endStep());
    assertEquals(StatusCode.OK, program.beginStep(
        select.handle(), TransactionProgramAction.EXACT_ONE));
    appendArgument(program, 1);
    assertEquals(StatusCode.OK, program.captureColumn(0));
    assertEquals(StatusCode.OK, program.endStep());
    assertEquals(StatusCode.OK, program.freeze());
    ProgramOpenResult programOpen = new ProgramOpenResult();
    assertEquals(StatusCode.OK, session.prepareProgram(program, programOpen));
    assertEquals(2, programOpen.requiredArgumentSlots());

    TransactionProgramArguments arguments = new TransactionProgramArguments();
    assertEquals(StatusCode.OK, arguments.setFixed(0, SqlTypeDescriptor.BIGINT, 25));
    assertEquals(StatusCode.OK, arguments.setFixed(1, SqlTypeDescriptor.BIGINT, 1));
    TransactionProgramResult result = new TransactionProgramResult();
    long requests = client.completedRequests();
    assertEquals(StatusCode.OK, session.executeProgram(
        programOpen.handle(), IsolationLevel.REPEATABLE_READ, arguments, result));
    assertEquals(requests + 1, client.completedRequests());
    assertEquals(2, result.stepCount());
    assertEquals(1, result.affectedRows(0));
    assertEquals(1, result.rowCount());
    assertEquals(125, result.valueAt(0, 0));
    assertEquals(StatusCode.CONFLICT, session.closePrepared(update.handle()));
    assertEquals(StatusCode.OK, session.closeProgram(programOpen.handle()));
    assertEquals(StatusCode.OK, session.closePrepared(update.handle()));
    assertEquals(StatusCode.OK, session.closePrepared(select.handle()));
    assertEquals(StatusCode.OK, session.close());
    assertEquals(StatusCode.OK, client.close());
    assertEquals(StatusCode.OK, server.close());
    assertEquals(StatusCode.OK, database.close());
  }

  @Test
  void returnsProgramFailureAndKeepsRolledBackSessionUsable(@TempDir Path root) {
    DatabaseOpenResult opened = new DatabaseOpenResult();
    assertEquals(StatusCode.OK, EmbeddedRiver.create(root, DATABASE, GENERATION, 8, opened));
    RiverDatabase database = opened.database();
    LoopbackRiverServer server = start(database);
    RiverClientConnection client = connect(server);
    SessionOpenResult sessionResult = new SessionOpenResult();
    assertEquals(StatusCode.OK, client.createSession(sessionResult));
    RiverSession session = sessionResult.session();
    CommandResult command = new CommandResult();
    assertEquals(StatusCode.OK, session.execute(
        "CREATE TABLE program_failure (id BIGINT PRIMARY KEY, balance BIGINT)", command));
    PreparedOpenResult insert = new PreparedOpenResult();
    assertEquals(StatusCode.OK, session.prepare(
        "INSERT INTO program_failure VALUES (?,?)", insert));
    TransactionProgram program = new TransactionProgram();
    for (int step = 0; step < 2; step++) {
      assertEquals(StatusCode.OK, program.beginStep(
          insert.handle(), TransactionProgramAction.COMMAND));
      appendArgument(program, 0);
      appendArgument(program, 1);
      assertEquals(StatusCode.OK, program.endStep());
    }
    assertEquals(StatusCode.OK, program.freeze());
    ProgramOpenResult openedProgram = new ProgramOpenResult();
    assertEquals(StatusCode.OK, session.prepareProgram(program, openedProgram));
    TransactionProgramArguments arguments = new TransactionProgramArguments();
    assertEquals(StatusCode.OK, arguments.setFixed(0, SqlTypeDescriptor.BIGINT, 2));
    assertEquals(StatusCode.OK, arguments.setFixed(1, SqlTypeDescriptor.BIGINT, 200));
    TransactionProgramResult result = new TransactionProgramResult();
    long requests = client.completedRequests();

    assertEquals(StatusCode.UNIQUE_VIOLATION, session.executeProgram(
        openedProgram.handle(), IsolationLevel.REPEATABLE_READ, arguments, result));
    assertEquals(requests + 1, client.completedRequests());
    assertEquals(0, result.stepCount());
    assertEquals(1, result.failingStep());
    assertEquals(StatusCode.UNIQUE_VIOLATION, result.primaryStatus());
    assertEquals(StatusCode.OK,
        session.execute("SELECT COUNT(*) FROM program_failure", command));
    assertEquals(0, command.valueAt(0));
    assertEquals(StatusCode.OK, session.closeProgram(openedProgram.handle()));
    assertEquals(StatusCode.OK, session.closePrepared(insert.handle()));
    assertEquals(StatusCode.OK, session.close());
    assertEquals(StatusCode.OK, client.close());
    assertEquals(StatusCode.OK, server.close());
    assertEquals(StatusCode.OK, database.close());
  }

  @Test
  void admitsContinuedProgramResponseBeforeCommit(@TempDir Path root) {
    DatabaseOpenResult opened = new DatabaseOpenResult();
    assertEquals(StatusCode.OK, EmbeddedRiver.create(root, DATABASE, GENERATION, 8, opened));
    RiverDatabase database = opened.database();
    LoopbackRiverServer server = start(database);
    RiverClientConnection client = connect(server);
    SessionOpenResult sessionResult = new SessionOpenResult();
    assertEquals(StatusCode.OK, client.createSession(sessionResult));
    RiverSession session = sessionResult.session();
    CommandResult command = new CommandResult();
    assertEquals(StatusCode.OK, session.execute(
        "CREATE TABLE program_rows (id INTEGER PRIMARY KEY, payload VARCHAR(100))", command));
    String payload = "x".repeat(80);
    for (int first = 0; first < 300; first += 60) {
      StringBuilder sql = new StringBuilder("INSERT INTO program_rows VALUES ");
      for (int row = first; row < Math.min(300, first + 60); row++) {
        if (row > first) sql.append(',');
        sql.append('(').append(row).append(",'").append(payload).append("')");
      }
      assertEquals(StatusCode.OK, session.execute(sql.toString(), command));
    }
    PreparedOpenResult select = new PreparedOpenResult();
    assertEquals(StatusCode.OK, session.prepare(
        "SELECT id,payload FROM program_rows ORDER BY id", select));
    TransactionProgram program = new TransactionProgram();
    assertEquals(StatusCode.OK, program.beginStep(
        select.handle(), TransactionProgramAction.ROW_SET));
    assertEquals(StatusCode.OK, program.captureColumn(0));
    assertEquals(StatusCode.OK, program.captureColumn(1));
    assertEquals(StatusCode.OK, program.endStep());
    assertEquals(StatusCode.OK, program.freeze());
    ProgramOpenResult programOpen = new ProgramOpenResult();
    assertEquals(StatusCode.OK, session.prepareProgram(program, programOpen));
    long requests = client.completedRequests();
    TransactionProgramResult result = new TransactionProgramResult();

    assertEquals(StatusCode.OK, session.executeProgram(
        programOpen.handle(), IsolationLevel.REPEATABLE_READ,
        new TransactionProgramArguments(), result));
    assertEquals(requests + 1, client.completedRequests());
    assertEquals(300, result.rowCount());
    assertEquals(299, result.valueAt(299, 0));
    assertEquals(80, result.textLengthAt(299, 1));
    assertEquals(StatusCode.OK, session.closeProgram(programOpen.handle()));
    assertEquals(StatusCode.OK, session.closePrepared(select.handle()));
    assertEquals(StatusCode.OK, session.close());
    assertEquals(StatusCode.OK, client.close());
    assertEquals(StatusCode.OK, server.close());
    assertEquals(StatusCode.OK, database.close());
  }

  @Test
  void preservesCommittedOutcomeWhenCallerResultMemoryIsExhausted(@TempDir Path root) {
    DatabaseOpenResult opened = new DatabaseOpenResult();
    assertEquals(StatusCode.OK, EmbeddedRiver.create(root, DATABASE, GENERATION, 8, opened));
    RiverDatabase database = opened.database();
    LoopbackRiverServer server = start(database);
    RiverClientConnection client = connect(server);
    SessionOpenResult sessionResult = new SessionOpenResult();
    assertEquals(StatusCode.OK, client.createSession(sessionResult));
    RiverSession session = sessionResult.session();
    CommandResult command = new CommandResult();
    assertEquals(StatusCode.OK, session.execute(
        "CREATE TABLE result_pressure (id INTEGER PRIMARY KEY, balance BIGINT)", command));
    assertEquals(StatusCode.OK, session.execute(
        "INSERT INTO result_pressure VALUES (1,100)", command));
    PreparedOpenResult update = new PreparedOpenResult();
    assertEquals(StatusCode.OK, session.prepare(
        "UPDATE result_pressure SET balance=balance+1 WHERE id=1", update));
    TransactionProgram program = new TransactionProgram();
    assertEquals(StatusCode.OK, program.beginStep(
        update.handle(), TransactionProgramAction.COMMAND));
    assertEquals(StatusCode.OK, program.requireAffectedRows(1, 1));
    assertEquals(StatusCode.OK, program.endStep());
    assertEquals(StatusCode.OK, program.freeze());
    ProgramOpenResult programOpen = new ProgramOpenResult();
    assertEquals(StatusCode.OK, session.prepareProgram(program, programOpen));
    long requests = client.completedRequests();

    assertEquals(StatusCode.RESOURCE_EXHAUSTED, session.executeProgram(
        programOpen.handle(), IsolationLevel.REPEATABLE_READ,
        new TransactionProgramArguments(),
        new TransactionProgramResult(new DenyingLease())));
    assertEquals(requests + 1, client.completedRequests());
    assertEquals(StatusCode.OK,
        session.execute("SELECT balance FROM result_pressure WHERE id=1", command));
    assertEquals(101, command.valueAt(0));
    assertEquals(StatusCode.OK, session.closeProgram(programOpen.handle()));
    assertEquals(StatusCode.OK, session.closePrepared(update.handle()));
    assertEquals(StatusCode.OK, session.close());
    assertEquals(StatusCode.OK, client.close());
    assertEquals(StatusCode.OK, server.close());
    assertEquals(StatusCode.OK, database.close());
  }

  private static void appendArgument(TransactionProgram program, int slot) {
    assertEquals(StatusCode.OK, program.beginParameter());
    assertEquals(StatusCode.OK, program.argument(slot, SqlTypeDescriptor.BIGINT));
    assertEquals(StatusCode.OK, program.endExpression());
  }

  private static final class DenyingLease implements RetainedMemoryLease {
    @Override
    public StatusCode resize(long bytes) {
      return bytes == 0 ? StatusCode.OK : StatusCode.RESOURCE_EXHAUSTED;
    }

    @Override
    public StatusCode awaitResize(long bytes) { return resize(bytes); }

    @Override
    public long retainedBytes() { return 0; }
  }

  @Test
  void exposesRemoteDatabaseSessionAndQueryOverRealStorage(@TempDir Path root) {
    DatabaseOpenResult engineResult = new DatabaseOpenResult();
    assertEquals(
        StatusCode.OK,
        EmbeddedRiver.create(root, DATABASE, GENERATION, 8, engineResult));
    RiverDatabase engine = engineResult.database();
    LoopbackRiverServer server = start(engine);
    RiverClientConnection client = connect(server);
    SessionOpenResult sessionResult = new SessionOpenResult();
    assertEquals(StatusCode.OK, client.createSession(sessionResult));
    RiverSession session = sessionResult.session();
    CommandResult command = new CommandResult();
    QueryOpenResult queryResult = new QueryOpenResult();
    assertEquals(StatusCode.OK,
        session.execute(" ".repeat(20_000) + "SELECT 1", command));
    ParameterSet maximumParameters = new ParameterSet(ParameterSet.MAXIMUM_PARAMETERS, 0);
    assertEquals(StatusCode.OK,
        maximumParameters.reserve(ParameterSet.MAXIMUM_PARAMETERS, 0));
    for (int index = 0; index < ParameterSet.MAXIMUM_PARAMETERS; index++) {
      assertEquals(StatusCode.OK,
          maximumParameters.appendFixed(SqlTypeDescriptor.BIGINT, index));
    }
    assertEquals(StatusCode.PARAMETER_COUNT_MISMATCH,
        session.execute("SELECT ?", maximumParameters, command));
    assertEquals(
        StatusCode.INVALID_EXTERNAL_INPUT,
        session.execute("SELECT 1", null, command));
    assertEquals(
        StatusCode.INVALID_EXTERNAL_INPUT,
        session.beginQuery("SELECT 1", null, queryResult));
    assertEquals(
        StatusCode.OK,
        session.execute(
            "CREATE TABLE accounts "
                + "(id BIGINT PRIMARY KEY, balance BIGINT, region BIGINT)",
            command));
    assertEquals(
        StatusCode.OK,
        session.execute(
            "INSERT INTO accounts VALUES "
                + "(1, 100, 7), (2, 200, 7), (3, 300, 8)",
            command));
    assertEquals(3, command.affectedRows());
    ParameterSet account = new ParameterSet(3, 0);
    assertEquals(StatusCode.OK, account.appendFixed(SqlTypeDescriptor.BIGINT, 4));
    assertEquals(StatusCode.OK, account.appendFixed(SqlTypeDescriptor.BIGINT, 400));
    assertEquals(StatusCode.OK, account.appendFixed(SqlTypeDescriptor.BIGINT, 9));
    assertEquals(
        StatusCode.OK,
        session.execute("INSERT INTO accounts VALUES (?,?,?)", account, command));
    assertEquals(
        StatusCode.OK,
        session.execute(
            "CREATE TABLE labels "
                + "(id BIGINT PRIMARY KEY, name VARCHAR(7) NOT NULL)",
            command));
    assertEquals(
        StatusCode.OK,
        session.execute("INSERT INTO labels VALUES (1, 'beta'), (2, 'alpha')", command));
    assertEquals(
        StatusCode.OK,
        session.execute("CREATE UNIQUE INDEX labels_name ON labels(name)", command));
    ParameterSet labelName = new ParameterSet(1, 8);
    assertEquals(
        StatusCode.OK,
        labelName.appendText(SqlTypeDescriptor.varchar(7), "alpha"));
    assertEquals(
        StatusCode.OK,
        session.execute("SELECT id FROM labels WHERE name=?", labelName, command));
    labelName.reset();
    assertEquals(2, command.valueAt(0));
    ParameterSet wrongNull = new ParameterSet(1, 0);
    assertEquals(StatusCode.OK, wrongNull.appendNull(SqlTypeDescriptor.DATE));
    assertEquals(
        StatusCode.DATATYPE_MISMATCH,
        session.execute("UPDATE accounts SET balance=? WHERE id=1", wrongNull, command));
    assertEquals(
        StatusCode.PARAMETER_COUNT_MISMATCH,
        session.execute(
            "SELECT id FROM labels WHERE id=?", new ParameterSet(0, 0), command));
    assertEquals(
        StatusCode.OK,
        session.execute("SELECT name FROM labels WHERE name='alpha'", command));
    assertText(command, 0, "alpha");

    assertEquals(
        StatusCode.OK,
        session.beginQuery("SELECT name FROM labels ORDER BY name", queryResult));
    RiverQuery query = queryResult.query();
    assertTrue(query.columnIsVarchar(0));
    assertEquals(SqlTypeDescriptor.varchar(7), query.columnTypeDescriptor(0));
    assertEquals(
        StatusCode.CONFLICT,
        session.execute("SELECT 1", null, command));
    assertFalse(command.rowAvailable());
    RowResult row = new RowResult();
    assertEquals(StatusCode.OK, query.next(row));
    assertText(row, 0, "alpha");
    assertEquals(StatusCode.OK, query.next(row));
    assertText(row, 0, "beta");
    assertEquals(StatusCode.OK, query.next(row));
    assertFalse(row.isAvailable());
    assertEquals(StatusCode.OK, query.close(command));
    ParameterSet accountKey = new ParameterSet(1, 0);
    assertEquals(StatusCode.OK, accountKey.appendFixed(SqlTypeDescriptor.BIGINT, 4));
    assertEquals(
        StatusCode.OK,
        session.beginQuery(
            "SELECT balance FROM accounts WHERE id=?", accountKey, queryResult));
    accountKey.reset();
    query = queryResult.query();
    assertEquals(StatusCode.OK, query.next(row));
    assertEquals(400, row.valueAt(0));
    assertEquals(StatusCode.OK, query.close(command));
    assertEquals(
        StatusCode.OK,
        session.beginQuery(
            "SELECT id, balance FROM accounts WHERE id >= 1 AND id < 4",
            queryResult));
    query = queryResult.query();
    assertEquals(2, query.columnCount());
    assertEquals("id", query.columnName(0));
    assertEquals("balance", query.columnName(1));
    assertRow(query, row, 1, 1, 100);
    assertRow(query, row, 2, 2, 200);
    assertRow(query, row, 3, 3, 300);
    assertEquals(StatusCode.OK, query.next(row));
    assertFalse(row.isAvailable());
    assertEquals(3, query.rowsReturned());
    assertEquals(StatusCode.OK, query.close(command));
    assertEquals(StatusCode.CONFLICT, client.close());
    assertEquals(StatusCode.OK, session.close());
    assertEquals(
        StatusCode.CLOSED,
        session.execute("SELECT 1", null, command));
    assertFalse(command.rowAvailable());
    assertEquals(0, command.columnCount());
    assertEquals(
        StatusCode.CLOSED,
        session.beginQuery("SELECT 1", null, queryResult));
    assertEquals(null, queryResult.query());
    assertEquals(StatusCode.OK, client.close());
    assertTrue(client.completedRequests() >= 10);
    assertTrue(client.bytesSent() > 0);
    assertTrue(client.bytesReceived() > 0);
    assertEquals(StatusCode.OK, server.close());
    assertEquals(StatusCode.OK, engine.close());

    assertEquals(
        StatusCode.OK,
        EmbeddedRiver.openExisting(root, DATABASE, GENERATION, 8, engineResult));
    engine = engineResult.database();
    server = start(engine);
    client = connect(server);
    assertEquals(StatusCode.OK, client.createSession(sessionResult));
    session = sessionResult.session();
    assertEquals(
        StatusCode.OK,
        session.execute("SELECT balance FROM accounts WHERE id=2", command));
    assertTrue(command.rowAvailable());
    assertEquals(200, command.valueAt(0));
    assertEquals(StatusCode.OK, session.close());
    assertEquals(StatusCode.OK, client.close());
    assertEquals(StatusCode.OK, server.close());
    assertEquals(StatusCode.OK, engine.close());
  }

  private static void assertText(CommandResult result, int column, String expected) {
    char[] characters = new char[7];
    assertTrue(result.isVarchar(column));
    assertEquals(expected.length(), result.copyTextAt(column, characters, 0));
    assertEquals(expected, new String(characters, 0, expected.length()));
  }

  private static void assertText(RowResult result, int column, String expected) {
    char[] characters = new char[7];
    assertTrue(result.isVarchar(column));
    assertEquals(expected.length(), result.copyTextAt(column, characters, 0));
    assertEquals(expected, new String(characters, 0, expected.length()));
  }

  @Test
  void serverDisconnectFencesTheClientAndReleasesItsTransaction(@TempDir Path root) {
    DatabaseOpenResult engineResult = new DatabaseOpenResult();
    assertEquals(
        StatusCode.OK,
        EmbeddedRiver.create(root, DATABASE, GENERATION, 4, engineResult));
    RiverDatabase engine = engineResult.database();
    LoopbackRiverServer server = start(engine);
    RiverClientConnection client = connect(server);
    SessionOpenResult sessionResult = new SessionOpenResult();
    assertEquals(StatusCode.OK, client.createSession(sessionResult));
    RiverSession session = sessionResult.session();
    CommandResult command = new CommandResult();
    assertEquals(StatusCode.OK, session.execute("CREATE TABLE ledger", command));
    assertEquals(StatusCode.OK, session.execute("BEGIN", command));
    assertEquals(
        StatusCode.OK,
        session.execute("INSERT INTO ledger VALUES (9, 900)", command));

    assertEquals(StatusCode.OK, server.close());
    assertEquals(0, server.activeConnections());
    assertEquals(
        StatusCode.IO_FAILURE,
        session.execute("INSERT INTO ledger VALUES (10, 1000)", command));
    assertEquals(StatusCode.IO_FAILURE, client.lastStatus());
    assertEquals(StatusCode.CLOSED, session.execute("COMMIT", command));
    assertEquals(StatusCode.CLOSED, client.close());

    server = start(engine);
    client = connect(server);
    assertEquals(StatusCode.OK, client.createSession(sessionResult));
    session = sessionResult.session();
    assertEquals(
        StatusCode.CONFLICT,
        session.execute("SELECT value FROM ledger WHERE key=9", command));
    assertEquals(StatusCode.OK, session.close());
    assertEquals(StatusCode.OK, client.close());
    assertEquals(StatusCode.OK, server.close());
    assertEquals(StatusCode.OK, engine.close());
  }

  @Test
  void twoRemoteSessionsRemainUsableTogether(@TempDir Path root) {
    DatabaseOpenResult engineResult = new DatabaseOpenResult();
    assertEquals(
        StatusCode.OK,
        EmbeddedRiver.create(root, DATABASE, GENERATION, 8, engineResult));
    RiverDatabase engine = engineResult.database();
    LoopbackRiverServer server = start(engine, 2);
    RiverClientConnection first = connect(server);
    RiverClientConnection second = connect(server);
    SessionOpenResult firstResult = new SessionOpenResult();
    SessionOpenResult secondResult = new SessionOpenResult();
    assertEquals(StatusCode.OK, first.createSession(firstResult));
    assertEquals(StatusCode.OK, second.createSession(secondResult));
    RiverSession firstSession = firstResult.session();
    RiverSession secondSession = secondResult.session();
    CommandResult command = new CommandResult();

    assertEquals(
        StatusCode.OK,
        firstSession.execute("CREATE TABLE concurrent_sessions", command));
    assertEquals(
        StatusCode.OK,
        firstSession.execute(
            "INSERT INTO concurrent_sessions VALUES (1, 100)", command));
    assertEquals(
        StatusCode.OK,
        secondSession.execute(
            "INSERT INTO concurrent_sessions VALUES (2, 200)", command));
    assertEquals(
        StatusCode.OK,
        firstSession.execute(
            "SELECT value FROM concurrent_sessions WHERE key=2", command));
    assertEquals(200, command.valueAt(0));
    assertEquals(
        StatusCode.OK,
        secondSession.execute(
            "SELECT value FROM concurrent_sessions WHERE key=1", command));
    assertEquals(100, command.valueAt(0));
    assertEquals(2, server.activeConnections());

    assertEquals(StatusCode.OK, firstSession.close());
    assertEquals(StatusCode.OK, secondSession.close());
    assertEquals(StatusCode.OK, first.close());
    assertEquals(StatusCode.OK, second.close());
    assertEquals(StatusCode.OK, server.close());
    assertEquals(0, server.activeConnections());
    assertEquals(StatusCode.OK, engine.close());
  }

  @Test
  void rejectsCorruptPeerHeadersBeforeReadingTheirPayload() throws Exception {
    assertEquals(StatusCode.CORRUPTION, connectToCorruptResponse(1));
    assertEquals(StatusCode.CORRUPTION, connectToCorruptResponse(2));
    assertEquals(StatusCode.CORRUPTION, connectToCorruptResponse(3));
    assertEquals(StatusCode.IO_FAILURE, connectToCorruptResponse(4));
    assertEquals(StatusCode.CORRUPTION, connectToCorruptResponse(5));
    assertEquals(StatusCode.CORRUPTION, connectToCorruptResponse(6));
  }

  @Test
  void rejectsFetchRowsWithImpossibleSequenceBeforePublication() throws Exception {
    AtomicReference<Throwable> serverFailure = new AtomicReference<>();
    try (ServerSocket server = new ServerSocket()) {
      server.bind(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0));
      Thread responder = Thread.ofPlatform().start(() -> {
        try (Socket connection = server.accept()) {
          InputStream input = connection.getInputStream();
          OutputStream output = connection.getOutputStream();
          ByteBuffer response = ByteBuffer.allocate(
              ProtocolFrameCodec.MAXIMUM_RESPONSE_BYTES);
          ProtocolFrameCodec codec = new ProtocolFrameCodec();
          expectRequest(input, ProtocolMessageType.HELLO, 1);
          assertEncoded(codec.encodeHelloResponse(
              response, 1, StatusCode.OK, 0, 0));
          writeResponse(output, response);
          expectRequest(input, ProtocolMessageType.OPEN_SESSION, 2);
          assertEncoded(codec.encodeStatusResponse(
              response,
              ProtocolMessageType.OPEN_SESSION,
              2,
              StatusCode.OK,
              false));
          writeResponse(output, response);
          expectRequest(input, ProtocolMessageType.BEGIN_QUERY, 3);
          OneColumnQuery metadataQuery = new OneColumnQuery(SqlTypeDescriptor.timestamp(6));
          ProtocolQueryMetadata metadata = new ProtocolQueryMetadata();
          assertEncoded(metadata.capture(metadataQuery));
          RowResult first = new RowResult();
          assertEncoded(first.complete(
              1,
              new long[] {0},
              0,
              new int[] {SqlTypeDescriptor.timestamp(6)},
              1));
          assertEncoded(codec.encodeQueryOpenResponse(
              response,
              ProtocolMessageType.BEGIN_QUERY,
              3,
              StatusCode.OK,
              metadata,
              first,
              1,
              null,
              true));
          writeResponse(output, response);
          expectRequest(input, ProtocolMessageType.FETCH, 4);
          RowResult row = new RowResult();
          assertEncoded(row.complete(
              1,
              new long[] {0},
              0,
              new int[] {SqlTypeDescriptor.timestamp(6)},
              1));
          assertEncoded(codec.encodeRowResponse(
              response,
              ProtocolMessageType.FETCH,
              4,
              StatusCode.OK,
              row,
              Long.MAX_VALUE,
              null,
              true));
          writeResponse(output, response);
        } catch (Throwable failure) {
          serverFailure.set(failure);
        }
      });

      RiverClientOpenResult clientResult = new RiverClientOpenResult();
      assertEquals(
          StatusCode.OK,
          RiverClientConnection.connectLoopback(server.getLocalPort(), clientResult));
      RiverClientConnection client = clientResult.connection();
      SessionOpenResult sessionResult = new SessionOpenResult();
      assertEquals(StatusCode.OK, client.createSession(sessionResult));
      QueryOpenResult queryResult = new QueryOpenResult();
      assertEquals(
          StatusCode.OK,
          sessionResult.session().beginQuery("SELECT captured", queryResult));
      RiverQuery query = queryResult.query();
      assertEquals(SqlTypeDescriptor.timestamp(6), query.columnTypeDescriptor(0));
      assertEquals(StatusCode.OK, query.next(new RowResult()));
      assertEquals(StatusCode.CORRUPTION, query.next(new RowResult()));
      assertEquals(StatusCode.CORRUPTION, client.lastStatus());
      assertEquals(StatusCode.CLOSED, query.next(new RowResult()));
      responder.join(2_000);
      assertFalse(responder.isAlive());
      if (serverFailure.get() != null) {
        throw new AssertionError(serverFailure.get());
      }
    }
  }

  @Test
  void preservesEveryNumericDescriptorAndCanonicalLaneAcrossTheClient() throws Exception {
    AtomicReference<Throwable> serverFailure = new AtomicReference<>();
    try (ServerSocket server = new ServerSocket()) {
      server.bind(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0));
      Thread responder = Thread.ofPlatform().start(() -> {
        try (Socket connection = server.accept()) {
          InputStream input = connection.getInputStream();
          OutputStream output = connection.getOutputStream();
          ByteBuffer response = ByteBuffer.allocate(ProtocolFrameCodec.MAXIMUM_RESPONSE_BYTES);
          ProtocolFrameCodec codec = new ProtocolFrameCodec();
          expectRequest(input, ProtocolMessageType.HELLO, 1);
          assertEncoded(codec.encodeHelloResponse(response, 1, StatusCode.OK, 0, 0));
          writeResponse(output, response);
          expectRequest(input, ProtocolMessageType.OPEN_SESSION, 2);
          assertEncoded(codec.encodeStatusResponse(
              response, ProtocolMessageType.OPEN_SESSION, 2, StatusCode.OK, false));
          writeResponse(output, response);
          expectRequest(input, ProtocolMessageType.EXECUTE, 3);
          CommandResult command = new CommandResult();
          long[] values = {
              -7,
              80_000,
              Long.MAX_VALUE,
              12_345,
              SqlApproximateNumeric.realBits(1.5f),
              SqlApproximateNumeric.doubleBits(-2.25d)
          };
          int[] types = {
              SqlTypeDescriptor.SMALLINT,
              SqlTypeDescriptor.INTEGER,
              SqlTypeDescriptor.BIGINT,
              SqlTypeDescriptor.decimal(6, 2),
              SqlTypeDescriptor.REAL,
              SqlTypeDescriptor.DOUBLE
          };
          assertEncoded(command.complete(
              1, 0, false, true, 0, values, 0, types, values.length));
          assertEncoded(codec.encodeCommandResponse(
              response, ProtocolMessageType.EXECUTE, 3, StatusCode.OK, command, false));
          writeResponse(output, response);
          expectRequest(input, ProtocolMessageType.CLOSE_SESSION, 4);
          assertEncoded(codec.encodeStatusResponse(
              response, ProtocolMessageType.CLOSE_SESSION, 4, StatusCode.OK, false));
          writeResponse(output, response);
        } catch (Throwable failure) {
          serverFailure.set(failure);
        }
      });

      RiverClientOpenResult clientResult = new RiverClientOpenResult();
      assertEquals(StatusCode.OK,
          RiverClientConnection.connectLoopback(server.getLocalPort(), clientResult));
      RiverClientConnection client = clientResult.connection();
      SessionOpenResult sessionResult = new SessionOpenResult();
      assertEquals(StatusCode.OK, client.createSession(sessionResult));
      CommandResult command = new CommandResult();
      assertEquals(StatusCode.OK, sessionResult.session().execute("SELECT numerics", command));
      assertEquals((short) -7, command.smallintAt(0));
      assertEquals(80_000, command.integerAt(1));
      assertEquals(Long.MAX_VALUE, command.bigintAt(2));
      assertEquals(12_345, command.decimalUnscaledAt(3));
      assertEquals(1.5f, command.realAt(4));
      assertEquals(-2.25d, command.doubleAt(5));
      assertEquals(StatusCode.OK, sessionResult.session().close());
      assertEquals(StatusCode.OK, client.close());
      responder.join(2_000);
      assertFalse(responder.isAlive());
      if (serverFailure.get() != null) throw new AssertionError(serverFailure.get());
    }
  }

  private static StatusCode connectToCorruptResponse(int corruption) throws Exception {
    AtomicReference<Throwable> serverFailure = new AtomicReference<>();
    try (ServerSocket server = new ServerSocket()) {
      server.bind(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0));
      Thread responder = Thread.ofPlatform().start(() -> {
        try (Socket connection = server.accept()) {
          byte[] requestHeader = new byte[ProtocolFrameCodec.HEADER_BYTES];
          if (!readExact(connection.getInputStream(), requestHeader, requestHeader.length)) {
            throw new IOException("client request header was truncated");
          }
          ByteBuffer response = ByteBuffer.allocate(ProtocolFrameCodec.MAXIMUM_RESPONSE_BYTES);
          ProtocolFrameCodec codec = new ProtocolFrameCodec();
          if (corruption == 1) {
            codec.encodeRequest(response, ProtocolMessageType.HELLO, 1);
          } else {
            codec.encodeHelloResponse(response, 1, StatusCode.OK, 0, 0);
            if (corruption == 2) {
              response.putInt(4, ProtocolFrameCodec.VERSION + 1);
            } else if (corruption == 3) {
              response.putInt(24, ProtocolFrameCodec.MAXIMUM_RESPONSE_BYTES);
            } else if (corruption == 5) {
              response.putInt(8, ProtocolMessageType.FETCH.wireCode());
            } else if (corruption == 6) {
              response.putLong(16, 2);
            }
          }
          int bytes = corruption == 4
              ? ProtocolFrameCodec.HEADER_BYTES - 1 : ProtocolFrameCodec.HEADER_BYTES;
          connection.getOutputStream().write(response.array(), 0, bytes);
          connection.getOutputStream().flush();
        } catch (Throwable failure) {
          serverFailure.set(failure);
        }
      });
      RiverClientOpenResult result = new RiverClientOpenResult();
      StatusCode status = RiverClientConnection.connectLoopback(server.getLocalPort(), result);
      responder.join(2_000);
      assertFalse(responder.isAlive());
      if (serverFailure.get() != null) {
        throw new AssertionError(serverFailure.get());
      }
      return status;
    }
  }

  private static boolean readExact(InputStream input, byte[] target, int length)
      throws IOException {
    int read = 0;
    while (read < length) {
      int count = input.read(target, read, length - read);
      if (count < 0) {
        return false;
      }
      read += count;
    }
    return true;
  }

  private static void expectRequest(
      InputStream input, ProtocolMessageType type, long requestId)
      throws IOException {
    byte[] header = new byte[ProtocolFrameCodec.HEADER_BYTES];
    if (!readExact(input, header, header.length)) {
      throw new IOException("client request header was truncated");
    }
    ByteBuffer bytes = ByteBuffer.wrap(header);
    if (bytes.getInt(8) != type.wireCode()
        || bytes.getLong(16) != requestId) {
      throw new IOException("client request header did not match the script");
    }
    int payloadBytes = bytes.getInt(24);
    if (payloadBytes < 0
        || payloadBytes > ProtocolFrameCodec.MAXIMUM_PAYLOAD_BYTES) {
      throw new IOException("client request payload was invalid");
    }
    if (payloadBytes > 0
        && !readExact(input, new byte[payloadBytes], payloadBytes)) {
      throw new IOException("client request payload was truncated");
    }
  }

  private static void writeResponse(OutputStream output, ByteBuffer response)
      throws IOException {
    output.write(response.array(), response.position(), response.remaining());
    output.flush();
  }

  private static void assertEncoded(StatusCode status) throws IOException {
    if (!status.isOk()) throw new IOException("script response encoding failed");
  }

  private static final class OneColumnQuery implements RiverQuery {
    private final int descriptor;

    private OneColumnQuery(int typeDescriptor) {
      descriptor = typeDescriptor;
    }

    @Override
    public StatusCode next(RowResult result) {
      return StatusCode.CONFLICT;
    }

    @Override
    public StatusCode close(CommandResult result) {
      return StatusCode.OK;
    }

    @Override
    public boolean isActive() {
      return true;
    }

    @Override
    public int columnCount() {
      return 1;
    }

    @Override
    public CharSequence columnName(int index) {
      return index == 0 ? "captured" : null;
    }

    @Override
    public int columnTypeDescriptor(int index) {
      return index == 0 ? descriptor : 0;
    }

    @Override
    public boolean columnIsNullable(int index) {
      return false;
    }

    @Override
    public long rowsReturned() {
      return 0;
    }
  }

  private static LoopbackRiverServer start(RiverDatabase database) {
    return start(database, LoopbackRiverServer.DEFAULT_MAXIMUM_CONNECTIONS);
  }

  private static LoopbackRiverServer start(
      RiverDatabase database,
      int maximumConnections) {
    LoopbackServerOpenResult result = new LoopbackServerOpenResult();
    assertEquals(
        StatusCode.OK,
        LoopbackRiverServer.start(database, 0, maximumConnections, result));
    return result.server();
  }

  private static RiverClientConnection connect(LoopbackRiverServer server) {
    RiverClientOpenResult result = new RiverClientOpenResult();
    assertEquals(StatusCode.OK, RiverClientConnection.connectLoopback(server.port(), result));
    return result.connection();
  }

  private static void assertRow(
      RiverQuery query,
      RowResult row,
      long key,
      long first,
      long second) {
    assertEquals(StatusCode.OK, query.next(row));
    assertTrue(row.isAvailable());
    assertEquals(key, row.key());
    assertEquals(2, row.columnCount());
    assertEquals(first, row.valueAt(0));
    assertEquals(second, row.valueAt(1));
  }
}
