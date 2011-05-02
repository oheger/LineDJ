package de.oliver_heger.mediastore.localstore.impl;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.io.IOException;

import org.junit.After;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;

import de.oliver_heger.mediastore.RemoteMediaStoreTestHelper;
import de.oliver_heger.mediastore.oauth.OAuthTokens;

/**
 * Test class for {@code OAuthFileTokenStore}.
 *
 * @author Oliver Heger
 * @version $Id: $
 */
public class TestOAuthFileTokenStore
{
    /** The name of the test sub directory below the user's home directory. */
    private static final String TEST_DIR = ".jplaya-test";

    /** The relative path to the test token file. */
    private static final String FILE_PATH = TEST_DIR + "/oauth/tokens.txt";

    /** Constant for a test exception message. */
    private static final String EX_MSG = "Test Exception!";

    /** A test tokens object. */
    private static OAuthTokens tokens;

    /** The directory with the token file. */
    private File subDir;

    @BeforeClass
    public static void setUpBeforeClass() throws Exception
    {
        tokens = new OAuthTokens("testParamToken", "testSecretToken007");
    }

    @Before
    public void setUp() throws Exception
    {
        File home = new File(System.getProperty("user.home"));
        subDir = new File(home, TEST_DIR);
    }

    @After
    public void tearDown() throws Exception
    {
        if (subDir.exists())
        {
            RemoteMediaStoreTestHelper.removeDir(subDir);
        }
    }

    /**
     * Tries to create an instance without a path.
     */
    @Test(expected = NullPointerException.class)
    public void testInitNoPath()
    {
        new OAuthFileTokenStore(null);
    }

    /**
     * Tests loadTokens() if no tokens have been stored so far.
     */
    @Test
    public void testLoadTokensNonExisting()
    {
        OAuthFileTokenStore store = new OAuthFileTokenStore(FILE_PATH);
        assertNull("Got tokens", store.loadTokens());
    }

    /**
     * Tests whether an error is appropriately handled by loadTokens().
     */
    @Test
    public void testLoadTokensError()
    {
        OAuthFileTokenStore store = new OAuthFileTokenStore(FILE_PATH)
        {
            @Override
            OAuthTokens doLoadTokens(File tokenFile) throws IOException
            {
                throw new IOException(EX_MSG);
            }
        };
        assertNull("Got tokens", store.loadTokens());
    }

    /**
     * Tries to pass a null object to saveTokens().
     */
    @Test(expected = NullPointerException.class)
    public void testSaveTokenNull()
    {
        OAuthFileTokenStore store = new OAuthFileTokenStore(FILE_PATH);
        store.saveTokens(null);
    }

    /**
     * Tests saveToken() if the sub directory structure has to be created.
     */
    @Test
    public void testSaveTokenCreatePath()
    {
        assertFalse("Directory already exists", subDir.exists());
        OAuthFileTokenStore store = new OAuthFileTokenStore(FILE_PATH);
        assertTrue("Wrong result", store.saveTokens(tokens));
        assertTrue("Directory not created", subDir.exists());
        assertEquals("Wrong tokens", tokens, store.loadTokens());
    }

    /**
     * Tests whether tokens can be overridden.
     */
    @Test
    public void testSaveTokensOverride()
    {
        OAuthFileTokenStore store = new OAuthFileTokenStore(FILE_PATH);
        assertTrue("Wrong result (1)",
                store.saveTokens(new OAuthTokens("some param", "some secret")));
        assertTrue("Wrong result (2)", store.saveTokens(tokens));
        assertEquals("Wrong tokens", tokens, store.loadTokens());
    }

    /**
     * Tests saveTokens() if the sub directory structure cannot be created.
     */
    @Test
    public void testSaveTokensErrCreateDir()
    {
        OAuthFileTokenStore store = new OAuthFileTokenStore(FILE_PATH)
        {
            @Override
            boolean createSubDirectories(File tokenFile)
            {
                return false;
            };
        };
        assertFalse("Could save tokens", store.saveTokens(tokens));
    }

    /**
     * Tests saveTokens() if an IO error occurs.
     */
    @Test
    public void testSaveTokensError()
    {
        OAuthFileTokenStore store = new OAuthFileTokenStore(FILE_PATH)
        {
            @Override
            void doSaveTokens(File tokenFile, OAuthTokens tokens)
                    throws IOException
            {
                throw new IOException(EX_MSG);
            }
        };
        assertFalse("Wrong result", store.saveTokens(tokens));
    }
}
