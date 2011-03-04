package de.oliver_heger.mediastore.server.model;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
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
 * Test class for {@code ArtistEntity}.
 *
 * @author Oliver Heger
 * @version $Id: $
 */
public class TestArtistEntity
{
    /** Constant for the name of the artist. */
    private static final String TEST_NAME = "Mike Oldfield";

    /** Constant for the prefix for synonyms. */
    private static final String SYN_PREFIX = "TestSynonym";

    /** Constant for the number of synonyms. */
    private static final int SYN_COUNT = 22;

    /** The test helper. */
    private final PersistenceTestHelper helper = new PersistenceTestHelper(
            new LocalDatastoreServiceTestConfig());

    @Before
    public void setUp()
    {
        helper.setUp();
    }

    @After
    public void tearDown()
    {
        helper.tearDown();
    }

    /**
     * Tests a newly created instance.
     */
    @Test
    public void testInit()
    {
        ArtistEntity a = new ArtistEntity();
        assertNull("Got a name", a.getName());
        assertNull("Got a user", a.getUser());
        RemoteMediaStoreTestHelper.checkCurrentDate(a.getCreationDate());
        assertNull("Got a search name", a.getSearchName());
        assertTrue("Got synonyms", a.getSynonyms().isEmpty());
    }

    /**
     * Tests whether the search name is correctly updated.
     */
    @Test
    public void testSearchName()
    {
        ArtistEntity a = new ArtistEntity();
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
        ArtistEntity a1 = new ArtistEntity();
        PersistenceTestHelper.checkEquals(a1, a1, true);
        ArtistEntity a2 = new ArtistEntity();
        PersistenceTestHelper.checkEquals(a1, a2, true);
        a1.setName(TEST_NAME);
        a2.setName(TEST_NAME);
        PersistenceTestHelper.checkEquals(a1, a2, true);
        a2.setName(TEST_NAME.toLowerCase(Locale.ENGLISH));
        PersistenceTestHelper.checkEquals(a1, a2, true);
        a1.setUser(PersistenceTestHelper.getTestUser());
        a2.setUser(PersistenceTestHelper.getTestUser());
        PersistenceTestHelper.checkEquals(a1, a2, true);
    }

    /**
     * Tests equals() if the expected result is false.
     */
    @Test
    public void testEqualsFalse()
    {
        ArtistEntity a1 = new ArtistEntity();
        ArtistEntity a2 = new ArtistEntity();
        a1.setName(TEST_NAME);
        PersistenceTestHelper.checkEquals(a1, a2, false);
        a2.setName(TEST_NAME + "_OTHER");
        PersistenceTestHelper.checkEquals(a1, a2, false);
        a2.setName(TEST_NAME);
        a1.setUser(PersistenceTestHelper.getTestUser());
        PersistenceTestHelper.checkEquals(a1, a2, false);
        a2.setUser(PersistenceTestHelper
                .getUser(PersistenceTestHelper.OTHER_USER));
        PersistenceTestHelper.checkEquals(a1, a2, false);
    }

    /**
     * Tests equals() with other objects.
     */
    @Test
    public void testEqualsTrivial()
    {
        ArtistEntity a = new ArtistEntity();
        a.setName(TEST_NAME);
        PersistenceTestHelper.checkEqualsTrivial(a);
    }

    /**
     * Tests the string representation.
     */
    @Test
    public void testToString()
    {
        ArtistEntity a = new ArtistEntity();
        a.setName(TEST_NAME);
        String s = a.toString();
        assertTrue("Name not found: " + s, s.contains("name = " + TEST_NAME));
    }

    /**
     * Tests whether an artist can be serialized.
     */
    @Test
    public void testSerialization() throws IOException
    {
        ArtistEntity a = new ArtistEntity();
        a.setName(TEST_NAME);
        a.setUser(PersistenceTestHelper.getTestUser());
        PersistenceTestHelper.checkSerialization(a);
    }

