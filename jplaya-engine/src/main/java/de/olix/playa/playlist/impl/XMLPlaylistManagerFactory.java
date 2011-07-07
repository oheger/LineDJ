package de.olix.playa.playlist.impl;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.zip.CRC32;

import org.apache.commons.configuration.AbstractHierarchicalFileConfiguration;
import org.apache.commons.configuration.ConfigurationException;
import org.apache.commons.configuration.HierarchicalConfiguration;
import org.apache.commons.configuration.XMLConfiguration;

import de.olix.playa.playlist.CurrentPositionInfo;
import de.olix.playa.playlist.FSScanner;
import de.olix.playa.playlist.KeepGroup;
import de.olix.playa.playlist.PlaylistManager;
import de.olix.playa.playlist.PlaylistManagerFactory;
import de.olix.playa.playlist.PlaylistOrder;
import de.olix.playa.playlist.PlaylistSettings;

/**
 * <p>
 * A specialized implementation of the {@code PlaylistManagerFactory} interface
 * which creates playlist managers using XML files as their data format.
 * </p>
 * <p>
 * This playlist manager factory implementation is initialized with a
 * {@link FSScanner} object which scans a specific directory structure. From the
 * information provided by this scanner object it creates a checksum that is
 * used as the playlist's ID. Information about the available songs and the
 * current playlist is stored in a XML file whose name is derived from the
 * checksum with the extension &quot;.plist&quot; in a configurable data
 * directory. If a second file with the same name, but the extension
 * &quot;.settings&quot; exists, this file is used to determine some properties
 * of the playlist. It can contain the following elements:
 * </p>
 * <p>
 * <table border="1">
 * <tr>
 * <th>Element</th>
 * <th>Description</th>
 * </tr>
 * <tr>
 * <td valign="top">name</td>
 * <td>A name for the playlist.</td>
 * </tr>
 * <tr>
 * <td valign="top">description</td>
 * <td>Here a description for the playlist can be set.</td>
 * </tr>
 * <tr>
 * <td valign="top">order</td>
 * <td>This property defines the order, in which the songs of the playlist are
 * played. It has a sub element named <code>mode</code>, for which the following
 * values are allowed:
 * <dl>
 * <dt>directories</dt>
 * <dd>The songs are played in an order defined by the directory structure. This
 * is usually suitable if the playlist contains different albums that are each
 * stored in their own directory. The contents of a directory is played in
 * alphabetical order.</dd>
 * <dt>random</dt>
 * <dd>A random order is used. In this mode, an arbitrary number of
 * <code>keep</code> elements can be placed below the <code>order</code>
 * element. Each <code>keep</code> element can in turn contain an arbitrary
 * number of <code>file</code> elements with a <code>name</code> attribute
 * storing relative path names to the files in the playlist. The meaning of
 * these elements is that they allow defining files that always should be played
 * in serial (e.g. if a large song is split into multiple song files).</dd>
 * <dt>exact</dt>
 * <dd>In this mode a playlist can directly be specified. This is done by
 * placing a <code>list</code> element below the <code>order</code> element.
 * This element can have an arbitrary number of <code>file</code> sub elements
 * with relative path names to the files to be played. Only the files specified
 * here will be played; if the directory tree contains more song files, these
 * files will be ignored.</dd></td>
 * </table>
 * </p>
 * <p>
 * If no such file exists in the data directory, for a file named
 * <code>.settings</code> is searched in the root directory of the directory
 * structure that is parsed. If here a file with the same structure as described
 * above is found, information about the playlist will be obtained from this
 * file. This mechanism allows it for instance to add a default settings file to
 * a CD ROM, but it will be also possible to override the settings by creating a
 * specific settings file in the data directory.
 * </p>
 *
 * @author Oliver Heger
 * @version $Id: $
 */
public class XMLPlaylistManagerFactory implements PlaylistManagerFactory
{
    /** Constant for the extension for PLIST files. */
    private static final String EXT_PLIST = ".plist";

    /** Constant for the extension for settings files. */
    private static final String EXT_SETTINGS = ".settings";

