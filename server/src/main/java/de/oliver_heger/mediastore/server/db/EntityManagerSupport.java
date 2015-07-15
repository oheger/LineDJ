package de.oliver_heger.mediastore.server.db;

import javax.persistence.EntityManager;

/**
 * <p>
 * Definition of an interface for objects that support injection of an
 * {@code EntityManager}.
 * </p>
 * <p>
 * This interface defines a method which expects an {@code EntityManager} as
 * argument. The idea is that objects which require an entity manager can
 * implement this interface. Code dealing with such objects has to ensure that
 * an entity manager is injected before the object is actually used.
 * </p>
 *
 * @author Oliver Heger
 * @version $Id: $
 */
public interface EntityManagerSupport
{
    /**
     * Passes the {@code EntityManager}.
     *
     * @param em the {@code EntityManager}
     */
    void setEntityManager(EntityManager em);
}
