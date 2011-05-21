package de.oliver_heger.mediastore.client.pages.overview;

import java.util.Collection;

import com.google.gwt.junit.client.GWTTestCase;
import com.google.gwt.user.client.rpc.AsyncCallback;

import de.oliver_heger.mediastore.shared.BasicMediaServiceAsync;

/**
 * Test class for {@code RemoveSingleElementHandler}.
 *
 * @author Oliver Heger
 * @version $Id: $
 */
public class GwtTestRemoveSingleElementHandler extends GWTTestCase
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
    private RemoveSingleElementHandler createHandler(final RemoveController ctrl)
    {
        OverviewTable tab = new OverviewTable();
        RemoveSingleElementHandler handler =
                new RemoveSingleElementHandler(SERVICE_HANDLER, tab)
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
    private RemoveSingleElementHandler createHandler()
    {
        return createHandler(null);
    }

    /**
     * Tests whether a valid remove controller is created.
     */
    public void testCreateRemoveController()
    {
        RemoveSingleElementHandler handler = createHandler();
        assertNotNull("No remove controller", handler.createRemoveController());
    }

    /**
     * Tests whether the handler can be invoked.
     */
    public void testHandleElement()
    {
        RemoveControllerTestImpl ctrl = new RemoveControllerTestImpl();
        RemoveSingleElementHandler handler = createHandler(ctrl);
        final Object elemID = 20110521212935L;
        handler.handleElement(elemID);
        assertSame("Wrong service handler", handler.getServiceHandler(),
                ctrl.getServiceHandler());
        assertSame("Wrong overview table", handler.getOverviewTable(),
                ctrl.getOverviewTable());
        assertSame("Wrong element ID", elemID, ctrl.getElemID());
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

        /** The element ID. */
        private Object elemID;

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
         * Returns the element ID passed to performRemoveOperation().
         *
         * @return the element ID
         */
        public Object getElemID()
        {
            return elemID;
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
            assertEquals("Wrong number of IDs", 1, elemIDs.size());
            elemID = elemIDs.iterator().next();
        }
    }
}
