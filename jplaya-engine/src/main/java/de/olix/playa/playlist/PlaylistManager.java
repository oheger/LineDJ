package de.olix.playa.playlist;

import de.olix.playa.engine.AudioReadMonitor;
import de.olix.playa.engine.AudioStreamSource;

/**
 * <p>
 * Definition of an interface for objects that can deal with playlists.
 * </p>
 * <p>
 * The audio player main class does not itself decide, which songs to play in
 * which order. This task is delegated to a <code>PlaylistManager</code>. At
 * startup a concrete implementation of this interface is created from the main
 * application's configuration file. Then through the methods defined by this
 * interface a <code>{@link de.olix.playa.engine.AudioStreamSource}</code>
 * object is obtained that represents the current playlist.
 * </p>
 * <p>
 * Additional methods in this interface allow a concrete playlist manager to
 * maintain its internal state. At startup or when the user wants to change the
 * source media the manager's <code>loadState()</code> method is called. The
 * counter part of this method is <code>saveState()</code>, which is invoked
 * at the end of the application.
 * </p>
 * <p>
 * During playback the manager is informed about the currently played songs. A
 * sophisticated implementation may use this information to decide, which songs
 * should be played next (e.g. based on playback frequency or on a self learning
 * heuristic taking the user's taste into account). It is also possible to query
 * more detailed information about a certain song.
 * </p>
 *
 * @author Oliver Heger
 * @version $Id$
 */
public interface PlaylistManager
{
    /**
     * Returns the <code>AudioStreamSource</code> object associated with the
     * current playlist. From this object the songs to be played can be fetched.
     *
     * @return the source object for the audio streams to be played
     */
    AudioStreamSource getSource();

    /**
     * Loads the state of this playlist manager. Here a concrete implementation
     * should check, which auido files are available and prepare the creation of
     * an <code>AudioStreamSource</code> for the playlist.
     * <code>getSource()</code> will be called next. The return value
     * indicates the start position of the first song of the playlist. So when
     * playback stopped in the middle of the song, it can later continue at this
     * very position.
     *
     * @return the start position of the first song in the playlist
     * @throws IOException if an IO error occurs
     */
    CurrentPositionInfo loadState() throws PlaylistException;

    /**
     * Saves the current state of the playlist manager. This method is called
     * by the main application to tell the playlist manager that playback is to
     * be stopped at the current position. A concrete implementation must
     * somehow make all necessary information persistent, so that playback can
     * later be continued at the same position.
     *
     * @param position the position of the currently played song; here a non
     * <b>null</b> value is passed in when the user canceled playback in the middle
     * of a song; a concrete implementation must save this value so that
     * playback can continue at that exact position; the parameter can also be
     * <b>null</b>
     * @throws IOException if an IO error occurs
     */
    void saveState(CurrentPositionInfo position) throws PlaylistException;

    /**
     * Tells the playlist manager that playback of a song has started. The song
     * is identified by the ID of its audio stream (as returned by the
     * corresponding <code>{@link de.olix.playa.engine.AudioStreamData}</code>
     * object). A concrete implementation can update its internal state in
     * reaction of this method call.
     *
     * @param streamID the ID of the stream whose playback recently started
     */
    void playbackStarted(Object streamID);

    /**
     * Tells the playlist manager that playback of a song was finished. This
     * method is similar to <code>{@link #playbackStarted(Object)}</code>,
     * but it is called when playback of a song has finished.
     *
     * @param streamID the ID of the affected audio stream
     */
    void playbackEnded(Object streamID);

    /**
     * Requests a <code>{@link SongInfo}</code> object for a specified audio
     * stream. This information can be used to display some interesting
     * information about a song to the user of an audio player application. The
     * requested info object is not immediately fetched because this could be a
     * longer running operation, which also could interfere with other parts of
     * the audio engine. Instead a <code>{@link SongInfoCallBack}</code>
     * object is provided that is invoked when the desired information is
     * available.
     *
     * @param streamID the ID of the affected audio stream
     * @param callBack the call back object
     * @param param an arbitrary parameter that will be passed to the call back
     * when it is invoked
     */
    void requestSongInfo(Object streamID, SongInfoCallBack callBack,
            Object param);

    /**
     * Initializes the audio read monitor. If this playlist manager needs access
     * to the source medium, it can use this monitor object for synchronization
     * with the audio engine.
     *
     * @param monitor the monitor object
     */
    void initAudioReadMonitor(AudioReadMonitor monitor);

    /**
     * Initializes the song info provider for obtaining information about audio
     * files.
     *
     * @param provider the song info provider
     */
    void initSongInfoProvider(SongInfoProvider provider);

	/**
     * Returns information about the current playlist. This can be displayed to
     * the user in the GUI of an audio application.
     *
     * @return an object with information about the current playlist
     */
    PlaylistInfo getPlaylistInfo();

	/**
	 * Tells the playlist manager to shut down and free all used resources.
	 * This method should be called at the end of an audio application.
	 */
	void shutdown();
}
