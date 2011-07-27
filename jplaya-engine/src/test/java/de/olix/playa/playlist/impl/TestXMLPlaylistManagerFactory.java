package de.olix.playa.playlist.impl;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.List;

import org.apache.commons.configuration.AbstractHierarchicalFileConfiguration;
import org.apache.commons.configuration.ConfigurationException;
import org.apache.commons.configuration.XMLConfiguration;
import org.easymock.EasyMock;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import de.olix.playa.playlist.CurrentPositionInfo;
import de.olix.playa.playlist.FSScanner;
import de.olix.playa.playlist.KeepGroup;
import de.olix.playa.playlist.PlaylistInfo;
import de.olix.playa.playlist.PlaylistManager;
import de.olix.playa.playlist.PlaylistOrder;
import de.olix.playa.playlist.PlaylistSettings;

/**
 * Test class for {@code XMLPlaylistManagerFactory}.
 *
 * @author Oliver Heger
 * @version $Id: $
 */
public class TestXMLPlaylistManagerFactory
{
    /** Constant for the default order. */
    private static final PlaylistOrder DEF_ORDER = PlaylistOrder.DIRECTORIES;

    /** An array for the content of a mock playlist. */
    private static final String[] MOCK_PLAYLIST = {
            "This", "is", "a", "mock", "playlist!"
    };

    /** Constant for the extension of a PLIST file. */
    private static final String EXT_PLIST = "plist";

    /** Constant for the extension of a settings file. */
    private static final String EXT_SETTINGS = "settings";

    /** Constant for the prefix of a test song URI. */
    private static final String SONG_URI = "testSongURI_";

    /** Constant for the format pattern for generating a test URI. */
    private static final String URI_PATTERN = SONG_URI + "%03d";

    /** Constant for a test checksum. */
    private static final String CHECKSUM = "AFFEABBA20110613172655";

    /** Constant for the key for adding a file to the playlist configuration. */
    private static final String KEY_ADDFILE = "list.file(-1)[@name]";

    /** Constant for the key for accessing files of the playlist. */
    private static final String KEY_URI = "list.file[@name]";

    /** Constant for the number of test songs. */
    private static final int SONG_COUNT = 128;

    /** An object managing temporary files. */
    @Rule
    public TemporaryFolder tempFolder = new TemporaryFolder();

    /** A mock for the scanner. */
    private FSScanner scanner;

    /** A folder for data files. */
    private File dataDirectory;

    @Before
    public void setUp() throws Exception
    {
        scanner = EasyMock.createMock(FSScanner.class);
        dataDirectory = tempFolder.newFolder("jplaya-data");
    }

    /**
     * Helper method for creating a default test instance.
     *
     * @return the test object
     */
    private XMLPlaylistManagerFactoryTestImpl createFactory()
    {
        return new XMLPlaylistManagerFactoryTestImpl(scanner,
                dataDirectory.getAbsolutePath());
    }

    /**
     * Creates the test URI with the given index.
     *
     * @param index the index
     * @return the test URI
     */
    private static String createTestURI(int index)
    {
        return String.format(URI_PATTERN, index);
    }

    /**
     * Creates a collection with test song URIs. The URIs are in descending
     * order so that sort functionality can be tested.
     *
     * @return the collection with test URIs
     */
    private static List<String> createTestURIs()
    {
        List<String> uris = new ArrayList<String>(SONG_COUNT);
        for (int i = SONG_COUNT - 1; i >= 0; i--)
        {
            uris.add(createTestURI(i));
        }
        return uris;
    }

    /**
     * Tries to create an instance without a data directory.
     */
    @Test(expected = IllegalArgumentException.class)
    public void testInitNoDir()
    {
        new XMLPlaylistManagerFactory(scanner, null);
    }

