package de.oliver_heger.mediastore.localstore.impl;

import javax.persistence.EntityManager;
import javax.persistence.EntityManagerFactory;
import javax.persistence.EntityTransaction;

import net.sf.jguiraffe.gui.cmd.CommandBase;

import org.apache.commons.lang3.concurrent.ConcurrentInitializer;
import org.apache.commons.lang3.concurrent.ConcurrentUtils;

/**
 * <p>
 * A specialized base class for <em>command</em> objects that need to execute
 * JPA operations.
 * </p>
 * <p>
 * This base class allows the execution of code which needs an
 * {@code EntityManager} in the default command queue worker thread of the
 * application. Creation and disposal of the {@code EntityManager} and
 * transaction handling are done behind the scenes by the base class. A concrete
 * subclass has to implement the {@link #executeJPAOperation(EntityManager)}
 * method. Here the {@code EntityManager} passed to the method can be directly
 * used. A transaction has already been started. After the execution of this
 * method the transaction is committed. If an error occurs, the transaction is
 * rolled back. Further the class ensures that the {@code EntityManager}
 * instance is always closed.
 * </p>
 * <p>
 * At construction time a {@code ConcurrentInitializer} for the
 * {@code EntityManagerFactory} is passed. At the beginning of the command
 * execution the factory is obtained from this initializer. An application could
 * use a background initializer for instance, so that the complex operation of
 * setting up the {@code EntityManagerFactory} is performed in a background
 * thread.
 * </p>
 *
 * @author Oliver Heger
 * @version $Id: $
 */
abstract class JPACommand extends CommandBase
{
    /** The initializer for the entity manager factory. */
    private final ConcurrentInitializer<EntityManagerFactory> factoryInitializer;

    /** The current entity manager instance. */
    private EntityManager entityManager;

    /**
     * Creates a new instance of {@code JPACommand} and initializes it with the
     * initializer for the {@code EntityManagerFactory}.
     *
     * @param emfInit the initializer for the entity manager factory (must not
     *        be <b>null</b>)
     * @throws NullPointerException if the factory initializer is <b>null</b>
     */
    protected JPACommand(ConcurrentInitializer<EntityManagerFactory> emfInit)
    {
        factoryInitializer = checkFactory(emfInit);
    }

    /**
     * Creates a new instance of {@code JPACommand}, initializes it with the
     * {@code EntityManagerFactory} and sets the flag whether the UI should be
     * updated after the command's execution in the background thread.
     *
     * @param emf the entity manager factory (must not be <b>null</b>)
     * @throws NullPointerException if the factory is <b>null</b>
     * @param updateUI the update UI flag
     */
    protected JPACommand(ConcurrentInitializer<EntityManagerFactory> emfInit,
            boolean updateUI)
    {
        super(updateUI);
        factoryInitializer = checkFactory(emfInit);
    }

    /**
     * Returns the {@code EntityManagerFactory} used by this command object.
     * This method accesses the initializer passed to the constructor for
     * obtaining the factory. Note that depending on the concrete initializer
     * used this may be a blocking call.
     *
     * @return the {@code EntityManagerFactory}
     */
    public final EntityManagerFactory getEntityManagerFactory()
    {
        return ConcurrentUtils.initializeUnchecked(factoryInitializer);
    }

    /**
     * Executes this command in a background thread. This implementation obtains
     * an {@code EntityManager} from the current {@code EntityManagerFactory}
     * and starts a transaction. Then it delegates to
     * {@link #executeJPAOperation(EntityManager)} to actually execute the JPA
     * operation. Finally a commit is performed.
     *
     * @throws Exception if an error occurs
     */
    @Override
    public void execute() throws Exception
    {
        entityManager = getEntityManagerFactory().createEntityManager();
        begin();
        executeJPAOperation(entityManager);
        commit();
    }

    /**
     * Handles exceptions. This implementation performs a rollback.
     */
    @Override
    public void onException(Throwable t)
    {
        super.onException(t);
        if (getEntityManager() != null)
        {
            rollback();
        }
    }

    /**
     * Performs cleanup after the execution of the command. This implementation
     * closes the current {@code EntityManager}.
     */
    @Override
    public void onFinally()
    {
        if (getEntityManager() != null)
        {
            getEntityManager().close();
        }
    }

    /**
     * Returns the current {@code EntityManager} instance. The
     * {@code EntityManager} is defined only during the execution of the command
     * in the background thread. Before that and after that this method returns
     * <b>null</b>.
     *
     * @return the current {@code EntityManager} instance
     */
    protected EntityManager getEntityManager()
    {
        return entityManager;
    }

    /**
     * Returns the current {@code EntityManager} instance and checks whether it
     * exists. This method cannot be called before {@link #execute()}. If no
     * {@code EntityManager} has been created yet, an exception is thrown.
     *
     * @return the current {@code EntityManager} instance
     * @throws IllegalStateException if no {@code EntityManager} is available
     *         yet
     */
    protected EntityManager fetchEntityManager()
    {
        if (getEntityManager() == null)
        {
            throw new IllegalStateException(
                    "Entity manager not available!"
                            + "This method can only be called during command execution.");
        }
        return getEntityManager();
    }

    /**
     * Starts a transaction on the current {@code EntityManager}. Normally this
     * method does not have to be called by derived classes because transaction
     * handling is done by the base class.
     *
     * @throws IllegalStateException if no {@code EntityManager} is available
     */
    protected void begin()
    {
        getLog().debug("Starting transaction.");
        fetchTransaction().begin();
    }

    /**
     * Commits a transaction on the current {@code EntityManager}. Normally this
     * method does not have to be called by derived classes because transaction
     * handling is done by the base class.
     *
     * @throws IllegalStateException if no {@code EntityManager} is available
     */
    protected void commit()
    {
        getLog().debug("Committing transaction.");
        fetchTransaction().commit();
    }

    /**
     * Rolls back the current transaction. This method is called when an
     * exception occurs during command execution.
     *
     * @throws IllegalStateException if no {@code EntityManager} is available
     */
    protected void rollback()
    {
        getLog().debug("Rolling back transaction!");
        try
        {
            fetchTransaction().rollback();
        }
        catch (Exception ex)
        {
            getLog().warn("Could not roll back transaction.", ex);
        }
    }

    /**
     * Returns the current {@code EntityTransaction} object. This method
     * requires that a current {@code EntityManager} is available.
     *
     * @return the current transaction object
     * @throws IllegalStateException if no {@code EntityManager} is available
     */
    protected EntityTransaction fetchTransaction()
    {
        return fetchEntityManager().getTransaction();
    }

    /**
     * Performs a JPA operation using the passed in {@code EntityManager}. This
     * method is called by {@link #execute()}. Here a concrete subclass can
     * place the actual JPA-related logic.
     *
     * @param em the {@code EntityManager}
     */
    protected abstract void executeJPAOperation(EntityManager em);

    /**
     * Checks the initializer for the {@code EntityManagerFactory} passed to the
     * constructor. If it is invalid, an exception is thrown.
     *
     * @param emfInit the initializer for the factory passed to the constructor
     * @return the factory initializer to be used
     * @throws NullPointerException if no factory was provided
     */
    private static ConcurrentInitializer<EntityManagerFactory> checkFactory(
            ConcurrentInitializer<EntityManagerFactory> emfInit)
    {
        if (emfInit == null)
        {
            throw new NullPointerException(
                    "Initializer for entity manager factory must not be null!");
        }
        return emfInit;
    }
}
