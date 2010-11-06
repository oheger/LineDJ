package de.oliver_heger.mediastore.shared;

import com.google.gwt.user.client.rpc.RemoteService;
import com.google.gwt.user.client.rpc.RemoteServiceRelativePath;

/**
 * <p>
 * The service interface of the login service.
 * </p>
 * <p>
 * This service is called by the client to obtain information about the user
 * currently signed in.
 * </p>
 *
 * @author Oliver Heger
 * @version $Id: $
 */
@RemoteServiceRelativePath("login")
public interface LoginService extends RemoteService
{
    /**
     * Returns a {@link LoginInfo} object with information about the user
     * currently logged in.
     *
     * @param requestUrl the URL of the current request (required for the
     *        generation of the login link)
     * @return a {@link LoginInfo} object
     */
    LoginInfo getLoginInfo(String requestUrl);
}
