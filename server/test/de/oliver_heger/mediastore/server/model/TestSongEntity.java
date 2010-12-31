package de.oliver_heger.mediastore.server.model;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
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

import com.google.appengine.tools.development.testing.LocalDatastoreServiceTestConfig;

import de.oliver_heger.mediastore.shared.RemoteMediaStoreTestHelper;
import de.oliver_heger.mediastore.shared.persistence.PersistenceTestHelper;

/**
 * Test class for {@code SongEntity}.
 *
 * @author hacker
 * @version $Id: $
 */
public class TestSongEntity
{
    /** Constant for the name of a song. */
    private static final String SONG_NAME = "Hotel California";

    /** Constant for a synonym prefix. */
    private static final String SYN_PREFIX = "SongSynonym_";

    /** Constant for an artist ID. */
    private static final Long ARTIST_ID = 20101230204631L;

    /** Constant for the duration. */
    private static final Long DURATION = (6 * 60 + 30) * 1000L;

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
     * Creates a song entity populated with test data.
     *
     * @return the test entity
     */
    private SongEntity createSong()
    {
        SongEntity song = new SongEntity();
        song.setName(SONG_NAME);
        song.setDuration(DURATION);
        song.setUser(PersistenceTestHelper.getTestUser());
        song.setTrackNo(2);
        song.setInceptionYear(1979);
        return song;
    }

    /**
     * Creates a test artist which can be associated with the test song.
     *
     * @return the test artist
     */
    private ArtistEntity createArtist()
    {
        ArtistEntity artist = new ArtistEntity();
        artist.setName("The Eagles");
        artist.setUser(PersistenceTestHelper.getTestUser());
        return artist;
    }

    /**
     * Tests a newly created instance.
     */
    @Test
    public void testInit()
    {
        SongEntity song = new SongEntity();
        assertNull("Got a user", song.getUser());
        assertNull("Got an artist", song.getArtistID());
        assertNull("Got a key", song.getId());
        assertNull("Got a name", song.getName());
        assertNull("Got a search name", song.getSearchName());
        RemoteMediaStoreTestHelper.checkCurrentDate(song.getCreationDate());
        assertEquals("Wrong play count", 0, song.getPlayCount());
    }

    /**
     * Tests whether the search name of the song is correctly generated.
     */
    @Test
    public void testGetSearchName()
    {
        SongEntity song = new SongEntity();
        song.setName(SONG_NAME);
        assertEquals("Wrong search name",
                SONG_NAME.toUpperCase(Locale.ENGLISH), song.getSearchName());
        song.setName(null);
        assertNull("Got a search name", song.getSearchName());
    }

    /**
     * Tests equals() if the expected result is true.
     */
    @Test
    public void testEqualsTrue()
    {
        SongEntity song1 = new SongEntity();
        RemoteMediaStoreTestHelper.checkEquals(song1, song1, true);
        SongEntity song2 = new SongEntity();
        RemoteMediaStoreTestHelper.checkEquals(song1, song2, true);
        song1.setName(SONG_NAME);
        song2.setName(SONG_NAME.toLowerCase(Locale.ENGLISH));
        RemoteMediaStoreTestHelper.checkEquals(song1, song2, true);
        song1.setDuration(DURATION);
        song2.setDuration(DURATION);
        song1.setUser(PersistenceTestHelper.getTestUser());
        song2.setUser(PersistenceTestHelper.getTestUser());
        song1.setArtistID(ARTIST_ID);
        song2.setArtistID(ARTIST_ID);
        RemoteMediaStoreTestHelper.checkEquals(song1, song2, true);
        song1.setInceptionYear(1979);
        song1.setTrackNo(2);
        song1.setPlayCount(100);
        RemoteMediaStoreTestHelper.checkEquals(song1, song2, true);
    }

