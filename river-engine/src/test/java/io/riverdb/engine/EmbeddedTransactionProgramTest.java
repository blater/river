package io.riverdb.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.riverdb.base.error.StatusCode;
import io.riverdb.base.id.DatabaseIncarnation;
import io.riverdb.base.id.WalGeneration;
import io.riverdb.base.type.SqlTypeDescriptor;
import io.riverdb.engine.api.CommandResult;
import io.riverdb.engine.api.PreparedOpenResult;
import io.riverdb.engine.api.ProgramOpenResult;
import io.riverdb.engine.api.RiverDatabase;
import io.riverdb.engine.api.RiverSession;
import io.riverdb.engine.api.SessionOpenResult;
import io.riverdb.engine.api.TransactionProgram;
import io.riverdb.engine.api.TransactionProgramAction;
import io.riverdb.engine.api.TransactionProgramArguments;
import io.riverdb.engine.api.TransactionProgramResult;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class EmbeddedTransactionProgramTest {
  private static final DatabaseIncarnation DATABASE = DatabaseIncarnation.of(1_201, 1_203);
  private static final WalGeneration GENERATION = WalGeneration.of(1);

  @Test
  void executesPreparedDataflowAndCommitsOnce(@TempDir Path root) {
    Fixture fixture = open(root);
    long insert = fixture.prepare("INSERT INTO account VALUES (?,?)");
    long read = fixture.prepare("SELECT balance FROM account WHERE id=?");
    long update = fixture.prepare(
        "UPDATE account SET balance=balance+? WHERE id=?");
    TransactionProgram program = new TransactionProgram();
    command(program, insert, 0, SqlTypeDescriptor.INTEGER, 1, SqlTypeDescriptor.BIGINT);
    query(program, read, 0, SqlTypeDescriptor.INTEGER);
    command(program, update, 2, SqlTypeDescriptor.BIGINT, 0, SqlTypeDescriptor.INTEGER);
    query(program, read, 0, SqlTypeDescriptor.INTEGER);
    assertEquals(StatusCode.OK, program.freeze());
    TransactionProgramArguments arguments = new TransactionProgramArguments();
    assertEquals(StatusCode.OK, arguments.setFixed(0, SqlTypeDescriptor.INTEGER, 7));
    assertEquals(StatusCode.OK, arguments.setFixed(1, SqlTypeDescriptor.BIGINT, 100));
    assertEquals(StatusCode.OK, arguments.setFixed(2, SqlTypeDescriptor.BIGINT, 25));
    TransactionProgramResult result = new TransactionProgramResult();
    long programHandle = fixture.prepareProgram(program);

    assertEquals(StatusCode.OK,
        fixture.session.executeProgram(programHandle, arguments, result));
    assertEquals(4, result.stepCount());
    assertEquals(100, result.valueAt(result.firstRow(1), 0));
    assertEquals(125, result.valueAt(result.firstRow(3), 0));
    assertTrue(result.commitSequence() > 0);
    fixture.close();
  }

  @Test
  void rollsBackTheWholeProgramOnStepFailure(@TempDir Path root) {
    Fixture fixture = open(root);
    long insert = fixture.prepare("INSERT INTO account VALUES (?,?)");
    TransactionProgram program = new TransactionProgram();
    command(program, insert, 0, SqlTypeDescriptor.INTEGER, 1, SqlTypeDescriptor.BIGINT);
    command(program, insert, 0, SqlTypeDescriptor.INTEGER, 1, SqlTypeDescriptor.BIGINT);
    assertEquals(StatusCode.OK, program.freeze());
    TransactionProgramArguments arguments = new TransactionProgramArguments();
    assertEquals(StatusCode.OK, arguments.setFixed(0, SqlTypeDescriptor.INTEGER, 9));
    assertEquals(StatusCode.OK, arguments.setFixed(1, SqlTypeDescriptor.BIGINT, 90));
    TransactionProgramResult result = new TransactionProgramResult();
    long programHandle = fixture.prepareProgram(program);

    assertEquals(StatusCode.UNIQUE_VIOLATION,
        fixture.session.executeProgram(programHandle, arguments, result));
    assertEquals(1, result.failingStep());
    assertEquals(StatusCode.UNIQUE_VIOLATION, result.primaryStatus());
    CommandResult count = new CommandResult();
    assertEquals(StatusCode.OK,
        fixture.session.execute("SELECT COUNT(*) FROM account", count));
    assertEquals(0, count.valueAt(0));
    fixture.close();
  }

  @Test
  void rollsBackBeforeCommitWhenResultPublicationCannotBeAdmitted(@TempDir Path root) {
    Fixture fixture = open(root);
    long insert = fixture.prepare("INSERT INTO account VALUES (?,?)");
    TransactionProgram program = new TransactionProgram();
    command(program, insert, 0, SqlTypeDescriptor.INTEGER, 1, SqlTypeDescriptor.BIGINT);
    assertEquals(StatusCode.OK, program.freeze());
    long programHandle = fixture.prepareProgram(program);
    TransactionProgramArguments arguments = new TransactionProgramArguments();
    assertEquals(StatusCode.OK, arguments.setFixed(0, SqlTypeDescriptor.INTEGER, 11));
    assertEquals(StatusCode.OK, arguments.setFixed(1, SqlTypeDescriptor.BIGINT, 110));
    TransactionProgramResult result = new TransactionProgramResult(
        io.riverdb.engine.api.RetainedMemoryLease.unbounded(),
        ignored -> StatusCode.RESOURCE_EXHAUSTED);

    assertEquals(StatusCode.RESOURCE_EXHAUSTED,
        fixture.session.executeProgram(programHandle, arguments, result));
    assertEquals(StatusCode.RESOURCE_EXHAUSTED, result.primaryStatus());
    assertEquals(StatusCode.OK, result.rollbackStatus());
    CommandResult count = new CommandResult();
    assertEquals(StatusCode.OK, fixture.session.execute("SELECT COUNT(*) FROM account", count));
    assertEquals(0, count.valueAt(0));
    fixture.close();
  }

  @Test
  void rollsBackMutatingProgramWhenLargeResultExceedsAdmissionBudget(@TempDir Path root) {
    Fixture fixture = open(root);
    int payload = SqlTypeDescriptor.varchar(1_800);
    assertEquals(StatusCode.OK, fixture.session.execute(
        "CREATE TABLE payload_account (id INTEGER PRIMARY KEY,payload VARCHAR(1800))",
        new CommandResult()));
    long insert = fixture.prepare("INSERT INTO payload_account VALUES (?,?)");
    long read = fixture.prepare(
        "SELECT payload FROM payload_account WHERE id=?");
    TransactionProgram program = new TransactionProgram();
    command(program, insert, 0, SqlTypeDescriptor.INTEGER, 1, payload);
    query(program, read, 2, SqlTypeDescriptor.INTEGER);
    assertEquals(StatusCode.OK, program.freeze());
    TransactionProgramArguments arguments = new TransactionProgramArguments();
    assertEquals(StatusCode.OK, arguments.setFixed(0, SqlTypeDescriptor.INTEGER, 11));
    assertEquals(StatusCode.OK, arguments.setText(1, payload, "x".repeat(1_800)));
    assertEquals(StatusCode.OK, arguments.setFixed(2, SqlTypeDescriptor.INTEGER, 11));
    int[] publishedCharacters = {0};
    TransactionProgramResult result = new TransactionProgramResult(
        io.riverdb.engine.api.RetainedMemoryLease.unbounded(), candidate -> {
          publishedCharacters[0] = candidate.textLengthAt(candidate.firstRow(1), 0);
          return publishedCharacters[0] > 1_024
              ? StatusCode.RESOURCE_EXHAUSTED : StatusCode.OK;
        });

    assertEquals(StatusCode.RESOURCE_EXHAUSTED, fixture.session.executeProgram(
        fixture.prepareProgram(program), arguments, result));
    assertTrue(publishedCharacters[0] > 1_024);
    assertEquals(StatusCode.RESOURCE_EXHAUSTED, result.primaryStatus());
    assertEquals(StatusCode.OK, result.rollbackStatus());
    CommandResult count = new CommandResult();
    assertEquals(StatusCode.OK, fixture.session.execute(
        "SELECT COUNT(*) FROM payload_account", count));
    assertEquals(0, count.valueAt(0));
    fixture.close();
  }

  @Test
  void commitsContinuationSizedResultExactlyOnce(@TempDir Path root) {
    Fixture fixture = open(root);
    int payload = SqlTypeDescriptor.varchar(1_800);
    assertEquals(StatusCode.OK, fixture.session.execute(
        "CREATE TABLE payload_account (id INTEGER PRIMARY KEY,payload VARCHAR(1800))",
        new CommandResult()));
    long insert = fixture.prepare("INSERT INTO payload_account VALUES (?,?)");
    long read = fixture.prepare(
        "SELECT payload FROM payload_account WHERE id=?");
    TransactionProgram program = new TransactionProgram();
    command(program, insert, 0, SqlTypeDescriptor.INTEGER, 1, payload);
    query(program, read, 2, SqlTypeDescriptor.INTEGER);
    assertEquals(StatusCode.OK, program.freeze());
    TransactionProgramArguments arguments = new TransactionProgramArguments();
    assertEquals(StatusCode.OK, arguments.setFixed(0, SqlTypeDescriptor.INTEGER, 12));
    assertEquals(StatusCode.OK, arguments.setText(1, payload, "x".repeat(1_800)));
    assertEquals(StatusCode.OK, arguments.setFixed(2, SqlTypeDescriptor.INTEGER, 12));
    int[] admissions = {0};
    TransactionProgramResult result = new TransactionProgramResult(
        io.riverdb.engine.api.RetainedMemoryLease.unbounded(), candidate -> {
          admissions[0]++;
          return StatusCode.OK;
        });

    assertEquals(StatusCode.OK, fixture.session.executeProgram(
        fixture.prepareProgram(program), arguments, result));
    assertEquals(1, admissions[0]);
    assertTrue(result.commitSequence() > 0);
    assertEquals(1_800, result.textLengthAt(result.firstRow(1), 0));
    CommandResult count = new CommandResult();
    assertEquals(StatusCode.OK, fixture.session.execute(
        "SELECT COUNT(*) FROM payload_account", count));
    assertEquals(1, count.valueAt(0));
    fixture.close();
  }

  @Test
  void rollsBackWhenCommandAffectedRowsViolateItsContract(@TempDir Path root) {
    Fixture fixture = open(root);
    long update = fixture.prepare("UPDATE account SET balance=balance+1 WHERE id=7");
    long insert = fixture.prepare("INSERT INTO account VALUES (?,?)");
    TransactionProgram program = new TransactionProgram();
    assertEquals(StatusCode.OK,
        program.beginStep(update, TransactionProgramAction.COMMAND));
    assertEquals(StatusCode.OK, program.requireAffectedRows(1, 1));
    assertEquals(StatusCode.OK, program.endStep());
    command(program, insert, 0, SqlTypeDescriptor.INTEGER, 1, SqlTypeDescriptor.BIGINT);
    assertEquals(StatusCode.OK, program.freeze());
    TransactionProgramArguments arguments = new TransactionProgramArguments();
    assertEquals(StatusCode.OK, arguments.setFixed(0, SqlTypeDescriptor.INTEGER, 7));
    assertEquals(StatusCode.OK, arguments.setFixed(1, SqlTypeDescriptor.BIGINT, 70));
    TransactionProgramResult result = new TransactionProgramResult();

    assertEquals(StatusCode.CARDINALITY_VIOLATION, fixture.session.executeProgram(
        fixture.prepareProgram(program), arguments, result));
    assertEquals(0, result.failingStep());
    assertEquals(StatusCode.CARDINALITY_VIOLATION, result.primaryStatus());
    CommandResult count = new CommandResult();
    assertEquals(StatusCode.OK, fixture.session.execute("SELECT COUNT(*) FROM account", count));
    assertEquals(0, count.valueAt(0));
    fixture.close();
  }

  @Test
  void closesSingletonScanBeforeRollingBackItsCardinalityFailure(@TempDir Path root) {
    Fixture fixture = open(root);
    assertEquals(StatusCode.OK, fixture.session.execute(
        "INSERT INTO account VALUES (1,10),(2,20)", new CommandResult()));
    long insert = fixture.prepare("INSERT INTO account VALUES (?,?)");
    long multiple = fixture.prepare("SELECT balance FROM account ORDER BY id");
    TransactionProgram program = new TransactionProgram();
    command(program, insert, 0, SqlTypeDescriptor.INTEGER, 1, SqlTypeDescriptor.BIGINT);
    assertEquals(StatusCode.OK,
        program.beginStep(multiple, TransactionProgramAction.EXACT_ONE));
    assertEquals(StatusCode.OK, program.endStep());
    assertEquals(StatusCode.OK, program.freeze());
    TransactionProgramArguments arguments = new TransactionProgramArguments();
    assertEquals(StatusCode.OK, arguments.setFixed(0, SqlTypeDescriptor.INTEGER, 3));
    assertEquals(StatusCode.OK, arguments.setFixed(1, SqlTypeDescriptor.BIGINT, 30));
    TransactionProgramResult result = new TransactionProgramResult();

    assertEquals(StatusCode.CARDINALITY_VIOLATION, fixture.session.executeProgram(
        fixture.prepareProgram(program), arguments, result));
    assertEquals(1, result.failingStep());
    assertEquals(StatusCode.OK, result.rollbackStatus());
    assertTrue(!result.sessionFenced());
    CommandResult count = new CommandResult();
    assertEquals(StatusCode.OK, fixture.session.execute("SELECT COUNT(*) FROM account", count));
    assertEquals(2, count.valueAt(0));
    fixture.close();
  }

  @Test
  void retainsCanonicalGraphAndPreparedPlanUntilProgramClose(@TempDir Path root) {
    Fixture fixture = open(root);
    long insert = fixture.prepare("INSERT INTO account VALUES (?,?)");
    TransactionProgram source = new TransactionProgram();
    command(source, insert, 0, SqlTypeDescriptor.INTEGER, 1, SqlTypeDescriptor.BIGINT);
    assertEquals(StatusCode.OK, source.freeze());
    long program = fixture.prepareProgram(source);
    source.reset();
    assertEquals(StatusCode.CONFLICT, fixture.session.closePrepared(insert));
    TransactionProgramArguments arguments = new TransactionProgramArguments();
    assertEquals(StatusCode.OK, arguments.setFixed(0, SqlTypeDescriptor.INTEGER, 19));
    assertEquals(StatusCode.OK, arguments.setFixed(1, SqlTypeDescriptor.BIGINT, 190));

    assertEquals(StatusCode.INVALID_EXTERNAL_INPUT, fixture.session.executePrepared(
        program, new io.riverdb.engine.api.ParameterSet(0, 0), new CommandResult()));
    assertEquals(StatusCode.INVALID_EXTERNAL_INPUT, fixture.session.executeProgram(
        insert, arguments, new TransactionProgramResult()));
    assertEquals(StatusCode.OK, fixture.session.executeProgram(
        program, arguments, new TransactionProgramResult()));
    assertEquals(StatusCode.OK, fixture.session.closeProgram(program));
    assertEquals(StatusCode.OK, fixture.session.closePrepared(insert));
    fixture.close();
  }

  @Test
  void rejectsProgramAfterCatalogInvalidationBeforeBeginningTransaction(@TempDir Path root) {
    Fixture fixture = open(root);
    long insert = fixture.prepare("INSERT INTO account VALUES (?,?)");
    TransactionProgram program = new TransactionProgram();
    command(program, insert, 0, SqlTypeDescriptor.INTEGER, 1, SqlTypeDescriptor.BIGINT);
    assertEquals(StatusCode.OK, program.freeze());
    long programHandle = fixture.prepareProgram(program);

    assertEquals(StatusCode.OK, fixture.session.execute(
        "CREATE TABLE unrelated (id INTEGER PRIMARY KEY)", new CommandResult()));
    TransactionProgramArguments arguments = new TransactionProgramArguments();
    assertEquals(StatusCode.OK, arguments.setFixed(0, SqlTypeDescriptor.INTEGER, 23));
    assertEquals(StatusCode.OK, arguments.setFixed(1, SqlTypeDescriptor.BIGINT, 230));
    TransactionProgramResult result = new TransactionProgramResult();

    assertEquals(StatusCode.PROGRAM_STALE,
        fixture.session.executeProgram(programHandle, arguments, result));
    assertEquals(StatusCode.PROGRAM_STALE, result.primaryStatus());
    assertEquals(StatusCode.OK, result.rollbackStatus());
    assertEquals(StatusCode.OK, fixture.session.execute(
        "INSERT INTO account VALUES (23,230)", new CommandResult()));
    assertEquals(StatusCode.OK, fixture.session.closeProgram(programHandle));
    fixture.close();
  }

  @Test
  void rejectsStatementActionMismatchAtProgramPreparation(@TempDir Path root) {
    Fixture fixture = open(root);
    long query = fixture.prepare("SELECT balance FROM account WHERE id=?");
    TransactionProgram program = new TransactionProgram();
    assertEquals(StatusCode.OK,
        program.beginStep(query, TransactionProgramAction.COMMAND));
    parameter(program, 0, SqlTypeDescriptor.INTEGER);
    assertEquals(StatusCode.OK, program.endStep());
    assertEquals(StatusCode.OK, program.freeze());
    ProgramOpenResult result = new ProgramOpenResult();

    assertEquals(StatusCode.INVALID_EXTERNAL_INPUT,
        fixture.session.prepareProgram(program, result));
    assertEquals(0, result.handle());
    fixture.close();
  }

  private static void command(
      TransactionProgram program, long handle,
      int firstSlot, int firstType, int secondSlot, int secondType) {
    assertEquals(StatusCode.OK,
        program.beginStep(handle, TransactionProgramAction.COMMAND));
    parameter(program, firstSlot, firstType);
    parameter(program, secondSlot, secondType);
    assertEquals(StatusCode.OK, program.endStep());
  }

  private static void query(
      TransactionProgram program, long handle, int slot, int descriptor) {
    assertEquals(StatusCode.OK,
        program.beginStep(handle, TransactionProgramAction.EXACT_ONE));
    parameter(program, slot, descriptor);
    assertEquals(StatusCode.OK, program.captureColumn(0));
    assertEquals(StatusCode.OK, program.endStep());
  }

  private static void parameter(
      TransactionProgram program, int slot, int descriptor) {
    assertEquals(StatusCode.OK, program.beginParameter());
    assertEquals(StatusCode.OK, program.argument(slot, descriptor));
    assertEquals(StatusCode.OK, program.endExpression());
  }

  private static Fixture open(Path root) {
    io.riverdb.engine.api.DatabaseOpenResult opened =
        new io.riverdb.engine.api.DatabaseOpenResult();
    assertEquals(StatusCode.OK,
        EmbeddedRiver.create(root, DATABASE, GENERATION, 8, opened));
    RiverDatabase database = opened.database();
    SessionOpenResult sessionResult = new SessionOpenResult();
    assertEquals(StatusCode.OK, database.createSession(sessionResult));
    RiverSession session = sessionResult.session();
    assertEquals(StatusCode.OK, session.execute(
        "CREATE TABLE account (id INTEGER PRIMARY KEY,balance BIGINT NOT NULL)",
        new CommandResult()));
    return new Fixture(database, session);
  }

  private static final class Fixture {
    private final RiverDatabase database;
    private final RiverSession session;

    private Fixture(RiverDatabase owner, RiverSession ownerSession) {
      database = owner;
      session = ownerSession;
    }

    private long prepare(String sql) {
      PreparedOpenResult prepared = new PreparedOpenResult();
      assertEquals(StatusCode.OK, session.prepare(sql, prepared));
      return prepared.handle();
    }

    private long prepareProgram(TransactionProgram program) {
      ProgramOpenResult prepared = new ProgramOpenResult();
      assertEquals(StatusCode.OK, session.prepareProgram(program, prepared));
      return prepared.handle();
    }

    private void close() {
      assertEquals(StatusCode.OK, session.close());
      assertEquals(StatusCode.OK, database.close());
    }
  }
}
