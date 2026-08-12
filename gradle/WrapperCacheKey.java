import java.math.BigInteger;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.Properties;

final class WrapperCacheKey {
  private WrapperCacheKey() {
  }

  public static void main(String[] arguments) throws Exception {
    if (arguments.length != 1) {
      throw new IllegalArgumentException("expected wrapper properties path");
    }
    var properties = new Properties();
    try (var input = Files.newInputStream(Path.of(arguments[0]))) {
      properties.load(input);
    }
    requireProperty(properties, "distributionBase", "GRADLE_USER_HOME");
    requireProperty(properties, "distributionPath", "wrapper/dists");
    requireProperty(properties, "zipStoreBase", "GRADLE_USER_HOME");
    requireProperty(properties, "zipStorePath", "wrapper/dists");
    var distribution = URI.create(properties.getProperty("distributionUrl"));
    var path = Path.of(distribution.getPath());
    var archive = path.getFileName().toString();
    var digest = MessageDigest.getInstance("MD5").digest(
      distribution.toString().getBytes(StandardCharsets.UTF_8)
    );
    System.out.println(archive);
    System.out.println(new BigInteger(1, digest).toString(36));
  }

  private static void requireProperty(
    Properties properties,
    String name,
    String expected
  ) {
    var actual = properties.getProperty(name);
    if (!expected.equals(actual)) {
      throw new IllegalArgumentException(
        name + " must be " + expected + ", got " + actual
      );
    }
  }
}
