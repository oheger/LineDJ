package de.oliver_heger.mediastore.server;

import java.util.Collection;
import java.util.Set;
import java.util.TreeSet;

import javax.persistence.EntityManager;
import javax.persistence.EntityNotFoundException;

import de.oliver_heger.mediastore.server.db.JPATemplate;
import de.oliver_heger.mediastore.server.model.AbstractSynonym;
import de.oliver_heger.mediastore.server.model.ArtistEntity;
import de.oliver_heger.mediastore.server.model.ArtistSynonym;
import de.oliver_heger.mediastore.service.utils.DTOTransformer;
import de.oliver_heger.mediastore.shared.BasicMediaService;
import de.oliver_heger.mediastore.shared.SynonymUpdateData;
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
                        ArtistEntity e = findAndCheckArtist(em, artistID);
                        checkUser(e.getUser());
                        return createArtistDetailInfo(e);
                    }
                };
        return templ.execute();
    }

    /**
     * {@inheritDoc} This implementation evaluates the changes described by the
     * update data object. While removing synonyms is relatively easy, adding
     * new ones is complicated: here all data of the artists to become new
     * synonyms have to be moved to the current artist.
     */
    @Override
    public void updateArtistSynonyms(final long artistID,
            final SynonymUpdateData updateData)
    {
        JPATemplate<Void> templ = new JPATemplate<Void>(false)
        {
            @Override
            protected Void performOperation(EntityManager em)
            {
                ArtistEntity e = findAndCheckArtist(em, artistID);
                removeArtistSynonyms(em, e, updateData.getRemoveSynonyms());
                addArtistSynonyms(em, e, updateData.getNewSynonymIDs());
                return null;
            }
        };
        templ.execute();
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
     * Transforms all dependent data from one artist to another one. This method
     * can be called to merge artists (e.g. if one is a synonym of another one).
     * It also supports removing of an artist. In this case the destination
     * artist has to be set to <b>null</b>.
     *
     * @param dest the destination artist
     * @param src the source artist
     */
    private void transferArtistData(EntityManager em, ArtistEntity dest,
            ArtistEntity src)
    {
        // TODO handle case dest == null
        moveArtistSynonyms(dest, src);
        // TODO transfer further data
    }

    /**
     * Moves all synonyms from one artist to another one.
     *
     * @param dest the destination artist
     * @param src the source artist
     */
    private void moveArtistSynonyms(ArtistEntity dest, ArtistEntity src)
    {
        for (ArtistSynonym as : src.getSynonyms())
        {
            dest.addSynonymName(as.getName());
        }
        dest.addSynonymName(src.getName());
    }

    /**
     * Removes the specified synonyms from the given artist entity.
     *
     * @param em the entity manager
     * @param e the artist entity
     * @param removeSyns the synonym names to be removed
     */
    private void removeArtistSynonyms(EntityManager em, ArtistEntity e,
            Set<String> removeSyns)
    {
        for (String syn : removeSyns)
        {
            ArtistSynonym as = e.findSynonym(syn);

            if (as != null)
            {
                e.removeSynonym(as);
                em.remove(as);
            }
        }
    }

    /**
     * Adds new entities as synonyms to an artist. All data of the new synonym
     * entities is added to the current artist.
     *
     * @param em the entity manager
     * @param e the current artist entity
     * @param newSynIDs a set with the IDs of the new synonym entities
     */
    private void addArtistSynonyms(EntityManager em, ArtistEntity e,
            Set<Object> newSynIDs)
    {
        for (Object id : newSynIDs)
        {
            ArtistEntity synArt = findAndCheckArtist(em, id);
            transferArtistData(em, e, synArt);
            em.remove(synArt);
        }
    }

    /**
     * Helper method for retrieving an artist. This method also checks whether
     * the artist belongs to the current user.
     *
     * @param em the entity manager
     * @param artistID the ID of the artist to be retrieved
     * @return the artist with this ID
     * @throws EntityNotFoundException if the entity cannot be resolved
     * @throws IllegalStateException if the artist does not belong to the
     *         current user
     */
    private ArtistEntity findAndCheckArtist(EntityManager em, Object artistID)
    {
        ArtistEntity e = find(em, ArtistEntity.class, artistID);
        checkUser(e.getUser());
        return e;
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
