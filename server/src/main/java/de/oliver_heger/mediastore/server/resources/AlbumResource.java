package de.oliver_heger.mediastore.server.resources;

import javax.ws.rs.Consumes;
import javax.ws.rs.PUT;
import javax.ws.rs.Path;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;

import de.oliver_heger.mediastore.server.NotLoggedInException;
import de.oliver_heger.mediastore.server.sync.MediaSyncService;
import de.oliver_heger.mediastore.server.sync.SyncResult;
import de.oliver_heger.mediastore.service.AlbumData;

/**
 * <p>
 * A specialized resource for albums.
 * </p>
 * <p>
 * This class allows synchronization of album data objects.
 * </p>
 *
 * @author Oliver Heger
 * @version $Id: $
 */
@Path("album")
public class AlbumResource extends AbstractSyncResource<AlbumData, Long>
{
    /**
     * Processes a PUT request for an album. This implementation just delegates
     * to the generic sync method of the super class.
     *
     * @param data the data object for the album to be synchronized
     * @return a response for this operation
     */
    @PUT
    @Consumes(MediaType.APPLICATION_XML)
    public Response putAlbum(AlbumData data)
    {
        return doSync(data);
    }

    /**
     * Invokes the {@link MediaSyncService} for actually performing the sync
     * operation. This implementation calls the method for synchronizing albums.
     *
     * @param service the {@link MediaSyncService} reference
     * @param data the data object describing the album
     * @return a result object for this operation
     * @throws NotLoggedInException if the current user cannot be determined
     */
    @Override
    protected SyncResult<Long> invokeSycService(MediaSyncService service,
            AlbumData data) throws NotLoggedInException
    {
        return service.syncAlbum(data);
    }
}
