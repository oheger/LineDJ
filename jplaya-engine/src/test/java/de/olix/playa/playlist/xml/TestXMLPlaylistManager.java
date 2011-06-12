package de.olix.playa.playlist.xml;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.URL;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Set;

import junit.framework.TestCase;

import org.easymock.EasyMock;

import de.olix.playa.engine.AudioReadMonitor;
import de.olix.playa.engine.AudioStreamData;
import de.olix.playa.engine.AudioStreamSource;
import de.olix.playa.playlist.CurrentPositionInfo;
import de.olix.playa.playlist.DefaultSongInfoProvider;
import de.olix.playa.playlist.PlaylistException;
import de.olix.playa.playlist.PlaylistInfo;
import de.olix.playa.playlist.SongInfo;
import de.olix.playa.playlist.SongInfoCallBack;
import de.olix.playa.playlist.SongInfoProvider;

/**
 * Test class for XMLPlaylistManager.
 *
 * @author Oliver Heger
 * @version $Id: PlaylistInfo.java 58 2006-11-13 19:22:50Z hacker $
 */
public class TestXMLPlaylistManager extends TestCase
{
    /** An array with some test interprets. */
    private static final String[] INTERPRETS =
    { "Mike Oldfield", "Pink Floyd" };

    /** An array with some albums of the test interprets. */
    private static final String[][] ALBUMS =
    {
            { "Crisis", "Discovery", "Islands", "QE2", "Tubular Bells" },
            { "Animals", "At\u00f6m Heart Mother", "Dark Side of the Moon",
                    "The Wall" } };

    /** An array with the numbers of tracks stored on the test albums. */
    private static final int[][] TRACKS =
    {
    { 6, 9, 8, 9, 2 },
    { 5, 7, 5, 16 } };

    /** Constant for the name of a track file. */
    private static final String TRACK_FILE = " - TRACK.mp3";

    /** Constant for the content of a dummy music file. */
    private static final String FILE_CONTENT = "This is a test dummy file.";

    /** Constant for the target directory. */
    private static final File TARGET = new File("target");

    /** Constant for the root directory of the music files. */
    private static final File MUSIC_DIR = new File(TARGET, "music");

    /** Constant for the data directory. */
    private static final File DATA_DIR = new File(TARGET, "data");

    /** Constant for the supported file extensions. */
    private static final String EXTENSIONS = "MP3;wav";

    /** Stores the object to be tested. */
    private XMLPlaylistManagerTestImpl manager;

    /** Stores the monitor object to be used. */
    private AudioReadMonitor monitor;

    /** Stores the song info provider to be used. */
    private SongInfoProvider provider;

    @Override
    protected void setUp() throws Exception
    {
        super.setUp();
        clearTestDirs(true);
        manager = new XMLPlaylistManagerTestImpl();
        manager.setDataDirectory(DATA_DIR.getAbsolutePath());
        manager.setMusicDirectory(MUSIC_DIR.getAbsolutePath());
        manager.setFileExtensions(EXTENSIONS);
        monitor = EasyMock.createMock(AudioReadMonitor.class);
        manager.initAudioReadMonitor(monitor);
        provider = EasyMock.createMock(SongInfoProvider.class);
        manager.initSongInfoProvider(provider);
    }

    @Override
    /**
     * Clears the test environment.
     */
    protected void tearDown() throws Exception
    {
        manager.shutdown();
        clearTestDirs(false);
    }

    /**
     * Clears the test directories. If the force flag is set, an error when
     * deleting a file will cause a test failure.
     *
     * @param force the force error flag
     */
    private void clearTestDirs(boolean force)
    {
        if (MUSIC_DIR.exists())
        {
            deleteDir(MUSIC_DIR, force);
        }
        if (DATA_DIR.exists())
        {
            deleteDir(DATA_DIR, force);
        }
    }

    /**
     * Recursively deletes a directory and all its content.
     *
     * @param dir the directory to delete
     * @param force when true, files that cannot be deleted will cause a test
     * failure
     */
    private void deleteDir(File dir, boolean force)
    {
        File[] content = dir.listFiles();
        if (content != null)
        {
            for (File f : content)
            {
                if (f.isDirectory())
                {
                    deleteDir(f, force);
                }
                else
                {
                    boolean ok = f.delete();
                    if (force)
                    {
                        assertTrue("Could not delete file " + f, ok);
                    }
                }
            }
        }
        boolean ok = dir.delete();
        if (force)
        {
            assertTrue("Could not delete directory " + dir, ok);
        }
    }

