package io.riverdb.buildpolicy;

import org.gradle.api.artifacts.Configuration;
import org.gradle.api.artifacts.ProjectDependency;

import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Shared, deterministic checks used by the live build and negative fixtures. */
public final class BuildPolicy {
  private static final Pattern PACKAGE = Pattern.compile(
      "\\bpackage\\s+([A-Za-z_$][\\w$]*(?:\\s*\\.\\s*[A-Za-z_$][\\w$]*)*)\\s*;"
  );
  private static final Pattern EXPORTS = Pattern.compile(
      "\\bexports\\s+([A-Za-z_$][\\w$]*(?:\\s*\\.\\s*[A-Za-z_$][\\w$]*)*)"
  );
  private static final Pattern QUALIFIED_NAME = Pattern.compile(
      "\\b[A-Za-z_$][\\w$]*(?:\\s*\\.\\s*[A-Za-z_$][\\w$]*)+"
  );
  private static final Pattern STREAM_API = Pattern.compile(
      "\\bjava\\s*\\.\\s*util\\s*\\.\\s*stream\\b|"
          + "\\bCollectors\\s*\\.|\\bStreamSupport\\s*\\.|"
          + "\\.\\s*(?:parallelStream|stream)\\s*\\("
  );
  private static final Pattern STRING_FORMAT = Pattern.compile(
      "\\bjava\\s*\\.\\s*util\\s*\\.\\s*Formatter\\b|"
          + "\\bString\\s*\\.\\s*format\\s*\\(|"
          + "\\.\\s*(?:formatted|printf)\\s*\\("
  );
  private static final Pattern RAW_UNICODE_ESCAPE = Pattern.compile(
      "\\\\u+[0-9a-fA-F]{4}"
  );
  private static final Pattern TEST_SUPPORT_IDENTIFIER = Pattern.compile(
      "\\b[A-Za-z_$][\\w$]*(?:ForTest|TestOnly)[A-Za-z0-9_$]*\\b"
  );

  private BuildPolicy() {
  }

  /** A Java source together with the project module that owns it. */
  public record JavaSource(
      String module,
      Path path,
      String text,
      boolean productionSource
  ) {
    public JavaSource(String module, Path path, String text) {
      this(module, path, text, true);
    }
  }

  /**
   * Validates source layout, Java package ownership, and the deliberately small
   * forbidden-API set for designated hot-path packages.
   */
  public static List<String> sourceViolations(
      Path root,
      Collection<JavaSource> javaSources,
      Collection<Path> checkedTextFiles,
      Set<String> checkedTextExtensions,
      Set<String> indentedExtensions,
      Set<String> hotPathPackagePrefixes
  ) {
    List<String> violations = new ArrayList<>();
    checkTextLayout(
        root,
        checkedTextFiles,
        checkedTextExtensions,
        indentedExtensions,
        violations
    );

    Map<String, String> internalPackageOwners = new LinkedHashMap<>();
    Map<JavaSource, ParsedJava> parsedSources = new LinkedHashMap<>();
    javaSources.stream()
        .sorted((left, right) -> left.path().compareTo(right.path()))
        .forEach(source -> {
          if (RAW_UNICODE_ESCAPE.matcher(source.text()).find()) {
            violations.add(relative(root, source.path())
                + ": raw Java Unicode escape is forbidden");
          }
          ParsedJava parsed = parseJava(source.text());
          parsedSources.put(source, parsed);
          if (isInternalPackage(parsed.packageName())) {
            String previous = internalPackageOwners.putIfAbsent(
                parsed.packageName(),
                source.module()
            );
            if (previous != null && !previous.equals(source.module())) {
              violations.add(relative(root, source.path())
                  + ": internal package " + parsed.packageName()
                  + " is owned by both " + previous + " and " + source.module());
            }
          }
        });

    parsedSources.forEach((source, parsed) -> {
      String relativePath = relative(root, source.path());
      parsed.exportedPackages().stream()
          .filter(BuildPolicy::isInternalPackage)
          .forEach(packageName -> violations.add(relativePath
              + ": exports internal package " + packageName));

      internalPackageOwners.forEach((packageName, owner) -> {
        if (!owner.equals(source.module()) && referencesPackage(parsed.code(), packageName)) {
          violations.add(relativePath + ": references internal package "
              + packageName + " owned by " + owner);
        }
      });

      if (source.productionSource()) {
        if ("io.riverdb.platform.fault".equals(parsed.packageName())) {
          violations.add(relativePath
              + ": production source owns the test-only platform fault package");
        }
        if (referencesPackage(parsed.code(), "io.riverdb.testkit")) {
          violations.add(relativePath + ": production source references testkit code");
        }
        if (TEST_SUPPORT_IDENTIFIER.matcher(parsed.code()).find()) {
          violations.add(relativePath
              + ": production source declares or references a test-support identifier");
        }
      }

      if (source.productionSource()
          && isHotPathPackage(parsed.packageName(), hotPathPackagePrefixes)) {
        if (STREAM_API.matcher(parsed.code()).find()) {
          violations.add(relativePath
              + ": hot-path package references stream/collector APIs");
        }
        if (STRING_FORMAT.matcher(parsed.code()).find()) {
          violations.add(relativePath
              + ": hot-path package references string-formatting APIs");
        }
      }
    });

    Collections.sort(violations);
    return List.copyOf(violations);
  }

