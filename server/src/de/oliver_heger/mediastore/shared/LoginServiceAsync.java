package de.oliver_heger.mediastore.shared;

import com.google.gwt.user.client.rpc.AsyncCallback;

/**
 * Asynchronous service interface for the login service.
 *
 * @author Oliver Heger
 * @version $Id: $
 */
public interface LoginServiceAsync
{
    void getLoginInfo(String requestUrl, AsyncCallback<LoginInfo> callback);
}
