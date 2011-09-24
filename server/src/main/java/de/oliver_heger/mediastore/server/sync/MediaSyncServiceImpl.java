package de.oliver_heger.mediastore.server.sync;

import java.util.Iterator;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

import javax.persistence.EntityManager;

import com.google.appengine.api.datastore.KeyFactory;
import com.google.appengine.api.oauth.OAuthRequestException;
import com.google.appengine.api.oauth.OAuthService;
import com.google.appengine.api.oauth.OAuthServiceFactory;
import com.google.appengine.api.users.User;

import de.oliver_heger.mediastore.server.NotLoggedInException;
import de.oliver_heger.mediastore.server.db.JPATemplate;
import de.oliver_heger.mediastore.server.model.AlbumEntity;
import de.oliver_heger.mediastore.server.model.ArtistEntity;
import de.oliver_heger.mediastore.server.model.SongEntity;
import de.oliver_heger.mediastore.service.AlbumData;
import de.oliver_heger.mediastore.service.ArtistData;
import de.oliver_heger.mediastore.service.SongData;
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
    /** The logger. */
    private static final Logger LOG = Logger
            .getLogger(MediaSyncServiceImpl.class.getName());

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
     * Performs a sync operation with the specified album data object.
     *
     * @param album the album data object
     * @return the result of the sync operation
     * @throws NotLoggedInException if no user is logged in
     */
    @Override
    public SyncResult<Long> syncAlbum(final AlbumData album)
            throws NotLoggedInException
    {
        final User user = authenticateUser();
        JPATemplate<SyncResult<Long>> templ =
                new JPATemplate<SyncResult<Long>>()
                {
                    @Override
                    protected SyncResult<Long> performOperation(EntityManager em)
                    {
                        return syncAlbum(em, user, album);
                    }
                };

        return templ.execute();
    }

    /**
     * Performs a sync operation with the specified song data object. This
     * implementation first checks whether a corresponding song exists in the
     * database. If no matching entity can be found (based on the criteria
     * implemented by the {@code equals()} method), a new entity is created. In
     * this case it is tried to find matching artist and album entities which
     * can be referenced from the song.
     *
     * @param song the song data object
     * @return the result of the sync operation
     * @throws NotLoggedInException if no user is logged in
     */
    @Override
    public SyncResult<String> syncSong(final SongData song)
            throws NotLoggedInException
    {
        final User user = authenticateUser();
        JPATemplate<SyncResult<String>> templ =
                new JPATemplate<SyncResult<String>>()
                {
                    @Override
                    protected SyncResult<String> performOperation(
                            EntityManager em)
                    {
                        return syncSong(em, user, song);
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
        ArtistEntity e = findArtist(em, user, data.getName());
        boolean add = e == null;

        if (add)
        {
            if (LOG.isLoggable(Level.INFO))
            {
                LOG.log(Level.INFO, "Adding new artist: " + data.getName());
            }

            e = new ArtistEntity();
            DTOTransformer.transform(data, e);
            e.setUser(user);
            em.persist(e);
            em.flush();
        }

        return new SyncResultImpl<Long>(e.getId(), add);
    }

    /**
     * Searches for the artist with the given name.
     *
     * @param em the entity manager
     * @param user the current user
     * @param name the name of the artist
     * @return the corresponding artist entity or <b>null</b> if no matching
     *         artist can be found
     */
    private ArtistEntity findArtist(EntityManager em, User user, String name)
    {
        return ArtistEntity.findByNameOrSynonym(em, user, name);
    }

    /**
     * Synchronizes an album.
     *
     * @param em the entity manager
     * @param user the current user
     * @param data the data object describing the album
     * @return the result of the operation
     */
    private SyncResult<Long> syncAlbum(EntityManager em, User user,
            AlbumData data)
    {
        AlbumEntity album = findAlbum(em, user, data);
        boolean add = album == null;

        if (add)
        {
            if (LOG.isLoggable(Level.INFO))
            {
                LOG.log(Level.INFO, "Adding new album: " + data.getName());
            }

            album = new AlbumEntity();
            DTOTransformer.transform(data, album);
            album.setInceptionYear(DTOTransformer.toWrapper(data
                    .getInceptionYear()));
            album.setUser(user);
            em.persist(album);
            em.flush();
        }

        return new SyncResultImpl<Long>(album.getId(), add);
    }

    /**
     * Searches for an album based on the given data object. If no matching
     * album is found, result is <b>null</b>.
     *
     * @param em the entity manager
     * @param user the current user
     * @param data the album data object
     * @return the found entity or <b>null</b>
     */
    private AlbumEntity findAlbum(EntityManager em, User user, AlbumData data)
    {
        return findAlbum(em, user, data.getName(),
                DTOTransformer.toWrapper(data.getInceptionYear()));
    }

    /**
     * Searches for an album based on the given criteria.
     *
     * @param em the entity manager
     * @param user the current user
     * @param name the name of the album
     * @param inceptionYear the inception year
     * @return the found entity or <b>null</b>
     */
    private AlbumEntity findAlbum(EntityManager em, User user, String name,
            Integer inceptionYear)
    {
        AlbumEntity album = null;
        List<AlbumEntity> albums =
                AlbumEntity.findByNameAndSynonym(em, user, name);

        for (Iterator<AlbumEntity> it = albums.iterator(); it.hasNext()
                && album == null;)
        {
            AlbumEntity e = it.next();
            if (e.matches(name, inceptionYear))
            {
                album = e;
            }
        }
        return album;
    }

    /**
     * Synchronizes a song.
     *
     * @param em the entity manager
     * @param user the current user
     * @param data the data object for the song
     * @return an object describing the result of the operation
     */
    private SyncResult<String> syncSong(EntityManager em, User user,
            SongData data)
    {
        SongEntity syncSong = fetchSyncSong(em, user, data);
        boolean add = syncSong.getId() == null;

        if (add)
        {
            if (LOG.isLoggable(Level.INFO))
            {
                LOG.log(Level.INFO, "Adding new song: " + data.getName());
            }

            resolveArtistReference(em, user, data, syncSong);
            resolveAlbumReference(em, user, data, syncSong);
            em.persist(syncSong);
            em.flush();
        }
        else
        {
            syncSong.setPlayCount(syncSong.getPlayCount() + data.getPlayCount());
        }

        return new SyncResultImpl<String>(KeyFactory.keyToString(syncSong
                .getId()), add);
    }

    /**
     * Resolves the reference to the artist of a song.
     *
     * @param em the entity manager
     * @param user the current user
     * @param data the data object for the song
     * @param syncSong the newly created entity for the song
     */
    private void resolveArtistReference(EntityManager em, User user,
            SongData data, SongEntity syncSong)
    {
        if (data.getArtistName() != null)
        {
            ArtistEntity art = findArtist(em, user, data.getArtistName());
            if (art != null)
            {
                syncSong.setArtistID(art.getId());
            }
            else
            {
                LOG.warning("Cannot resolve artist: " + data.getArtistName());
            }
        }
    }

    /**
     * Resolves the reference to the album of a song.
     *
     * @param em the entity manager
     * @param user the current user
     * @param data the data object for the song
     * @param syncSong the newly created entity for the song
     */
    private void resolveAlbumReference(EntityManager em, User user,
            SongData data, SongEntity syncSong)
    {
        if (data.getAlbumName() != null)
        {
            AlbumEntity album =
                    findAlbum(em, user, data.getAlbumName(),
                            syncSong.getInceptionYear());
            if (album != null)
            {
                syncSong.setAlbumID(album.getId());
            }
            else
            {
                LOG.warning("Cannot resolve album: " + data.getAlbumName());
            }
        }
    }

    /**
     * Searches for a song entity that matches the criteria specified by the
     * song data object. This method always returns a non <b>null</b> entity. If
     * a match is found, the entity has a valid ID set. Otherwise, the ID is
     * undefined, but other properties have already been initialized from the
     * song data object.
     *
     * @param em the entity manager
     * @param user the current user
     * @param data the song data object
     * @return the song entity for synchronization
     */
    private SongEntity fetchSyncSong(EntityManager em, User user, SongData data)
    {
        SongEntity syncSong = new SongEntity();
        DTOTransformer.transform(data, syncSong);
        Long duration =
                (data.getDuration() == null) ? null : Long.valueOf(data
                        .getDuration().longValue());
        Long artistID = fetchSongArtistID(em, user, data);

        for (SongEntity song : SongEntity.findByNameAndSynonym(em, user,
                data.getName()))
        {
            if (song.matches(data.getName(), artistID, duration))
            {
                return song;
            }
        }

        syncSong.setUser(user);
        return syncSong;
    }

    /**
     * Returns the ID of the artist of a song to be synchronized. Result may be
     * <b>null</b> if the song is not associated with an artist.
     *
     * @param em the entity manager
     * @param user the current user
     * @param data the song data object
     * @return the ID of the song's artist
     */
    private Long fetchSongArtistID(EntityManager em, User user, SongData data)
    {
        if (data.getArtistName() == null)
        {
            return null;
        }
        ArtistEntity artist = findArtist(em, user, data.getArtistName());
        if (artist == null)
        {
            LOG.warning("Synchronization of a song with an invalid artist: "
                    + data.getArtistName());
            return null;
        }
        else
        {
            return artist.getId();
        }
    }
}
