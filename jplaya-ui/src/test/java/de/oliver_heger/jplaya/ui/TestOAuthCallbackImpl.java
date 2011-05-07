package de.oliver_heger.jplaya.ui;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;

import java.awt.Desktop;
import java.io.IOException;
import java.net.URI;

import net.sf.jguiraffe.gui.app.ApplicationBuilderData;
import net.sf.jguiraffe.gui.app.ApplicationContext;
import net.sf.jguiraffe.gui.builder.Builder;
import net.sf.jguiraffe.gui.builder.BuilderException;
import net.sf.jguiraffe.gui.builder.utils.GUISynchronizer;
import net.sf.jguiraffe.gui.builder.utils.MessageOutput;
import net.sf.jguiraffe.gui.builder.window.Window;
import net.sf.jguiraffe.locators.Locator;
import net.sf.jguiraffe.locators.LocatorException;

import org.apache.commons.lang3.mutable.MutableObject;
import org.easymock.EasyMock;
import org.easymock.IAnswer;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;

import de.oliver_heger.mediastore.localstore.OAuthTokenStore;
import de.oliver_heger.mediastore.oauth.OAuthTokens;

/**
 * Test class for {@code OAuthCallbackImpl}.
 *
 * @author Oliver Heger
 * @version $Id: $
 */
public class TestOAuthCallbackImpl
{
    /** Constant for a test exception message. */
    private static final String EX_MSG = "Test exception!";

    /** Constant for the verification code. */
    private static final String VERIFICATION_CODE = "Yes, it is true!";

    /** Constant for the URI for retrieving the verification code. */
    private static URI verifyURI;

    /** A test token instance. */
    private static OAuthTokens tokens;

    /** A mock for the token store. */
    private OAuthTokenStore store;

    /** A mock for the application context. */
    private ApplicationContext appCtx;

    /** A mock for the script locator. */
    private Locator locator;

    @BeforeClass
    public static void setUpBeforeClass() throws Exception
    {
        verifyURI = new URI("https://check-the-code.com/");
        tokens = new OAuthTokens("paramToken", "top-secret token");
    }

    @Before
    public void setUp() throws Exception
    {
        store = EasyMock.createMock(OAuthTokenStore.class);
        appCtx = EasyMock.createMock(ApplicationContext.class);
        locator = EasyMock.createMock(Locator.class);
    }

    /**
     * Tries to create an instance without a token store.
     */
    @Test(expected = NullPointerException.class)
    public void testInitNoStore()
    {
        new OAuthCallbackImpl(null, appCtx, locator);
    }

    /**
     * Tries to create an instance without an application context.
     */
    @Test(expected = NullPointerException.class)
    public void testInitNoAppCtx()
    {
        new OAuthCallbackImpl(store, null, locator);
    }

    /**
     * Tries to create an instance without a locator.
     */
    @Test(expected = NullPointerException.class)
    public void testInitNoLocator()
    {
        new OAuthCallbackImpl(store, appCtx, null);
    }

    /**
     * Tests whether a desktop object can be fetched.
     */
    @Test
    public void testGetDesktop()
    {
        if (Desktop.isDesktopSupported())
        {
            OAuthCallbackImpl cb =
                    new OAuthCallbackImpl(store, appCtx, locator);
            assertNotNull("Got no desktop", cb.getDesktop());
        }
    }

    /**
     * Tests whether a runnable for producing an error message can be created.
     */
    @Test
    public void testCreateErrorRunnable()
    {
        EasyMock.expect(
                appCtx.messageBox(VERIFICATION_CODE,
                        OAuthCallbackImpl.RES_ERRMSG_TITLE,
                        MessageOutput.MESSAGE_ERROR, MessageOutput.BTN_OK))
                .andReturn(0);
        EasyMock.replay(appCtx, store, locator);
        OAuthCallbackImpl cb = new OAuthCallbackImpl(store, appCtx, locator);
        Runnable runnable = cb.createErrorRunnable(VERIFICATION_CODE);
        runnable.run();
        EasyMock.verify(appCtx, store, locator);
    }

