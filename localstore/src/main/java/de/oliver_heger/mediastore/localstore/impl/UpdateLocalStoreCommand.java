package de.oliver_heger.mediastore.localstore.impl;

import javax.persistence.EntityManager;
import javax.persistence.EntityManagerFactory;

import org.apache.commons.lang3.concurrent.ConcurrentInitializer;

import de.oliver_heger.mediastore.localstore.model.AlbumEntity;
import de.oliver_heger.mediastore.localstore.model.ArtistEntity;
import de.oliver_heger.mediastore.localstore.model.SongEntity;
import de.oliver_heger.mediastore.service.SongData;

/**
 * <p>
 * A specialized command implementation for updating the local data storage with
 * information about a song.
 * </p>
 * <p>
 * This command is used when information about a new song becomes available. The
 * command checks whether this song (and also its artist and album if known) is
 * already contained in the local database. If not, the missing information is
 * added. Otherwise the play counter is increased.
 * </p>
 *
 * @author Oliver Heger
 * @version $Id: $
 */
class UpdateLocalStoreCommand extends JPACommand
{
    /** Stores the song data object defining the song to be updated. */
    private final SongData songData;

    /**
     * Creates a new instance of {@code UpdateLocalStoreCommand} and initializes
     * it with the {@code EntityManagerFactory} and the object with the song
     * information.
     *
     * @param emfInit the initializer for the {@code EntityManagerFactory} (must
     *        not be <b>null</b>)
     * @param data the data object for the song to be updated (must not be
     *        <b>null</b>)
     * @throws NullPointerException if a required parameter is missing
     */
    public UpdateLocalStoreCommand(
            ConcurrentInitializer<EntityManagerFactory> emfInit, SongData data)
    {
        super(emfInit, false);
        if (data == null)
        {
            throw new NullPointerException("SongData must not be null!");
        }
        songData = data;
    }

    /**
     * Returns the {@code SongData} object which is processed by this command.
     *
     * @return the managed song data object
     */
    public SongData getSongData()
    {
        return songData;
    }

    /**
     * Implements the logic of this command. This implementation checks which of
     * the entities specified by the current {@code SongData} object already
     * exist in the local database. Entities that are not yet present are newly
     * created.
     *
     * @param em the {@code EntityManager}
     */
    @Override
    protected void executeJPAOperation(EntityManager em)
    {
        SongEntity song =
                Finders.findSong(em, getSongData().getName(),
                        toInteger(getSongData().getDuration()),
                        getSongData().getArtistName());

        if (song == null)
        {
            if (getLog().isInfoEnabled())
            {
                getLog().info(
                        "Creating new SongEntity for song "
                                + getSongData().getName());
            }

            song = createEntityFromSongData(em);
            em.persist(song);
        }

        song.incrementPlayCount();
    }

    /**
     * Creates a {@link SongEntity} object from the {@code SongData} object of
     * this command.
     *
     * @param em the {@code EntityManager}
     * @return the {@link SongEntity}
     */
    private SongEntity createEntityFromSongData(EntityManager em)
    {
        SongEntity song;
        song = new SongEntity();
        song.setDuration(toInteger(getSongData().getDuration()));
        song.setInceptionYear(toInteger(getSongData().getInceptionYear()));
        song.setName(getSongData().getName());
        song.setTrackNo(toInteger(getSongData().getTrackNo()));

        if (getSongData().getArtistName() != null)
        {
            ArtistEntity artist = fetchOrCreateArtist(em);
            artist.addSong(song);
        }

        if (getSongData().getAlbumName() != null)
        {
            AlbumEntity album = fetchOrCreateAlbum(em);
            album.addSong(song);
        }
        return song;
    }

    /**
     * Handles the artist of the current song data object. If necessary, a new
     * instance is created.
     *
     * @param em the {@code EntityManager}
     * @return the corresponding artist entity
     */
    private ArtistEntity fetchOrCreateArtist(EntityManager em)
    {
        ArtistEntity artist = Finders.findArtist(em, getSongData().getArtistName());

        if (artist == null)
        {
            if (getLog().isInfoEnabled())
            {
                getLog().info(
                        "Creating new ArtistEntity for "
                                + getSongData().getArtistName());
            }

            artist = new ArtistEntity();
            artist.setName(getSongData().getArtistName());
            em.persist(artist);
        }

        return artist;
    }

    /**
     * Handles the album of the current song data object. If necessary, a new
     * instance is created.
     *
     * @param em the {@code EntityManager}
     * @return the corresponding album entity
     */
    private AlbumEntity fetchOrCreateAlbum(EntityManager em)
    {
        Integer year = toInteger(getSongData().getInceptionYear());
        AlbumEntity album =
                Finders.findAlbum(em, getSongData().getAlbumName(), year);

        if (album == null)
        {
            if (getLog().isInfoEnabled())
            {
                getLog().info(
                        "Creating new AlbumEntity for "
                                + getSongData().getAlbumName());
            }

            album = new AlbumEntity();
            album.setName(getSongData().getAlbumName());
            album.setInceptionYear(year);
            em.persist(album);
        }

        return album;
    }

    /**
     * Helper method for transforming a number to an integer value. This is
     * needed for dealing with the BigInteger properties of the song data
     * object.
     *
     * @param num the number (may be <b>null</b>)
     * @return the corresponding Integer object
     */
    private static Integer toInteger(Number num)
    {
        return (num != null) ? Integer.valueOf(num.intValue()) : null;
    }
}
