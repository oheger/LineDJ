package de.oliver_heger.jplaya.ui;

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

        Main main = new Main();
        Main.startup(main, new String[0]);
    }

    @Override
    public void stop(BundleContext context) throws Exception
    {
    }
}
