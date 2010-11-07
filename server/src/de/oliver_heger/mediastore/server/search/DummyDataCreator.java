package de.oliver_heger.mediastore.server.search;

import java.util.List;

import javax.persistence.EntityManager;

import de.oliver_heger.mediastore.server.db.JPATemplate;
import de.oliver_heger.mediastore.shared.model.Artist;

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
            "Within Temptation", "Yellow", "ZZ Top"
    };

    /** Constant for the user parameter. */
    private static final String PARAM_USER = "user";

    /** Constant for the name parameter. */
    private static final String PARAM_NAME = "name";

    /** Constant for a query for searching for an artist. */
    private static final String QUERY_ARTIST = "select a from Artist a "
            + "where a.name = :" + PARAM_NAME + " and a.userID = :"
            + PARAM_USER;

    /** The ID of the current user. */
    private final String userID;

    /**
     * Creates a new instance of {@code DummyDataCreator} and sets the ID of the
     * current user.
     *
     * @param uid the user ID
     */
    public DummyDataCreator(String uid)
    {
        userID = uid;
    }

    /**
     * Returns the ID of the current user.
     *
     * @return the user ID
     */
    public String getUserID()
    {
        return userID;
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
    private Artist findArtist(EntityManager em, String name)
    {
        @SuppressWarnings("unchecked")
        List<Artist> list =
                em.createQuery(QUERY_ARTIST)
                        .setParameter(PARAM_USER, getUserID())
                        .setParameter(PARAM_NAME, name).getResultList();
        return list.isEmpty() ? null : list.get(0);
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
                    Artist art = new Artist();
                    art.setName(name);
                    art.setUserID(getUserID());
                    em.persist(art);
                }
                return null;
            }
        };
        templ.execute();
    }
}
