package de.oliver_heger.jplaya.ui.mainwnd;

import static org.junit.Assert.assertEquals;

import org.junit.Before;
import org.junit.Test;

/**
 * Test class for {@code SongDataSynchronizer}.
 *
 * @author Oliver Heger
 * @version $Id: $
 */
public class TestSongDataSynchronizer
{
    /** Constant for the URI of a test song. */
    private static final String URI = "file://my_test_song.mp3";

    /** The object to be tested. */
    private SongDataSynchronizer sync;

    @Before
    public void setUp() throws Exception
    {
        sync = new SongDataSynchronizer();
    }

    /**
     * Tests the reaction on a song played event if no song data is available.
     */
    @Test
    public void testSongPlayedEventReceivedNoDataAvailable()
    {
        assertEquals("Wrong result", 0, sync.songPlayedEventReceived(URI));
    }

    /**
     * Tests the reaction on a song data event if the song has not yet been
     * played.
     */
    @Test
    public void testSongDataEventReceivedSongNotPlayed()
    {
        assertEquals("Wrong result", 0, sync.songDataEventReceived(URI));
    }

    /**
     * Tests reaction on a song played event if song data is available.
     */
    @Test
    public void testSongPlayedEventAfterSongDataEvent()
    {
        sync.songDataEventReceived(URI);
        assertEquals("Wrong result", 1, sync.songPlayedEventReceived(URI));
    }

    /**
     * Tests reaction on a song data event if the song has been played.
     */
    @Test
    public void testSongDataEventAfterSongPlayedEvent()
    {
        sync.songPlayedEventReceived(URI);
        assertEquals("Wrong result", 1, sync.songDataEventReceived(URI));
    }

    /**
     * Tests whether multiple song played events are accumulated.
     */
    @Test
    public void testSongDataEventAfterMultipleSongPlayedEvents()
    {
        sync.songPlayedEventReceived(URI);
        sync.songPlayedEventReceived(URI + "_other");
        assertEquals("Wrong result of played event", 0,
                sync.songPlayedEventReceived(URI));
        assertEquals("Wrong result of data event", 2,
                sync.songDataEventReceived(URI));
    }

    /**
     * Tests whether the counter for played events is reset if song data is
     * available.
     */
    @Test
    public void testCounterResetForMultiplePlayedEvents()
    {
        sync.songPlayedEventReceived(URI);
        sync.songPlayedEventReceived(URI);
        assertEquals("Wrong result of data event", 2,
                sync.songDataEventReceived(URI));
        assertEquals("Wrong result of new played event", 1,
                sync.songPlayedEventReceived(URI));
    }
}