    /**
     * Creates a directory structure with some audio files that corresponds to
     * the constant definitions.
     */
    private void setUpMusicDir()
    {
        MUSIC_DIR.mkdir();
        for (int inter = 0; inter < INTERPRETS.length; inter++)
        {
            File interpretDir = new File(MUSIC_DIR, INTERPRETS[inter]);
            interpretDir.mkdir();
            for (int album = 0; album < ALBUMS[inter].length; album++)
            {
                File albumDir = new File(interpretDir, ALBUMS[inter][album]);
                albumDir.mkdir();
                for (int track = 1; track <= TRACKS[inter][album]; track++)
                {
                    createFile(albumDir, fileName(track));
                }
            }
        }
    }

    /**
     * Helper method for creating a file. This will be a dummy file with the
     * specified name.
     *
     * @param dir the directory
     * @param name the name of the file
     */
    private void createFile(File dir, String name)
    {
        File f = new File(dir, name);
        PrintWriter out = null;
        try
        {
            out = new PrintWriter(new FileWriter(f));
            out.println(name);
            out.println(FILE_CONTENT);
        }
        catch (IOException ioex)
        {
            ioex.printStackTrace();
            fail("Could not create test file " + f);
        }
        finally
        {
            if (out != null)
            {
                out.close();
            }
        }
    }

    /**
     * Checks the content of the specified file. Used for testing URLs created
     * for playlist items.
     *
     * @param url the URL to the test file
     * @param expName the name of this file
     * @throws IOException if an error occurs
     */
    private void checkFileURL(URL url, String expName) throws IOException
    {
        BufferedReader in = new BufferedReader(new InputStreamReader(url
                .openStream()));
        try
        {
            String line = in.readLine();
            assertEquals("Wrong file name", expName, line);
            assertEquals("Wrong content of file", FILE_CONTENT, in.readLine());
        }
        finally
        {
            in.close();
        }
    }

    /**
     * Creates the name of an album file based on the track number.
     *
     * @param track
     * @return
     */
    private String fileName(int track)
    {
        String trackNo = String.valueOf(track);
        if (trackNo.length() < 2)
        {
            trackNo = "0" + trackNo;
        }
        return trackNo + TRACK_FILE;
    }

    /**
     * Creates a playlist from the test data.
     *
     * @return the playlist
     */
    private Playlist setUpPlaylist()
    {
        Playlist list = new Playlist();
        for (int inter = 0; inter < INTERPRETS.length; inter++)
        {
            for (int album = 0; album < ALBUMS[inter].length; album++)
            {
                String path = INTERPRETS[inter] + '/' + ALBUMS[inter][album]
                        + '/';
                for (int track = 1; track <= TRACKS[inter][album]; track++)
                {
                    list.addItem(new PlaylistItem(path + fileName(track)));
                }
            }
        }
        return list;
    }

    /**
     * Creates the data directory.
     */
    private void setUpDataDir()
    {
        if (!DATA_DIR.exists())
        {
            assertTrue("Data directory could not be created", DATA_DIR.mkdir());
        }
    }

    /**
     * Tests scanning a directory structure.
     */
    public void testScan()
    {
        setUpMusicDir();
        Set<String> exts = new HashSet<String>();
        exts.add("mp3");
        Playlist list = manager.scan(MUSIC_DIR.getAbsolutePath(), exts);
        list.sort();
        checkPlaylist(list);
    }

    /**
     * Checks the content of a playlist.
     *
     * @param list the playlist to check
     */
    private void checkPlaylist(Playlist list)
    {
        for (int i = 0; i < INTERPRETS.length; i++)
        {
            for (int j = 0; j < ALBUMS[i].length; j++)
            {
                for (int k = 1; k <= TRACKS[i][j]; k++)
                {
                    String path = INTERPRETS[i] + '/' + ALBUMS[i][j] + '/'
                            + fileName(k);
                    assertEquals("Wrong file name", path, list.take()
                            .getPathName());
                }
            }
        }
        assertTrue("Too many files found", list.isEmpty());
    }

