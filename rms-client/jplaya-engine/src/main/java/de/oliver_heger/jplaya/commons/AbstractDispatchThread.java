package de.oliver_heger.jplaya.commons;

import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

/**
 * <p>
 * An abstract base class for dispatch threads.
 * </p>
 * <p>
 * A <em>dispatch thread</em> runs in a loop and processes incoming events or
 * commands sequentially. This is done until a terminate command is detected.
 * </p>
 * <p>
 * This base class provides the whole framework of enqueueing and processing
 * command objects. The type of these objects is determined by a generic type
 * parameter. A concrete implementation only has to implement the actual
 * processing of a command object.
 * </p>
 *
 * @author Oliver Heger
 * @version $Id$
 */
public abstract class AbstractDispatchThread<T> extends Thread
{
    /** The logger. */
    protected Log log = LogFactory.getLog(getClass());

    /** A queue for storing the commands to be executed. */
    private BlockingQueue<CommandData<T>> commands;

    /** A flag whether this thread should terminate. */
    private volatile boolean terminate;

    /**
     * Creates a new instance of <code>AbstractDispatchThread</code>.
     */
    protected AbstractDispatchThread()
    {
        commands = new LinkedBlockingQueue<CommandData<T>>();
    }

    /**
     * Adds the specified command object to this thread. It will be added to the
     * end of the internal queue and processed when all commands before it are
     * completed.
     *
     * @param cmd the command to be executed (must not be <b>null</b>)
     * @throws IllegalArgumentException if the command is <b>null</b>
     */
    public void execute(T cmd)
    {
        if (cmd == null)
        {
            throw new IllegalArgumentException("Command must not be null!");
        }

        CommandData<T> cdata = new CommandData<T>();
        cdata.command = cmd;
        addCommand(cdata);
    }

    /**
     * Returns a flag whether this thread is currently busy. &quot;busy&quot;
     * means that there are commands pending, which must be executed.
     *
     * @return a flag whether this thread is busy
     */
    public boolean isBusy()
    {
        return !commands.isEmpty();
    }

    /**
     * Shuts down this dispatching thread. The commands in the internal queue
     * will still be processed, then the thread terminates. If the
     * <code>wait</code> parameter is set to <b>true</b>, this method will
     * block until all pending commands have been processed. Otherwise this
     * method returns immediately.
     *
     * @param wait a flag whether this method should wait until all pending
     * commands have been processed
     */
    public void shutdown(boolean wait)
    {
        addCommand(new CommandData<T>());

        if (wait)
        {
            try
            {
                join();
            }
            catch (InterruptedException iex)
            {
                log.warn("Thread was interrupted", iex);
            }
        }
    }

    /**
     * The main method of this thread. Executes the commands that have been
     * added using the <code>execute()</code> method until
     * <code>shutdown()</code> is called.
     */
    @Override
    public void run()
    {
        log.info("Starting CommandDispatchThread.");

        while (!terminate)
        {
            nextCommand();
        }

        onThreadEnd();
        log.info("CommandDispatchThread ends.");
    }

    /**
     * Executes the next command in the queue. If the queue is currently empty,
     * the method will block.
     */
    protected void nextCommand()
    {
        try
        {
            CommandData<T> cdata = commands.take();
            if (cdata.command == null)
            {
                // empty command => terminate
                terminate = true;
            }
            else
            {
                try
                {
                    process(cdata.command);
                }
                catch (Exception ex)
                {
                    onException(ex);
                }
            }
        }
        catch (InterruptedException iex)
        {
            log.warn("Access to queue was interrupted", iex);
        }
    }

    /**
     * The exception handler method. This method is invoked when a command
     * causes an exception. This base implementation just logs the exception.
     *
     * @param ex the exception
     */
    protected void onException(Exception ex)
    {
        log.error("Command caused an exception", ex);
    }

    /**
     * This method is called immediately before this dispatch thread terminates.
     * Sub classes can override this method to perform clean up. This base
     * implementation is left empty.
     */
    protected void onThreadEnd()
    {
    }
    
    /**
     * Processes a command. This method is called when a new command was fetched
     * from the internal queue. Concrete classes can implement their specific
     * functionality here.
     *
     * @param cmd the command to be executed
     * @throws Exception in case of an error
     */
    protected abstract void process(T cmd) throws Exception;

    /**
     * Adds a command data object to the internal queue.
     *
     * @param cdata the data object
     */
    private void addCommand(CommandData<T> cdata)
    {
        try
        {
            commands.put(cdata);
        }
        catch (InterruptedException iex)
        {
            // should not happen because the queue is unbounded
        }
    }

    /**
     * An internal data class that holds information about commands.
     */
    static class CommandData<T>
    {
        /**
         * Stores the command. If the command is <b>null</b>, this is the
         * terminate command.
         */
        T command;
    }
}
