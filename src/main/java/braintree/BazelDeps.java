package braintree;

import com.google.common.collect.Lists;
import org.eclipse.aether.artifact.Artifact;
import org.eclipse.aether.artifact.DefaultArtifact;
import org.eclipse.aether.collection.DependencyCollectionException;
import org.eclipse.aether.repository.RemoteRepository;
import org.kohsuke.args4j.Argument;
import org.kohsuke.args4j.CmdLineException;
import org.kohsuke.args4j.CmdLineParser;
import org.kohsuke.args4j.Option;

import java.util.*;
import java.util.stream.Collectors;

public class BazelDeps {

  @Option(name = "-x", usage = "Exclude a libraries dependencies")
  private List<String> excludeArtifacts = Lists.newArrayList();

  @Option(name = "-r", usage = "Enumerate maven repositories", metaVar = "id@repo")
  private List<String> repos = Lists.newArrayList();

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

    Maven mvn = new Maven(repos);

    Set<String> excludeDependencies =
        excludeArtifacts.stream().map(s -> new DefaultArtifact(s).toString()).collect(Collectors.toSet());

    Map<Artifact, Set<Maven.ArtifactLocation>> dependencies = fetchDependencies(mvn, artifactNames, excludeDependencies);

    printWorkspace(mvn, dependencies);
    printBuildEntries(dependencies);
  }

  private Map<Artifact, Set<Maven.ArtifactLocation>> fetchDependencies(Maven mvn,
                                                                       List<String> artifactNames,
                                                                       Set<String> excludeDependencies) {
    Map<Artifact, Set<Maven.ArtifactLocation>> dependencies = new HashMap<>();

    artifactNames.stream()
        .map(DefaultArtifact::new)
        .forEach(artifact -> dependencies.put(artifact, mvn.transitiveDependencies(artifact, excludeDependencies)));
    return dependencies;
  }

  private void printWorkspace(Maven mvn, Map<Artifact, Set<Maven.ArtifactLocation>> dependencies) {
    System.out.println("\n\n--------- Add these lines to your WORKSPACE file ---------\n");

    mvn.getRepositories().stream()
        .sorted(Comparator.comparing(RemoteRepository::getId))
        .forEach(repo -> {
          System.out.format("maven_server(name=\"%s\", url=\"%s\")\n", repo.getId(), repo.getUrl());
        });
    dependencies.values().stream()
        .flatMap(Collection::stream)
        .sorted(Comparator.comparing(aa -> aa.artifact.getArtifactId()))
        .forEach(artifact -> {
          System.out.format("maven_jar(name = \"%s\", artifact = \"%s\", server = \"%s\")\n",
              artifactName(artifact.artifact),
              artifact.artifact.toString(),
              artifact.repoId
          );
        });
  }

  private void printBuildEntries(Map<Artifact, Set<Maven.ArtifactLocation>> dependencies) {
    System.out.println("\n\n--------- Add these lines to your BUILD file ---------\n");
    dependencies.entrySet().stream()
        .sorted((e1, e2) -> e1.getKey().getArtifactId().compareTo(e2.getKey().getArtifactId()))
        .forEach(entry -> printForBuildFile(entry.getKey(), entry.getValue()));
  }

  private static void printForBuildFile(Artifact artifact, Set<Maven.ArtifactLocation> dependencies) {
    System.out.println("java_library(");
    System.out.println("  name=\"" + artifact.getArtifactId() + "\",");
    System.out.println("  visibility = [\"//visibility:public\"],");
    System.out.println("  exports = [");

    dependencies.stream()
        .map(d -> String.format("    \"@%s//jar\",", artifactName(d.artifact)))
        .sorted()
        .forEach(System.out::println);

    System.out.println("  ],");
    System.out.println(")\n");
  }

  private static String artifactName(Artifact artifact) {
    return artifact.getGroupId() + "_" + artifact.getArtifactId();
  }
}
