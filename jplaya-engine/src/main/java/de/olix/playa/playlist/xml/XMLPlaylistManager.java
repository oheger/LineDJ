package de.olix.playa.playlist.xml;

import java.io.File;
import java.io.FileFilter;
import java.io.IOException;
import java.net.URL;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.StringTokenizer;

import org.apache.commons.configuration.ConfigurationException;
import org.apache.commons.configuration.XMLConfiguration;

import de.olix.playa.engine.AudioReadMonitor;
import de.olix.playa.engine.AudioStreamSource;
import de.olix.playa.playlist.CurrentPositionInfo;
import de.olix.playa.playlist.PlaylistException;
import de.olix.playa.playlist.PlaylistInfo;
import de.olix.playa.playlist.SongInfoCallBack;
import de.olix.playa.playlist.SongInfoProvider;

/**
 * <p>
 * A specialized implementation of the <code>PlaylistManager</code> interface
 * that stores playlists in XML files.
 * </p>
 * <p>
 * This playlist manager implementation is able to scan the content of a
 * specified directory structure, e.g. a CD-ROM drive. From the information
 * gathered from this directory structure it creates a checksum that is used as
 * the playlist's ID. Information about the available songs and the actual
 * playlist are stored in a XML file whose name is derived from the checksum
 * with the extension &quot;.plist&quot; in a data directory. If a second file
 * with the same name, but the extension &quot;.settings&quot; exists, this file
 * is used to determine some properties of the playlist. It can contain the
 * following elements:
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
 * <td>Here a discription for the playlist can be set.</td>
 * </tr>
 * <tr>
 * <td valign="top">order</td>
 * <td>This property defines the order, in which the songs of the playlist are
 * played. It has a sub element named <code>mode</code>, for which the
 * following values are allowed:
 * <dl>
 * <dt>directories</dt>
 * <dd>The songs are played in an order defined by the directory structure.
 * This is usually suitable if the playlist contains different albums that are
 * each stored in their own directory. The contents of a directory is played in
 * alphabetical order.</dd>
 * <dt>random</dt>
 * <dd>A random order is used. In this mode, an arbitrary number of
 * <code>keep</code> elements can be placed below the <code>order</code>
 * element. Each <code>keep</code> element can in turn contain an arbitrary
 * number of <code>file</code> elements with a <code>name</code> attribute
 * storing relative path names to the files in the playlist. The meaning of
 * these elements is that they allow defining files that always should be played
 * in serial (e.g. if a large song is splitted into multiple song files).</dd>
 * <dt>exact</dt>
 * <dd>In this mode a playlist can directly be specified. This is done by
 * placing a <code>list</code> element below the <code>order</code> element.
 * This element can have an arbitrary number of <code>file</code> sub elements
 * with relative path names to the files to be played. Only the files specified
 * here will be played; if the directory tree contains more song files, these
 * files will be ignored.</dd>
 * </td>
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
 * @version $Id$
 */
public class XMLPlaylistManager
{
    /** Constant for the encoding to be used.*/
    static final String ENCODING = "iso-8859-1";

    /** Constant for the name of a settings file. */
	private static final String SETTINGS_FILE = ".settings";

	/** Constant for the name of a playlist file. */
	private static final String PLIST_FILE = ".plist";

	/** Constant for the default order of new playlists. */
	private static final PlaylistSettings.Order DEFAULT_ORDER = PlaylistSettings.Order.directories;

	/** Constant for the default file extensions supported by this manager. */
	private static final String DEFAULT_EXTENSIONS = "mp3";

	/** Constant for the file extension list separator. */
	private static final String EXT_LIST_SEPERATOR = ";";

	/** Constant for the file name key. */
	private static final String KEY_FILENAME = "file[@name]";

	/** Constant for the key for adding a file name. */
	private static final String KEY_ADDFILE = "file(-1)[@name]";

	/** Constant for the current section. */
	private static final String SEC_CURRENT = "current.";

	/** Constant for the current file key. */
	private static final String KEY_CURRENTFILE = SEC_CURRENT + KEY_FILENAME;

	/** Constant for the current position key. */
	private static final String KEY_CURRENTPOS = SEC_CURRENT + "position";

	/** Constant for the current time key.*/
	private static final String KEY_CURRENTTIME = SEC_CURRENT + "time";

	/** Constant for the list file key. */
	private static final String KEY_LIST = "list.";

    /** Constant for the default fetch count for the song info loader.*/
    private static final int DEF_FETCH_COUNT = 5;

	/** Constant for the file extension separator. */
	private static final char FILE_EXT_SEPARATOR = '.';