    /** Constant for the section with information about the current song. */
    private static final String SEC_CURRENT = "current.";

    /** Constant for the key referring to a file name. */
    private static final String KEY_FILE = "file[@name]";

    /** Constant for the current file in the playlist. */
    private static final String KEY_CURFILE = SEC_CURRENT + KEY_FILE;

    /**
     * Constant for the configuration key which contains the files of the
     * playlist.
     */
    private static final String KEY_FILE_LIST = "list." + KEY_FILE;

    /** Constant for the key for adding a file to the playlist configuration. */
    private static final String KEY_ADDFILE = "list.file(-1)[@name]";

    /** Constant for the configuration key for the current position. */
    private static final String KEY_CURPOSITION = SEC_CURRENT + "position";

    /** Constant for the configuration key for the current time. */
    private static final String KEY_CURTIME = SEC_CURRENT + "time";

    /** Constant for the configuration key for the current index. */
    private static final String KEY_CURINDEX = SEC_CURRENT + "index";

    /** Constant for a reserved URI representing a keep group. */
    private static final String URI_KEEP_GROUP = "keepgroup://";

    /** Constant for the encoding of configurations which are saved. */
    private static final String ENCODING = "iso-8859-1";

    /** The scanner for scanning the media directory. */
    private final FSScanner scanner;

    /** The data directory. */
    private final File dataDirectory;

    /**
     * Creates a new instance of {@code XMLPlaylistManagerFactory}.
     *
     * @param myScanner the object for scanning the media directory (must not be
     *        <b>null</b>)
     * @param dataDir the directory in which to store data files (must not be
     *        <b>null</b>)
     * @throws IllegalArgumentException if a required parameter is missing
     */
    public XMLPlaylistManagerFactory(FSScanner myScanner, String dataDir)
    {
        if (myScanner == null)
        {
            throw new IllegalArgumentException("Scanner must not be null!");
        }
        if (dataDir == null)
        {
            throw new IllegalArgumentException(
                    "Data directory must not be null!");
        }

        scanner = myScanner;
        dataDirectory = new File(dataDir);
    }

    /**
     * Returns the data directory used by this factory. In this directory the
     * playlist and settings files are stored.
     *
     * @return the data directory
     */
    public File getDataDirectory()
    {
        return dataDirectory;
    }

    /**
     * Creates a new {@code PlaylistManager}. This implementation scans the
     * source medium and calculates a checksum over the media files found. Based
     * on this checksum files for the current status of the playlist and
     * playlist settings are searched in the data directory. If a playlist that
     * has been interrupted is found, it is continued. Otherwise, a new playlist
     * is generated. The {@code PlaylistManager} returned by this method is
     * correspondingly initialized.
     *
     * @param defaultOrder the default playlist order
     * @return the new {@code PlaylistManager}
     * @throws IOException if an IO error occurs
     */
    @Override
    public PlaylistManager createPlaylistManager(PlaylistOrder defaultOrder)
            throws IOException
    {
        Collection<String> uris = scanner.scan();
        String checksum = calcChecksum(uris);

        try
        {
            XMLConfiguration config = fetchPlaylistConfig(checksum);
            PlaylistSettings settings = fetchSettings(checksum);

            List<String> playlist =
                    obtainPlaylist(uris, config, settings, defaultOrder);
            XMLPlaylistManager manager =
                    new XMLPlaylistManager(playlist, settings,
                            fetchPositionInfo(config), config.getFile(), this);
            initCurrentIndex(config, manager);
            return manager;
        }
        catch (ConfigurationException cex)
        {
            throw new IOException(cex);
        }
    }

