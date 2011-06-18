package de.olix.playa.playlist.impl;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
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

    /** The position of the first song to be played. */
    private final CurrentPositionInfo initialPositionInfo;

    /** The file for storing the state of the playlist. */
    private final File plistFile;

    /** A reference to the factory which created this object. */
    private final XMLPlaylistManagerFactory factory;

    /** The object with information about the playlist. */
    private final PlaylistInfoImpl playlistInfo;

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
        songURIs = Collections.unmodifiableList(new ArrayList<String>(uris));
        initialPositionInfo = initPos;
        this.plistFile = plistFile;
        factory = myFactory;
        playlistInfo = new PlaylistInfoImpl(currentSettings, uris.size());
    }

    /**
     * Creates a new instance of {@code XMLPlaylistManager} that is an exact
     * copy of the passed in playlist manager.
     *
     * @param c the source playlist manager (must not be <b>null</b>)
     * @throws IllegalArgumentException if the passed in playlist manager is
     *         <b>null</b>
     */
    public XMLPlaylistManager(XMLPlaylistManager c)
    {
        if (c == null)
        {
            throw new IllegalArgumentException(
                    "Manager to copy must not be null!");
        }

        factory = c.factory;
        initialPositionInfo = c.initialPositionInfo;
        playlistInfo = c.playlistInfo;
        plistFile = c.plistFile;
        songURIs = c.songURIs;
        currentSongIndex = c.currentSongIndex;
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
        return playlistInfo;
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
     * Returns a copy of this playlist manager. This implementation creates a
     * new instance by invoking the copy constructor.
     *
     * @return a copy of this {@code PlaylistManager}
     */
    @Override
    public PlaylistManager copy()
    {
        return new XMLPlaylistManager(this);
    }

    /**
     * Returns the URIs of the songs in this playlist.
     *
     * @return the song URIs
     */
    public List<String> getSongURIs()
    {
        return songURIs;
    }

    /**
     * A simple implementation of the {@code PlaylistInfo} interface used by
     * {@code getPlaylistInfo()}.
     */
    private static class PlaylistInfoImpl implements PlaylistInfo
    {
        /** The settings for this playlist. */
        private final PlaylistSettings settings;

        /** The number of songs in this playlist. */
        private final int numberOfSongs;

        /**
         * Creates a new instance of {@code PlaylistInfoImpl} and initializes
         * it.
         *
         * @param mySettings the settings of this playlist
         * @param count the number of songs
         */
        public PlaylistInfoImpl(PlaylistSettings mySettings, int count)
        {
            settings = mySettings;
            numberOfSongs = count;
        }

        @Override
        public int getNumberOfSongs()
        {
            return numberOfSongs;
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
    }
}
