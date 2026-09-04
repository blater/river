package io.riverdb.engine;

import static io.riverdb.engine.TestDatabaseResources.databaseRequest;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.riverdb.base.error.StatusCode;
import io.riverdb.base.id.DatabaseIncarnation;
import io.riverdb.base.id.WalGeneration;
import io.riverdb.base.type.SqlTypeDescriptor;
import io.riverdb.engine.api.CommandResult;
import io.riverdb.engine.api.DatabaseOpenResult;
import io.riverdb.engine.api.ParameterSet;
import io.riverdb.engine.api.QueryOpenResult;
import io.riverdb.engine.api.RiverDatabase;
import io.riverdb.engine.api.RiverQuery;
import io.riverdb.engine.api.RiverSession;
import io.riverdb.engine.api.RowResult;
import io.riverdb.engine.api.SessionOpenResult;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class EmbeddedRiverTypedParameterTest {
  private static final DatabaseIncarnation DATABASE =
      DatabaseIncarnation.of(0x504152414d455445L, 0x5253303030303031L);
  private static final WalGeneration GENERATION = WalGeneration.of(1);

  @Test
  void copiesTypedValuesAndPreservesNullAndStreamingLifetime(@TempDir Path root) {
    DatabaseOpenResult opened = new DatabaseOpenResult();
    assertEquals(StatusCode.OK, EmbeddedRiver.create(databaseRequest(8), root, DATABASE, GENERATION, 8, opened));
    RiverDatabase database = opened.database();
    SessionOpenResult sessionResult = new SessionOpenResult();
    assertEquals(StatusCode.OK, database.createSession(sessionResult));
    RiverSession session = sessionResult.session();
    CommandResult command = new CommandResult();
    QueryOpenResult queryResult = new QueryOpenResult();
    assertEquals(
        StatusCode.INVALID_EXTERNAL_INPUT,
        session.execute("SELECT 1", null, command));
    assertEquals(
        StatusCode.INVALID_EXTERNAL_INPUT,
        session.beginQuery("SELECT 1", null, queryResult));
    assertEquals(
        StatusCode.OK,
        session.execute(
            "CREATE TABLE typed_values (id BIGINT PRIMARY KEY, flag BOOLEAN, "
                + "amount DECIMAL(6,2), note VARCHAR(16), day DATE, clock TIME(6), "
                + "observed TIMESTAMP(6), captured TIMESTAMP(6) WITH TIME ZONE)",
            command));

    ParameterSet insert = new ParameterSet(8, 32);
    assertEquals(StatusCode.OK, insert.appendFixed(SqlTypeDescriptor.BIGINT, 1));
    assertEquals(StatusCode.OK, insert.appendFixed(SqlTypeDescriptor.BOOLEAN, 1));
    assertEquals(
        StatusCode.OK,
        insert.appendFixed(SqlTypeDescriptor.decimal(6, 2), 1_234));
    assertEquals(
        StatusCode.OK,
        insert.appendText(SqlTypeDescriptor.varchar(16), "Aé😀"));
    assertEquals(StatusCode.OK, insert.appendNull(SqlTypeDescriptor.DATE));
    assertEquals(StatusCode.OK, insert.appendFixed(SqlTypeDescriptor.time(6), 12_345_678));
    assertEquals(StatusCode.OK, insert.appendFixed(SqlTypeDescriptor.timestamp(6), 0));
    assertEquals(
        StatusCode.OK,
        insert.appendFixed(SqlTypeDescriptor.timestampWithTimeZone(6), 0));
    assertEquals(
        StatusCode.OK,
        session.execute(
            "INSERT INTO typed_values VALUES(?,?,?,?,?,?,?,?)", insert, command));
    insert.reset();
    assertEquals(StatusCode.OK, insert.appendFixed(SqlTypeDescriptor.BIGINT, 2));
    assertEquals(StatusCode.OK, insert.appendFixed(SqlTypeDescriptor.BOOLEAN, 0));
    assertEquals(
        StatusCode.OK,
        insert.appendFixed(SqlTypeDescriptor.decimal(6, 2), 5_678));
    assertEquals(
        StatusCode.OK,
        insert.appendText(SqlTypeDescriptor.varchar(16), "second"));
    assertEquals(StatusCode.OK, insert.appendNull(0));
    assertEquals(StatusCode.OK, insert.appendFixed(SqlTypeDescriptor.time(6), 1));
    assertEquals(StatusCode.OK, insert.appendFixed(SqlTypeDescriptor.timestamp(6), 1));
    assertEquals(
        StatusCode.OK,
        insert.appendFixed(SqlTypeDescriptor.timestampWithTimeZone(6), 1));
    assertEquals(
        StatusCode.OK,
        session.execute(
            "INSERT INTO typed_values VALUES(?,?,?,?,?,?,?,?)", insert, command));

    ParameterSet key = new ParameterSet(1, 0);
    assertEquals(StatusCode.OK, key.appendFixed(SqlTypeDescriptor.BIGINT, 1));
    assertEquals(
        StatusCode.OK,
        session.beginQuery(
            "SELECT id,note,day FROM typed_values WHERE id=?", key, queryResult));
    key.reset();
    assertEquals(StatusCode.OK, key.appendFixed(SqlTypeDescriptor.BIGINT, 99));
    RiverQuery query = queryResult.query();
    RowResult row = new RowResult();
    assertEquals(StatusCode.OK, query.next(row));
    assertTrue(row.isAvailable());
    assertEquals(1, row.valueAt(0));
    assertEquals("Aé😀", text(row, 1));
    assertTrue(row.isNull(2));
    assertEquals(StatusCode.OK, query.close(command));

    ParameterSet update = new ParameterSet(2, 16);
    assertEquals(
        StatusCode.OK,
        update.appendText(SqlTypeDescriptor.varchar(16), "updated"));
    assertEquals(StatusCode.OK, update.appendFixed(SqlTypeDescriptor.BIGINT, 2));
    assertEquals(
        StatusCode.OK,
        session.execute(
            "UPDATE typed_values SET note=? WHERE id=?", update, command));
    assertEquals(1, command.affectedRows());

    assertEquals(
        StatusCode.OK,
        session.execute(
            "CREATE VIEW typed_projection AS SELECT id,note FROM typed_values",
            command));
    ParameterSet viewText = new ParameterSet(1, 16);
    assertEquals(
        StatusCode.OK,
        viewText.appendText(SqlTypeDescriptor.varchar(16), "Aé😀"));
    assertEquals(
        StatusCode.OK,
        session.beginQuery(
            "SELECT id FROM typed_projection WHERE note=?", viewText, queryResult));
    viewText.reset();
    query = queryResult.query();
    assertEquals(StatusCode.OK, query.next(row));
    assertEquals(1, row.valueAt(0));
    assertEquals(StatusCode.OK, query.close(command));

    ParameterSet membership = new ParameterSet(2, 0);
    assertEquals(StatusCode.OK, membership.appendFixed(SqlTypeDescriptor.BIGINT, 1));
    assertEquals(StatusCode.OK, membership.appendNull(SqlTypeDescriptor.BIGINT));
    assertEquals(
        StatusCode.OK,
        session.execute(
            "SELECT COUNT(*) FROM typed_values WHERE id IN (?,?)", membership, command));
    assertEquals(1, command.valueAt(0));
    membership.reset();
    assertEquals(StatusCode.OK, membership.appendFixed(SqlTypeDescriptor.BIGINT, 3));
    assertEquals(StatusCode.OK, membership.appendNull(SqlTypeDescriptor.BIGINT));
    assertEquals(
        StatusCode.OK,
        session.execute(
            "SELECT COUNT(*) FROM typed_values WHERE id NOT IN (?,?)",
            membership,
            command));
    assertEquals(0, command.valueAt(0));

    ParameterSet wrongNull = new ParameterSet(1, 0);
    assertEquals(StatusCode.OK, wrongNull.appendNull(SqlTypeDescriptor.DATE));
    assertEquals(
        StatusCode.DATATYPE_MISMATCH,
        session.execute(
            "UPDATE typed_values SET note=? WHERE id=1", wrongNull, command));
    assertEquals(
        StatusCode.DATATYPE_MISMATCH,
        session.execute(
            "SELECT COUNT(*) FROM typed_values WHERE id IN (?)", wrongNull, command));

    ParameterSet compatibleNulls = new ParameterSet(2, 0);
    assertEquals(
        StatusCode.OK,
        compatibleNulls.appendNull(SqlTypeDescriptor.varchar(255)));
    assertEquals(
        StatusCode.OK,
        compatibleNulls.appendNull(SqlTypeDescriptor.decimal(18, 0)));
    assertEquals(
        StatusCode.OK,
        session.execute(
            "UPDATE typed_values SET note=?,amount=? WHERE id=1",
            compatibleNulls,
            command));
    assertEquals(1, command.affectedRows());
    assertEquals(
        StatusCode.OK,
        session.execute(
            "SELECT note,amount FROM typed_values WHERE id=1", command));
    assertTrue(command.isNull(0));
    assertTrue(command.isNull(1));
    ParameterSet numericNull = new ParameterSet(1, 0);
    assertEquals(StatusCode.OK, numericNull.appendNull(SqlTypeDescriptor.BIGINT));
    assertEquals(
        StatusCode.OK,
        session.execute(
            "UPDATE typed_values SET amount=? WHERE id=2", numericNull, command));

    ParameterSet none = new ParameterSet(0, 0);
    assertEquals(
        StatusCode.PARAMETER_COUNT_MISMATCH,
        session.execute("SELECT id FROM typed_values WHERE id=?", none, command));
    assertEquals(
        StatusCode.CONFLICT,
        session.execute(
            "SELECT d.id FROM (SELECT id FROM typed_values WHERE id=?) d",
            key,
            command));
    assertEquals(StatusCode.OK, session.close());
    assertEquals(StatusCode.OK, database.close());

    opened.reset();
    assertEquals(
        StatusCode.OK,
        EmbeddedRiver.openExisting(databaseRequest(8), root, DATABASE, GENERATION, 8, opened));
    database = opened.database();
    sessionResult.reset();
    assertEquals(StatusCode.OK, database.createSession(sessionResult));
    session = sessionResult.session();
    viewText.reset();
    assertEquals(
        StatusCode.OK,
        viewText.appendText(SqlTypeDescriptor.varchar(16), "updated"));
    assertEquals(
        StatusCode.OK,
        session.beginQuery(
            "SELECT id FROM typed_projection WHERE note=?", viewText, queryResult));
    viewText.reset();
    query = queryResult.query();
    assertEquals(StatusCode.OK, query.next(row));
    assertEquals(2, row.valueAt(0));
    assertEquals(StatusCode.OK, query.close(command));
    key.reset();
    assertEquals(StatusCode.OK, key.appendFixed(SqlTypeDescriptor.BIGINT, 2));
    assertEquals(
        StatusCode.OK,
        session.execute(
            "SELECT note FROM typed_projection WHERE id=?", key, command));
    assertEquals("updated", text(command, 0));
    assertEquals(StatusCode.OK, session.close());
    assertEquals(StatusCode.OK, database.close());
  }

  private static String text(RowResult row, int index) {
    char[] characters = new char[32];
    int length = row.copyTextAt(index, characters, 0);
    return new String(characters, 0, length);
  }

  private static String text(CommandResult result, int index) {
    char[] characters = new char[32];
    int length = result.copyTextAt(index, characters, 0);
    return new String(characters, 0, length);
  }
}
