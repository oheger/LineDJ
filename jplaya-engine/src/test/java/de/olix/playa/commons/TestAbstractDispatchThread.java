package de.olix.playa.commons;

import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.StringTokenizer;

import junit.framework.TestCase;

/**
 * Test class of AbstractDispatchThread.
 *
 * @author Oliver Heger
 * @version $Id$
 */
public class TestAbstractDispatchThread extends TestCase
{
    /** Constant for the command prefix. */
    private static final String CMD_PREFIX = "COMMAND";

    /** Constant for the wait command. */
    private static final String CMD_WAIT = "wait";

    /** Constant for the exception command. */
    private static final String CMD_EX = "exception";

    /** The thread to be tested. */
    private AbstractDispatchThreadTestImpl thread;

    @Override
    protected void setUp() throws Exception
    {
        super.setUp();
        thread = new AbstractDispatchThreadTestImpl();
        thread.start();
    }

    /**
     * Tests executing some commands.
     */
    public void testExecute()
    {
        final int count = 10;
        StringBuilder buf = new StringBuilder();
        for (int i = 0; i < count; i++)
        {
            String cmd = CMD_PREFIX + i;
            if (i > 0)
            {
                buf.append(",");
            }
            buf.append(cmd);
            thread.execute(cmd);
        }
        thread.shutdown(true);
        thread.verify(buf.toString());
    }

    /**
     * Tests the isBusy() method.
     */
    public void testIsBusy()
    {
        assertFalse("Thread already busy", thread.isBusy());
        thread.execute("testCmd");
        thread.execute(CMD_WAIT);
        thread.execute("another command");
        assertTrue("Thread not busy", thread.isBusy());
        thread.signal();
        thread.shutdown(true);
        assertFalse("Thread still busy", thread.isBusy());
    }

    /**
     * Tests whether exceptions are correctly handled.
     */
    public void testOnException()
    {
        thread.execute(CMD_EX);
        thread.shutdown(true);
        assertNotNull("Exception was not handled", thread.exception);
    }

    /**
     * Tests shutdown when wait is false.
     */
    public void testShutdownNoWait() throws InterruptedException
    {
        thread.execute("testCmd");
        thread.execute(CMD_WAIT);
        thread.execute("anotherCmd");
        thread.shutdown(false);
        assertTrue("All commands processed", thread.commands.size() < 3);
        thread.signal();
        thread.join();
    }

    /**
     * Tries to execute a null command. This is not allowed.
     */
    public void testExecuteNull()
    {
        try
        {
            thread.execute(null);
            fail("Could execute null command!");
        }
        catch (IllegalArgumentException iex)
        {
            // ok
        }
    }

    /**
     * A test implementation of a dispatch thread that operates on strings.
     */
    static class AbstractDispatchThreadTestImpl extends
            AbstractDispatchThread<String>
    {
        /** Stores the processed commands. */
        private List<String> commands = new LinkedList<String>();

        /** Stores a caught exception. */
        private Exception exception;

        /** A lock object for the wait command. */
        private Object lock = new Object();

        /** A flag whether we have to wait. */
        private Boolean isWaiting;

        @Override
        protected void process(String cmd) throws Exception
        {
            log.info("Executing " + cmd);
            commands.add(cmd);
            if (CMD_WAIT.equals(cmd))
            {
                synchronized (lock)
                {
                    if (isWaiting == null || isWaiting.booleanValue())
                    {
                        isWaiting = Boolean.TRUE;
                        while (isWaiting.booleanValue())
                        {
                            lock.wait();
                        }
                    }
                }
            }
            else if (CMD_EX.equals(cmd))
            {
                throw new Exception("Exception");
            }
        }

        @Override
        protected void onException(Exception ex)
        {
            super.onException(ex);
            exception = ex;
        }

        /**
         * Restarts this thread after a wait command.
         */
        public void signal()
        {
            synchronized (lock)
            {
                isWaiting = Boolean.FALSE;
                lock.notify();
            }
        }

        /**
         * Tests whether the expected commands have been processed. The passed
         * in string is a comma separated list of commands.
         *
         * @param cmds the expected commands
         */
        public void verify(String cmds)
        {
            assertNull("An exception occurred: " + exception, exception);

            StringTokenizer tok = new StringTokenizer(cmds, ",");
            Iterator<String> it = commands.iterator();
            int i = 0;
            while (tok.hasMoreTokens() && it.hasNext())
            {
                assertEquals("Wrong token at " + i, tok.nextToken(), it.next());
            }
            assertFalse("Too few commands", tok.hasMoreElements());
            assertFalse("Too many commands", it.hasNext());
        }
    }
}
