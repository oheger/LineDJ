package de.oliver_heger.mediastore.client.pages.overview;

import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

import com.google.gwt.junit.client.GWTTestCase;
import com.google.gwt.user.client.rpc.AsyncCallback;

import de.oliver_heger.mediastore.shared.BasicMediaServiceAsync;

/**
 * Test class for {@code RemoveElementHandler}.
 *
 * @author Oliver Heger
 * @version $Id: $
 */
public class RemoveElementHandlerTestGwt extends GWTTestCase
{
    /** Constant for a dummy service handler. */
    private static final RemoveServiceHandler SERVICE_HANDLER =
            new RemoveServiceHandler()
            {
                @Override
                public void removeElement(BasicMediaServiceAsync service,
                        Object elemID, AsyncCallback<Boolean> callback)
                {
                    throw new UnsupportedOperationException(
                            "Unexpected method call!");
                }
            };

    @Override
    public String getModuleName()
    {
        return "de.oliver_heger.mediastore.RemoteMediaStore";
    }

    /**
     * Creates a test instance of the single element handler which returns the
     * specified remove controller. If no controller is passed, the default
     * implementation is used.
     *
     * @param ctrl the remove controller
     * @return the test instance
     */
    private RemoveElementHandler createHandler(final RemoveController ctrl)
    {
        OverviewTable tab = new OverviewTable();
        RemoveElementHandler handler =
                new RemoveElementHandler(SERVICE_HANDLER, tab)
                {
                    @Override
                    RemoveController createRemoveController()
                    {
                        if (ctrl != null)
                        {
                            return ctrl;
                        }
                        return super.createRemoveController();
                    };
                };
        assertSame("Wrong table", tab, handler.getOverviewTable());
        assertSame("Wrong service handler", SERVICE_HANDLER,
                handler.getServiceHandler());
        return handler;
    }

    /**
     * Creates a test instance of the single element handler.
     *
     * @return the test instance
     */
    private RemoveElementHandler createHandler()
    {
        return createHandler(null);
    }

    /**
     * Tests whether a valid remove controller is created.
     */
    public void testCreateRemoveController()
    {
        RemoveElementHandler handler = createHandler();
        assertNotNull("No remove controller", handler.createRemoveController());
    }

    /**
     * Tests whether a single element is correctly handled.
     */
    public void testHandleElement()
    {
        RemoveControllerTestImpl ctrl = new RemoveControllerTestImpl();
        RemoveElementHandler handler = createHandler(ctrl);
        final Object elemID = 20110521212935L;
        handler.handleElement(elemID);
        assertSame("Wrong service handler", handler.getServiceHandler(),
                ctrl.getServiceHandler());
        assertSame("Wrong overview table", handler.getOverviewTable(),
                ctrl.getOverviewTable());
        assertEquals("Wrong number of IDs", 1, ctrl.getElemIDs().size());
        assertSame("Wrong element ID", elemID, ctrl.getElemIDs().iterator()
                .next());
    }

    /**
     * Tests whether a set of elements is handled correctly.
     */
    public void testHandleElements()
    {
        RemoveControllerTestImpl ctrl = new RemoveControllerTestImpl();
        RemoveElementHandler handler = createHandler(ctrl);
        Object[] ids =
                new Object[] {
                        20110526214305L, 20110526214350L, 20110526214402L,
                        20110526214420L
                };
        Set<Object> idCol =
                Collections.unmodifiableSet(new HashSet<Object>(Arrays
                        .asList(ids)));
        handler.handleElements(idCol);
        assertSame("Wrong service handler", handler.getServiceHandler(),
                ctrl.getServiceHandler());
        assertSame("Wrong overview table", handler.getOverviewTable(),
                ctrl.getOverviewTable());
        assertSame("Wrong IDs", idCol, ctrl.getElemIDs());
    }

    /**
     * A test remove controller implementation which checks whether the
     * controller is correctly initialized by the single element handler.
     */
    private static class RemoveControllerTestImpl extends RemoveController
    {
        /** The remove service handler. */
        private RemoveServiceHandler serviceHandler;

        /** The overview table. */
        private OverviewTable overviewTable;

        /** The element IDs. */
        private Collection<Object> elemIDs;

        /**
         * Returns the service handler passed to performRemoveOperation().
         *
         * @return the service handler
         */
        public RemoveServiceHandler getServiceHandler()
        {
            return serviceHandler;
        }

        /**
         * Returns the overview table passed to performRemoveOperation().
         *
         * @return the overview table
         */
        public OverviewTable getOverviewTable()
        {
            return overviewTable;
        }

        /**
         * Returns the collection of element IDs passed to
         * performRemoveOperation().
         *
         * @return the element IDs
         */
        public Collection<Object> getElemIDs()
        {
            return elemIDs;
        }

        /**
         * Records this invocation and stores the parameters.
         */
        @Override
        public void performRemove(RemoveServiceHandler handler,
                OverviewTable tab, Collection<Object> elemIDs)
        {
            assertNull("Multiple invocations", serviceHandler);
            serviceHandler = handler;
            overviewTable = tab;
            this.elemIDs = elemIDs;
        }
    }
}
