package de.olix.playa.playlist;

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
    PlaylistManager createPlaylistManager();
}
