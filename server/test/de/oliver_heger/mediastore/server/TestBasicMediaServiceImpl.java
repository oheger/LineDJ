package de.oliver_heger.mediastore.server;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.HashSet;
import java.util.Set;

import javax.persistence.EntityNotFoundException;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import com.google.appengine.tools.development.testing.LocalDatastoreServiceTestConfig;

import de.oliver_heger.mediastore.server.model.ArtistEntity;
import de.oliver_heger.mediastore.server.model.ArtistSynonym;
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
        final String[] syns = {
                "The King", "Elvis Preslay", "Elvis Pressluft"
        };
        ArtistEntity e = createBasicArtist();
        for (String s : syns)
        {
            ArtistSynonym as = new ArtistSynonym();
            as.setName(s);
            e.addSynonym(as);
        }
        helper.persist(e);
        ArtistDetailInfo info = service.fetchArtistDetails(e.getId());
        Set<String> synonyms = new HashSet<String>(info.getSynonyms());
        for (String s : syns)
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
}
