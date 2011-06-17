package de.olix.playa.playlist;

import java.io.IOException;

/**
 * <p>
 * Definition of an interface which is used to obtain new
 * {@link PlaylistManager} instances.
 * </p>
 * <p>
 * This interface can be used by an audio player application to setup a playlist
 * manager for the media files currently available. Typically, an application
 * checks whether already a playlist exists for the available audio files which
 * has not yet been played completely. If this is the case, a
 * {@code PlaylistManager} object is created which allows continuing the
 * playlist at the very same position. Otherwise, a new playlist is setup, and a
 * corresponding {@code PlaylistManager} object is returned.
 * </p>
 *
 * @author Oliver Heger
 * @version $Id: $
 */
public interface PlaylistManagerFactory
{
    /**
     * Creates a {@code PlaylistManager}. An implementation has to return a
     * fully initialized {@code PlaylistManager} instance which can be used to
     * play the songs in the current playlist.
     *
     * @param defaultOrder the default order if a playlist has to be newly
     *        generated
     * @return the {@code PlaylistManager} instance
     * @throws IOException if an error occurs
     */
    PlaylistManager createPlaylistManager(PlaylistOrder defaultOrder)
            throws IOException;
}