	/** Stores the path to the data directory. */
	private String dataDirectory;

	/** Stores the path to the music directory. */
	private String musicDirectory;

	/** Stores a list of file extensions for supported audio files. */
	private String fileExtensions;

	/** Stores the audio source used by this manager. */
	private PlaylistStreamSource audioSource;

    /** Stores the song info loader to be used.*/
    private SongInfoLoader loader;

	/** Stores the playlist order for new playlists. */
	private PlaylistSettings.Order newPlaylistOrder;

	/** Stores the current playlist settings. */
	private PlaylistSettings currentSettings;

	/** Stores the current playlist. */
	private Playlist currentPlaylist;

    /** Stores the audio read monitor to be used.*/
    private AudioReadMonitor audioReadMonitor;

    /** Stores the song info provider.*/
    private de.olix.playa.playlist.SongInfoProvider songInfoProvider;

	/** Stores the ID of the currently played song. */
	private Object currentSongID;

	/** Stores the plist file for the current play list. */
	private File plistFile;

	/** A temporary set with the supported file extensions. */
	private transient Set<String> extensionSet;

	/** Stores the number of songs in the current playlist. */
	private int numberOfSongs;

	/** Stores the index of the current song in the playlist. */
	private int currentSongIndex;

	/**
     * Returns the path to the data directory.
     *
     * @return the path to the data directory
     */
	public String getDataDirectory()
	{
		return dataDirectory;
	}

	/**
     * Sets the path to the data directory. In this directory data files for the
     * managed playlists will be stored.
     *
     * @param dataDirectory the data directory path
     */
	public void setDataDirectory(String dataDirectory)
	{
		this.dataDirectory = dataDirectory;
	}

	/**
     * Returns the path to the music directory.
     *
     * @return the path to the music directory
     */
	public String getMusicDirectory()
	{
		return musicDirectory;
	}

	/**
     * Sets the path to the music directory. This directory will be scanned for
     * audio files to be played.
     *
     * @param musicDirectory the path to the music directory
     */
	public void setMusicDirectory(String musicDirectory)
	{
		this.musicDirectory = musicDirectory;
	}

	/**
     * Returns a list of file extensions for supported audio files.
     *
     * @return a list with file extensions
     */
	public String getFileExtensions()
	{
		return (fileExtensions != null) ? fileExtensions : DEFAULT_EXTENSIONS;
	}

	/**
     * Sets the file extensions for supported audio files. This is a string with
     * a list of file extensions separated by &quot;;&quot;, e.g. something like
     * &quot;mp3;wav&quot;.
     *
     * @param fileExtensions a list with file extensions
     */
	public void setFileExtensions(String fileExtensions)
	{
		this.fileExtensions = fileExtensions;
		extensionSet = null;
	}

	/**
     * Returns the order for new playlists.
     *
     * @return the order for new playlists
     */
	public PlaylistSettings.Order getNewPlaylistOrder()
	{
		return (newPlaylistOrder != null) ? newPlaylistOrder : DEFAULT_ORDER;
	}

	/**
     * Sets the order for new playlists. If a new playlist is loaded, for which
     * no settings file exists yet, this order will be used. If the order is
     * undefined, the <em>directories</em> order will be used.
     *
     * @param newPlaylistOrder the default order for new playlists
     */
	public void setNewPlaylistOrder(PlaylistSettings.Order newPlaylistOrder)
	{
		this.newPlaylistOrder = newPlaylistOrder;
	}

    /**
     * Returns a reference to the audio read monitor for synchronizing access to
     * the source medium with the audio engine.
     *
     * @return the audio read monitor
     */
    public AudioReadMonitor getAudioReadMonitor()
    {
        return audioReadMonitor;
    }

    /**
     * Initializes the audio read monitor.
     *
     * @param monitor the monitor object
     */
    public void initAudioReadMonitor(AudioReadMonitor monitor)
    {
        audioReadMonitor = monitor;
    }

    /**
     * Returns the song info provider object for retrieving information about
     * media files.
     *
     * @return the song info provider
     */
    public de.olix.playa.playlist.SongInfoProvider getSongInfoProvider()
    {
        return songInfoProvider;
    }

    /**
     * Initializes the song info provider object.
     *
     * @param provider the song info provider
     */
    public void initSongInfoProvider(SongInfoProvider provider)
    {
        songInfoProvider = provider;
    }

