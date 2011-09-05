package de.oliver_heger.mediastore.oauth;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.UnsupportedEncodingException;
import java.net.URI;
import java.net.URLDecoder;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;

import javax.ws.rs.ext.Providers;

import org.easymock.EasyMock;
import org.junit.BeforeClass;
import org.junit.Test;

import com.sun.jersey.api.client.Client;
import com.sun.jersey.api.client.ClientResponse;
import com.sun.jersey.api.client.WebResource;
import com.sun.jersey.oauth.client.OAuthClientFilter;
import com.sun.jersey.oauth.signature.OAuthParameters;
import com.sun.jersey.oauth.signature.OAuthSecrets;

import de.oliver_heger.mediastore.oauth.NotAuthorizedException;
import de.oliver_heger.mediastore.oauth.OAuthCallback;
import de.oliver_heger.mediastore.oauth.OAuthTemplate;
import de.oliver_heger.mediastore.oauth.OAuthTokens;
import de.oliver_heger.mediastore.oauth.ResourceProcessor;

/**
 * Test class for {@code OAuthTemplate}.
 *
 * @author Oliver Heger
 * @version $Id: $
 */
public class TestOAuthTemplate
{
    /** The prefix of the OAuth services. */
    private static final String OAUTH_URI =
            "https://remotemediastore.appspot.com/_ah/";

    /** Constant for the service URL. */
    private static final String SERVICE_URI =
            "https://remotemediastore.appspot.com/resources/";

    /** Constant for an OAuth token. */
    private static final String OAUTH_TOKEN =
            "4%2FBQ7vRwLEp5cj_aRWaS1BNFfW5dmy";

    /** Constant for an OAuth secret token. */
    private static final String OAUT_TOKEN_SECRET = "ctHjYvTlOh3Nk5mYRNYDySEj";

    /** Constant for a default tokens object. */
    private static final OAuthTokens TOKENS = new OAuthTokens(OAUTH_TOKEN,
            OAUT_TOKEN_SECRET);

    /** The URI for the verification code. */
    private static URI verificationURI;

    @BeforeClass
    public static void setUpBeforeClass() throws Exception
    {
        verificationURI =
                new URI(OAUTH_URI + "OAuthAuthorizeToken?oauth_token="
                        + OAUTH_TOKEN);
    }

    /**
     * Tests whether a default client is created.
     */
    @Test
    public void testInitDefaultClient()
    {
        OAuthTemplate templ = new OAuthTemplate(OAUTH_URI, SERVICE_URI);
        assertNotNull("No client", templ.getClient());
        assertNull("Got a filter", templ.getOAuthFilter());
    }

    /**
     * Tests whether the URIs passed to the constructor are correctly appended
     * with a slash if necessary.
     */
    @Test
    public void testInitNormalizeURIs()
    {
        final String oauthURI = "http://some.oauth.com";
        final String serviceURI = "http://some.service.com";
        OAuthTemplate templ = new OAuthTemplate(oauthURI, serviceURI);
        assertEquals("Wrong OAuth URI", oauthURI + "/",
                templ.getOAuthEndpointURI());
        assertEquals("Wrong service URI", serviceURI + "/",
                templ.getServiceURI());
    }

    /**
     * Tries to create an instance without an OAuth URI.
     */
    @Test(expected = NullPointerException.class)
    public void testInitNullOAuthURI()
    {
        new OAuthTemplate(null, SERVICE_URI);
    }

    /**
     * Tries to create an instance without a service URI.
     */
    @Test(expected = NullPointerException.class)
    public void testInitNoServiceURI()
    {
        new OAuthTemplate(OAUTH_URI, null);
    }

    /**
     * Tests whether a parameters object can be created. We can only check
     * whether an object is actually created.
     */
    @Test
    public void testCreateParameters()
    {
        OAuthTemplate templ = new OAuthTemplate(OAUTH_URI, SERVICE_URI);
        assertNotNull("No parameters", templ.createParameters());
    }

    /**
     * Tests whether a secrets object can be created. We can only check whether
     * an object is actually created.
     */
    @Test
    public void testCreateSecrets()
    {
        OAuthTemplate templ = new OAuthTemplate(OAUTH_URI, SERVICE_URI);
        assertNotNull("No secrets", templ.createSecrets());
    }