    /**
     * Tests whether file extensions are regarded when scanning a directory
     * structure.
     */
    public void testScanWithExtensions()
    {
        setUpMusicDir();
        createFile(MUSIC_DIR, "file.mru");
        File dir = new File(new File(MUSIC_DIR, INTERPRETS[1]), ALBUMS[1][2]);
        createFile(dir, "cover.jpg");
        createFile(dir, "album.info");
        Set<String> exts = new HashSet<String>();
        exts.add("mp3");
        exts.add("info");
        Playlist list = manager.scan(MUSIC_DIR.getAbsolutePath(), exts);
        boolean infoFound = false;
        while (!list.isEmpty())
        {
            PlaylistItem item = list.take();
            int pos = item.getPathName().lastIndexOf('.');
            String ext = item.getPathName().substring(pos + 1);
            if ("info".equals(ext))
            {
                infoFound = true;
            }
            else if (!"mp3".equals(ext))
            {
                fail("Found wrong file: " + item.getPathName());
            }
        }
        assertTrue("Info file not found", infoFound);
    }

    /**
     * Tests transforming the string list with file extensions into a set.
     */
    public void testGetSupportedExtensions()
    {
        Set<String> exts = manager.getSupportedExtensions();
        assertEquals("Wrong number of contained elements", 2, exts.size());
        assertTrue("Cannot find extension mp3", exts.contains("mp3"));
        assertTrue("Cannot find extension wav", exts.contains("wav"));
    }

    /**
     * Tests obtaining the extension set when the files are updated.
     */
    public void testGetSupportedExtensionsUpdate()
    {
        Set<String> exts = manager.getSupportedExtensions();
        assertSame("Different instance returned", exts, manager
                .getSupportedExtensions());
        manager.setFileExtensions("mp2");
        Set<String> exts2 = manager.getSupportedExtensions();
        assertNotSame("Object not updated", exts, exts2);
        assertEquals("Wrong number of extensions", 1, exts2.size());
        assertTrue("Extension not found", exts2.contains("mp2"));
    }

    /**
     * Tests obtaining the default file extensions.
     */
    public void testGetFileExtensionsDefault()
    {
        manager.setFileExtensions(null);
        assertNotNull("No file extensions supported", manager
                .getFileExtensions());
        assertTrue("No mp3 support", manager.getSupportedExtensions().contains(
                "mp3"));
    }

    /**
     * Tests defining the order for new playlists.
     */
    public void testGetNewPlaylistOrder()
    {
        manager.setNewPlaylistOrder(PlaylistSettings.Order.random);
        assertEquals("Wrong order", PlaylistSettings.Order.random, manager
                .getNewPlaylistOrder());
        manager.setNewPlaylistOrder(null);
        assertEquals("Wrong default order", PlaylistSettings.Order.directories,
                manager.getNewPlaylistOrder());
    }

    /**
     * Tests obtaining settings for a new, unknown playlist. In this case a new
     * settings object should be created.
     */
    public void testFindSettingsUnknown() throws PlaylistException
    {
        Playlist list = setUpPlaylist();
        manager.setNewPlaylistOrder(PlaylistSettings.Order.random);
        PlaylistSettings settings = manager.findSettings(list);
        assertNull("Playlist has a name", settings.getName());
        assertNull("Playlist has a description", settings.getDescription());
        assertEquals("Wrong order", PlaylistSettings.Order.random, settings
                .getOrder());
        assertEquals("Wrong file name", new File(DATA_DIR, list.getID()
                + ".settings").getAbsoluteFile(), settings.getSettingsFile());
    }

    /**
     * Tests obtaining settings from the music directory.
     */
    public void testFindSettingsFromMusicDir() throws IOException,
            PlaylistException
    {
        final String testName = "TestPlaylist";
        setUpMusicDir();
        Playlist list = setUpPlaylist();
        list.sort();
        File settingsFile = new File(MUSIC_DIR, ".settings");
        setUpSettingsFile(settingsFile, testName);

        PlaylistSettings settings = manager.findSettings(list);
        assertEquals("Wrong playlist name", testName, settings.getName());
        assertEquals("Wrong order", PlaylistSettings.Order.random, settings
                .getOrder());
        assertEquals("Wrong settings file", settingsFile.getAbsoluteFile(),
                settings.getSettingsFile());
    }

