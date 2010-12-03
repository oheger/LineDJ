package de.oliver_heger.mediastore.server;

import java.util.Collection;
import java.util.Set;
import java.util.TreeSet;

import javax.persistence.EntityManager;
import javax.persistence.EntityNotFoundException;

import de.oliver_heger.mediastore.server.db.JPATemplate;
import de.oliver_heger.mediastore.server.model.AbstractSynonym;
import de.oliver_heger.mediastore.server.model.ArtistEntity;
import de.oliver_heger.mediastore.service.utils.DTOTransformer;
import de.oliver_heger.mediastore.shared.BasicMediaService;
import de.oliver_heger.mediastore.shared.model.ArtistDetailInfo;

/**
 * <p>
 * The Google RPC-based implementation of the {@link BasicMediaService}
 * interface.
 * </p>
 * <p>
 * This implementation makes use of the entity classes for the different media
 * types supported. Based on JPA operations data can be retrieved or
 * manipulated.
 * </p>
 *
 * @author Oliver Heger
 * @version $Id: $
 */
public class BasicMediaServiceImpl extends RemoteMediaServiceServlet implements
        BasicMediaService
{
    /**
     * The serial version UID.
     */
    private static final long serialVersionUID = 20101203L;

    /**
     * Returns details for the specified artist.
     *
     * @param artistID the ID of the artist
     * @return an object with detail information about this artist
     * @throws EntityNotFoundException if the artist cannot be resolved
     * @throws IllegalStateException if the artist does not belong to the logged
     *         in user
     */
    @Override
    public ArtistDetailInfo fetchArtistDetails(final long artistID)
    {
        JPATemplate<ArtistDetailInfo> templ =
                new JPATemplate<ArtistDetailInfo>()
                {
                    @Override
                    protected ArtistDetailInfo performOperation(EntityManager em)
                    {
                        ArtistEntity e = find(em, ArtistEntity.class, artistID);
                        checkUser(e.getUser());
                        return createArtistDetailInfo(e);
                    }
                };
        return templ.execute();
    }

    /**
     * Creates a detail info object for an artist entity.
     *
     * @param e the artist entity
     * @return the detail info object
     */
    private ArtistDetailInfo createArtistDetailInfo(ArtistEntity e)
    {
        ArtistDetailInfo info = new ArtistDetailInfo();
        info.setArtistID(e.getId());
        DTOTransformer.transform(e, info);
        info.setSynonyms(transformSynonyms(e.getSynonyms()));
        return info;
    }

    /**
     * Helper method for looking up an entity with a given ID. This method loads
     * the entity. If it cannot be found, an exception is thrown.
     *
     * @param <T> the type of the entity to be loaded
     * @param em the entity manager
     * @param entityCls the entity class
     * @param id the ID of the entity to be looked up
     * @return the entity with this ID
     * @throws EntityNotFoundException if the entity cannot be resolved
     */
    private static <T> T find(EntityManager em, Class<T> entityCls, Object id)
    {
        T entity = em.find(entityCls, id);
        if (entity == null)
        {
            throw new EntityNotFoundException("Cannot find entity of class "
                    + entityCls.getName() + " with ID " + id);
        }

        return entity;
    }

    /**
     * Helper method for transforming a set with synonym entities to a set of
     * strings (with the synonym names).
     *
     * @param syns the set with entities to be transformed
     * @return the resulting set with strings
     */
    private static Set<String> transformSynonyms(
            Collection<? extends AbstractSynonym> syns)
    {
        Set<String> result = new TreeSet<String>();

        for (AbstractSynonym as : syns)
        {
            result.add(as.getName());
        }

        return result;
    }
}