    /**
     * Tests equals() if the expected result is false.
     */
    @Test
    public void testEqualsFalse()
    {
        SongEntity song1 = new SongEntity();
        SongEntity song2 = new SongEntity();
        song1.setUser(PersistenceTestHelper.getTestUser());
        RemoteMediaStoreTestHelper.checkEquals(song1, song2, false);
        song2.setUser(PersistenceTestHelper
                .getUser(PersistenceTestHelper.OTHER_USER));
        RemoteMediaStoreTestHelper.checkEquals(song1, song2, false);
        song2.setUser(song1.getUser());
        song1.setArtistID(ARTIST_ID);
        RemoteMediaStoreTestHelper.checkEquals(song1, song2, false);
        song2.setArtistID(ARTIST_ID + 1);
        RemoteMediaStoreTestHelper.checkEquals(song1, song2, false);
        song2.setArtistID(ARTIST_ID);
        song1.setName(SONG_NAME);
        RemoteMediaStoreTestHelper.checkEquals(song1, song2, false);
        song2.setName(SONG_NAME + "_other");
        RemoteMediaStoreTestHelper.checkEquals(song1, song2, false);
        song2.setName(SONG_NAME);
        song1.setDuration(DURATION);
        RemoteMediaStoreTestHelper.checkEquals(song1, song2, false);
        song2.setDuration(Long.valueOf(DURATION.longValue() + 111));
        RemoteMediaStoreTestHelper.checkEquals(song1, song2, false);
    }

    /**
     * Tests equals() with other objects.
     */
    @Test
    public void testEqualsTrivial()
    {
        RemoteMediaStoreTestHelper.checkEqualsTrivial(createSong());
    }

    /**
     * Tests the string representation.
     */
    @Test
    public void testToString()
    {
        SongEntity song = createSong();
        song.setArtistID(ARTIST_ID);
        String s = song.toString();
        assertTrue("Name not found: " + s, s.contains("name = " + SONG_NAME));
        assertTrue("Duration not found: " + s,
                s.contains("duration = " + DURATION + " ms"));
        assertTrue("Artist not found: " + s,
                s.contains("artistID = " + ARTIST_ID));
    }

    /**
     * Tests the string representation for an empty song object.
     */
    @Test
    public void testToStringNoData()
    {
        String s = new SongEntity().toString();
        assertTrue("Name not found: " + s, s.contains("name = null"));
        assertFalse("Got a duration: " + s, s.contains("duration ="));
        assertFalse("Got an artist: " + s, s.contains("artist ="));
    }

    /**
     * Tests whether an object can be serialized.
     */
    @Test
    public void testSerialization() throws IOException
    {
        SongEntity song = createSong();
        PersistenceTestHelper.checkSerialization(song);
    }

    /**
     * Tests whether a simple entity can be persisted.
     */
    @Test
    public void testPersist()
    {
        SongEntity song = createSong();
        ArtistEntity artist = createArtist();
        helper.persist(artist);
        song.setArtistID(artist.getId());
        helper.persist(song);
        helper.closeEM();
        assertNotNull("No key", song.getId());
        song = helper.getEM().find(SongEntity.class, song.getId());
        SongEntity songRef = createSong();
        songRef.setArtistID(artist.getId());
        assertEquals("Different song", songRef, song);
        assertEquals("Wrong track", songRef.getTrackNo(), song.getTrackNo());
        assertEquals("Wrong year", songRef.getInceptionYear(),
                song.getInceptionYear());
        assertEquals("Wrong play count", songRef.getPlayCount(),
                song.getPlayCount());
        assertEquals("Wrong artist ID", artist.getId(), song.getArtistID());
    }

    /**
     * Tests whether a song can be persisted without an artist.
     */
    @Test
    public void testPersistNoArtist()
    {
        SongEntity song = createSong();
        helper.persist(song);
        song = helper.getEM().find(SongEntity.class, song.getId());
        assertNull("Got an artist", song.getArtistID());
    }

    /**
     * Tests whether synonyms can be persisted for a song.
     */
    @Test
    public void testPersistWithSynonyms()
    {
        final int synCount = 8;
        ArtistEntity art = createArtist();
        helper.persist(art);
        SongEntity song = createSong();
        song.setArtistID(art.getId());
        Set<String> expSynonyms = new HashSet<String>();
        for (int i = 0; i < synCount; i++)
        {
            String synName = SYN_PREFIX + i;
            expSynonyms.add(synName);
            assertTrue("Wrong result", song.addSynonymName(synName));
        }
        helper.persist(song);
        helper.closeEM();
        song = helper.getEM().find(SongEntity.class, song.getId());
        assertEquals("Wrong number of synonyms", synCount, song.getSynonyms()
                .size());
        for (SongSynonym ssyn : song.getSynonyms())
        {
            assertEquals("Wrong user", PersistenceTestHelper.getTestUser(),
                    ssyn.getUser());
            assertEquals("Wrong song", song, ssyn.getSong());
            assertTrue("Unexpected synonym: " + ssyn,
                    expSynonyms.remove(ssyn.getName()));
        }
    }

