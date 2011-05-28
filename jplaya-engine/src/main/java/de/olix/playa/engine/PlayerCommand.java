package de.olix.playa.engine;

/**
 * <p>A base class for commands that can be executed asynchronously from the
 * <code>{@link AudioPlayer}</code> class.</p>
 * <p>This abstract class defines the structure of commands that can be processed
 * by the <code>{@link CommandDispatchThread}</code> class. This structure is
 * actually quite simple: There is a single <code>execute()</code> method that
 * must be implemented to define whatever the command should do. Because the command
 * mechanism is package local we use an abstract base class rather than an
 * interface.</p>
 *
 * @author Oliver Heger
 * @version $Id$
 */
abstract class PlayerCommand
{
    /**
     * Executes this command. A concrete implementation is free do implement
     * this method in a suitable way.
     */
    public abstract void execute();
}
