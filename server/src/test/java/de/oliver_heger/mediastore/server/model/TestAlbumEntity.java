package de.oliver_heger.mediastore.server.model;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import com.google.appengine.api.users.User;
import com.google.appengine.tools.development.testing.LocalDatastoreServiceTestConfig;

import de.oliver_heger.mediastore.RemoteMediaStoreTestHelper;
import de.oliver_heger.mediastore.shared.persistence.PersistenceTestHelper;

/**
 * Test class for {@code AlbumEntity}.
 *
 * @author Oliver Heger
 * @version $Id: $
 */
public class TestAlbumEntity
{
    /** Constant for a test album name. */
    private static final String TEST_NAME = "Misplaced Childhood";

    /** Constant for a synonym prefix. */
    private static final String SYNONYM_PREFIX = "Album Synonym ";

    /** Constant for an inception year. */
    private static final Integer YEAR = 1985;

    /** The test helper. */
    private final PersistenceTestHelper helper = new PersistenceTestHelper(
            new LocalDatastoreServiceTestConfig());

    @Before
    public void setUp() throws Exception
    {
        helper.setUp();
    }

    @After
    public void tearDown() throws Exception
    {
        helper.tearDown();
    }

    /**
     * Creates an album entity with default properties.
     *
     * @return the test album
     */
    private AlbumEntity createAlbum()
    {
        AlbumEntity a = new AlbumEntity();
        a.setName(TEST_NAME);
        a.setUser(PersistenceTestHelper.getTestUser());
        a.setInceptionYear(YEAR);
        return a;
    }

    /**
     * Tests a newly created instance.
     */
    @Test
    public void testInit()
    {
        AlbumEntity a = new AlbumEntity();
        assertNull("Got an ID", a.getId());
        assertNull("Got a name", a.getName());
        assertNull("Got a search name", a.getSearchName());
        assertNull("Got a user", a.getUser());
        assertNull("Got an inception year", a.getInceptionYear());
        RemoteMediaStoreTestHelper.checkCurrentDate(a.getCreationDate());
    }

    /**
     * Tests whether the search name is updated correctly.
     */
    @Test
    public void testSearchName()
    {
        AlbumEntity a = new AlbumEntity();
        a.setName(TEST_NAME);
        assertEquals("Wrong search name",
                TEST_NAME.toUpperCase(Locale.ENGLISH), a.getSearchName());
        a.setName(null);
        assertNull("Got a search name", a.getSearchName());
    }

    /**
     * Tests equals() if the expected result is true.
     */
    @Test
    public void testEqualsTrue()
    {
        AlbumEntity a = new AlbumEntity();
        RemoteMediaStoreTestHelper.checkEquals(a, a, true);
        AlbumEntity a2 = new AlbumEntity();
        RemoteMediaStoreTestHelper.checkEquals(a, a2, true);
        a.setName(TEST_NAME);
        a2.setName(TEST_NAME.toLowerCase(Locale.ENGLISH));
        RemoteMediaStoreTestHelper.checkEquals(a, a2, true);
        a.setUser(PersistenceTestHelper.getTestUser());
        a2.setUser(PersistenceTestHelper.getTestUser());
        RemoteMediaStoreTestHelper.checkEquals(a, a2, true);
        a.setInceptionYear(YEAR);
        a2.setInceptionYear(YEAR);
        RemoteMediaStoreTestHelper.checkEquals(a, a2, true);
    }

    /**
     * Tests equals() if the expected result is false.
     */
    @Test
    public void testEqualsFalse()
    {
        AlbumEntity a = new AlbumEntity();
        AlbumEntity a2 = new AlbumEntity();
        a.setName(TEST_NAME);
        RemoteMediaStoreTestHelper.checkEquals(a, a2, false);
        a2.setName(TEST_NAME + "_other");
        RemoteMediaStoreTestHelper.checkEquals(a, a2, false);
        a2.setName(TEST_NAME);
        a.setUser(PersistenceTestHelper.getTestUser());
        RemoteMediaStoreTestHelper.checkEquals(a, a2, false);
        a2.setUser(PersistenceTestHelper
                .getUser(PersistenceTestHelper.OTHER_USER));
        RemoteMediaStoreTestHelper.checkEquals(a, a2, false);
        a2.setUser(a.getUser());
        a.setInceptionYear(YEAR);
        RemoteMediaStoreTestHelper.checkEquals(a, a2, false);
        a2.setInceptionYear(Integer.valueOf(YEAR.intValue() + 1));
        RemoteMediaStoreTestHelper.checkEquals(a, a2, false);
    }

