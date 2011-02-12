package de.oliver_heger.mediastore.server.search;

import java.util.List;
import java.util.Random;

import javax.persistence.EntityManager;

import com.google.appengine.api.users.User;

import de.oliver_heger.mediastore.server.db.JPATemplate;
import de.oliver_heger.mediastore.server.model.AlbumEntity;
import de.oliver_heger.mediastore.server.model.ArtistEntity;
import de.oliver_heger.mediastore.server.model.SongEntity;

/**
 * A class for creating test data. This class is used temporarily to populate
 * the database with test data before the actual import functionality is
 * implemented.
 *
 * @author Oliver Heger
 * @version $Id: $
 */
class DummyDataCreator
{
    /** An array with the names of test artists. */
    private static final String[] ARTIST_NAMES = {
            "AC/DC", "Adam Ant", "Bryan Adams", "Culture Club", "Dido",
            "Eddy Grant", "Evanescense", "Four Non Blonds", "Greenday",
            "Heroes del Silencio", "Indica", "Jekyl", "Kim Wild",
            "Lisa Standsfield", "Marillion", "Mike Oldfield", "Nightwish",
            "OMD", "Pearl Jam", "Queen", "REO Speedwagon", "Supertramp",
            "The Sisters of Mercy", "U2", "Van Canto", "Van Halen", "Vangelis",
            "Within Temptation", "Yellow", "ZZ Top", "Elvis", "Elviz",
            "Elvis Presley", "The King"
    };

    /** An array with the data about test songs. */
    private static final String[] SONG_DATA = {
            "Thunderstrike|0|0", "TNT|0|0", "Highway to Hell|0|1",
            "Run to You|2", "Summer of 69|2",
            "Do you really want to heart me?|3|2", "War Song|3|2",
            "Thank You|4", "All that you want|4|3", "Electric Avenue|5|4",
            "Gimme hope, Johanna|5|4", "Come to live|6|5",
            "What's going on?|7", "American idiot|8|6",
            "21st Century Breakdown|8|7", "Entre dos tierros|9",
            "Kids in America|12", "Go for the second time|12", "Fugazzi|14|11",
            "A script for a Jester's tear|14", "Grendel|14|14",
            "Tubular Bells|15|15", "Moonlight Shaddow|15|16",
            "To France|15|17", "Wishmaster|16|20",
            "Over the hills and far away|16|19", "Pandorras Box|17",
            "Inuendo|19", "Bohemien Rapsody|19",
            "Princes of the Universe|19|21", "Roll with the changes|20",
            "Rocking all over the world|21", "More|22|23",
            "Temple of Love|22|23", "Pride|23", "With or without you|23",
            "Jump|25", "Conquest of Paradise|26", "Mother Earth|27",
            "The Race|28", "Gimme all your loving|29|24", "LeGrange|29|24",
            "Return to sender|30", "Heartbreak Hotel|31", "Love me tender|31"
    };

    /** An array with data about test albums. */
    private static final String[] ALBUM_NAMES = {
            "High Voltage", "Jailbreak", "Kissing to be clever",
            "Long way home", "Electric Avenue", "Fallen", "American idiot",
            "21st century breakdown", "El espiritu del vino", "A way away",
            "Come and play", "Fugazzi", "Misplaced Childhood",
            "Brief encounter", "B-sides themselves", "Tubular Bells", "Crisis",
            "Discovery", "Islands", "Over the hills and far away",
            "Wishmastour", "A kind of magic", "A night at the opera",
            "Floodland", "Texas"
    };

    /** Constant for the user parameter. */
    private static final String PARAM_USER = "user";

    /** Constant for the name parameter. */
    private static final String PARAM_NAME = "name";

    /** Constant for a query for searching for an artist. */
    private static final String QUERY_ARTIST = "select a from ArtistEntity a "
            + "where a.name = :" + PARAM_NAME + " and a.user = :" + PARAM_USER;

    /** Constant for a query for searching for an album. */
    private static final String QUERY_ALBUM = "select a from AlbumEntity a "
            + "where a.name = :" + PARAM_NAME + " and a.user = :" + PARAM_USER;

    /** Constant for a query for searching for a song. */
    private static final String QUERY_SONG = "select s from SongEntity s "
            + "where s.name = :" + PARAM_NAME + " and s.user = :" + PARAM_USER;

    /** The current user. */
    private final User user;

    /** A random object. */
    private final Random random;

    /**
     * Creates a new instance of {@code DummyDataCreator} and sets the current
     * user.
     *
     * @param usr the user
     */
    public DummyDataCreator(User usr)
    {
        user = usr;
        random = new Random();
    }

