package de.oliver_heger.mediastore.localstore;

/**
 * <p>
 * An interface which allows monitoring commands.
 * </p>
 * <p>
 * Commands are executed in a background thread. This is also true for commands
 * producing results, so their results are not directly available. This
 * interface provides a solution for this problem: it defines methods that can
 * be called by the command at important points of their life-cycle and when
 * results become available. Note that it is important to distinguish in which
 * thread the methods are called.
 * </p>
 *
 * @author Oliver Heger
 * @version $Id: $
 * @param <T> the type of the results produced by the command
 */
public interface CommandObserver<T>
{
    /**
     * Notifies this observer that the background processing of the command
     * completed. This method is called in the background thread, too. The
     * results produced by the command are passed.
     *
     * @param result the result object produced by the command
     */
    void commandCompletedBackground(T result);

    /**
     * Notifies this observer that the execution of the command caused an error.
     * This method is called in the background thread immediately after the
     * error was detected. Later on the
     * {@link #commandCompletedUI(Object, Throwable)} method is called.
     *
     * @param t the exception raised during command execution
     */
    void commandExecutionFailed(Throwable t);

    /**
     * Notifies this observer that the UI processing of the command completed.
     * This method is called in the event dispatch thread. The results produced
     * by the command are passed. If execution of the command caused an error,
     * the exception object is passed to this method. If there is no error, this
     * parameter is <b>null</b>.
     *
     * @param result the result object produced by the command
     */
    void commandCompletedUI(T result, Throwable t);
}
