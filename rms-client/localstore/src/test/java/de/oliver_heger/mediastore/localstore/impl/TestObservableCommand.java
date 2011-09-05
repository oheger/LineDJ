package de.oliver_heger.mediastore.localstore.impl;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.fail;

import javax.persistence.EntityManager;
import javax.persistence.EntityManagerFactory;
import javax.persistence.EntityTransaction;

import org.apache.commons.lang3.concurrent.ConcurrentException;
import org.apache.commons.lang3.concurrent.ConcurrentInitializer;
import org.apache.commons.lang3.concurrent.ConstantInitializer;
import org.easymock.EasyMock;
import org.junit.Before;
import org.junit.Test;

import de.oliver_heger.mediastore.localstore.CommandObserver;

/**
 * Test class for {@code ObservableCommand}.
 *
 * @author Oliver Heger
 * @version $Id: $
 */
public class TestObservableCommand
{
    /** Constant for the result of the command. */
    private static final String RESULT = "Test command result!";

    /** An initializer for the entity manager factory. */
    private ConstantInitializer<EntityManagerFactory> init;

    /** A mock for the observer. */
    private CommandObserver<String> observer;

    @SuppressWarnings("unchecked")
    @Before
    public void setUp() throws Exception
    {
        EntityManagerFactory factory =
                EasyMock.createMock(EntityManagerFactory.class);
        init = new ConstantInitializer<EntityManagerFactory>(factory);
        observer = EasyMock.createMock(CommandObserver.class);
    }

    /**
     * Tries to create a command without an observer.
     */
    @Test(expected = NullPointerException.class)
    public void testInitNoObserver()
    {
        new ObservableCommandTestImpl(init, null);
    }

    /**
     * Tests whether the observer is correctly notified after executing a JPA
     * operation.
     */
    @Test
    public void testExecuteJPAOperation()
    {
        EntityManager em = EasyMock.createMock(EntityManager.class);
        observer.commandCompletedBackground(RESULT);
        EasyMock.replay(em, observer);
        ObservableCommandTestImpl cmd =
                new ObservableCommandTestImpl(init, observer, em);
        cmd.executeJPAOperation(em);
        assertEquals("Results not available", RESULT, cmd.getResult());
        EasyMock.verify(em, observer);
    }

    /**
     * Tests whether an exception is reported to the observer.
     */
    @Test
    public void testOnException() throws ConcurrentException
    {
        EntityManager em = EasyMock.createMock(EntityManager.class);
        EntityTransaction tx = EasyMock.createNiceMock(EntityTransaction.class);
        EasyMock.expect(em.getTransaction()).andReturn(tx).anyTimes();
        EasyMock.expect(init.get().createEntityManager()).andReturn(em);
        final RuntimeException err = new RuntimeException("TestException");
        observer.commandExecutionFailed(err);
        EasyMock.replay(em, tx, observer, init.get());
        ObservableCommandTestImpl cmd =
                new ObservableCommandTestImpl(init, observer, em)
                {
                    @Override
                    protected String produceResults(EntityManager em)
                    {
                        super.produceResults(em);
                        throw err;
                    }
                };
        try
        {
            cmd.execute();
            fail("Exception not thrown!");
        }
        catch (Exception ex)
        {
            assertEquals("Wrong exception", err, ex);
        }
        cmd.onException(err);
        EasyMock.verify(em, tx, observer, init.get());
    }

    /**
     * Tests whether the observer is correctly notified on the event dispatch
     * thread.
     */
    @Test
    public void testPerformGUIUpdate()
    {
        final Throwable err = new RuntimeException();
        observer.commandCompletedUI(RESULT, err);
        EasyMock.replay(observer);
        ObservableCommandTestImpl cmd =
                new ObservableCommandTestImpl(init, observer)
                {
                    @Override
                    String getResult()
                    {
                        return RESULT;
                    }
                };
        cmd.setException(err);
        cmd.performGUIUpdate();
        EasyMock.verify(observer);
    }

    /**
     * A test implementation of the observable command.
     */
    private static class ObservableCommandTestImpl extends
            ObservableCommand<String>
    {
        /** The expected entity manager. */
        private final EntityManager expEM;

        public ObservableCommandTestImpl(
                ConcurrentInitializer<EntityManagerFactory> emfInit,
                CommandObserver<String> obs)
        {
            this(emfInit, obs, null);
        }

        public ObservableCommandTestImpl(
                ConcurrentInitializer<EntityManagerFactory> emfInit,
                CommandObserver<String> obs, EntityManager em)
        {
            super(emfInit, obs);
            expEM = em;
        }

        /**
         * Checks the parameters and returns the test result.
         */
        @Override
        protected String produceResults(EntityManager em)
        {
            assertSame("Wrong entity manager", expEM, em);
            return RESULT;
        }
    }
}
