package braintree;

import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Lists;
import com.google.common.collect.Sets;
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
import org.eclipse.aether.spi.connector.RepositoryConnectorFactory;
import org.eclipse.aether.spi.connector.transport.TransporterFactory;
import org.eclipse.aether.transport.file.FileTransporterFactory;
import org.eclipse.aether.transport.http.HttpTransporterFactory;
import org.eclipse.aether.util.graph.visitor.FilteringDependencyVisitor;
import org.eclipse.aether.util.graph.visitor.PreorderNodeListGenerator;

import java.net.URI;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

public class Maven {

  private final RepositorySystem system;
  private final DefaultRepositorySystemSession session;
  private final List<RemoteRepository> repositories;

  public Maven(List<URI> repoList) {
    system = newRepositorySystem();
    session = newRepositorySystemSession(system);
    repositories = repositories(repoList);
  }

  public Set<Artifact> transitiveDependencies(Artifact artifact, Set<String> excluded) {

    System.err.println("Collecting artifacts for " + artifact.getArtifactId() + "...\n");

    CollectRequest collectRequest = new CollectRequest();
    collectRequest.setRoot(new Dependency(artifact, ""));
    collectRequest.setRepositories(repositories);

    CollectResult collectResult = null;
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
            .map(DependencyNode::getArtifact)
            .map((Artifact a) -> {
              System.err.println("    " + a.getArtifactId() + " as dependency");
              return a;
            })
            .collect(Collectors.toList()));
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


  static URI central = URI.create("http://central.maven.org/maven2/");

  public List<RemoteRepository> repositories(List<URI> repoList) {
    LinkedHashSet<URI> givenUrls = Sets.newLinkedHashSet(repoList);
    if (!givenUrls.contains(central)) {
      givenUrls.add(central);
    }
    final int[] id = {0};
    return Lists.transform(repoList, uri -> new RemoteRepository.Builder("uri" + id[0]++,
        "default",
        uri.toASCIIString()).build());
  }
}
