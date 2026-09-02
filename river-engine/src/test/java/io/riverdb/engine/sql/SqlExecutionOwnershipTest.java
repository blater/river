package io.riverdb.engine.sql;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.riverdb.engine.relational.CatalogIndexCursor;
import io.riverdb.engine.relational.CatalogObjectCursor;
import io.riverdb.engine.relational.RelationalScanCursor;
import io.riverdb.engine.relational.RelationalSession;
import io.riverdb.base.error.StatusCode;
import io.riverdb.sql.SqlParser;
import io.riverdb.sql.SqlJoinChain;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.ByteBuffer;
import org.junit.jupiter.api.Test;

final class SqlExecutionOwnershipTest {
  @Test
  void publicSessionOwnsOnlyTheExecutionCoordinator() {
    Field owned = null;
    int fieldCount = 0;
    for (Field field : SqlSession.class.getDeclaredFields()) {
      if (!Modifier.isStatic(field.getModifiers())) {
        owned = field;
        fieldCount++;
      }
    }
    assertEquals(1, fieldCount);
    assertEquals(SqlSessionExecutionCoordinator.class, owned.getType());
  }

  @Test
  void scanCursorContainsOnlyCapabilityAndPublicShapeState() {
    for (Field field : SqlScanCursor.class.getDeclaredFields()) {
      if (Modifier.isStatic(field.getModifiers())) {
        continue;
      }
      Class<?> type = field.getType();
      assertFalse(RelationalScanCursor.class.isAssignableFrom(type), field.getName());
      assertFalse(CatalogObjectCursor.class.isAssignableFrom(type), field.getName());
      assertFalse(CatalogIndexCursor.class.isAssignableFrom(type), field.getName());
      assertFalse(ByteBuffer.class.isAssignableFrom(type), field.getName());
      assertFalse(SqlSortWorkspace.class.isAssignableFrom(type), field.getName());
      assertFalse(SqlSubqueryGraphExecution.class.isAssignableFrom(type), field.getName());
      assertFalse(SqlPhysicalPlan.class.isAssignableFrom(type), field.getName());
      assertFalse(hasWorkspaceName(field.getName()), field.getName());
    }
  }

  @Test
  void coordinatorOwnsTheSessionOperationComponents() {
    assertOwnedOnce(SqlSessionExecutionCoordinator.class, RelationalSession.class);
    assertOwnedOnce(SqlSessionExecutionCoordinator.class, SqlParser.class);
    assertOwnedOnce(SqlSessionExecutionCoordinator.class, BoundSqlStatement.class);
    assertOwnedOnce(SqlSessionExecutionCoordinator.class, SqlBinder.class);
    assertOwnedOnce(SqlSessionExecutionCoordinator.class, SqlTransactionState.class);
    assertOwnedOnce(SqlSessionExecutionCoordinator.class, SqlCommandDispatcher.class);
    assertOwnedOnce(SqlSessionExecutionCoordinator.class, SqlDmlExecutor.class);
    assertOwnedOnce(SqlSessionExecutionCoordinator.class, SqlQueryExecution.class);
  }

  @Test
  void queryExecutionBorrowsBoundStateWithoutOwningCoordinatorResponsibilities() {
    int physicalPlans = 0;
    int nestedExecutions = 0;
    int sortExecutions = 0;
    for (Field field : SqlQueryExecution.class.getDeclaredFields()) {
      if (Modifier.isStatic(field.getModifiers())) {
        continue;
      }
      Class<?> type = field.getType();
      assertFalse(SqlParser.class.isAssignableFrom(type), field.getName());
      assertFalse(SqlTransactionState.class.isAssignableFrom(type), field.getName());
      assertFalse(SqlCommandDispatcher.class.isAssignableFrom(type), field.getName());
      assertFalse(SqlDmlExecutor.class.isAssignableFrom(type), field.getName());
      assertFalse(SqlPointCommandExecutor.class.isAssignableFrom(type), field.getName());
      assertFalse(
          SqlStreamingStatementLifecycle.class.isAssignableFrom(type), field.getName());
      assertFalse(SqlSession.class.isAssignableFrom(type), field.getName());
      if (type.isArray()) {
        assertTrue(
            field.getName().equals("projectedValues")
                || field.getName().equals("explainTypeDescriptors"),
            field.getName());
      }
      physicalPlans += type == SqlPhysicalPlan.class ? 1 : 0;
      nestedExecutions += type == SqlSubqueryGraphExecution.class ? 1 : 0;
      sortExecutions += type == SqlSortExecution.class ? 1 : 0;
    }
    assertEquals(1, physicalPlans);
    assertEquals(1, nestedExecutions);
    assertEquals(1, sortExecutions);
  }

