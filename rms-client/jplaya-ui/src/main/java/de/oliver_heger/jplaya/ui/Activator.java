package de.oliver_heger.jplaya.ui;

import net.sf.jguiraffe.gui.app.ApplicationException;

import org.apache.commons.vfs2.FileSystemException;
import org.apache.commons.vfs2.VFS;
import org.osgi.framework.BundleActivator;
import org.osgi.framework.BundleContext;

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
    @Override
    public void start(BundleContext context) throws Exception
    {
        initVFS();
        startMainApplication();
    }

    @Override
    public void stop(BundleContext context) throws Exception
    {
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
     */
    private void startMainApplication()
    {
        new Thread()
        {
            @Override
            public void run()
            {
                Main main = new Main();
                try
                {
                    Main.startup(main, new String[0]);
                }
                catch (ApplicationException e)
                {
                    e.printStackTrace();
                }
            }
        }.start();
    }
}
