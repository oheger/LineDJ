package de.oliver_heger.mediastore.server;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import javax.persistence.EntityNotFoundException;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import com.google.appengine.tools.development.testing.LocalDatastoreServiceTestConfig;

import de.oliver_heger.mediastore.server.model.ArtistEntity;
import de.oliver_heger.mediastore.shared.SynonymUpdateData;
import de.oliver_heger.mediastore.shared.model.ArtistDetailInfo;
import de.oliver_heger.mediastore.shared.persistence.PersistenceTestHelper;

/**
 * Test class for {@code BasicMediaServiceImpl}.
 *
 * @author Oliver Heger
 * @version $Id: $
 */
public class TestBasicMediaServiceImpl
{
    /** Constant for a test artist name. */
    private static final String TEST_ARTIST = "Elvis";

    /** An array with synonyms of the test artist. */
    private static final String[] ARTIST_SYNONYMS = {
            "The King", "Elvis Preslay", "Elvis Pressluft"
    };

    /** The persistence test helper. */
    private final PersistenceTestHelper helper = new PersistenceTestHelper(
            new LocalDatastoreServiceTestConfig());

    /** The service to be tested. */
    private BasicMediaServiceImpl service;

    @Before
    public void setUp() throws Exception
    {
        helper.setUp();
        service = new BasicMediaServiceImpl();
    }

    @After
    public void tearDown() throws Exception
    {
        helper.tearDown();
    }

    /**
     * Creates an artist entity with some basic properties.
     *
     * @return the artist entity
     */
    private ArtistEntity createBasicArtist()
    {
        ArtistEntity e = new ArtistEntity();
        e.setName(TEST_ARTIST);
        e.setUser(PersistenceTestHelper.getTestUser());
        return e;
    }

    /**
     * Adds the test synonyms to the test artist.
     *
     * @param e the test artist
     */
    private void appendArtistSynonyms(ArtistEntity e)
    {
        for (String s : ARTIST_SYNONYMS)
        {
            e.addSynonymName(s);
        }
    }

    /**
     * Tests querying details of an artist with only simple properties.
     */
    @Test
    public void testFetchArtistDetailsSimple()
    {
        ArtistEntity e = createBasicArtist();
        helper.persist(e);
        ArtistDetailInfo info = service.fetchArtistDetails(e.getId());
        assertEquals("Wrong artist ID", e.getId(), info.getArtistID());
        assertEquals("Wrong artist name", TEST_ARTIST, info.getName());
        assertEquals("Wrong creation date", e.getCreationDate(),
                info.getCreationDate());
        assertTrue("Got synonyms", info.getSynonyms().isEmpty());
    }

    /**
     * Tests whether the synonyms of an artist can also be fetched.
     */
    @Test
    public void testFetchArtistDetailsWithSynonyms()
    {
        ArtistEntity e = createBasicArtist();
        appendArtistSynonyms(e);
        helper.persist(e);
        ArtistDetailInfo info = service.fetchArtistDetails(e.getId());
        Set<String> synonyms = new HashSet<String>(info.getSynonyms());
        for (String s : ARTIST_SYNONYMS)
        {
            assertTrue("Synonym not found: " + s, synonyms.remove(s));
        }
        assertTrue("Remaining synonyms: " + synonyms, synonyms.isEmpty());
    }

    /**
     * Tests fetchArtistDetails() if the ID cannot be resolved.
     */
    @Test(expected = EntityNotFoundException.class)
    public void testFetchArtistDetailsUnknown()
    {
        ArtistEntity e = createBasicArtist();
        helper.persist(e);
        service.fetchArtistDetails(e.getId().longValue() + 1);
    }

    /**
     * Tests whether fetchArtistDetails() checks whether the logged in user
     * matches the entity's user.
     */
    @Test(expected = IllegalStateException.class)
    public void testFetchArtistDetailsWrongUser()
    {
        ArtistEntity e = createBasicArtist();
        e.setUser(PersistenceTestHelper
                .getUser(PersistenceTestHelper.OTHER_USER));
        helper.persist(e);
        service.fetchArtistDetails(e.getId());
    }

