package io.riverdb.engine.sql;

import static io.riverdb.engine.TestDatabaseResources.databaseRequest;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;

import io.riverdb.base.error.StatusCode;
import io.riverdb.base.id.DatabaseIncarnation;
import io.riverdb.base.id.WalGeneration;
import io.riverdb.engine.api.ParameterSet;
import io.riverdb.engine.api.SessionAuthorizer;
import io.riverdb.engine.relational.RelationalDatabase;
import io.riverdb.engine.relational.RelationalDatabaseOpenResult;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class SqlRetainedPreparedCounterTest {
  @Test
  void compilesOnceAndExecutesTheRetainedTemplateWithoutReparsing(@TempDir Path root) {
    RelationalDatabaseOpenResult databaseResult = new RelationalDatabaseOpenResult();
    assertEquals(StatusCode.OK, RelationalDatabase.create(databaseRequest(4), root,
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
    assertEquals(StatusCode.OK, session.validatePrepared(sql, null, session, validation));
    assertEquals(2, validation.parameterCount());
    SqlPreparedPlan plan = validation.plan();
    ParameterSet parameters = new ParameterSet(2, 0);
    assertEquals(StatusCode.OK, parameters.appendInteger(7));
    assertEquals(StatusCode.OK, parameters.appendInteger(1));
    assertEquals(StatusCode.OK,
        session.executePrepared(validation.plan(), parameters, execution));
    SqlPreparedValidationResult reused = new SqlPreparedValidationResult();
    assertEquals(StatusCode.OK, session.validatePrepared(sql, plan, session, reused));
    assertEquals(plan, reused.plan());
    assertEquals(1, session.preparedCompiles());
    assertEquals(StatusCode.OK, reused.reset());
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

  @Test
  void publishedDdlForcesCandidateRevalidation(@TempDir Path root) {
    RelationalDatabaseOpenResult databaseResult = new RelationalDatabaseOpenResult();
    assertEquals(StatusCode.OK, RelationalDatabase.create(databaseRequest(4), root,
        DatabaseIncarnation.of(0x5052455041524544L, 0x434F554E54455232L),
        WalGeneration.of(1), 4, databaseResult));
    RelationalDatabase database = databaseResult.database();
    SqlSessionOpenResult sessionResult = new SqlSessionOpenResult();
    assertEquals(StatusCode.OK, SqlSession.create(database, sessionResult));
    SqlSession session = sessionResult.session();
    SqlExecutionResult execution = new SqlExecutionResult();
    String sql = "SELECT id FROM counters";
    assertEquals(StatusCode.OK, session.execute(
        "CREATE TABLE counters (id INTEGER PRIMARY KEY)", execution));
    SqlPreparedValidationResult first = new SqlPreparedValidationResult();
    assertEquals(StatusCode.OK, session.validatePrepared(sql, null, session, first));
    SqlPreparedPlan plan = first.plan();
    assertEquals(1, session.preparedCompiles());

    assertEquals(StatusCode.OK, session.execute(
        "CREATE TABLE unrelated (id INTEGER PRIMARY KEY)", execution));
    SqlPreparedValidationResult second = new SqlPreparedValidationResult();
    assertEquals(StatusCode.OK, session.validatePrepared(sql, plan, session, second));
    assertEquals(2, session.preparedCompiles());
    assertNotSame(plan, second.plan());
    assertEquals(StatusCode.OK, second.reset());
    assertEquals(StatusCode.OK, first.reset());
    assertEquals(StatusCode.OK, session.close());
    assertEquals(StatusCode.OK, database.close());
  }

  @Test
  void cachedCandidateStillRequiresCurrentAuthorization(@TempDir Path root) {
    RelationalDatabaseOpenResult databaseResult = new RelationalDatabaseOpenResult();
    assertEquals(StatusCode.OK, RelationalDatabase.create(databaseRequest(4), root,
        DatabaseIncarnation.of(0x5052455041524544L, 0x434F554E54455233L),
        WalGeneration.of(1), 4, databaseResult));
    RelationalDatabase database = databaseResult.database();
    boolean[] allowed = {true};
    SessionAuthorizer authorizer = permission -> allowed[0]
        ? StatusCode.OK : StatusCode.ACCESS_DENIED;
    SqlSessionOpenResult sessionResult = new SqlSessionOpenResult();
    assertEquals(StatusCode.OK, SqlSession.create(database, authorizer, sessionResult));
    SqlSession session = sessionResult.session();
    SqlExecutionResult execution = new SqlExecutionResult();
    assertEquals(StatusCode.OK, session.execute(
        "CREATE TABLE counters (id INTEGER PRIMARY KEY)", execution));
    String sql = "SELECT id FROM counters";
    SqlPreparedValidationResult first = new SqlPreparedValidationResult();
    assertEquals(StatusCode.OK, session.validatePrepared(sql, null, session, first));
    SqlPreparedPlan plan = first.plan();
    long compiles = session.preparedCompiles();

    allowed[0] = false;
    SqlPreparedValidationResult denied = new SqlPreparedValidationResult();
    assertEquals(StatusCode.ACCESS_DENIED,
        session.validatePrepared(sql, plan, session, denied));
    assertEquals(compiles, session.preparedCompiles());
    assertEquals(StatusCode.OK, denied.reset());
    assertEquals(StatusCode.OK, first.reset());
    assertEquals(StatusCode.OK, session.close());
    assertEquals(StatusCode.OK, database.close());
  }

  @Test
  void privateDdlNeverMakesAReusableCandidate(@TempDir Path root) {
    RelationalDatabaseOpenResult databaseResult = new RelationalDatabaseOpenResult();
    assertEquals(StatusCode.OK, RelationalDatabase.create(databaseRequest(4), root,
        DatabaseIncarnation.of(0x5052455041524544L, 0x434F554E54455234L),
        WalGeneration.of(1), 4, databaseResult));
    RelationalDatabase database = databaseResult.database();
    SqlSessionOpenResult sessionResult = new SqlSessionOpenResult();
    assertEquals(StatusCode.OK, SqlSession.create(database, sessionResult));
    SqlSession session = sessionResult.session();
    SqlExecutionResult execution = new SqlExecutionResult();
    assertEquals(StatusCode.OK, session.execute(
        "CREATE TABLE counters (id INTEGER PRIMARY KEY)", execution));
    String sql = "SELECT id FROM counters";
    SqlPreparedValidationResult published = new SqlPreparedValidationResult();
    assertEquals(StatusCode.OK, session.validatePrepared(sql, null, session, published));
    SqlPreparedPlan publishedPlan = published.plan();

    assertEquals(StatusCode.OK, session.execute("BEGIN", execution));
    assertEquals(StatusCode.OK, session.execute(
        "CREATE TABLE private_table (id INTEGER PRIMARY KEY)", execution));
    SqlPreparedValidationResult duringPrivateDdl = new SqlPreparedValidationResult();
    assertEquals(StatusCode.OK,
        session.validatePrepared(sql, publishedPlan, session, duringPrivateDdl));
    assertNotSame(publishedPlan, duringPrivateDdl.plan());
    assertEquals(2, session.preparedCompiles());
    SqlPreparedPlan privatePlan = duringPrivateDdl.plan();
    assertEquals(StatusCode.OK, published.reset());

    SqlPreparedValidationResult repeatedPrivate = new SqlPreparedValidationResult();
    assertEquals(StatusCode.OK,
        session.validatePrepared(sql, privatePlan, session, repeatedPrivate));
    assertNotSame(privatePlan, repeatedPrivate.plan());
    assertEquals(3, session.preparedCompiles());
    assertEquals(StatusCode.OK, session.execute("ROLLBACK", execution));

    SqlPreparedValidationResult afterRollback = new SqlPreparedValidationResult();
    assertEquals(StatusCode.OK,
        session.validatePrepared(sql, privatePlan, session, afterRollback));
    assertNotSame(privatePlan, afterRollback.plan());
    assertEquals(4, session.preparedCompiles());
    assertEquals(StatusCode.OK, afterRollback.reset());
    assertEquals(StatusCode.OK, repeatedPrivate.reset());
    assertEquals(StatusCode.OK, duringPrivateDdl.reset());
    assertEquals(StatusCode.OK, session.close());
    assertEquals(StatusCode.OK, database.close());
  }
}
