package de.oliver_heger.mediastore.oauth;

import java.io.UnsupportedEncodingException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URLDecoder;

import com.sun.jersey.api.client.Client;
import com.sun.jersey.api.client.ClientResponse;
import com.sun.jersey.api.client.WebResource;
import com.sun.jersey.oauth.client.OAuthClientFilter;
import com.sun.jersey.oauth.signature.OAuthParameters;
import com.sun.jersey.oauth.signature.OAuthSecrets;

/**
 * <p>
 * A class implementing an easy to use framework for performing OAuth operations
 * on REST services implemented with Jersey.
 * </p>
 * <p>
 * This class follows the template philosophy as used for instance by the <a
 * href="http://www.springsource.org/">Spring framework</a> for various types of
 * resources. Usage is as follows:
 * <ul>
 * <li>Create an instance and initialize it with the URI for the OAuth endpoints
 * and the base URI for the resources to be manipulated.</li>
 * <li>Pass an object implementing the {@link ResourceProcessor} interface to
 * the {@code execute()} method. {@code execute()} also requires a reference to
 * an implementation of {@link OAuthCallback} which is used to deal with
 * authorization tokens.</li>
 * </ul>
 * The data of a successful authorization is cached so that it can be reused by
 * following invocations of {@code execute()}. Because of this internal state
 * the class is not thread-safe. An instance can only be used by a single
 * thread.
 * </p>
 *
 * @author Oliver Heger
 * @version $Id: $
 */
public class OAuthTemplate
{
    /** Constant for the consumer key. */
    static final String CONSUMER_KEY = "www.oliver-heger.de";

    /** Constant for the consumer secret. */
    static final String CONSUMER_SECRET = "sw6fsLDpo9U55eVcZFRX+nl8";

    /** The prefix for all OAuth service names. */
    private static final String OAUTH_ENDPOINT_PREFIX = "OAuth";

    /** The URL suffix for requesting a request token. */
    private static final String URL_REQUEST = OAUTH_ENDPOINT_PREFIX
            + "GetRequestToken";

    /** The URL for authorization of a user. */
    private static final String URL_AUTHORIZE = OAUTH_ENDPOINT_PREFIX
            + "AuthorizeToken?oauth_token=";

    /** The URL for obtaining an access token. */
    private static final String URL_ACCESS = OAUTH_ENDPOINT_PREFIX
            + "GetAccessToken";

    /** Constant for the callback. */
    private static final String CALLBACK = "oob";

    /** Constant for the signature method. */
    private static final String SIG_METHOD = "HMAC-SHA1";

    /** Delimiters for parsing URLs. */
    private static final String DELIMITERS = "[&=]";

    /** Constant for the encoding for URLs. */
    private static final String ENCODING = "UTF-8";

    /** Constant for the slash. */
    private static final String SLASH = "/";

    /** Stores the Jersey client object. */
    private final Client client;

    /** Stores the OAuth endpoint URI. */
    private final String oauthEndpointURI;

    /** The base URI for the resources to be accessed. */
    private final String serviceURI;

    /** The current OAuth filter. */
    private OAuthClientFilter oAuthFilter;

    /**
     * Creates a new instance of {@code OAuthTemplate} and initializes it with
     * the URIs for the OAuth endpoint and the service and the {@code Client} to
     * be used.
     *
     * @param oauthURI the URI prefix of the OAuth services (must not be
     *        <b>null</b>)
     * @param svcURI the base URI of the resources (must not be <b>null</b>)
     * @param client the client object for dealing with resources (can be
     *        <b>null</b>, then a default client is created)
     * @throws NullPointerException if a required parameter is missing
     */
    public OAuthTemplate(String oauthURI, String svcURI, Client client)
    {
        oauthEndpointURI = normalizeURI(oauthURI);
        serviceURI = normalizeURI(svcURI);

        if (client != null)
        {
            this.client = client;
        }
        else
        {
            this.client = Client.create();
        }
    }

    /**
     * Creates a new instance of {@code OAuthTemplate} and initializes it with
     * the URIs for the OAuth services and the base resource URI. A default
     * {@code Client} object is created.
     *
     * @param oauthURI the URI prefix of the OAuth services (must not be
     *        <b>null</b>)
     * @param svcURI the base URI of the resources (must not be <b>null</b>)
     * @throws NullPointerException if a required parameter is missing
     */
    public OAuthTemplate(String oauthURI, String svcURI)
    {
        this(oauthURI, svcURI, null);
    }

    /**
     * Returns the client object used by this template.
     *
     * @return the client
     */
    public Client getClient()
    {
        return client;
    }

    /**
     * Returns the URI prefix of the OAuth services.
     *
     * @return the OAuth service endpoint URI
     */
    public String getOAuthEndpointURI()
    {
        return oauthEndpointURI;
    }

    /**
     * Returns the base URI for the services (the resources) to be accessed.
     *
     * @return the service URI
     */
    public String getServiceURI()
    {
        return serviceURI;
    }

    /**
     * Returns the current filter for OAuth authorization. This filter is
     * created once (performing a full OAuth authorization if necessary). Then
     * it is cached and reused for further requests.
     *
     * @return the current OAuth filter
     */
    public OAuthClientFilter getOAuthFilter()
    {
        return oAuthFilter;
    }