    /**
     * Accepts a request for a song info object.
     *
     * @param streamID the ID of the desired media file
     * @param callBack the call back object (must not be <b>null</b>)
     * @param param a parameter to be passed to the call back
     * @throws IllegalArgumentException if arguments are invalid
     * @throws IllegalStateException if no playlist has been loaded so far
     */
    public void requestSongInfo(Object streamID, SongInfoCallBack callBack,
            Object param)
    {
        if (callBack == null)
        {
            throw new IllegalArgumentException("Call back must not be null!");
        }
        if (!(streamID instanceof File))
        {
            throw new IllegalArgumentException("Invalid stream ID: " + streamID);
        }
        checkLoadState();

        loader.fetchSongInfo((File) streamID, param, callBack);
    }

	/**
     * Returns the audio source for obtaining the streams to be played. This
     * method can only be called after <code>loadState()</code>.
     *
     * @return the audio source
     */
	public AudioStreamSource getSource()
	{
		if (audioSource == null)
		{
			checkLoadState();
			audioSource = new PlaylistStreamSource(currentPlaylist, new File(
					getMusicDirectory()));
		}
		return audioSource;
	}

	/**
     * Loads the current state of this playlist manager. This implementation
     * will scan the directory structure for supported audio files. Then it
     * checks whether for the resulting playlist already a data file exists. If
     * this is the case, this file is loaded and used as playlist. Otherwise a
     * new playlist is initialized.
     *
     * @return the start position of the first song
     * @throws PlaylistException if an error occurs
     */
	public CurrentPositionInfo loadState() throws PlaylistException
	{
		shutdownSongInfoLoader();
        Playlist list = scan(getMusicDirectory(), getSupportedExtensions());
        int totalSongs = list.size();
        currentSettings = findSettings(list);
        plistFile = getPListFile(list);

        audioSource = null;
        if (plistFile.exists())
        {
            currentPlaylist = new Playlist();
            CurrentPositionInfo pos = parsePListFile(plistFile, currentPlaylist);
            if (!currentPlaylist.isEmpty())
            {
                initCurrentPlaylist(totalSongs);
                return pos;
            }
        }

        currentPlaylist = currentSettings.applyOrder(list);
        initCurrentPlaylist(totalSongs);
        return CurrentPositionInfo.UNDEFINED_POSITION;
	}

	/**
     * The playback of the specified song is completed.
     *
     * @param streamID the affected audio stream's ID
     */
	public void playbackEnded(Object streamID)
	{
		if (audioSource.updatePlaylist(getCurrentPlaylist(), streamID, false))
		{
			currentSongID = null;
		}
	}

	/**
     * Playback of another song has just begun.
     *
     * @param streamID the affected audio stream's ID
     */
	public void playbackStarted(Object streamID)
	{
		if (audioSource.updatePlaylist(getCurrentPlaylist(), streamID, true))
		{
			currentSongID = streamID;
			currentSongIndex++;
		}
	}

	/**
     * Saves the state of this manager. This implementation will create a plist
     * file in the data directory with the content of the current playlist.
     *
     * @param position the position in the current song
     * @throws PlaylistException if an error occurs
     */
	public void saveState(CurrentPositionInfo position) throws PlaylistException
	{
		checkLoadState();

        assert plistFile != null : "No playlist file available!";
        XMLConfiguration config = new XMLConfiguration();
        config.setListDelimiter((char) 0);
        config.setEncoding(ENCODING);
        try
        {
            if (currentSongID != null)
            {
                if (position != null)
                {
                    config.addProperty(KEY_CURRENTPOS, position.getPosition());
                    config.addProperty(KEY_CURRENTTIME, position.getTime());
                }
                config.addProperty(KEY_CURRENTFILE, currentPlaylist.take()
                        .getPathName());
            }
            for (Iterator<PlaylistItem> it = currentPlaylist.items(); it
                    .hasNext();)
            {
                config.addProperty(KEY_LIST + KEY_ADDFILE, it.next()
                        .getPathName());
            }
            config.save(plistFile);
        }
        catch (ConfigurationException cex)
        {
            throw new PlaylistException("Could not save playlist data to "
                    + plistFile, cex);
        }
	}

	/**
     * Returns an object with information about the current playlist. Note:
     * Before this method can be called, <code>loadState()</code> must have
     * been invoked.
     *
     * @return an info object for the current playlist
     */
	public PlaylistInfo getPlaylistInfo()
	{
		checkLoadState();
		return new XMLPlaylistInfo();
	}

	/**
     * Returns a <code>PlaylistSettings</code> object for the current
     * playlist. This method can be called after <code>loadState()</code>.
     *
     * @return a settings object
     */
	public PlaylistSettings getCurrentSettings()
	{
		checkLoadState();
		return currentSettings;
	}

