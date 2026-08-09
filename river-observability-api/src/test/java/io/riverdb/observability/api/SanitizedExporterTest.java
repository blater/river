package io.riverdb.observability.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.riverdb.observability.api.event.BoundedEventRing;
import io.riverdb.observability.api.event.BoundedEventRingFactory;
import io.riverdb.observability.api.event.DiagnosticContext;
import io.riverdb.observability.api.event.DiagnosticContextField;
import io.riverdb.observability.api.event.DiagnosticEvent;
import io.riverdb.observability.api.event.EventPollResult;
import io.riverdb.observability.api.event.EventPublishResult;
import io.riverdb.observability.api.event.EventTypeId;
import io.riverdb.observability.api.event.ObservabilityBuildMode;
import io.riverdb.observability.api.event.SaturationPolicy;
import io.riverdb.observability.api.event.Severity;
import io.riverdb.observability.api.export.DiagnosticEventExporter;
import io.riverdb.observability.api.export.SanitizedDiagnosticEvent;
import io.riverdb.observability.api.export.SanitizedEventConsumer;
import java.lang.reflect.Method;
import org.junit.jupiter.api.Test;

class SanitizedExporterTest {
  @Test
  void exporterEndpointReceivesOnlyPolicyFilteredView() {
    BoundedEventRing ring = BoundedEventRingFactory.create(
        2,
        Severity.DEBUG,
        SaturationPolicy.DROP_AND_COUNT,
        ObservabilityBuildMode.TEST);
    DiagnosticContext context = new DiagnosticContext().databaseId(31).sessionId(32);
    DiagnosticEvent raw = new DiagnosticEvent().set(
        EventTypeId.DATABASE_STARTED,
        Severity.INFO,
        33,
        34,
        context,
        35,
        36,
        37,
        38);
    assertEquals(EventPublishResult.PUBLISHED, ring.publish(raw));

    long[] exported = new long[4];
    boolean[] visible = new boolean[4];
    SanitizedEventConsumer consumer = event -> {
      visible[0] = event.hasContext(DiagnosticContextField.DATABASE_ID);
      visible[1] = event.hasContext(DiagnosticContextField.SESSION_ID);
      visible[2] = event.hasEventField(0);
      visible[3] = event.hasEventField(1);
      exported[0] = event.contextValue(DiagnosticContextField.DATABASE_ID);
      exported[1] = event.contextValue(DiagnosticContextField.SESSION_ID);
      exported[2] = event.eventField(0);
      exported[3] = event.eventField(1);
    };
    DiagnosticEventExporter exporter = new DiagnosticEventExporter(ring);

    assertEquals(EventPollResult.POLLED, exporter.pollAndExport(consumer));
    assertFalse(visible[0]);
    assertFalse(visible[1]);
    assertFalse(visible[2]);
    assertTrue(visible[3]);
    assertEquals(0, exported[0]);
    assertEquals(0, exported[1]);
    assertEquals(0, exported[2]);
    assertEquals(36, exported[3]);
  }

  @Test
  void privilegedInternalExporterMustBeSelectedExplicitly() {
    BoundedEventRing ring = BoundedEventRingFactory.create(
        2,
        Severity.DEBUG,
        SaturationPolicy.DROP_AND_COUNT,
        ObservabilityBuildMode.TEST);
    DiagnosticContext context = new DiagnosticContext().databaseId(51).sessionId(52);
    DiagnosticEvent raw = new DiagnosticEvent().set(
        EventTypeId.DATABASE_STARTED,
        Severity.INFO,
        53,
        54,
        context,
        55,
        56,
        57,
        58);
    assertEquals(EventPublishResult.PUBLISHED, ring.publish(raw));
    long[] exported = new long[2];
    DiagnosticEventExporter exporter = DiagnosticEventExporter.privilegedInternal(ring);

    assertEquals(EventPollResult.POLLED, exporter.pollAndExport(event -> {
      exported[0] = event.contextValue(DiagnosticContextField.SESSION_ID);
      exported[1] = event.eventField(0);
    }));
    assertEquals(52, exported[0]);
    assertEquals(55, exported[1]);
  }

  @Test
  void exporterInterfacesCannotReceiveRawDiagnosticEvent() {
    for (Method method : SanitizedEventConsumer.class.getDeclaredMethods()) {
      for (Class<?> parameter : method.getParameterTypes()) {
        assertFalse(parameter == DiagnosticEvent.class);
        assertEquals(SanitizedDiagnosticEvent.class, parameter);
      }
    }
    for (Method method : SanitizedDiagnosticEvent.class.getDeclaredMethods()) {
      assertFalse(method.getReturnType() == DiagnosticEvent.class);
      assertFalse(method.getReturnType() == DiagnosticContext.class);
    }
  }
}
