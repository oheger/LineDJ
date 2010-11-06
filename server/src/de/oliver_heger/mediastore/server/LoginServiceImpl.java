package de.oliver_heger.mediastore.server;

import com.google.appengine.api.users.User;
import com.google.appengine.api.users.UserService;
import com.google.appengine.api.users.UserServiceFactory;
import com.google.gwt.user.server.rpc.RemoteServiceServlet;

import de.oliver_heger.mediastore.shared.LoginInfo;
import de.oliver_heger.mediastore.shared.LoginService;

/**
 * <p>
 * The implementation class of the login service.
 * </p>
 * <p>
 * This class uses the user service of the Google AppEngine to obtain
 * information about the user currently logged in.
 * </p>
 *
 * @author Oliver Heger
 * @version $Id: $
 */
public class LoginServiceImpl extends RemoteServiceServlet implements
        LoginService
{
    /**
     * The serial version UID.
     */
    private static final long serialVersionUID = 20100918L;

    /**
     * Returns information about the user currently logged in. This
     * implementation uses the {@code UserService} interface provided by
     * AppEngine to obtain this information.
     */
    @Override
    public LoginInfo getLoginInfo(String requestUrl)
    {
        UserService userService = getUserService();
        User user = userService.getCurrentUser();
        LoginInfo info = new LoginInfo();

        if (user == null)
        {
            info.setLoginUrl(userService.createLoginURL(requestUrl));
        }
        else
        {
            info.setLoggedIn(true);
            info.setUserName(user.getNickname());
            info.setLogoutUrl(userService.createLogoutURL(requestUrl));
        }

        return info;
    }

    /**
     * Returns the user service.
     *
     * @return the user service
     */
    UserService getUserService()
    {
        return UserServiceFactory.getUserService();
    }
}