    /**
     * Tests whether a filter object can be created. Unfortunately, we cannot
     * check whether the filter was initialized with the correct objects.
     */
    @Test
    public void testCreateOAuthFilter()
    {
        Client client = EasyMock.createMock(Client.class);
        Providers providers = EasyMock.createNiceMock(Providers.class);
        EasyMock.expect(client.getProviders()).andReturn(providers);
        EasyMock.replay(client, providers);
        OAuthTemplate templ = new OAuthTemplate(OAUTH_URI, SERVICE_URI, client);
        assertNotNull("No filter", templ.createOAuthFilter(
                new OAuthParameters(), new OAuthSecrets()));
        EasyMock.verify(client, providers);
    }

    /**
     * Creates a dummy filter object.
     *
     * @return the dummy filter
     */
    private static OAuthClientFilter createDummyFilter()
    {
        return new OAuthClientFilter(EasyMock.createNiceMock(Providers.class),
                new OAuthParameters(), new OAuthSecrets());
    }

    /**
     * Tests whether a request to an OAuth service is sent correctly.
     */
    @Test
    public void testSendOAuthRequest()
    {
        Client client = EasyMock.createMock(Client.class);
        WebResource resource = EasyMock.createMock(WebResource.class);
        final OAuthParameters params =
                EasyMock.createMock(OAuthParameters.class);
        final OAuthSecrets secrets = EasyMock.createMock(OAuthSecrets.class);
        final OAuthClientFilter filter = createDummyFilter();
        final String request = "SpecificOAuthService";
        final String response = "TheServiceResponse";
        EasyMock.expect(client.resource(OAUTH_URI + request)).andReturn(
                resource);
        resource.addFilter(filter);
        EasyMock.expect(resource.get(String.class)).andReturn(response);
        EasyMock.replay(client, resource, params, secrets);
        OAuthTemplate templ = new OAuthTemplate(OAUTH_URI, SERVICE_URI, client)
        {
            @Override
            OAuthClientFilter createOAuthFilter(OAuthParameters theParameters,
                    OAuthSecrets theSecrets)
            {
                assertSame("Wrong parameters", params, theParameters);
                assertSame("Wrong secrets", secrets, theSecrets);
                return filter;
            }
        };
        assertEquals("Wrong response", response,
                templ.sendOAuthRequest(request, params, secrets));
        EasyMock.verify(client, resource, params, secrets);
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
            return URLDecoder.decode(s, "UTF-8");
        }
        catch (UnsupportedEncodingException e)
        {
            throw new AssertionError("UTF 8 encoding not supported: " + e);
        }
    }

    /**
     * Prepares a parameters object for the request token call.
     *
     * @return the mock object
     */
    private OAuthParameters prepareParametersForRequestToken()
    {
        OAuthParameters params = EasyMock.createMock(OAuthParameters.class);
        EasyMock.expect(params.consumerKey(OAuthTemplate.CONSUMER_KEY))
                .andReturn(params);
        EasyMock.expect(params.nonce()).andReturn(params);
        EasyMock.expect(params.signatureMethod("HMAC-SHA1")).andReturn(params);
        EasyMock.expect(params.callback("oob")).andReturn(params);
        return params;
    }

    /**
     * Prepares a secrets object for the request token call.
     *
     * @return the mock object
     */
    private OAuthSecrets prepareSecretsForRequestToken(OAuthParameters params)
    {
        OAuthSecrets secrets = EasyMock.createMock(OAuthSecrets.class);
        EasyMock.expect(secrets.consumerSecret(OAuthTemplate.CONSUMER_SECRET))
                .andReturn(secrets);
        return secrets;
    }

    /**
     * Tests a successful authorization.
     */
    @Test
    public void testAuthorizeSuccess()
    {
        Client client = EasyMock.createMock(Client.class);
        OAuthCallback callback = EasyMock.createMock(OAuthCallback.class);
        OAuthTemplAuthorizeTestImpl templ =
                new OAuthTemplAuthorizeTestImpl(OAUTH_URI, SERVICE_URI, client);
        // Step get request token
        OAuthParameters params = prepareParametersForRequestToken();
        OAuthSecrets secrets = prepareSecretsForRequestToken(params);
        EasyMock.replay(params, secrets);
        templ.addMockParameters(params);
        templ.addMockSecrets(secrets);
        templ.expectSend("OAuthGetRequestToken", params, secrets,
                "oauth_token=" + OAUTH_TOKEN + "&oauth_token_secret="
                        + OAUT_TOKEN_SECRET);

        // Step authorization
        final String verificationCode = "93q7its-nqI-Dcyt17NZNKA4";
        EasyMock.expect(callback.getVerificationCode(verificationURI))
                .andReturn(verificationCode);

        // Step get access token
        OAuthParameters paramsAcc = EasyMock.createMock(OAuthParameters.class);
        EasyMock.expect(paramsAcc.consumerKey(OAuthTemplate.CONSUMER_KEY))
                .andReturn(paramsAcc);
        EasyMock.expect(paramsAcc.token(decode(OAUTH_TOKEN))).andReturn(
                paramsAcc);
        EasyMock.expect(paramsAcc.verifier(verificationCode)).andReturn(
                paramsAcc);
        EasyMock.expect(paramsAcc.signatureMethod("HMAC-SHA1")).andReturn(
                paramsAcc);
        OAuthSecrets secretsAcc = EasyMock.createMock(OAuthSecrets.class);
        EasyMock.expect(secretsAcc.tokenSecret(decode(OAUT_TOKEN_SECRET)))
                .andReturn(secretsAcc);
        EasyMock.expect(
                secretsAcc.consumerSecret(OAuthTemplate.CONSUMER_SECRET))
                .andReturn(secretsAcc);
        EasyMock.replay(paramsAcc, secretsAcc);
        templ.addMockParameters(paramsAcc);
        templ.addMockSecrets(secretsAcc);
        final String oauthAccessToken =
                "1%2F7e34UZ4XPRcgg2SJWGYJc_PKev_yMIBkRQpZyNa8kcF";
        final String oauthAccessTokenSecret = "rpSAVVHNIOnbEzJ7jC0ACNdJ";
        templ.expectSend("OAuthGetAccessToken", paramsAcc, secretsAcc,
                "oauth_token=" + oauthAccessToken + "&oauth_token_secret="
                        + oauthAccessTokenSecret);

        EasyMock.replay(client, callback);
        OAuthTokens tokens = templ.authorize(callback);
        assertEquals("Wrong parameters token", decode(oauthAccessToken),
                tokens.getParameterToken());
        assertEquals("Wrong secret token", decode(oauthAccessTokenSecret),
                tokens.getSecretToken());
        EasyMock.verify(client, callback, params, secrets, paramsAcc,
                secretsAcc);
    }

    /**
     * Tests an authorization operation that is aborted by the user.
     */
    @Test
    public void testAuthorizeAborted()
    {
        Client client = EasyMock.createMock(Client.class);
        OAuthCallback callback = EasyMock.createMock(OAuthCallback.class);
        OAuthTemplAuthorizeTestImpl templ =
                new OAuthTemplAuthorizeTestImpl(OAUTH_URI, SERVICE_URI, client);
        OAuthParameters params = prepareParametersForRequestToken();
        OAuthSecrets secrets = prepareSecretsForRequestToken(params);
        EasyMock.replay(params, secrets);
        templ.addMockParameters(params);
        templ.addMockSecrets(secrets);
        templ.expectSend("OAuthGetRequestToken", params, secrets,
                "oauth_token=" + OAUTH_TOKEN + "&oauth_token_secret="
                        + OAUT_TOKEN_SECRET);
        EasyMock.expect(callback.getVerificationCode(verificationURI))
                .andReturn(null);
        EasyMock.replay(client, callback);
        assertNull("Got tokens", templ.authorize(callback));
        EasyMock.verify(client, callback, params, secrets);
    }

    /**
     * Tests createAuthorizeURI() if a syntax exception is thrown.
     */
    @Test(expected = IllegalStateException.class)
    public void testCreateAuthorizeURISyntaxEx()
    {
        OAuthTemplate templ =
                new OAuthTemplate("not a valid uri!!", SERVICE_URI);
        templ.createAuthorizeURI("aToken");
    }

    /**
     * Tests whether a filter object can be created from tokens.
     */
    @Test
    public void testCreateOAuthFilterFromTokens()
    {
        final OAuthParameters params =
                EasyMock.createMock(OAuthParameters.class);
        final OAuthSecrets secrets = EasyMock.createMock(OAuthSecrets.class);
        final OAuthClientFilter filter = createDummyFilter();
        EasyMock.expect(params.consumerKey(OAuthTemplate.CONSUMER_KEY))
                .andReturn(params);
        EasyMock.expect(params.token(OAUTH_TOKEN)).andReturn(params);
        EasyMock.expect(params.signatureMethod("HMAC-SHA1")).andReturn(params);
        EasyMock.expect(secrets.tokenSecret(OAUT_TOKEN_SECRET)).andReturn(
                secrets);
        EasyMock.expect(secrets.consumerSecret(OAuthTemplate.CONSUMER_SECRET))
                .andReturn(secrets);
        EasyMock.replay(params, secrets);
        OAuthTemplate templ = new OAuthTemplate(OAUTH_URI, SERVICE_URI)
        {
            @Override
            OAuthParameters createParameters()
            {
                return params;
            }

            @Override
            OAuthSecrets createSecrets()
            {
                return secrets;
            }

            @Override
            OAuthClientFilter createOAuthFilter(OAuthParameters theParams,
                    OAuthSecrets theSecrets)
            {
                assertSame("Wrong parameters", params, theParams);
                assertSame("Wrong secrets", secrets, theSecrets);
                return filter;
            }
        };
        assertSame("Wrong filter", filter,
                templ.createOAuthFilterFromTokens(TOKENS));
        EasyMock.verify(params, secrets);
    }

    /**
     * Prepares a mock object for a resource processor.
     *
     * @param proc the processor mock
     * @param resource the resource
     * @param resp the response
     * @param status the status code of the response
     * @return a mock object for the response
     */
    private ClientResponse prepareResourceProcessor(ResourceProcessor proc,
            WebResource resource, ClientResponse.Status status)
    {
        try
        {
            ClientResponse resp = EasyMock.createMock(ClientResponse.class);
            EasyMock.expect(proc.doWithResource(resource)).andReturn(resp);
            EasyMock.expect(resp.getStatus()).andReturn(status.getStatusCode());
            if (status != ClientResponse.Status.UNAUTHORIZED)
            {
                proc.processResponse(resp);
            }
            return resp;
        }
        catch (NotAuthorizedException nex)
        {
            // cannot happen
            throw new AssertionError();
        }
    }

    /**
     * Tests execute() if the callback provides the correct tokens immediately.
     */
    @Test
    public void testExecuteCorrectTokensAvailable()
    {
        Client client = EasyMock.createMock(Client.class);
        OAuthCallback callback = EasyMock.createMock(OAuthCallback.class);
        ResourceProcessor proc = EasyMock.createMock(ResourceProcessor.class);
        WebResource resource = EasyMock.createMock(WebResource.class);
        EasyMock.expect(callback.getTokens()).andReturn(TOKENS);
        OAuthTemplateExecuteTestImpl templ =
                new OAuthTemplateExecuteTestImpl(OAUTH_URI, SERVICE_URI, client);
        OAuthClientFilter filter = templ.installMockFilter();
        EasyMock.expect(client.resource(SERVICE_URI)).andReturn(resource);
        resource.addFilter(filter);
        ClientResponse resp =
                prepareResourceProcessor(proc, resource,
                        ClientResponse.Status.CREATED);
        EasyMock.replay(client, callback, proc, resource, resp);
        assertTrue("Wrong result", templ.execute(proc, callback));
        assertSame("Filter not cached", filter, templ.getOAuthFilter());
        EasyMock.verify(client, callback, proc, resource, resp);
    }

    /**
     * Tests execute() if the callback does not provide tokens.
     */
    @Test
    public void testExecuteNoTokensAvailable()
    {
        Client client = EasyMock.createMock(Client.class);
        OAuthCallback callback = EasyMock.createMock(OAuthCallback.class);
        ResourceProcessor proc = EasyMock.createMock(ResourceProcessor.class);
        WebResource resource = EasyMock.createMock(WebResource.class);
        EasyMock.expect(callback.getTokens()).andReturn(null);
        OAuthTemplateExecuteTestImpl templ =
                new OAuthTemplateExecuteTestImpl(OAUTH_URI, SERVICE_URI, client);
        templ.expectAuthorize(callback, true);
        OAuthClientFilter filter = templ.installMockFilter();
        EasyMock.expect(client.resource(SERVICE_URI)).andReturn(resource);
        resource.addFilter(filter);
        ClientResponse resp =
                prepareResourceProcessor(proc, resource,
                        ClientResponse.Status.CREATED);
        callback.setTokens(TOKENS);
        EasyMock.replay(client, callback, proc, resource, resp);
        assertTrue("Wrong result", templ.execute(proc, callback));
        EasyMock.verify(client, callback, proc, resource, resp);
    }

    /**
     * Tests execute() if the callback provides invalid tokens.
     */
    @Test
    public void testExecuteInvalidTokensAvailable()
    {
        Client client = EasyMock.createMock(Client.class);
        OAuthCallback callback = EasyMock.createMock(OAuthCallback.class);
        ResourceProcessor proc = EasyMock.createMock(ResourceProcessor.class);
        WebResource resource = EasyMock.createMock(WebResource.class);
        EasyMock.expect(callback.getTokens()).andReturn(TOKENS);
        OAuthTemplateExecuteTestImpl templ =
                new OAuthTemplateExecuteTestImpl(OAUTH_URI, SERVICE_URI, client);
        templ.expectAuthorize(callback, true);
        OAuthClientFilter filter = templ.installMockFilter();
        EasyMock.expect(client.resource(SERVICE_URI)).andReturn(resource)
                .times(2);
        resource.addFilter(filter);
        EasyMock.expectLastCall().times(2);
        ClientResponse resp1 =
                prepareResourceProcessor(proc, resource,
                        ClientResponse.Status.UNAUTHORIZED);
        ClientResponse resp2 =
                prepareResourceProcessor(proc, resource,
                        ClientResponse.Status.CREATED);
        callback.setTokens(TOKENS);
        EasyMock.replay(client, callback, proc, resource, resp1, resp2);
        assertTrue("Wrong result", templ.execute(proc, callback));
        EasyMock.verify(client, callback, proc, resource, resp1, resp2);
    }

    /**
     * Tests execute() if the authorization is aborted.
     */
    @Test
    public void testExecuteAuthorizationAborted()
    {
        Client client = EasyMock.createMock(Client.class);
        OAuthCallback callback = EasyMock.createMock(OAuthCallback.class);
        ResourceProcessor proc = EasyMock.createMock(ResourceProcessor.class);
        EasyMock.expect(callback.getTokens()).andReturn(null);
        OAuthTemplateExecuteTestImpl templ =
                new OAuthTemplateExecuteTestImpl(OAUTH_URI, SERVICE_URI, client);
        templ.expectAuthorize(callback, false);
        EasyMock.replay(client, callback, proc);
        assertFalse("Wrong result", templ.execute(proc, callback));
        EasyMock.verify(client, callback, proc);
    }

    /**
     * Tests execute() if the processor does not return a response object.
     */
    @Test
    public void testExecuteProcessorNullResult() throws NotAuthorizedException
    {
        Client client = EasyMock.createMock(Client.class);
        OAuthCallback callback = EasyMock.createMock(OAuthCallback.class);
        ResourceProcessor proc = EasyMock.createMock(ResourceProcessor.class);
        WebResource resource = EasyMock.createMock(WebResource.class);
        EasyMock.expect(callback.getTokens()).andReturn(TOKENS);
        OAuthTemplateExecuteTestImpl templ =
                new OAuthTemplateExecuteTestImpl(OAUTH_URI, SERVICE_URI, client);
        OAuthClientFilter filter = templ.installMockFilter();
        EasyMock.expect(client.resource(SERVICE_URI)).andReturn(resource);
        resource.addFilter(filter);
        EasyMock.expect(proc.doWithResource(resource)).andReturn(null);
        EasyMock.replay(client, callback, proc, resource);
        assertTrue("Wrong result", templ.execute(proc, callback));
        EasyMock.verify(client, callback, proc, resource);
    }

    /**
     * Tests execute() if the processor throws a not authorized exception.
     */
    @Test
    public void testExecuteProcessorNotAuthorizedException()
            throws NotAuthorizedException
    {
        Client client = EasyMock.createMock(Client.class);
        OAuthCallback callback = EasyMock.createMock(OAuthCallback.class);
        ResourceProcessor proc = EasyMock.createMock(ResourceProcessor.class);
        WebResource resource = EasyMock.createMock(WebResource.class);
        EasyMock.expect(callback.getTokens()).andReturn(TOKENS);
        OAuthTemplateExecuteTestImpl templ =
                new OAuthTemplateExecuteTestImpl(OAUTH_URI, SERVICE_URI, client);
        OAuthClientFilter filter = templ.installMockFilter();
        EasyMock.expect(client.resource(SERVICE_URI)).andReturn(resource)
                .times(2);
        resource.addFilter(filter);
        EasyMock.expectLastCall().times(2);
        EasyMock.expect(proc.doWithResource(resource)).andThrow(
                new NotAuthorizedException());
        EasyMock.expect(proc.doWithResource(resource)).andReturn(null);
        templ.expectAuthorize(callback, true);
        callback.setTokens(TOKENS);
        EasyMock.replay(client, callback, proc, resource);
        assertTrue("Wrong result", templ.execute(proc, callback));
        EasyMock.verify(client, callback, proc, resource);
    }

    /**
     * Tests execute() if an OAuth filter is already available.
     */
    @Test
    public void testExecuteFilterAvailable() throws NotAuthorizedException
    {
        Client client = EasyMock.createMock(Client.class);
        OAuthCallback callback = EasyMock.createMock(OAuthCallback.class);
        ResourceProcessor proc = EasyMock.createMock(ResourceProcessor.class);
        WebResource resource = EasyMock.createMock(WebResource.class);
        final OAuthClientFilter filter = createDummyFilter();
        OAuthTemplate templ = new OAuthTemplate(OAUTH_URI, SERVICE_URI, client)
        {
            @Override
            public OAuthClientFilter getOAuthFilter()
            {
                return filter;
            }
        };
        EasyMock.expect(client.resource(SERVICE_URI)).andReturn(resource);
        resource.addFilter(filter);
        EasyMock.expect(proc.doWithResource(resource)).andReturn(null);
        EasyMock.replay(client, callback, proc, resource);
        assertTrue("Wrong result", templ.execute(proc, callback));
        EasyMock.verify(client, callback, proc, resource);
    }

    /**
     * Tests execute() if not processor is provided.
     */
    @Test
    public void testExecuteNoProcessor()
    {
        OAuthCallback callback = EasyMock.createMock(OAuthCallback.class);
        EasyMock.replay(callback);
        OAuthTemplate templ = new OAuthTemplate(OAUTH_URI, SERVICE_URI);
        try
        {
            templ.execute(null, callback);
            fail("Missing processor not detected!");
        }
        catch (NullPointerException npex)
        {
            EasyMock.verify(callback);
        }
    }

    /**
     * Tests execute() if no callback object is provided.
     */
    @Test
    public void testExecuteNoCallback()
    {
        ResourceProcessor proc = EasyMock.createMock(ResourceProcessor.class);
        EasyMock.replay(proc);
        OAuthTemplate templ = new OAuthTemplate(OAUTH_URI, SERVICE_URI);
        try
        {
            templ.execute(proc, null);
            fail("Missing callback not detected!");
        }
        catch (NullPointerException npex)
        {
            EasyMock.verify(proc);
        }
    }

    /**
     * A test implementation of the template with enhanced mocking facilities
     * used for tests of the authorize() implementation.
     */
    private static class OAuthTemplAuthorizeTestImpl extends OAuthTemplate
    {
        /** The key for the request URI. */
        private static final String KEY_REQUEST = "request";

        /** The key for the response. */
        private static final String KEY_RESPONSE = "response";

        /** The key for the parameters. */
        private static final String KEY_PARAMS = "parameters";

        /** The key for the secrets. */
        private static final String KEY_SECRETS = "secrets";

        /** A list with mock parameter objects. */
        private List<OAuthParameters> mockParameters;

        /** A list with mock secret objects. */
        private List<OAuthSecrets> mockSecrets;

        /** A list with data about expected send invocations. */
        private List<Map<String, Object>> sendData;

        public OAuthTemplAuthorizeTestImpl(String oauthURI, String svcURI,
                Client client)
        {
            super(oauthURI, svcURI, client);
        }

        /**
         * Adds the given mock parameters object. It is returned by the
         * createParameters() method.
         *
         * @param mock the mock object to be added
         */
        public void addMockParameters(OAuthParameters mock)
        {
            if (mockParameters == null)
            {
                mockParameters = new LinkedList<OAuthParameters>();
            }
            mockParameters.add(mock);
        }

        /**
         * Adds the given mock secrets object. It is returned by the
         * createSecrets() method.
         *
         * @param mock the mock object to be added
         */
        public void addMockSecrets(OAuthSecrets mock)
        {
            if (mockSecrets == null)
            {
                mockSecrets = new LinkedList<OAuthSecrets>();
            }
            mockSecrets.add(mock);
        }

        /**
         * Prepares the object to expect a send operation.
         *
         * @param uri the URI
         * @param params the parameters
         * @param secrets the secrets
         * @param response the response
         */
        public void expectSend(String uri, OAuthParameters params,
                OAuthSecrets secrets, String response)
        {
            Map<String, Object> data = new HashMap<String, Object>();
            data.put(KEY_REQUEST, uri);
            data.put(KEY_PARAMS, params);
            data.put(KEY_SECRETS, secrets);
            data.put(KEY_RESPONSE, response);
            if (sendData == null)
            {
                sendData = new LinkedList<Map<String, Object>>();
            }
            sendData.add(data);
        }

        /**
         * Either returns a mock parameters object or calls the super method.
         */
        @Override
        OAuthParameters createParameters()
        {
            if (mockParameters != null)
            {
                return mockParameters.remove(0);
            }
            return super.createParameters();
        }

        /**
         * Either returns a mock secrets object or calls the super method.
         */
        @Override
        OAuthSecrets createSecrets()
        {
            if (mockSecrets != null)
            {
                return mockSecrets.remove(0);
            }
            return super.createSecrets();
        }

        /**
         * Either checks the parameters and returns a mock request or calls the
         * super method.
         */
        @Override
        String sendOAuthRequest(String urlSuffix, OAuthParameters params,
                OAuthSecrets secrets)
        {
            if (sendData == null)
            {
                return super.sendOAuthRequest(urlSuffix, params, secrets);
            }
            Map<String, Object> data = sendData.remove(0);
            assertEquals("Wrong request URI", data.get(KEY_REQUEST), urlSuffix);
            assertEquals("Wrong params", data.get(KEY_PARAMS), params);
            assertEquals("Wrong secrets", data.get(KEY_SECRETS), secrets);
            return (String) data.get(KEY_RESPONSE);
        }
    }

    /**
     * A test implementation for testing the execute() method.
     */
    private static class OAuthTemplateExecuteTestImpl extends OAuthTemplate
    {
        /** The expected callback object. */
        private OAuthCallback expCallback;

        /** The mock filter. */
        private OAuthClientFilter filter;

        /** A flag whether authorization is successful. */
        private boolean authorizationSuccessful;

        public OAuthTemplateExecuteTestImpl(String oauthURI, String svcURI,
                Client client)
        {
            super(oauthURI, svcURI, client);
        }

        /**
         * Prepares this object to expect an authorize() call.
         *
         * @param cb the expected callback
         * @param success a flag whether authorization is successful
         */
        public void expectAuthorize(OAuthCallback cb, boolean success)
        {
            expCallback = cb;
            authorizationSuccessful = success;
        }

        /**
         * Installs a mock filter object.
         *
         * @return the mock filter
         */
        public OAuthClientFilter installMockFilter()
        {
            filter = createDummyFilter();
            return filter;
        }

        /**
         * Checks the parameters and returns a standard tokens object (depending
         * on the success flag).
         */
        @Override
        OAuthTokens authorize(OAuthCallback callback)
        {
            assertSame("Wrong callback", expCallback, callback);
            expCallback = null;
            return authorizationSuccessful ? TOKENS : null;
        }

        /**
         * Checks the parameters and returns the dummy filter.
         */
        @Override
        OAuthClientFilter createOAuthFilterFromTokens(OAuthTokens tokens)
        {
            assertEquals("Wrong tokens", TOKENS, tokens);
            return filter;
        }
    }
}
