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