    /**
     * Tests obtaining settings from the data directory.
     */
    public void testFindSettingsFromDataDir() throws IOException,
            PlaylistException
    {
        final String testName = "TestDataPlaylist";
        final String testMusicName = "TestMusicPlaylist";
        setUpDataDir();
        setUpMusicDir();
        Playlist list = setUpPlaylist();
        list.sort();
        File settingsFile = new File(DATA_DIR, list.getID() + ".settings");
        setUpSettingsFile(settingsFile, testName);
        setUpSettingsFile(new File(MUSIC_DIR, ".settings"), testMusicName);

        PlaylistSettings settings = manager.findSettings(list);
        assertEquals("Wrong playlist name", testName, settings.getName());
        assertEquals("Wrong order", PlaylistSettings.Order.random, settings
                .getOrder());
        assertEquals("Wrong settings file", settingsFile.getAbsoluteFile(),
                settings.getSettingsFile());
    }

    /**
     * Creates a settings file at the specified location.
     *
     * @param target the file to be written
     * @param name the name of the playlist
     * @throws IOException if an error occurs
     */
    private void setUpSettingsFile(File target, String name) throws IOException
    {
        StringBuilder buf = new StringBuilder(256);
        buf.append("<settings><name>").append(name).append("</name>");
        buf.append("<order><mode>random</mode></order></settings>");
        BufferedWriter out = new BufferedWriter(new FileWriter(target));
        try
        {
            out.write(buf.toString());
        }
        finally
        {
            out.close();
        }
    }

    /**
     * Loads the manager's state and checks the returned position.
     *
     * @param expPos the expected position
     * @param expTime the expected time
     * @throws PlaylistException if an error occurs
     */
    private void checkPosition(long expPos, long expTime)
            throws PlaylistException
    {
        CurrentPositionInfo posInfo = manager.loadState();
        assertEquals("Wrong position", expPos, posInfo.getPosition());
        assertEquals("Wrong time", expTime, posInfo.getTime());
    }

    /**
     * Tests obtaining the current settings without calling loadState() before.
     * This should cause an exception.
     */
    public void testGetCurrentSettingsWithoutLoad()
    {
        try
        {
            manager.getCurrentSettings();
            fail("Could obtain settings without loading the state first!");
        }
        catch (IllegalStateException istex)
        {
            // ok
        }
    }

    /**
     * Tests obtaining the current playlist without calling loadState() before.
     * This should cause an exception.
     */
    public void testGetCurrentPlaylistWithoutLoad()
    {
        try
        {
            manager.getCurrentPlaylist();
            fail("Could obtain playlist without loading the state first!");
        }
        catch (IllegalStateException istex)
        {
            // ok
        }
    }

    /**
     * Tests obtaining an audio source without calling loadState() before. This
     * should cause an exception.
     */
    public void testGetSourceWithoutLoad()
    {
        try
        {
            manager.getSource();
            fail("Could obtain source without loading the state first!");
        }
        catch (IllegalStateException istex)
        {
            // ok
        }
    }

    /**
     * Tests loading a new playlist.
     */
    public void testLoadStateNewPlaylist() throws PlaylistException
    {
        setUpMusicDir();
        setUpDataDir();
        checkPosition(0, 0);
        assertNull("Playlist name available", manager.getCurrentSettings()
                .getName());
        checkPlaylist(manager.getCurrentPlaylist());
    }

    /**
     * Tests loading a playlist that has been partly played.
     */
    public void testLoadStateExistingList() throws PlaylistException,
            IOException
    {
        setUpMusicDir();
        setUpDataDir();
        Playlist list = setUpPlaylist();
        list.sort();
        File listFile = new File(DATA_DIR, list.getID() + ".plist");

        final int interpret = 0;
        final int album = 1;
        final int trackIdx = 2;
        final long position = 100;
        final long time = 4567;
        String path = INTERPRETS[interpret] + '/' + ALBUMS[interpret][album]
                + '/';
        StringBuilder buf = new StringBuilder("<playlist><current>");
        buf.append("<file name=\"").append(path);
        buf.append(fileName(trackIdx)).append("\"/>");
        buf.append("<position>").append(position).append("</position>");
        buf.append("<time>").append(time).append("</time>");
        buf.append("</current><list>");
        for (int track = trackIdx + 1; track <= TRACKS[interpret][album]; track++)
        {
            buf.append("<file name=\"").append(path);
            buf.append(fileName(track)).append("\"/>");
        }
        buf.append("</list></playlist>");

        BufferedWriter out = new BufferedWriter(new FileWriter(listFile));
        try
        {
            out.write(buf.toString());
        }
        finally
        {
            out.close();
        }

        checkPosition(position, time);
        list = manager.getCurrentPlaylist();
        for (int track = trackIdx; track < TRACKS[interpret][album]; track++)
        {
            assertEquals("Wrong path name", path + fileName(track), list.take()
                    .getPathName());
        }
    }

