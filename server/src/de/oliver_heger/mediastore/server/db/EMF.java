package de.oliver_heger.mediastore.server.db;

import javax.persistence.EntityManager;
import javax.persistence.EntityManagerFactory;
import javax.persistence.Persistence;

/**
 * <p>
 * An utility class for managing an {@code EntityManagerFactory}.
 * </p>
 * <p>
 * Through the static methods provided by this class a reference to the default
 * {@code EntityManagerFactory} can be obtained. All code on the server that
 * needs to access the database can obtain a factory through this class.
 * </p>
 *
 * @author Oliver Heger
 * @version $Id: $
 */
public final class EMF
{
    /** Constant for the name of the default persistence unit. */
    private static final String PERSISTENCE_UNIT = "remoteMediaStore";

    /** The central, shared entity manager factory instance. */
    private static final EntityManagerFactory FACTORY = Persistence
            .createEntityManagerFactory(PERSISTENCE_UNIT);

    /**
     * Private constructor so that no instances can be created.
     */
    private EMF()
    {
    }

    /**
     * Returns the reference to the central {@code EntityManagerFactory}.
     *
     * @return the {@code EntityManagerFactory}
     */
    public static EntityManagerFactory getFactory()
    {
        return FACTORY;
    }

    /**
     * Convenience method for creating an {@code EntityManager}. This method
     * creates a new {@code EntityManager} using the central
     * {@code EntityManagerFactory}.
     *
     * @return the new {@code EntityManager}
     */
    public static EntityManager createEM()
    {
        return getFactory().createEntityManager();
    }
}