    /**
     * Tries to add a null synonym.
     */
    @Test(expected = NullPointerException.class)
    public void testAddSynonymNull()
    {
        ArtistEntity a = new ArtistEntity();
        a.addSynonym(null);
    }

    /**
     * Tests whether an artist can be made persistent.
     */
    @Test
    public void testPersist()
    {
        ArtistEntity a = new ArtistEntity();
        a.setName(TEST_NAME);
        a.setUser(PersistenceTestHelper.getTestUser());
        helper.persist(a);
        helper.closeEM();
        assertNotNull("No ID", a.getId());
        ArtistEntity a2 = helper.getEM().find(ArtistEntity.class, a.getId());
        assertNotSame("Same instance", a, a2);
        assertEquals("Wrong artist instance", a, a2);
        assertEquals("Wrong search name",
                TEST_NAME.toUpperCase(Locale.ENGLISH), a2.getSearchName());
    }

    /**
     * Tests whether an artist with synonyms can be stored.
     */
    @Test
    public void testPersistWithSynonyms()
    {
        final int synCount = 12;
        ArtistEntity a = new ArtistEntity();
        a.setUser(PersistenceTestHelper.getTestUser());
        a.setName(TEST_NAME);
        Set<String> synonyms = new HashSet<String>();
        for (int i = 0; i < synCount; i++)
        {
            ArtistSynonym syn = new ArtistSynonym();
            syn.setName(SYN_PREFIX + i);
            synonyms.add(syn.getName());
            assertTrue("Could not add synonym: " + syn, a.addSynonym(syn));
        }
        helper.persist(a);
        helper.closeEM();
        ArtistEntity a2 = helper.getEM().find(ArtistEntity.class, a.getId());
        assertEquals("Wrong number of synonyms", synCount, a2.getSynonyms()
                .size());
        for (ArtistSynonym syn : a2.getSynonyms())
        {
            assertSame("Wrong artist reference", a2, syn.getArtist());
            assertEquals("Wrong user", PersistenceTestHelper.getTestUser(),
                    syn.getUser());
            assertTrue("Unexpected synonym: " + syn,
                    synonyms.remove(syn.getName()));
        }
    }

    /**
     * Tests whether a null synonym can be removed from an artist.
     */
    @Test
    public void testRemoveSynonymNull()
    {
        ArtistEntity a = new ArtistEntity();
        ArtistSynonym syn = new ArtistSynonym();
        syn.setName(SYN_PREFIX);
        a.addSynonym(syn);
        assertFalse("Wrong result", a.removeSynonym(null));
    }

    /**
     * Tests whether a synonym can be removed that is associated with another
     * artist.
     */
    @Test
    public void testRemoveSynonymWrongArtist()
    {
        ArtistEntity a = new ArtistEntity();
        ArtistSynonym syn = new ArtistSynonym();
        syn.setName(SYN_PREFIX);
        a.addSynonym(syn);
        syn.setArtist(new ArtistEntity());
        assertFalse("Wrong result", a.removeSynonym(syn));
    }

    /**
     * Tests whether synonym names can be added.
     */
    @Test
    public void testAddSynonymName()
    {
        ArtistEntity a = new ArtistEntity();
        Set<String> synNames = new HashSet<String>();
        for (int i = 0; i < SYN_COUNT; i++)
        {
            String syn = SYN_PREFIX + i;
            assertTrue("Wrong result for " + syn, a.addSynonymName(syn));
            synNames.add(syn);
        }
        assertEquals("Wrong number of synonyms", synNames.size(), a
                .getSynonyms().size());
        for (ArtistSynonym as : a.getSynonyms())
        {
            assertTrue("Synonym not found: " + as,
                    synNames.contains(as.getName()));
        }
    }

    /**
     * Tries to add a null synonym name.
     */
    @Test(expected = NullPointerException.class)
    public void testAddSynonymNameNull()
    {
        new ArtistEntity().addSynonymName(null);
    }

