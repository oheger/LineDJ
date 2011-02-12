package de.oliver_heger.mediastore.server.sync;

import de.oliver_heger.mediastore.server.NotLoggedInException;
import de.oliver_heger.mediastore.service.ArtistData;

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
}