    /**
     * Tests whether a synonym can be removed from an artist.
     */
    @Test
    public void testUpdateArtistSynonymsRemoveSyns()
    {
        ArtistEntity e = createBasicArtist();
        appendArtistSynonyms(e);
        helper.persist(e);
        SynonymUpdateData ud =
                new SynonymUpdateData(
                        Collections.singleton(ARTIST_SYNONYMS[0]), null);
        service.updateArtistSynonyms(e.getId(), ud);
        ArtistDetailInfo info = service.fetchArtistDetails(e.getId());
        assertEquals("Wrong number of synonyms", ARTIST_SYNONYMS.length - 1,
                info.getSynonyms().size());
        for (int i = 1; i < ARTIST_SYNONYMS.length; i++)
        {
            assertTrue("Synonym not found: " + ARTIST_SYNONYMS[i], info
                    .getSynonyms().contains(ARTIST_SYNONYMS[i]));
        }
    }

    /**
     * Tests whether a non existing synonym that should be removed does not
     * cause any problems.
     */
    @Test
    public void testUpdateArtistSynonymsRemoveSynUnknown()
    {
        ArtistEntity e = createBasicArtist();
        appendArtistSynonyms(e);
        helper.persist(e);
        SynonymUpdateData ud =
                new SynonymUpdateData(
                        Collections.singleton("non existing synonym!"), null);
        service.updateArtistSynonyms(e.getId(), ud);
        helper.begin();
        e = helper.getEM().find(ArtistEntity.class, e.getId());
        assertEquals("Wrong number of synonyms", ARTIST_SYNONYMS.length, e
                .getSynonyms().size());
        for (String syn : ARTIST_SYNONYMS)
        {
            assertNotNull("Synonym not found: " + syn, e.findSynonym(syn));
        }
        helper.commit();
    }

    /**
     * Tests whether an artist can be declared a synonym of another artist.
     */
    @Test
    public void testUpdateArtistSynonymsNewSyns()
    {
        ArtistEntity e = createBasicArtist();
        e.addSynonymName(ARTIST_SYNONYMS[0]);
        ArtistEntity eSyn = createBasicArtist();
        eSyn.setName(ARTIST_SYNONYMS[1]);
        for (int i = 2; i < ARTIST_SYNONYMS.length; i++)
        {
            eSyn.addSynonymName(ARTIST_SYNONYMS[i]);
        }
        helper.persist(e);
        helper.persist(eSyn);
        SynonymUpdateData ud =
                new SynonymUpdateData(null, Collections.singleton((Object) eSyn
                        .getId()));
        service.updateArtistSynonyms(e.getId(), ud);
        ArtistDetailInfo info = service.fetchArtistDetails(e.getId());
        assertEquals("Wrong number of synonyms", ARTIST_SYNONYMS.length, info
                .getSynonyms().size());
        for (String syn : ARTIST_SYNONYMS)
        {
            assertTrue("Synonym not found: " + syn, info.getSynonyms()
                    .contains(syn));
        }
        assertNull("Synonym entity still exists",
                helper.getEM().find(ArtistEntity.class, eSyn.getId()));
        List<?> allSynonyms =
                helper.getEM().createQuery("select syn from ArtistSynonym syn")
                        .getResultList();
        assertEquals("Wrong number of all synonyms", ARTIST_SYNONYMS.length,
                allSynonyms.size());
    }

    /**
     * Tests updateArtistSynonyms() if an entity is to be marked as synonym
     * which cannot be retrieved.
     */
    @Test(expected = EntityNotFoundException.class)
    public void testUpdateArtistSynonymsNewSynsNotFound()
    {
        ArtistEntity e = createBasicArtist();
        helper.persist(e);
        SynonymUpdateData ud =
                new SynonymUpdateData(null, Collections.singleton((Object) Long
                        .valueOf(e.getId() + 1)));
        service.updateArtistSynonyms(e.getId(), ud);
    }

    /**
     * Tries to update artist synonyms without passing in an update object.
     */
    @Test(expected = NullPointerException.class)
    public void testUpdateArtistSynonymsNoUpdateData()
    {
        ArtistEntity e = createBasicArtist();
        helper.persist(e);
        service.updateArtistSynonyms(e.getId(), null);
    }
}
