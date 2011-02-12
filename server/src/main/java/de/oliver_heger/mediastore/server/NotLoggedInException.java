package de.oliver_heger.mediastore.server;

/**
 * An exception class which is thrown by services if no user is logged in.
 *
 * @author Oliver Heger
 * @version $Id: $
 */
public class NotLoggedInException extends Exception
{
    /**
     * The serial version UID.
     */
    private static final long serialVersionUID = 20101112L;

    /**
     * Creates a new, uninitialized instance of {@code NotLoggedInException}.
     */
    public NotLoggedInException()
    {
    }

    /**
     * Creates a new instance of {@code NotLoggedInException} and sets the error
     * message.
     *
     * @param message the error message
     */
    public NotLoggedInException(String message)
    {
        super(message);
    }

    /**
     * Creates a new instance of {@code NotLoggedInException} and sets the root
     * cause.
     *
     * @param cause the cause
     */
    public NotLoggedInException(Throwable cause)
    {
        super(cause);
    }

    /**
     * Creates a new instance of {@code NotLoggedInException} and sets the error
     * message and the cause of the exception.
     *
     * @param message the error message
     * @param cause the cause
     */
    public NotLoggedInException(String message, Throwable cause)
    {
        super(message, cause);
    }
}
