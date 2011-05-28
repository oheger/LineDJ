package de.olix.playa.playlist;

/**
 * Definition of an exception class indicating problems with the
 * <code>{@link PlaylistManager}</code>.
 * 
 * @author Oliver Heger
 * @version $Id$
 */
public class PlaylistException extends Exception
{
    /**
     * The serial version UID.
     */
    private static final long serialVersionUID = 3850738937863169983L;

    /**
     * Creates a new instance of <code>PlaylistException</code> and sets the
     * error message.
     * 
     * @param msg the error message
     */
    public PlaylistException(String msg)
    {
        super(msg);
    }

    /**
     * Creates a new instance of <code>PlaylistException</code> and sets the
     * causing exception.
     * 
     * @param cause the root cause of this exception
     */
    public PlaylistException(Throwable cause)
    {
        super(cause);
    }

    /**
     * Creates a new instance of <code>PlaylistException</code> and sets the
     * error message and the root cause of this exception.
     * 
     * @param msg the error message
     * @param cause the root cause of this exception
     */
    public PlaylistException(String msg, Throwable cause)
    {
        super(msg, cause);
    }
}
