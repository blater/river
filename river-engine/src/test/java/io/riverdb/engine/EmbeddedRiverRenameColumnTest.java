package io.riverdb.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.riverdb.base.error.StatusCode;
import io.riverdb.base.id.DatabaseIncarnation;
import io.riverdb.base.id.WalGeneration;
import io.riverdb.engine.api.CommandResult;
import io.riverdb.engine.api.DatabaseOpenResult;
import io.riverdb.engine.api.QueryOpenResult;
import io.riverdb.engine.api.RiverDatabase;
import io.riverdb.engine.api.RiverQuery;
import io.riverdb.engine.api.RiverSession;
import io.riverdb.engine.api.RowResult;
import io.riverdb.engine.api.SessionOpenResult;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class EmbeddedRiverRenameColumnTest {
  private static final DatabaseIncarnation DATABASE =
      DatabaseIncarnation.of(0x52454e414d45434fL, 0x4c554d4e30303031L);
  private static final WalGeneration GENERATION = WalGeneration.of(1);

  @Test
  void renamesIndexedColumnsTransactionallyAndDurably(@TempDir Path root) {
    DatabaseOpenResult opened = new DatabaseOpenResult();
    assertEquals(StatusCode.OK, EmbeddedRiver.create(root, DATABASE, GENERATION, 8, opened));
    RiverDatabase database = opened.database();
    SessionOpenResult sessionResult = new SessionOpenResult();
    assertEquals(StatusCode.OK, database.createSession(sessionResult));
    RiverSession session = sessionResult.session();
    CommandResult result = new CommandResult();

    assertEquals(
        StatusCode.OK,
        session.execute(
            "CREATE TABLE items "
                + "(id BIGINT PRIMARY KEY, code VARCHAR(7) NOT NULL, category BIGINT)",
            result));
    assertEquals(
        StatusCode.OK,
        session.execute(
            "INSERT INTO items VALUES (1, 'alpha', 7), (2, 'beta', 7)",
            result));
    assertEquals(
        StatusCode.OK,
        session.execute("CREATE UNIQUE INDEX items_code ON items(code)", result));
    assertEquals(
        StatusCode.OK,
        session.execute("CREATE INDEX items_category ON items(category)", result));

    SessionOpenResult secondResult = new SessionOpenResult();
    assertEquals(StatusCode.OK, database.createSession(secondResult));
    RiverSession second = secondResult.session();
    assertEquals(StatusCode.OK, second.execute("BEGIN", result));
    assertEquals(
        StatusCode.RETRY,
        session.execute("ALTER TABLE items RENAME COLUMN code TO sku", result));
    assertEquals(StatusCode.OK, second.execute("ROLLBACK", result));
    assertEquals(StatusCode.OK, second.close());

    assertEquals(
        StatusCode.CONFLICT,
        session.execute("ALTER TABLE items RENAME COLUMN code TO category", result));
    assertEquals(StatusCode.OK, session.execute("BEGIN", result));
    assertEquals(
        StatusCode.OK,
        session.execute("ALTER TABLE items RENAME COLUMN code TO sku", result));
    assertEquals(1, countRows(session, "SELECT id FROM items WHERE sku='alpha'"));
    assertEquals(
        StatusCode.INVALID_EXTERNAL_INPUT,
        session.beginQuery("SELECT id FROM items WHERE code='alpha'", new QueryOpenResult()));
    assertEquals(StatusCode.OK, session.execute("ROLLBACK", result));
    assertEquals(1, countRows(session, "SELECT id FROM items WHERE code='alpha'"));

    assertEquals(StatusCode.OK, session.execute("BEGIN", result));
    assertEquals(StatusCode.OK, session.execute("SAVEPOINT before_rename", result));
    assertEquals(
        StatusCode.OK,
        session.execute("ALTER TABLE items RENAME COLUMN code TO sku", result));
    assertEquals(
        StatusCode.OK,
        session.execute("ROLLBACK TO SAVEPOINT before_rename", result));
    assertEquals(1, countRows(session, "SELECT id FROM items WHERE code='alpha'"));
    assertEquals(StatusCode.OK, session.execute("COMMIT", result));

    assertEquals(StatusCode.OK, session.execute("BEGIN", result));
    assertEquals(
        StatusCode.OK,
        session.execute("ALTER TABLE items RENAME COLUMN code TO sku", result));
    assertEquals(StatusCode.OK, session.execute("COMMIT", result));
    assertEquals(1, countRows(session, "SELECT id FROM items WHERE sku='alpha'"));
    assertEquals(2, countRows(session, "SELECT id FROM items WHERE category=7"));
    assertEquals(
        StatusCode.UNIQUE_VIOLATION,
        session.execute("INSERT INTO items VALUES (3, 'alpha', 8)", result));
    assertEquals(
        StatusCode.CONFLICT,
        session.execute("ALTER TABLE items RENAME COLUMN sku TO category", result));
    assertEquals(
        StatusCode.OK,
        session.execute("ALTER TABLE items RENAME COLUMN id TO item_id", result));
    assertEquals(2, countRows(session, "SELECT item_id FROM items ORDER BY item_id"));
    assertEquals(StatusCode.OK, session.execute("CHECKPOINT", result));
    assertEquals(StatusCode.OK, session.close());
    assertEquals(StatusCode.OK, database.close());

    assertEquals(
        StatusCode.OK,
        EmbeddedRiver.openExisting(root, DATABASE, GENERATION, 8, opened));
    database = opened.database();
    assertEquals(StatusCode.OK, database.createSession(sessionResult));
    session = sessionResult.session();
    assertEquals(1, countRows(session, "SELECT item_id FROM items WHERE sku='alpha'"));
    assertEquals(2, countRows(session, "SELECT item_id FROM items WHERE category=7"));
    assertEquals(
        StatusCode.UNIQUE_VIOLATION,
        session.execute("INSERT INTO items VALUES (3, 'alpha', 8)", result));
    assertEquals(StatusCode.OK, session.close());
    assertEquals(StatusCode.OK, database.close());
  }

  @Test
  void preservesCompositeKeysForeignKeysDefaultsAndChecks(@TempDir Path root) {
    DatabaseOpenResult opened = new DatabaseOpenResult();
    assertEquals(StatusCode.OK, EmbeddedRiver.create(root, DATABASE, GENERATION, 8, opened));
    RiverDatabase database = opened.database();
    SessionOpenResult sessionResult = new SessionOpenResult();
    assertEquals(StatusCode.OK, database.createSession(sessionResult));
    RiverSession session = sessionResult.session();
    CommandResult result = new CommandResult();

    assertEquals(StatusCode.OK, session.execute(
        "CREATE TABLE rename_parents (tenant INTEGER,code VARCHAR(12),"
            + "amount DECIMAL(22,18) DEFAULT 1.000000000000000000 "
            + "CHECK (amount>0.000000000000000000),"
            + "CONSTRAINT rename_parents_pk PRIMARY KEY(tenant,code,amount),"
            + "CONSTRAINT rename_parents_uq UNIQUE(code,amount))", result));
    assertEquals(StatusCode.OK, session.execute(
        "CREATE INDEX rename_parents_lookup ON rename_parents(code,amount)", result));
    assertEquals(StatusCode.OK, session.execute(
        "CREATE TABLE rename_children (id BIGINT,tenant INTEGER,code VARCHAR(12),"
            + "amount DECIMAL(22,18),PRIMARY KEY(id),"
            + "CONSTRAINT rename_parent_fk FOREIGN KEY(tenant,code,amount) "
            + "REFERENCES rename_parents(tenant,code,amount))", result));
    assertEquals(StatusCode.OK, session.execute(
        "INSERT INTO rename_parents(tenant,code) VALUES (7,'north')", result));
    assertEquals(StatusCode.OK, session.execute(
        "INSERT INTO rename_children VALUES (1,7,'north',1.000000000000000000)", result));
    assertEquals(StatusCode.OK, session.execute("BEGIN", result));
    assertEquals(StatusCode.OK, session.execute(
        "ALTER TABLE rename_parents RENAME COLUMN amount TO total", result));
    assertEquals(1, countRows(session,
        "SELECT tenant FROM rename_parents WHERE total=1.000000000000000000"));
    assertEquals(StatusCode.OK, session.execute("ROLLBACK", result));
    assertEquals(1, countRows(session,
        "SELECT tenant FROM rename_parents WHERE amount=1.000000000000000000"));

    assertEquals(StatusCode.OK, session.execute("BEGIN", result));
    assertEquals(StatusCode.OK, session.execute(
        "ALTER TABLE rename_parents RENAME COLUMN amount TO total", result));
    assertEquals(StatusCode.OK, session.execute("COMMIT", result));
    assertEquals(StatusCode.INVALID_EXTERNAL_INPUT, session.execute(
        "ALTER INDEX rename_parents_pk RENAME TO renamed_pk", result));
    assertEquals(StatusCode.INVALID_EXTERNAL_INPUT, session.execute(
        "ALTER INDEX rename_parents_uq RENAME TO renamed_uq", result));
    assertEquals(StatusCode.OK, session.execute("CHECKPOINT", result));
    assertEquals(StatusCode.OK, session.close());
    assertEquals(StatusCode.OK, database.close());

    assertEquals(StatusCode.OK,
        EmbeddedRiver.openExisting(root, DATABASE, GENERATION, 8, opened));
    database = opened.database();
    assertEquals(StatusCode.OK, database.createSession(sessionResult));
    session = sessionResult.session();
    assertEquals(1, countRows(session,
        "SELECT tenant FROM rename_parents WHERE total=1.000000000000000000"));
    assertEquals(StatusCode.OK, session.execute(
        "INSERT INTO rename_children VALUES (2,7,'north',1.000000000000000000)", result));
    assertEquals(StatusCode.FOREIGN_KEY_VIOLATION, session.execute(
        "INSERT INTO rename_children VALUES (3,7,'north',2.000000000000000000)", result));
    assertEquals(StatusCode.CHECK_VIOLATION, session.execute(
        "INSERT INTO rename_parents VALUES (8,'south',-1.000000000000000000)", result));
    assertEquals(StatusCode.OK, session.close());
    assertEquals(StatusCode.OK, database.close());
  }

  private static int countRows(RiverSession session, String sql) {
    QueryOpenResult opened = new QueryOpenResult();
    assertEquals(StatusCode.OK, session.beginQuery(sql, opened));
    RiverQuery query = opened.query();
    RowResult row = new RowResult();
    int count = 0;
    StatusCode status = query.next(row);
    while (status.isOk() && row.isAvailable()) {
      count++;
      status = query.next(row);
    }
    assertEquals(StatusCode.OK, status);
    assertEquals(StatusCode.OK, query.close(new CommandResult()));
    return count;
  }
}