    /**
     * Tests whether duplicate synonyms are rejected.
     */
    @Test
    public void testAddSynonymDuplicate()
    {
        ArtistEntity a = new ArtistEntity();
        assertTrue("Could not add synonym", a.addSynonymName(SYN_PREFIX));
        assertFalse("Could add duplicate synonym", a.addSynonymName(SYN_PREFIX));
        ArtistSynonym as = new ArtistSynonym();
        as.setName(SYN_PREFIX);
        assertFalse("Could add duplicate entity", a.addSynonym(as));
        assertNull("Artist was set", as.getArtist());
    }

    /**
     * Tests findSynonym() for an existing synonym.
     */
    @Test
    public void testFindSynonymExisting()
    {
        ArtistEntity a = new ArtistEntity();
        for (int i = 0; i < SYN_COUNT; i++)
        {
            a.addSynonymName(SYN_PREFIX + i);
        }
        String synName = SYN_PREFIX + "0";
        ArtistSynonym as = a.findSynonym(synName);
        assertEquals("Wrong artist synonym", synName, as.getName());
    }

    /**
     * Tests findSynonym() for a non existing synonym.
     */
    @Test
    public void testFindSynonymsNonExisting()
    {
        ArtistEntity a = new ArtistEntity();
        for (int i = 0; i < SYN_COUNT; i++)
        {
            a.addSynonymName(SYN_PREFIX + i);
        }
        assertNull("Got a synonym", a.findSynonym(SYN_PREFIX));
    }

    /**
     * Tests whether an existing synonym name can be removed.
     */
    @Test
    public void testRemoveSynonymNameTrue()
    {
        ArtistEntity a = new ArtistEntity();
        a.setUser(PersistenceTestHelper.getTestUser());
        a.addSynonymName(SYN_PREFIX);
        assertTrue("Wrong result", a.removeSynonymName(SYN_PREFIX));
        assertTrue("Still got synonyms", a.getSynonyms().isEmpty());
    }

    /**
     * Tries to remove a non existing synonym name.
     */
    @Test
    public void testRemoveSynonymNameFalse()
    {
        ArtistEntity a = new ArtistEntity();
        a.setUser(PersistenceTestHelper.getTestUser());
        a.addSynonymName(SYN_PREFIX);
        assertFalse("Wrong result", a.removeSynonymName(SYN_PREFIX + "_other"));
        assertEquals("Wrong number of synonyms", 1, a.getSynonyms().size());
    }

    /**
     * Loads all synonym entities from the database.
     *
     * @return the list with all synonyms found in the DB
     */
    private List<ArtistSynonym> loadSynonyms()
    {
        @SuppressWarnings("unchecked")
        List<ArtistSynonym> result =
                helper.getEM().createQuery("select syn from ArtistSynonym syn")
                        .getResultList();
        return result;
    }

    /**
     * Tests whether synonyms can be removed from a persistent object.
     */
    @Test
    public void testRemovePersistentSynonyms()
    {
        ArtistEntity a = new ArtistEntity();
        a.setName(TEST_NAME);
        a.setUser(PersistenceTestHelper.getTestUser());
        ArtistSynonym syn = new ArtistSynonym();
        syn.setName(SYN_PREFIX);
        a.addSynonym(syn);
        syn = new ArtistSynonym();
        syn.setName(SYN_PREFIX + 1);
        a.addSynonym(syn);
        helper.persist(a);
        helper.closeEM();
        helper.begin();
        a = helper.getEM().find(ArtistEntity.class, a.getId());
        syn = null;
        for (ArtistSynonym as : a.getSynonyms())
        {
            if (SYN_PREFIX.equals(as.getName()))
            {
                syn = as;
                break;
            }
        }
        assertNotNull("Synonym not found", syn);
        a.removeSynonym(syn);
        helper.getEM().remove(syn);
        helper.commit();
        helper.closeEM();
        a = helper.getEM().find(ArtistEntity.class, a.getId());
        assertEquals("Wrong number of synonyms", 1, a.getSynonyms().size());
        syn = a.getSynonyms().iterator().next();
        assertEquals("Wrong synonym", SYN_PREFIX + 1, syn.getName());
        assertEquals("Wrong number of synonyms", 1, loadSynonyms().size());
    }

