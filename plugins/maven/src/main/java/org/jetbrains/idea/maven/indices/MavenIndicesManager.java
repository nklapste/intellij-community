package org.jetbrains.idea.maven.indices;

import com.intellij.codeInsight.daemon.DaemonCodeAnalyzer;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.components.ApplicationComponent;
import com.intellij.openapi.extensions.Extensions;
import com.intellij.openapi.progress.*;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.project.ProjectManager;
import com.intellij.openapi.util.JDOMUtil;
import com.intellij.openapi.util.text.StringUtil;
import com.intellij.psi.PsiManager;
import com.intellij.psi.impl.PsiModificationTrackerImpl;
import org.apache.maven.archetype.catalog.Archetype;
import org.apache.maven.archetype.catalog.ArchetypeCatalog;
import org.apache.maven.archetype.source.ArchetypeDataSource;
import org.apache.maven.archetype.source.ArchetypeDataSourceException;
import org.apache.maven.embedder.MavenEmbedder;
import org.codehaus.plexus.PlexusContainer;
import org.codehaus.plexus.component.repository.exception.ComponentLookupException;
import org.jdom.Document;
import org.jdom.Element;
import org.jdom.JDOMException;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.TestOnly;
import org.jetbrains.idea.maven.embedder.MavenEmbedderFactory;
import org.jetbrains.idea.maven.project.MavenGeneralSettings;
import org.jetbrains.idea.maven.project.MavenProcess;
import org.jetbrains.idea.maven.runner.SoutMavenConsole;
import org.jetbrains.idea.maven.utils.MavenLog;
import org.jetbrains.idea.maven.utils.MavenUtil;

import java.io.File;
import java.io.IOException;
import java.util.*;

public class MavenIndicesManager implements ApplicationComponent {
  private static final String ELEMENT_ARCHETYPES = "archetypes";
  private static final String ELEMENT_ARCHETYPE = "archetype";
  private static final String ELEMENT_GROUP_ID = "groupId";
  private static final String ELEMENT_ARTIFACT_ID = "artifactId";
  private static final String ELEMENT_VERSION = "version";
  private static final String ELEMENT_REPOSITORY = "repository";

  public enum IndexUpdatingState {
    IDLE, WAITING, UPDATING
  }

  private volatile File myTestIndicesDir;

  private volatile MavenEmbedder myEmbedder;
  private volatile MavenIndices myIndices;

  private final Object myUpdatingIndicesLock = new Object();
  private final List<MavenIndex> myWaitingIndices = new ArrayList<MavenIndex>();
  private volatile MavenIndex myUpdatingIndex;
  private final BackgroundTaskQueue myUpdatingQueue = new BackgroundTaskQueue(IndicesBundle.message("maven.indices.updating"));

  private volatile List<ArchetypeInfo> myUserArchetypes = new ArrayList<ArchetypeInfo>();

  public static MavenIndicesManager getInstance() {
    return ApplicationManager.getApplication().getComponent(MavenIndicesManager.class);
  }

  @NotNull
  public String getComponentName() {
    return getClass().getSimpleName();
  }

  public void initComponent() {
  }

  @TestOnly
  public void setTestIndexDir(File indicesDir) {
    myTestIndicesDir = indicesDir;
  }

  private synchronized MavenIndices getIndicesObject() {
    ensureInitialized();
    return myIndices;
  }

  private synchronized void ensureInitialized() {
    if (myIndices != null) return;

    MavenGeneralSettings defaultSettings = new MavenGeneralSettings();
    myEmbedder = MavenEmbedderFactory.createEmbedderForExecute(defaultSettings,
                                                               new SoutMavenConsole(),
                                                               new MavenProcess(new EmptyProgressIndicator())).getEmbedder();
    myIndices = new MavenIndices(myEmbedder, getIndicesDir(), new MavenIndex.IndexListener() {
      public void indexIsBroken(MavenIndex index) {
        scheduleUpdate(Collections.singletonList(index), false);
      }
    });

    loadUserArchetypes();
  }

  private File getIndicesDir() {
    return myTestIndicesDir == null
           ? MavenUtil.getPluginSystemDir("Indices")
           : myTestIndicesDir;
  }

  public void disposeComponent() {
    doShutdown();
  }