    /**
     * Saves the current state of the specified {@code PlaylistManager}. This
     * method is called by the {@code XMLPlaylistManager} class if the manager's
     * state is to be saved. This implementation stores sufficient information
     * in an XML file to continue the playlist at the current position. If a
     * {@code CurrentPositionInfo} object is specified, the current position in
     * the currently played song is stored. Otherwise, it is expected that the
     * current song should be played from the beginning.
     *
     * @param manager the playlist manager
     * @param posInfo the current position in the current song
     * @param currentIndex the index of the current song in the playlist; a
     *        value &lt; 0 means that the playlist is finished
     * @throws IOException if an IO exception occurs
     */
    public void saveState(XMLPlaylistManager manager,
            CurrentPositionInfo posInfo, int currentIndex) throws IOException
    {
        AbstractHierarchicalFileConfiguration config = createSaveConfig();
        if (currentIndex >= 0)
        {
            fillPlaylistConfig(config, manager.getSongURIs(), posInfo,
                    currentIndex);
        }

        try
        {
            config.save(manager.getPListFile());
        }
        catch (ConfigurationException cex)
        {
            throw new IOException("Error when saving playlist file: "
                    + manager.getPlaylistInfo(), cex);
        }
    }

    /**
     * Calculates a checksum over the given list of song URIs. This checksum is
     * used for determining the file names of the data files for the current
     * playlist.
     *
     * @param songURIs a collection with song URIs
     * @return the corresponding checksum as string
     */
    protected String calcChecksum(Collection<String> songURIs)
    {
        List<String> uris = new ArrayList<String>(songURIs);
        Collections.sort(uris);
        CRC32 checksum = new CRC32();

        for (String uri : uris)
        {
            checksum.update(uri.getBytes());
        }

        return Long.toHexString(checksum.getValue());
    }

    /**
     * Generates a new playlist for the songs found on the source medium. This
     * method is called if no current playlist is found or if the current
     * playlist is already finished. It evaluates the playlist settings to
     * determine the desired playlist order.
     *
     * @param songURIs a collection with the URIs of all songs found on the
     *        source medium
     * @param settings the settings for the current playlist
     * @param defOrder the default order to be used if the order in the settings
     *        is undefined
     * @return the new playlist
     */
    protected List<String> setUpPlaylist(Collection<String> songURIs,
            PlaylistSettings settings, PlaylistOrder defOrder)
    {
        List<String> playlist;
        PlaylistOrder order = settings.getOrder();
        if (order == PlaylistOrder.UNDEFINED)
        {
            order = defOrder;
        }

        switch (order)
        {
        case EXACT:
            playlist = createExactPlaylist(settings);
            break;
        case DIRECTORIES:
            playlist = createDirectoriesPlaylist(songURIs);
            break;
        case RANDOM:
            playlist = createRandomPlaylist(songURIs, settings);
            break;
        default:
            playlist = new ArrayList<String>(songURIs);
        }

        return playlist;
    }

    /**
     * Creates the configuration for saving a playlist. This method is called by
     * {@link #saveState(XMLPlaylistManager, CurrentPositionInfo, int)}.
     *
     * @return the configuration for saving the state of the playlist manager
     */
    protected AbstractHierarchicalFileConfiguration createSaveConfig()
    {
        XMLConfiguration config = new XMLConfiguration();
        config.setEncoding(ENCODING);
        return config;
    }

    /**
     * Populates a configuration object with the data of a playlist. This method
     * is called whenever the state of a playlist manager is to be saved and the
     * playlist is not finished.
     *
     * @param config the configuration to be filled
     * @param songURIs the list with the song URIs of the playlist
     * @param posInfo the information about the current position
     * @param currentIndex the current index in the playlist
     */
    protected void fillPlaylistConfig(HierarchicalConfiguration config,
            List<String> songURIs, CurrentPositionInfo posInfo, int currentIndex)
    {
        config.addProperty(KEY_CURINDEX, currentIndex);
        storePositionInfo(config, posInfo);
        for (String uri : songURIs)
        {
            config.addProperty(KEY_ADDFILE, uri);
        }
    }

    /**
     * Loads a configuration for a saved PLIST file from the data directory. If
     * no such file can be found, result is an empty configuration.
     *
     * @param checksum the checksum of the current playlist
     * @return a configuration for this playlist
     * @throws ConfigurationException if there is an error when loading the file
     */
    private XMLConfiguration fetchPlaylistConfig(String checksum)
            throws ConfigurationException
    {
        File f = getDataFile(checksum, EXT_PLIST);
        if (f.exists())
        {
            return loadConfiguration(f);
        }
        else
        {
            XMLConfiguration config = new XMLConfiguration();
            config.setFile(f);
            return config;
        }
    }

