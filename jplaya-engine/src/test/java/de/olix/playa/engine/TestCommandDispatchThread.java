package de.olix.playa.engine;

import junit.framework.TestCase;

/**
 * Test class for CommandDispatchThread.
 * 
 * @author Oliver Heger
 * @version $Id$
 */
public class TestCommandDispatchThread extends TestCase
{
    /** Stores the object to be tested.*/
    private CommandDispatchThread thread;

    protected void setUp() throws Exception
    {
        super.setUp();
        thread = new CommandDispatchThread();
        thread.start();
    }

    @Override
    /**
     * Clears the test environment. This implementation will shut down the
     * thread gracefully. So it can be seen as a test case for the thread's exit()
     * method.
     */
    protected void tearDown() throws Exception
    {
        thread.exit();
        thread.join();
        super.tearDown();
    }

    /**
     * Tests executing a null command. This should throw an exception.
     */
    public void testExecuteNull()
    {
        try
        {
            thread.execute(null);
            fail("Could execute null command!");
        }
        catch(IllegalArgumentException iex)
        {
            //ok
        }
    }
    
    /**
     * Tests executing some commands.
     */
    public void testExecute()
    {
        TestCommand cmd1 = new TestCommand();
        TestCommand cmd2 = new TestCommand();
        thread.execute(cmd1);
        thread.execute(cmd2);
        cmd2.waitFor();
        assertTrue("Command 1 not executed", cmd1.isExecuted());
        assertTrue("Command 2 not executed", cmd2.isExecuted());
    }
    
    /**
     * A test command class. This command class will record whether it was
     * executed. It also allows to wait for its execution.
     */
    static class TestCommand extends PlayerCommand
    {
        /** A flag whether this object was executed.*/
        private boolean executed;

        public synchronized boolean isExecuted()
        {
            return executed;
        }

        public synchronized void setExecuted(boolean executed)
        {
            this.executed = executed;
            if(executed)
            {
                notifyAll();
            }
        }
        
        /**
         * Waits until this command was executed.
         */
        public synchronized void waitFor()
        {
            while(!isExecuted())
            {
                try
                {
                    wait();
                }
                catch(InterruptedException iex)
                {
                    //ignore
                }
            }
        }

        @Override
        public void execute()
        {
            setExecuted(true);
        }
    }
}