    /**
     * Tries to create an instance without a scanner.
     */
    @Test(expected = IllegalArgumentException.class)
    public void testInitNoScanner()
    {
        new XMLPlaylistManagerFactory(null, dataDirectory.getAbsolutePath());
    }

    /**
     * Tests whether a checksum can be calculated over the songs in the
     * playlist.
     */
    @Test
    public void testCalcChecksumBasic()
    {
        XMLPlaylistManagerFactoryTestImpl factory = createFactory();
        List<String> uris = createTestURIs();
        String checksum = factory.calcChecksum(uris);
        assertNotNull("No checksum", uris);
        assertEquals("Multiple checksum values", checksum,
                factory.calcChecksum(uris));
    }

    /**
     * Tests whether the checksum is independent on the order of the playlist
     * files.
     */
    @Test
    public void testCalcChecksumOrder()
    {
        XMLPlaylistManagerFactoryTestImpl factory = createFactory();
        List<String> uris = createTestURIs();
        String checksum = factory.calcChecksum(uris);
        Collections.reverse(uris);
        assertEquals("Different checksum", checksum, factory.calcChecksum(uris));
    }

    /**
     * Tests whether a different playlist yields a different checksum.
     */
    @Test
    public void testCalcChecksumDifferent()
    {
        XMLPlaylistManagerFactoryTestImpl factory = createFactory();
        List<String> uris = createTestURIs();
        String checksum = factory.calcChecksum(uris);
        uris.add("another Song!");
        assertFalse("Same checksum (1)",
                checksum.equals(factory.calcChecksum(uris)));
        uris.remove(SONG_COUNT);
        uris.remove(0);
        assertFalse("Same checksum (2)",
                checksum.equals(factory.calcChecksum(uris)));
    }

    /**
     * Creates a configuration and initializes the playlist section with the
     * given URIs.
     *
     * @param uris the URIs
     * @return the configuration
     */
    private static XMLConfiguration createPlaylistConfig(List<String> uris)
    {
        XMLConfiguration config = new XMLConfiguration();
        config.setAttributeSplittingDisabled(true);
        config.setDelimiterParsingDisabled(true);
        for (String uri : uris)
        {
            config.addProperty(KEY_ADDFILE, uri);
        }
        return config;
    }

    /**
     * Helper method for saving a configuration in the data directory.
     *
     * @param config the configuration to be saved
     * @param ext the file extension
     */
    private void saveConfig(XMLConfiguration config, String ext)
    {
        File file = createDataFile(ext);
        try
        {
            config.save(file);
        }
        catch (ConfigurationException cex)
        {
            fail("Could not save configuration: " + cex);
        }
    }

    /**
     * Helper method for loading a configuration in the data directory.
     *
     * @param ext the file extension
     * @return the configuration
     */
    private XMLConfiguration loadConfig(String ext)
    {
        XMLConfiguration config = new XMLConfiguration();
        try
        {
            config.load(createDataFile(ext));
        }
        catch (ConfigurationException cex)
        {
            fail("Could not load configuration: " + cex);
        }
        return config;
    }

    /**
     * Helper method for creating the file for the configuration in the data
     * directory with the specified extension.
     *
     * @param ext the file extension
     * @return the data file
     */
    private File createDataFile(String ext)
    {
        File file = new File(dataDirectory, CHECKSUM + "." + ext);
        return file;
    }

    /**
     * Prepares the mock for the file system scanner to return a list of URIs.
     * This list is also returned by this method.
     *
     * @return the list with URIs
     */
    private List<String> prepareScannerMock()
    {
        List<String> uris = createTestURIs();
        try
        {
            EasyMock.expect(scanner.scan()).andReturn(uris);
        }
        catch (IOException ioex)
        {
            fail("Unexpected exception: " + ioex);
        }
        EasyMock.replay(scanner);
        return uris;
    }