    /**
     * Tests equals() with other objects.
     */
    @Test
    public void testEqualsTrivial()
    {
        AlbumEntity a = new AlbumEntity();
        a.setName(TEST_NAME);
        RemoteMediaStoreTestHelper.checkEqualsTrivial(a);
    }

    /**
     * Tests the string representation.
     */
    @Test
    public void testToString()
    {
        AlbumEntity a = createAlbum();
        String s = a.toString();
        assertTrue("Name not found: " + s, s.contains("name = " + TEST_NAME));
        assertTrue("Year not found:" + s, s.contains("inceptionYear = " + YEAR));
    }

    /**
     * Tests whether an instance can be serialized.
     */
    @Test
    public void testSerialization() throws IOException
    {
        AlbumEntity a = createAlbum();
        RemoteMediaStoreTestHelper.checkSerialization(a);
    }

    /**
     * Tests whether an instance can be made persistent.
     */
    @Test
    public void testPersist()
    {
        AlbumEntity a = createAlbum();
        helper.persist(a);
        helper.closeEM();
        assertNotNull("No ID", a.getId());
        AlbumEntity a2 = helper.getEM().find(AlbumEntity.class, a.getId());
        assertEquals("Different objects", a, a2);
        assertEquals("Wrong creation date", a.getCreationDate(),
                a2.getCreationDate());
    }

    /**
     * Tests whether album synonyms can be stored, too.
     */
    @Test
    public void testPersistWithSynonyms()
    {
        AlbumEntity a = createAlbum();
        final int synCount = 12;
        Set<String> expSynNames = new HashSet<String>();
        for (int i = 0; i < synCount; i++)
        {
            String synName = SYNONYM_PREFIX + i;
            expSynNames.add(synName);
            assertTrue("Could not add synonym", a.addSynonymName(synName));
        }
        helper.persist(a);
        helper.closeEM();
        a = helper.getEM().find(AlbumEntity.class, a.getId());
        assertEquals("Wrong number of synonyms", synCount, a.getSynonyms()
                .size());
        for (AlbumSynonym syn : a.getSynonyms())
        {
            assertEquals("Wrong album", a, syn.getAlbum());
            assertEquals("Wrong user", PersistenceTestHelper.getTestUser(),
                    syn.getUser());
            assertTrue("Unexpected synonym: " + syn,
                    expSynNames.remove(syn.getName()));
        }
    }

    /**
     * Tries to add a null synonym name.
     */
    @Test(expected = NullPointerException.class)
    public void testAddSynonymNameNull()
    {
        new AlbumEntity().addSynonymName(null);
    }

    /**
     * Tries to add a null synonym.
     */
    @Test(expected = NullPointerException.class)
    public void testAddSynonymNull()
    {
        new AlbumEntity().addSynonym(null);
    }

    /**
     * Tests whether duplicate synonyms are handled correctly.
     */
    @Test
    public void testAddSynonymDuplicate()
    {
        AlbumEntity a = new AlbumEntity();
        assertTrue("Wrong result 1", a.addSynonymName(SYNONYM_PREFIX));
        assertFalse("Wrong result 2", a.addSynonymName(SYNONYM_PREFIX));
        assertEquals("Wrong number of synonyms", 1, a.getSynonyms().size());
    }

    /**
     * Tests whether an existing synonym can be removed.
     */
    @Test
    public void testRemoveSynonymExisting()
    {
        AlbumEntity a = new AlbumEntity();
        AlbumSynonym syn = new AlbumSynonym();
        syn.setName(SYNONYM_PREFIX);
        assertTrue("Wrong result 1", a.addSynonym(syn));
        final String synName = SYNONYM_PREFIX + "_other";
        assertTrue("Wrong result 2", a.addSynonymName(synName));
        assertTrue("Could not remove synonym", a.removeSynonym(syn));
        assertEquals("Wrong number of synonyms", 1, a.getSynonyms().size());
        assertEquals("Wrong synonym name", synName, a.getSynonyms().iterator()
                .next().getName());
    }

    /**
     * Tests removeSynonym() for an unknown synonym.
     */
    @Test
    public void testRemoveSynonymNonExisting()
    {
        AlbumEntity a = new AlbumEntity();
        a.addSynonymName(SYNONYM_PREFIX);
        assertFalse("Wrong result", a.removeSynonymName(TEST_NAME));
        assertEquals("Wrong number of synonyms", 1, a.getSynonyms().size());
    }

