package de.oliver_heger.mediastore.server;

import com.google.appengine.api.users.User;
import com.google.appengine.api.users.UserService;
import com.google.appengine.api.users.UserServiceFactory;
import com.google.gwt.user.server.rpc.RemoteServiceServlet;

/**
 * <p>
 * A base class for the service servlets used by this application.
 * </p>
 * <p>
 * This class provides some common functionality needed for most services.
 * Therefore the most service classes in this application extend this class
 * rather than {@code RemoteServiceServlet}.
 * </p>
 * <p>
 * The functionality offered by this main class is mainly related to the
 * handling of the logged in user.
 * </p>
 *
 * @author Oliver Heger
 * @version $Id: $
 */
public abstract class RemoteMediaServiceServlet extends RemoteServiceServlet
{
    /**
     * The serial version UID.
     */
    private static final long serialVersionUID = 20101202L;

    /**
     * Returns the user service.
     *
     * @return a reference to the user service
     */
    protected UserService getUserService()
    {
        return UserServiceFactory.getUserService();
    }

    /**
     * Returns the user currently logged in. Result can be <b>null</b> if no
     * user is logged in.
     *
     * @return the current user
     */
    protected User getCurrentUser()
    {
        return getCurrentUser(false);
    }

    /**
     * Returns the user currently logged in and optionally throws an exception
     * if there is none. This method can be used by sub classes that require a
     * logged in user. By simply passing <b>true</b> for the boolean parameter a
     * check is automatically performed.
     *
     * @param forceLogin a flag whether a current user is required
     * @return the current user
     * @throws IllegalStateException if no user is logged in
     */
    protected User getCurrentUser(boolean forceLogin)
    {
        User user = getUserService().getCurrentUser();

        if (forceLogin && user == null)
        {
            throw new IllegalStateException("No user logged in!");
        }

        return user;
    }

    /**
     * Tests whether the current user is the expected one. This method can be
     * used by derived classes for security checks: If data is to be fetched
     * which is associated with a user, it may be necessary to check whether
     * this user is actually the current user. Otherwise it would be possible to
     * obtain data by simply guessing ID values.
     *
     * @param expected the expected user
     * @throws IllegalStateException if the current user is not the expected
     *         user or user is logged in
     */
    protected void checkUser(User expected)
    {
        User current = getCurrentUser(true);
        if (!current.equals(expected))
        {
            throw new IllegalStateException("Not the expected user: "
                    + expected);
        }
    }
}