    /**
     * Tests whether a playlist manager for an interrupted playlist can be
     * created.
     */
    @Test
    public void testCreatePlaylistManagerExistingPlaylist() throws IOException
    {
        List<String> uris = prepareScannerMock();
        final int index = 11;
        final long pos = 20110613182715L;
        final long time = 20110613182740L;
        XMLConfiguration config = createPlaylistConfig(uris);
        config.addProperty("current.position", pos);
        config.addProperty("current.time", time);
        config.addProperty("current.index", index);
        saveConfig(config, EXT_PLIST);
        XMLPlaylistManagerFactoryTestImpl factory = createFactory();
        factory.installMockChecksum(CHECKSUM, uris);
        PlaylistManager manager = factory.createPlaylistManager(DEF_ORDER);
        assertEquals("Wrong current index", index,
                manager.getCurrentSongIndex());
        CurrentPositionInfo positionInfo = manager.getInitialPositionInfo();
        assertEquals("Wrong position", pos, positionInfo.getPosition());
        assertEquals("Wrong time", time, positionInfo.getTime());
        assertNull("Got a name", manager.getPlaylistInfo().getName());
        EasyMock.verify(scanner);
    }

    /**
     * Tests whether a playlist manager for an interrupted playlist can be
     * created and whether existing settings are found.
     */
    @Test
    public void testCreatePlaylistManagerExistingPlaylistWithSettings()
            throws IOException
    {
        List<String> uris = prepareScannerMock();
        XMLConfiguration config = createPlaylistConfig(uris);
        config.addProperty("current.index", 1);
        saveConfig(config, EXT_PLIST);
        PlaylistSettingsDTO dto = new PlaylistSettingsDTO();
        dto.setName("MyTestPlaylist");
        dto.setDescription("Lots of cool songs.");
        XMLConfiguration confSettings = new XMLConfiguration();
        dto.save(confSettings);
        saveConfig(confSettings, EXT_SETTINGS);
        XMLPlaylistManagerFactoryTestImpl factory = createFactory();
        factory.installMockChecksum(CHECKSUM, uris);
        PlaylistManager manager = factory.createPlaylistManager(DEF_ORDER);
        CurrentPositionInfo positionInfo = manager.getInitialPositionInfo();
        assertEquals("Wrong position", 0, positionInfo.getPosition());
        assertEquals("Wrong time", 0, positionInfo.getTime());
        PlaylistInfo info = manager.getPlaylistInfo();
        assertEquals("Wrong name", dto.getName(), info.getName());
        assertEquals("Wrong desc", dto.getDescription(), info.getDescription());
        assertEquals("Wrong song count", SONG_COUNT, info.getNumberOfSongs());
        EasyMock.verify(scanner);
    }

    /**
     * Tests whether a playlist manager can be created if there is an existing
     * PLIST file in legacy format.
     */
    @Test
    public void testCreatePlaylistManagerExistingLegacy() throws IOException
    {
        List<String> uris = prepareScannerMock();
        final int played = 16;
        List<String> plURIs = createTestURIs().subList(played + 1, SONG_COUNT);
        XMLConfiguration config = createPlaylistConfig(plURIs);
        config.addProperty("current.file[@name]", uris.get(played));
        config.addProperty("current.position", 1L);
        saveConfig(config, EXT_PLIST);
        XMLPlaylistManagerFactoryTestImpl factory = createFactory();
        factory.installMockChecksum(CHECKSUM, uris);
        PlaylistManager manager = factory.createPlaylistManager(DEF_ORDER);
        assertEquals("Wrong current index", 0, manager.getCurrentSongIndex());
        assertEquals("Wrong first URI", uris.get(played),
                manager.getCurrentSongURI());
        assertEquals("Wrong initial position", 1L, manager
                .getInitialPositionInfo().getPosition());
        assertEquals("Wrong song count", SONG_COUNT - played, manager
                .getPlaylistInfo().getNumberOfSongs());
        EasyMock.verify(scanner);
    }

