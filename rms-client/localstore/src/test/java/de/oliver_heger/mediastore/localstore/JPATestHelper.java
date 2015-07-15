package de.oliver_heger.mediastore.localstore;

import javax.persistence.EntityManager;
import javax.persistence.EntityManagerFactory;
import javax.persistence.Persistence;

/**
 * <p>
 * A helper class for tests using the database.
 * </p>
 * <p>
 * This class wraps an entity manager factory which points to a HSQLD in-memory
 * database. It provides some convenience methods for accessing functionality of
 * the entity manager.
 * </p>
 *
 * @author Oliver Heger
 * @version $Id: $
 */
public class JPATestHelper
{
    /** Constant for the default name of the persistence unit. */
    private static final String DEFAULT_UNIT_NAME = "localstore-test";

    /** The name of the persistence unit. */
    private final String unitName;

    /** The entity manager factory. */
    private EntityManagerFactory emf;

    /** The current entity manager. */
    private EntityManager em;

    /**
     * Creates a new instance of {@code JPATestHelper} and sets the name of the
     * persistence unit.
     *
     * @param unit the name of the persistence unit
     */
    public JPATestHelper(String unit)
    {
        unitName = unit;
    }

    /**
     * Creates a new instance of {@code JPATestHelper} which uses the standard
     * persistence unit.
     */
    public JPATestHelper()
    {
        this(DEFAULT_UNIT_NAME);
    }

    /**
     * Returns the entity manager factory. It is created on demand.
     *
     * @return the entity manager factory
     */
    public EntityManagerFactory getEMF()
    {
        if (emf == null)
        {
            emf = Persistence.createEntityManagerFactory(unitName);
        }
        return emf;
    }

    /**
     * Returns the current entity manager. It is created on demand.
     *
     * @return the current entity manager
     */
    public EntityManager getEM()
    {
        if (em == null)
        {
            em = getEMF().createEntityManager();
        }
        return em;
    }

    /**
     * Closes the current entity manager if one is open.
     */
    public void closeEM()
    {
        if (em != null)
        {
            em.close();
            em = null;
        }
    }

    /**
     * Closes this object and frees all resources. This method should be called
     * in the tearDown() method of a test class.
     */
    public void close()
    {
        closeEM();
        if (emf != null)
        {
            emf.close();
        }
    }

    /**
     * Starts a new transaction.
     */
    public void begin()
    {
        getEM().getTransaction().begin();
    }

    /**
     * Commits a transaction.
     */
    public void commit()
    {
        getEM().getTransaction().commit();
    }

    /**
     * Convenience method for persisting an entity. If the transaction flag is
     * set, a new transaction is started and committed after the object was
     * persisted.
     *
     * @param entity the entity to be persisted
     * @param tx the transaction flag
     */
    public void persist(Object entity, boolean tx)
    {
        if (tx)
        {
            begin();
        }
        getEM().persist(entity);
        if (tx)
        {
            commit();
        }
    }
}