    /**
     * Tests removeSynonym() if the synonym belongs to another song.
     */
    @Test
    public void testRemoveSynonymWrongAlbum()
    {
        AlbumSynonym syn = new AlbumSynonym();
        syn.setName(SYNONYM_PREFIX);
        AlbumEntity a1 = new AlbumEntity();
        a1.addSynonym(syn);
        AlbumEntity a2 = new AlbumEntity();
        assertFalse("Wrong result", a2.removeSynonym(syn));
        assertEquals("Wrong number of synonyms", 1, a1.getSynonyms().size());
        assertEquals("Wrong album reference", a1, syn.getAlbum());
    }

    /**
     * Tests removeSynonym() for a null synonym.
     */
    @Test
    public void testRemoveSynonymNull()
    {
        assertFalse("Wrong result", new AlbumEntity().removeSynonym(null));
    }

    /**
     * Retrieves all existing album synonyms.
     *
     * @return the list with the found entities
     */
    private List<AlbumSynonym> fetchAlbumSynonyms()
    {
        @SuppressWarnings("unchecked")
        List<AlbumSynonym> result =
                helper.getEM().createQuery("select s from AlbumSynonym s")
                        .getResultList();
        return result;
    }

    /**
     * Tests whether synonyms can be removed which have already been persisted.
     */
    @Test
    public void testRemovePersistentSynonyms()
    {
        AlbumEntity a = createAlbum();
        a.addSynonymName(SYNONYM_PREFIX);
        helper.persist(a);
        helper.closeEM();
        helper.begin();
        a = helper.getEM().find(AlbumEntity.class, a.getId());
        assertTrue("Wrong result", a.removeSynonymName(SYNONYM_PREFIX));
        helper.commit();
        helper.closeEM();
        a = helper.getEM().find(AlbumEntity.class, a.getId());
        assertTrue("Got synonyms", a.getSynonyms().isEmpty());
        assertTrue("Got synoym entities", fetchAlbumSynonyms().isEmpty());
    }

    /**
     * Tests whether synonyms are removed when their owning album is removed.
     */
    @Test
    public void testRemoveSongCascade()
    {
        final int synCount = 10;
        AlbumEntity a = createAlbum();
        for (int i = 0; i < synCount; i++)
        {
            a.addSynonymName(SYNONYM_PREFIX + i);
        }
        helper.persist(a);
        assertEquals("Wrong number of synonyms (1)", synCount,
                fetchAlbumSynonyms().size());
        helper.begin();
        a = helper.getEM().find(AlbumEntity.class, a.getId());
        helper.getEM().remove(a);
        helper.commit();
        assertTrue("Got synoym entities", fetchAlbumSynonyms().isEmpty());
    }

    /**
     * Creates a number of test albums with unrelated names and synonyms. This
     * method is used to populate the database for tests which query albums.
     */
    private void persistTestAlbums()
    {
        for (int i = 0; i < 32; i++)
        {
            AlbumEntity ae = new AlbumEntity();
            ae.setName("Another Test Album #" + i);
            User usr =
                    (i % 2 == 0) ? PersistenceTestHelper.getTestUser()
                            : PersistenceTestHelper
                                    .getUser(PersistenceTestHelper.OTHER_USER);
            ae.setUser(usr);
            ae.addSynonymName(SYNONYM_PREFIX + i);
            helper.persist(ae);
        }
    }

    /**
     * Tests whether the expected albums were retrieved.
     *
     * @param expAlbumIDs a set with the expected album IDs
     * @param list the list with the retrieved albums
     */
    private void checkRetrievedAlbums(Set<Long> expAlbumIDs,
            List<AlbumEntity> list)
    {
        assertEquals("Wrong number of entities", expAlbumIDs.size(),
                list.size());
        for (AlbumEntity ae : list)
        {
            assertTrue("Unexpected album: " + ae,
                    expAlbumIDs.remove(ae.getId()));
        }
    }

    /**
     * Tests whether albums can be found by name.
     */
    @Test
    public void testFindByName()
    {
        Set<Long> expAlbumIDs = new HashSet<Long>();
        AlbumEntity album = createAlbum();
        helper.persist(album);
        expAlbumIDs.add(album.getId());
        persistTestAlbums();
        album = createAlbum();
        helper.persist(album);
        expAlbumIDs.add(album.getId());
        helper.closeEM();
        List<AlbumEntity> list =
                AlbumEntity.findByName(helper.getEM(),
                        PersistenceTestHelper.getTestUser(), TEST_NAME);
        checkRetrievedAlbums(expAlbumIDs, list);
    }