    /**
     * Tests whether a playlist manager for a legacy playlist file can be
     * created if only the current file is remaining in the list.
     */
    @Test
    public void testCreatePlaylistManagerExistingLegacyCurrentOnly()
            throws IOException
    {
        List<String> uris = prepareScannerMock();
        XMLConfiguration config = new XMLConfiguration();
        config.addProperty("current.file[@name]", uris.get(0));
        config.addProperty("current.position", 1L);
        saveConfig(config, EXT_PLIST);
        XMLPlaylistManagerFactoryTestImpl factory = createFactory();
        factory.installMockChecksum(CHECKSUM, uris);
        PlaylistManager manager = factory.createPlaylistManager(DEF_ORDER);
        assertEquals("Wrong current index", 0, manager.getCurrentSongIndex());
        assertEquals("Wrong current URI", uris.get(0),
                manager.getCurrentSongURI());
        assertEquals("Wrong initial position", 1L, manager
                .getInitialPositionInfo().getPosition());
        assertEquals("Wrong song count", 1, manager.getPlaylistInfo()
                .getNumberOfSongs());
        EasyMock.verify(scanner);
    }

    /**
     * Tests that list delimiters in the playlist configuration do not cause
     * problems.
     */
    @Test
    public void testCreatePlaylistManagerExistingWithDelimiters()
            throws IOException
    {
        List<String> uris = prepareScannerMock();
        final String testURI = "file:///La, La, La.mp3";
        uris.add(testURI);
        XMLConfiguration config = createPlaylistConfig(uris);
        saveConfig(config, EXT_PLIST);
        XMLPlaylistManagerFactoryTestImpl factory = createFactory();
        factory.installMockChecksum(CHECKSUM, uris);
        PlaylistManager manager = factory.createPlaylistManager(DEF_ORDER);
        List<String> songURIs = manager.getSongURIs();
        assertEquals("Wrong song count", SONG_COUNT + 1, songURIs.size());
        assertEquals("Wrong special URI", testURI, songURIs.get(SONG_COUNT));
        EasyMock.verify(scanner);
    }

    /**
     * Helper method for testing whether the playlist manager has been
     * initialized with the mock playlist.
     *
     * @param pm the manager to check
     */
    private static void checkMockPlaylist(PlaylistManager pm)
    {
        XMLPlaylistManager manager = (XMLPlaylistManager) pm;
        assertEquals("Wrong number of URIs", MOCK_PLAYLIST.length, manager
                .getSongURIs().size());
        for (int i = 0; i < MOCK_PLAYLIST.length; i++)
        {
            assertEquals("Wrong URI at " + i, MOCK_PLAYLIST[i], manager
                    .getSongURIs().get(i));
        }
    }

    /**
     * Helper method for testing whether a new playlist is setup correctly.
     *
     * @param uris the list with song URIs
     * @param settings the expected playlist settings
     * @throws IOException if an IO error occurs
     */
    private void checkNewPlaylist(List<String> uris, PlaylistSettings settings)
            throws IOException
    {
        XMLPlaylistManagerFactoryTestImpl factory = createFactory();
        factory.installMockChecksum(CHECKSUM, uris);
        factory.installMockPlaylist(settings);
        PlaylistManager manager = factory.createPlaylistManager(DEF_ORDER);
        checkMockPlaylist(manager);
        EasyMock.verify(scanner);
    }

    /**
     * Tests whether a playlist manager can be created if there is a PLIST file,
     * but the list has been completely played.
     */
    @Test
    public void testCreatePlaylistManagerFinished() throws IOException
    {
        List<String> uris = prepareScannerMock();
        XMLConfiguration config = new XMLConfiguration();
        config.addProperty("current.position", "100");
        saveConfig(config, EXT_PLIST);
        checkNewPlaylist(uris, ImmutablePlaylistSettings.emptyInstance());
    }

