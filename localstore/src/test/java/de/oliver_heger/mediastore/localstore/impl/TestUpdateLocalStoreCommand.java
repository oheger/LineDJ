package de.oliver_heger.mediastore.localstore.impl;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;

import java.math.BigInteger;
import java.util.List;
import java.util.Locale;

import javax.persistence.EntityManagerFactory;

import org.apache.commons.lang3.concurrent.ConstantInitializer;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import de.oliver_heger.mediastore.localstore.JPATestHelper;
import de.oliver_heger.mediastore.localstore.model.AlbumEntity;
import de.oliver_heger.mediastore.localstore.model.ArtistEntity;
import de.oliver_heger.mediastore.localstore.model.SongEntity;
import de.oliver_heger.mediastore.service.ObjectFactory;
import de.oliver_heger.mediastore.service.SongData;

/**
 * Test class for {@code UpdateLocalStoreCommand}.
 *
 * @author Oliver Heger
 * @version $Id: $
 */
public class TestUpdateLocalStoreCommand
{
    /** Constant for the name of an artist. */
    private static final String ARTIST_NAME = "Within Temptation";

    /** Constant for the name of the test song. */
    private static final String SONG_NAME = "Restless";

    /** Constant for the duration of the test song in seconds. */
    private static final int SONG_DURATION = 368;

    /** Constant for the name of the test album. */
    private static final String ALBUM_NAME = "Live Tilburg";

    /** Constant for the name of the album's inception year. */
    private static final int ALBUM_YEAR = 2002;

    /** Constant for the track number. */
    private static final int TRACK = 3;

    /** Constant for the play count. */
    private static final int PLAY_COUNT = 2;

    /** The JPA test helper. */
    private JPATestHelper helper;

    @Before
    public void setUp() throws Exception
    {
        helper = new JPATestHelper();
    }

    @After
    public void tearDown() throws Exception
    {
        helper.close();
    }

    /**
     * Helper method for creating a test command object.
     *
     * @param data the object with song data
     * @return the test command object
     */
    private UpdateLocalStoreCommand createCommand(SongData data)
    {
        return new UpdateLocalStoreCommand(
                new ConstantInitializer<EntityManagerFactory>(helper.getEMF()),
                data);
    }

    /**
     * Loads all entities of the given type.
     *
     * @param <T> the entity type
     * @param entityCls the entity class
     * @return a list with the found entities
     */
    private <T> List<T> load(Class<T> entityCls)
    {
        @SuppressWarnings("unchecked")
        List<T> result =
                (List<T>) helper
                        .getEM()
                        .createQuery(
                                "select e from " + entityCls.getName() + " e")
                        .getResultList();
        return result;
    }

    /**
     * Checks the number of entities for the given class.
     *
     * @param entityCls the entity class
     * @param expCount the expected number of entities
     */
    private void checkCount(Class<?> entityCls, int expCount)
    {
        List<?> list = load(entityCls);
        assertEquals("Wrong number of entities for " + entityCls, expCount,
                list.size());
    }

    /**
     * Checks whether the test song has been correctly added to the local
     * database.
     *
     * @param expPlayCount the expected play count
     * @return the entity for the test song
     */
    private SongEntity checkSyncedSong(int expPlayCount)
    {
        List<SongEntity> songs = load(SongEntity.class);
        assertEquals("Wrong number of songs", 1, songs.size());
        SongEntity song = songs.get(0);
        assertEquals("Wrong song name", SONG_NAME, song.getName());
        assertEquals("Wrong duration", SONG_DURATION, song.getDuration()
                .intValue());
        assertEquals("Wrong song year", ALBUM_YEAR, song.getInceptionYear()
                .intValue());
        assertEquals("Wrong track number", TRACK, song.getTrackNo().intValue());
        assertEquals("Wrong play count", expPlayCount,
                song.getCurrentPlayCount());
        return song;
    }

    /**
     * Checks whether the local database contains the expected entities.
     *
     * @param expPlayCount the expected play count
     * @return the entity for the test song
     */
    private SongEntity checkLocalStore(int expPlayCount)
    {
        SongEntity song = checkSyncedSong(expPlayCount);
        ArtistEntity artist = song.getArtist();
        assertEquals("Wrong artist name", ARTIST_NAME, artist.getName());
        assertEquals("Wrong number of artist songs", 1, artist.getSongs()
                .size());
        checkCount(ArtistEntity.class, 1);
        AlbumEntity album = song.getAlbum();
        assertEquals("Wrong album name", ALBUM_NAME, album.getName());
        assertEquals("Wrong album year", ALBUM_YEAR, album.getInceptionYear()
                .intValue());
        assertEquals("Wrong number of album songs", 1, album.getSongs().size());
        checkCount(AlbumEntity.class, 1);
        return song;
    }

