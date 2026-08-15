package io.riverdb.buildpolicy;

import org.gradle.api.artifacts.Configuration;
import org.gradle.api.artifacts.ProjectDependency;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;

/** Checks the declared module graph and extracts inherited Gradle project edges. */
final class BuildGraphPolicy {
  private BuildGraphPolicy() {
  }

  static List<String> violations(
      Map<String, Set<String>> actualGraph,
      Map<String, Set<String>> allowedGraph
  ) {
    List<String> violations = new ArrayList<>();
    checkModuleSets(actualGraph, allowedGraph, violations);
    for (Map.Entry<String, Set<String>> entry : actualGraph.entrySet()) {
      checkDependencies(entry.getKey(), entry.getValue(), actualGraph, allowedGraph, violations);
    }
    findCycle(actualGraph).ifPresent(cycle ->
        violations.add("module dependency cycle: " + String.join(" -> ", cycle))
    );
    Collections.sort(violations);
    return List.copyOf(violations);
  }

  static Set<String> inheritedProjectDependencies(
      Collection<Configuration> mainClasspaths
  ) {
    Set<String> dependencies = new LinkedHashSet<>();
    for (Configuration configuration : mainClasspaths) {
      configuration.getAllDependencies()
          .withType(ProjectDependency.class)
          .forEach(dependency -> dependencies.add(shortName(dependency.getPath())));
    }
    return Set.copyOf(dependencies);
  }

  private static void checkModuleSets(
      Map<String, Set<String>> actualGraph,
      Map<String, Set<String>> allowedGraph,
      List<String> violations
  ) {
    Set<String> missing = new LinkedHashSet<>(allowedGraph.keySet());
    missing.removeAll(actualGraph.keySet());
    if (!missing.isEmpty()) {
      violations.add("missing declared modules: " + sorted(missing));
    }
    Set<String> unknown = new LinkedHashSet<>(actualGraph.keySet());
    unknown.removeAll(allowedGraph.keySet());
    if (!unknown.isEmpty()) {
      violations.add("modules absent from the approved graph: " + sorted(unknown));
    }
  }

  private static void checkDependencies(
      String module,
      Set<String> dependencies,
      Map<String, Set<String>> actualGraph,
      Map<String, Set<String>> allowedGraph,
      List<String> violations
  ) {
    Set<String> allowed = allowedGraph.get(module);
    if (allowed == null) {
      return;
    }
    Set<String> forbidden = new LinkedHashSet<>(dependencies);
    forbidden.removeAll(allowed);
    if (!forbidden.isEmpty()) {
      violations.add(module + " has forbidden dependencies: " + sorted(forbidden));
    }
    Set<String> unknown = new LinkedHashSet<>(dependencies);
    unknown.removeAll(actualGraph.keySet());
    if (!unknown.isEmpty()) {
      violations.add(module + " references unknown modules: " + sorted(unknown));
    }
  }

  private static Optional<List<String>> findCycle(Map<String, Set<String>> graph) {
    Map<String, VisitState> states = new HashMap<>();
    Deque<String> path = new ArrayDeque<>();
    for (String module : new TreeSet<>(graph.keySet())) {
      Optional<List<String>> cycle = visit(module, graph, states, path);
      if (cycle.isPresent()) {
        return cycle;
      }
    }
    return Optional.empty();
  }

  private static Optional<List<String>> visit(
      String module,
      Map<String, Set<String>> graph,
      Map<String, VisitState> states,
      Deque<String> path
  ) {
    VisitState state = states.get(module);
    if (state == VisitState.COMPLETE) {
      return Optional.empty();
    }
    if (state == VisitState.ACTIVE) {
      return Optional.of(cycleFrom(module, path));
    }
    states.put(module, VisitState.ACTIVE);
    path.addLast(module);
    for (String dependency : new TreeSet<>(graph.getOrDefault(module, Set.of()))) {
      if (graph.containsKey(dependency)) {
        Optional<List<String>> cycle = visit(dependency, graph, states, path);
        if (cycle.isPresent()) {
          return cycle;
        }
      }
    }
    path.removeLast();
    states.put(module, VisitState.COMPLETE);
    return Optional.empty();
  }

  private static List<String> cycleFrom(String module, Deque<String> path) {
    List<String> cycle = new ArrayList<>();
    boolean copying = false;
    for (String entry : path) {
      if (entry.equals(module)) {
        copying = true;
      }
      if (copying) {
        cycle.add(entry);
      }
    }
    cycle.add(module);
    return List.copyOf(cycle);
  }

  private static String shortName(String path) {
    return path.substring(path.lastIndexOf(':') + 1);
  }

  private static List<String> sorted(Collection<String> values) {
    List<String> result = new ArrayList<>(values);
    Collections.sort(result);
    return List.copyOf(result);
  }

  private enum VisitState {
    ACTIVE,
    COMPLETE
  }
}