    /**
     * Tests whether a playlist manager can be created if there is an existing,
     * empty PLIST file, and whether settings can be found.
     */
    @Test
    public void testCreatePlaylistManagerFinishedWithSettings()
            throws IOException
    {
        List<String> uris = prepareScannerMock();
        XMLConfiguration config = new XMLConfiguration();
        config.addProperty("current.position", "100");
        saveConfig(config, EXT_PLIST);
        PlaylistSettingsDTO dto = new PlaylistSettingsDTO();
        dto.setName("Test Playlist");
        dto.setOrder(PlaylistOrder.RANDOM);
        XMLConfiguration confSettings = new XMLConfiguration();
        dto.save(confSettings);
        saveConfig(confSettings, EXT_SETTINGS);
        checkNewPlaylist(uris,
                ImmutablePlaylistSettings.newInstance(confSettings));
    }

    /**
     * Tests whether a playlist manager for a completely new medium can be
     * created.
     */
    @Test
    public void testCreatePlaylistManagerNoPList() throws IOException
    {
        List<String> uris = prepareScannerMock();
        checkNewPlaylist(uris, ImmutablePlaylistSettings.emptyInstance());
    }

    /**
     * Tests whether configuration exceptions are handled correctly.
     */
    @Test
    public void testCreatePlaylistManagerConfigEx() throws IOException
    {
        dataDirectory.mkdirs();
        File plist = new File(dataDirectory, CHECKSUM + "." + EXT_PLIST);
        PrintWriter out = new PrintWriter(new FileWriter(plist));
        try
        {
            out.println("Not a valid XML file!");
        }
        finally
        {
            out.close();
        }
        List<String> uris = prepareScannerMock();
        XMLPlaylistManagerFactoryTestImpl factory = createFactory();
        factory.installMockChecksum(CHECKSUM, uris);
        try
        {
            factory.createPlaylistManager(DEF_ORDER);
            fail("ConfigurationException not detected!");
        }
        catch (IOException ioex)
        {
            assertTrue("Wrong cause: " + ioex,
                    ioex.getCause() instanceof ConfigurationException);
        }
        EasyMock.verify(scanner);
    }

    /**
     * Tests whether the correct encoding is used for configurations that are
     * saved.
     */
    @Test
    public void testCreateSaveConfigEncoding()
    {
        XMLPlaylistManagerFactoryTestImpl factory = createFactory();
        AbstractHierarchicalFileConfiguration config =
                factory.createSaveConfig();
        assertEquals("Wrong encoding", "iso-8859-1", config.getEncoding());
    }

    /**
     * Tests whether some internal settings of the configuration used for saving
     * the playlist are correctly initialized.
     */
    @Test
    public void testCreateSaveConfigSettings()
    {
        XMLPlaylistManagerFactoryTestImpl factory = createFactory();
        XMLConfiguration config = (XMLConfiguration) factory.createSaveConfig();
        assertTrue("Delimiter parsing not disabled",
                config.isDelimiterParsingDisabled());
        assertTrue("Attribute splitting not disabled",
                config.isAttributeSplittingDisabled());
    }

    /**
     * Tests whether the state of a manager can be saved.
     */
    @Test
    public void testSaveState() throws IOException
    {
        List<String> uris = prepareScannerMock();
        final int index = 11;
        XMLConfiguration config = createPlaylistConfig(uris);
        saveConfig(config, EXT_PLIST);
        XMLPlaylistManagerFactoryTestImpl factory = createFactory();
        factory.installMockChecksum(CHECKSUM, uris);
        PlaylistManager manager = factory.createPlaylistManager(DEF_ORDER);
        manager.setCurrentSongIndex(index);
        CurrentPositionInfo position =
                new CurrentPositionInfo(20110615215421L, 20110615215434L);
        manager.saveState(position);
        config = loadConfig(EXT_PLIST);
        List<?> savedURIs = config.getList(KEY_URI);
        assertEquals("Wrong number of files", uris.size(), savedURIs.size());
        int idx = 0;
        for (Object o : savedURIs)
        {
            assertEquals("Wrong file at " + idx, o, uris.get(idx));
            idx++;
        }
        assertEquals("Wrong current position", position.getPosition(),
                config.getLong("current.position"));
        assertEquals("Wrong current time", position.getTime(),
                config.getLong("current.time"));
        assertEquals("Wrong current index", index,
                config.getInt("current.index"));
        EasyMock.verify(scanner);
    }

