package de.oliver_heger.jplaya.ui;

import java.awt.Desktop;
import java.io.IOException;
import java.net.URI;

import net.sf.jguiraffe.gui.app.ApplicationBuilderData;
import net.sf.jguiraffe.gui.app.ApplicationContext;
import net.sf.jguiraffe.gui.builder.Builder;
import net.sf.jguiraffe.gui.builder.BuilderException;
import net.sf.jguiraffe.gui.builder.utils.MessageOutput;
import net.sf.jguiraffe.gui.builder.window.Window;
import net.sf.jguiraffe.locators.Locator;
import net.sf.jguiraffe.locators.LocatorException;

import org.apache.commons.lang3.mutable.MutableObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import de.oliver_heger.mediastore.localstore.OAuthTokenStore;
import de.oliver_heger.mediastore.oauth.OAuthCallback;
import de.oliver_heger.mediastore.oauth.OAuthTokens;

/**
 * <p>
 * An implementation of the {@link OAuthCallback} interface which user a dialog
 * window to prompt the user for the OAuth verification code.
 * </p>
 * <p>
 * An instance of this class is initialized with the <em>locator</em> to a
 * builder script producing a dialog window and the current
 * {@link OAuthTokenStore}. When asked for authorization tokens it first
 * delegates to the token store. If no tokens are available, the authorization
 * procedure has to be performed. For this purpose the browser is launched
 * pointing to the URI provided by the OAuth template. In parallel, the builder
 * script is executed, and the resulting dialog window is opened in which the
 * user can enter the results of the authorization.
 * </p>
 *
 * @author Oliver Heger
 * @version $Id: $
 */
public class OAuthCallbackImpl implements OAuthCallback
{
    /** The key for the data model of the dialog window. */
    static final String KEY_MODEL = "modelBean";

    /** The resource key for the title of an error message box. */
    static final String RES_ERRMSG_TITLE = "sync_err_title";

    /** The resource key for the error message for an IO error. */
    static final String RES_ERRMSG_IO = "sync_err_io";

    /** The resource key for the error message for a builder error. */
    static final String RES_ERRMSG_BUILDER = "sync_err_builder";

    /** The logger. */
    private final Logger log = LoggerFactory.getLogger(getClass());

    /** The token store. */
    private final OAuthTokenStore tokenStore;

    /** The application context. */
    private final ApplicationContext applicationContext;

    /** The script locator. */
    private final Locator scriptLocator;

    /**
     * Creates a new instance of {@code OAuthCallbackImpl} and initializes it.
     *
     * @param store the token store (must not be <b>null</b>)
     * @param appCtx the current application context (must not be <b>null</b>)
     * @param locator the locator for the builder script defining the dialog
     *        window (must not be <b>null</b>)
     * @throws NullPointerException if a required parameter is missing
     */
    public OAuthCallbackImpl(OAuthTokenStore store, ApplicationContext appCtx,
            Locator locator)
    {
        if (store == null)
        {
            throw new NullPointerException("Token store must not be null!");
        }
        if (appCtx == null)
        {
            throw new NullPointerException(
                    "Application context must not be null!");
        }
        if (locator == null)
        {
            throw new NullPointerException("Locator must not be null!");
        }

        tokenStore = store;
        applicationContext = appCtx;
        scriptLocator = locator;
    }

    /**
     * Returns the current authorization tokens if available. This method
     * delegates to the token store.
     *
     * @return the authorization tokens or <b>null</b> if none are available
     */
    @Override
    public OAuthTokens getTokens()
    {
        return tokenStore.loadTokens();
    }

    /**
     * Prompts the user for the verification code. Refer to the class comment
     * for a description of the process implemented by this method.
     *
     * @param uri the URI to navigate the browser to
     * @return the verification code or <b>null</b> if the user aborted the
     *         operation
     */
    @Override
    public String getVerificationCode(URI uri)
    {
        try
        {
            launchBrowser(uri);
            MutableObject<String> model = new MutableObject<String>();
            Window window = buildVerificationCodeDialogWindow(model);
            window.open();
            return model.getValue();
        }
        catch (IOException ioex)
        {
            log.error("Error when opening browser!", ioex);
            showErrorMessage(RES_ERRMSG_IO);
        }
        catch (BuilderException bex)
        {
            handleBuilderException(bex);
        }
        catch (LocatorException lex)
        {
            handleBuilderException(lex);
        }

        return null;
    }

    /**
     * Stores the authorization tokens after a successful authorization. This
     * implementation delegates to the token store.
     *
     * @param tokens the tokens to be stored
     */
    @Override
    public void setTokens(OAuthTokens tokens)
    {
        tokenStore.saveTokens(tokens);
    }

    /**
     * Returns the current desktop object.
     *
     * @return the desktop object
     */
    Desktop getDesktop()
    {
        return Desktop.getDesktop();
    }

    /**
     * Creates a runnable for displaying an error message. Because this object
     * is not called from the event dispatch thread, a separate runnable is
     * needed for this purpose.
     *
     * @param msgRes the resource of the message to be displayed
     * @return the runnable for displaying the error message
     */
    Runnable createErrorRunnable(final String msgRes)
    {
        return new Runnable()
        {
            @Override
            public void run()
            {
                applicationContext.messageBox(msgRes, RES_ERRMSG_TITLE,
                        MessageOutput.MESSAGE_ERROR, MessageOutput.BTN_OK);
            }
        };
    }

    /**
     * Displays an error message in the event dispatch thread.
     *
     * @param msgRes the resource ID of the error message
     */
    private void showErrorMessage(String msgRes)
    {
        applicationContext.getGUISynchronizer().asyncInvoke(
                createErrorRunnable(msgRes));
    }

    /**
     * Opens the specified URI in the browser.
     *
     * @param uri the URI to open
     * @throws IOException if an IO error occurs
     */
    private void launchBrowser(URI uri) throws IOException
    {
        getDesktop().browse(uri);
    }

    /**
     * Executes the builder script for setting up the dialog window which
     * prompts the user for the verification code.
     *
     * @param model the data model for the window
     * @return the window
     * @throws BuilderException if a builder error occurs
     */
    private Window buildVerificationCodeDialogWindow(MutableObject<String> model)
            throws BuilderException
    {
        Builder builder = applicationContext.newBuilder();
        ApplicationBuilderData builderData =
                applicationContext.initBuilderData();
        builderData.addProperty(KEY_MODEL, model);
        return builder.buildWindow(scriptLocator, builderData);
    }

    /**
     * Handles an exception thrown during the execution of a builder script.
     *
     * @param t the exception
     */
    private void handleBuilderException(Throwable t)
    {
        log.error("Error when executing builder script!", t);
        showErrorMessage(RES_ERRMSG_BUILDER);
    }
}
