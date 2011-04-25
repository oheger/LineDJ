package de.oliver_heger.jplaya.ui;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import javax.persistence.EntityManagerFactory;

import net.sf.jguiraffe.di.BeanContext;
import net.sf.jguiraffe.gui.app.Application;
import net.sf.jguiraffe.gui.builder.window.Window;

import org.apache.commons.lang3.concurrent.ConcurrentInitializer;
import org.apache.commons.lang3.concurrent.ConcurrentUtils;
import org.junit.Before;
import org.junit.Test;

import de.oliver_heger.mediastore.localstore.MediaStore;

/**
 * A test class for the global bean definition file of the application.
 *
 * @author Oliver Heger
 * @version $Id: $
 */
public class TestAppBeans
{
    /** The application to be tested. */
    private MainTestImpl app;

    @Before
    public void setUp() throws Exception
    {
        app = new MainTestImpl();
        Application.startup(app, new String[0]);
    }

    /**
     * A convenience method for querying the bean context of the test
     * application.
     *
     * @return the bean context
     */
    private BeanContext getBeanContext()
    {
        return app.getApplicationContext().getBeanContext();
    }

    /**
     * Tests whether the application context was created.
     */
    @Test
    public void testAppCtx()
    {
        assertNotNull("No application context", app.getApplicationContext());
        assertNotNull("No bean context", getBeanContext());
    }

    /**
     * Tests whether a correct initializer bean for the EMF is defined.
     */
    @Test
    public void testBeanEMFInitializer()
    {
        ConcurrentInitializer<?> init =
                (ConcurrentInitializer<?>) getBeanContext().getBean(
                        "emfInitializer");
        Object emf = ConcurrentUtils.initializeUnchecked(init);
        assertTrue("Wrong initialized object: " + emf,
                emf instanceof EntityManagerFactory);
    }

    /**
     * Tests whether the bean for the media store has been defined correctly.
     */
    @Test
    public void testBeanMediaStore()
    {
        MediaStore store = getBeanContext().getBean(MediaStore.class);
        assertNotNull("No media store", store);
    }

    private static class MainTestImpl extends Main
    {
        @Override
        protected void showMainWindow(Window window)
        {
            // ignore this call
        }
    }
}
