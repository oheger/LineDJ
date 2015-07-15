package de.oliver_heger.mediastore.server.convert;

import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;

import javax.persistence.EntityManager;

import de.oliver_heger.mediastore.server.db.EntityManagerSupport;
import de.oliver_heger.mediastore.server.model.AlbumEntity;
import de.oliver_heger.mediastore.server.model.SongEntity;
import de.oliver_heger.mediastore.service.utils.DTOTransformer;
import de.oliver_heger.mediastore.shared.model.AlbumInfo;

/**
 * <p>
 * A specialized {@link EntityConverter} implementation for converting
 * {@link AlbumEntity} objects.
 * </p>
 * <p>
 * This converter also has to deal with some fields of {@link AlbumInfo} which
 * are calculated based on the songs associated with the album. For this purpose
 * it must be possible to access the songs. They are either specified before the
 * conversion starts or can be loaded dynamically through an
 * {@code EntityManager} provided to an instance.
 * </p>
 * <p>
 * Implementation note: This class is not thread-safe. It is assumed that for
 * each server request a separate instance is created.
 * </p>
 *
 * @author Oliver Heger
 * @version $Id: $
 */
public class AlbumEntityConverter implements
        EntityConverter<AlbumEntity, AlbumInfo>, EntityManagerSupport
{
    /** A map with information about songs and their association with albums. */
    private final Map<Long, List<SongEntity>> albumSongMap;

    /** Stores the reference to the entity manager. */
    private EntityManager entityManager;

    /**
     * Creates a new instance of {@code AlbumEntityConverter}.
     */
    public AlbumEntityConverter()
    {
        albumSongMap = new HashMap<Long, List<SongEntity>>();
    }

    /**
     * Returns the entity manager used by this object. Result can be <b>null</b>
     * if no entity manager has been set.
     *
     * @return the entity manager
     */
    public final EntityManager getEntityManager()
    {
        return entityManager;
    }

    /**
     * Injects an entity manager. This entity manager is used for loadings the
     * songs of an album.
     *
     * @param em the entity manager
     */
    @Override
    public final void setEntityManager(EntityManager em)
    {
        entityManager = em;
    }

    /**
     * Initializes information about the songs of the albums to be converted.
     * The songs passed to this method are used to initialize the properties of
     * {@link AlbumInfo} objects related to the songs of the album.
     *
     * @param songs a collection with all songs
     */
    public void initializeSongData(Collection<? extends SongEntity> songs)
    {
        albumSongMap.clear();

        for (SongEntity song : songs)
        {
            List<SongEntity> albumSongs = albumSongMap.get(song.getAlbumID());
            if (albumSongs == null)
            {
                albumSongs = new LinkedList<SongEntity>();
                albumSongMap.put(song.getAlbumID(), albumSongs);
            }
            albumSongs.add(song);
        }
    }

    /**
     * Converts the specified {@link AlbumEntity} object into an
     * {@link AlbumInfo} object.
     *
     * @param the entity to be converted
     * @return the result of the conversion
     * @throws IllegalArgumentException if the entity is <b>null</b>
     */
    @Override
    public AlbumInfo convert(AlbumEntity e)
    {
        AlbumInfo info = new AlbumInfo();
        convert(e, info);
        return info;
    }

    /**
     * Initializes the specified {@link AlbumInfo} object with the data of the
     * given {@link AlbumEntity}. For populating the fields related to the songs
     * of the album, this method tries to load the songs. If this fails, the
     * fields are not initialized.
     *
     * @param e the entity to be converted
     * @param info the resulting info object
     * @throws IllegalArgumentException if one of the parameters is <b>null</b>
     */
    public void convert(AlbumEntity e, AlbumInfo info)
    {
        convert(e, info, fetchSongsForAlbum(e));
    }

    /**
     * Initializes the specified {@link AlbumInfo} object with the data of the
     * given {@link AlbumEntity} using the specified collection of songs to
     * initialize the fields related to the album's songs.
     *
     * @param e the entity to be converted
     * @param info the resulting info object
     * @param songs a collection with the songs of the album (may be
     *        <b>null</b>)
     */
    public void convert(AlbumEntity e, AlbumInfo info,
            Collection<? extends SongEntity> songs)
    {
        DTOTransformer.transform(e, info);
        info.setAlbumID(e.getId());

        if (songs != null)
        {
            info.setNumberOfSongs(songs.size());
            info.setDuration(calcAlbumDuration(songs));
        }
        info.setInceptionYear(calcAlbumInceptionYear(e, songs));
    }

    /**
     * Queries the songs for the specified album. This method executes a
     * corresponding query on the injected entity manager. If no entity manager
     * is available, result is <b>null</b>.
     *
     * @param e the album entity
     * @return the songs for this album
     */
    List<SongEntity> querySongs(AlbumEntity e)
    {
        if (getEntityManager() == null)
        {
            return null;
        }

        return loadSongs(e);
    }

    /**
     * Loads the songs associated with the specified album. This method is
     * called if an entity manager is available and song data has to be
     * retrieved.
     *
     * @param e the album entity
     * @return the songs for this album
     */
    List<SongEntity> loadSongs(AlbumEntity e)
    {
        return SongEntity.findByAlbum(getEntityManager(), e);
    }

    /**
     * Tries to obtain the songs for the specified album. This method checks
     * whether the songs for this album are already stored in the internal
     * mapping. If yes, they are directly returned. Otherwise,
     * {@link #querySongs(AlbumEntity)} is invoked in order to load the songs
     * from the database.
     *
     * @return the songs for this album (not <b>null</b>)
     */
    private List<SongEntity> fetchSongsForAlbum(AlbumEntity e)
    {
        List<SongEntity> songs = albumSongMap.get(e.getId());

        if (songs == null)
        {
            songs = querySongs(e);
            if (songs == null)
            {
                songs = Collections.emptyList();
            }
            albumSongMap.put(e.getId(), songs);
        }

        return songs;
    }

    /**
     * Calculates the duration of the album from the songs. If a song has an
     * unknown duration, the whole duration is unknown.
     *
     * @param songs the collection of songs
     * @return the duration of the album or <b>null</b> if it cannot be
     *         determined
     */
    private static Long calcAlbumDuration(Collection<? extends SongEntity> songs)
    {
        if (songs.isEmpty())
        {
            return null;
        }

        long duration = 0;
        for (SongEntity song : songs)
        {
            if (song.getDuration() == null)
            {
                return null;
            }
            duration += song.getDuration().longValue();
        }

        return Long.valueOf(duration);
    }

    /**
     * Determines the inception year of the album. If the entity has an
     * inception year set, it is used. Otherwise, it is tried to determine the
     * year from the songs.
     *
     * @param e the album entity
     * @param songs the collection of songs
     * @return the inception year of the album or <b>null</b> if it cannot be
     *         determined
     */
    private static Integer calcAlbumInceptionYear(AlbumEntity e,
            Collection<? extends SongEntity> songs)
    {
        Integer year = e.getInceptionYear();
        if (year == null && songs != null)
        {
            year = calcAlbumInceptionYearFromSongs(songs);
        }

        return year;
    }

    /**
     * Determines the inception year of the album based on the songs. The
     * inception year is set only if all songs have the same - non <b>null</b> -
     * inception year.
     *
     * @param songs the collection of songs
     * @return the inception year of the album or <b>null</b> if it cannot be
     *         determined
     */
    private static Integer calcAlbumInceptionYearFromSongs(
            Collection<? extends SongEntity> songs)
    {
        Integer year = null;
        for (SongEntity song : songs)
        {
            if (song.getInceptionYear() == null)
            {
                return null;
            }
            if (year == null)
            {
                year = song.getInceptionYear();
            }
            else
            {
                if (!year.equals(song.getInceptionYear()))
                {
                    return null;
                }
            }
        }

        return year;
    }
}
