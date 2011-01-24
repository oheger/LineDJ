package de.oliver_heger.mediastore.server.convert;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.Collection;
import java.util.Collections;
import java.util.Date;
import java.util.LinkedList;
import java.util.List;

import javax.persistence.EntityManager;

import org.easymock.EasyMock;
import org.junit.Before;
import org.junit.Test;

import de.oliver_heger.mediastore.server.model.AlbumEntity;
import de.oliver_heger.mediastore.server.model.SongEntity;
import de.oliver_heger.mediastore.shared.model.AlbumInfo;

/**
 * Test class for {@code AlbumEntityConverter}.
 *
 * @author Oliver Heger
 * @version $Id: $
 */
public class TestAlbumEntityConverter
{
    /** Constant for the milliseconds in one minute. */
    private static final long MINUTE = 60 * 1000L;

    /** Constant for the ID of test album 1. */
    private static final Long ID_ALBUM1 = 20110123215938L;

    /** Constant for the ID of test album 2. */
    private static final Long ID_ALBUM2 = 20110123220014L;

    /** The converter to be tested. */
    private AlbumEntityConverterTestImpl converter;

    @Before
    public void setUp() throws Exception
    {
        converter = new AlbumEntityConverterTestImpl();
    }

    /**
     * Creates an album entity with the specified ID.
     *
     * @param id the ID of the new album
     * @return the newly created entity
     */
    private static AlbumEntity createAlbumEntity(final Long id)
    {
        return new AlbumEntity()
        {
            private static final long serialVersionUID = 1L;

            @Override
            public Long getId()
            {
                return id;
            }
        };
    }

    /**
     * Helper method for creating a test song with some specific properties.
     *
     * @param albumID the ID of the referenced album
     * @param durationMin the song's duration in minutes (can be <b>null</b>)
     * @param year the inception year (can be <b>null</b>)
     * @return the song entity
     */
    private static SongEntity createTestSong(Long albumID, Integer durationMin,
            Integer year)
    {
        SongEntity e = new SongEntity();
        e.setAlbumID(albumID);
        if (durationMin != null)
        {
            e.setDuration(durationMin.intValue() * MINUTE);
        }
        e.setInceptionYear(year);
        return e;
    }

    /**
     * Creates some test songs for the first test album. All data is fully
     * defined.
     *
     * @return the test data
     */
    private static List<SongEntity> createTestSongsForAlbum1()
    {
        List<SongEntity> result = new LinkedList<SongEntity>();
        result.add(createTestSong(ID_ALBUM1, 3, 1985));
        result.add(createTestSong(ID_ALBUM1, 4, 1985));
        result.add(createTestSong(ID_ALBUM1, 7, 1985));
        return result;
    }

    /**
     * Returns a list with some test songs that refer to the test albums.
     *
     * @return the list with the test songs
     */
    private static List<SongEntity> createTestSongs()
    {
        List<SongEntity> result =
                new LinkedList<SongEntity>(createTestSongsForAlbum1());
        result.add(createTestSong(ID_ALBUM2, 5, 2005));
        result.add(createTestSong(ID_ALBUM2, null, 2005));
        result.add(createTestSong(20110123221620L, 27, 1999));
        Collections.shuffle(result);
        return result;
    }

    /**
     * Tests a conversion with only simple properties. No songs are involved.
     */
    @Test
    public void testConvertSimple()
    {
        AlbumEntity e = createAlbumEntity(ID_ALBUM1);
        e.setName("Brothers in Arms");
        e.setCreationDate(new Date(20110123220607L));
        AlbumInfo info = converter.convert(e);
        assertEquals("Wrong album ID", ID_ALBUM1, info.getAlbumID());
        assertEquals("Wrong name", e.getName(), info.getName());
        assertEquals("Wrong creation date", e.getCreationDate(),
                info.getCreationDate());
        checkNoSongInfo(info);
    }

    /**
     * Tests whether the specified info object does not contain any information
     * about songs.
     *
     * @param info the info object to check
     */
    private void checkNoSongInfo(AlbumInfo info)
    {
        assertEquals("Wrong song count", 0, info.getNumberOfSongs());
        assertNull("Got a duration", info.getDuration());
        assertNull("Got an inception year", info.getInceptionYear());
    }

    /**
     * Tests a conversion with songs that are part of the initialized song data.
     */
    @Test
    public void testConvertSongDataFromList()
    {
        converter.initializeSongData(createTestSongs());
        AlbumInfo info = converter.convert(createAlbumEntity(ID_ALBUM1));
        assertEquals("Wrong number of songs", 3, info.getNumberOfSongs());
        assertEquals("Wrong inception year", 1985, info.getInceptionYear()
                .intValue());
        assertEquals("Wrong duration", 14 * MINUTE, info.getDuration()
                .longValue());
    }

    /**
     * Tests a conversion with songs that are retrieved from an entity manager.
     */
    @Test
    public void testConvertSongDataFromEM()
    {
        converter.expectQuerySongs(ID_ALBUM1, createTestSongsForAlbum1());
        converter
                .setEntityManager(EasyMock.createNiceMock(EntityManager.class));
        AlbumInfo info = converter.convert(createAlbumEntity(ID_ALBUM1));
        assertEquals("Wrong number of songs", 3, info.getNumberOfSongs());
        assertEquals("Wrong inception year", 1985, info.getInceptionYear()
                .intValue());
        assertEquals("Wrong duration", 14 * MINUTE, info.getDuration()
                .longValue());
        converter.verifyQuerySongs();
    }

