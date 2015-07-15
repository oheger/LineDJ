package de.oliver_heger.mediastore.oauth;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.io.IOException;

import org.junit.Before;
import org.junit.Test;

import de.oliver_heger.mediastore.RemoteMediaStoreTestHelper;
import de.oliver_heger.mediastore.oauth.OAuthTokens;

/**
 * Test class for {@code OAuthTokens}.
 *
 * @author Oliver Heger
 * @version $Id: $
 */
public class TestOAuthTokens
{
    /** Constant for the parameter token. */
    private static final String PARAM_TOKEN = "TestParamToken";

    /** Constant for the secret token. */
    private static final String SECRET_TOKEN = "TopSecretTestToken!!!";

    /** The object to be tested. */
    private OAuthTokens tokens;

    @Before
    public void setUp() throws Exception
    {
        tokens = new OAuthTokens(PARAM_TOKEN, SECRET_TOKEN);
    }

    /**
     * Tests a newly created instance.
     */
    @Test
    public void testInit()
    {
        assertEquals("Wrong param token", PARAM_TOKEN,
                tokens.getParameterToken());
        assertEquals("Wrong secret token", SECRET_TOKEN,
                tokens.getSecretToken());
    }

    /**
     * Tests equals() if the expected result is true.
     */
    @Test
    public void testEqualsTrue()
    {
        RemoteMediaStoreTestHelper.checkEquals(tokens, tokens, true);
        OAuthTokens tok2 = new OAuthTokens(PARAM_TOKEN, SECRET_TOKEN);
        RemoteMediaStoreTestHelper.checkEquals(tokens, tok2, true);
    }

    /**
     * Tests equals() if the expected result is false.
     */
    @Test
    public void testEqualsFalse()
    {
        OAuthTokens tok2 = new OAuthTokens(null, null);
        RemoteMediaStoreTestHelper.checkEquals(tokens, tok2, false);
        tok2 = new OAuthTokens(PARAM_TOKEN, null);
        RemoteMediaStoreTestHelper.checkEquals(tokens, tok2, false);
        tok2 = new OAuthTokens(null, SECRET_TOKEN);
        RemoteMediaStoreTestHelper.checkEquals(tokens, tok2, false);
        tok2 = new OAuthTokens("other", SECRET_TOKEN);
        RemoteMediaStoreTestHelper.checkEquals(tokens, tok2, false);
        tok2 = new OAuthTokens(PARAM_TOKEN, "other");
        RemoteMediaStoreTestHelper.checkEquals(tokens, tok2, false);
    }

    /**
     * Tests equals() with other objects.
     */
    @Test
    public void testEqualsTrivial()
    {
        RemoteMediaStoreTestHelper.checkEqualsTrivial(tokens);
    }

    /**
     * Tests the string representation.
     */
    @Test
    public void testToString()
    {
        String s = tokens.toString();
        assertTrue("Param token not found: " + s,
                s.contains("parameterToken=" + PARAM_TOKEN));
        assertTrue("Secret token not found: " + s,
                s.contains("secretToken=" + SECRET_TOKEN));
    }

    /**
     * Tests whether an instance can be serialized.
     */
    @Test
    public void testSerialization() throws IOException
    {
        RemoteMediaStoreTestHelper.checkSerialization(tokens);
    }
}