    /**
     * Tests whether the verification code can be queried successfully.
     */
    @Test
    public void testGetVerificationCodeSuccess() throws BuilderException,
            IOException
    {
        Window window = EasyMock.createMock(Window.class);
        Builder builder = EasyMock.createMock(Builder.class);
        final ApplicationBuilderData builderData = new ApplicationBuilderData();
        EasyMock.expect(appCtx.newBuilder()).andReturn(builder);
        EasyMock.expect(appCtx.initBuilderData()).andReturn(builderData);
        EasyMock.expect(builder.buildWindow(locator, builderData)).andReturn(
                window);
        window.open();
        EasyMock.expectLastCall().andAnswer(new IAnswer<Object>()
        {
            @Override
            public Object answer() throws Throwable
            {
                @SuppressWarnings("unchecked")
                MutableObject<String> model =
                        (MutableObject<String>) builderData.getProperties()
                                .get(OAuthCallbackImpl.KEY_MODEL);
                assertNotNull("No data model", model);
                model.setValue(VERIFICATION_CODE);
                return null;
            }
        });
        EasyMock.replay(window, builder, appCtx, store, locator);
        OAuthCallbackTestImpl cb =
                new OAuthCallbackTestImpl(store, appCtx, locator);
        Desktop desktop = cb.installMockDesktop();
        desktop.browse(verifyURI);
        EasyMock.replay(desktop);
        assertEquals("Wrong code", VERIFICATION_CODE,
                cb.getVerificationCode(verifyURI));
        EasyMock.verify(window, builder, desktop, appCtx, store, locator);
    }

    /**
     * Tests getVerificationCode() if the user cancels this process.
     */
    @Test
    public void testGetVerificationCodeCanceled() throws BuilderException,
            IOException
    {
        Window window = EasyMock.createMock(Window.class);
        Builder builder = EasyMock.createMock(Builder.class);
        final ApplicationBuilderData builderData = new ApplicationBuilderData();
        EasyMock.expect(appCtx.newBuilder()).andReturn(builder);
        EasyMock.expect(appCtx.initBuilderData()).andReturn(builderData);
        EasyMock.expect(builder.buildWindow(locator, builderData)).andReturn(
                window);
        window.open();
        EasyMock.replay(window, builder, appCtx, store, locator);
        OAuthCallbackTestImpl cb =
                new OAuthCallbackTestImpl(store, appCtx, locator);
        Desktop desktop = cb.installMockDesktop();
        desktop.browse(verifyURI);
        EasyMock.replay(desktop);
        assertNull("Got a code", cb.getVerificationCode(verifyURI));
        EasyMock.verify(window, builder, desktop, appCtx, store, locator);
    }

    /**
     * Helper method for testing getVerificationCode() if the builder invocation
     * causes an error.
     *
     * @param t the exception to be thrown by the builder
     * @throws BuilderException if a builder exception occurs
     * @throws IOException if an IO error occurs
     */
    private void checkGetVerificationCodeBuilderError(Throwable t)
            throws BuilderException, IOException
    {
        Builder builder = EasyMock.createMock(Builder.class);
        OAuthCallbackTestImpl cb =
                new OAuthCallbackTestImpl(store, appCtx, locator);
        Runnable errRun =
                cb.installErrorRunnable(OAuthCallbackImpl.RES_ERRMSG_BUILDER);
        GUISynchronizer sync = EasyMock.createMock(GUISynchronizer.class);
        ApplicationBuilderData builderData = new ApplicationBuilderData();
        EasyMock.expect(appCtx.newBuilder()).andReturn(builder);
        EasyMock.expect(appCtx.initBuilderData()).andReturn(builderData);
        EasyMock.expect(builder.buildWindow(locator, builderData)).andThrow(t);
        EasyMock.expect(appCtx.getGUISynchronizer()).andReturn(sync);
        sync.asyncInvoke(errRun);
        EasyMock.replay(builder, errRun, sync, appCtx, store, locator);
        Desktop desktop = cb.installMockDesktop();
        desktop.browse(verifyURI);
        EasyMock.replay(desktop);
        assertNull("Got a code", cb.getVerificationCode(verifyURI));
        EasyMock.verify(builder, desktop, errRun, sync, appCtx, store, locator);
    }