    /**
     * Executes an operation on a resource using the specified
     * {@link ResourceProcessor}. This is the main method for accessing
     * resources through the OAuth protocol. A {@code WebResource} object is
     * created and initialized and passed to the {@link ResourceProcessor}.
     * Authorization is performed as required. The return value indicates
     * whether the operation could be completed successfully. A result of
     * <b>false</b> means that authorization was no possible.
     *
     * @param proc the {@link ResourceProcessor} (must not be <b>null</b>)
     * @param callback the {@link OAuthCallback} to be used for authorization
     *        (must not be <b>null</b>)
     * @return a flag whether the operation could be executed successfully
     * @throws NullPointerException if a required parameter is missing
     */
    public boolean execute(ResourceProcessor proc, OAuthCallback callback)
    {
        if (proc == null)
        {
            throw new NullPointerException(
                    "ResourceProcessor must not be null!");
        }
        if (callback == null)
        {
            throw new NullPointerException("Callback must not be null!");
        }

        boolean hasTokens = true;
        do
        {
            OAuthTokens tokens = null;
            OAuthClientFilter filter = getOAuthFilter();

            if (filter == null)
            {
                if (hasTokens)
                {
                    tokens = callback.getTokens();
                    hasTokens = tokens != null;
                }

                if (!hasTokens)
                {
                    tokens = authorize(callback);
                    if (tokens == null)
                    {
                        return false;
                    }
                }
                filter = createOAuthFilterFromTokens(tokens);
                oAuthFilter = filter;
            }

            WebResource resource = createResourceForServiceCall(filter);
            try
            {
                interactWithResourceProcessor(proc, resource);
                if (!hasTokens)
                {
                    callback.setTokens(tokens);
                }
                return true;
            }
            catch (NotAuthorizedException naex)
            {
                oAuthFilter = null;
                hasTokens = false;
            }
        } while (true);
    }

    /**
     * Tests whether the specified response is authorized. This method checks
     * the status code of the response object. If it indicates that the response
     * is not authorized, an exception is thrown.
     *
     * @param response the response to be checked (may be <b>null</b>)
     * @throws NotAuthorizedException if the response indicates an unauthorized
     *         request
     */
    public static void checkAuthorizedResponse(ClientResponse response)
            throws NotAuthorizedException
    {
        if (response != null)
        {
            if (ClientResponse.Status.UNAUTHORIZED.getStatusCode() == response
                    .getStatus())
            {
                throw new NotAuthorizedException("Unauthorized response "
                        + response);
            }
        }
    }

    /**
     * Creates a new OAuth parameters object. This method is called when
     * parameters have to be initialized.
     *
     * @return the newly created parameters object
     */
    OAuthParameters createParameters()
    {
        return new OAuthParameters();
    }

    /**
     * Creates a new OAuth secrets object. This method is called when secretes
     * have to be initialized.
     *
     * @return the newly created secrets object
     */
    OAuthSecrets createSecrets()
    {
        return new OAuthSecrets();
    }

    /**
     * Creates a OAuth filter object.
     *
     * @param params the parameters object
     * @param secrets the secretes object
     * @return the newly created filter object
     */
    OAuthClientFilter createOAuthFilter(OAuthParameters params,
            OAuthSecrets secrets)
    {
        return new OAuthClientFilter(getClient().getProviders(), params,
                secrets);
    }

    /**
     * Sends a request to an OAuth service URL using the specified OAuth
     * parameter objects.
     *
     * @param urlSuffix the suffix of service URL (the full URL is generated
     *        from the endpoint URI passed to the constructor)
     * @param params the parameters object
     * @param secrets the secrets object
     * @return the result of the request
     */
    String sendOAuthRequest(String urlSuffix, OAuthParameters params,
            OAuthSecrets secrets)
    {
        WebResource resource = getClient().resource(oauthURI(urlSuffix));
        resource.addFilter(createOAuthFilter(params, secrets));
        return resource.get(String.class);
    }

    /**
     * Creates a filter object based on the specified tokens.
     *
     * @param tokens the tokens object
     * @return the filter
     */
    OAuthClientFilter createOAuthFilterFromTokens(OAuthTokens tokens)
    {
        OAuthParameters params =
                createParameters().consumerKey(CONSUMER_KEY)
                        .token(tokens.getParameterToken())
                        .signatureMethod(SIG_METHOD);
        OAuthSecrets secrets =
                createSecrets().tokenSecret(tokens.getSecretToken())
                        .consumerSecret(CONSUMER_SECRET);
        return createOAuthFilter(params, secrets);
    }