    /**
     * Adds a number of synonyms to the specified artist entity
     *
     * @param a the artist
     */
    private void addSynonyms(ArtistEntity a)
    {
        for (int i = 0; i < SYN_COUNT; i++)
        {
            ArtistSynonym syn = new ArtistSynonym();
            syn.setName(TEST_NAME + i);
            a.addSynonym(syn);
        }
    }

    /**
     * Tests whether with an artist all synonyms are removed.
     */
    @Test
    public void testRemoveArtistCascading()
    {
        ArtistEntity a = new ArtistEntity();
        addSynonyms(a);
        a.setName(TEST_NAME);
        helper.persist(a);
        ArtistEntity a2 = new ArtistEntity();
        ArtistSynonym syn = new ArtistSynonym();
        syn.setName(SYN_PREFIX);
        a2.addSynonym(syn);
        a2.setName(TEST_NAME + 2);
        helper.persist(a2);
        helper.closeEM();
        helper.begin();
        a = helper.getEM().getReference(ArtistEntity.class, a.getId());
        helper.getEM().remove(a);
        helper.commit();
        List<ArtistSynonym> syns = loadSynonyms();
        assertEquals("Wrong number of synonyms", 1, syns.size());
        assertEquals("Wrong synonym", syn, syns.get(0));
    }

    /**
     * Tests findByName() if a single match is found.
     */
    @Test
    public void testFindByNameSingleMatch()
    {
        for (int i = 0; i < 10; i++)
        {
            ArtistEntity e = new ArtistEntity();
            e.setName(SYN_PREFIX + i);
            User usr =
                    (i % 2 == 0) ? PersistenceTestHelper.getTestUser()
                            : PersistenceTestHelper
                                    .getUser(PersistenceTestHelper.OTHER_USER);
            e.setUser(usr);
            helper.persist(e);
        }
        ArtistEntity e = new ArtistEntity();
        e.setName(TEST_NAME);
        e.setUser(PersistenceTestHelper.getTestUser());
        helper.persist(e);
        e = new ArtistEntity();
        e.setName(TEST_NAME);
        e.setUser(PersistenceTestHelper
                .getUser(PersistenceTestHelper.OTHER_USER));
        helper.persist(e);
        helper.closeEM();
        e =
                ArtistEntity.findByName(helper.getEM(),
                        PersistenceTestHelper.getTestUser(),
                        TEST_NAME.toLowerCase(Locale.ENGLISH));
        assertEquals("Wrong name", TEST_NAME, e.getName());
        assertEquals("Wrong user", PersistenceTestHelper.getTestUser(),
                e.getUser());
    }

    /**
     * Tests findByName() if no match is found.
     */
    @Test
    public void testFindByNameNoMatch()
    {
        assertNull(
                "Got a match",
                ArtistEntity.findByName(helper.getEM(),
                        PersistenceTestHelper.getTestUser(), TEST_NAME));
    }

