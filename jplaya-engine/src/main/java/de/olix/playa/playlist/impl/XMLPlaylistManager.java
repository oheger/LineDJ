package de.olix.playa.playlist.impl;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

import de.olix.playa.playlist.CurrentPositionInfo;
import de.olix.playa.playlist.PlaylistInfo;
import de.olix.playa.playlist.PlaylistManager;
import de.olix.playa.playlist.PlaylistSettings;

/**
 * <p>
 * A specialized implementation of the {@code PlaylistManager} interface that
 * stores playlist data in XML files in a configurable directory.
 * </p>
 * <p>
 * This playlist manager implementation is able to scan the content of a
 * specified directory structure, e.g. a CD-ROM drive. From the information
 * gathered from this directory structure it creates a checksum that is used as
 * the playlist's ID. Information about the available songs and the current
 * playlist is stored in a XML file whose name is derived from the checksum with
 * the extension &quot;.plist&quot; in a data directory. If a second file with
 * the same name, but the extension &quot;.settings&quot; exists, this file is
 * used to determine some properties of the playlist. It can contain the
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
 * <p>
 * Implementation notes: This class is thread-safe. It is expected that multiple
 * threads change and access the state of an instance. Creation of new instances
 * happens internally only by the factory. Therefore no parameter checking is
 * performed.
 * </p>
 *
 * @author Oliver Heger
 * @version $Id$
 */
class XMLPlaylistManager implements PlaylistManager
{
    /** The collection with the URIs of the files in the current playlist. */
    private final List<String> songURIs;

    /** The settings of the current playlist. */
    private final PlaylistSettings settings;

    /** The position of the first song to be played. */
    private final CurrentPositionInfo initialPositionInfo;

    /** The file for storing the state of the playlist. */
    private final File plistFile;

    /** A reference to the factory which created this object. */
    private final XMLPlaylistManagerFactory factory;

    /** The index of the current song. */
    private int currentSongIndex;

    /** A flag whether the playlist has been finished. */
    private boolean finished;

    /**
     * Creates a new instance of {@code XMLPlaylistManager} and initializes it.
     *
     * @param uris the collection with the URIs of the files to be played
     * @param currentSettings the settings object describing the current
     *        playlist
     * @param initPos the position of the first song to be played
     * @param plistFile the file in which to store the current state of this
     *        manager
     * @param myFactory a reference to the factory which created this object
     */
    public XMLPlaylistManager(Collection<String> uris,
            PlaylistSettings currentSettings, CurrentPositionInfo initPos,
            File plistFile, XMLPlaylistManagerFactory myFactory)
    {
        songURIs = new ArrayList<String>(uris);
        settings = currentSettings;
        initialPositionInfo = initPos;
        this.plistFile = plistFile;
        factory = myFactory;
    }

    /**
     * Returns the file in which the state of this playlist manager is stored.
     *
     * @return the file for saving the state
     */
    public File getPListFile()
    {
        return plistFile;
    }

    /**
     * Saves the state of this manager. This implementation delegates to the
     * factory for this purpose.
     *
     * @param position the position in the current song
     * @throws IOException if an IO error occurs
     */
    @Override
    public void saveState(CurrentPositionInfo position) throws IOException
    {
        int index;
        synchronized (this)
        {
            index = isFinished() ? -1 : getCurrentSongIndex();
        }
        factory.saveState(this, position, index);
    }

    /**
     * Returns an information object about the current playlist.
     *
     * @return a {@code PlaylistInfo} object describing the current playlist
     */
    @Override
    public PlaylistInfo getPlaylistInfo()
    {
        return new PlaylistInfo()
        {
            @Override
            public int getNumberOfSongs()
            {
                return getSongURIs().size();
            }

            @Override
            public String getName()
            {
                return settings.getName();
            }

            @Override
            public String getDescription()
            {
                return settings.getDescription();
            }
        };
    }

    /**
     * Moves backwards to the previous song if possible.
     *
     * @return a flag whether there is a previous song
     */
    @Override
    public synchronized boolean previousSong()
    {
        if (getCurrentSongIndex() > 0)
        {
            currentSongIndex--;
            finished = false;
            return true;
        }
        else
        {
            return false;
        }
    }

    /**
     * Moves forward to the next song if possible.
     *
     * @return a flag whether there is a next song
     */
    @Override
    public synchronized boolean nextSong()
    {
        if (getCurrentSongIndex() < getSongURIs().size() - 1)
        {
            currentSongIndex++;
            return true;
        }
        else
        {
            finished = true;
            return false;
        }
    }

    /**
     * Returns the index of the song which is currently played.
     *
     * @return the index of the current song
     */
    @Override
    public synchronized int getCurrentSongIndex()
    {
        return currentSongIndex;
    }

    /**
     * Moves to a specific song.
     *
     * @param idx the index of the desired song
     * @throws IndexOutOfBoundsException if the index is invalid
     */
    @Override
    public void setCurrentSongIndex(int idx)
    {
        if (idx < 0 || idx >= getSongURIs().size())
        {
            throw new IndexOutOfBoundsException("Invalid current song index: "
                    + idx);
        }

        synchronized (this)
        {
            currentSongIndex = idx;
            finished = false;
        }
    }

    /**
     * Returns the URI of the current song. The current song is determined by
     * the current index.
     *
     * @return the URI of the current song
     */
    @Override
    public String getCurrentSongURI()
    {
        return getSongURIs().get(getCurrentSongIndex());
    }

    /**
     * Returns a {@code CurrentPositionInfo} object defining the position of the
     * first song to be played. This information is needed if playback was
     * interrupted in the middle of a song.
     *
     * @return the position of the first song to be played
     */
    @Override
    public CurrentPositionInfo getInitialPositionInfo()
    {
        return initialPositionInfo;
    }

    /**
     * Returns a flag whether the playlist has been finished.
     *
     * @return a flag whether the playlist has been finished
     */
    @Override
    public synchronized boolean isFinished()
    {
        return finished;
    }

    /**
     * Returns the URIs of the songs in this playlist.
     *
     * @return the song URIs
     */
    List<String> getSongURIs()
    {
        return songURIs;
    }
}
