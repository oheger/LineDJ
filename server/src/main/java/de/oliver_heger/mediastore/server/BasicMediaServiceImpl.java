package de.oliver_heger.mediastore.server;

import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Set;

import javax.persistence.EntityManager;
import javax.persistence.EntityNotFoundException;

import com.google.appengine.api.datastore.KeyFactory;

import de.oliver_heger.mediastore.server.convert.AlbumEntityConverter;
import de.oliver_heger.mediastore.server.convert.ArtistEntityConverter;
import de.oliver_heger.mediastore.server.convert.ConvertUtils;
import de.oliver_heger.mediastore.server.convert.SongEntityConverter;
import de.oliver_heger.mediastore.server.db.JPATemplate;
import de.oliver_heger.mediastore.server.model.AlbumEntity;
import de.oliver_heger.mediastore.server.model.AlbumSynonym;
import de.oliver_heger.mediastore.server.model.ArtistEntity;
import de.oliver_heger.mediastore.server.model.ArtistSynonym;
import de.oliver_heger.mediastore.server.model.Finders;
import de.oliver_heger.mediastore.server.model.SongEntity;
import de.oliver_heger.mediastore.server.model.SongSynonym;
import de.oliver_heger.mediastore.shared.BasicMediaService;
import de.oliver_heger.mediastore.shared.SynonymUpdateData;
import de.oliver_heger.mediastore.shared.model.AlbumDetailInfo;
import de.oliver_heger.mediastore.shared.model.AlbumInfo;
import de.oliver_heger.mediastore.shared.model.ArtistDetailInfo;
import de.oliver_heger.mediastore.shared.model.ArtistInfo;
import de.oliver_heger.mediastore.shared.model.SongDetailInfo;
import de.oliver_heger.mediastore.shared.model.SongInfo;

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
                new JPATemplate<ArtistDetailInfo>(false)
                {
                    @Override
                    protected ArtistDetailInfo performOperation(EntityManager em)
                    {
                        ArtistEntity e = findAndCheckArtist(em, artistID);
                        return createArtistDetailInfo(em, e);
                    }
                };
        return templ.execute();
    }

    /**
     * Returns details for the specified song.
     *
     * @param songID the ID of the song
     * @return a data object with detail information about this song
     * @throws EntityNotFoundException if the song cannot be resolved
     * @throws IllegalStateException if the song does not belong to the logged
     *         in user
     */
    @Override
    public SongDetailInfo fetchSongDetails(final String songID)
    {
        JPATemplate<SongDetailInfo> templ =
                new JPATemplate<SongDetailInfo>(false)
                {
                    @Override
                    protected SongDetailInfo performOperation(EntityManager em)
                    {
                        SongEntity song = findAndCheckSong(em, songID);
                        return createSongDetailInfo(em, song);
                    }
                };
        return templ.execute();
    }

    /**
     * Returns detail information about the specified album.
     *
     * @param albumID the ID of the album
     * @return a data object with detail information about this album
     * @throws EntityNotFoundException if the album cannot be resolved
     * @throws IllegalStateException if the album does not belong to the logged
     *         in user
     */
    @Override
    public AlbumDetailInfo fetchAlbumDetails(final long albumID)
    {
        JPATemplate<AlbumDetailInfo> templ =
                new JPATemplate<AlbumDetailInfo>(false)
                {
                    @Override
                    protected AlbumDetailInfo performOperation(EntityManager em)
                    {
                        AlbumEntity album = findAndCheckAlbum(em, albumID);
                        return createAlbumDetailInfo(em, album);
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
        moveArtistSongs(artistID, updateData.getNewSynonymIDsAsLongs());

        JPATemplate<Void> templ = new JPATemplate<Void>(false)
        {
            @Override
            protected Void performOperation(EntityManager em)
            {
                ArtistEntity e = findAndCheckArtist(em, artistID);
                removeArtistSynonyms(em, e, updateData.getRemoveSynonyms());
                addArtistSynonyms(em, e, updateData.getNewSynonymIDsAsLongs());
                return null;
            }
        };
        templ.execute();
    }

    /**
     * {@inheritDoc} This implementation modifies the song with the given ID
     * according to the changes described in the {@link SynonymUpdateData}
     * object.
     */
    @Override
    public void updateSongSynonyms(final String songID,
            final SynonymUpdateData updateData)
    {
        JPATemplate<Void> templ = new JPATemplate<Void>(false)
        {
            @Override
            protected Void performOperation(EntityManager em)
            {
                SongEntity song = findAndCheckSong(em, songID);
                removeSongSynonyms(em, song, updateData.getRemoveSynonyms());
                addSongSynonyms(em, song, updateData.getNewSynonymIDs());
                return null;
            }
        };
        templ.execute();
    }

    /**
     * {@inheritDoc} This implementation applies the changes described by the
     * {@link SynonymUpdateData} object to the specified song. When adding new
     * synonyms all data from the affected albums is copied to the current
     * album. Then the synonym albums are removed.
     */
    @Override
    public void updateAlbumSynonyms(final long albumID,
            final SynonymUpdateData updateData)
    {
        moveAlbumSongs(albumID, updateData.getNewSynonymIDsAsLongs());

        JPATemplate<Void> templ = new JPATemplate<Void>(false)
        {
            @Override
            protected Void performOperation(EntityManager em)
            {
                AlbumEntity album = findAndCheckAlbum(em, albumID);
                removeAlbumSyonyms(em, album, updateData.getRemoveSynonyms());
                addAlbumSynonyms(em, album,
                        updateData.getNewSynonymIDsAsLongs());
                return null;
            }
        };
        templ.execute();
    }

    /**
     * Removes the artist with the specified ID. All dependent objects are
     * handled, too.
     *
     * @param artistID the ID of the artist to be removed
     * @return a flag whether the artist could be removed successfully
     * @throws IllegalStateException if the artist does not belong to the
     *         current user
     */
    @Override
    public boolean removeArtist(final long artistID)
    {
        try
        {
            moveArtistSongs(null, Collections.singleton(Long.valueOf(artistID)));
        }
        catch (EntityNotFoundException enfex)
        {
            return false;
        }

        JPATemplate<Boolean> templ = new JPATemplate<Boolean>()
        {
            @Override
            protected Boolean performOperation(EntityManager em)
            {
                ArtistEntity artist = em.find(ArtistEntity.class, artistID);
                if (artist != null)
                {
                    checkUser(artist.getUser());
                    em.remove(artist);
                    return Boolean.TRUE;
                }
                else
                {
                    return Boolean.FALSE;
                }
            }
        };
        return templ.execute();
    }

    /**
     * Removes the album with the specified ID. All dependent objects are
     * handled, too.
     *
     * @param albumID the ID of the album to be removed
     * @return a flag whether the album could be removed successfully
     * @throws IllegalStateException if the album does not belong to the current
     *         user
     */
    @Override
    public boolean removeAlbum(final long albumID)
    {
        try
        {
            moveAlbumSongs(null, Collections.singleton(Long.valueOf(albumID)));
        }
        catch (EntityNotFoundException enfex)
        {
            return false;
        }

        JPATemplate<Boolean> templ = new JPATemplate<Boolean>()
        {
            @Override
            protected Boolean performOperation(EntityManager em)
            {
                AlbumEntity album = em.find(AlbumEntity.class, albumID);
                if (album != null)
                {
                    checkUser(album.getUser());
                    em.remove(album);
                    return Boolean.TRUE;
                }
                else
                {
                    return Boolean.FALSE;
                }
            }
        };
        return templ.execute();
    }

    /**
     * Removes the song with the specified ID including its synonyms.
     *
     * @param songID the ID of the song to be removed
     * @return a flag whether the song could be removed successfully
     * @throws IllegalStateException if the song does not belong to the current
     *         user
     */
    @Override
    public boolean removeSong(final String songID)
    {
        JPATemplate<Boolean> templ = new JPATemplate<Boolean>()
        {
            @Override
            protected Boolean performOperation(EntityManager em)
            {
                SongEntity song =
                        em.find(SongEntity.class,
                                KeyFactory.stringToKey(songID));
                if (song != null)
                {
                    checkUser(song.getUser());
                    em.remove(song);
                    return Boolean.TRUE;
                }
                else
                {
                    return Boolean.FALSE;
                }
            }
        };
        return templ.execute();
    }

    /**
     * Creates a detail info object for an artist entity.
     *
     * @param em the entity manager
     * @param e the artist entity
     * @return the detail info object
     */
    private ArtistDetailInfo createArtistDetailInfo(EntityManager em,
            ArtistEntity e)
    {
        ArtistDetailInfo info = new ArtistDetailInfo();
        ArtistEntityConverter.INSTANCE.convert(e, info);
        info.setSynonyms(ConvertUtils.extractSynonymNames(e.getSynonyms()));
        List<SongEntity> songs = fetchSongsForArtist(em, e, info);
        fetchAlbumsForArtist(em, e, info, songs);

        return info;
    }

    /**
     * Initializes the list with {@link SongInfo} objects for the info object of
     * the given artist entity.
     *
     * @param em the entity manager
     * @param e the artist entity
     * @param info the info object for the artist
     * @return a list with the song entities of this artist
     */
    private List<SongEntity> fetchSongsForArtist(EntityManager em,
            ArtistEntity e, ArtistDetailInfo info)
    {
        List<SongEntity> songs = Finders.findSongsByArtist(em, e);
        SongEntityConverter conv = new SongEntityConverter();
        conv.initResolvedArtists(Collections.singleton(e));
        conv.setEntityManager(em);
        info.setSongs(ConvertUtils.convertEntities(songs, conv));
        return songs;
    }

    /**
     * Initializes the list with {@link AlbumInfo} objects for the info object
     * of the given artist entity. Basically, all albums the given songs refer
     * to are collected.
     *
     * @param em the entity manager
     * @param e the artist entity
     * @param songs a list with all songs of this artist
     * @return a list with the album entities related to this artist
     */
    private List<AlbumEntity> fetchAlbumsForArtist(EntityManager em,
            ArtistEntity e, ArtistDetailInfo info, List<SongEntity> songs)
    {
        List<AlbumEntity> albums = Finders.findAlbumsForSongs(em, songs);
        AlbumEntityConverter conv = new AlbumEntityConverter();
        info.setAlbums(ConvertUtils.convertEntities(albums, conv));
        return albums;
    }

    /**
     * Copies all synonyms from one artist to another one.
     *
     * @param dest the destination artist
     * @param src the source artist
     */
    private void copyArtistSynonyms(ArtistEntity dest, ArtistEntity src)
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
            Set<Long> newSynIDs)
    {
        for (Long id : newSynIDs)
        {
            ArtistEntity synArt = findAndCheckArtist(em, id);
            copyArtistSynonyms(e, synArt);
            em.remove(synArt);
        }
    }

    /**
     * Moves songs associated with one of a given set of artists to another
     * artist. This method can be used for merging or removing artists.
     * Implementation note: Obviously this operation has to be performed in a
     * separate step; otherwise there were strange data nucleus exceptions.
     *
     * @param artistID the ID of the new destination artist (can be <b>null</b>
     *        if artists are to be removed)
     * @param srcIDs a collection with the IDs of the source artists
     */
    private void moveArtistSongs(final Long artistID,
            final Collection<Long> srcIDs)
    {
        JPATemplate<Void> templ = new JPATemplate<Void>(false)
        {
            @Override
            protected Void performOperation(EntityManager em)
            {
                findAndCheckArtist(em, artistID);
                for (Long synArtID : srcIDs)
                {
                    findAndCheckArtist(em, synArtID);
                    SongEntity.updateArtistID(em, artistID, synArtID);
                }
                return null;
            }
        };
        templ.execute();
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
    private ArtistEntity findAndCheckArtist(EntityManager em, Long artistID)
    {
        if (artistID == null)
        {
            return null;
        }
        ArtistEntity e = find(em, ArtistEntity.class, artistID);
        checkUser(e.getUser());
        return e;
    }

    /**
     * Helper method for retrieving a song entity. This method also checks
     * whether the song belongs to the current user.
     *
     * @param em the entity manager
     * @param songID the ID of the song to be retrieved
     * @return the song entity with this ID
     * @throws EntityNotFoundException if the entity cannot be resolved
     * @throws IllegalStateException if the entity does not belong to the
     *         current user
     */
    private SongEntity findAndCheckSong(EntityManager em, String songID)
    {
        SongEntity song =
                find(em, SongEntity.class, KeyFactory.stringToKey(songID));
        checkUser(song.getUser());
        return song;
    }

    /**
     * Helper method for retrieving an album entity. This method also checks
     * whether the album belongs to the current user.
     *
     * @param em the entity manager
     * @param albumID the ID of the album to be retrieved
     * @return the album entity with this ID
     * @throws EntityNotFoundException if the entity cannot be resolved
     * @throws IllegalStateException if the entity does not belong to the
     *         current user
     */
    private AlbumEntity findAndCheckAlbum(EntityManager em, Long albumID)
    {
        if (albumID == null)
        {
            return null;
        }
        AlbumEntity album = find(em, AlbumEntity.class, albumID);
        checkUser(album.getUser());
        return album;
    }

    /**
     * Creates an object with detail information for the specified song entity.
     *
     * @param em the entity manager
     * @param song the song entity
     * @return the object with details information
     */
    private SongDetailInfo createSongDetailInfo(EntityManager em,
            SongEntity song)
    {
        SongEntityConverter converter = new SongEntityConverter();
        converter.setEntityManager(em);

        SongDetailInfo info = new SongDetailInfo();
        converter.convert(song, info);
        info.setSynonyms(ConvertUtils.extractSynonymNames(song.getSynonyms()));

        return info;
    }

    /**
     * Removes synonyms from the specified song entity.
     *
     * @param em the entity manager
     * @param song the song entity
     * @param removeSyns the synonym names to be removed
     */
    private void removeSongSynonyms(EntityManager em, SongEntity song,
            Set<String> removeSyns)
    {
        for (String syn : removeSyns)
        {
            SongSynonym ssyn = song.findSynonym(syn);

            if (ssyn != null)
            {
                song.removeSynonym(ssyn);
            }
        }
    }

    /**
     * Copies the synonyms from the given source song entity to the destination
     * song entity.
     *
     * @param dest the destination song
     * @param src the source song
     */
    private void copySongSynonyms(SongEntity dest, SongEntity src)
    {
        for (SongSynonym syn : src.getSynonyms())
        {
            dest.addSynonymName(syn.getName());
        }
        dest.addSynonymName(src.getName());
    }

    /**
     * Adds song entities as synonyms to the specified song entity. With this
     * method the entities declared as synonyms are merged with the current
     * song. After that they are removed.
     *
     * @param em the entity manager
     * @param song the current song
     * @param synonymIDs the IDs of the songs to become synonyms
     */
    private void addSongSynonyms(EntityManager em, SongEntity song,
            Set<String> synonymIDs)
    {
        for (String songID : synonymIDs)
        {
            SongEntity synSong = findAndCheckSong(em, songID);
            copySongSynonyms(song, synSong);
            em.remove(synSong);
        }
    }

    /**
     * Creates a detail info object for an album entity.
     *
     * @param em the entity manager
     * @param e the album entity
     * @return the detail info object
     */
    private AlbumDetailInfo createAlbumDetailInfo(EntityManager em,
            AlbumEntity e)
    {
        AlbumDetailInfo info = new AlbumDetailInfo();
        AlbumEntityConverter converter = new AlbumEntityConverter();
        List<SongEntity> songs = fetchSongsForAlbum(em, e, info);
        converter.convert(e, info, songs);
        info.setSynonyms(ConvertUtils.extractSynonymNames(e.getSynonyms()));
        fetchArtistsForAlbum(em, e, info, songs);

        return info;
    }

    /**
     * Initializes the list with {@link SongInfo} objects for the info object of
     * the given album entity.
     *
     * @param em the entity manager
     * @param e the album entity
     * @param info the info object for the album
     * @return a list with the song entities of this album
     */
    private List<SongEntity> fetchSongsForAlbum(EntityManager em,
            AlbumEntity e, AlbumDetailInfo info)
    {
        List<SongEntity> songs = Finders.findSongsByAlbum(em, e);
        SongEntityConverter conv = new SongEntityConverter();
        conv.initResolvedAlbums(Collections.singleton(e));
        conv.setEntityManager(em);
        info.setSongs(ConvertUtils.convertEntities(songs, conv));
        return songs;
    }

    /**
     * Initializes the list with {@link ArtistInfo} objects for the info object
     * of the given album entity. Basically, all artists the given songs refer
     * to are collected.
     *
     * @param em the entity manager
     * @param e the album entity
     * @param songs a list with all songs of this album
     * @return a list with the artist entities related to this album
     */
    private List<ArtistEntity> fetchArtistsForAlbum(EntityManager em,
            AlbumEntity e, AlbumDetailInfo info, List<SongEntity> songs)
    {
        List<ArtistEntity> artists = Finders.findArtistsForSongs(em, songs);
        info.setArtists(ConvertUtils.convertEntities(artists,
                ArtistEntityConverter.INSTANCE));
        return artists;
    }

    /**
     * Removes synonyms from a given album.
     *
     * @param em the entity manager
     * @param album the album affected by this operation
     * @param syns the set of the synonym names to be removed
     */
    private void removeAlbumSyonyms(EntityManager em, AlbumEntity album,
            Set<String> syns)
    {
        for (String syn : syns)
        {
            AlbumSynonym albumSynonym = album.findSynonym(syn);
            if (albumSynonym != null)
            {
                album.removeSynonym(albumSynonym);
                em.remove(albumSynonym);
            }
        }
    }

    /**
     * Adds new entities as synonyms to an album. All data of the new synonym
     * entities is added to the current album.
     *
     * @param em the entity manager
     * @param e the current album entity
     * @param newSynIDs a set with the IDs of the new synonym entities
     */
    private void addAlbumSynonyms(EntityManager em, AlbumEntity e,
            Set<Long> newSynIDs)
    {
        for (Long id : newSynIDs)
        {
            AlbumEntity synAlb = findAndCheckAlbum(em, id);
            copyAlbumSynonyms(e, synAlb);
            em.remove(synAlb);
        }
    }

    /**
     * Copies all synonyms from one album to another one.
     *
     * @param dest the destination album
     * @param src the source album
     */
    private void copyAlbumSynonyms(AlbumEntity dest, AlbumEntity src)
    {
        for (AlbumSynonym as : src.getSynonyms())
        {
            dest.addSynonymName(as.getName());
        }
        dest.addSynonymName(src.getName());
    }

    /**
     * Moves songs associated with one of a given set of albums to another
     * album. This method can be used for merging or removing albums.
     * Implementation note: Obviously this operation has to be performed in a
     * separate step; otherwise there were strange data nucleus exceptions.
     *
     * @param albumID the ID of the new destination album (can be <b>null</b> if
     *        albums are to be removed)
     * @param srcIDs a collection with the IDs of the source albums
     */
    private void moveAlbumSongs(final Long albumID,
            final Collection<Long> srcIDs)
    {
        JPATemplate<Void> templ = new JPATemplate<Void>(false)
        {
            @Override
            protected Void performOperation(EntityManager em)
            {
                findAndCheckAlbum(em, albumID);
                for (Long synAlbID : srcIDs)
                {
                    findAndCheckAlbum(em, synAlbID);
                    SongEntity.updateAlbumID(em, albumID, synAlbID);
                }
                return null;
            }
        };
        templ.execute();
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
}