    /**
     * Performs an authorization using the OAuth protocol. If this is
     * successful, the new tokens are returned. A return value of <b>null</b>
     * indicates that the callback implementation did not provide any data
     * (which usually means that the user aborted the operation).
     *
     * @param callback the callback object
     * @return the tokens of a successful authorization
     */
    OAuthTokens authorize(OAuthCallback callback)
    {
        String requestTokenResult =
                sendOAuthRequest(URL_REQUEST,
                        prepareParametersForRequestToken(),
                        prepareSecretsForRequestToken());
        String[] requestTokens = extractTokens(requestTokenResult);

        String verificationCode =
                callback.getVerificationCode(createAuthorizeURI(requestTokens[0]));
        if (verificationCode == null)
        {
            return null;
        }

        String accessTokenResult =
                sendOAuthRequest(
                        URL_ACCESS,
                        prepareParametersForAccessToken(requestTokens[0],
                                verificationCode),
                        prepareSecretsForAccessToken(requestTokens[1]));
        String[] accessTokens = extractTokens(accessTokenResult);
        return new OAuthTokens(decode(accessTokens[0]), decode(accessTokens[1]));
    }

    /**
     * Creates the URI that is used for the manual authorization by the user.
     *
     * @param token the request token
     * @return the URI
     */
    URI createAuthorizeURI(String token)
    {
        StringBuilder buf = new StringBuilder(getOAuthEndpointURI());
        buf.append(URL_AUTHORIZE).append(token);
        try
        {
            return new URI(buf.toString());
        }
        catch (URISyntaxException e)
        {
            throw new IllegalStateException(
                    "Cannot generate authorization URI!", e);
        }
    }

    /**
     * Returns the full OAuth service URI for the specified service request. The
     * passed in string is interpreted as a suffix which is appended to the base
     * OAuth service URI.
     *
     * @param suffix the suffix for the service request
     * @return the full service URI
     */
    private String oauthURI(String suffix)
    {
        return getOAuthEndpointURI() + suffix;
    }

    /**
     * Prepares a parameters object for the service call get request token.
     *
     * @return the parameters object
     */
    private OAuthParameters prepareParametersForRequestToken()
    {
        return createParameters().consumerKey(CONSUMER_KEY).nonce()
                .signatureMethod(SIG_METHOD).callback(CALLBACK);
    }

    /**
     * Prepares a secrets object for the service call get request token.
     *
     * @return the secrets object
     */
    private OAuthSecrets prepareSecretsForRequestToken()
    {
        return createSecrets().consumerSecret(CONSUMER_SECRET);
    }

    /**
     * Prepares a parameters object for the service call get access token.
     *
     * @param token the request token
     * @param verificationCode the verification code
     * @return the parameters object
     */
    private OAuthParameters prepareParametersForAccessToken(String token,
            String verificationCode)
    {
        return createParameters().consumerKey(CONSUMER_KEY)
                .token(decode(token)).verifier(verificationCode)
                .signatureMethod(SIG_METHOD);
    }

    /**
     * Prepares a secrets object for the service call get access token.
     *
     * @param secToken the secret token
     * @return the secrets object
     */
    private OAuthSecrets prepareSecretsForAccessToken(String secToken)
    {
        return createSecrets().tokenSecret(decode(secToken)).consumerSecret(
                CONSUMER_SECRET);
    }

    /**
     * Creates the resource object for invoking the service. This resource is
     * then passed to the {@link ResourceProcessor}.
     *
     * @param filter the current OAuth filter
     * @return the resource object
     */
    private WebResource createResourceForServiceCall(OAuthClientFilter filter)
    {
        WebResource resource = getClient().resource(getServiceURI());
        resource.addFilter(filter);
        return resource;
    }

    /**
     * Calls the specified resource processor with the given resource. This
     * method calls the methods defined by the {@link ResourceProcessor}
     * interface in the expected way.
     *
     * @param proc the processor
     * @param resource the current resource
     * @throws NotAuthorizedException if the request was not authorized
     */
    private void interactWithResourceProcessor(ResourceProcessor proc,
            WebResource resource) throws NotAuthorizedException
    {
        ClientResponse response = proc.doWithResource(resource);
        checkAuthorizedResponse(response);

        if (response != null)
        {
            proc.processResponse(response);
        }
    }

    /**
     * Checks and processes a URI. The URI must not be <b>null</b>. If
     * necessary, a slash is appended.
     *
     * @param uri the URI to check
     * @return the processed URI
     * @throws NullPointerException if the URI is <b>null</b>
     */
    private static String normalizeURI(String uri)
    {
        if (uri == null)
        {
            throw new NullPointerException("URI must not be null!");
        }
        if (!uri.endsWith(SLASH))
        {
            return uri + SLASH;
        }
        return uri;
    }

    /**
     * Extracts the tokens from the response of an OAuth service call.
     *
     * @param response the response of the service call as string
     * @return an array with the two tokens that have been extracted
     */
    private static String[] extractTokens(String response)
    {
        String[] split = response.split(DELIMITERS);
        String[] tokens = new String[2];
        tokens[0] = split[1];
        tokens[1] = split[3];
        return tokens;
    }

    /**
     * Helper method for decoding a string.
     *
     * @param s the string
     * @return the decoded string
     */
    private static String decode(String s)
    {
        try
        {
            return URLDecoder.decode(s, ENCODING);
        }
        catch (UnsupportedEncodingException e)
        {
            // should not happen
            throw new AssertionError("UTF 8 encoding not supported: " + e);
        }
    }
}
