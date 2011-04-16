package de.oliver_heger.mediastore.localstore.impl;

import javax.persistence.EntityManager;
import javax.persistence.EntityManagerFactory;

import org.apache.commons.lang3.concurrent.ConcurrentInitializer;

import de.oliver_heger.mediastore.localstore.CommandObserver;

/**
 * <p>
 * An abstract base class for commands that support an observer.
 * </p>
 * <p>
 * When an instance of this class is created an object implementing the
 * {@link CommandObserver} interface has to be passed in. This base
 * implementation takes care that the methods of this objects are called at the
 * correct points in the life-cycle of this command.
 * </p>
 * <p>
 * Derived classes have to implement the {@link #produceResults(EntityManager)}
 * methods. Here the result object to be passed to the observer has to be
 * created.
 * </p>
 *
 * @author Oliver Heger
 * @version $Id: $
 * @param <T> the type of the observer and the result produced by this command
 */
abstract class ObservableCommand<T> extends JPACommand
{
    /** The observer of this command. */
    private final CommandObserver<T> observer;

    /** Stores the result produced by this command. */
    private T result;

    /**
     * Creates a new instance of {@code ObservableCommand}.
     *
     * @param emfInit the initializer for the entity manager factory
     * @param obs the observer of this command (must not be <b>null</b>)
     * @throws NullPointerException if no observer is passed
     */
    protected ObservableCommand(
            ConcurrentInitializer<EntityManagerFactory> emfInit,
            CommandObserver<T> obs)
    {
        super(emfInit);
        if (obs == null)
        {
            throw new NullPointerException("Observer must not be null!");
        }
        observer = obs;
    }

    /**
     * An error occurred during command execution. This implementation notifies
     * the observer.
     *
     * @param t the exception
     */
    @Override
    public void onException(Throwable t)
    {
        super.onException(t);
        observer.commandExecutionFailed(t);
    }

    /**
     * Performs the update of the UI. This method is called in the event
     * dispatch thread after the execution of the command in the background
     * thread. It calls the corresponding observer method.
     */
    @Override
    protected void performGUIUpdate()
    {
        observer.commandCompletedUI(getResult(), getException());
    }

    /**
     * {@inheritDoc} This implementation takes care that the observer is
     * notified correctly.
     */
    @Override
    protected void executeJPAOperation(EntityManager em)
    {
        result = produceResults(em);
        observer.commandCompletedBackground(getResult());
    }

    /**
     * Executes this command and returns the result. Here the actual logic of
     * this command class has to be implemented. This method is called by
     * {@link #executeJPAOperation(EntityManager)}.
     *
     * @param em the {@code EntityManager}
     * @return the result produced by this command
     */
    protected abstract T produceResults(EntityManager em);

    /**
     * Returns the result produced by this command.
     *
     * @return the result object
     */
    T getResult()
    {
        return result;
    }
}