    /**
     * Tries to add a null synonym name.
     */
    @Test(expected = NullPointerException.class)
    public void testAddSynonymNameNull()
    {
        createSong().addSynonymName(null);
    }

    /**
     * Tries to add a null synonym object.
     */
    @Test(expected = NullPointerException.class)
    public void testAddSynonymNull()
    {
        createSong().addSynonym(null);
    }

    /**
     * Tests whether duplicate synonyms are rejected.
     */
    @Test
    public void testAddSynonymDuplicate()
    {
        SongEntity song = createSong();
        assertTrue("Wrong result (1)", song.addSynonymName(SYN_PREFIX));
        assertFalse("Wrong result (2)", song.addSynonymName(SYN_PREFIX));
        assertEquals("Wrong number of synonyms", 1, song.getSynonyms().size());
    }

    /**
     * Tests whether an existing synonym can be removed.
     */
    @Test
    public void testRemoveSynonymExisting()
    {
        SongEntity song = createSong();
        SongSynonym ssym = new SongSynonym();
        ssym.setName(SYN_PREFIX);
        song.addSynonym(ssym);
        final String otherSyn = SYN_PREFIX + "other";
        song.addSynonymName(otherSyn);
        assertTrue("Wrong result", song.removeSynonym(ssym));
        assertEquals("Wrong number of synonyms", 1, song.getSynonyms().size());
        assertEquals("Wrong name", otherSyn, song.getSynonyms().iterator()
                .next().getName());
        assertNull("Song reference not cleared", ssym.getSong());
    }

    /**
     * Tries to remove a non existing synonym.
     */
    @Test
    public void testRemoveSynonymNonExisting()
    {
        SongEntity song = createSong();
        song.addSynonymName(SYN_PREFIX);
        assertFalse("Wrong result", song.removeSynonymName(SONG_NAME));
        assertEquals("Wrong number of synonyms", 1, song.getSynonyms().size());
    }

    /**
     * Tries to remove a synonym from another song.
     */
    @Test
    public void testRemoveSynonymOtherSong()
    {
        SongSynonym ssym = new SongSynonym();
        ssym.setName(SYN_PREFIX);
        SongEntity song1 = createSong();
        song1.addSynonym(ssym);
        SongEntity song2 = new SongEntity();
        assertFalse("Wrong result", song2.removeSynonym(ssym));
        assertSame("Wrong song reference", song1, ssym.getSong());
    }

    /**
     * Tries to remove a null synonym.
     */
    @Test
    public void testRemoveSynonymNull()
    {
        assertFalse("Could remove null synonym",
                createSong().removeSynonym(null));
    }

    /**
     * Retrieves all existing song synonyms.
     *
     * @return the list with the found entities
     */
    private List<SongSynonym> fetchSongSynonyms()
    {
        @SuppressWarnings("unchecked")
        List<SongSynonym> result =
                helper.getEM().createQuery("select s from SongSynonym s")
                        .getResultList();
        return result;
    }

    /**
     * Tests whether synonyms can be removed which have already been persisted.
     */
    @Test
    public void testRemovePersistentSynonyms()
    {
        ArtistEntity art = createArtist();
        helper.persist(art);
        SongEntity song = createSong();
        song.setArtistID(art.getId());
        song.addSynonymName(SYN_PREFIX);
        helper.persist(song);
        helper.closeEM();
        helper.begin();
        song = helper.getEM().find(SongEntity.class, song.getId());
        assertTrue("Wrong result", song.removeSynonymName(SYN_PREFIX));
        helper.commit();
        helper.closeEM();
        song = helper.getEM().find(SongEntity.class, song.getId());
        assertTrue("Got synonyms", song.getSynonyms().isEmpty());
        assertTrue("Got synoym entities", fetchSongSynonyms().isEmpty());
    }

    /**
     * Tests whether synonyms are removed when their owning song is removed.
     */
    @Test
    public void testRemoveSongCascade()
    {
        final int synCount = 10;
        SongEntity song = createSong();
        for (int i = 0; i < synCount; i++)
        {
            song.addSynonymName(SYN_PREFIX + i);
        }
        helper.persist(song);
        assertEquals("Wrong number of synonyms (1)", synCount,
                fetchSongSynonyms().size());
        helper.begin();
        song = helper.getEM().find(SongEntity.class, song.getId());
        helper.getEM().remove(song);
        helper.commit();
        assertTrue("Got synoym entities", fetchSongSynonyms().isEmpty());
    }
}
