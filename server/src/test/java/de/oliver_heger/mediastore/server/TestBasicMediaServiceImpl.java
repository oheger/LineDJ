package de.oliver_heger.mediastore.server;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.persistence.EntityNotFoundException;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import com.google.appengine.api.datastore.Key;
import com.google.appengine.api.datastore.KeyFactory;
import com.google.appengine.tools.development.testing.LocalDatastoreServiceTestConfig;

import de.oliver_heger.mediastore.server.model.AlbumEntity;
import de.oliver_heger.mediastore.server.model.AlbumSynonym;
import de.oliver_heger.mediastore.server.model.ArtistEntity;
import de.oliver_heger.mediastore.server.model.Finders;
import de.oliver_heger.mediastore.server.model.SongEntity;
import de.oliver_heger.mediastore.shared.SynonymUpdateData;
import de.oliver_heger.mediastore.shared.model.AlbumDetailInfo;
import de.oliver_heger.mediastore.shared.model.AlbumInfo;
import de.oliver_heger.mediastore.shared.model.ArtistDetailInfo;
import de.oliver_heger.mediastore.shared.model.ArtistInfo;
import de.oliver_heger.mediastore.shared.model.SongDetailInfo;
import de.oliver_heger.mediastore.shared.model.SongInfo;
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

    /** Constant for the name of a test album. */
    private static final String TEST_ALBUM = "Test Album of Elvis";

    /** An array with synonyms of the test album. */
    private static final String[] ALBUM_SYNONYMS = {
            "Test Elvis #1", "1st Elvis Test", "Elvis for Testing",
            "Test songs by Elvis"
    };

    /** Constant for the duration of a test song. */
    private static final long DURATION = 3 * 60 * 1000L;

    /** Constant for the inception year of a test song. */
    private static final int YEAR = 1957;

    /** The persistence test helper. */
    private final PersistenceTestHelper helper = new PersistenceTestHelper(
            new LocalDatastoreServiceTestConfig());

    /** A counter for the generation of names. */
    private int counter;

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
        e.setDuration(DURATION);
        e.setName(TEST_SONG);
        e.setInceptionYear(YEAR);
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
     * Creates and persists a number of test songs which can be associated with
     * other entities.
     *
     * @param songCount the number of songs to create
     * @param art the associated artist (can be <b>null</b>)
     * @param album the associated album (can be <b>null</b>)
     * @return a set with the names of the songs created by this method
     */
    private Set<String> createTestSongs(int songCount, ArtistEntity art,
            AlbumEntity album)
    {
        Set<String> songNames = createTestSongNames(songCount);
        for (String name : songNames)
        {
            SongEntity song = createTestSong(false);
            song.setName(name);
            if (art != null)
            {
                song.setArtistID(art.getId());
            }
            if (album != null)
            {
                song.setAlbumID(album.getId());
            }
            helper.persist(song);
        }
        return songNames;
    }

    /**
     * Returns a set with the names of the test songs generated by
     * {@code createTestSongs()}.
     *
     * @param songCount the number of test songs
     * @return a set with the names of these test songs
     */
    private Set<String> createTestSongNames(int songCount)
    {
        Set<String> names = new HashSet<String>();
        for (int i = 0; i < songCount; i++)
        {
            names.add(TEST_SONG + counter);
            counter++;
        }
        return names;
    }

    /**
     * Creates a simple album entity instance.
     *
     * @return the test album
     */
    private AlbumEntity createBasicAlbum()
    {
        AlbumEntity album = new AlbumEntity();
        album.setName(TEST_ALBUM);
        album.setUser(PersistenceTestHelper.getTestUser());
        return album;
    }

    /**
     * Adds the test synonyms to the specified album.
     *
     * @param e the album entity
     */
    private void appendAlbumSynonyms(AlbumEntity e)
    {
        for (String syn : ALBUM_SYNONYMS)
        {
            e.addSynonymName(syn);
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
        assertTrue("Got songs", info.getSongs().isEmpty());
        assertTrue("Got albums", info.getAlbums().isEmpty());
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
     * Tests whether the artist has the expected songs.
     *
     * @param info the artist info object
     * @param expNames a set with the expected song names
     */
    private void checkSongsOfArtist(ArtistDetailInfo info, Set<String> expNames)
    {
        assertEquals("Wrong number of songs", expNames.size(), info.getSongs()
                .size());
        for (SongInfo si : info.getSongs())
        {
            assertTrue("Unexpected song: " + si, expNames.remove(si.getName()));
            assertEquals("Wrong artist ID", info.getArtistID(),
                    si.getArtistID());
        }
    }

    /**
     * Tests whether the songs of an artist can also be fetched.
     */
    @Test
    public void testFetchArtistDetailsWithSongs()
    {
        ArtistEntity e = createBasicArtist();
        helper.persist(e);
        final int songCount = 8;
        Set<String> expNames = createTestSongs(songCount, e, null);
        ArtistDetailInfo info = service.fetchArtistDetails(e.getId());
        checkSongsOfArtist(info, expNames);
    }

    /**
     * Tests whether albums related to the artist can be retrieved.
     */
    @Test
    public void testFetchArtistDetailsWithAlbums()
    {
        ArtistEntity art = createBasicArtist();
        helper.persist(art);
        Set<String> songNames = createTestSongs(2, art, null);
        Set<String> albumNames = new HashSet<String>();
        final int albumCount = 4;
        for (int i = 0; i < albumCount; i++)
        {
            AlbumEntity album = createBasicAlbum();
            album.setName(album.getName() + i);
            helper.persist(album);
            albumNames.add(album.getName());
            songNames.addAll(createTestSongs(i + 1, art, album));
        }
        ArtistDetailInfo info = service.fetchArtistDetails(art.getId());
        checkSongsOfArtist(info, songNames);
        Set<String> songAlbums = new HashSet<String>();
        for (SongInfo si : info.getSongs())
        {
            if (si.getAlbumName() != null)
            {
                songAlbums.add(si.getAlbumName());
            }
        }
        assertEquals("Album names not resolved", albumNames, songAlbums);
        assertEquals("Wrong number of albums", albumCount, info.getAlbums()
                .size());
        for (AlbumInfo album : info.getAlbums())
        {
            assertTrue("Album not found: " + album,
                    albumNames.remove(album.getName()));
        }
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
        helper.closeEM();
        SynonymUpdateData ud =
                new SynonymUpdateData(null, Collections.singleton(eSyn.getId()));
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
     * Tests whether songs are copied when an artist becomes a synonym of
     * another one.
     */
    @Test
    public void testUpdateArtistSynonymsNewSynsWithSongs()
    {
        ArtistEntity e = createBasicArtist();
        e.addSynonymName("A test synonym");
        helper.persist(e);
        ArtistEntity eSyn = createBasicArtist();
        eSyn.setName(ARTIST_SYNONYMS[0]);
        eSyn.addSynonymName(ARTIST_SYNONYMS[1]);
        helper.persist(eSyn);
        SongEntity song = createTestSong(true);
        song.setArtistID(eSyn.getId());
        helper.persist(song);
        SongEntity song2 = createTestSong(false);
        song2.setName("Another Song");
        song2.setArtistID(eSyn.getId());
        helper.persist(song2);
        helper.closeEM();
        SynonymUpdateData ud =
                new SynonymUpdateData(null, Collections.singleton(eSyn.getId()));
        service.updateArtistSynonyms(e.getId(), ud);
        helper.closeEM();
        List<SongEntity> songs = SongEntity.findByArtist(helper.getEM(), e);
        assertEquals("Wrong number of songs", 2, songs.size());
        Set<String> songNames = new HashSet<String>();
        for (SongEntity se : songs)
        {
            songNames.add(se.getName());
        }
        assertTrue("Song 1 not found", songNames.contains(song.getName()));
        assertTrue("Song 2 not found", songNames.contains(song2.getName()));
        assertTrue("Got songs for synonym",
                SongEntity.findByArtist(helper.getEM(), eSyn).isEmpty());
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
     * Tests whether an invalid user is detected when updating synonyms of an
     * artist.
     */
    @Test
    public void testUpdateArtistSynonymsInvalidUser()
    {
        ArtistEntity a1 = new ArtistEntity();
        a1.setName(ARTIST_SYNONYMS[0]);
        a1.setUser(PersistenceTestHelper
                .getUser(PersistenceTestHelper.OTHER_USER));
        helper.persist(a1);
        ArtistEntity artist = createBasicArtist();
        helper.persist(artist);
        final int songCount = 12;
        createTestSongs(songCount, artist, null);
        SynonymUpdateData ud =
                new SynonymUpdateData(null, Collections.singleton(artist
                        .getId()));
        try
        {
            service.updateArtistSynonyms(a1.getId(), ud);
            fail("Invalid user not detected!");
        }
        catch (IllegalStateException istex)
        {
            // ok
        }
        assertTrue("Songs were moved",
                Finders.findSongsByArtist(helper.getEM(), a1).isEmpty());
        assertEquals("Wrong song count", songCount,
                Finders.findSongsByArtist(helper.getEM(), artist).size());
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
        List<?> allSyns =
                helper.getEM().createQuery("select s from SongSynonym s")
                        .getResultList();
        assertEquals("Wrong number of synonyms", SONG_SYNONYMS.length - 1,
                allSyns.size());
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

    /**
     * Tests whether details of an album can be fetched if not much information
     * is available.
     */
    @Test
    public void testFetchAlbumDetailsSimple()
    {
        AlbumEntity album = createBasicAlbum();
        helper.persist(album);
        AlbumDetailInfo info = service.fetchAlbumDetails(album.getId());
        assertEquals("Wrong albumID", album.getId(), info.getAlbumID());
        assertEquals("Wrong name", TEST_ALBUM, info.getName());
        assertTrue("Got synonyms", info.getSynonyms().isEmpty());
        assertEquals("Got songs", 0, info.getNumberOfSongs());
        assertTrue("Got song entities", info.getSongs().isEmpty());
        assertNull("Got a duration", info.getDuration());
        assertNull("Got a year", info.getInceptionYear());
        assertTrue("Got artists", info.getArtists().isEmpty());
    }

    /**
     * Tests whether the synonyms of an album can be retrieved.
     */
    @Test
    public void testFetchAlbumDetailsWithSynonyms()
    {
        AlbumEntity album = createBasicAlbum();
        appendAlbumSynonyms(album);
        helper.persist(album);
        helper.closeEM();
        AlbumDetailInfo info = service.fetchAlbumDetails(album.getId());
        assertEquals("Wrong number of synonyms", ALBUM_SYNONYMS.length, info
                .getSynonyms().size());
        for (String syn : ALBUM_SYNONYMS)
        {
            assertTrue("Synonym not found: " + syn, info.getSynonyms()
                    .contains(syn));
        }
    }

    /**
     * Checks whether the correct songs of an album have been retrieved.
     *
     * @param info the album info object
     * @param songs the set with the expected song names
     */
    private void checkSongsOfAlbum(AlbumDetailInfo info, Set<String> songs)
    {
        assertEquals("Wrong number of songs", songs.size(), info.getSongs()
                .size());
        for (SongInfo si : info.getSongs())
        {
            assertTrue("Song not found: " + si, songs.remove(si.getName()));
            assertEquals("Wrong album ID", info.getAlbumID(), si.getAlbumID());
        }
    }

    /**
     * Tests whether the songs of an album can be retrieved.
     */
    @Test
    public void testFetchAlbumWithSongs()
    {
        final int songCount = 16;
        AlbumEntity album = createBasicAlbum();
        helper.persist(album);
        Set<String> songs = createTestSongs(songCount, null, album);
        AlbumDetailInfo info = service.fetchAlbumDetails(album.getId());
        checkSongsOfAlbum(info, songs);
        assertEquals("Wrong number of songs", songCount,
                info.getNumberOfSongs());
        assertEquals("Wrong inception year", YEAR, info.getInceptionYear()
                .intValue());
        assertEquals("Wrong duration", songCount * DURATION, info.getDuration()
                .longValue());
    }

    /**
     * Tests whether information about the artists of an album can be fetched.
     */
    @Test
    public void testFetchAlbumWithArtists()
    {
        AlbumEntity album = createBasicAlbum();
        helper.persist(album);
        Set<String> songs = createTestSongs(4, null, album);
        final int artistCount = 4;
        Map<Long, ArtistEntity> artists = new HashMap<Long, ArtistEntity>();
        for (int i = 0; i < artistCount; i++)
        {
            ArtistEntity art = createBasicArtist();
            art.setName(art.getName() + i);
            helper.persist(art);
            artists.put(art.getId(), art);
            songs.addAll(createTestSongs(i + 1, art, album));
        }
        helper.closeEM();
        AlbumDetailInfo info = service.fetchAlbumDetails(album.getId());
        checkSongsOfAlbum(info, songs);
        Set<Long> artistIDs = new HashSet<Long>();
        for (SongInfo si : info.getSongs())
        {
            if (si.getArtistID() != null)
            {
                artistIDs.add(si.getArtistID());
            }
        }
        assertTrue("Not all artist IDs could be resolved",
                artistIDs.containsAll(artists.keySet()));
        assertEquals("Wrong number of artists", artistCount, info.getArtists()
                .size());
        for (ArtistInfo ai : info.getArtists())
        {
            ArtistEntity art = artists.remove(ai.getArtistID());
            assertNotNull("Unexpected artist: " + ai, art);
            assertEquals("Wrong artist name", art.getName(), ai.getName());
        }
    }

    /**
     * Tests whether a wrong user is detected when fetching details of an album.
     */
    @Test(expected = IllegalStateException.class)
    public void testFetchAlbumDetailsWrongUser()
    {
        AlbumEntity album = createBasicAlbum();
        album.setUser(PersistenceTestHelper
                .getUser(PersistenceTestHelper.OTHER_USER));
        helper.persist(album);
        service.fetchAlbumDetails(album.getId());
    }

    /**
     * Helper method for checking the synonyms of an album.
     *
     * @param album the album entity
     * @param expSynonyms the expected synonyms
     */
    private void checkAlbumSynonyms(AlbumEntity album,
            Collection<String> expSynonyms)
    {
        helper.closeEM();
        helper.begin();
        AlbumEntity ae = helper.getEM().find(AlbumEntity.class, album.getId());
        assertEquals("Wrong number of synonyms", expSynonyms.size(), ae
                .getSynonyms().size());
        Set<String> syns = new HashSet<String>(expSynonyms);
        for (AlbumSynonym as : ae.getSynonyms())
        {
            assertTrue("Unexpected synonym: " + as, syns.remove(as.getName()));
        }
        helper.commit();
    }

    /**
     * Tests whether synonyms can be removed from an album.
     */
    @Test
    public void testUpdateAlbumSynonymsRemoveSyns()
    {
        AlbumEntity album = createBasicAlbum();
        appendAlbumSynonyms(album);
        helper.persist(album);
        SynonymUpdateData ud =
                new SynonymUpdateData(Collections.singleton(ALBUM_SYNONYMS[0]),
                        null);
        service.updateAlbumSynonyms(album.getId(), ud);
        checkAlbumSynonyms(album,
                Arrays.asList(ALBUM_SYNONYMS).subList(1, ALBUM_SYNONYMS.length));
    }

    /**
     * Tests that removing an unknown synonym does not have any effect.
     */
    @Test
    public void testUpdateAlbumSynonymsRemoveSynsUnknown()
    {
        AlbumEntity album = createBasicAlbum();
        appendAlbumSynonyms(album);
        helper.persist(album);
        SynonymUpdateData ud =
                new SynonymUpdateData(Collections.singleton("unknown synoyn"),
                        null);
        service.updateAlbumSynonyms(album.getId(), ud);
        checkAlbumSynonyms(album, Arrays.asList(ALBUM_SYNONYMS));
    }

    /**
     * Tests whether an album can be declared a synonym of another album.
     */
    @Test
    public void testUpdateAlbumSynonymsNewSyns()
    {
        AlbumEntity album = createBasicAlbum();
        album.addSynonymName(ALBUM_SYNONYMS[0]);
        helper.persist(album);
        AlbumEntity albSyn = createBasicAlbum();
        albSyn.setName(ALBUM_SYNONYMS[1]);
        for (int i = 2; i < ALBUM_SYNONYMS.length; i++)
        {
            albSyn.addSynonymName(ALBUM_SYNONYMS[i]);
        }
        helper.persist(albSyn);
        SynonymUpdateData ud =
                new SynonymUpdateData(null, Collections.singleton(albSyn
                        .getId()));
        service.updateAlbumSynonyms(album.getId(), ud);
        checkAlbumSynonyms(album, Arrays.asList(ALBUM_SYNONYMS));
        assertNull("Synonym album still found",
                helper.getEM().find(AlbumEntity.class, albSyn.getId()));
    }

    /**
     * Tests whether songs are copied when one album becomes a synonym of
     * another one.
     */
    @Test
    public void testUpdateAlbumSynonymsNewSynsWithSongs()
    {
        AlbumEntity album = createBasicAlbum();
        helper.persist(album);
        Set<String> songNames = createTestSongs(4, null, album);
        AlbumEntity albSyn = createBasicAlbum();
        albSyn.setName(ALBUM_SYNONYMS[0]);
        helper.persist(albSyn);
        songNames.addAll(createTestSongs(6, null, albSyn));
        SynonymUpdateData ud =
                new SynonymUpdateData(null, Collections.singleton(albSyn
                        .getId()));
        service.updateAlbumSynonyms(album.getId(), ud);
        helper.closeEM();
        List<SongEntity> songs =
                Finders.findSongsByAlbum(helper.getEM(), album);
        assertEquals("Wrong number of songs", songNames.size(), songs.size());
        for (SongEntity se : songs)
        {
            assertTrue("Unexpected song: " + se, songNames.remove(se.getName()));
        }
        assertNull("Synonym album still found",
                helper.getEM().find(AlbumEntity.class, albSyn.getId()));
    }

    /**
     * Tries to update an album's synonyms without an update data object.
     */
    @Test(expected = NullPointerException.class)
    public void testUpdateAlbumSynonymsNoUpdateData()
    {
        AlbumEntity album = createBasicAlbum();
        helper.persist(album);
        service.updateAlbumSynonyms(album.getId(), null);
    }

    /**
     * Tests whether an invalid user is detected when updating synonyms of an
     * album.
     */
    @Test
    public void testUpdateAlbumSynonymsInvalidUser()
    {
        AlbumEntity a1 = new AlbumEntity();
        a1.setName(ALBUM_SYNONYMS[0]);
        a1.setUser(PersistenceTestHelper
                .getUser(PersistenceTestHelper.OTHER_USER));
        helper.persist(a1);
        AlbumEntity album = createBasicAlbum();
        helper.persist(album);
        final int songCount = 12;
        createTestSongs(songCount, null, album);
        SynonymUpdateData ud =
                new SynonymUpdateData(null,
                        Collections.singleton(album.getId()));
        try
        {
            service.updateAlbumSynonyms(a1.getId(), ud);
            fail("Invalid user not detected!");
        }
        catch (IllegalStateException istex)
        {
            // ok
        }
        assertTrue("Songs were moved",
                Finders.findSongsByAlbum(helper.getEM(), a1).isEmpty());
        assertEquals("Wrong song count", songCount,
                Finders.findSongsByAlbum(helper.getEM(), album).size());
    }

    /**
     * Tests whether a non existing album can be removed.
     */
    @Test
    public void testRemoveAlbumNotExisting()
    {
        AlbumEntity album = createBasicAlbum();
        helper.persist(album);
        long albumID = album.getId().longValue();
        helper.begin();
        album = helper.getEM().merge(album);
        helper.getEM().remove(album);
        helper.commit();
        assertFalse("Wrong result", service.removeAlbum(albumID));
    }

    /**
     * Tests whether an album without related objects can be removed.
     */
    @Test
    public void testRemoveAlbumSimple()
    {
        AlbumEntity album = createBasicAlbum();
        helper.persist(album);
        AlbumEntity a2 = createBasicAlbum();
        a2.setUser(PersistenceTestHelper
                .getUser(PersistenceTestHelper.OTHER_USER));
        helper.persist(a2);
        assertTrue("Wrong result",
                service.removeAlbum(album.getId().longValue()));
        assertNull("Album still found",
                helper.getEM().find(AlbumEntity.class, album.getId()));
        assertNotNull("Other album removed",
                helper.getEM().find(AlbumEntity.class, a2.getId()));
    }

    /**
     * Tests whether an album can be removed which has references to other
     * entities.
     */
    @Test
    public void testRemoveAlbumWithReferences()
    {
        AlbumEntity album = createBasicAlbum();
        appendAlbumSynonyms(album);
        helper.persist(album);
        Set<String> songNames = createTestSongs(42, null, album);
        assertTrue("Wrong result", service.removeAlbum(album.getId()));
        helper.closeEM();
        List<?> syns =
                helper.getEM().createQuery("select s from AlbumSynonym s")
                        .getResultList();
        assertTrue("Still got synonyms", syns.isEmpty());
        List<SongEntity> songs = loadAllSongs();
        assertEquals("Wrong number of songs", songNames.size(), songs.size());
        for (SongEntity song : songs)
        {
            assertTrue("Unexpected song name: " + song,
                    songNames.remove(song.getName()));
            assertNull("Still got an album ID", song.getAlbumID());
        }
    }

    /**
     * Helper method for loading all songs in the database.
     *
     * @return the list with all songs
     */
    private List<SongEntity> loadAllSongs()
    {
        @SuppressWarnings("unchecked")
        List<SongEntity> songs =
                helper.getEM().createQuery("select s from SongEntity s")
                        .getResultList();
        return songs;
    }

    /**
     * Tests that an album of a different user cannot be removed.
     */
    @Test
    public void testRemoveAlbumWrongUser()
    {
        AlbumEntity album = createBasicAlbum();
        album.setUser(PersistenceTestHelper
                .getUser(PersistenceTestHelper.OTHER_USER));
        helper.persist(album);
        Set<String> songNames = createTestSongs(10, null, album);
        try
        {
            service.removeAlbum(album.getId());
            fail("Could remove album of other user!");
        }
        catch (IllegalStateException istex)
        {
            // ok
        }
        List<SongEntity> songs =
                Finders.findSongsByAlbum(helper.getEM(), album);
        assertEquals("Songs have been removed", songNames.size(), songs.size());
    }

    /**
     * Tests whether a non existing artist can be removed.
     */
    @Test
    public void testRemoveArtistNotExisting()
    {
        ArtistEntity artist = createBasicArtist();
        helper.persist(artist);
        long artistID = artist.getId().longValue();
        helper.begin();
        artist = helper.getEM().merge(artist);
        helper.getEM().remove(artist);
        helper.commit();
        assertFalse("Wrong result", service.removeArtist(artistID));
    }

    /**
     * Tests whether an artist without related objects can be removed.
     */
    @Test
    public void testRemoveArtistSimple()
    {
        ArtistEntity artist = createBasicArtist();
        helper.persist(artist);
        ArtistEntity a2 = createBasicArtist();
        a2.setUser(PersistenceTestHelper
                .getUser(PersistenceTestHelper.OTHER_USER));
        helper.persist(a2);
        assertTrue("Wrong result",
                service.removeArtist(artist.getId().longValue()));
        assertNull("Artist still found",
                helper.getEM().find(ArtistEntity.class, artist.getId()));
        assertNotNull("Other artist removed",
                helper.getEM().find(ArtistEntity.class, a2.getId()));
    }

    /**
     * Tests whether an artist can be removed which has references to other
     * entities.
     */
    @Test
    public void testRemoveArtistWithReferences()
    {
        ArtistEntity artist = createBasicArtist();
        appendArtistSynonyms(artist);
        helper.persist(artist);
        Set<String> songNames = createTestSongs(32, artist, null);
        assertTrue("Wrong result", service.removeArtist(artist.getId()));
        helper.closeEM();
        List<?> syns =
                helper.getEM().createQuery("select s from ArtistSynonym s")
                        .getResultList();
        assertTrue("Still got synonyms", syns.isEmpty());
        List<SongEntity> songs = loadAllSongs();
        assertEquals("Wrong number of songs", songNames.size(), songs.size());
        for (SongEntity song : songs)
        {
            assertTrue("Unexpected song name: " + song,
                    songNames.remove(song.getName()));
            assertNull("Still got an artist ID", song.getArtistID());
        }
    }

    /**
     * Tests that an artist of a different user cannot be removed.
     */
    @Test
    public void testRemoveArtistWrongUser()
    {
        ArtistEntity artist = createBasicArtist();
        artist.setUser(PersistenceTestHelper
                .getUser(PersistenceTestHelper.OTHER_USER));
        helper.persist(artist);
        Set<String> songNames = createTestSongs(8, artist, null);
        try
        {
            service.removeArtist(artist.getId());
            fail("Could remove artist of other user!");
        }
        catch (IllegalStateException istex)
        {
            // ok
        }
        List<SongEntity> songs =
                Finders.findSongsByArtist(helper.getEM(), artist);
        assertEquals("Songs have been removed", songNames.size(), songs.size());
    }

    /**
     * Tests whether a non existing song can be removed.
     */
    @Test
    public void testRemoveSongNotExisting()
    {
        SongEntity song = createTestSong(false);
        helper.persist(song);
        Key songID = song.getId();
        helper.begin();
        song = helper.getEM().merge(song);
        helper.getEM().remove(song);
        helper.commit();
        assertFalse("Wrong result",
                service.removeSong(KeyFactory.keyToString(songID)));
    }

    /**
     * Helper method for testing whether a song can be removed.
     *
     * @param withSyns a flag whether synonyms should be involved
     * @return the ID of the song used for testing
     */
    private void checkRemoveSong(boolean withSyns)
    {
        SongEntity song = createTestSong(withSyns);
        helper.persist(song);
        SongEntity song2 = createTestSong(false);
        song2.setUser(PersistenceTestHelper
                .getUser(PersistenceTestHelper.OTHER_USER));
        helper.persist(song2);
        helper.closeEM();
        assertTrue("Wrong result",
                service.removeSong(KeyFactory.keyToString(song.getId())));
        assertNull("Song still found",
                helper.getEM().find(SongEntity.class, song.getId()));
        assertNotNull("Other song removed",
                helper.getEM().find(SongEntity.class, song2.getId()));
    }

    /**
     * Tests whether a song without references to other objects can be removed.
     */
    @Test
    public void testRemoveSongSimple()
    {
        checkRemoveSong(false);
    }

    /**
     * Tests whether a song with synonyms can be removed.
     */
    @Test
    public void testRemoveSongWithSynonyms()
    {
        checkRemoveSong(true);
        List<?> syns =
                helper.getEM().createQuery("select s from SongSynonym s")
                        .getResultList();
        assertTrue("Still got synonyms", syns.isEmpty());
    }

    /**
     * Tests that it is not allowed to remove a song from another user.
     */
    @Test
    public void testRemoveSongWrongUser()
    {
        SongEntity song = createTestSong(true);
        song.setUser(PersistenceTestHelper
                .getUser(PersistenceTestHelper.OTHER_USER));
        helper.persist(song);
        helper.closeEM();
        try
        {
            service.removeSong(KeyFactory.keyToString(song.getId()));
            fail("Could remove song from other user!");
        }
        catch (IllegalStateException istex)
        {
            // ok
        }
        song = helper.getEM().find(SongEntity.class, song.getId());
        assertFalse("No synonyms", song.getSynonyms().isEmpty());
    }
}
