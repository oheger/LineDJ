package de.oliver_heger.mediastore.localstore.impl;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import javax.persistence.EntityManager;
import javax.persistence.EntityManagerFactory;
import javax.persistence.EntityTransaction;

import org.apache.commons.lang3.concurrent.ConcurrentInitializer;
import org.apache.commons.lang3.concurrent.ConstantInitializer;
import org.easymock.EasyMock;
import org.junit.Before;
import org.junit.Test;

/**
 * Test class for {@code JPACommand}.
 *
 * @author Oliver Heger
 * @version $Id: $
 */
public class TestJPACommand
{
    /** A mock for the EMF. */
    private EntityManagerFactory emf;

    /** The initializer for the EMF. */
    private ConcurrentInitializer<EntityManagerFactory> emfInit;

    @Before
    public void setUp() throws Exception
    {
        emf = EasyMock.createMock(EntityManagerFactory.class);
        emfInit = new ConstantInitializer<EntityManagerFactory>(emf);
    }

    /**
     * Tests whether the default update UI flag is set.
     */
    @Test
    public void testInitNoUIFlag()
    {
        JPACommandTestImpl cmd = new JPACommandTestImpl(emfInit);
        assertSame("Wrong EMF", emf, cmd.getEntityManagerFactory());
        assertTrue("Wrong flag", cmd.isUpdateGUI());
    }

    /**
     * Tests whether the update UI flag is correctly processed.
     */
    @Test
    public void testInitWithUIFlag()
    {
        JPACommandTestImpl cmd = new JPACommandTestImpl(emfInit, false);
        assertSame("Wrong EMF", emf, cmd.getEntityManagerFactory());
        assertFalse("Wrong flag", cmd.isUpdateGUI());
    }

    /**
     * Tries to create an instance without a factory.
     */
    @Test(expected = NullPointerException.class)
    public void testInitNoFactory()
    {
        new JPACommandTestImpl(null);
    }

    /**
     * Tries to obtain an EM if none is set.
     */
    @Test(expected = IllegalStateException.class)
    public void testFetchEntityManagerNotSet()
    {
        JPACommandTestImpl cmd = new JPACommandTestImpl(emfInit);
        cmd.fetchEntityManager();
    }

    /**
     * Tests a successful execution of the command.
     */
    @Test
    public void testExecuteSuccess() throws Exception
    {
        @SuppressWarnings("unchecked")
        ConcurrentInitializer<EntityManagerFactory> init =
                EasyMock.createMock(ConcurrentInitializer.class);
        EntityManager em = EasyMock.createMock(EntityManager.class);
        EntityTransaction tx = EasyMock.createMock(EntityTransaction.class);
        EasyMock.expect(init.get()).andReturn(emf);
        EasyMock.expect(emf.createEntityManager()).andReturn(em);
        EasyMock.expect(em.getTransaction()).andReturn(tx).anyTimes();
        tx.begin();
        tx.commit();
        EasyMock.replay(emf, em, tx, init);
        JPACommandTestImpl cmd = new JPACommandTestImpl(init);
        cmd.execute();
        assertSame("Wrong current EM", em, cmd.fetchEntityManager());
        cmd.verifyExecute(em);
        EasyMock.verify(emf, em, tx, init);
    }

    /**
     * Tests whether exceptions are correctly handled.
     */
    @Test
    public void testOnException()
    {
        JPACommandTestImpl cmd = new JPACommandTestImpl(emfInit);
        EntityManager em = cmd.installMockEM();
        EntityTransaction tx = EasyMock.createMock(EntityTransaction.class);
        EasyMock.expect(em.getTransaction()).andReturn(tx);
        tx.rollback();
        EasyMock.replay(em, tx, emf);
        RuntimeException rex = new RuntimeException("Test exception");
        cmd.onException(rex);
        assertSame("Exception not set", rex, cmd.getException());
        EasyMock.verify(em, tx, emf);
    }

    /**
     * Tests onException() if there is no EM. We can only check that no
     * exception is thrown.
     */
    @Test
    public void testOnExceptionNoEM()
    {
        JPACommandTestImpl cmd = new JPACommandTestImpl(emfInit);
        EasyMock.replay(emf);
        cmd.onException(new RuntimeException("Another test exception!"));
        EasyMock.verify(emf);
    }

    /**
     * Tests rollback() if even this causes an exception.
     */
    @Test
    public void testRollbackEx()
    {
        JPACommandTestImpl cmd = new JPACommandTestImpl(emfInit);
        EntityManager em = cmd.installMockEM();
        EntityTransaction tx = EasyMock.createMock(EntityTransaction.class);
        EasyMock.expect(em.getTransaction()).andReturn(tx);
        tx.rollback();
        EasyMock.expectLastCall().andThrow(
                new RuntimeException("Test exception on rollback!"));
        EasyMock.replay(em, tx, emf);
        cmd.onException(new RuntimeException("Again an exception!"));
        EasyMock.verify(em, tx, emf);
    }

    /**
     * Tests a successful invocation of onFinally().
     */
    @Test
    public void testOnFinally()
    {
        JPACommandTestImpl cmd = new JPACommandTestImpl(emfInit);
        EntityManager em = cmd.installMockEM();
        em.close();
        EasyMock.replay(emf, em);
        cmd.onFinally();
        EasyMock.verify(emf, em);
    }

    /**
     * Tests onFinally() if no EM is available. We can only check that no
     * exception is thrown.
     */
    @Test
    public void testOnFinyllyNoEM()
    {
        JPACommandTestImpl cmd = new JPACommandTestImpl(emfInit);
        EasyMock.replay(emf);
        cmd.onFinally();
        EasyMock.verify(emf);
    }

    /**
     * A test command implementation.
     */
    private static class JPACommandTestImpl extends JPACommand
    {
        /** Stores the EM passed to executeJPAOperation(). */
        private EntityManager executeEM;

        /** A mock EM to be returned by getEntityManager(). */
        private EntityManager mockEM;

        public JPACommandTestImpl(
                ConcurrentInitializer<EntityManagerFactory> emfInit)
        {
            super(emfInit);
        }

        public JPACommandTestImpl(
                ConcurrentInitializer<EntityManagerFactory> emfInit,
                boolean updateUI)
        {
            super(emfInit, updateUI);
        }

        /**
         * Verifies that this object was called with the expected EM.
         *
         * @param em the expected entity manager
         */
        public void verifyExecute(EntityManager em)
        {
            assertSame("Wrong EM", em, executeEM);
        }

        /**
         * Creates and installs a mock EM.
         *
         * @return the mock EM
         */
        public EntityManager installMockEM()
        {
            mockEM = EasyMock.createMock(EntityManager.class);
            return mockEM;
        }

        /**
         * Records this invocation.
         */
        @Override
        protected void executeJPAOperation(EntityManager em)
        {
            assertNull("Too many calls", executeEM);
            executeEM = em;
        }

        /**
         * Either returns the mock EM or calls the super method.
         */
        @Override
        protected EntityManager getEntityManager()
        {
            return (mockEM != null) ? mockEM : super.getEntityManager();
        }
    }
}