    /**
     * Tests loading the manager's state when the playlist was completely
     * played. In this case, a new playlist with the defined order is to be
     * created.
     */
    public void testLoadStateEmptyList() throws PlaylistException, IOException
    {
        setUpMusicDir();
        setUpDataDir();
        Playlist list = setUpPlaylist();
        list.sort();
        File listFile = new File(DATA_DIR, list.getID() + ".plist");
        PrintWriter out = new PrintWriter(new FileWriter(listFile));
        out.println("<playlist></playlist>");
        out.close();

        checkPosition(0, 0);
        assertEquals("Wrong number of elements in playlist", list.size(),
                manager.getCurrentPlaylist().size());
    }

    /**
     * Tests whether the correct order is applied to a new playlist.
     */
    public void testLoadStateApplyOrder() throws PlaylistException
    {
        setUpMusicDir();
        setUpDataDir();
        manager.setNewPlaylistOrder(PlaylistSettings.Order.random);
        Playlist list = setUpPlaylist();
        final int trials = 100;
        int orderChanged = 0;

        for (int i = 0; i < trials; i++)
        {
            checkPosition(0, 0);
            Playlist list2 = manager.getCurrentPlaylist();
            assertEquals("Wrong number of playlist items", list.size(), list2
                    .size());
            for (Iterator<PlaylistItem> it1 = list.items(), it2 = list2.items(); it1
                    .hasNext();)
            {
                if (!it1.next().equals(it2.next()))
                {
                    orderChanged++;
                    break;
                }
            }
        }

        assertTrue("No random order was created", orderChanged > 0);
    }

    /**
     * Tests loading the manager's state from a non existing directory
     * structure. This could happen in practice when data is to be read from a
     * CD-ROM, and no disc is in the drive. Result should be an empty playlist.
     */
    public void testLoadStateNonExistingDirectory() throws PlaylistException
    {
        manager.setMusicDirectory(new File("Non existing directory")
                .getAbsolutePath());
        checkPosition(0, 0);
        assertEquals("Wrong size of playlist", 0, manager.getCurrentPlaylist()
                .size());
    }

    /**
     * Tests obtaining a stream source.
     */
    public void testGetSource() throws PlaylistException, InterruptedException,
            IOException
    {
        AudioStreamSource source = setUpLoadTest();
        Playlist list = setUpPlaylist();
        for (Iterator<PlaylistItem> it = list.items(); it.hasNext();)
        {
            PlaylistItem item = it.next();
            AudioStreamData data = source.nextAudioStream();
            assertEquals("Wrong name of stream", item.getPathName(), data
                    .getName());
            File f = item.getFile(MUSIC_DIR).getAbsoluteFile();
            assertEquals("Wrong ID", f, data.getID());
            assertEquals("Wrong size of stream", f.length(), data.size());
            assertEquals("Wrong position", 0, data.getPosition());
            InputStream stream = data.getStream();
            assertNotNull("No stream returned", stream);
            stream.close();
        }
        AudioStreamData data = source.nextAudioStream();
        assertTrue("No end of source", data.size() < 0);
    }

    /**
     * Tests saving the manager's state while a song is played.
     */
    public void testSaveStateInSong() throws PlaylistException,
            InterruptedException
    {
        AudioStreamSource source = setUpLoadTest();
        final long position = 250;
        final long time = 12345;
        AudioStreamData data = source.nextAudioStream();
        assertNull("Already a song played", manager.getCurrentSongID());
        File file = (File) data.getID();
        manager.playbackStarted(file);
        assertEquals("Wrong current song ID", file, manager.getCurrentSongID());
        manager.saveState(new CurrentPositionInfo(position, time));

        checkPosition(position, time);
        assertEquals("Wrong current song ID after load", file, manager
                .getCurrentSongID());
        source = manager.getSource();
        assertEquals("Wrong stream data", file, source.nextAudioStream()
                .getID());
    }

