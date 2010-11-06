package de.oliver_heger.mediastore.server.db;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import javax.persistence.EntityManager;
import javax.persistence.EntityManagerFactory;
import javax.persistence.EntityTransaction;

import org.easymock.EasyMock;
import org.junit.Before;
import org.junit.Test;

/**
 * Test class for {@code JPATemplate}.
 *
 * @author Oliver Heger
 * @version $Id: $
 */
public class TestJPATemplate
{
    /** Constant for the result object returned by the template. */
    private static final Object TEMPLATE_RESULT = "TestTemplateResult";

    /** Constant for a test exception message. */
    private static final String TEST_EXCEPTION_MSG = "Test exception";

    /** A mock for the entity manager factory. */
    private EntityManagerFactory mockFactory;

    @Before
    public void setUp() throws Exception
    {
        mockFactory = EasyMock.createMock(EntityManagerFactory.class);
    }

    /**
     * Tries to create an instance with a null factory.
     */
    @Test(expected = IllegalArgumentException.class)
    public void testInitNullFactory()
    {
        new JPATemplateTestImpl(null, false);
    }

    /**
     * Tests whether default values are set by the standard constructor.
     */
    @Test
    public void testInitDefaults()
    {
        JPATemplateTestImpl templ = new JPATemplateTestImpl();
        assertNotNull("No EMF", templ.getFactory());
        assertTrue("No transactions", templ.isTransactional());
    }

    /**
     * Tests an execution without transactions.
     */
    @Test
    public void testExecuteNoTx()
    {
        EntityManager em = EasyMock.createMock(EntityManager.class);
        EasyMock.expect(mockFactory.createEntityManager()).andReturn(em);
        em.close();
        EasyMock.replay(mockFactory, em);
        JPATemplateTestImpl templ = new JPATemplateTestImpl(mockFactory, false);
        assertEquals("Wrong result", TEMPLATE_RESULT, templ.execute());
        templ.checkEM(em);
        EasyMock.verify(mockFactory, em);
    }

    /**
     * Tests an exception without transactions if an exception occurs.
     */
    @Test
    public void testExecuteNoTxException()
    {
        EntityManager em = EasyMock.createMock(EntityManager.class);
        EasyMock.expect(mockFactory.createEntityManager()).andReturn(em);
        em.close();
        EasyMock.replay(mockFactory, em);
        RuntimeException rex = new RuntimeException(TEST_EXCEPTION_MSG);
        JPATemplateTestImpl templ =
                new JPATemplateTestImpl(mockFactory, false, rex);
        try
        {
            templ.execute();
            fail("Exception not detected!");
        }
        catch (RuntimeException rex2)
        {
            assertSame("Wrong exception", rex, rex2);
        }
        templ.checkEM(em);
        EasyMock.verify(mockFactory, em);
    }

    /**
     * Tests an execution with transactions.
     */
    @Test
    public void testExecuteTx()
    {
        EntityManager em = EasyMock.createMock(EntityManager.class);
        EntityTransaction tx = EasyMock.createMock(EntityTransaction.class);
        EasyMock.expect(mockFactory.createEntityManager()).andReturn(em);
        EasyMock.expect(em.getTransaction()).andReturn(tx).anyTimes();
        tx.begin();
        tx.commit();
        em.close();
        EasyMock.replay(mockFactory, em, tx);
        JPATemplateTestImpl templ = new JPATemplateTestImpl(mockFactory, true);
        assertEquals("Wrong result", TEMPLATE_RESULT, templ.execute());
        templ.checkEM(em);
        EasyMock.verify(mockFactory, em, tx);
    }

    /**
     * Tests an execution with transactions if an exception occurs.
     */
    @Test
    public void testExecuteTxException()
    {
        EntityManager em = EasyMock.createMock(EntityManager.class);
        EntityTransaction tx = EasyMock.createMock(EntityTransaction.class);
        EasyMock.expect(mockFactory.createEntityManager()).andReturn(em);
        EasyMock.expect(em.getTransaction()).andReturn(tx).anyTimes();
        tx.begin();
        tx.rollback();
        em.close();
        EasyMock.replay(mockFactory, em, tx);
        RuntimeException rex = new RuntimeException(TEST_EXCEPTION_MSG);
        JPATemplateTestImpl templ =
                new JPATemplateTestImpl(mockFactory, true, rex);
        try
        {
            templ.execute();
            fail("Exception not detected!");
        }
        catch (RuntimeException rex2)
        {
            assertSame("Wrong exception", rex, rex2);
        }
        templ.checkEM(em);
        EasyMock.verify(mockFactory, em, tx);
    }

    /**
     * Tests an execution with transactions if an exception occurs and the
     * rollback also causes an exception.
     */
    @Test
    public void testExecuteTxExceptionRollbackEx()
    {
        EntityManager em = EasyMock.createMock(EntityManager.class);
        EntityTransaction tx = EasyMock.createMock(EntityTransaction.class);
        EasyMock.expect(mockFactory.createEntityManager()).andReturn(em);
        EasyMock.expect(em.getTransaction()).andReturn(tx).anyTimes();
        tx.begin();
        RuntimeException rexRollback =
                new RuntimeException(TEST_EXCEPTION_MSG + "_Rollback");
        tx.rollback();
        EasyMock.expectLastCall().andThrow(rexRollback);
        em.close();
        EasyMock.replay(mockFactory, em, tx);
        RuntimeException rex = new RuntimeException(TEST_EXCEPTION_MSG);
        JPATemplateTestImpl templ =
                new JPATemplateTestImpl(mockFactory, true, rex);
        try
        {
            templ.execute();
            fail("Exception not detected!");
        }
        catch (RuntimeException rex2)
        {
            assertSame("Wrong exception", rex, rex2);
        }
        templ.checkEM(em);
        EasyMock.verify(mockFactory, em, tx);
    }

    /**
     * A test implementation of the template to test whether the abstract method
     * is correctly invoked.
     */
    private static class JPATemplateTestImpl extends JPATemplate<Object>
    {
        /** Stores the entity manager passed to performOperation(). */
        private EntityManager emForOperation;

        /** An exception to be thrown by performOperation(). */
        private RuntimeException exception;

        public JPATemplateTestImpl()
        {
            super();
        }

        public JPATemplateTestImpl(EntityManagerFactory emf,
                boolean startTransaction)
        {
            this(emf, startTransaction, null);
        }

        /**
         * Creates a new instance of {@code JPATemplateTestImpl} and initializes
         * it. If an exception is specified, it is thrown by
         * {@link #performOperation(EntityManager)}. This allows testing the
         * template if exceptions occur.
         *
         * @param emf the EMF
         * @param startTransaction the transaction flag
         * @param rex an optional exception
         */
        public JPATemplateTestImpl(EntityManagerFactory emf,
                boolean startTransaction, RuntimeException rex)
        {
            super(emf, startTransaction);
            exception = rex;
        }

        /**
         * Tests whether the expected entity manager was passed.
         *
         * @param expectedEM the expected EM
         */
        public void checkEM(EntityManager expectedEM)
        {
            assertSame("Wrong EM", expectedEM, emForOperation);
        }

        /**
         * Records this invocation and returns the test result. If configured,
         * an exception is thrown.
         */
        @Override
        protected Object performOperation(EntityManager em)
        {
            emForOperation = em;
            if (exception != null)
            {
                throw exception;
            }
            return TEMPLATE_RESULT;
        }
    }
}
