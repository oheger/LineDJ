package de.oliver_heger.jplaya.ui.mainwnd;

import static org.junit.Assert.assertEquals;

import org.easymock.EasyMock;
import org.junit.Before;
import org.junit.Test;

import de.oliver_heger.splaya.AudioSourceData;
import de.oliver_heger.splaya.PlaylistData;
import de.oliver_heger.splaya.PlaylistSettings;

/**
 * Test class for {@code PlaylistItem}.
 *
 * @author Oliver Heger
 * @version $Id: $
 */
public class TestPlaylistItem
{
    /** Constant for the index of the playlist item. */
    private final int INDEX = 11;

    /** A mock for the playlist data object. */
    private PlaylistData pldata;

    /** The item to be tested. */
    private PlaylistItem item;

    @Before
    public void setUp() throws Exception
    {
        pldata = EasyMock.createMock(PlaylistData.class);
        item = new PlaylistItem(pldata, INDEX);
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
     * Tests a formatted playback time.
     */
    @Test
    public void testGetPlaybackTime()
    {
        AudioSourceData srcData = initSourceData();
        EasyMock.expect(srcData.duration()).andReturn((10 * 60 + 15) * 1000L);
        EasyMock.replay(srcData, pldata);
        item.setCurrentPlaybackTime((2 * 60 + 10) * 1000L);
        assertEquals("Wrong playback time", "2:10 / 10:15",
                item.getPlaybackTime());
        EasyMock.verify(srcData, pldata);
    }

    /**
     * Tests getPlaybackTime() if no duration is available.
     */
    @Test
    public void testGetPlaybackTimeNoDuration()
    {
        AudioSourceData srcData = initSourceData();
        EasyMock.expect(srcData.duration()).andReturn(0L);
        EasyMock.replay(srcData, pldata);
        item.setCurrentPlaybackTime((2 * 60 + 10) * 1000L);
        assertEquals("Wrong playback time", "2:10", item.getPlaybackTime());
        EasyMock.verify(srcData, pldata);
    }

    /**
     * Tests the index in the playlist.
     */
    @Test
    public void testGetPlaybackIndex()
    {
        final int count = 128;
        EasyMock.expect(pldata.size()).andReturn(count);
        EasyMock.replay(pldata);
        assertEquals("Wrong index", String.format("%d / %d", INDEX + 1, count),
                item.getPlaybackIndex());
        EasyMock.verify(pldata);
    }

    /**
     * Tests the index in the playlist if it is undefined.
     */
    @Test
    public void testGetplaybackIndexUndefined()
    {
        item = new PlaylistItem(pldata, -1);
        assertEquals("Wrong index", "", item.getPlaybackIndex());
    }

    /**
     * Helper method for testing whether the name of the playlist can be
     * queried.
     *
     * @param name the name to be returned by the settings object
     * @param expResult the expected result
     */
    private void checkGetPlaylistName(String name, String expResult)
    {
        PlaylistSettings settings = EasyMock.createMock(PlaylistSettings.class);
        EasyMock.expect(settings.name()).andReturn(name);
        EasyMock.expect(pldata.settings()).andReturn(settings);
        EasyMock.replay(pldata, settings);
        assertEquals("Wrong name", expResult, item.getPlaylistName());
        EasyMock.verify(pldata, settings);
    }

    /**
     * Tests whether the name of the playlist can be queried.
     */
    @Test
    public void testGetPlaylistName()
    {
        final String name = "Super Cool Test Music";
        checkGetPlaylistName(name, name);
    }

    /**
     * Tests getPlaylistName() if there is no playlist.
     */
    @Test
    public void testGetPlaylistNameUndefined()
    {
        checkGetPlaylistName(null, "");
    }

    /**
     * Tests whether the playback ratio can be queried.
     */
    @Test
    public void testGetPlaybackRatio()
    {
        final int ratio = 75;
        item.setPlaybackRatio(ratio);
        assertEquals("Wrong ratio", ratio, item.getPlaybackRatio());
    }

    /**
     * Prepares a source data object for a test for querying the combined album
     * name and track number property.
     *
     * @param album the album name
     * @param track the track number
     * @return the mock for the source data object
     */
    private AudioSourceData prepareAlbumAndTrackTest(String album, int track)
    {
        AudioSourceData data = initSourceData();
        EasyMock.expect(data.albumName()).andReturn(album).anyTimes();
        EasyMock.expect(data.trackNo()).andReturn(track).anyTimes();
        EasyMock.replay(data);
        return data;
    }

    /**
     * Helper method for testing the getAlbumAndTrack() method.
     *
     * @param album the album name
     * @param track the track number
     * @param expResult the expected result
     */
    private void checkGetAlbumAndTrack(String album, int track, String expResult)
    {
        AudioSourceData srcData = prepareAlbumAndTrackTest(album, track);
        EasyMock.replay(pldata);
        assertEquals("Wrong result", expResult, item.getAlbumAndTrack());
        EasyMock.verify(srcData, pldata);
    }

    /**
     * Tests getAlbumAndTrack() if neither is defined.
     */
    @Test
    public void testGetAlbumAndTrackUndefined()
    {
        checkGetAlbumAndTrack(null, 0, "");
    }

    /**
     * Tests getAlbumAndTrack() if only the album is defined.
     */
    @Test
    public void testGetAlbumAndTrackAlbumOnly()
    {
        final String albumName = "Hounds of Love";
        checkGetAlbumAndTrack(albumName, 0, albumName);
    }

    /**
     * Tests getAlbumAndTrack() if only the track number is defined.
     */
    @Test
    public void testGetAlbumAndTrackTrackOnly()
    {
        checkGetAlbumAndTrack(null, 10, "");
    }

    /**
     * Tests getAlbumAndTrack() if both values are defined.
     */
    @Test
    public void testGetAlbumAndTrackBothValues()
    {
        checkGetAlbumAndTrack("Lost in Space", 5, "Lost in Space (5)");
    }
}