  /** Validates that declared edges fit within a maximum graph and rejects cycles. */
  public static List<String> graphViolations(
      Map<String, Set<String>> actualGraph,
      Map<String, Set<String>> allowedGraph
  ) {
    List<String> violations = new ArrayList<>();
    Set<String> missingModules = new LinkedHashSet<>(allowedGraph.keySet());
    missingModules.removeAll(actualGraph.keySet());
    if (!missingModules.isEmpty()) {
      violations.add("missing declared modules: " + sorted(missingModules));
    }

    Set<String> unknownModules = new LinkedHashSet<>(actualGraph.keySet());
    unknownModules.removeAll(allowedGraph.keySet());
    if (!unknownModules.isEmpty()) {
      violations.add("modules absent from the approved graph: " + sorted(unknownModules));
    }

    actualGraph.forEach((module, dependencies) -> {
      Set<String> allowed = allowedGraph.get(module);
      if (allowed == null) {
        return;
      }
      Set<String> forbidden = new LinkedHashSet<>(dependencies);
      forbidden.removeAll(allowed);
      if (!forbidden.isEmpty()) {
        violations.add(module + " has forbidden dependencies: " + sorted(forbidden));
      }
      Set<String> unknownDependencies = new LinkedHashSet<>(dependencies);
      unknownDependencies.removeAll(actualGraph.keySet());
      if (!unknownDependencies.isEmpty()) {
        violations.add(module + " references unknown modules: " + sorted(unknownDependencies));
      }
    });

    findCycle(actualGraph).ifPresent(cycle ->
        violations.add("module dependency cycle: " + String.join(" -> ", cycle))
    );
    Collections.sort(violations);
    return List.copyOf(violations);
  }

  /** Extracts every inherited project edge visible to a main classpath. */
  public static Set<String> inheritedProjectDependencies(
      Collection<Configuration> mainClasspaths
  ) {
    Set<String> dependencies = new LinkedHashSet<>();
    mainClasspaths.forEach(configuration ->
        configuration.getAllDependencies()
            .withType(ProjectDependency.class)
            .forEach(dependency -> dependencies.add(
                dependency.getPath().substring(dependency.getPath().lastIndexOf(':') + 1)
            ))
    );
    return Set.copyOf(dependencies);
  }

  private static void checkTextLayout(
      Path root,
      Collection<Path> files,
      Set<String> checkedExtensions,
      Set<String> indentedExtensions,
      List<String> violations
  ) {
    files.stream().sorted().forEach(file -> {
      String extension = extension(file);
      if (!checkedExtensions.contains(extension)) {
        return;
      }
      String text;
      try {
        text = java.nio.file.Files.readString(file);
      } catch (java.io.IOException exception) {
        throw new IllegalStateException("cannot read policy input " + file, exception);
      }
      String[] lines = text.split("\\R", -1);
      for (int index = 0; index < lines.length; index++) {
        String line = lines[index];
        if (line.indexOf('\t') >= 0) {
          violations.add(relative(root, file) + ":" + (index + 1) + ": tab character");
        }
        if (indentedExtensions.contains(extension) && !line.isBlank()) {
          int leadingSpaces = 0;
          while (leadingSpaces < line.length() && line.charAt(leadingSpaces) == ' ') {
            leadingSpaces++;
          }
          String trimmed = line.substring(leadingSpaces);
          if (!trimmed.startsWith("*") && leadingSpaces % 2 != 0) {
            violations.add(relative(root, file) + ":" + (index + 1)
                + ": indentation is not a multiple of two");
          }
        }
      }
    });
  }

  private static ParsedJava parseJava(String source) {
    String code = stripCommentsAndLiterals(source);
    Matcher packageMatcher = PACKAGE.matcher(code);
    String packageName = packageMatcher.find() ? normalizeName(packageMatcher.group(1)) : "";
    List<String> exportedPackages = new ArrayList<>();
    Matcher exportsMatcher = EXPORTS.matcher(code);
    while (exportsMatcher.find()) {
      exportedPackages.add(normalizeName(exportsMatcher.group(1)));
    }
    return new ParsedJava(packageName, code, List.copyOf(exportedPackages));
  }

