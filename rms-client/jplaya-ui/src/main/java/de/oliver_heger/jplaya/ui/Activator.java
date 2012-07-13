package de.oliver_heger.jplaya.ui;

import net.sf.jguiraffe.gui.app.ApplicationException;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.commons.vfs2.FileSystemException;
import org.apache.commons.vfs2.VFS;
import org.osgi.framework.Bundle;
import org.osgi.framework.BundleActivator;
import org.osgi.framework.BundleContext;
import org.osgi.framework.BundleException;

/**
 * <p>
 * The activator for this bundle.
 * </p>
 * <p>
 * This class starts the main application.
 * </p>
 *
 * @author Oliver Heger
 * @version $Id: $
 */
public class Activator implements BundleActivator
{
    /** The logger. */
    private final Log log = LogFactory.getLog(getClass());

    @Override
    public void start(BundleContext context) throws Exception
    {
        log.info("Starting PlayaUI bundle.");
        initVFS();
        startMainApplication(context);
    }

    @Override
    public void stop(BundleContext context) throws Exception
    {
        log.info("Stopping PlayaUI bundle.");
    }

    /**
     * Initializes the VFS library. This has to be done in a separate step
     * because the context class loader has to be set during library
     * initialization.
     *
     * @throws FileSystemException if an error occurs
     */
    private void initVFS() throws FileSystemException
    {
        ClassLoader oldCtxCL = Thread.currentThread().getContextClassLoader();
        try
        {
            Thread.currentThread().setContextClassLoader(
                    getClass().getClassLoader());
            VFS.getManager();
        }
        finally
        {
            Thread.currentThread().setContextClassLoader(oldCtxCL);
        }
    }

    /**
     * Starts up the main application. This causes the main window to be
     * displayed.
     *
     * @param context the bundle context
     */
    private void startMainApplication(BundleContext context)
    {
        final Runnable exitHandler = createExitHandler(context);
        new Thread()
        {
            @Override
            public void run()
            {
                Main main = new Main();
                main.setExitHandler(exitHandler);
                try
                {
                    Main.startup(main, new String[0]);
                }
                catch (ApplicationException e)
                {
                    log.error("Could not start application!", e);
                }
            }
        }.start();
    }

    /**
     * Creates the exit handler for the application. This handler will shutdown
     * the OSGi framework by stopping the system bundle.
     *
     * @param context the bundle context
     * @return the exit handler
     */
    private Runnable createExitHandler(final BundleContext context)
    {
        return new Runnable()
        {
            @Override
            public void run()
            {
                Bundle sysBundle = context.getBundle(0);
                try
                {
                    sysBundle.stop();
                }
                catch (BundleException bex)
                {
                    log.error("Could not stop OSGi framework!", bex);
                }
            }
        };
    }
}
