package de.oliver_heger.jplaya.engine;

import de.oliver_heger.jplaya.commons.AbstractDispatchThread;

/**
 * <p>
 * A thread class that is internally used by <code>{@link AudioPlayer}</code>
 * for the parallel execution of certain commands.
 * </p>
 * <p>
 * This thread class is able to process certain commands that are mainly related
 * to event firing stuff, e.g. notifying registered event listeners. To do this
 * on a separate thread has two reasons:
 * <ol>
 * <li>No race conditions can occur because all commands are serialized (i.e.
 * the command thread will only execute a single command at a given time). So no
 * special synchronization is needed.</li>
 * <li>Executing these commands on a different thread allows give control
 * immediately back to the main thread, which writes data into the source data
 * line for playback. Thus the line can be served with the maximum speed and the
 * probability of buffer underruns is reduced.</li>
 * </ol>
 * </p>
 *
 * @author Oliver Heger
 * @version $Id$
 */
class CommandDispatchThread extends AbstractDispatchThread<PlayerCommand>
{
    /**
     * Exits this command thread. The remaining commands in the queue will be
     * executed. Then the main loop is terminated and this thread ends.
     */
    public void exit()
    {
        shutdown(true);
    }
    
    /**
     * Makes this method available for test purposes.
     */
    @Override
    public void nextCommand()
    {
        super.nextCommand();
    }

    /**
     * Processes the current command.
     *
     * @param cmd the command to execute
     * @throws Exception if an error occursn
     */
    @Override
    protected void process(PlayerCommand cmd) throws Exception
    {
        cmd.execute();
    }
}