  private static String stripCommentsAndLiterals(String source) {
    StringBuilder result = new StringBuilder(source.length());
    LexicalState state = LexicalState.CODE;
    for (int index = 0; index < source.length(); index++) {
      char current = source.charAt(index);
      char next = index + 1 < source.length() ? source.charAt(index + 1) : '\0';
      char third = index + 2 < source.length() ? source.charAt(index + 2) : '\0';
      if (state == LexicalState.CODE) {
        if (current == '/' && next == '/') {
          result.append("  ");
          index++;
          state = LexicalState.LINE_COMMENT;
        } else if (current == '/' && next == '*') {
          result.append("  ");
          index++;
          state = LexicalState.BLOCK_COMMENT;
        } else if (current == '"' && next == '"' && third == '"') {
          result.append("   ");
          index += 2;
          state = LexicalState.TEXT_BLOCK;
        } else if (current == '"') {
          result.append(' ');
          state = LexicalState.STRING;
        } else if (current == '\'') {
          result.append(' ');
          state = LexicalState.CHARACTER;
        } else {
          result.append(current);
        }
      } else if (state == LexicalState.LINE_COMMENT) {
        result.append(current == '\n' ? '\n' : ' ');
        if (current == '\n') {
          state = LexicalState.CODE;
        }
      } else if (state == LexicalState.BLOCK_COMMENT) {
        if (current == '*' && next == '/') {
          result.append("  ");
          index++;
          state = LexicalState.CODE;
        } else {
          result.append(current == '\n' ? '\n' : ' ');
        }
      } else if (state == LexicalState.TEXT_BLOCK) {
        if (current == '"' && next == '"' && third == '"') {
          result.append("   ");
          index += 2;
          state = LexicalState.CODE;
        } else {
          result.append(current == '\n' ? '\n' : ' ');
        }
      } else {
        boolean escaped = current == '\\';
        if (escaped && next != '\0') {
          result.append("  ");
          index++;
        } else {
          result.append(current == '\n' ? '\n' : ' ');
          if ((state == LexicalState.STRING && current == '"')
              || (state == LexicalState.CHARACTER && current == '\'')) {
            state = LexicalState.CODE;
          }
        }
      }
    }
    return result.toString();
  }

  private static boolean referencesPackage(String code, String packageName) {
    Matcher matcher = QUALIFIED_NAME.matcher(code);
    while (matcher.find()) {
      String candidate = normalizeName(matcher.group());
      if (candidate.equals(packageName) || candidate.startsWith(packageName + ".")) {
        return true;
      }
    }
    return false;
  }

  private static boolean isInternalPackage(String packageName) {
    return ("." + packageName + ".").contains(".internal.");
  }

  private static boolean isHotPathPackage(String packageName, Set<String> prefixes) {
    return prefixes.stream().anyMatch(prefix ->
        packageName.equals(prefix) || packageName.startsWith(prefix + ".")
    );
  }

  private static String normalizeName(String value) {
    return value.replaceAll("\\s+", "");
  }

  private static String relative(Path root, Path path) {
    Path absoluteRoot = root.toAbsolutePath().normalize();
    Path absolutePath = path.toAbsolutePath().normalize();
    return absolutePath.startsWith(absoluteRoot)
        ? absoluteRoot.relativize(absolutePath).toString()
        : path.toString();
  }

  private static String extension(Path path) {
    String name = path.getFileName().toString();
    int separator = name.lastIndexOf('.');
    return separator < 0 ? "" : name.substring(separator + 1).toLowerCase(java.util.Locale.ROOT);
  }

  private static java.util.Optional<List<String>> findCycle(
      Map<String, Set<String>> graph
  ) {
    Map<String, VisitState> states = new HashMap<>();
    Deque<String> path = new ArrayDeque<>();
    for (String module : new java.util.TreeSet<>(graph.keySet())) {
      java.util.Optional<List<String>> cycle = visit(module, graph, states, path);
      if (cycle.isPresent()) {
        return cycle;
      }
    }
    return java.util.Optional.empty();
  }

  private static java.util.Optional<List<String>> visit(
      String module,
      Map<String, Set<String>> graph,
      Map<String, VisitState> states,
      Deque<String> path
  ) {
    VisitState state = states.get(module);
    if (state == VisitState.COMPLETE) {
      return java.util.Optional.empty();
    }
    if (state == VisitState.ACTIVE) {
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
      return java.util.Optional.of(List.copyOf(cycle));
    }
    states.put(module, VisitState.ACTIVE);
    path.addLast(module);
    for (String dependency : new java.util.TreeSet<>(
        graph.getOrDefault(module, Set.of())
    )) {
      if (!graph.containsKey(dependency)) {
        continue;
      }
      java.util.Optional<List<String>> cycle = visit(dependency, graph, states, path);
      if (cycle.isPresent()) {
        return cycle;
      }
    }
    path.removeLast();
    states.put(module, VisitState.COMPLETE);
    return java.util.Optional.empty();
  }

  private static List<String> sorted(Collection<String> values) {
    List<String> result = new ArrayList<>(values);
    Collections.sort(result);
    return List.copyOf(result);
  }

  private record ParsedJava(
      String packageName,
      String code,
      List<String> exportedPackages
  ) {
  }

  private enum LexicalState {
    CODE,
    LINE_COMMENT,
    BLOCK_COMMENT,
    STRING,
    CHARACTER,
    TEXT_BLOCK
  }

  private enum VisitState {
    ACTIVE,
    COMPLETE
  }
}
