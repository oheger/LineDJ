package de.oliver_heger.mediastore.server.sync;

import javax.persistence.EntityManager;

import com.google.appengine.api.oauth.OAuthRequestException;
import com.google.appengine.api.oauth.OAuthService;
import com.google.appengine.api.oauth.OAuthServiceFactory;
import com.google.appengine.api.users.User;

import de.oliver_heger.mediastore.server.NotLoggedInException;
import de.oliver_heger.mediastore.server.db.JPATemplate;
import de.oliver_heger.mediastore.server.model.ArtistEntity;
import de.oliver_heger.mediastore.service.ArtistData;
import de.oliver_heger.mediastore.service.utils.DTOTransformer;

/**
 * <p>
 * The implementation of the {@link MediaSyncService} interface.
 * </p>
 * <p>
 * This implementation class gets data objects passed from REST services and has
 * to decide whether new instances have to be created in the database for the
 * current user or whether the entity already exists. In order to determine the
 * user associated with the new entities, the OAUTH protocol is used.
 * </p>
 *
 * @author Oliver Heger
 * @version $Id: $
 */
public class MediaSyncServiceImpl implements MediaSyncService
{
    /**
     * Performs a sync operation with the specified artist data object.
     *
     * @param artist the artist data object
     * @return the result of the sync operation
     * @throws NotLoggedInException if no user is logged in
     */
    @Override
    public SyncResult<Long> syncArtist(final ArtistData artist)
            throws NotLoggedInException
    {
        final User user = authenticateUser();
        JPATemplate<SyncResult<Long>> templ =
                new JPATemplate<SyncResult<Long>>()
                {
                    @Override
                    protected SyncResult<Long> performOperation(EntityManager em)
                    {
                        return syncArtist(em, user, artist);
                    }
                };

        return templ.execute();
    }

    /**
     * Determines the current user. This implementation uses the OAUTH service
     * to obtained the current user. If authentication fails, an exception is
     * thrown.
     *
     * @return the current user
     * @throws NotLoggedInException if no user can be determined
     */
    protected User authenticateUser() throws NotLoggedInException
    {
        try
        {
            return fetchOAuthService().getCurrentUser();
        }
        catch (OAuthRequestException orex)
        {
            throw new NotLoggedInException(orex);
        }
    }

    /**
     * Obtains the OAUTH service. This implementation obtains the service from
     * its factory.
     *
     * @return the OAUTH service
     */
    protected OAuthService fetchOAuthService()
    {
        return OAuthServiceFactory.getOAuthService();
    }

    /**
     * Synchronizes an artist.
     *
     * @param em the entity manager
     * @param user the current user
     * @param data the data object describing the artist
     * @return the result of the operation
     */
    private SyncResult<Long> syncArtist(EntityManager em, User user,
            ArtistData data)
    {
        ArtistEntity e =
                ArtistEntity.findByNameOrSynonym(em, user, data.getName());
        boolean add = e == null;

        if (add)
        {
            e = new ArtistEntity();
            DTOTransformer.transform(data, e);
            e.setUser(user);
            em.persist(e);
            em.flush();
        }

        return new SyncResultImpl<Long>(e.getId(), add);
    }
}