    /**
     * Obtains the settings for the playlist with the given checksum. This
     * method checks whether there is a settings file for the playlist in the
     * data directory. If so, it is loaded and parsed. Otherwise, an empty
     * settings object is returned.
     *
     * @param checksum the checksum of the playlist
     * @return settings for this playlist
     * @throws ConfigurationException if the settings file cannot be loaded
     */
    private PlaylistSettings fetchSettings(String checksum)
            throws ConfigurationException
    {
        File f = getDataFile(checksum, EXT_SETTINGS);
        if (f.exists())
        {
            HierarchicalConfiguration config = loadConfiguration(f);
            return ImmutablePlaylistSettings.newInstance(config);
        }
        else
        {
            return ImmutablePlaylistSettings.emptyInstance();
        }
    }

    /**
     * Determines the list with the songs to be played from the passed in
     * information. This method checks whether the configuration contains a
     * playlist. If this is the case, it is extracted. Otherwise, a new playlist
     * has to be constructed.
     *
     * @param songURIs a collection with the songs found on the medium
     * @param config the configuration for the PLIST file
     * @param settings the settings
     * @param defOrder the default order
     * @return the playlist
     */
    private List<String> obtainPlaylist(Collection<String> songURIs,
            HierarchicalConfiguration config, PlaylistSettings settings,
            PlaylistOrder defOrder)
    {
        if (config.containsKey(KEY_FILE_LIST)
                || config.containsKey(KEY_CURFILE))
        {
            return extractPlaylist(config);
        }
        else
        {
            return setUpPlaylist(songURIs, settings, defOrder);
        }
    }

    /**
     * Extracts a playlist from a configuration.
     *
     * @param config the configuration
     * @return the playlist which has been extracted
     */
    private List<String> extractPlaylist(HierarchicalConfiguration config)
    {
        List<?> files = config.getList(KEY_FILE_LIST);
        String currentFile = config.getString(KEY_CURFILE);
        int size = files.size();
        if (currentFile != null)
        {
            size++;
        }

        List<String> playlist = new ArrayList<String>(size);
        if (currentFile != null)
        {
            playlist.add(currentFile);
        }
        for (Object o : files)
        {
            playlist.add(String.valueOf(o));
        }

        return playlist;
    }

    /**
     * Creates a {@code File} object pointing to the specified data file.
     *
     * @param checksum the checksum (from which the name is derived)
     * @param ext the file extension
     * @return the corresponding data file
     */
    private File getDataFile(String checksum, String ext)
    {
        return new File(getDataDirectory(), checksum + ext);
    }

    /**
     * Helper method for loading a configuration from a file. This method loads
     * XML configuration files.
     *
     * @param f the file to be loaded
     * @return the configuration object
     * @throws ConfigurationException if an error occurs when the file is loaded
     */
    private XMLConfiguration loadConfiguration(File f)
            throws ConfigurationException
    {
        XMLConfiguration config = new XMLConfiguration();
        config.setFile(f);
        config.load();
        return config;
    }

    /**
     * Extracts the data of a {@code CurrentPositionInfo} object from the
     * configuration representing the playlist file.
     *
     * @param config the configuration
     * @return the extracted {@code CurrentPositionInfo} object
     */
    private CurrentPositionInfo fetchPositionInfo(
            HierarchicalConfiguration config)
    {
        return new CurrentPositionInfo(config.getLong(KEY_CURPOSITION, 0L),
                config.getLong(KEY_CURTIME, 0L));
    }

    /**
     * Writes the data of a {@code CurrentPositionInfo} object in a
     * configuration.
     *
     * @param config the configuration
     * @param posInfo the {@code CurrentPositionInfo} object
     */
    private void storePositionInfo(HierarchicalConfiguration config,
            CurrentPositionInfo posInfo)
    {
        if (posInfo != null)
        {
            config.addProperty(KEY_CURPOSITION, posInfo.getPosition());
            config.addProperty(KEY_CURTIME, posInfo.getTime());
        }
    }

