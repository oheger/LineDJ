package de.oliver_heger.mediastore.sync;

/**
 * <p>
 * An exception class used to report an unauthorized request.
 * </p>
 * <p>
 * An exception of this type can be triggered for an OAuth request which is not
 * accepted by the server due to missing or wrong authorization information.
 * </p>
 *
 * @author Oliver Heger
 * @version $Id: $
 */
public class NotAuthorizedException extends Exception
{
    /**
     * The serial version UID.
     */
    private static final long serialVersionUID = 20110405L;

    /**
     * Creates a new, uninitialized instance of {@code NotAuthorizedException}.
     */
    public NotAuthorizedException()
    {
    }

    /**
     * Creates a new instance of {@code NotAuthorizedException} and initializes
     * it with the specified exception message.
     *
     * @param msg the exception message
     */
    public NotAuthorizedException(String msg)
    {
        super(msg);
    }
}
