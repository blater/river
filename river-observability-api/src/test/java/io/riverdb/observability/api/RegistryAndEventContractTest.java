package io.riverdb.observability.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.riverdb.observability.api.event.DiagnosticContext;
import io.riverdb.observability.api.event.DiagnosticContextField;
import io.riverdb.observability.api.event.DiagnosticEvent;
import io.riverdb.observability.api.event.EventTypeId;
import io.riverdb.observability.api.event.Severity;
import io.riverdb.observability.api.metric.MetricName;
import io.riverdb.observability.api.redaction.DiagnosticRedactor;
import io.riverdb.observability.api.redaction.SensitiveFieldPolicies;
import java.lang.reflect.Field;
import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;

class RegistryAndEventContractTest {
  @Test
  void stableRegistriesHaveUniqueIdsAndNames() {
    Set<Integer> eventIds = new HashSet<>();
    Set<String> eventNames = new HashSet<>();
    for (EventTypeId type : EventTypeId.values()) {
      assertTrue(eventIds.add(type.stableId()));
      assertTrue(eventNames.add(type.canonicalName()));
      assertSame(type, EventTypeId.fromStableId(type.stableId()));
      assertFalse(type.canonicalName().startsWith("river.audit"));
    }
    assertSame(EventTypeId.UNKNOWN, EventTypeId.fromStableId(Integer.MAX_VALUE));

    Set<Integer> metricIds = new HashSet<>();
    Set<String> metricNames = new HashSet<>();
    for (MetricName name : MetricName.values()) {
      assertTrue(metricIds.add(name.stableId()));
      assertTrue(metricNames.add(name.canonicalName()));
      assertSame(name, MetricName.fromStableId(name.stableId()));
    }
    assertSame(MetricName.UNKNOWN, MetricName.fromStableId(Integer.MAX_VALUE));
  }

  @Test
  void eventCopiesFixedFieldsAndReusableContext() {
    DiagnosticContext context = new DiagnosticContext()
        .databaseId(11)
        .sessionId(12)
        .transactionId(13)
        .requestId(14, 15)
        .statementFingerprint(16);
    DiagnosticEvent source = new DiagnosticEvent().set(
        EventTypeId.WAL_STALL, Severity.WARN, 17, 18, context, 19, 20, 21, 22);
    DiagnosticEvent copy = new DiagnosticEvent().copyFrom(source);

    context.reset();
    source.reset();

    assertSame(EventTypeId.WAL_STALL, copy.type());
    assertSame(Severity.WARN, copy.severity());
    assertEquals(17, copy.monotonicNanos());
    assertEquals(18, copy.sequence());
    assertEquals(13, copy.context().value(DiagnosticContextField.TRANSACTION_ID));
    assertEquals(19, copy.field0());
    assertEquals(22, copy.field3());
  }

  @Test
  void payloadStorageIsFixedWidthAndContainsNoDynamicContainers() {
    for (Field field : DiagnosticContext.class.getDeclaredFields()) {
      assertSame(long.class, field.getType());
    }
    for (Field field : DiagnosticEvent.class.getDeclaredFields()) {
      Class<?> type = field.getType();
      boolean permitted = type == long.class
          || type == DiagnosticContext.class
          || type == EventTypeId.class
          || type == Severity.class;
      assertTrue(permitted, field.toString());
    }
  }

  @Test
  void redactionFailsClosedAtCentralSeam() {
    DiagnosticContext context = new DiagnosticContext().databaseId(41).sessionId(42);
    DiagnosticEvent event = new DiagnosticEvent().set(
        EventTypeId.DATABASE_STARTED, Severity.INFO, 1, 2, context, 43, 44, 45, 46);

    assertFalse(DiagnosticRedactor.mayExportContext(
        context, DiagnosticContextField.DATABASE_ID, SensitiveFieldPolicies.safeExternal()));
    assertEquals(0, DiagnosticRedactor.contextValue(
        context, DiagnosticContextField.SESSION_ID, SensitiveFieldPolicies.safeExternal()));
    assertEquals(0, DiagnosticRedactor.eventField(
        event, 0, SensitiveFieldPolicies.safeExternal()));
    assertEquals(44, DiagnosticRedactor.eventField(
        event, 1, SensitiveFieldPolicies.safeExternal()));
    assertEquals(0, DiagnosticRedactor.eventField(
        event, 99, SensitiveFieldPolicies.privileged()));
  }
}
