package de.oliver_heger.mediastore.client;

/**
 * <p>
 * Definition of an interface that can be used to report an error.
 * </p>
 * <p>
 * Many client components perform operations (e.g. server calls) which can cause
 * errors. This interface provides a generic and common way to report such
 * errors. There is a standard UI component implementing this interface in a way
 * that the error message is displayed to the user. In tests mock
 * implementations can be used.
 * </p>
 *
 * @author Oliver Heger
 * @version $Id: $
 */
public interface ErrorIndicator
{
    /**
     * Notifies this object that an error occurred. It is up to a concrete
     * implementation how it reacts. A graphical element will typically change
     * its appearance to indicate the error state to the user.
     *
     * @param ex the exception (must not be <b>null</b>)
     */
    void displayError(Throwable ex);

    /**
     * Notifies this object that there is no error. This method is typically
     * invoked after the successful execution of an operation. It clears the
     * error state.
     */
    void clearError();
}