    /**
     * Creates a song data object with default values.
     *
     * @return the data object
     */
    private SongData createSongData()
    {
        SongData data = new ObjectFactory().createSongData();
        data.setName(SONG_NAME);
        data.setDuration(BigInteger.valueOf(SONG_DURATION));
        data.setInceptionYear(BigInteger.valueOf(ALBUM_YEAR));
        data.setTrackNo(BigInteger.valueOf(TRACK));
        data.setArtistName(ARTIST_NAME);
        data.setAlbumName(ALBUM_NAME);
        return data;
    }

    /**
     * Tests whether the command is correctly initialized.
     */
    @Test
    public void testInit()
    {
        UpdateLocalStoreCommand cmd = createCommand(createSongData());
        assertSame("Wrong factory", helper.getEMF(),
                cmd.getEntityManagerFactory());
        assertFalse("UI update", cmd.isUpdateGUI());
    }

    /**
     * Tries to create an instance without a song data object.
     */
    @Test(expected = NullPointerException.class)
    public void testInitNoSongData()
    {
        createCommand(null);
    }

    /**
     * Tests the command if a new song entity has to be added which has no
     * references.
     */
    @Test
    public void testUpdateNewSong() throws Exception
    {
        SongData data = createSongData();
        data.setAlbumName(null);
        data.setArtistName(null);
        UpdateLocalStoreCommand cmd = createCommand(data);
        cmd.execute();
        checkSyncedSong(1);
    }

    /**
     * Helper method for executing the test command and checking results.
     *
     * @param data the song data object
     * @throws Exception if an error occurs
     */
    private void checkUpdateCommand(SongData data) throws Exception
    {
        UpdateLocalStoreCommand cmd = createCommand(data);
        cmd.execute();
        checkLocalStore(1);
    }

    /**
     * Tests the command if all entities have to be created.
     */
    @Test
    public void testUpdateNewEntities() throws Exception
    {
        checkUpdateCommand(createSongData());
    }

    /**
     * Checks an update operation if the artist entity already exists.
     *
     * @param artName the name of the artist
     * @throws Exception if an error occurs
     */
    private void checkUpdateExistingArtist(String artName) throws Exception
    {
        ArtistEntity art = new ArtistEntity();
        art.setName(ARTIST_NAME);
        helper.persist(art, true);
        helper.closeEM();
        SongData data = createSongData();
        data.setArtistName(artName);
        checkUpdateCommand(data);
    }

    /**
     * Tests an update operation if the artist entity already exists with the
     * name specified.
     */
    @Test
    public void testUpdateExistingArtist() throws Exception
    {
        checkUpdateExistingArtist(ARTIST_NAME);
    }

    /**
     * Tests whether case of the artist name is ignored when updating a song.
     */
    @Test
    public void testUpdateExistingArtistCase() throws Exception
    {
        checkUpdateExistingArtist(ARTIST_NAME.toLowerCase(Locale.ENGLISH));
    }

    /**
     * Checks an update operation of the album entity already exists.
     *
     * @param albName the name of the album
     * @throws Exception if an error occurs
     */
    private void checkUpdateExistingAlbum(String albName) throws Exception
    {
        AlbumEntity alb = new AlbumEntity();
        alb.setName(ALBUM_NAME);
        alb.setInceptionYear(ALBUM_YEAR);
        helper.persist(alb, true);
        helper.closeEM();
        SongData data = createSongData();
        data.setAlbumName(albName);
        checkUpdateCommand(data);
    }

    /**
     * Tests an update operation if the album entity already exists with the
     * name specified.
     */
    @Test
    public void testUpdateExistingAlbum() throws Exception
    {
        checkUpdateExistingAlbum(ALBUM_NAME);
    }

    /**
     * Tests whether the case of the album name is ignored when updating a song.
     */
    @Test
    public void testUpdateExistingAlbumCase() throws Exception
    {
        checkUpdateExistingAlbum(ALBUM_NAME.toLowerCase(Locale.ENGLISH));
    }

    /**
     * Checks an update operation if the song already exists.
     *
     * @param songName the name of the song
     */
    private void checkUpdateExistingSong(String songName) throws Exception
    {
        SongEntity song = new SongEntity();
        song.setName(SONG_NAME);
        song.setDuration(SONG_DURATION);
        song.setCurrentPlayCount(PLAY_COUNT);
        helper.persist(song, true);
        SongData data = createSongData();
        data.setArtistName(null);
        data.setAlbumName(null);
        data.setName(songName);
        UpdateLocalStoreCommand cmd = createCommand(data);
        cmd.execute();
        helper.closeEM();
        checkCount(SongEntity.class, 1);
        song = helper.getEM().find(SongEntity.class, song.getId());
        assertEquals("Wrong play count", PLAY_COUNT + 1,
                song.getCurrentPlayCount());
    }

    /**
     * Tests an update operation if the song already exists with the name
     * specified.
     */
    @Test
    public void testUpdateExistingSong() throws Exception
    {
        checkUpdateExistingSong(SONG_NAME);
    }

    /**
     * Tests whether the case of the song name is ignored when updating a song.
     */
    @Test
    public void testUpdateExistingSongCase() throws Exception
    {
        checkUpdateExistingSong(SONG_NAME.toLowerCase(Locale.ENGLISH));
    }
}
