package de.oliver_heger.mediastore.localstore;

import java.util.List;

import de.oliver_heger.mediastore.localstore.model.SongEntity;
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

    /**
     * Synchronizes the local database with the server. Synchronization happens
     * in the background thread and is monitored by the {@link SyncController}
     * object. A limit for the number of songs to synchronize can be specified;
     * a value of <b>null</b> means that there is no restriction.
     *
     * @param observer the observer for the command (must not be <b>null</b>)
     * @param syncController the controller for the sync operation (must not be
     *        <b>null</b>)
     * @param maxSongs the maximum number of songs to synchronize (can be
     *        <b>null</b> for no limit)
     */
    void syncWithServer(CommandObserver<List<SongEntity>> observer,
            SyncController syncController, Integer maxSongs);
}