    /**
     * Tests whether state of a manager can be saved without providing current
     * position information.
     */
    @Test
    public void testSaveStateNoCurrentInfo() throws IOException
    {
        List<String> uris = prepareScannerMock();
        XMLConfiguration config = createPlaylistConfig(uris);
        saveConfig(config, EXT_PLIST);
        XMLPlaylistManagerFactoryTestImpl factory = createFactory();
        factory.installMockChecksum(CHECKSUM, uris);
        PlaylistManager manager = factory.createPlaylistManager(DEF_ORDER);
        manager.nextSong();
        manager.saveState(null);
        config = loadConfig(EXT_PLIST);
        assertFalse("Got a current position",
                config.containsKey("current.position"));
        assertFalse("Got a current time", config.containsKey("current.time"));
        assertEquals("Wrong current index", 1, config.getInt("current.index"));
        EasyMock.verify(scanner);
    }

    /**
     * Tests whether the state of a manager can be saved if the playlist is
     * finished.
     */
    @Test
    public void testSaveStateFinished() throws IOException
    {
        XMLPlaylistManagerFactoryTestImpl factory = createFactory();
        CurrentPositionInfo posInfo = new CurrentPositionInfo(1, 2);
        XMLPlaylistManager manager =
                new XMLPlaylistManager(createTestURIs(),
                        ImmutablePlaylistSettings.emptyInstance(), posInfo,
                        createDataFile(EXT_PLIST), factory);
        factory.saveState(manager, posInfo, -1);
        XMLConfiguration config = loadConfig(EXT_PLIST);
        assertFalse("Got a playlist", config.containsKey(KEY_URI));
        assertFalse("Got a current position",
                config.containsKey("current.position"));
        assertFalse("Got a current index", config.containsKey("current.index"));
    }

    /**
     * Tests whether an exception caused by saving the playlist is correctly
     * handled.
     */
    @Test(expected = IOException.class)
    public void testSaveStateEx() throws IOException
    {
        @SuppressWarnings("serial")
        final XMLConfiguration errSaveConfig = new XMLConfiguration()
        {
            @Override
            public void save(File file) throws ConfigurationException
            {
                throw new ConfigurationException("Test exception!");
            }
        };
        XMLPlaylistManagerFactoryTestImpl factory =
                new XMLPlaylistManagerFactoryTestImpl(scanner,
                        dataDirectory.getAbsolutePath())
                {
                    @Override
                    protected org.apache.commons.configuration.AbstractHierarchicalFileConfiguration createSaveConfig()
                    {
                        return errSaveConfig;
                    };
                };
        File errFile = new File("test.xml");
        XMLPlaylistManager manager =
                new XMLPlaylistManager(createTestURIs(),
                        ImmutablePlaylistSettings.emptyInstance(), null,
                        errFile, factory);
        factory.saveState(manager, null, -1);
    }

    /**
     * Tests whether a playlist can be setup if the order is undefined.
     */
    @Test
    public void testSetupPlaylistUndefinedOrder()
    {
        List<String> uris = createTestURIs();
        XMLPlaylistManagerFactoryTestImpl factory = createFactory();
        List<String> playlist =
                factory.setUpPlaylist(uris,
                        ImmutablePlaylistSettings.emptyInstance(),
                        PlaylistOrder.UNDEFINED);
        assertEquals("Wrong size", uris.size(), playlist.size());
        for (int i = 0; i < uris.size(); i++)
        {
            assertEquals("Wrong URI at " + i, uris.get(i), playlist.get(i));
        }
    }

