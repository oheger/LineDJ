package de.oliver_heger.mediastore.localstore;

import de.oliver_heger.mediastore.service.SongData;

/**
 * <p>
 * Definition of an interface providing access to locally stored information
 * about media files.
 * </p>
 * <p>
 * This interface is used by an application to communicate with the local media
 * store.
 * </p>
 *
 * @author Oliver Heger
 * @version $Id: $
 */
public interface MediaStore
{
    /**
     * Updates the local store with information about a song. This method is
     * called when information about a song becomes available (e.g. when a song
     * has been played). It checks whether this song (and the associated artist
     * and album) is already contained in the local database. If not, it is
     * added now.
     *
     * @param songData the data object describing the song
     */
    void updateSongData(SongData songData);
}