    /**
     * Tests the manager's reaction when a null position info object is passed
     * in.
     */
    public void testSaveStateNullPosition() throws PlaylistException,
            InterruptedException
    {
        AudioStreamSource source = setUpLoadTest();
        AudioStreamData data = source.nextAudioStream();
        assertNull("Already a song played", manager.getCurrentSongID());
        File file = (File) data.getID();
        manager.playbackStarted(file);
        assertEquals("Wrong current song ID", file, manager.getCurrentSongID());
        manager.saveState(null);
        checkPosition(0, 0);
    }

    /**
     * Tests saving the manager's state after a song was finished. In this case
     * a passed in current position will be ignored.
     */
    public void testSaveStateAfterSong() throws PlaylistException,
            InterruptedException
    {
        AudioStreamSource source = setUpLoadTest();
        AudioStreamData data = source.nextAudioStream();
        AudioStreamData data2 = source.nextAudioStream();
        manager.playbackStarted(data.getID());
        manager.playbackEnded(data.getID());
        assertNull("Wrong current song ID", manager.getCurrentSongID());
        manager.saveState(new CurrentPositionInfo(1234, 5678));

        checkPosition(0, 0);
        assertNull("Wrong current song ID after load", manager
                .getCurrentSongID());
        source = manager.getSource();
        assertEquals("Wrong stream data", data2.getID(), source
                .nextAudioStream().getID());
    }

    /**
     * Tests the manager's behavior when a callback for a playback end gets
     * lost. Normally this should not happen, but who knows?
     */
    public void testSkipCallback() throws PlaylistException,
            InterruptedException
    {
        AudioStreamSource source = setUpLoadTest();
        AudioStreamData data1 = source.nextAudioStream();
        source.nextAudioStream();
        AudioStreamData data2 = source.nextAudioStream();
        manager.playbackStarted(data1.getID());
        manager.playbackStarted(data2.getID());
        assertEquals("Wrong current song ID", data2.getID(), manager
                .getCurrentSongID());
        final int position = 300;
        final int time = 12345;

        manager.saveState(new CurrentPositionInfo(position, time));
        checkPosition(position, time);
        source = manager.getSource();
        assertEquals("Wrong song after load", data2.getID(), source
                .nextAudioStream().getID());
        assertEquals("Wrong current song ID after load", data2.getID(), manager
                .getCurrentSongID());
    }

    /**
     * Tests what happens when a callback for an invalid ID arrives. This should
     * be ignored.
     */
    public void testInvalidCallback() throws PlaylistException,
            InterruptedException
    {
        AudioStreamSource source = setUpLoadTest();
        AudioStreamData data = source.nextAudioStream();
        manager.playbackStarted(data.getID());
        final Object invalidID = "AnInvalidID";
        manager.playbackStarted(invalidID);
        manager.playbackEnded(invalidID);
        final int position = 400;
        final int time = 333;

        manager.saveState(new CurrentPositionInfo(position, time));
        checkPosition(position, time);
        assertEquals("Wrong current song ID after load", data.getID(), manager
                .getCurrentSongID());
        assertEquals("Wrong song after load", data.getID(), manager.getSource()
                .nextAudioStream().getID());
    }

    /**
     * Prepares a test that involves loading the manager's state.
     *
     * @return the current audio stream source
     * @throws PlaylistException if an error occurs
     */
    private AudioStreamSource setUpLoadTest() throws PlaylistException
    {
        setUpDataDir();
        setUpMusicDir();
        checkPosition(0, 0);
        return manager.getSource();
    }

    /**
     * Tests saving the manager's state without loading it before. This should
     * cause an exception.
     */
    public void testSaveWithoutLoad() throws PlaylistException
    {
        try
        {
            manager.saveState(null);
            fail("Could save state without loading!");
        }
        catch (IllegalStateException istex)
        {
            // ok
        }
    }

    /**
     * Tests obtaining information about a song.
     */
    public void testRequestSongInfo() throws PlaylistException
    {
        setUpMusicDir();
        manager.loadState();
        assertNotNull("No audio loader created", manager.testLoader);
        File testFile = new File("test.mp3");
        SongInfo info = EasyMock.createMock(SongInfo.class);
        SongInfoCallBack mockCallBack = EasyMock
                .createMock(SongInfoCallBack.class);
        final Object param = "A test parameter";
        manager.testLoader.info = info;
        mockCallBack.putSongInfo(testFile, param, info);
        EasyMock.replay(info, mockCallBack);
        manager.requestSongInfo(testFile, mockCallBack, param);
        assertEquals("Wrong file passed", testFile,
                manager.testLoader.requestedFile);
        EasyMock.verify(mockCallBack);
    }

