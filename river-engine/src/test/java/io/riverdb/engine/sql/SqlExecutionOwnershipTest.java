package io.riverdb.engine.sql;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.riverdb.engine.relational.CatalogIndexCursor;
import io.riverdb.engine.relational.CatalogObjectCursor;
import io.riverdb.engine.relational.RelationalScanCursor;
import io.riverdb.engine.relational.RelationalSession;
import io.riverdb.sql.SqlParser;
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
      assertFalse(SqlNestedQueryExecution.class.isAssignableFrom(type), field.getName());
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
    int sortWorkspaces = 0;
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
        assertEquals("projectedValues", field.getName());
      }
      physicalPlans += type == SqlPhysicalPlan.class ? 1 : 0;
      nestedExecutions += type == SqlNestedQueryExecution.class ? 1 : 0;
      sortWorkspaces += type == SqlSortWorkspace.class ? 1 : 0;
    }
    assertEquals(1, physicalPlans);
    assertEquals(1, nestedExecutions);
    assertEquals(1, sortWorkspaces);
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
  void nestedExecutionKeepsItsMutableStoragePrivate() {
    for (Field field : SqlNestedQueryExecution.class.getDeclaredFields()) {
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