    /**
     * Tests a playlist if the order is DIRECTORIES.
     *
     * @param playlist the playlist to be checked
     */
    private void checkDirectoriesPlaylist(List<String> playlist)
    {
        assertEquals("Wrong size", SONG_COUNT, playlist.size());
        for (int i = 0; i < SONG_COUNT; i++)
        {
            assertEquals("Wrong URI at " + i, createTestURI(i), playlist.get(i));
        }
    }

    /**
     * Tests whether a playlist can be setup if the order is DIRECTORIES.
     */
    @Test
    public void testSetupPlaylistDirectoriesOrder()
    {
        List<String> uris = createTestURIs();
        XMLPlaylistManagerFactoryTestImpl factory = createFactory();
        PlaylistSettingsDTO settings = new PlaylistSettingsDTO();
        settings.setOrder(PlaylistOrder.DIRECTORIES);
        List<String> playlist =
                factory.setUpPlaylist(uris, settings, PlaylistOrder.UNDEFINED);
        checkDirectoriesPlaylist(playlist);
    }

    /**
     * Tests whether the default order is used when constructing a playlist if
     * no order is defined in the settings.
     */
    @Test
    public void testSetupPlaylistUseDefaultOrder()
    {
        List<String> uris = createTestURIs();
        XMLPlaylistManagerFactoryTestImpl factory = createFactory();
        List<String> playlist =
                factory.setUpPlaylist(uris,
                        ImmutablePlaylistSettings.emptyInstance(),
                        PlaylistOrder.DIRECTORIES);
        checkDirectoriesPlaylist(playlist);
    }

    /**
     * Tests whether a playlist can be setup if the order is EXACT.
     */
    @Test
    public void testSetupPlaylistExactOrder()
    {
        List<String> uris = createTestURIs();
        final int[] exactOrder = {
                5, 9, 1, SONG_COUNT - 1, 22, 99
        };
        PlaylistSettingsDTO settings = new PlaylistSettingsDTO();
        settings.setOrder(PlaylistOrder.EXACT);
        for (int i : exactOrder)
        {
            settings.getExactPlaylist().add(createTestURI(i));
        }
        XMLPlaylistManagerFactoryTestImpl factory = createFactory();
        List<String> playlist =
                factory.setUpPlaylist(uris, settings, PlaylistOrder.UNDEFINED);
        assertEquals("Wrong number of songs", exactOrder.length,
                playlist.size());
        int idx = 0;
        for (String uri : playlist)
        {
            assertEquals("Wrong URI at " + idx, createTestURI(exactOrder[idx]),
                    uri);
            idx++;
        }
    }

    /**
     * Tests a playlist in RANDOM order.
     *
     * @param uris the list with all URIs
     * @param playlist the playlist to be checked
     */
    private void checkRandomPlaylist(List<String> uris, List<String> playlist)
    {
        assertEquals("Wrong number of songs", uris.size(), playlist.size());
        assertTrue("Not all songs used", playlist.containsAll(uris));
        boolean found = false;
        int idx = 0;
        while (idx < SONG_COUNT && !found)
        {
            found = !uris.get(idx).equals(playlist.get(idx));
            idx++;
        }
        assertTrue("Playlist was not changed", found);
    }

    /**
     * Tests whether a playlist can be created if the order is RANDOM.
     */
    @Test
    public void testSetupPlaylistRandomOrder()
    {
        List<String> uris = createTestURIs();
        PlaylistSettingsDTO settings = new PlaylistSettingsDTO();
        settings.setOrder(PlaylistOrder.RANDOM);
        XMLPlaylistManagerFactoryTestImpl factory = createFactory();
        List<String> playlist =
                factory.setUpPlaylist(uris, settings, PlaylistOrder.UNDEFINED);
        checkRandomPlaylist(uris, playlist);
    }

