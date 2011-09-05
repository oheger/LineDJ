package de.oliver_heger.mediastore.localstore;

import de.oliver_heger.mediastore.oauth.OAuthCallback;
import de.oliver_heger.mediastore.oauth.OAuthTokens;

/**
 * <p>
 * Definition of an interface for a component which manages the tokens required
 * for an OAuth authorization.
 * </p>
 * <p>
 * A component implementing this interface is responsible for persisting OAuth
 * authorization information. It can be used by implementations of the
 * {@link OAuthCallback} interface.
 * </p>
 *
 * @author Oliver Heger
 * @version $Id: $
 */
public interface OAuthTokenStore
{
    /**
     * Loads the tokens from the internal store. If no tokens are available
     * (e.g. because the user has not performed any authorization before),
     * result is <b>null</b>.
     *
     * @return the current tokens or <b>null</b> if there are none
     */
    OAuthTokens loadTokens();

    /**
     * Saves the specified tokens in the internal store. Tokens saved using this
     * method can later be loaded again by {@link #loadTokens()}. So the user
     * does not have to go through the authorization again.
     *
     * @param tokens the tokens to be saved (must not be <b>null</b>)
     * @return a flag whether the tokens could be saved successfully
     * @throws NullPointerException if the tokens are <b>null</b>
     */
    boolean saveTokens(OAuthTokens tokens);
}
