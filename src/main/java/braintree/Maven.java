package braintree;

import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Lists;
import org.apache.maven.repository.internal.MavenRepositorySystemUtils;
import org.eclipse.aether.DefaultRepositorySystemSession;
import org.eclipse.aether.RepositorySystem;
import org.eclipse.aether.artifact.Artifact;
import org.eclipse.aether.collection.CollectRequest;
import org.eclipse.aether.collection.CollectResult;
import org.eclipse.aether.collection.DependencyCollectionException;
import org.eclipse.aether.connector.basic.BasicRepositoryConnectorFactory;
import org.eclipse.aether.graph.Dependency;
import org.eclipse.aether.graph.DependencyNode;
import org.eclipse.aether.impl.DefaultServiceLocator;
import org.eclipse.aether.repository.LocalRepository;
import org.eclipse.aether.repository.RemoteRepository;
import org.eclipse.aether.resolution.ArtifactRequest;
import org.eclipse.aether.resolution.ArtifactResolutionException;
import org.eclipse.aether.resolution.ArtifactResult;
import org.eclipse.aether.spi.connector.RepositoryConnectorFactory;
import org.eclipse.aether.spi.connector.transport.TransporterFactory;
import org.eclipse.aether.transport.file.FileTransporterFactory;
import org.eclipse.aether.transport.http.HttpTransporterFactory;
import org.eclipse.aether.util.graph.visitor.FilteringDependencyVisitor;
import org.eclipse.aether.util.graph.visitor.PreorderNodeListGenerator;

import java.net.URI;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

public class Maven {


  private final RepositorySystem system;
  private final DefaultRepositorySystemSession session;
  private final List<RemoteRepository> repositories;

  public Maven(List<String> repoList) {
    system = newRepositorySystem();
    session = newRepositorySystemSession(system);
    repositories = repositories(repoList);
  }

  /**
   * Resolve transitive dependencies
   *
   * @param artifact what to resolve
   * @param excluded set of excluded artifacts
   * @return map of { artifact -> repo }
   */
  public Set<ArtifactLocation> transitiveDependencies(Artifact artifact, Set<String> excluded) {

    System.err.println("Collecting artifacts for " + artifact.getArtifactId() + "...\n");

    CollectRequest collectRequest = new CollectRequest();
    collectRequest.setRoot(new Dependency(artifact, ""));
    collectRequest.setRepositories(repositories);

    CollectResult collectResult;
    try {
      collectResult = system.collectDependencies(session, collectRequest);
    } catch (DependencyCollectionException e) {
      throw new RuntimeException(e);
    }

    PreorderNodeListGenerator visitor = new PreorderNodeListGenerator();
    collectResult.getRoot().accept(new FilteringDependencyVisitor(visitor,
        (node, parents) -> !excluded.contains(node.getArtifact().toString())));

    return ImmutableSet.copyOf(
        visitor.getNodes().stream()
            .filter(d -> !d.getDependency().isOptional())
            .map((d) -> {
              ArtifactResult result = resolveArtifact(d);
              return new ArtifactLocation(result.getArtifact(), result.getRepository().getId());
            })
            .collect(Collectors.toSet()));
  }

  private ArtifactResult resolveArtifact(DependencyNode d) {
    ArtifactRequest request = new ArtifactRequest();
    request.setArtifact(d.getArtifact());
    request.setDependencyNode(d);
    request.setRepositories(d.getRepositories());
    try {
      return system.resolveArtifact(session, request);
    } catch (ArtifactResolutionException e) {
      throw new RuntimeException(e);
    }
  }

  private RepositorySystem newRepositorySystem() {
    DefaultServiceLocator locator = MavenRepositorySystemUtils.newServiceLocator();
    locator.addService(RepositoryConnectorFactory.class, BasicRepositoryConnectorFactory.class);
    locator.addService(TransporterFactory.class, FileTransporterFactory.class);
    locator.addService(TransporterFactory.class, HttpTransporterFactory.class);

    locator.setErrorHandler(new DefaultServiceLocator.ErrorHandler() {
      @Override
      public void serviceCreationFailed(Class<?> type, Class<?> impl, Throwable exception) {
        exception.printStackTrace();
      }
    });

    return locator.getService(RepositorySystem.class);
  }

  public DefaultRepositorySystemSession newRepositorySystemSession(RepositorySystem system) {
    DefaultRepositorySystemSession session = MavenRepositorySystemUtils.newSession();

    LocalRepository localRepo = new LocalRepository("/tmp/bazel-deps-repo");
    session.setLocalRepositoryManager(system.newLocalRepositoryManager(session, localRepo));

    return session;
  }


  static String central = "central@http://central.maven.org/maven2/";

  public List<RemoteRepository> repositories(List<String> repoList) {
    List<String> list = Lists.newArrayList(central);
    list.addAll(repoList);
    return list.stream().map((String repo) -> {
      String[] parts = repo.split("@", 2);
      if (parts.length != 2) {
        throw new IllegalArgumentException("Expected repo in form of: id@uri");
      }
      System.out.format("Adding repo: %s %s\n", parts[0], parts[1]);
      return new RemoteRepository.Builder(
          parts[0],
          "default",
          URI.create(parts[1]).toASCIIString()).build();
    }).collect(Collectors.toList());
  }

  public List<RemoteRepository> getRepositories() {
    return repositories;
  }

  /**
   * Describes artifact location
   */
  public static class ArtifactLocation {
    public final Artifact artifact;
    public final String repoId;

    public ArtifactLocation(Artifact artifact, String repoId) {
      this.artifact = artifact;
      this.repoId = repoId;
    }

    @Override
    public boolean equals(Object o) {
      if (this == o) return true;
      if (o == null || getClass() != o.getClass()) return false;

      ArtifactLocation that = (ArtifactLocation) o;

      if (artifact != null ? !artifact.equals(that.artifact) : that.artifact != null) return false;
      return repoId != null ? repoId.equals(that.repoId) : that.repoId == null;

    }

    @Override
    public int hashCode() {
      int result = artifact != null ? artifact.hashCode() : 0;
      result = 31 * result + (repoId != null ? repoId.hashCode() : 0);
      return result;
    }
  }

}
