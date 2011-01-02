package de.oliver_heger.mediastore.server.db;

import java.util.logging.Level;
import java.util.logging.Logger;

import javax.persistence.EntityManager;
import javax.persistence.EntityManagerFactory;

/**
 * <p>
 * A template class for performing JPA operations.
 * </p>
 * <p>
 * This class implements the basic handling of a JPA {@code EntityManager}. It
 * takes care that an {@code EntityManager} is obtained from the current
 * {@code EntityManagerFactory}, that a transaction is started and committed
 * after successful execution. If an error occurs, the transaction is rolled
 * back. Derived classes have to implement the
 * {@link #performOperation(EntityManager)} method. Here they can perform
 * arbitrary persistence operations using the passed in {@code EntityManager}.
 * </p>
 *
 * @author Oliver Heger
 * @version $Id: $
 * @param <T> the type of result objects produced by this operation template
 */
public abstract class JPATemplate<T>
{
    /** The logger. */
    protected static final Logger LOG = Logger.getLogger(JPATemplate.class
            .getName());

    /** The entity manager factory used by this object. */
    private final EntityManagerFactory factory;

    /** A flag whether a transaction is to be started. */
    private final boolean transactional;

    /**
     * Creates a new instance of {@code JPATemplate} and initializes it with the
     * default {@code EntityManagerFactory}. Transactions will be started
     * automatically.
     */
    public JPATemplate()
    {
        this(true);
    }

    /**
     * Creates a new instance of {@code JPATemplate}, initializes it with the
     * default {@code EntityManagerFactory} and sets the flag whether a
     * transaction is to be started before the operation is executed.
     *
     * @param startTransaction a flag whether a transaction is to be started
     */
    public JPATemplate(boolean startTransaction)
    {
        this(EMF.getFactory(), startTransaction);
    }

    /**
     * Creates a new instance of {@code JPATemplate} and initializes it with the
     * given {@code EntityManagerFactory} and the flag whether a transaction is
     * to be started before the operation is executed.
     *
     * @param emf the {@code EntityManagerFactory} (must not be <b>null</b>)
     * @param startTransaction a flag whether a transaction is to be started
     * @throws IllegalArgumentException if the {@code EntityManagerFactory} is
     *         <b>null</b>
     */
    public JPATemplate(EntityManagerFactory emf, boolean startTransaction)
    {
        if (emf == null)
        {
            throw new IllegalArgumentException(
                    "Entity manager factory must not be null!");
        }

        factory = emf;
        transactional = startTransaction;
    }

    /**
     * Returns the {@code EntityManagerFactory} used by this template to create
     * an {@code EntityManager}.
     *
     * @return the {@code EntityManagerFactory}
     */
    public EntityManagerFactory getFactory()
    {
        return factory;
    }

    /**
     * Returns a flag whether this operation runs in a transaction.
     *
     * @return a flag whether a transaction is managed by this template
     */
    public final boolean isTransactional()
    {
        return transactional;
    }

    /**
     * Executes this template. This is the main method of the template. It
     * creates and initializes an {@code EntityManager}. Then it delegates to
     * {@link #performOperation(EntityManager)}. After that necessary cleanup is
     * done, and the result of the operation is returned.
     *
     * @return the result of the operation
     */
    public T execute()
    {
        EntityManager em = getFactory().createEntityManager();

        try
        {
            startTx(em);

            LOG.fine("Executing JPATemplate");
            try
            {
                T result = performOperation(em);
                commit(em);

                return result;
            }
            catch (RuntimeException rex)
            {
                LOG.log(Level.SEVERE, "Exception on execution of JPATemplate!",
                        rex);
                rollback(em);

                throw rex;
            }
        }
        finally
        {
            em.close();
        }
    }

    /**
     * Injects the given {@code EntityManager} into the given object. This
     * method checks whether the target object implements the
     * {@link EntityManagerSupport} interface. If this is the case, the entity
     * manager is passed to this object. Otherwise, the method has no effect.
     *
     * @param em the {@code EntityManager}
     * @param target the target object
     */
    public static void inject(EntityManager em, Object target)
    {
        if (target instanceof EntityManagerSupport)
        {
            ((EntityManagerSupport) target).setEntityManager(em);
        }
    }

    /**
     * Performs the actual JPA operation. This method is invoked with a properly
     * initialized {@code EntityManager}.
     *
     * @param em the {@code EntityManager}
     * @return the result of the operation
     */
    protected abstract T performOperation(EntityManager em);

    /**
     * Starts a transaction if transactions are enabled.
     *
     * @param em the entity manager
     */
    private void startTx(EntityManager em)
    {
        if (isTransactional())
        {
            em.getTransaction().begin();
        }
    }

    /**
     * Executes a commit if transactions are enabled.
     *
     * @param em the entity manager
     */
    private void commit(EntityManager em)
    {
        if (isTransactional())
        {
            em.getTransaction().commit();
        }
    }

    /**
     * Rolls back a transaction if transactions are enabled.
     *
     * @param em the entity manager
     */
    private void rollback(EntityManager em)
    {
        if (isTransactional())
        {
            LOG.info("Rolling back transaction.");
            try
            {
                em.getTransaction().rollback();
            }
            catch (RuntimeException rex2)
            {
                LOG.log(Level.WARNING, "Could not roll back transaction.", rex2);
            }
        }
    }
}
