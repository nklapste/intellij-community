package org.jetbrains.idea.maven.indices;

import org.jetbrains.idea.maven.MavenImportingTestCase;

import java.io.File;
import java.util.ArrayList;
import static java.util.Arrays.asList;
import java.util.Collections;
import java.util.List;
import java.util.Set;

public class MavenIndicesManagerTest extends MavenImportingTestCase {
  private MavenIndicesTestFixture myIndicesFixture;

  @Override
  protected void setUp() throws Exception {
    super.setUp();
    myIndicesFixture = new MavenIndicesTestFixture(myDir, myProject);
    myIndicesFixture.setUp();
  }

  @Override
  protected void tearDown() throws Exception {
    myIndicesFixture.tearDown();
    super.tearDown();
  }

  public void testEnsuringLocalRepositoryIndex() throws Exception {
    File dir1 = myIndicesFixture.getRepositoryHelper().getTestData("dir/foo");
    File dir2 = myIndicesFixture.getRepositoryHelper().getTestData("dir\\foo");
    File dir3 = myIndicesFixture.getRepositoryHelper().getTestData("dir\\foo\\");
    File dir4 = myIndicesFixture.getRepositoryHelper().getTestData("dir/bar");

    List<MavenIndex> indices1 = myIndicesFixture.getIndicesManager().ensureIndicesExist(dir1, Collections.<String>emptySet());
    assertEquals(1, indices1.size());
    assertTrue(myIndicesFixture.getIndicesManager().getIndices().contains(indices1.get(0)));

    assertEquals(indices1, myIndicesFixture.getIndicesManager().ensureIndicesExist(dir2, Collections.<String>emptySet()));
    assertEquals(indices1, myIndicesFixture.getIndicesManager().ensureIndicesExist(dir3, Collections.<String>emptySet()));

    List<MavenIndex> indices2 = myIndicesFixture.getIndicesManager().ensureIndicesExist(dir4, Collections.<String>emptySet());
    assertFalse(indices1.get(0).equals(indices2.get(0)));
  }

  public void testEnsuringRemoteRepositoryIndex() throws Exception {
    File local = myIndicesFixture.getRepositoryHelper().getTestData("dir");
    String remote1 = "http://foo/bar";
    String remote2 = "  http://foo\\bar\\\\  ";
    String remote3 = "http://foo\\bar\\baz";

    List<MavenIndex> indices1 = myIndicesFixture.getIndicesManager().ensureIndicesExist(local, Collections.singleton(remote1));
    assertEquals(2, indices1.size());

    List<MavenIndex> indices2 = myIndicesFixture.getIndicesManager().ensureIndicesExist(local, asList(remote1, remote2));
    assertEquals(2, indices2.size());

    List<MavenIndex> indices3 = myIndicesFixture.getIndicesManager().ensureIndicesExist(local, asList(remote1, remote2, remote3));
    assertEquals(3, indices3.size());
  }

  public void testDefaultArchetypes() throws Exception {
    assertArchetypeExists("org.apache.maven.archetypes:maven-archetype-quickstart:RELEASE");
  }

  public void testIndexedArchetypes() throws Exception {
    myIndicesFixture.getRepositoryHelper().addTestData("archetypes");
    myIndicesFixture.getIndicesManager().ensureIndicesExist(myIndicesFixture.getRepositoryHelper().getTestData("archetypes"),
                                                            Collections.<String>emptySet());

    assertArchetypeExists("org.apache.maven.archetypes:maven-archetype-foobar:1.0");
  }

  public void testIndexedArchetypesWithSeveralIndicesAfterReopening() throws Exception {
    myIndicesFixture.getRepositoryHelper().addTestData("archetypes");
    myIndicesFixture.getIndicesManager().ensureIndicesExist(myIndicesFixture.getRepositoryHelper().getTestData("archetypes"),
                                                            Collections.<String>singletonList("foo://bar.baz"));

    assertArchetypeExists("org.apache.maven.archetypes:maven-archetype-foobar:1.0");

    myIndicesFixture.tearDown();
    myIndicesFixture.setUp();

    assertArchetypeExists("org.apache.maven.archetypes:maven-archetype-foobar:1.0");
  }

  public void testAddingArchetypes() throws Exception {
    myIndicesFixture.getIndicesManager().addArchetype(new ArchetypeInfo("myGroup",
                                                                        "myArtifact",
                                                                        "666",
                                                                        "custom.repository"));

    assertArchetypeExists("myGroup:myArtifact:666");

    myIndicesFixture.tearDown();
    myIndicesFixture.setUp();

    assertArchetypeExists("myGroup:myArtifact:666");
  }

  private void assertArchetypeExists(String archetypeId) {
    Set<ArchetypeInfo> achetypes = myIndicesFixture.getIndicesManager().getArchetypes();
    List<String> actualNames = new ArrayList<String>();
    for (ArchetypeInfo each : achetypes) {
      actualNames.add(each.groupId + ":" + each.artifactId + ":" + each.version);
    }
    assertTrue(actualNames.toString(), actualNames.contains(archetypeId));
  }
}