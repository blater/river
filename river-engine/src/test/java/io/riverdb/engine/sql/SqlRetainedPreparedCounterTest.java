package io.riverdb.engine.sql;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.riverdb.base.error.StatusCode;
import io.riverdb.base.id.DatabaseIncarnation;
import io.riverdb.base.id.WalGeneration;
import io.riverdb.engine.api.ParameterSet;
import io.riverdb.engine.relational.RelationalDatabase;
import io.riverdb.engine.relational.RelationalDatabaseOpenResult;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class SqlRetainedPreparedCounterTest {
  @Test
  void compilesOnceAndExecutesTheRetainedTemplateWithoutReparsing(@TempDir Path root) {
    RelationalDatabaseOpenResult databaseResult = new RelationalDatabaseOpenResult();
    assertEquals(StatusCode.OK, RelationalDatabase.create(root,
        DatabaseIncarnation.of(0x5052455041524544L, 0x434F554E54455231L),
        WalGeneration.of(1), 4, databaseResult));
    RelationalDatabase database = databaseResult.database();
    SqlSessionOpenResult sessionResult = new SqlSessionOpenResult();
    assertEquals(StatusCode.OK, SqlSession.create(database, sessionResult));
    SqlSession session = sessionResult.session();
    SqlExecutionResult execution = new SqlExecutionResult();
    assertEquals(StatusCode.OK,
        session.execute("CREATE TABLE counters (id INTEGER PRIMARY KEY,value INTEGER)", execution));
    assertEquals(StatusCode.OK, session.execute("INSERT INTO counters VALUES (1,0)", execution));

    SqlPreparedValidationResult validation = new SqlPreparedValidationResult();
    String sql = "UPDATE counters SET value=? WHERE id=?";
    assertEquals(StatusCode.OK, session.validatePrepared(sql, session, validation));
    assertEquals(2, validation.parameterCount());
    ParameterSet parameters = new ParameterSet(2, 0);
    assertEquals(StatusCode.OK, parameters.appendInteger(7));
    assertEquals(StatusCode.OK, parameters.appendInteger(1));
    assertEquals(StatusCode.OK,
        session.executePrepared(validation.plan(), parameters, execution));
    assertEquals(StatusCode.OK,
        session.execute("CREATE TABLE unrelated (id INTEGER PRIMARY KEY)", execution));
    parameters.reset();
    assertEquals(StatusCode.OK, parameters.appendInteger(8));
    assertEquals(StatusCode.OK, parameters.appendInteger(1));
    assertEquals(StatusCode.OK,
        session.executePrepared(validation.plan(), parameters, execution));
    parameters.reset();
    assertEquals(StatusCode.OK, parameters.appendInteger(9));
    assertEquals(StatusCode.OK, parameters.appendInteger(1));
    assertEquals(StatusCode.OK,
        session.executePrepared(validation.plan(), parameters, execution));

    assertEquals(1, session.preparedCompiles());
    assertEquals(3, session.preparedExecutions());
    assertEquals(1, session.preparedRecompiles());
    assertEquals(StatusCode.OK, validation.reset());
    assertEquals(StatusCode.OK, session.close());
    assertEquals(StatusCode.OK, database.close());
  }
}