    /**
     * Tests obtaining an info object for an invalid stream ID. This should
     * cause an exception.
     */
    public void testRequestSongInfoInvalidID() throws PlaylistException
    {
        setUpLoadTest();
        SongInfoCallBack mockCallBack = EasyMock
                .createMock(SongInfoCallBack.class);
        EasyMock.replay(mockCallBack);
        try
        {
            manager.requestSongInfo("AnInvalidID", mockCallBack, null);
            fail("Could obtain info for invalid ID!");
        }
        catch (IllegalArgumentException iex)
        {
            // ok
        }
    }

    /**
     * Tries to request a song info object without providing a call back. This
     * should cause an exception.
     */
    public void testRequestSongInfoNoCallBack() throws PlaylistException
    {
        setUpLoadTest();
        try
        {
            manager.requestSongInfo(new File("test.mp3"), null, "test");
            fail("Null call back was not detected!");
        }
        catch (IllegalArgumentException iex)
        {
            // ok
        }
    }

    /**
     * Tries to obtain an info object before loadState() was called. This should
     * cause an exception.
     */
    public void testRequestSongInfoWithoutLoadState()
    {
        SongInfoCallBack mockCallBack = EasyMock
                .createMock(SongInfoCallBack.class);
        EasyMock.replay(mockCallBack);
        try
        {
            manager.requestSongInfo(new File("test.mp3"), mockCallBack, null);
            fail("Could obtain an info object before calling loadState()!");
        }
        catch (IllegalStateException istex)
        {
            // ok
        }
    }

    /**
     * Tests calling getPlaylistInfo() without calling loadState() first. This
     * should cause an exception.
     */
    public void testGetPlaylistInfoWithoutLoad()
    {
        try
        {
            manager.getPlaylistInfo();
            fail("Could call getPlaylistInfo() without calling loadState()!");
        }
        catch (IllegalStateException istex)
        {
            // ok
        }
    }

    /**
     * Tests obtaining a playlist info object.
     */
    public void testGetPlaylistInfo() throws IOException, PlaylistException,
            InterruptedException
    {
        setUpMusicDir();
        File settingsFile = new File(MUSIC_DIR, ".settings");
        final String name = "My playlist";
        setUpSettingsFile(settingsFile, name);
        Playlist list = setUpPlaylist();
        manager.loadState();
        AudioStreamSource source = manager.getSource();
        PlaylistInfo pinfo = manager.getPlaylistInfo();
        assertEquals("Wrong name", name, pinfo.getName());
        assertEquals("Wrong number of songs", list.size(), pinfo
                .getNumberOfSongs());

        AudioStreamData data = source.nextAudioStream();
        manager.playbackStarted(data.getID());
        pinfo = manager.getPlaylistInfo();
        manager.playbackEnded(data.getID());
        assertEquals("Wrong number of songs after playback", list.size(), pinfo
                .getNumberOfSongs());
    }

    /**
     * Tries to convert a null file to a URL. This should cause an exception.
     */
    public void testFetchURLForFileNull()
    {
        try
        {
            XMLPlaylistManager.fetchURLforFile(null);
            fail("Could transform null file to URL!");
        }
        catch (PlaylistException pex)
        {
            // ok
        }
    }

    /**
     * Tests fetching URLs for playlist items.
     */
    public void testFetchMediaURL() throws PlaylistException, IOException
    {
        setUpMusicDir();
        Playlist list = setUpPlaylist();
        for (Iterator<PlaylistItem> it = list.items(); it.hasNext();)
        {
            PlaylistItem item = it.next();
            int pos = item.getPathName().lastIndexOf('/');
            String fileName = item.getPathName().substring(pos + 1);
            URL url = XMLPlaylistManager.fetchMediaURL(item, MUSIC_DIR);
            checkFileURL(url, fileName);
        }
    }

    /**
     * Tests obtaining the URL for a playlist item when the file name contains
     * some special characters.
     */
    public void testFetchMediaURLSpecialChars() throws PlaylistException,
            IOException
    {
        String fileName = "M\u00e4ine sch\u00f6ne, \u00c4c\u00fcstische M\u00fcsic.mp3";
        PlaylistItem item = new PlaylistItem(fileName);
        setUpMusicDir();
        createFile(MUSIC_DIR, fileName);
        checkFileURL(XMLPlaylistManager.fetchMediaURL(item, MUSIC_DIR),
                fileName);
    }

