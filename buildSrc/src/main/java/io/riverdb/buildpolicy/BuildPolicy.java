package io.riverdb.buildpolicy;

import org.gradle.api.artifacts.Configuration;

import java.nio.file.Path;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Stable facade for the build's source and dependency policies. */
public final class BuildPolicy {
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

  public static List<String> sourceViolations(
      Path root,
      Collection<JavaSource> javaSources,
      Collection<Path> checkedTextFiles,
      Set<String> checkedTextExtensions,
      Set<String> indentedExtensions,
      Set<String> hotPathPackagePrefixes
  ) {
    return BuildSourcePolicy.violations(
        root,
        javaSources,
        checkedTextFiles,
        checkedTextExtensions,
        indentedExtensions,
        hotPathPackagePrefixes
    );
  }

  public static List<String> graphViolations(
      Map<String, Set<String>> actualGraph,
      Map<String, Set<String>> allowedGraph
  ) {
    return BuildGraphPolicy.violations(actualGraph, allowedGraph);
  }

  public static Set<String> inheritedProjectDependencies(
      Collection<Configuration> mainClasspaths
  ) {
    return BuildGraphPolicy.inheritedProjectDependencies(mainClasspaths);
  }
}