	/**
     * Returns the current playlist. This method is available after the state
     * was loaded using <code>loadState()</code>.
     *
     * @return the current playlist
     * @see #loadState()
     */
	public Playlist getCurrentPlaylist()
	{
		checkLoadState();
		return currentPlaylist;
	}

	/**
     * Returns the ID of the currently played song. The return value will be
     * <b>null</b> if there is no such song.
     *
     * @return the ID of the currently played song
     */
	public Object getCurrentSongID()
	{
		return currentSongID;
	}

	/**
     * Terminates this playlist manager gracefully.
     */
	public void shutdown()
	{
		shutdownSongInfoLoader();
	}

    /**
     * Returns the stream ID for the specified playlist item. Under the hood the
     * file represented by this item is used as ID.
     *
     * @param item the playlist item
     * @param mediaDir the root directory with the media files
     * @return the stream ID for this item
     */
    static Object fetchStreamID(PlaylistItem item, File mediaDir)
    {
        return item.getFile(mediaDir);
    }

    /**
     * Transforms the specified stream ID into a file.
     *
     * @param streamID the stream ID
     * @return the corresponding media file
     * @throws PlaylistException if the stream ID is invalid
     */
    static File streamIDToFile(Object streamID) throws PlaylistException
    {
        if (!(streamID instanceof File))
        {
            throw new PlaylistException("Invalid stream ID: " + streamID);
        }
        return (File) streamID;
    }

    /**
     * Obtains a URL from the given playlist item when the passed in directory
     * is used as root directory for the media files.
     *
     * @param item the playlist item
     * @param mediaDir the root directory with the media files
     * @return the URL for this playlist item
     * @throws PlaylistException if conversion fails
     */
    static URL fetchMediaURL(PlaylistItem item, File mediaDir)
            throws PlaylistException
    {
        if (item == null || mediaDir == null)
        {
            throw new PlaylistException(
                    "Playlist item and root directory must not be null!");
        }

        return fetchURLforFile(item.getFile(mediaDir));
    }

    /**
     * Converts the specified file into a URL.
     *
     * @param file the file
     * @return a URL for this file
     * @throws PlaylistException if conversion fails
     */
    static URL fetchURLforFile(File file) throws PlaylistException
    {
        if (file == null)
        {
            throw new PlaylistException("Media file must not be null!");
        }
        try
        {
            return file.toURI().toURL();
        }
        catch (IOException ioex)
        {
            throw new PlaylistException("Invalid media file: " + file);
        }
    }

	/**
     * Scans the specified directory for supported audio files. The found files
     * will be added to a <code>{@link Playlist}</code> object and returned.
     *
     * @param dir the directory to scan
     * @param extensions a set with the supported file extensions
     * @return a playlist object with the found files
     */
	protected Playlist scan(String dir, final Set<String> extensions)
	{
		Playlist result = new Playlist();
		FileFilter filter = new FileFilter()
		{
			/**
             * A specialized filter that accept sub directories and files whose
             * extension is contained in the passed in set.
             */
			public boolean accept(File pathname)
			{
				if (pathname.isDirectory())
				{
					return true;
				}
				int pos = pathname.getName().lastIndexOf(FILE_EXT_SEPARATOR);
				return pos >= 0
						&& extensions.contains(pathname.getName().substring(
								pos + 1).toLowerCase());
			}
		};

		scanRec(dir, new File(dir), filter, result);
		return result;
	}

	/**
     * Recursively scans a directory structure for supported audio files.
     *
     * @param rootDir the root dir of the file structure
     * @param dir the current directory to scan
     * @param filter the filter to be used
     * @param list the list, to which the found files are to be added
     */
	private void scanRec(String rootDir, File dir, FileFilter filter,
			Playlist list)
	{
		File[] content = dir.listFiles(filter);
		if (content != null)
		{
			for (File f : content)
			{
				if (f.isDirectory())
				{
					scanRec(rootDir, f, filter, list);
				}
				else
				{
					list.addItem(new PlaylistItem(rootDir, f));
				}
			}
		}
	}

	/**
     * Returns a set with the supported file extensions.
     *
     * @return a set with the supported file extensions
     */
	protected Set<String> getSupportedExtensions()
	{
		if (extensionSet == null)
		{
			extensionSet = new HashSet<String>();
			StringTokenizer tok = new StringTokenizer(getFileExtensions(),
					EXT_LIST_SEPERATOR);
			while (tok.hasMoreTokens())
			{
				extensionSet.add(tok.nextToken().toLowerCase());
			}
		}
		return extensionSet;
	}