    /**
     * Tests whether song data queried once through the entity manager is
     * cached.
     */
    @Test
    public void testConvertSongDataFromEMCached()
    {
        converter.expectQuerySongs(ID_ALBUM1, createTestSongsForAlbum1());
        converter
                .setEntityManager(EasyMock.createNiceMock(EntityManager.class));
        AlbumEntity album = createAlbumEntity(ID_ALBUM1);
        converter.convert(album);
        converter.convert(album);
        converter.verifyQuerySongs();
    }

    /**
     * Tests whether a null duration is detected when converting song data.
     */
    @Test
    public void testConvertSongDataNullDuration()
    {
        List<SongEntity> songs = createTestSongsForAlbum1();
        songs.add(createTestSong(ID_ALBUM1, null, 1985));
        AlbumEntity e = createAlbumEntity(ID_ALBUM1);
        AlbumInfo info = new AlbumInfo();
        converter.convert(e, info, songs);
        assertNull("Got a duration", info.getDuration());
    }

    /**
     * Tests whether a null inception year is detected when converting song
     * data.
     */
    @Test
    public void testConvertSongDataNullYear()
    {
        List<SongEntity> songs = createTestSongsForAlbum1();
        songs.add(createTestSong(ID_ALBUM1, 4, null));
        AlbumEntity e = createAlbumEntity(ID_ALBUM1);
        AlbumInfo info = new AlbumInfo();
        converter.convert(e, info, songs);
        assertNull("Got a year", info.getInceptionYear());
    }

    /**
     * Tests whether an ambiguous inception year is detected when converting
     * song data.
     */
    @Test
    public void testConvertSongDataDifferentYears()
    {
        List<SongEntity> songs = createTestSongsForAlbum1();
        songs.add(createTestSong(ID_ALBUM1, 4, 1986));
        AlbumEntity e = createAlbumEntity(ID_ALBUM1);
        AlbumInfo info = new AlbumInfo();
        converter.convert(e, info, songs);
        assertNull("Got a year", info.getInceptionYear());
    }

    /**
     * Helper method for testing a conversion if no song data is available.
     *
     * @param songs the collection with songs
     */
    private void checkConvertWithoutSongs(Collection<? extends SongEntity> songs)
    {
        AlbumEntity e = createAlbumEntity(ID_ALBUM1);
        AlbumInfo info = new AlbumInfo();
        converter.convert(e, info, null);
        assertEquals("Wrong ID", e.getId(), info.getAlbumID());
        checkNoSongInfo(info);
    }

    /**
     * Tests convert() if a null collection with songs is passed in.
     */
    @Test
    public void testConvertNullSongs()
    {
        checkConvertWithoutSongs(null);
    }

    /**
     * Tests convert() if an empty collection with songs is passed in.
     */
    @Test
    public void testConvertEmptySongs()
    {
        List<SongEntity> songs = Collections.emptyList();
        checkConvertWithoutSongs(songs);
    }

    /**
     * A test converter implementation which provides some mocking facilities.
     */
    private static class AlbumEntityConverterTestImpl extends
            AlbumEntityConverter
    {
        /** The list with songs to be returned by querySongs(). */
        private final List<List<SongEntity>> mockSongData =
                new LinkedList<List<SongEntity>>();

        /** The list of IDs of expected album entities. */
        private final List<Long> expectedAlbumIDs = new LinkedList<Long>();

        /** A flag whether querySongs() is to be mocked. */
        private boolean mockQuerySongs;

        /**
         * Returns a flag whether querySongs() is to be mocked.
         *
         * @return the mock flag
         */
        public boolean isMockQuerySongs()
        {
            return mockQuerySongs;
        }

        /**
         * Sets a flag whether querySongs() is to be mocked.
         *
         * @param mockQuerySongs a flag whether the method is to be mocked
         */
        public void setMockQuerySongs(boolean mockQuerySongs)
        {
            this.mockQuerySongs = mockQuerySongs;
        }

        /**
         * Prepares this object to expect a call to querySongs().
         *
         * @param albumID the expected album ID
         * @param songs the songs to be returned
         */
        public void expectQuerySongs(Long albumID, List<SongEntity> songs)
        {
            mockSongData.add(songs);
            expectedAlbumIDs.add(albumID);
            setMockQuerySongs(true);
        }

        /**
         * Checks whether the expected calls to querySongs() have been
         * performed.
         */
        public void verifyQuerySongs()
        {
            assertTrue("Remaining querySongs() calls: " + expectedAlbumIDs,
                    expectedAlbumIDs.isEmpty());
        }

        /**
         * If the method is to be mocked, test data is returned. Otherwise the
         * super method is called.
         */
        @Override
        List<SongEntity> loadSongs(AlbumEntity e)
        {
            if (isMockQuerySongs())
            {
                assertEquals("Wrong entity ID", expectedAlbumIDs.remove(0),
                        e.getId());
                return mockSongData.remove(0);
            }
            return super.querySongs(e);
        }
    }
}
