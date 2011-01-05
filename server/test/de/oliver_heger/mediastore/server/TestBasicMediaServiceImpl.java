package de.oliver_heger.mediastore.server;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import javax.persistence.EntityNotFoundException;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import com.google.appengine.api.datastore.KeyFactory;
import com.google.appengine.tools.development.testing.LocalDatastoreServiceTestConfig;

import de.oliver_heger.mediastore.server.model.ArtistEntity;
import de.oliver_heger.mediastore.server.model.SongEntity;
import de.oliver_heger.mediastore.shared.SynonymUpdateData;
import de.oliver_heger.mediastore.shared.model.ArtistDetailInfo;
import de.oliver_heger.mediastore.shared.model.SongDetailInfo;
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

    /** Constant for a test song name. */
    private static final String TEST_SONG = "Love me tender";

    /** An array with synonyms of the test song. */
    private static final String[] SONG_SYNONYMS = {
            "Love me tender!", "Love me tenderly"
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
     * Creates a song object which can be used for tests.
     *
     * @param withSyns a flag whether synonyms are to be added
     * @return the test song entity
     */
    private SongEntity createTestSong(boolean withSyns)
    {
        SongEntity e = new SongEntity();
        e.setUser(PersistenceTestHelper.getTestUser());
        e.setDuration(3 * 60 * 1000L);
        e.setName(TEST_SONG);
        e.setInceptionYear(1957);
        if (withSyns)
        {
            for (String syn : SONG_SYNONYMS)
            {
                e.addSynonymName(syn);
            }
        }
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

    /**
     * Tests whether details of a song can be fetched if there are no dependent
     * objects.
     */
    @Test
    public void testFetchSongDetailsSimple()
    {
        SongEntity e = createTestSong(false);
        helper.persist(e);
        String key = KeyFactory.keyToString(e.getId());
        SongDetailInfo info = service.fetchSongDetails(key);
        assertEquals("Wrong ID", key, info.getSongID());
        assertEquals("Wrong name", TEST_SONG, info.getName());
        assertEquals("Wrong year", e.getInceptionYear(),
                info.getInceptionYear());
        assertEquals("Wrong duration", e.getDuration(), info.getDuration());
        assertNull("Got an artist ID", info.getArtistID());
        assertNull("Got an artist name", info.getArtistName());
        assertTrue("Got synonyms", info.getSynonyms().isEmpty());
    }

    /**
     * Tests whether details of a song can be fetched if there are references to
     * other objects.
     */
    @Test
    public void testFetchSongDetailsComplex()
    {
        ArtistEntity art = createBasicArtist();
        helper.persist(art);
        SongEntity e = createTestSong(true);
        e.setArtistID(art.getId());
        helper.persist(e);
        String key = KeyFactory.keyToString(e.getId());
        SongDetailInfo info = service.fetchSongDetails(key);
        assertEquals("Wrong ID", key, info.getSongID());
        assertEquals("Wrong number of synonyms", SONG_SYNONYMS.length, info
                .getSynonyms().size());
        for (String syn : SONG_SYNONYMS)
        {
            assertTrue("Synonym not found: " + syn, info.getSynonyms()
                    .contains(syn));
        }
        assertEquals("Wrong artist ID", art.getId(), info.getArtistID());
        assertEquals("Wrong artist name", art.getName(), info.getArtistName());
    }

    /**
     * Tests fetchSongDetails() if references to other entities cannot be
     * resolved.
     */
    @Test
    public void testFetchSongDetailsUnknownReferences()
    {
        SongEntity e = createTestSong(false);
        e.setArtistID(20110105115008L);
        helper.persist(e);
        SongDetailInfo info =
                service.fetchSongDetails(KeyFactory.keyToString(e.getId()));
        assertNull("Got an artist ID", info.getArtistID());
        assertNull("Got an artist name", info.getArtistName());
    }

    /**
     * Tests whether the user is checked when retrieving details of a song.
     */
    @Test(expected = IllegalStateException.class)
    public void testFetchSongDetailsWrongUser()
    {
        SongEntity song = createTestSong(true);
        song.setUser(PersistenceTestHelper
                .getUser(PersistenceTestHelper.OTHER_USER));
        helper.persist(song);
        service.fetchSongDetails(KeyFactory.keyToString(song.getId()));
    }

    /**
     * Checks whether the song entity contains the specified synonyms
     *
     * @param oldSong the old song entity (it will be refreshed)
     * @param expSyns the synonyms
     */
    private void checkSongSynonyms(SongEntity oldSong,
            Collection<String> expSyns)
    {
        helper.closeEM();
        helper.begin();
        SongEntity song =
                helper.getEM().find(SongEntity.class, oldSong.getId());
        assertEquals("Wrong number of synonyms", expSyns.size(), song
                .getSynonyms().size());
        for (String syn : expSyns)
        {
            assertNotNull("Synonym not found: " + syn, song.findSynonym(syn));
        }
        helper.commit();
    }

    /**
     * Tests whether synonyms can be removed from a song.
     */
    @Test
    public void testUpdateSongSynonymsRemoveSyns()
    {
        SongEntity song = createTestSong(true);
        helper.persist(song);
        String key = KeyFactory.keyToString(song.getId());
        SynonymUpdateData upData =
                new SynonymUpdateData(Collections.singleton(SONG_SYNONYMS[0]),
                        null);
        service.updateSongSynonyms(key, upData);
        checkSongSynonyms(song,
                Arrays.asList(SONG_SYNONYMS).subList(1, SONG_SYNONYMS.length));
    }

    /**
     * Tests that it does not cause a problem to remove a non existing synonym.
     */
    @Test
    public void testUpdateSongSynonymsRemoveSynsUnknown()
    {
        SongEntity song = createTestSong(true);
        helper.persist(song);
        String key = KeyFactory.keyToString(song.getId());
        SynonymUpdateData upData =
                new SynonymUpdateData(
                        Collections.singleton("non existing synonym!"), null);
        service.updateSongSynonyms(key, upData);
        checkSongSynonyms(song, Arrays.asList(SONG_SYNONYMS));
    }

    /**
     * Tests whether new synonyms can be added to a song.
     */
    @Test
    public void testUpdateSongSynonymsNewSyns()
    {
        final String synonymPrefix = "songSynonym";
        final int synCount = 4;
        Set<String> expSyns = new HashSet<String>();
        SongEntity song = createTestSong(true);
        SongEntity synSong = createTestSong(false);
        synSong.setName(synonymPrefix);
        expSyns.add(synonymPrefix);
        for (int i = 0; i < synCount; i++)
        {
            String syn = synonymPrefix + i;
            synSong.addSynonymName(syn);
            expSyns.add(syn);
        }
        expSyns.addAll(Arrays.asList(SONG_SYNONYMS));
        helper.persist(song);
        helper.persist(synSong);
        SynonymUpdateData upData =
                new SynonymUpdateData(null, Collections.singleton(KeyFactory
                        .keyToString(synSong.getId())));
        service.updateSongSynonyms(KeyFactory.keyToString(song.getId()), upData);
        checkSongSynonyms(song, expSyns);
        assertNull("Synonym song still exists",
                helper.getEM().find(SongEntity.class, synSong.getId()));
        List<?> allSynonyms =
                helper.getEM().createQuery("select syn from SongSynonym syn")
                        .getResultList();
        assertEquals("Wrong number of all synonyms", SONG_SYNONYMS.length
                + synCount + 1, allSynonyms.size());
    }

    /**
     * Tries to pass a null object to updateSongSynonyms().
     */
    @Test(expected = NullPointerException.class)
    public void testUpdateSongSynonymsNoUpdateData()
    {
        SongEntity song = createTestSong(false);
        helper.persist(song);
        service.updateSongSynonyms(KeyFactory.keyToString(song.getId()), null);
    }
}
