package de.olix.playa.playlist;

import java.io.IOException;

/**
 * <p>
 * Definition of an interface for objects that manage a list of songs to be
 * played.
 * </p>
 * <p>
 * The audio player main class does not itself decide, which songs to play in
 * which order. This task is delegated to a {@code PlaylistManager}. The main
 * task of such an object is to obtain all media files currently available and
 * to define an order in which they are played. This list of songs also has to
 * be persisted, so that the application can be closed and later restarted at
 * the very same position.
 * </p>
 *
 * @author Oliver Heger
 * @version $Id$
 */
public interface PlaylistManager
{
    /**
     * Loads the state of this {@code PlaylistManager}. Here a concrete
     * implementation should check, which audio files are available and whether
     * already an order for playing them is defined. If so, this list should be
     * loaded. The return value indicates the start position of the first song
     * of the play list. So when playback stopped in the middle of the song, it
     * can later continue at this very position.
     *
     * @return the start position of the first song in the play list
     * @throws IOException if an IO error occurs
     */
    CurrentPositionInfo loadState() throws IOException;

    /**
     * Saves the current state of this {@code PlaylistManager}. This method is
     * called by the main application to tell this object that playback is to be
     * stopped at the current position. A concrete implementation must somehow
     * make all necessary information persistent, so that playback can later be
     * continued at the same position.
     *
     * @param position the position of the currently played song; here a non
     *        <b>null</b> value is passed in when the user canceled playback in
     *        the middle of a song; a concrete implementation must save this
     *        value so that playback can continue at that exact position; the
     *        parameter can also be <b>null</b>
     * @throws IOException if an IO error occurs
     */
    void saveState(CurrentPositionInfo position) throws IOException;

    /**
     * Returns information about the current list of songs to be played. This
     * can be displayed to the user in the GUI of an audio application.
     *
     * @return an object with information about the current play list
     */
    PlaylistInfo getPlaylistInfo();

    /**
     * Tells this object to make the previous song the current one. This method
     * can be called if the user wants to go back one song in the current play
     * list.
     *
     * @return a flag whether there is a previous song
     */
    boolean previousSong();

    /**
     * Tells this object to advance to the next song. This method is called if
     * the user wants to skip a song or a song has been played completely. The
     * return value indicates whether the position could be changed; a value of
     * <b>false</b> means that already the end of the list was reached.
     *
     * @return a flag there is a next song
     */
    boolean nextSong();

    /**
     * Returns the index of the current song in the play list.
     *
     * @return the index of the current song (0-based)
     */
    int getCurrentSongIndex();

    /**
     * Sets the index of the current song. Using this method the position in the
     * song list can be set to an arbitrary index.
     *
     * @param idx the index of the new current song
     */
    void setCurrentSongIndex(int idx);

    /**
     * Returns the URI of the current media file. This file has to be played.
     *
     * @return the URI of the current media file
     */
    String getCurrentSongURI();
}