    /**
     * Tests findByName() if no matching album is found.
     */
    @Test
    public void testFindByNameNoMatch()
    {
        helper.persist(createAlbum());
        assertTrue(
                "Got results",
                AlbumEntity.findByName(
                        helper.getEM(),
                        PersistenceTestHelper
                                .getUser(PersistenceTestHelper.OTHER_USER),
                        TEST_NAME).isEmpty());
    }

    /**
     * Tests whether albums can be found by synonyms.
     */
    @Test
    public void testFindBySynonym()
    {
        Set<Long> expAlbumIDs = new HashSet<Long>();
        AlbumEntity album = createAlbum();
        album.addSynonymName(SYNONYM_PREFIX);
        helper.persist(album);
        expAlbumIDs.add(album.getId());
        persistTestAlbums();
        album = new AlbumEntity();
        album.setName("Another album");
        album.setUser(PersistenceTestHelper.getTestUser());
        album.addSynonymName(SYNONYM_PREFIX);
        album.addSynonymName(SYNONYM_PREFIX + 11);
        helper.persist(album);
        expAlbumIDs.add(album.getId());
        helper.closeEM();
        List<AlbumEntity> list =
                AlbumEntity.findBySynonym(helper.getEM(),
                        PersistenceTestHelper.getTestUser(), SYNONYM_PREFIX);
        checkRetrievedAlbums(expAlbumIDs, list);
    }

    /**
     * Tests findBySynonym() if no matching album can be found.
     */
    @Test
    public void testFindBySynonymNoMatch()
    {
        AlbumEntity album = createAlbum();
        album.addSynonymName(SYNONYM_PREFIX);
        helper.persist(album);
        assertTrue(
                "Got results",
                AlbumEntity.findBySynonym(
                        helper.getEM(),
                        PersistenceTestHelper
                                .getUser(PersistenceTestHelper.OTHER_USER),
                        SYNONYM_PREFIX).isEmpty());
    }

    /**
     * Tests whether an album search can be performed for both the name and the
     * synonyms.
     */
    @Test
    public void testFindByNameAndSynonym()
    {
        Set<Long> expAlbumIDs = new HashSet<Long>();
        AlbumEntity album = createAlbum();
        album.addSynonymName("some synonym");
        helper.persist(album);
        expAlbumIDs.add(album.getId());
        persistTestAlbums();
        album = new AlbumEntity();
        album.setName("Another test album...");
        album.setUser(PersistenceTestHelper.getTestUser());
        album.addSynonymName(TEST_NAME);
        helper.persist(album);
        expAlbumIDs.add(album.getId());
        helper.closeEM();
        List<AlbumEntity> list =
                AlbumEntity.findByNameAndSynonym(helper.getEM(),
                        PersistenceTestHelper.getTestUser(), TEST_NAME);
        checkRetrievedAlbums(expAlbumIDs, list);
    }

    /**
     * Tests a find operation by name and synonym if there is no match.
     */
    @Test
    public void testFindByNameAndSynonymNoMatch()
    {
        helper.persist(createAlbum());
        AlbumEntity album = new AlbumEntity();
        album.setName(SYNONYM_PREFIX);
        album.setUser(PersistenceTestHelper.getTestUser());
        album.addSynonymName(TEST_NAME);
        helper.persist(album);
        persistTestAlbums();
        assertTrue(
                "Got results",
                AlbumEntity.findByNameAndSynonym(
                        helper.getEM(),
                        PersistenceTestHelper
                                .getUser(PersistenceTestHelper.OTHER_USER),
                        TEST_NAME).isEmpty());
    }

    /**
     * Tests whether duplicates are removed when searching for albums by name
     * and synonym.
     */
    @Test
    public void testFindByNameAndSynonymNoDuplicates()
    {
        AlbumEntity album = createAlbum();
        album.addSynonymName(TEST_NAME);
        helper.persist(album);
        persistTestAlbums();
        helper.closeEM();
        List<AlbumEntity> list =
                AlbumEntity.findByNameAndSynonym(helper.getEM(),
                        PersistenceTestHelper.getTestUser(), TEST_NAME);
        Set<Long> expAlbumIDs = new HashSet<Long>();
        expAlbumIDs.add(album.getId());
        checkRetrievedAlbums(expAlbumIDs, list);
    }
}
