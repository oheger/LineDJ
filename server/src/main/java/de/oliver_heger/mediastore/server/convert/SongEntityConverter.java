package de.oliver_heger.mediastore.server.convert;

import java.util.Collection;
import java.util.HashMap;
import java.util.Map;

import javax.persistence.EntityManager;

import com.google.appengine.api.datastore.KeyFactory;

import de.oliver_heger.mediastore.server.db.EntityManagerSupport;
import de.oliver_heger.mediastore.server.model.AlbumEntity;
import de.oliver_heger.mediastore.server.model.ArtistEntity;
import de.oliver_heger.mediastore.server.model.SongEntity;
import de.oliver_heger.mediastore.service.utils.DTOTransformer;
import de.oliver_heger.mediastore.shared.model.SongInfo;

/**
 * <p>
 * A specialized {@link EntityConverter} implementation that deals with song
 * objects.
 * </p>
 * <p>
 * In addition to the converter functionality, this class also has to resolve
 * some IDs to populate all fields of the {@link SongInfo} object.
 * </p>
 *
 * @author Oliver Heger
 * @version $Id: $
 */
public class SongEntityConverter implements
        EntityConverter<SongEntity, SongInfo>, EntityManagerSupport
{
    /** A map with the already resolved artist entities. */
    private Map<Long, ArtistEntity> artistEntities;

    /** A map with the already resolved album entities. */
    private Map<Long, AlbumEntity> albumEntities;

    /** The entity manager for resolving references. */
    private EntityManager em;

    /**
     * Initializes this object with a list of artist entities. These artists are
     * used to resolve the IDs stored in the song entity objects.
     *
     * @param artists a list of artist objects
     * @throws NullPointerException if the list is <b>null</b> or contains
     *         <b>null</b> elements
     */
    public void initResolvedArtists(Collection<? extends ArtistEntity> artists)
    {
        artistEntities = new HashMap<Long, ArtistEntity>();
        for (ArtistEntity ae : artists)
        {
            artistEntities.put(ae.getId(), ae);
        }
    }

    /**
     * Initializes this object with a list of album entities. These albums are
     * used to resolve the IDs stored in the song entity objects.
     *
     * @param albums a list of album objects
     * @throws NullPointerException if the list is <b>null</b> or contains
     *         <b>null</b> elements
     */
    public void initResolvedAlbums(Collection<? extends AlbumEntity> albums)
    {
        albumEntities = new HashMap<Long, AlbumEntity>();
        for (AlbumEntity ae : albums)
        {
            albumEntities.put(ae.getId(), ae);
        }
    }

    /**
     * Returns the entity manager used by this converter.
     *
     * @return the entity manager
     */
    public EntityManager getEntityManager()
    {
        return em;
    }

    /**
     * Initializes the entity manager. This entity manager is needed for
     * resolving references to other objects.
     *
     * @param em the entity manager
     */
    @Override
    public void setEntityManager(EntityManager em)
    {
        this.em = em;
    }

    /**
     * Converts the given song entity object to a song info object.
     *
     * @param e the entity to be converted
     * @return the resulting info object
     */
    @Override
    public SongInfo convert(SongEntity e)
    {
        SongInfo info = new SongInfo();
        convert(e, info);

        return info;
    }

    /**
     * Converts the given song entity object to the specified song info object.
     * The properties of the entity are extracted and written into the given
     * info object.
     *
     * @param e the song entity
     * @param info the target info object
     * @throws IllegalArgumentException if one of the passed in objects is
     *         <b>null</b>
     */
    public void convert(SongEntity e, SongInfo info)
    {
        DTOTransformer.transform(e, info);

        if (e.getId() != null)
        {
            info.setSongID(KeyFactory.keyToString(e.getId()));
        }

        convertArtist(e, info);
        convertAlbum(e, info);
    }

    /**
     * Resolves the artist of the specified song. If a list of artist entities
     * has been set, this information is used to resolve the entity. Otherwise,
     * the ID is looked up using the entity manager.
     *
     * @param song the song entity
     * @return the resolved artist entity or <b>null</b> if the artist cannot be
     *         resolved
     */
    ArtistEntity resolveArtist(SongEntity song)
    {
        if (song.getArtistID() != null)
        {
            if (artistEntities != null)
            {
                return artistEntities.get(song.getArtistID());
            }
            else
            {
                if (getEntityManager() != null)
                {
                    return getEntityManager().find(ArtistEntity.class,
                            song.getArtistID());
                }
            }
        }

        return null;
    }

    /**
     * Resolves the album of the specified song. Works analogously to
     * {@link #resolveArtist(SongEntity)}.
     *
     * @param song the song entity
     * @return the resolved album entity or <b>null</b> if the album cannot be
     *         resolved
     */
    AlbumEntity resolveAlbum(SongEntity song)
    {
        if (song.getAlbumID() != null)
        {
            if (albumEntities != null)
            {
                return albumEntities.get(song.getAlbumID());
            }
            else
            {
                if (getEntityManager() != null)
                {
                    return getEntityManager().find(AlbumEntity.class,
                            song.getAlbumID());
                }
            }
        }

        return null;
    }

    /**
     * Converts the data related to the song's artist.
     *
     * @param e the song entity
     * @param info the song info object
     */
    private void convertArtist(SongEntity e, SongInfo info)
    {
        ArtistEntity ae = resolveArtist(e);
        if (ae == null)
        {
            info.setArtistID(null);
        }
        else
        {
            info.setArtistName(ae.getName());
        }
    }

    /**
     * Converts the data related to the song's album.
     *
     * @param e the song entity
     * @param info the song info object
     */
    private void convertAlbum(SongEntity e, SongInfo info)
    {
        AlbumEntity ae = resolveAlbum(e);
        if (ae == null)
        {
            info.setAlbumID(null);
        }
        else
        {
            info.setAlbumName(ae.getName());
        }
    }
}
