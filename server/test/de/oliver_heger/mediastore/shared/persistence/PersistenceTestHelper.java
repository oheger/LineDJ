package de.oliver_heger.mediastore.shared.persistence;



import javax.persistence.EntityManager;
import javax.persistence.EntityTransaction;

import com.google.appengine.tools.development.testing.LocalServiceTestConfig;
import com.google.appengine.tools.development.testing.LocalServiceTestHelper;

import de.oliver_heger.mediastore.server.db.EMF;
import de.oliver_heger.mediastore.shared.RemoteMediaStoreTestHelper;

/**
 * A helper class providing common functionality for testing AppEngine
 * applications. There is a set of static utility methods which can be used for
 * testing different aspects of entity implementations. An {@code EntityManager}
 * instance can also be maintained. Management of AppEngine-specific helper
 * objects is implemented, too.
 *
 * @author Oliver Heger
 * @version $Id: $
 */
public class PersistenceTestHelper extends RemoteMediaStoreTestHelper
{
    /** A test user ID. */
    public static final String USER = "testUser";

    /**
     * Constant for another user ID that is different from the first test user
     * ID.
     */
    public static final String OTHER_USER = "testUser2";

    /** The AppEngine-related test helper. */
    private final LocalServiceTestHelper helper;

    /** Stores the current entity manager. */
    private EntityManager em;

    /**
     * Creates a new instance of {@code PersistenceTestHelper} and initializes
     * it with test configurations for the services provided by the AppEngine.
     *
     * @param configs the test configuration objects
     */
    public PersistenceTestHelper(LocalServiceTestConfig... configs)
    {
        helper = new LocalServiceTestHelper(configs);
    }

    /**
     * Initializes this object.
     */
    public void setUp()
    {
        helper.setEnvIsLoggedIn(true);
        helper.setEnvEmail(USER);
        helper.setEnvAuthDomain("www.testauthdomain.com");
        helper.setUp();
    }

    /**
     * Performs cleanup. This method should be called after execution of a test
     * case.
     */
    public void tearDown()
    {
        helper.tearDown();
        closeEM();
    }

    /**
     * Returns the AppEngine-specific test helper object.
     *
     * @return the {@code LocalServiceTestHelper} object
     */
    public LocalServiceTestHelper getLocalServiceTestHelper()
    {
        return helper;
    }

    /**
     * Closes the current entity manager if it exists.
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
     * Returns the current entity manager. It is created on demand.
     *
     * @return the entity manager
     */
    public EntityManager getEM()
    {
        if (em == null)
        {
            em = EMF.createEM();
        }
        return em;
    }

    /**
     * Returns the current transaction.
     *
     * @return the current transaction
     */
    public EntityTransaction getTransaction()
    {
        return getEM().getTransaction();
    }

    /**
     * Convenience method to start a new transaction on the current entity
     * manager.
     */
    public void begin()
    {
        getTransaction().begin();
    }

    /**
     * Convenience method to commit the current transaction.
     */
    public void commit()
    {
        getTransaction().commit();
    }

    /**
     * Persists the passed in object. Optionally the operation is performed in a
     * new transaction.
     *
     * @param obj the object to be persisted
     * @param tx a flag whether a new transaction is to be used
     */
    public void persist(Object obj, boolean tx)
    {
        if (tx)
        {
            begin();
        }
        getEM().persist(obj);
        if (tx)
        {
            commit();
        }
    }

    /**
     * Persists the passed in object in a new transaction. This is a short form
     * of {@code persist(obj, true)}.
     *
     * @param obj the object to be persisted
     */
    public void persist(Object obj)
    {
        persist(obj, true);
    }
}
