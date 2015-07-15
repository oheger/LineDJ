package de.oliver_heger.mediastore.server.sync;

import de.oliver_heger.mediastore.server.NotLoggedInException;
import de.oliver_heger.mediastore.service.AlbumData;
import de.oliver_heger.mediastore.service.ArtistData;
import de.oliver_heger.mediastore.service.SongData;

/**
 * <p>
 * The service interface of the synchronization service.
 * </p>
 * <p>
 * This service is called by clients when new entities (e.g. artists or songs)
 * are available. The service checks whether the entity is already stored in the
 * database. If not, it is imported.
 * </p>
 *
 * @author Oliver Heger
 * @version $Id: $
 */
public interface MediaSyncService
{
    /**
     * Synchronizes the specified artist data object. The artist is added if it
     * is not already contained in the database.
     *
     * @param artist the artist data object
     * @return a result object for the synchronize operation
     * @throws NotLoggedInException if the current user cannot be determined
     */
    SyncResult<Long> syncArtist(ArtistData artist) throws NotLoggedInException;

    /**
     * Synchronizes the specified album data object. If no matching album entity
     * is found in the database, the album is added.
     *
     * @param album the album data object
     * @return a result object for the synchronization operation
     * @throws NotLoggedInException if the current user cannot be determined
     */
    SyncResult<Long> syncAlbum(AlbumData album) throws NotLoggedInException;

    /**
     * Synchronizes the specified song data object. If no matching song entity
     * is found in the database, the song is added. This method expects that the
     * song's artist and album have been synchronized before, so the
     * corresponding entities can be found based on the information contained in
     * the data object. If it is not possible to find an artist or an album, the
     * corresponding references of the resulting song entity will not be set.
     *
     * @param song the song data object
     * @return a result object for the synchronization operation
     * @throws NotLoggedInException if the current user cannot be determined
     */
    SyncResult<String> syncSong(SongData song) throws NotLoggedInException;
}