    /**
     * Tests getVerificationCode() if the builder script cannot be executed.
     */
    @Test
    public void testGetVerificationCodeBuilderError() throws BuilderException,
            IOException
    {
        checkGetVerificationCodeBuilderError(new BuilderException(EX_MSG));
    }

    /**
     * Tests getVerificationCode() if the builder throws a locator exception.
     */
    @Test
    public void testGetVerificationCodeBuilderLocatorEx()
            throws BuilderException, IOException
    {
        checkGetVerificationCodeBuilderError(new LocatorException(EX_MSG));
    }

    /**
     * Tests getVerificationCode() if the Desktop class throws an exception.
     */
    @Test
    public void testGetVerificationCodeDesktopError() throws BuilderException,
            IOException
    {
        OAuthCallbackTestImpl cb =
                new OAuthCallbackTestImpl(store, appCtx, locator);
        Runnable errRun =
                cb.installErrorRunnable(OAuthCallbackImpl.RES_ERRMSG_IO);
        GUISynchronizer sync = EasyMock.createMock(GUISynchronizer.class);
        EasyMock.expect(appCtx.getGUISynchronizer()).andReturn(sync);
        sync.asyncInvoke(errRun);
        EasyMock.replay(errRun, sync, appCtx, store, locator);
        Desktop desktop = cb.installMockDesktop();
        desktop.browse(verifyURI);
        EasyMock.expectLastCall().andThrow(new IOException(EX_MSG));
        EasyMock.replay(desktop);
        assertNull("Got a code", cb.getVerificationCode(verifyURI));
        EasyMock.verify(desktop, errRun, sync, appCtx, store, locator);
    }

    /**
     * Tests whether the authorization tokens can be queried.
     */
    @Test
    public void testGetTokens()
    {
        EasyMock.expect(store.loadTokens()).andReturn(tokens);
        EasyMock.replay(store, appCtx, locator);
        OAuthCallbackImpl cb = new OAuthCallbackImpl(store, appCtx, locator);
        assertSame("Wrong tokens", tokens, cb.getTokens());
        EasyMock.verify(store, appCtx, locator);
    }

    /**
     * Tests whether authorization tokens can be set.
     */
    @Test
    public void testSetTokens()
    {
        EasyMock.expect(store.saveTokens(tokens)).andReturn(Boolean.TRUE);
        EasyMock.replay(store, appCtx, locator);
        OAuthCallbackImpl cb = new OAuthCallbackImpl(store, appCtx, locator);
        cb.setTokens(tokens);
        EasyMock.verify(store, appCtx, locator);
    }

    /**
     * A test implementation with some enhanced mocking facilities.
     */
    private static class OAuthCallbackTestImpl extends OAuthCallbackImpl
    {
        /** A mock for the desktop. */
        private Desktop mockDesktop;

        /** A mock for an error runnable. */
        private Runnable errorRunnable;

        /** The expected resource ID for an error message. */
        private String expErrorResKey;

        public OAuthCallbackTestImpl(OAuthTokenStore store,
                ApplicationContext appCtx, Locator locator)
        {
            super(store, appCtx, locator);
        }

        /**
         * Creates and installs a mock desktop.
         *
         * @return the mock desktop
         */
        public Desktop installMockDesktop()
        {
            mockDesktop = EasyMock.createMock(Desktop.class);
            return mockDesktop;
        }

        /**
         * Creates an installs a runnable mock for producing an error message.
         *
         * @param resKey the expected resource ID
         * @return the mock runnable
         */
        public Runnable installErrorRunnable(String resKey)
        {
            errorRunnable = EasyMock.createMock(Runnable.class);
            expErrorResKey = resKey;
            return errorRunnable;
        }

        /**
         * Either returns the mock desktop or calls the super class.
         */
        @Override
        Desktop getDesktop()
        {
            return (mockDesktop != null) ? mockDesktop : super.getDesktop();
        }

        /**
         * Either returns the mock error runnable or calls the super method.
         */
        @Override
        Runnable createErrorRunnable(String msgRes)
        {
            if (errorRunnable != null)
            {
                assertEquals("Wrong resource key", expErrorResKey, msgRes);
                return errorRunnable;
            }
            return super.createErrorRunnable(msgRes);
        }
    }
}
