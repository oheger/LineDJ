package de.oliver_heger.jplaya.ui;

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
        ClassLoader cclOld = Thread.currentThread().getContextClassLoader();
        try
        {
            Thread.currentThread().setContextClassLoader(
                    getClass().getClassLoader());
            Main main = new Main();
            Main.startup(main, new String[0]);
        }
        finally
        {
            Thread.currentThread().setContextClassLoader(cclOld);
        }
    }

    @Override
    public void stop(BundleContext context) throws Exception
    {
    }
}
