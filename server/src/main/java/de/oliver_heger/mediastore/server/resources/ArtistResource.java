package de.oliver_heger.mediastore.server.resources;

import javax.ws.rs.Consumes;
import javax.ws.rs.PUT;
import javax.ws.rs.Path;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;

import de.oliver_heger.mediastore.server.NotLoggedInException;
import de.oliver_heger.mediastore.server.sync.MediaSyncService;
import de.oliver_heger.mediastore.server.sync.SyncResult;
import de.oliver_heger.mediastore.service.ArtistData;

/**
 * <p>
 * A resource implementation for artists.
 * </p>
 * <p>
 * This class allows synchronizing artist data objects.
 * </p>
 *
 * @author Oliver Heger
 * @version $Id: $
 */
@Path("artist")
public class ArtistResource extends AbstractSyncResource<ArtistData, Long>
{
    /**
     * Processes a put request for an artist. This implementation just delegates
     * to the generic sync method of the super class.
     *
     * @param artist the artist data object
     * @return a response for this request
     */
    @PUT
    @Consumes(MediaType.APPLICATION_XML)
    public Response putArtist(ArtistData artist)
    {
        return doSync(artist);
    }

    /**
     * Invokes the sync service. This implementation calls the method for
     * synchronizing an artist data object.
     *
     * @param service the sync service
     * @param data the data object to be synchronized
     * @return the result of the sync operation
     * @throws NotLoggedInException if no user is logged in
     */
    @Override
    protected SyncResult<Long> invokeSycService(MediaSyncService service,
            ArtistData data) throws NotLoggedInException
    {
        return service.syncArtist(data);
    }
}