    /**
     * Tests findBySynonym() if there is a single match.
     */
    @Test
    public void testFindBySynonymSingleMatch()
    {
        ArtistEntity a1 = new ArtistEntity();
        a1.setUser(PersistenceTestHelper.getTestUser());
        a1.setName(TEST_NAME);
        addSynonyms(a1);
        ArtistSynonym syn = new ArtistSynonym();
        syn.setName(SYN_PREFIX);
        a1.addSynonym(syn);
        helper.persist(a1);
        ArtistEntity a2 = new ArtistEntity();
        a2.setUser(PersistenceTestHelper.getTestUser());
        a2.setName(TEST_NAME + "_other");
        addSynonyms(a2);
        helper.persist(a2);
        ArtistEntity a3 = new ArtistEntity();
        a3.setUser(PersistenceTestHelper
                .getUser(PersistenceTestHelper.OTHER_USER));
        a3.setName(TEST_NAME);
        ArtistSynonym syn2 = new ArtistSynonym();
        syn2.setName(SYN_PREFIX);
        a3.addSynonym(syn2);
        helper.persist(a3);
        helper.closeEM();
        ArtistEntity res =
                ArtistEntity.findBySynonym(helper.getEM(),
                        PersistenceTestHelper.getTestUser(),
                        SYN_PREFIX.toLowerCase(Locale.ENGLISH));
        assertEquals("Wrong artist name", TEST_NAME, res.getName());
        assertEquals("Wrong user", PersistenceTestHelper.getTestUser(),
                res.getUser());
    }

    /**
     * Tests findBySynonym() if no match is found.
     */
    @Test
    public void testFindBySynonymNoMatch()
    {
        ArtistEntity a = new ArtistEntity();
        a.setUser(PersistenceTestHelper
                .getUser(PersistenceTestHelper.OTHER_USER));
        a.setName(TEST_NAME);
        addSynonyms(a);
        helper.persist(a);
        assertNull("Got results", ArtistEntity.findBySynonym(helper.getEM(),
                PersistenceTestHelper.getTestUser(), SYN_PREFIX + 0));
    }

    /**
     * A helper method for creating some test artists and synonyms.
     */
    private void createTestArtistsAndSynonyms()
    {
        ArtistEntity a1 = new ArtistEntity();
        a1.setUser(PersistenceTestHelper.getTestUser());
        a1.setName(TEST_NAME);
        ArtistSynonym syn = new ArtistSynonym();
        syn.setName(SYN_PREFIX);
        a1.addSynonym(syn);
        helper.persist(a1);
        ArtistEntity a2 = new ArtistEntity();
        a2.setUser(PersistenceTestHelper.getTestUser());
        a2.setName(TEST_NAME + "_other");
        helper.persist(a2);
        ArtistEntity a3 = new ArtistEntity();
        a3.setUser(PersistenceTestHelper
                .getUser(PersistenceTestHelper.OTHER_USER));
        a3.setName("another artist");
        syn = new ArtistSynonym();
        syn.setName(TEST_NAME);
        a3.addSynonym(syn);
        helper.persist(a3);
        helper.closeEM();
    }

    /**
     * Tests findByNameOrSynonym() if there is an artist with a matching name.
     */
    @Test
    public void testFindByNameOrSynonymNameMatch()
    {
        createTestArtistsAndSynonyms();
        ArtistEntity art =
                ArtistEntity.findByNameOrSynonym(helper.getEM(),
                        PersistenceTestHelper.getTestUser(), TEST_NAME);
        assertEquals("Wrong artist name", TEST_NAME, art.getName());
        assertEquals("Wrong user", PersistenceTestHelper.getTestUser(),
                art.getUser());
    }

    /**
     * Tests findByNameOrSynonym() if there is an artist with a matching
     * synonym.
     */
    @Test
    public void testFindByNameOrSynonymSynMatch()
    {
        createTestArtistsAndSynonyms();
        User usr =
                PersistenceTestHelper.getUser(PersistenceTestHelper.OTHER_USER);
        ArtistEntity art =
                ArtistEntity.findByNameOrSynonym(helper.getEM(), usr,
                        TEST_NAME.toLowerCase(Locale.ENGLISH));
        assertEquals("Wrong user", usr, art.getUser());
        assertEquals("Wrong number of synonyms", 1, art.getSynonyms().size());
        ArtistSynonym syn = art.getSynonyms().iterator().next();
        assertEquals("Wrong synonym", TEST_NAME, syn.getName());
    }
}
