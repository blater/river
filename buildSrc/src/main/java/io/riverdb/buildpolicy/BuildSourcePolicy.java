package io.riverdb.buildpolicy;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Checks Java package ownership and production-source boundaries. */
final class BuildSourcePolicy {
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

  private BuildSourcePolicy() {
  }

  static List<String> violations(
      Path root,
      Collection<BuildPolicy.JavaSource> javaSources,
      Collection<Path> checkedTextFiles,
      Set<String> checkedTextExtensions,
      Set<String> indentedExtensions,
      Set<String> hotPathPackagePrefixes
  ) {
    List<String> violations = new ArrayList<>();
    BuildTextPolicy.check(
        root, checkedTextFiles, checkedTextExtensions, indentedExtensions, violations);
    Map<String, String> owners = new LinkedHashMap<>();
    Map<BuildPolicy.JavaSource, ParsedJava> parsedSources = parseSources(
        root, javaSources, owners, violations);
    for (Map.Entry<BuildPolicy.JavaSource, ParsedJava> entry : parsedSources.entrySet()) {
      checkSource(
          root,
          entry.getKey(),
          entry.getValue(),
          owners,
          hotPathPackagePrefixes,
          violations
      );
    }
    Collections.sort(violations);
    return List.copyOf(violations);
  }

  private static Map<BuildPolicy.JavaSource, ParsedJava> parseSources(
      Path root,
      Collection<BuildPolicy.JavaSource> sources,
      Map<String, String> owners,
      List<String> violations
  ) {
    List<BuildPolicy.JavaSource> ordered = new ArrayList<>(sources);
    ordered.sort((left, right) -> left.path().compareTo(right.path()));
    Map<BuildPolicy.JavaSource, ParsedJava> parsed = new LinkedHashMap<>();
    for (BuildPolicy.JavaSource source : ordered) {
      if (RAW_UNICODE_ESCAPE.matcher(source.text()).find()) {
        violations.add(relative(root, source.path())
            + ": raw Java Unicode escape is forbidden");
      }
      ParsedJava java = parseJava(source.text());
      parsed.put(source, java);
      recordOwner(root, source, java, owners, violations);
    }
    return parsed;
  }

  private static void recordOwner(
      Path root,
      BuildPolicy.JavaSource source,
      ParsedJava java,
      Map<String, String> owners,
      List<String> violations
  ) {
    if (!isInternalPackage(java.packageName())) {
      return;
    }
    String previous = owners.putIfAbsent(java.packageName(), source.module());
    if (previous != null && !previous.equals(source.module())) {
      violations.add(relative(root, source.path()) + ": internal package "
          + java.packageName() + " is owned by both " + previous + " and " + source.module());
    }
  }

  private static void checkSource(
      Path root,
      BuildPolicy.JavaSource source,
      ParsedJava java,
      Map<String, String> owners,
      Set<String> hotPathPrefixes,
      List<String> violations
  ) {
    String relativePath = relative(root, source.path());
    checkExports(relativePath, java, violations);
    checkInternalReferences(relativePath, source, java, owners, violations);
    if (source.productionSource()) {
      checkProduction(relativePath, java, hotPathPrefixes, violations);
    }
  }

  private static void checkExports(
      String relativePath, ParsedJava java, List<String> violations) {
    for (String packageName : java.exportedPackages()) {
      if (isInternalPackage(packageName)) {
        violations.add(relativePath + ": exports internal package " + packageName);
      }
    }
  }

  private static void checkInternalReferences(
      String relativePath,
      BuildPolicy.JavaSource source,
      ParsedJava java,
      Map<String, String> owners,
      List<String> violations
  ) {
    for (Map.Entry<String, String> owner : owners.entrySet()) {
      if (!owner.getValue().equals(source.module())
          && referencesPackage(java.code(), owner.getKey())) {
        violations.add(relativePath + ": references internal package "
            + owner.getKey() + " owned by " + owner.getValue());
      }
    }
  }

  private static void checkProduction(
      String relativePath,
      ParsedJava java,
      Set<String> hotPathPrefixes,
      List<String> violations
  ) {
    if ("io.riverdb.platform.fault".equals(java.packageName())) {
      violations.add(relativePath + ": production source owns the test-only platform fault package");
    }
    if (referencesPackage(java.code(), "io.riverdb.testkit")) {
      violations.add(relativePath + ": production source references testkit code");
    }
    if (TEST_SUPPORT_IDENTIFIER.matcher(java.code()).find()) {
      violations.add(relativePath
          + ": production source declares or references a test-support identifier");
    }
    if (isHotPathPackage(java.packageName(), hotPathPrefixes)) {
      checkHotPath(relativePath, java.code(), violations);
    }
  }

  private static void checkHotPath(
      String relativePath, String code, List<String> violations) {
    if (STREAM_API.matcher(code).find()) {
      violations.add(relativePath + ": hot-path package references stream/collector APIs");
    }
    if (STRING_FORMAT.matcher(code).find()) {
      violations.add(relativePath + ": hot-path package references string-formatting APIs");
    }
  }

  private static ParsedJava parseJava(String source) {
    String code = JavaSourceSanitizer.strip(source);
    Matcher packageMatcher = PACKAGE.matcher(code);
    String packageName = packageMatcher.find() ? normalizeName(packageMatcher.group(1)) : "";
    List<String> exportedPackages = new ArrayList<>();
    Matcher exportsMatcher = EXPORTS.matcher(code);
    while (exportsMatcher.find()) {
      exportedPackages.add(normalizeName(exportsMatcher.group(1)));
    }
    return new ParsedJava(packageName, code, List.copyOf(exportedPackages));
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
    for (String prefix : prefixes) {
      if (packageName.equals(prefix) || packageName.startsWith(prefix + ".")) {
        return true;
      }
    }
    return false;
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

  private record ParsedJava(
      String packageName,
      String code,
      List<String> exportedPackages
  ) {
  }
}
