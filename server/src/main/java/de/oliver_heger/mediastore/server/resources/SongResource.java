package de.oliver_heger.mediastore.server.resources;

import javax.ws.rs.Consumes;
import javax.ws.rs.PUT;
import javax.ws.rs.Path;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;

import de.oliver_heger.mediastore.server.NotLoggedInException;
import de.oliver_heger.mediastore.server.sync.MediaSyncService;
import de.oliver_heger.mediastore.server.sync.SyncResult;
import de.oliver_heger.mediastore.service.SongData;

/**
 * <p>
 * A specialized resource for songs.
 * </p>
 * <p>
 * This class allows synchronization of song data objects.
 * </p>
 *
 * @author Oliver Heger
 * @version $Id: $
 */
@Path("song")
public class SongResource extends AbstractSyncResource<SongData, String>
{
    /**
     * Processes a PUT request for a song. This implementation just delegates to
     * the generic sync method of the super class.
     *
     * @param data the song data object
     * @return a response with the result of the operation
     */
    @PUT
    @Consumes(MediaType.APPLICATION_XML)
    public Response putSong(SongData data)
    {
        return doSync(data);
    }

    /**
     * Invokes the {@link MediaSyncService} service for actually synchronizing
     * the song data object. This implementation calls the sync method for
     * songs.
     *
     * @param service a reference to the {@link MediaSyncService}
     * @param data the data object for the song
     * @return an object with the result of the operation
     */
    @Override
    protected SyncResult<String> invokeSycService(MediaSyncService service,
            SongData data) throws NotLoggedInException
    {
        return service.syncSong(data);
    }
}
