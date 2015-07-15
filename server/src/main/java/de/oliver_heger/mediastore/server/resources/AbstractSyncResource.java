package de.oliver_heger.mediastore.server.resources;

import java.net.URI;
import java.util.logging.Level;
import java.util.logging.Logger;

import javax.ws.rs.core.Context;
import javax.ws.rs.core.Response;
import javax.ws.rs.core.Response.Status;
import javax.ws.rs.core.UriInfo;

import de.oliver_heger.mediastore.server.NotLoggedInException;
import de.oliver_heger.mediastore.server.sync.MediaSyncService;
import de.oliver_heger.mediastore.server.sync.MediaSyncServiceImpl;
import de.oliver_heger.mediastore.server.sync.SyncResult;

/**
 * <p>
 * A base class for resources that take part in synchronization operations.
 * </p>
 * <p>
 * This abstract base already provides some common functionality needed for
 * resources that can be synchronized by clients:
 * <ul>
 * <li>A reference to the {@link MediaSyncService} is provided.</li>
 * <li>A {@link SyncResult} object can be transformed into a response object.</li>
 * <li>An {@code UriInfo} object is maintained.</li>
 * <li>There is already a template method performing the synchronization
 * operation. Concrete sub classes just have to define the
 * {@code invokeSyncService()} method.</li>
 * </ul>
 *
 * @author Oliver Heger
 * @version $Id: $
 * @param <T> the type of data objects this resource is about
 * @param <K> the type of the primary key of the data objects
 */
public abstract class AbstractSyncResource<T, K>
{
    /** The logger. */
    protected static final Logger LOG = Logger
            .getLogger(AbstractSyncResource.class.getName());

    /** The UriInfo object for the generation of URIs. */
    @Context
    UriInfo uriInfo;

    /**
     * Performs a synchronization operation. This method obtains the
     * {@link MediaSyncService} and delegates to {@code invokeSyncService()}.
     * Then the result of the operation is transformed into a Response object.
     * Exceptions are handled properly.
     *
     * @param data the data object to be synchronized
     * @return the response describing the result of the operation
     */
    protected Response doSync(T data)
    {
        try
        {
            SyncResult<K> result = invokeSycService(fetchSyncService(), data);
            return createResponseFromSyncResult(result);
        }
        catch (NotLoggedInException nlex)
        {
            LOG.log(Level.WARNING,
                    "Synchronization attempt without a logged in user!", nlex);
            return createResponseNotLoggedIn();
        }
    }

    /**
     * Obtains the {@link MediaSyncService}. Implementation note: Currently the
     * service instance is directly created in a hard-coded way. It would well
     * be possible to obtain it using a different means, e.g. by looking it up
     * in a Spring context got from the servlet context.
     *
     * @return the {@link MediaSyncService} instance
     */
    protected MediaSyncService fetchSyncService()
    {
        return new MediaSyncServiceImpl();
    }

    /**
     * Produces a response based on the specified {@link SyncResult} object.
     * This method generates a response code which corresponds to the outcome of
     * the synchronization operation performed.
     *
     * @param result the result of the synchronization operation
     * @return the corresponding response
     */
    protected Response createResponseFromSyncResult(SyncResult<K> result)
    {
        URI uri =
                uriInfo.getAbsolutePathBuilder()
                        .path(convertKeyToURIPart(result.getKey())).build();
        if (result.imported())
        {
            return Response.created(uri).build();
        }

        return Response.status(Status.NOT_MODIFIED).location(uri).build();
    }

    /**
     * Creates a response in case that no user was logged in.
     *
     * @return the response
     */
    protected Response createResponseNotLoggedIn()
    {
        return Response.status(Status.UNAUTHORIZED).build();
    }

    /**
     * Converts the primary key of a data object to a string which can be
     * integrated into a URI pointing to the corresponding resource. This method
     * is called when the response of a synchronization call is generated. Then
     * the URI of the resource affected has to be produced. This base
     * implementation directly converts the key object to a string.
     *
     * @param key the primary key of a data object
     * @return a string to be used as part of a URI
     */
    protected String convertKeyToURIPart(K key)
    {
        return String.valueOf(key);
    }

    /**
     * Invokes the {@link MediaSyncService} to actually perform the operation
     * with the specified data object. This method is called by
     * {@link #doSync(Object)}. Concrete sub classes have to define this method
     * correspondingly.
     *
     * @param service the {@link MediaSyncService}
     * @param data the data object to be synchronized
     * @return the result object of the operation
     * @throws NotLoggedInException if no user is logged in
     */
    protected abstract SyncResult<K> invokeSycService(MediaSyncService service,
            T data) throws NotLoggedInException;
}
