package de.oliver_heger.mediastore.localstore;

import de.oliver_heger.mediastore.oauth.OAuthCallback;
import de.oliver_heger.mediastore.service.SongData;

/**
 * <p>
 * Definition of an interface for components that control a sync operation
 * between the local database and the remote store.
 * </p>
 * <p>
 * The {@link MediaStore} interface provides a means to synchronize the data
 * stored locally with data on the server. During this operation all songs which
 * have been played since the last synchronization (including new ones) are
 * retrieved and sent to the server. The synchronization runs in a background
 * thread. Because it can be a long-running operation typically visual feedback
 * has to be provided to the user and also a way to abort the process. A
 * concrete implementation of this interface can address these requirements.
 * </p>
 * <p>
 * The basic idea is that an object implementing this interface is passed to the
 * sync operation. During synchronization, interface methods are called allowing
 * the implementation to update its state. These notifications can be used for
 * instance to implement a progress bar.
 * </p>
 *
 * @author Oliver Heger
 * @version $Id: $
 */
public interface SyncController
{
    /**
     * Notifies this object that a synchronization operation starts. The method
     * is passed the number of songs which are to be synchronized.
     *
     * @param songs the number of songs to be synchronized
     */
    void startSynchronization(int songs);

    /**
     * Notifies this object that the specified song is going to be synchronized.
     * This will cause the song data object (and also data objects for the
     * song's artist and album if defined) to be sent to the server. The return
     * value indicates whether the sync operation should continue. If an
     * implementation returns <b>false</b>, this song is skipped. This may be
     * used to abort the operation by skipping all remaining song objects.
     *
     * @param data the data object for the song to be synchronized
     * @return <b>true</b> for synchronizing this song, <b>false</b> for
     *         skipping it
     */
    boolean beforeSongSync(SongData data);

    /**
     * Notifies this object that a song has been synchronized with the server.
     * The flags passed to this method determine whether new objects have been
     * created on the server side.
     *
     * @param data the data object for the song that has been synchronized
     * @param songCreated a flag whether a new song was created on the server
     * @param artistCreated a flag whether a new artist was created on the
     *        server
     * @param albumCreated a flag whether a new album was created on the server
     */
    void afterSongSync(SongData data, boolean songCreated,
            boolean artistCreated, boolean albumCreated);

    /**
     * Notifies this object that a sync operation for a song was not successful.
     *
     * @param data the data object for the song affected
     */
    void failedSongSync(SongData data);

    /**
     * Notifies this object that synchronization is complete.
     */
    void endSynchronization();

    /**
     * Returns the callback that is needed by the OAuth components. This object
     * is needed for querying the OAuth tokens required for accessing the
     * server.
     *
     * @return the OAuth callback object
     */
    OAuthCallback getOAuthCallback();
}