	/**
     * Returns a settings object for the given playlist. This implementation
     * will perform the following steps for obtaining a settings file for the
     * specified playlist:
     * <ol>
     * <li>The ID of the sorted playlist will be determined.</li>
     * <li>If the data directory contains a file with the same name than this
     * ID and the extension <code>.settings</code>, this file will be loaded.</li>
     * <li>If the music directory contains a file named <code>.settings</code>,
     * this file will be loaded.</li>
     * <li>Otherwise a new <code>PlaylistSettings</code> object will be
     * created and initialized with some default properties.</li>
     * </ol>
     *
     * @param list the affected playlist
     * @return a settings object for this playlist
     * @throws PlaylistException if an error occurs
     */
	protected PlaylistSettings findSettings(Playlist list)
			throws PlaylistException
	{
		list.sort();
		File dataFile = new File(new File(getDataDirectory()), list.getID()
				+ SETTINGS_FILE);
		File settingsFile = dataFile;
		if (!settingsFile.exists())
		{
			settingsFile = new File(new File(getMusicDirectory()),
					SETTINGS_FILE);
		}

		PlaylistSettings settings = new PlaylistSettings();
		settings.setSettingsFile(settingsFile);
		if (settingsFile.exists())
		{
			settings.load();
		}
		else
		{
			settings.setOrder(getNewPlaylistOrder());
			settings.setSettingsFile(dataFile);
		}
		return settings;
	}

	/**
     * Parses a playlist data file. The task of this method is to extract the
     * data of a playlist file and to setup a corresponding playlist.
     *
     * @param file the data file
     * @param list the resulting playlist
     * @return the start position of the first file
     * @throws PlaylistException if an error occurs
     */
	protected CurrentPositionInfo parsePListFile(File file, Playlist list)
			throws PlaylistException
	{
		XMLConfiguration config = new XMLConfiguration();
        try
        {
            config.load(file);
            if (config.containsKey(KEY_CURRENTFILE))
            {
                PlaylistItem currentItem = new PlaylistItem(config
                        .getString(KEY_CURRENTFILE));
                list.addItem(currentItem);
                currentSongID = currentItem.getFile(new File(
                        getMusicDirectory()));
            }
            else
            {
                currentSongID = null;
            }

            List files = config.getList(KEY_LIST + KEY_FILENAME);
            for (Iterator it = files.iterator(); it.hasNext();)
            {
                list.addItem(new PlaylistItem((String) it.next()));
            }
            return new CurrentPositionInfo(config.getLong(KEY_CURRENTPOS, 0L),
                    config.getLong(KEY_CURRENTTIME, 0L));
        }
        catch (ConfigurationException cex)
        {
            throw new PlaylistException("Could not read plist file " + file,
                    cex);
        }
	}

    /**
     * Creates the object for loading song information.
     *
     * @param plist the current playlist
     * @return the <code>SongInfoLoader</code> to be used
     */
    SongInfoLoader createInfoLoader(Playlist plist)
    {
        return new SongInfoLoader(getSongInfoProvider(), getAudioReadMonitor(),
                new Playlist(plist), new File(getMusicDirectory()),
                DEF_FETCH_COUNT);
    }

	/**
     * Creates a file object with the name of the data file for the specified
     * playlist.
     *
     * @param list the playlist
     * @return the data file name for this playlist
     */
	private File getPListFile(Playlist list)
	{
		return new File(new File(getDataDirectory()), list.getID() + PLIST_FILE);
	}

	/**
     * Checks the state of this instance. This method can be called by methods
     * that require that loadState() has been called before. If this is not the
     * case, this method will throw an exception.
     */
	private void checkLoadState()
	{
		if (currentPlaylist == null)
		{
			throw new IllegalStateException("loadState() must be called first!");
		}
	}

	/**
     * Terminates the song info provider gracefully if it exists.
     */
	private void shutdownSongInfoLoader()
	{
		if (loader != null)
		{
			loader.shutdown();
		}
	}

	/**
     * Initializes some data fields related to the current playlist. This method
     * is called from the loadState() method.
     */
	private void initCurrentPlaylist(int totalSongs)
	{
		loader = createInfoLoader(currentPlaylist);
		numberOfSongs = totalSongs;
		currentSongIndex = totalSongs - currentPlaylist.size();
	}

	/**
     * An internally used implementation of the <code>PlaylistInfo</code>
     * interface.
     */
	class XMLPlaylistInfo implements PlaylistInfo
	{
		public int getCurrentSongIndex()
		{
			return currentSongIndex;
		}

		public String getDescription()
		{
			return getCurrentSettings().getDescription();
		}

		public String getName()
		{
			return getCurrentSettings().getName();
		}

		public int getNumberOfSongs()
		{
			return numberOfSongs;
		}
	}
}
