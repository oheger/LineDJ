package de.olix.playa.playlist;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import org.apache.commons.lang3.ObjectUtils;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.commons.vfs.FileContent;
import org.apache.commons.vfs.FileObject;
import org.apache.commons.vfs.FileSystemManager;

import de.olix.playa.engine.AudioPlayerEvent;
import de.olix.playa.engine.AudioPlayerListener;
import de.olix.playa.engine.AudioStreamData;
import de.olix.playa.engine.AudioStreamSource;
import de.olix.playa.engine.EndAudioStreamData;

/**
 * <p>
 * A helper class for connecting a {@link PlaylistManager} with the audio player
 * engine.
 * </p>
 * <p>
 * This class implements some functionality which is typically needed when using
 * the audio player engine to play the songs defined by a playlist. It
 * implements the {@link AudioStreamSource} interface in a way that the songs in
 * the order defined by a {@code PlaylistManager} are returned. In addition it
 * keeps track on the songs actually played. It does so by storing a
 * {@code PlaylistManager} which is obtained from the
 * {@link PlaylistManagerFactory} passed to the constructor. This
 * {@code PlaylistManager} can be queried and even manipulated by other
 * components which are interested in the current playlist. (Actually two
 * playlist managers have to be maintained: one for the audio source, and one
 * for the songs currently played. The first one is typically some songs before
 * the other one because songs are buffered on the local hard disc before they
 * are played. The {@code PlaylistManager} exposed by the
 * {@link #getPlaylistManager()} method is a wrapper which ensures that the
 * playlist managers used internally are in sync with status changes performed
 * by external components.)
 * </p>
 * <p>
 * The class also implements an auto-safe feature. After a configurable number
 * of songs has been played, the state of the playlist manager is saved
 * automatically. For this to work the class implements the
 * {@link AudioPlayerListener} interface so that it can be notified when a songs
 * has been played. Client code is responsible for registering an instance as
 * listener at an audio player.
 * </p>
 * <p>
 * Another feature is a smart handling of backwards commands: When the current
 * playlist manager is told to move to the previous song the current position in
 * the currently played song is checked. If it is before a configurable time
 * span, the manager actually moves to the previous song. Otherwise, the current
 * song is played again from start.
 * </p>
 * <p>
 * Implementation note: This class can be used in an environment with multiple
 * threads. (Typically events from the audio player arrive in different threads.
 * The class can handle this.)
 * </p>
 *
 * @author Oliver Heger
 * @version $Id: $
 */
