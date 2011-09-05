package de.oliver_heger.mediastore.oauth;

import java.net.URI;

/**
 * <p>
 * Definition of an interface used by the OAuth implementation to obtain
 * authorization information.
 * </p>
 * <p>
 * When doing authorization with the server some tokens, and sometimes even user
 * interaction are required. This information is provided to the OAuth component
 * through this interface. The basic idea is that when authorization information
 * is needed, the corresponding interface method is called. It is then up to the
 * implementation how this request is handled. For instance, a dialog window
 * could be opened to prompt the user. The Javadocs of the interface methods
 * contains detailed information when and under what circumstances the
 * corresponding methods are invoked.
 * </p>
 *
 * @author Oliver Heger
 * @version $Id: $
 */
public interface OAuthCallback
{
    /**
     * Asks this implementation to return OAuth tokens if they are available. If
     * the token from the last successful authorization have been stored, they
     * can now be returned and reused. If no tokens are available, <b>null</b>
     * can be returned. In this case a new authorization is performed.
     *
     * @return OAuth tokens if available
     */
    OAuthTokens getTokens();

    /**
     * Returns the verification code for an OAuth authorization. This method is
     * called if a full authorization is required. Before the URI for the
     * authorization has been determined. An implementation has to open this URI
     * in a browser and prompt the user to enter the corresponding verification
     * code. This code has to be returned. A return value of <b>null</b> means
     * that the operation is to be canceled. This can be used if the user
     * refuses to enter the code.
     *
     * @param authorizeURI the URI for the authorization request
     * @return the verification code
     */
    String getVerificationCode(URI authorizeURI);

    /**
     * Passes the tokens used for a successful authorization back to the
     * implementation. This information can be stored to be reused for the next
     * access to the service. Then the user does not have to go through the
     * authorization procedure.
     *
     * @param tokens the tokens of the last successful authorization
     */
    void setTokens(OAuthTokens tokens);
}