    /**
     * Returns the current user.
     *
     * @return the user
     */
    public User getUser()
    {
        return user;
    }

    /**
     * Creates test data in the database for the currently logged-in user.
     */
    public void createTestData()
    {
        for (String n : ARTIST_NAMES)
        {
            createArtist(n);
        }

        for (String n : ALBUM_NAMES)
        {
            createAlbum(n);
        }

        for (String d : SONG_DATA)
        {
            createSong(d);
        }
    }

    /**
     * Helper method for searching an entity by its name.
     *
     * @param em the entity manager
     * @param query the query to be executed
     * @param name the name of the entity
     * @return the found entity or <b>null</b> if the name cannot be resolved
     */
    private Object findEntityByName(EntityManager em, String query, String name)
    {
        List<?> list =
                em.createQuery(query).setParameter(PARAM_USER, getUser())
                        .setParameter(PARAM_NAME, name).getResultList();
        return list.isEmpty() ? null : list.get(0);
    }

    /**
     * Searches for an artist with a given name. If the artist cannot be found,
     * result is <b>null</b>.
     *
     * @param em the entity manager
     * @param name the name of the artist
     * @return the corresponding Artist object or <b>null</b> if it cannot be
     *         resolved
     */
    private ArtistEntity findArtist(EntityManager em, String name)
    {
        return (ArtistEntity) findEntityByName(em, QUERY_ARTIST, name);
    }

    /**
     * Creates an entity for an artist with the given name. This method checks
     * if this artist already exists. If this is the case, it has no effect.
     * Otherwise the artist is created now.
     *
     * @param name the name of the artist
     */
    private void createArtist(final String name)
    {
        JPATemplate<Void> templ = new JPATemplate<Void>()
        {
            @Override
            protected Void performOperation(EntityManager em)
            {
                if (findArtist(em, name) == null)
                {
                    LOG.info("Creating Artist instance for " + name);
                    ArtistEntity art = new ArtistEntity();
                    art.setName(name);
                    art.setUser(getUser());
                    em.persist(art);
                }
                return null;
            }
        };
        templ.execute();
    }

    /**
     * Searches for an album with a given name. If the album cannot be found,
     * result is <b>null</b>.
     *
     * @param em the entity manager
     * @param name the name of the album
     * @return the corresponding album object or <b>null</b> if it cannot be
     *         resolved
     */
    private AlbumEntity findAlbum(EntityManager em, String name)
    {
        return (AlbumEntity) findEntityByName(em, QUERY_ALBUM, name);
    }

    /**
     * Creates an entity for an album with the given name.
     *
     * @param name the name of the album
     */
    private void createAlbum(final String name)
    {
        JPATemplate<Void> templ = new JPATemplate<Void>()
        {
            @Override
            protected Void performOperation(EntityManager em)
            {
                if (findAlbum(em, name) == null)
                {
                    LOG.info("Creating Album instance for " + name);
                    AlbumEntity album = new AlbumEntity();
                    album.setName(name);
                    album.setUser(getUser());
                    em.persist(album);
                }
                return null;
            }
        };
        templ.execute();
    }

    /**
     * Creates a song entity.
     *
     * @param data the data for the song
     */
    private void createSong(String data)
    {
        String[] songData = data.split("\\|");
        final String name = songData[0];
        final String artistName = ARTIST_NAMES[Integer.parseInt(songData[1])];
        final String albumName =
                (songData.length < 3) ? null : ALBUM_NAMES[Integer
                        .parseInt(songData[2])];
        JPATemplate<Void> templ = new JPATemplate<Void>(false)
        {
            @Override
            protected Void performOperation(EntityManager em)
            {
                if (findEntityByName(em, QUERY_SONG, name) == null)
                {
                    LOG.info("Creating Song instance for " + name);
                    SongEntity song = new SongEntity();
                    song.setName(name);
                    song.setDuration(Long.valueOf(random.nextInt(600000) + 30000));
                    song.setInceptionYear(1970 + random.nextInt(40));
                    song.setUser(getUser());
                    ArtistEntity artist = findArtist(em, artistName);
                    if (artist != null)
                    {
                        song.setArtistID(artist.getId());
                    }
                    AlbumEntity album =
                            (albumName != null) ? findAlbum(em, albumName)
                                    : null;
                    if (album != null)
                    {
                        song.setAlbumID(album.getId());
                    }
                    em.persist(song);
                }
                return null;
            }
        };
        templ.execute();
    }
}