  @TestOnly
  public void doShutdown() {
    if (myIndices != null) {
      try {
        myIndices.close();
      }
      catch (Exception e) {
        MavenLog.LOG.error("", e);
      }
      myIndices = null;
    }

    if (myEmbedder != null) {
      try {
        myEmbedder.stop();
      }
      catch (Exception e) {
        MavenLog.LOG.error("", e);
      }
      myEmbedder = null;
    }
  }

  public List<MavenIndex> getIndices() {
    return getIndicesObject().getIndices();
  }

  public synchronized List<MavenIndex> ensureIndicesExist(File localRepository,
                                                          Collection<String> remoteRepositories) {
    // MavenIndices.add method returns an existing index if it has already been added, thus we have to use set here.
    LinkedHashSet<MavenIndex> result = new LinkedHashSet<MavenIndex>();

    MavenIndices indicesObjectCache = getIndicesObject();

    try {
      MavenIndex localIndex = indicesObjectCache.add(localRepository.getPath(), MavenIndex.Kind.LOCAL);
      result.add(localIndex);
      if (localIndex.getUpdateTimestamp() == -1) {
        scheduleUpdate(Collections.singletonList(localIndex));
      }
    }
    catch (MavenIndexException e) {
      MavenLog.LOG.warn(e);
    }

    for (String eachRepo : remoteRepositories) {
      try {
        result.add(indicesObjectCache.add(eachRepo, MavenIndex.Kind.REMOTE));
      }
      catch (MavenIndexException e) {
        MavenLog.LOG.warn(e);
      }
    }

    return new ArrayList<MavenIndex>(result);
  }

  public void addArtifact(File artifactFile, String name) {
    File repository = getRepositoryFile(artifactFile, name);

    for (MavenIndex each : getIndices()) {
      if (each.isForLocal(repository)) {
        each.addArtifact(artifactFile);
        return;
      }
    }
  }

  private File getRepositoryFile(File artifactFile, String name) {
    List<String> parts = getArtifactParts(name);

    File result = artifactFile;
    for (int i = 0; i < parts.size(); i++) {
      result = result.getParentFile();
    }
    return result;
  }

  private List<String> getArtifactParts(String name) {
    return StringUtil.split(name, "/");
  }

  public void scheduleUpdate(List<MavenIndex> indices) {
    scheduleUpdate(indices, true);
  }

  private void scheduleUpdate(List<MavenIndex> indices, final boolean fullUpdate) {
    final List<MavenIndex> toSchedule = new ArrayList<MavenIndex>();

    synchronized (myUpdatingIndicesLock) {
      for (MavenIndex each : indices) {
        if (myWaitingIndices.contains(each)) continue;
        toSchedule.add(each);
      }

      myWaitingIndices.addAll(toSchedule);
    }

    myUpdatingQueue.run(new Task.Backgroundable(null, IndicesBundle.message("maven.indices.updating"), true) {
      public void run(@NotNull ProgressIndicator indicator) {
        doUpdateIndices(toSchedule, fullUpdate, indicator);
      }
    });
  }

  private void doUpdateIndices(List<MavenIndex> indices, boolean fullUpdate, ProgressIndicator indicator) {
    List<MavenIndex> remainingWaiting = new ArrayList<MavenIndex>(indices);

    try {
      for (MavenIndex each : indices) {
        if (indicator.isCanceled()) return;

        indicator.setText(IndicesBundle.message("maven.indices.updating.index", each.getRepositoryPathOrUrl()));

        synchronized (myUpdatingIndicesLock) {
          remainingWaiting.remove(each);
          myWaitingIndices.remove(each);
          myUpdatingIndex = each;
        }

        try {
          getIndicesObject().updateOrRepair(each, fullUpdate, indicator);

          ApplicationManager.getApplication().invokeLater(new Runnable() {
            public void run() {
              rehighlightAllPoms();
            }
          });
        }
        finally {
          synchronized (myUpdatingIndicesLock) {
            myUpdatingIndex = null;
          }
        }
      }
    }
    catch (ProcessCanceledException ignore) {
    }
    finally {
      synchronized (myUpdatingIndicesLock) {
        myWaitingIndices.removeAll(remainingWaiting);
      }
    }
  }

  public IndexUpdatingState getUpdatingState(MavenIndex index) {
    synchronized (myUpdatingIndicesLock) {
      if (myUpdatingIndex == index) return IndexUpdatingState.UPDATING;
      if (myWaitingIndices.contains(index)) return IndexUpdatingState.WAITING;
      return IndexUpdatingState.IDLE;
    }
  }