    /**
     * Initializes the current index of a newly created playlist manager object.
     * This method checks whether an index is defined in the configuration. If
     * so, it is set. Otherwise, the index is set to 0 (i.e. the first file in
     * the playlist).
     *
     * @param config the configuration
     * @param manager the manager to be initialized
     */
    private void initCurrentIndex(HierarchicalConfiguration config,
            XMLPlaylistManager manager)
    {
        manager.setCurrentSongIndex(config.getInt(KEY_CURINDEX, 0));
    }

    /**
     * Creates a playlist with exact order.
     *
     * @param settings the settings
     * @return the playlist
     */
    private List<String> createExactPlaylist(PlaylistSettings settings)
    {
        return new ArrayList<String>(settings.getExactPlaylist());
    }

    /**
     * Creates a playlist with order DIRECTORIES.
     *
     * @param songURIs the collection with song URIs
     * @return the playlist
     */
    private List<String> createDirectoriesPlaylist(Collection<String> songURIs)
    {
        List<String> playlist = new ArrayList<String>(songURIs);
        Collections.sort(playlist);
        return playlist;
    }

    /**
     * Creates a playlist with random order and also takes the keep groups into
     * account.
     *
     * @param songURIs the collection with all song URIs
     * @param settings the playlist settings
     * @return the playlist
     */
    private List<String> createRandomPlaylist(Collection<String> songURIs,
            PlaylistSettings settings)
    {
        List<KeepGroup> keepGroups = settings.getKeepGroups();
        Set<String> groupItems = new HashSet<String>();
        for (KeepGroup group : keepGroups)
        {
            addKeepGroup(groupItems, group);
        }

        List<String> normalSongs = new ArrayList<String>(songURIs);
        normalSongs.removeAll(groupItems);
        for (int idx = 0; idx < keepGroups.size(); idx++)
        {
            normalSongs.add(keepGroupURI(idx));
        }
        Collections.shuffle(normalSongs);

        return keepGroups.isEmpty() ? normalSongs : handleKeepGroups(
                normalSongs, keepGroups, songURIs.size());
    }

    /**
     * Adds the songs in keep groups again to a playlist with random order.
     *
     * @param normalSongs the list with songs not in keep groups
     * @param keepGroups the list with keep groups
     * @param size the total size of the playlist
     * @return the final playlist
     */
    private List<String> handleKeepGroups(List<String> normalSongs,
            List<KeepGroup> keepGroups, int size)
    {
        List<String> playlist = new ArrayList<String>(size);
        for (String uri : normalSongs)
        {
            int groupIdx = keepGroupIndex(uri);
            if (groupIdx >= 0)
            {
                addKeepGroup(playlist, keepGroups.get(groupIdx));
            }
            else
            {
                playlist.add(uri);
            }
        }

        return playlist;
    }

    /**
     * Generates a URI for the keep group with the given index.
     *
     * @param idx the index
     * @return the URI for this keep group
     */
    private static String keepGroupURI(int idx)
    {
        return URI_KEEP_GROUP + idx;
    }

    /**
     * Checks whether the specified URI represents a keep group. If this is the
     * case, the index of this group is extracted and returned. Otherwise,
     * result is -1.
     *
     * @param uri the URI to check
     * @return the index of the corresponding keep group or -1
     */
    private static int keepGroupIndex(String uri)
    {
        if (uri.startsWith(URI_KEEP_GROUP))
        {
            return Integer.parseInt(uri.substring(URI_KEEP_GROUP.length()));
        }
        return -1;
    }

    /**
     * Helper method for adding all URIs contained in the given keep group to
     * the specified collection.
     *
     * @param col the collection
     * @param group the keep group
     */
    private static void addKeepGroup(Collection<String> col, KeepGroup group)
    {
        for (int i = 0; i < group.size(); i++)
        {
            col.add(group.getSongURI(i));
        }
    }
}
