package de.olix.playa.playlist.impl;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

import org.easymock.EasyMock;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;

import de.olix.playa.playlist.CurrentPositionInfo;
import de.olix.playa.playlist.PlaylistInfo;
import de.olix.playa.playlist.PlaylistSettings;

/**
 * Test class for {@code XMLPlaylistManager}.
 *
 * @author Oliver Heger
 * @version $Id: $
 */
public class TestXMLPlaylistManager
{
    /** Constant for a test song URI. */
    private static final String SONG_URI = "ATestSongURI_";

    /** The name of the test playlist. */
    private static final String PLAYLIST_NAME = "Top 100";

    /** The description of the test playlist. */
    private static final String PLAYLIST_DESC =
            "My personal favorites from 1680 to 2012";

    /** The file for storing the state. */
    private static final File PLIST_FILE = new File("test.plist");

    /** Constant for the number of test songs in the playlist. */
    private static final int COUNT = 32;

    /** The initial position information object. */
    private static CurrentPositionInfo initialPosition;

    /** The settings of the playlist. */
    private static PlaylistSettings settings;

    /** A mock for the playlist factory. */
    private XMLPlaylistManagerFactory factory;

    /** The object to be tested. */
    private XMLPlaylistManager manager;

    @BeforeClass
    public static void setUpBeforeClass() throws Exception
    {
        initialPosition = new CurrentPositionInfo(20110612191818L, 10000L);
        PlaylistSettingsDTO dto = new PlaylistSettingsDTO();
        dto.setName(PLAYLIST_NAME);
        dto.setDescription(PLAYLIST_DESC);
        settings = dto;
    }

    @Before
    public void setUp() throws Exception
    {
        factory = EasyMock.createMock(XMLPlaylistManagerFactory.class);
        manager =
                new XMLPlaylistManager(setUpURIs(), settings, initialPosition,
                        PLIST_FILE, factory);
    }

    /**
     * Creates a list with test URIs comprising the test playlist.
     *
     * @return the collection with test URIs
     */
    private static Collection<String> setUpURIs()
    {
        List<String> uris = new ArrayList<String>(COUNT);
        for (int i = 0; i < COUNT; i++)
        {
            uris.add(SONG_URI + i);
        }
        return uris;
    }

    /**
     * Tests whether the position of the first song can be queried.
     */
    @Test
    public void testGetInitialPositionInfo()
    {
        assertSame("Wrong position", initialPosition,
                manager.getInitialPositionInfo());
    }

    /**
     * Tests whether information about the playlist can be queried.
     */
    @Test
    public void testGetPlaylistInfo()
    {
        PlaylistInfo info = manager.getPlaylistInfo();
        assertEquals("Wrong name", PLAYLIST_NAME, info.getName());
        assertEquals("Wrong description", PLAYLIST_DESC, info.getDescription());
        assertEquals("Wrong number of songs", COUNT, info.getNumberOfSongs());
    }

    /**
     * Tests whether the correct file for the playlist is returned.
     */
    @Test
    public void testGetPListFile()
    {
        assertSame("Wrong file", PLIST_FILE, manager.getPListFile());
    }

    /**
     * Tests some properties of a newly created object.
     */
    @Test
    public void testInitialProperties()
    {
        assertFalse("Complete", manager.isFinished());
        assertEquals("Wrong index", 0, manager.getCurrentSongIndex());
        assertEquals("Wrong URI", SONG_URI + "0", manager.getCurrentSongURI());
    }

    /**
     * Tries to navigate to the previous song at the beginning of the list.
     */
    @Test
    public void testPreviousSongBeginning()
    {
        assertFalse("Wrong result", manager.previousSong());
        assertEquals("Wrong index", 0, manager.getCurrentSongIndex());
    }

    /**
     * Tests whether over the whole playlist can be iterated.
     */
    @Test
    public void testIterate()
    {
        for (int i = 1; i < COUNT; i++)
        {
            assertTrue("No more items", manager.nextSong());
            assertEquals("Wrong index", i, manager.getCurrentSongIndex());
            assertEquals("Wrong song URI", SONG_URI + i,
                    manager.getCurrentSongURI());
            assertFalse("Already finished", manager.isFinished());
        }
        assertFalse("Still more items", manager.nextSong());
        assertEquals("Wrong index at end", COUNT - 1,
                manager.getCurrentSongIndex());
        assertTrue("Not finished", manager.isFinished());
    }