public class PlaylistController implements AudioStreamSource,
        AudioPlayerListener
{
    /** The logger. */
    private final Log log = LogFactory.getLog(getClass());

    /** Stores the file system manager. */
    private final FileSystemManager fileSystemManager;

    /** The playlist manager factory. */
    private final PlaylistManagerFactory pmFactory;

    /** Stores the current position in the current audio stream. */
    private final AtomicLong currentPosition;

    /** Stores the current time in the current audio stream. */
    private final AtomicLong currentTime;

    /** A counter for the auto save feature. */
    private final AtomicInteger autoSaveCounter;

    /** The limit for skip backwards operations. */
    private final long skipBackwardsLimit;

    /** The interval for auto-save operations. */
    private final int autoSaveInterval;

    /** The wrapper playlist manager. */
    private volatile PlaylistManagerWrapper wrapper;

    /**
     * Creates a new instance of {@code PlaylistController} and initializes it.
     *
     * @param fsm the {@code FileSystemManager} (must not be <b>null</b>)
     * @param factory the {@code PlaylistManagerFactory} (must not be
     *        <b>null</b>)
     * @param autoSaveCount the number of songs after which an auto safe
     *        operation is triggered; values &lt;= 0 mean that this feature is
     *        disabled
     * @param skipBackLimit a time limit (in milliseconds) which controls the
     *        behavior of moving backwards to the previous song; if the current
     *        song has already been played longer than the specified time, it is
     *        started again rather than actually moving one song backwards in
     *        the playlist
     * @throws IllegalArgumentException if a required parameter is <b>null</b>
     */
    public PlaylistController(FileSystemManager fsm,
            PlaylistManagerFactory factory, int autoSaveCount,
            long skipBackLimit)
    {
        if (fsm == null)
        {
            throw new IllegalArgumentException(
                    "FileSystemManager must not be null!");
        }
        if (factory == null)
        {
            throw new IllegalArgumentException(
                    "PlaylistManagerFactory must not be null!");
        }

        fileSystemManager = fsm;
        pmFactory = factory;
        autoSaveInterval = autoSaveCount;
        skipBackwardsLimit = skipBackLimit;
        currentPosition = new AtomicLong();
        currentTime = new AtomicLong();
        autoSaveCounter = new AtomicInteger();
    }

    /**
     * Returns the auto-save interval. Whenever this number of songs has been
     * played the {@code PlaylistManager} is asked to save its state. Values
     * less or equal 0 mean that this feature is disabled.
     *
     * @return the auto-save interval
     */
    public int getAutoSaveInterval()
    {
        return autoSaveInterval;
    }

    /**
     * Returns the limit of skip backwards operations (in milliseconds). This
     * time is taken account when the playlist manager is to be moved to the
     * previous song. This could mean that either the current song is to be
     * started again or that the previous song is actually to be played again.
     * The decision is made based on this time span: If the current song has
     * been played that long (or longer), it is started again
     *
     * @return the limit of skip backwards operations in milliseconds
     */
    public long getSkipBackwardsLimit()
    {
        return skipBackwardsLimit;
    }

    /**
     * Initializes the current playlist. This method must be called before an
     * instance can be actually used. It obtains a new {@code PlaylistManager}
     * from the current {@code PlaylistManagerFactory}. This object determines
     * the order in which songs are played.
     *
     * @param defOrder the default order for a new playlist
     * @throws IOException if an IO error occurs
     */
    public void initializePlaylist(PlaylistOrder defOrder) throws IOException
    {
        PlaylistManager manager = pmFactory.createPlaylistManager(defOrder);
        wrapper = new PlaylistManagerWrapper(manager, manager.copy());
        autoSaveCounter.set(0);
    }

    /**
     * Saves the current state of the playlist. This is more or less a
     * convenience method. It asks the current playlist manager to save its
     * state and passes in the current position (which is also managed by this
     * controller).
     *
     * @throws IOException if an IO error occurs
     * @throws IllegalStateException if the playlist has not been initialized
     */
    public void saveState() throws IOException
    {
        getPlaylistManager().saveState(getCurrentPosition());
    }

    /**
     * Returns an object with the current position in the currently played song.
     * If there is no current position (maybe no song has been played so far),
     * the properties of the object returned by this method are all 0.
     *
     * @return an object reporting the current position in the currently played
     *         song
     */
    public CurrentPositionInfo getCurrentPosition()
    {
        return new CurrentPositionInfo(currentPosition.get(), currentTime.get());
    }

    /**
     * Dummy implementation of this interface method.
     *
     * @param event the event
     */
    @Override
    public void streamStarts(AudioPlayerEvent event)
    {
    }

    /**
     * Notifies this object that an audio stream has been played completely.
     * This implementation switches the internal playlist manager to the next
     * song so it keeps track on the songs currently played. If auto-save mode
     * is enabled and the interval is reached, the playlist manager is asked to
     * save its state.
     *
     * @param event the event
     * @throws IllegalStateException if the playlist has not been initialized
     */
    @Override
    public void streamEnds(AudioPlayerEvent event)
    {
        if (isEventForCurrentSong(event))
        {
            fetchPlaylistManager().getCurrentPlaylistManager().nextSong();
            handleAutoSave(getAutoSaveCounter());
        }
    }

    /**
     * The position in the audio stream has changed. This method keeps track of
     * the position.
     *
     * @param event the event
     */
    @Override
    public void positionChanged(AudioPlayerEvent event)
    {
        if (isEventForCurrentSong(event))
        {
            updateCurrentPosition(event.getPosition(), event.getPlaybackTime());
        }
    }

    /**
     * Dummy implementation of this interface method.
     *
     * @param event the event
     */
    @Override
    public void playListEnds(AudioPlayerEvent event)
    {
    }

    /**
     * Dummy implementation of this interface method.
     *
     * @param event the event
     */
    @Override
    public void error(AudioPlayerEvent event)
    {
    }

    /**
     * Dummy implementation of this interface method.
     *
     * @param event the event
     */
    @Override
    public void fatalError(AudioPlayerEvent event)
    {
    }

    /**
     * Returns a data object describing the next audio stream. This
     * implementation returns audio streams in the order determined by the
     * internal playlist manager. If the end of the playlist is reached, a
     * special end token is returned.
     *
     * @return a data object describing the next audio stream
     * @throws InterruptedException not thrown by this implementation
     * @throws IOException if an IO error occurs
     * @throws IllegalStateException if the playlist has not been initialized
     */
    @Override
    public AudioStreamData nextAudioStream() throws InterruptedException,
            IOException
    {
        PlaylistManager manager =
                fetchPlaylistManager().getPlaylistManagerSource();
        if (manager.isFinished())
        {
            return EndAudioStreamData.INSTANCE;
        }

        AudioStreamDataImpl data =
                AudioStreamDataImpl.newInstance(getFileSystemManager(),
                        manager.getCurrentSongURI());
        manager.nextSong();
        return data;
    }

    /**
     * Returns the current {@code PlaylistManager}. This is the manager which
     * controls the currently played song. Actually this method returns a
     * wrapper around a playlist manager. So client code can perform updates on
     * the playlist, and the playlist managers used internally are automatically
     * kept in sync.
     *
     * @return the current {@code PlaylistManager}
     * @throws IllegalStateException if the playlist has not been initialized
     */
    public PlaylistManager getPlaylistManager()
    {
        return fetchPlaylistManager();
    }

    /**
     * Returns the {@code FileSystemManager} used by this object. This manager
     * is used to resolve URIs for audio files and to create streams for them.
     *
     * @return the {@code FileSystemManager}
     */
    protected final FileSystemManager getFileSystemManager()
    {
        return fileSystemManager;
    }

    /**
     * Updates the position in the currently played audio stream. This method is
     * typically invoked when an event is received about a change in the
     * position of the currently played stream.
     *
     * @param pos the new position in the audio stream
     * @param time the new time
     */
    protected void updateCurrentPosition(long pos, long time)
    {
        currentPosition.set(pos);
        currentTime.set(time);
    }

    /**
     * Takes care of the auto save mechanism. This method is called after a song
     * was played. It increments the counter and checks whether the auto save
     * interval is reached. If this is the case, the playlist manager is asked
     * to save its state. Note: An exception which occurs during the auto save
     * operation is just logged and ignored. This is due to the fact that auto
     * save is not an essential feature; if it fails the application should not
     * crash.
     *
     * @param counter the counter for auto save
     */
    protected void handleAutoSave(AtomicInteger counter)
    {
        if (counter.incrementAndGet() % getAutoSaveInterval() == 0)
        {
            try
            {
                getPlaylistManager().saveState(null);
                counter.addAndGet(-getAutoSaveInterval());
            }
            catch (IOException ioex)
            {
                counter.decrementAndGet();
                log.warn("Error when auto-saving playlist!", ioex);
            }
        }
    }

    /**
     * Returns the counter used for the auto-save implementation. Note: This
     * method is not private for testing purposes. It should not be used by
     * external code.
     *
     * @return the counter for the auto-save implementation
     */
    AtomicInteger getAutoSaveCounter()
    {
        return autoSaveCounter;
    }

    /**
     * Returns the wrapper for the internal playlist managers. If the playlist
     * has not yet been initialized, an exception is thrown.
     *
     * @return the playlist manager wrapper
     * @throws IllegalStateException if the playlist has not been initialized
     */
    private PlaylistManagerWrapper fetchPlaylistManager()
    {
        PlaylistManagerWrapper result = wrapper;
        if (result == null)
        {
            throw new IllegalStateException("No playlist manager available! "
                    + "Call initializePlaylist() first!");
        }
        return result;
    }

    /**
     * Helper method for checking the stream the event is about against the
     * currently played song.
     *
     * @param event the event
     * @return a flag whether this event is related to the current song
     */
    private boolean isEventForCurrentSong(AudioPlayerEvent event)
    {
        return ObjectUtils.equals(fetchPlaylistManager()
                .getCurrentPlaylistManager().getCurrentSongURI(), event
                .getStreamData().getID());
    }

    /**
     * A wrapper class for the playlist managers used internally. An instance of
     * this class is returned by the {@code getPlaylistManager()} method. It
     * ensures that updates on the playlist are propagated to the playlist
     * managers used internally.
     */
    private class PlaylistManagerWrapper implements PlaylistManager
    {
        /** The playlist manager for the song currently played. */
        private final PlaylistManager pmPlayed;

        /** The playlist manager for the audio stream source implementation. */
        private final PlaylistManager pmSource;

        /**
         * Creates a new instance of {@code PlaylistManagerWrapper} and
         * initializes it with the managers to wrap.
         *
         * @param played the playlist manager for the currently played song
         * @param source the playlist manager for the source
         */
        public PlaylistManagerWrapper(PlaylistManager played,
                PlaylistManager source)
        {
            pmPlayed = played;
            pmSource = source;
        }

        /**
         * Returns the playlist manager for the currently played song.
         *
         * @return the playlist manager for the currently played song
         */
        public PlaylistManager getCurrentPlaylistManager()
        {
            return pmPlayed;
        }

        /**
         * Returns the playlist manager for the audio stream source
         * implementation.
         *
         * @return the audio source playlist manager
         */
        public PlaylistManager getPlaylistManagerSource()
        {
            return pmSource;
        }

        /**
         * {@inheritDoc} This implementation delegates to the current playlist
         * manager.
         */
        @Override
        public CurrentPositionInfo getInitialPositionInfo()
        {
            return getCurrentPlaylistManager().getInitialPositionInfo();
        }

        /**
         * {@inheritDoc} This implementation delegates to the current playlist
         * manager.
         */
        @Override
        public void saveState(CurrentPositionInfo position) throws IOException
        {
            getCurrentPlaylistManager().saveState(position);
        }

        /**
         * {@inheritDoc} This implementation delegates to the current playlist
         * manager.
         */
        @Override
        public PlaylistInfo getPlaylistInfo()
        {
            return getCurrentPlaylistManager().getPlaylistInfo();
        }

        /**
         * {@inheritDoc} This implementation takes the skip backwards limit into
         * account to decide whether the playlist should move to the previous
         * song or the current song should be played again.
         */
        @Override
        public boolean previousSong()
        {
            boolean result;
            if (currentTime.get() < getSkipBackwardsLimit())
            {
                result = getCurrentPlaylistManager().previousSong();
            }
            else
            {
                result = true;
            }

            syncSourcePlaylistManager();
            return result;
        }

        /**
         * {@inheritDoc} This implementation delegates to the current playlist
         * manager. Then the source playlist manager is adapted to the same
         * index.
         */
        @Override
        public boolean nextSong()
        {
            boolean result = getCurrentPlaylistManager().nextSong();
            syncSourcePlaylistManager();
            return result;
        }

        /**
         * {@inheritDoc} This implementation delegates to the current playlist
         * manager.
         */
        @Override
        public int getCurrentSongIndex()
        {
            return getCurrentPlaylistManager().getCurrentSongIndex();
        }

        /**
         * {@inheritDoc} This implementation delegates to the current playlist
         * manager. It also sets the index for the source playlist manager so
         * that it returns the correct song when the next audio stream is
         * requested.
         */
        @Override
        public void setCurrentSongIndex(int idx)
        {
            getCurrentPlaylistManager().setCurrentSongIndex(idx);
            getPlaylistManagerSource().setCurrentSongIndex(idx);
        }

        /**
         * {@inheritDoc} This implementation delegates to the current playlist
         * manager.
         */
        @Override
        public String getCurrentSongURI()
        {
            return getCurrentPlaylistManager().getCurrentSongURI();
        }

        /**
         * {@inheritDoc} This implementation delegates to the current playlist
         * manager.
         */
        @Override
        public boolean isFinished()
        {
            return getCurrentPlaylistManager().isFinished();
        }

        /**
         * {@inheritDoc} This implementation delegates to the current playlist
         * manager.
         */
        @Override
        public List<String> getSongURIs()
        {
            return getCurrentPlaylistManager().getSongURIs();
        }

        /**
         * {@inheritDoc} This implementation delegates to the current playlist
         * manager.
         */
        @Override
        public PlaylistManager copy()
        {
            return getCurrentPlaylistManager().copy();
        }

        /**
         * Helper method for setting the current index of the playlist manager
         * for the source to the same value as the current playlist manager.
         */
        private void syncSourcePlaylistManager()
        {
            getPlaylistManagerSource().setCurrentSongIndex(
                    getCurrentPlaylistManager().getCurrentSongIndex());
        }
    }

    /**
     * An internally used implementation of {@code AudioStreamData}. Objects of
     * this class are returned by {@code nextAudioStream}. The class uses the
     * VFS file system manager to resolve URIs to audio files.
     */
    private static class AudioStreamDataImpl implements AudioStreamData
    {
        /** The URI of the wrapped audio file. */
        private final String uri;

        /** The file content object. */
        private final FileContent content;

        /** The file size. */
        private final long size;

        /**
         * Creates a new instance of {@code AudioStreamDataImpl} and initializes
         * it with the audio file to wrap.
         *
         * @param audioUri the URI of the underlying audio file
         * @param fileContent the content object of the file
         * @param streamSize the size of the stream
         */
        private AudioStreamDataImpl(String audioUri, FileContent fileContent,
                long streamSize)
        {
            uri = audioUri;
            content = fileContent;
            size = streamSize;
        }

        /**
         * Creates a new instance that wraps the audio stream with the given
         * URI.
         *
         * @param fsm the file system manager
         * @param uri the URI of the audio file to wrap
         * @return the newly created instance
         * @throws IOException if an IO error occurs
         */
        public static AudioStreamDataImpl newInstance(FileSystemManager fsm,
                String uri) throws IOException
        {
            FileObject file = fsm.resolveFile(uri);
            FileContent content = file.getContent();
            return new AudioStreamDataImpl(uri, content, content.getSize());
        }

        /**
         * Returns the name of this audio stream. This is the URI.
         *
         * @return the name of this stream
         */
        @Override
        public String getName()
        {
            return uri;
        }

        /**
         * Returns the ID of this audio stream. This implementation returns the
         * URI.
         *
         * @return the ID of this stream
         */
        @Override
        public Object getID()
        {
            return uri;
        }

        /**
         * Returns the underlying stream. This implementation obtains the stream
         * from the content object.
         *
         * @return the audio stream
         * @throws IOException if an IO error occurs
         */
        @Override
        public InputStream getStream() throws IOException
        {
            return content.getInputStream();
        }

        /**
         * Returns the size of the audio stream.
         *
         * @return the size of the stream
         */
        @Override
        public long size()
        {
            return size;
        }

        /**
         * Returns the position. This implementation does not support a
         * position, so always 0 is returned.
         *
         * @return the position
         */
        @Override
        public long getPosition()
        {
            return 0;
        }
    }
}
