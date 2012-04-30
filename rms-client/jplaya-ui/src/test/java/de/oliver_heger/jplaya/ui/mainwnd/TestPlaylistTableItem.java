package de.oliver_heger.jplaya.ui.mainwnd;

import static org.junit.Assert.assertEquals;

import org.easymock.EasyMock;
import org.junit.Before;
import org.junit.Test;

import de.oliver_heger.splaya.AudioSourceData;
import de.oliver_heger.splaya.PlaylistData;

/**
 * Test class for {@code PlaylistTableItem}.
 *
 * @author Oliver Heger
 * @version $Id: $
 */
public class TestPlaylistTableItem
{
    /** Constant for the item index. */
    private final int INDEX = 42;

    /** A mock for the playlist data object. */
    private PlaylistData pldata;

    /** The item to be tested. */
    private PlaylistTableItem item;

    @Before
    public void setUp() throws Exception
    {
        pldata = EasyMock.createMock(PlaylistData.class);
        item = new PlaylistTableItem(pldata, INDEX);
    }

    /**
     * Prepares the playlist data mock to expect a request for the test audio
     * source data.
     *
     * @return the source data mock
     */
    private AudioSourceData initSourceData()
    {
        AudioSourceData srcData = EasyMock.createMock(AudioSourceData.class);
        EasyMock.expect(pldata.getAudioSourceData(INDEX)).andReturn(srcData)
                .anyTimes();
        return srcData;
    }

    /**
     * Tests whether the correct song name can be queried.
     */
    @Test
    public void testGetSongName()
    {
        AudioSourceData srcData = initSourceData();
        final String songName = "Money for Nothing";
        EasyMock.expect(srcData.title()).andReturn(songName);
        EasyMock.replay(srcData, pldata);
        assertEquals("Wrong song name", songName, item.getSongName());
        EasyMock.verify(srcData, pldata);
    }

    /**
     * Tests whether the correct list index is returned.
     */
    @Test
    public void testGetListIndex()
    {
        assertEquals("Wrong list index", INDEX + 1, item.getListIndex());
    }

    /**
     * Helper method for testing whether the correct duration is returned.
     *
     * @param duration the numeric duration in milliseconds
     * @param strDuration the expected formatted duration string
     */
    private void checkDuration(long duration, String strDuration)
    {
        AudioSourceData srcData = initSourceData();
        EasyMock.expect(srcData.duration()).andReturn(duration);
        EasyMock.replay(srcData, pldata);
        assertEquals("Wrong duration", strDuration, item.getDuration());
        EasyMock.verify(srcData, pldata);
    }

    /**
     * Tests whether the correct duration is returned.
     */
    @Test
    public void testGetDuration()
    {
        checkDuration((5 * 60 + 25) * 1000L, "5:25");
    }

    /**
     * Tests whether parts of a duration have two digits if necessary.
     */
    @Test
    public void testGetDurationLeading0()
    {
        checkDuration((1 * 60 * 60 + 2 * 60 + 8) * 1000L, "1:02:08");
    }

    /**
     * Tests whether an undefined duration is handled correctly.
     */
    @Test
    public void testGetDurationUndefined()
    {
        checkDuration(0, "");
    }

    /**
     * Helper method for checking the getAlbum() method.
     *
     * @param album the album name returned by the source data
     * @param expResult the expected result
     */
    private void checkGetAlbum(String album, String expResult)
    {
        AudioSourceData srcData = initSourceData();
        EasyMock.expect(srcData.albumName()).andReturn(album);
        EasyMock.replay(srcData, pldata);
        assertEquals("Wrong album name", expResult, item.getAlbum());
        EasyMock.verify(srcData, pldata);
    }

    /**
     * Tests whether the album name can be queried.
     */
    @Test
    public void testGetAlbum()
    {
        final String album = "Brothers in Arms";
        checkGetAlbum(album, album);
    }

    /**
     * Tests getAlbum() if the album name is undefined.
     */
    @Test
    public void testGetAlbumUndefined()
    {
        checkGetAlbum(null, "");
    }

    /**
     * Tests whether the correct artist name is returned.
     */
    @Test
    public void testGetArtist()
    {
        AudioSourceData srcData = initSourceData();
        final String artist = "Dire Straits";
        EasyMock.expect(srcData.artistName()).andReturn(artist);
        EasyMock.replay(srcData, pldata);
        assertEquals("Wrong artist", artist, item.getArtist());
        EasyMock.verify(srcData, pldata);
    }

    /**
     * Helper method for testing the getTrackNo() method.
     *
     * @param track the track number to be returned by the audio source data
     * @param expResult the expected result
     */
    private void checkGetTrackNo(int track, String expResult)
    {
        AudioSourceData srcData = initSourceData();
        EasyMock.expect(srcData.trackNo()).andReturn(track);
        EasyMock.replay(srcData, pldata);
        assertEquals("Wrong track", expResult, item.getTrackNo());
        EasyMock.verify(srcData, pldata);
    }

    /**
     * Tests whether the correct track number is returned if it is defined.
     */
    @Test
    public void testGetTrackNoDefined()
    {
        checkGetTrackNo(2, "2");
    }

    /**
     * Tests whether an undefined track number is handled correctly.
     */
    @Test
    public void testGetTrackNoUndefined()
    {
        checkGetTrackNo(0, "");
    }
}