  @Test
  void queryExecutionDoesNotReceiveCoordinatorOwnedPreparationOrFraming() {
    for (Method method : SqlQueryExecution.class.getDeclaredMethods()) {
      assertFalse(
          method.getReturnType() == SqlBinder.class
              || method.getReturnType() == SqlViewExpander.class
              || method.getReturnType() == SqlStreamingStatementLifecycle.class,
          method.getName());
      for (Class<?> parameter : method.getParameterTypes()) {
        assertFalse(parameter == SqlBinder.class, method.getName());
        assertFalse(parameter == SqlViewExpander.class, method.getName());
        assertFalse(parameter == SqlStreamingStatementLifecycle.class, method.getName());
      }
    }
  }

  @Test
  void subqueryGraphKeepsItsMutableStoragePrivate() {
    for (Field field : SqlSubqueryGraphExecution.class.getDeclaredFields()) {
      if (Modifier.isStatic(field.getModifiers())) {
        continue;
      }
      Class<?> type = field.getType();
      boolean mutableStorage = type.isArray()
          || ByteBuffer.class.isAssignableFrom(type)
          || RelationalScanCursor.class.isAssignableFrom(type);
      if (mutableStorage) {
        assertTrue(Modifier.isPrivate(field.getModifiers()), field.getName());
      }
    }
  }

  @Test
  void executableGenerationPublishesOnlyAfterBindingAndInvalidatesOnReuse() {
    BoundSqlStatement bound = new BoundSqlStatement();
    SqlParser parser = new SqlParser();
    SqlBinder binder = new SqlBinder();
    assertEquals(
        StatusCode.OK,
        parser.parseQuery(
            "SELECT o.id FROM t o WHERE EXISTS "
                + "(SELECT i.id FROM t i WHERE i.id=o.id)",
            bound.query,
            bound.command));
    assertEquals(StatusCode.OK, binder.captureExecutableQuery(bound));
    assertFalse(bound.executableQuery.isExecutable());
    bound.executableQuery.beginBinding(bound.table);
    assertFalse(bound.executableQuery.isExecutable());
    bound.executableQuery.publishBinding();
    assertTrue(bound.executableQuery.isExecutable());
    long published = bound.executableQuery.executableGeneration();

    assertEquals(StatusCode.OK, binder.captureExecutableQuery(bound));
    assertFalse(bound.executableQuery.isExecutable());
    bound.executableQuery.beginBinding(bound.table);
    bound.executableQuery.publishBinding();
    assertTrue(bound.executableQuery.executableGeneration() > published);
  }

  @Test
  void executableSnapshotOwnsTheFullJoinChainAndClearsItOnReuse() {
    BoundSqlStatement bound = new BoundSqlStatement();
    SqlParser parser = new SqlParser();
    SqlBinder binder = new SqlBinder();
    assertEquals(
        StatusCode.OK,
        parser.parseQuery(
            "SELECT a.id,c.id FROM first_table a "
                + "LEFT JOIN second_table b ON a.id=b.id "
                + "JOIN third_table c ON b.id=c.id WHERE c.id=a.id",
            bound.query,
            bound.command));
    assertEquals(StatusCode.OK, binder.captureExecutableQuery(bound));
    bound.command.reset();
    SqlJoinChain chain = bound.executableQuery.root().joinChain();
    assertEquals(3, chain.roleCount());
    assertEquals(2, chain.stageCount());
    assertTrue(chain.isLeft(0));
    assertEquals(1, chain.onPredicates(0).leafCount());
    assertEquals(1, chain.onPredicates(1).leafCount());
    assertTrue(SqlBindingNames.same("third_table", chain.tableName(2)));

    bound.reset();
    assertEquals(
        StatusCode.OK,
        parser.parseQuery(
            "SELECT id FROM first_table", bound.query, bound.command));
    assertEquals(StatusCode.OK, binder.captureExecutableQuery(bound));
    assertNull(bound.executableQuery.root().joinChain());
  }

  private static boolean hasWorkspaceName(String name) {
    return name.contains("recursive")
        || name.contains("join")
        || name.contains("group")
        || name.contains("sort")
        || name.contains("plan");
  }

  private static void assertOwnedOnce(Class<?> owner, Class<?> component) {
    int count = 0;
    for (Field field : owner.getDeclaredFields()) {
      if (!Modifier.isStatic(field.getModifiers()) && field.getType() == component) {
        count++;
      }
    }
    assertEquals(1, count, component.getSimpleName());
  }
}
