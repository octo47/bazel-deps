package braintree;

import com.google.common.collect.Lists;
import org.eclipse.aether.artifact.Artifact;
import org.eclipse.aether.artifact.DefaultArtifact;
import org.eclipse.aether.collection.DependencyCollectionException;
import org.kohsuke.args4j.Argument;
import org.kohsuke.args4j.CmdLineException;
import org.kohsuke.args4j.CmdLineParser;
import org.kohsuke.args4j.Option;

import java.net.URI;
import java.util.*;
import java.util.stream.Collectors;

public class BazelDeps {

  @Option(name = "-x", usage = "Exclude a libraries dependencies")
  private List<String> excludeArtifacts = Lists.newArrayList();

  @Option(name = "-r", usage = "Enumerate maven repositories")
  private List<URI> repoUris = Lists.newArrayList();

  @Argument(usage = "<artifact id>")
  private List<String> artifactNames = new ArrayList<>();

  public static void main(String[] args) throws DependencyCollectionException, CmdLineException {
    new BazelDeps().doMain(args);
  }

  public void doMain(String[] args) throws DependencyCollectionException, CmdLineException {
    CmdLineParser parser = new CmdLineParser(this);
    parser.parseArgument(args);

    if (artifactNames.isEmpty()) {
      System.out.print("Usage: java -jar bazel-deps-1.0-SNAPSHOT");
      parser.printSingleLineUsage(System.out);
      System.out.println();
      parser.printUsage(System.out);
      System.out.println(
          "\nExample: java -jar bazel-deps-1.0-SNAPSHOT com.fasterxml.jackson.core:jackson-databind:2.5.0");
      System.exit(1);
    }

    System.err.println("Fetching dependencies from maven...\n");

    Maven mvn = new Maven(repoUris);

    Set<String> excludeDependencies =
        excludeArtifacts.stream().map(s -> new DefaultArtifact(s).toString()).collect(Collectors.toSet());

    Map<Artifact, Set<Artifact>> dependencies = fetchDependencies(mvn, artifactNames, excludeDependencies);

    printWorkspace(dependencies);
    printBuildEntries(dependencies);
  }

  private Map<Artifact, Set<Artifact>> fetchDependencies(Maven mvn,
                                                         List<String> artifactNames,
                                                         Set<String> excludeDependencies) {
    Map<Artifact, Set<Artifact>> dependencies = new HashMap<>();

    artifactNames.stream()
        .map(DefaultArtifact::new)
        .forEach(artifact -> dependencies.put(artifact, mvn.transitiveDependencies(artifact, excludeDependencies)));
    return dependencies;
  }

  private void printWorkspace(Map<Artifact, Set<Artifact>> dependencies) {
    System.out.println("\n\n--------- Add these lines to your WORKSPACE file ---------\n");
    dependencies.values().stream()
        .flatMap(Collection::stream)
        .sorted(Comparator.comparing(Artifact::getArtifactId))
        .forEach(artifact -> {
          System.out.format("maven_jar(name = \"%s\", artifact = \"%s\")\n", artifactName(artifact),
              artifact.toString());
        });
  }

  private void printBuildEntries(Map<Artifact, Set<Artifact>> dependencies) {
    System.out.println("\n\n--------- Add these lines to your BUILD file ---------\n");
    dependencies.entrySet().stream()
        .sorted((e1, e2) -> e1.getKey().getArtifactId().compareTo(e2.getKey().getArtifactId()))
        .forEach(entry -> printForBuildFile(entry.getKey(), entry.getValue()));
  }

  private static void printForBuildFile(Artifact artifact, Set<Artifact> dependencies) {
    System.out.println("java_library(");
    System.out.println("  name=\"" + artifact.getArtifactId() + "\",");
    System.out.println("  visibility = [\"//visibility:public\"],");
    System.out.println("  exports = [");

    dependencies.stream()
        .map(d -> String.format("    \"@%s//jar\",", artifactName(d)))
        .sorted()
        .forEach(System.out::println);

    System.out.println("  ],");
    System.out.println(")\n");
  }

  private static String artifactName(Artifact artifact) {
    return artifact.getGroupId() + "_" + artifact.getArtifactId();
  }
}
