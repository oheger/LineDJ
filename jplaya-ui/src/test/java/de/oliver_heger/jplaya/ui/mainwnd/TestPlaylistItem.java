package de.oliver_heger.jplaya.ui.mainwnd;

import static org.junit.Assert.assertEquals;

import java.math.BigInteger;

import org.easymock.EasyMock;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;

import de.oliver_heger.mediastore.service.ObjectFactory;
import de.oliver_heger.mediastore.service.SongData;
import de.olix.playa.playlist.PlaylistInfo;

/**
 * Test class for {@code PlaylistItem}.
 *
 * @author Oliver Heger
 * @version $Id: $
 */
public class TestPlaylistItem
{
    /** Constant for a duration as string. */
    private static final String DURATION_STR = "5:25";

    /** Constant for a duration. */
    private static final long DURATION = (5 * 60 + 25) * 1000;

    /** Constant for the number of songs in the playlist. */
    private static final int COUNT = 111;

    /** The factory for song data objects. */
    private static ObjectFactory factory;

    /** A song data object with media information. */
    private SongData songData;

    /** The playlist context. */
    private PlaylistContext context;

    /** The item to be tested. */
    private PlaylistItem item;

    @BeforeClass
    public static void setUpBeforeClass() throws Exception
    {
        factory = new ObjectFactory();
    }

    @Before
    public void setUp() throws Exception
    {
        songData = factory.createSongData();
        context = new PlaylistContext();
        item = new PlaylistItem();
        item.setSongData(songData);
        item.setPlaylistContext(context);
    }

    /**
     * Tests whether the song name can be obtained from the song URI.
     */
    @Test
    public void testGetSongNameFromURI()
    {
        final String name = "TestSong";
        item.setUri("file:///mymusic/folder/" + name + ".mp3");
        assertEquals("Wrong name", name, item.getSongName());
    }

    /**
     * Tests getSongName() if the name has to be extracted from the URI, but the
     * URI does not contain any special characters.
     */
    @Test
    public void testGetSongNameFromURINoSlashes()
    {
        final String uri = "TheSongNameAndNothingElse";
        item.setUri(uri);
        assertEquals("Wrong name", uri, item.getSongName());
    }

    /**
     * Tests whether the song name can be obtained from media data.
     */
    @Test
    public void testGetSongNameFromData()
    {
        songData.setName("SongDataName");
        item.setUri("anotherURI");
        assertEquals("Wrong name", songData.getName(), item.getSongName());
    }

    /**
     * Tests whether the URI can be set to null and the consequences for the
     * song name.
     */
    @Test
    public void testSetUriNull()
    {
        item.setUri(null);
        assertEquals("Wrong song name", "", item.getSongName());
    }

    /**
     * Tests whether a URI that ends on a slash can be handled.
     */
    @Test
    public void testSetUriSlashAtEnd()
    {
        String uri = "www.test.de/songs/";
        item.setUri(uri);
        assertEquals("Wrong song name", uri, item.getSongName());
    }

    /**
     * Tests whether a formatted duration can be queried.
     */
    @Test
    public void testGetDurationFormatted()
    {
        songData.setDuration(BigInteger.valueOf(DURATION));
        assertEquals("Wrong duration", DURATION_STR, item.getDuration());
    }

    /**
     * Tests whether parts of a duration have two digits if necessary.
     */
    @Test
    public void testGetDurationLeading0()
    {
        songData.setDuration(BigInteger
                .valueOf((1 * 60 * 60 + 2 * 60 + 8) * 1000 + 555));
        assertEquals("Wrong duration", "1:02:09", item.getDuration());
    }

    /**
     * Tests a formatted playback time.
     */
    @Test
    public void testGetPlaybackTime()
    {
        context.setPlaybackTime((2 * 60 + 10) * 1000);
        songData.setDuration(BigInteger.valueOf(DURATION));
        assertEquals("Wrong playback time", "2:10 / " + DURATION_STR,
                item.getPlaybackTime());
    }

    /**
     * Tests getPlaybackTime() if no duration is available.
     */
    @Test
    public void testGetPlaybackTimeNoDuration()
    {
        context.setPlaybackTime(DURATION);
        assertEquals("Wrong playback time", DURATION_STR,
                item.getPlaybackTime());
    }

    /**
     * Tests whether the artist can be queried.
     */
    @Test
    public void testGetArtist()
    {
        final String artist = "Peter Gabriel";
        songData.setArtistName(artist);
        assertEquals("Wrong artist", artist, item.getArtist());
    }

    /**
     * Tests whether the album can be queried.
     */
    @Test
    public void testGetAlbum()
    {
        final String album = "Brothers in Arms";
        songData.setAlbumName(album);
        assertEquals("Wrong album", album, item.getAlbum());
    }

    /**
     * Tests whether the track number can be queried.
     */
    @Test
    public void testGetTrackNo()
    {
        songData.setTrackNo(BigInteger.valueOf(2));
        assertEquals("Wrong track number", "2", item.getTrackNo());
    }

    /**
     * Tests the index in the playlist.
     */
    @Test
    public void testGetPlaybackIndex()
    {
        PlaylistInfo info = EasyMock.createMock(PlaylistInfo.class);
        EasyMock.expect(info.getNumberOfSongs()).andReturn(COUNT).anyTimes();
        EasyMock.replay(info);
        context.setPlaylistInfo(info);
        item.setIndex(11);
        assertEquals("Wrong index (1)", "12 / 111", item.getPlaybackIndex());
        item.setIndex(0);
        assertEquals("Wrong index (1)", "1 / 111", item.getPlaybackIndex());
        EasyMock.verify(info);
    }

    /**
     * Tests the index in the playlist if it is undefined.
     */
    @Test
    public void testGetplaybackIndexUndefined()
    {
        assertEquals("Wrong index", "", item.getPlaybackIndex());
    }

    /**
     * Tests whether the name of the playlist can be queried.
     */
    @Test
    public void testGetPlaylistName()
    {
        PlaylistInfo info = EasyMock.createMock(PlaylistInfo.class);
        final String name = "Super Cool Test Music";
        EasyMock.expect(info.getName()).andReturn(name);
        EasyMock.replay(info);
        context.setPlaylistInfo(info);
        assertEquals("Wrong name", name, item.getPlaylistName());
        EasyMock.verify(info);
    }

    /**
     * Tests getPlaylistName() if there is no playlist.
     */
    @Test
    public void testGetPlaylistNameUndefined()
    {
        assertEquals("Wrong name", "", item.getPlaylistName());
    }

    /**
     * Tests whether the playback ratio can be queried.
     */
    @Test
    public void testGetPlaybackRatio()
    {
        final int ratio = 75;
        context.setPlaybackRatio(ratio);
        assertEquals("Wrong ratio", ratio, item.getPlaybackRatio());
    }

    /**
     * Tests the behavior for undefined properties.
     */
    @Test
    public void testUndefinedProperties()
    {
        assertEquals("Wrong artist", "", item.getArtist());
        assertEquals("Wrong album", "", item.getAlbum());
        assertEquals("Wrong duration", "", item.getDuration());
        assertEquals("Wrong track", "", item.getTrackNo());
        assertEquals("Wrong name", "", item.getSongName());
    }
}
