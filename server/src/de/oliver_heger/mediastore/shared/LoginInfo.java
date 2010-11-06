package de.oliver_heger.mediastore.shared;

import java.io.Serializable;

/**
 * <p>
 * A data class for storing information about the currently logged in user.
 * </p>
 * <p>
 * Objects of this class are used by the login service to send back user
 * information to the client. They store a flag whether a user is currently
 * logged in. If this is the case, some user information is available.
 * Otherwise, there is a link which can be used to sign in.
 * </p>
 *
 * @author Oliver Heger
 * @version $Id: $
 */
public class LoginInfo implements Serializable
{
    /**
     * The serial version UID.
     */
    private static final long serialVersionUID = 20100918L;

    /** The name of the user. */
    private String userName;

    /** The URL for the login. */
    private String loginUrl;

    /** The URL for the logout. */
    private String logoutUrl;

    /** A flag whether a user is logged in. */
    private boolean loggedIn;

    /**
     * Returns the name of the current user.
     *
     * @return the user's name
     */
    public String getUserName()
    {
        return userName;
    }

    /**
     * Sets the name of the user currently logged in.
     *
     * @param userName the name of the user
     */
    public void setUserName(String userName)
    {
        this.userName = userName;
    }

    /**
     * Returns a URL which can be used to login.
     *
     * @return the login URL
     */
    public String getLoginUrl()
    {
        return loginUrl;
    }

    /**
     * Sets the URL for login.
     *
     * @param loginUrl the login URL
     */
    public void setLoginUrl(String loginUrl)
    {
        this.loginUrl = loginUrl;
    }

    /**
     * Returns the URL which can be used to logout.
     *
     * @return the logout URL
     */
    public String getLogoutUrl()
    {
        return logoutUrl;
    }

    /**
     * Sets the URL for logout.
     *
     * @param logoutUrl the logout URL
     */
    public void setLogoutUrl(String logoutUrl)
    {
        this.logoutUrl = logoutUrl;
    }

    /**
     * Returns a flag whether a logged in user is available.
     *
     * @return the logged in flag
     */
    public boolean isLoggedIn()
    {
        return loggedIn;
    }

    /**
     * Sets the logged in flag.
     *
     * @param loggedIn the logged in flag
     */
    public void setLoggedIn(boolean loggedIn)
    {
        this.loggedIn = loggedIn;
    }
}