  private void rehighlightAllPoms() {
    ApplicationManager.getApplication().runWriteAction(new Runnable() {
      public void run() {
        for (Project each : ProjectManager.getInstance().getOpenProjects()) {
          ((PsiModificationTrackerImpl)PsiManager.getInstance(each).getModificationTracker()).incCounter();
          DaemonCodeAnalyzer.getInstance(each).restart();
        }
      }
    });
  }

  public synchronized Set<ArchetypeInfo> getArchetypes() {
    ensureInitialized();
    PlexusContainer container = myEmbedder.getPlexusContainer();
    Set<ArchetypeInfo> result = new HashSet<ArchetypeInfo>();
    result.addAll(getArchetypesFrom(container, "internal-catalog"));
    result.addAll(getArchetypesFrom(container, "nexus"));
    result.addAll(myUserArchetypes);

    for (MavenArchetypesProvider each : Extensions.getExtensions(MavenArchetypesProvider.EP_NAME)) {
      result.addAll(each.getArchetypes());
    }
    return result;
  }

  public synchronized void addArchetype(ArchetypeInfo archetype) {
    ensureInitialized();
    myUserArchetypes.add(archetype);
    saveUserArchetypes();
  }

  private void loadUserArchetypes() {
    try {
      File file = getUserArchetypesFile();
      if (!file.exists()) return;
      
      Document doc = JDOMUtil.loadDocument(file);
      Element root = doc.getRootElement();
      if (root == null) return;
      List<ArchetypeInfo> result = new ArrayList<ArchetypeInfo>();
      for (Element each : (Iterable<? extends Element>)root.getChildren(ELEMENT_ARCHETYPE)) {
        String groupId = each.getAttributeValue(ELEMENT_GROUP_ID);
        String artifactId = each.getAttributeValue(ELEMENT_ARTIFACT_ID);
        String version = each.getAttributeValue(ELEMENT_VERSION);
        String repository = each.getAttributeValue(ELEMENT_REPOSITORY);

        if (StringUtil.isEmptyOrSpaces(groupId)
          || StringUtil.isEmptyOrSpaces(artifactId)
          || StringUtil.isEmptyOrSpaces(version)) continue;

        result.add(new ArchetypeInfo(groupId, artifactId, version, repository));
      }

      myUserArchetypes = result;
    }
    catch (IOException e) {
      MavenLog.LOG.warn(e);
    }
    catch (JDOMException e) {
      MavenLog.LOG.warn(e);
    }
  }

  private void saveUserArchetypes() {
    Element root = new Element(ELEMENT_ARCHETYPES);
    for (ArchetypeInfo each : myUserArchetypes) {
      Element childElement = new Element(ELEMENT_ARCHETYPE);
      childElement.setAttribute(ELEMENT_GROUP_ID, each.groupId);
      childElement.setAttribute(ELEMENT_ARTIFACT_ID, each.artifactId);
      childElement.setAttribute(ELEMENT_VERSION, each.version);
      if (each.repository != null) {
        childElement.setAttribute(ELEMENT_REPOSITORY, each.repository);
      }
      root.addContent(childElement);
    }
    try {
      JDOMUtil.writeDocument(new Document(root), getUserArchetypesFile(), "\n");
    }
    catch (IOException e) {
      MavenLog.LOG.warn(e);
    }
  }

  private File getUserArchetypesFile() {
    return new File(getIndicesDir(), "UserArchetypes.xml");
  }

  private List<ArchetypeInfo> getArchetypesFrom(PlexusContainer container, String roleHint) {
    try {
      ArchetypeDataSource source = (ArchetypeDataSource)container.lookup(ArchetypeDataSource.class, roleHint);
      ArchetypeCatalog catalog = source.getArchetypeCatalog(new Properties());

      List<ArchetypeInfo> result = new ArrayList<ArchetypeInfo>();
      for (Archetype each : (Iterable<? extends Archetype>)catalog.getArchetypes()) {
        result.add(new ArchetypeInfo(each));
      }

      return result;
    }
    catch (ComponentLookupException e) {
      MavenLog.LOG.warn(e);
    }
    catch (ArchetypeDataSourceException e) {
      MavenLog.LOG.warn(e);
    }
    return Collections.emptyList();
  }
}