    /**
     * Tests whether it is possible to move to a specific song.
     */
    @Test
    public void testSetCurrentSongIndex()
    {
        int idx = 5;
        manager.setCurrentSongIndex(idx);
        assertEquals("Wrong index", idx, manager.getCurrentSongIndex());
        assertEquals("Wrong URI", SONG_URI + idx, manager.getCurrentSongURI());
    }

    /**
     * Tries to set a song index which is too small.
     */
    @Test(expected = IndexOutOfBoundsException.class)
    public void testSetCurrentSongIndexTooSmall()
    {
        manager.setCurrentSongIndex(-1);
    }

    /**
     * Tries to set a song index which is too big.
     */
    @Test(expected = IndexOutOfBoundsException.class)
    public void testSetCurrentSongIndexTooBig()
    {
        manager.setCurrentSongIndex(COUNT);
    }

    /**
     * Tests whether it is possible to move a position backwards.
     */
    @Test
    public void testPreviousSongValidIndex()
    {
        int idx = 4;
        manager.setCurrentSongIndex(idx);
        assertTrue("Wrong result", manager.previousSong());
        assertEquals("Wrong index", idx - 1, manager.getCurrentSongIndex());
        assertEquals("Wrong URI", SONG_URI + (idx - 1),
                manager.getCurrentSongURI());
    }

    /**
     * Navigates to the end of the playlist.
     */
    private void navigateToEnd()
    {
        while (manager.nextSong())
            ;
        assertTrue("Not finished", manager.isFinished());
    }

    /**
     * Tests whether a finished playlist can be reactivated by moving backwards.
     */
    @Test
    public void testPreviousAfterFinished()
    {
        navigateToEnd();
        assertTrue("Wrong result", manager.previousSong());
        assertFalse("Still finished", manager.isFinished());
    }

    /**
     * Tests whether a finished playlist can be reactivated by setting a song
     * index.
     */
    @Test
    public void testSetCurrentSongIndexAfterFinished()
    {
        navigateToEnd();
        manager.setCurrentSongIndex(COUNT - 1);
        assertFalse("Still finished", manager.isFinished());
    }

    /**
     * Tests whether the state can be saved.
     */
    @Test
    public void testSaveState() throws IOException
    {
        CurrentPositionInfo posInfo =
                new CurrentPositionInfo(20110612194828L, 20110612194840L);
        factory.saveState(manager, posInfo, 0);
        EasyMock.replay(factory);
        manager.saveState(posInfo);
        EasyMock.verify(factory);
    }

    /**
     * Tests saveState() if the playlist has been finished.
     */
    @Test
    public void testSaveStateFinished() throws IOException
    {
        navigateToEnd();
        factory.saveState(manager, null, -1);
        EasyMock.replay(factory);
        manager.saveState(null);
        EasyMock.verify(factory);
    }

    /**
     * Tries to invoke the copy constructor with a null object.
     */
    @Test(expected = IllegalArgumentException.class)
    public void testInitCopyNull()
    {
        new XMLPlaylistManager(null);
    }

    /**
     * Tests whether a copy of the manager can be created.
     */
    @Test
    public void testCopy()
    {
        manager.setCurrentSongIndex(2);
        XMLPlaylistManager copy = (XMLPlaylistManager) manager.copy();
        assertSame("Different playlist", manager.getSongURIs(),
                copy.getSongURIs());
        assertSame("Different current position", initialPosition,
                copy.getInitialPositionInfo());
        assertSame("Different info", manager.getPlaylistInfo(),
                copy.getPlaylistInfo());
        assertEquals("Different file", manager.getPListFile(),
                copy.getPListFile());
    }

    /**
     * Tests whether the list with song URIs cannot be modified.
     */
    @Test(expected = UnsupportedOperationException.class)
    public void testGetSongURIsModify()
    {
        manager.getSongURIs().add("newSong");
    }
}