    /**
     * Tries to pass a null playlist item to the fetchMediaURL() method. This
     * should cause an exception.
     */
    public void testFetchMediaURLNullItem()
    {
        setUpMusicDir();
        try
        {
            XMLPlaylistManager.fetchMediaURL(null, MUSIC_DIR);
            fail("Could fetch URL for null item!");
        }
        catch (PlaylistException pex)
        {
            // ok
        }
    }

    /**
     * Tries to pass a null directory to the fetchMediaURL() method. This should
     * cause an exception.
     */
    public void testFetchMediaURLNullDir()
    {
        try
        {
            XMLPlaylistManager.fetchMediaURL(new PlaylistItem("Test"), null);
            fail("Could fetch URL for null root dir!");
        }
        catch (PlaylistException pex)
        {
            // ok
        }
    }

    /**
     * Tests obtaining the stream ID for a playlist item.
     */
    public void testFetchStreamID()
    {
        setUpMusicDir();
        PlaylistItem item = new PlaylistItem("Test.wav");
        assertEquals("Wrong stream ID", item.getFile(MUSIC_DIR),
                XMLPlaylistManager.fetchStreamID(item, MUSIC_DIR));
    }

    /**
     * Tests obtaining a file from a stream ID.
     */
    public void testStreamIDToFile() throws PlaylistException
    {
        setUpMusicDir();
        assertEquals("Wrong file for stream ID", MUSIC_DIR, XMLPlaylistManager
                .streamIDToFile(MUSIC_DIR));
    }

    /**
     * Tries to obtain a file for an invalid stream ID. This should cause an
     * exception.
     */
    public void testStreamIDToFileInvalid()
    {
        try
        {
            XMLPlaylistManager.streamIDToFile("No valid ID");
            fail("Could obtain file from invalid stream ID");
        }
        catch (PlaylistException pex)
        {
            // ok
        }
    }

    /**
     * Tries to obtain a file for a null stream ID. This should cause an
     * exception.
     */
    public void testStreamIDToFileNull()
    {
        try
        {
            XMLPlaylistManager.streamIDToFile(null);
            fail("Could obtain file from null stream ID");
        }
        catch (PlaylistException pex)
        {
            // ok
        }
    }

    /**
     * Tests whether a correct info loader is created.
     */
    public void testCreateInfoLoader()
    {
        manager.superLoader = true;
        Playlist plist = new Playlist();
        SongInfoLoader loader = manager.createInfoLoader(plist);
        assertSame("Wrong info provider set", provider, loader
                .getInfoProvider());
        assertSame("Wrong monitor set", monitor, loader.getMonitor());
        assertEquals("Wrong directory set", MUSIC_DIR.getAbsoluteFile(), loader
                .getMediaDir());
    }

    /**
     * A special info loader test implementation that checks whether the loader
     * is correctly accessed.
     */
    static class SongInfoLoaderTestImpl extends SongInfoLoader
    {
        /** Stores the last requested media file. */
        File requestedFile;

        /** Stores the song info to be passed to the call back. */
        SongInfo info;

        public SongInfoLoaderTestImpl(SongInfoProvider provider,
                AudioReadMonitor mon, Playlist list, File rootDir,
                int fetchCount)
        {
            super(provider, mon, list, rootDir, fetchCount);
        }

        /**
         * Stores the requested media file and sends the info to the call back.
         */
        @Override
        public void fetchSongInfo(File mediaFile, Object parameter,
                SongInfoCallBack callBack)
        {
            requestedFile = mediaFile;
            callBack.putSongInfo(mediaFile, parameter, info);
        }
    }

    /**
     * A test playlist manager implementation. This implementation creates a
     * specialized song info loader.
     */
    static class XMLPlaylistManagerTestImpl extends XMLPlaylistManager
    {
        /** Stores the loader for direct access. */
        SongInfoLoaderTestImpl testLoader;

        /** A flag whether the super loader should be called. */
        boolean superLoader;

        @Override
        SongInfoLoader createInfoLoader(Playlist plist)
        {
            if (superLoader)
            {
                return super.createInfoLoader(plist);
            }
            else
            {
                testLoader = new SongInfoLoaderTestImpl(
                        new DefaultSongInfoProvider(), getAudioReadMonitor(),
                        plist, new File(getMusicDirectory()), 1);
                return testLoader;
            }
        }
    }
}