    /**
     * Tests whether keep groups are taken into account when setting up a
     * playlist with random order.
     */
    @Test
    public void testSetupPlaylistRandomOrderWithKeepGroups()
    {
        List<String> uris = createTestURIs();
        PlaylistSettingsDTO settings = new PlaylistSettingsDTO();
        settings.setOrder(PlaylistOrder.RANDOM);
        List<KeepGroup> keepGroups = new ArrayList<KeepGroup>();
        KeepGroup kg =
                new KeepGroup(Arrays.asList(uris.get(10), uris.get(20),
                        uris.get(30)));
        keepGroups.add(kg);
        kg =
                new KeepGroup(Arrays.asList(uris.get(42), uris.get(33),
                        uris.get(100), uris.get(SONG_COUNT - 2)));
        keepGroups.add(kg);
        kg =
                new KeepGroup(Arrays.asList(uris.get(0), uris.get(1),
                        uris.get(2)));
        keepGroups.add(kg);
        settings.setKeepGroups(keepGroups);
        XMLPlaylistManagerFactoryTestImpl factory = createFactory();
        List<String> playlist =
                factory.setUpPlaylist(uris, settings, PlaylistOrder.UNDEFINED);
        checkRandomPlaylist(uris, playlist);
        for (KeepGroup group : keepGroups)
        {
            int idx = playlist.indexOf(group.getSongURI(0));
            for (int i = 1; i < group.size(); i++)
            {
                assertEquals("Wrong URI in keep group", group.getSongURI(i),
                        playlist.get(idx + i));
            }
        }
    }

    /**
     * A specialized test implementation with some mocking facilities.
     */
    private static class XMLPlaylistManagerFactoryTestImpl extends
            XMLPlaylistManagerFactory
    {
        /** Stores the expected URI collections. */
        private List<String> expectedURIs;

        /** The expected settings. */
        private PlaylistSettings expectedSettings;

        /** A mock playlist. */
        private List<String> mockPlaylist;

        /** A mock checksum. */
        private String checksum;

        public XMLPlaylistManagerFactoryTestImpl(FSScanner myScanner,
                String dataDir)
        {
            super(myScanner, dataDir);
        }

        /**
         * Installs a mock checksum that will be returned by calcChecksum().
         *
         * @param check the mock checksum
         * @param uris the expected collection with URIs
         */
        public void installMockChecksum(String check, List<String> uris)
        {
            expectedURIs = uris;
            checksum = check;
        }

        /**
         * Creates and installs a mock playlist that will be returned by
         * setUpPlaylist().
         *
         * @param expSettings the expected settings
         * @return the mock playlist
         */
        public List<String> installMockPlaylist(PlaylistSettings expSettings)
        {
            mockPlaylist = Arrays.asList(MOCK_PLAYLIST);
            expectedSettings = expSettings;
            return mockPlaylist;
        }

        /**
         * Either returns the mock checksum or calls the super method.
         */
        @Override
        protected String calcChecksum(Collection<String> songURIs)
        {
            if (checksum != null)
            {
                assertSame("Wrong URI collection", expectedURIs, songURIs);
                return checksum;
            }
            return super.calcChecksum(songURIs);
        }

        /**
         * Either returns the mock playlist or calls the super method.
         */
        @Override
        protected List<String> setUpPlaylist(Collection<String> songURIs,
                PlaylistSettings settings, PlaylistOrder defOrder)
        {
            if (mockPlaylist != null)
            {
                assertEquals("Wrong settings", expectedSettings, settings);
                assertSame("Wrong URI collection", expectedURIs, songURIs);
                assertEquals("Wrong default order", DEF_ORDER, defOrder);
                return mockPlaylist;
            }
            return super.setUpPlaylist(songURIs, settings, defOrder);
        }
    }
}
