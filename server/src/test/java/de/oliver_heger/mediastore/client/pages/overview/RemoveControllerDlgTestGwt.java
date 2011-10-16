package de.oliver_heger.mediastore.client.pages.overview;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedList;
import java.util.List;
import java.util.Set;

import com.google.gwt.junit.client.GWTTestCase;
import com.google.gwt.user.client.rpc.AsyncCallback;

import de.oliver_heger.mediastore.shared.BasicMediaServiceAsync;

/**
 * Test class for {@code RemoveControllerDlg}.
 *
 * @author Oliver Heger
 * @version $Id: $
 */
public class RemoveControllerDlgTestGwt extends GWTTestCase
{
    @Override
    public String getModuleName()
    {
        return "de.oliver_heger.mediastore.RemoteMediaStore";
    }

    /**
     * Creates a collection with test element IDs. As test IDs integer values
     * are used in the range specified.
     *
     * @param from the first test ID
     * @param to the last test ID
     * @return a collection with the test IDs
     */
    private static Collection<Object> createElementIDs(int from, int to)
    {
        Collection<Object> result = new ArrayList<Object>();
        for (int i = from; i <= to; i++)
        {
            result.add(Integer.valueOf(i));
        }
        return result;
    }

    /**
     * Creates a number of test element IDs. The IDs start from 1 until the
     * given element count.
     *
     * @param count the number of test IDs to be generated
     * @return a collection with the test IDs
     */
    private static Collection<Object> createElementIDs(int count)
    {
        return createElementIDs(1, count);
    }

    /**
     * Helper method for testing the IDs to be removed.
     *
     * @param ctrl the controller
     * @param expIDs the expected IDs
     */
    private static void checkIDsToRemove(RemoveControllerDlg ctrl,
            Collection<Object> expIDs)
    {
        Set<Object> removeIDs = ctrl.getIDsToBeRemoved();
        assertEquals("Wrong number of elements to be removed", expIDs.size(),
                removeIDs.size());
        assertTrue("Wrong IDs: " + removeIDs, removeIDs.containsAll(expIDs));
    }

    /**
     * Tests whether an instance is correctly created and initialized.
     */
    public void testInit()
    {
        RemoveControllerDlg ctrl = new RemoveControllerDlg();
        assertNotNull("No remove button", ctrl.btnRemove);
        assertNotNull("No count span", ctrl.spanCount);
        assertNotNull("No dialog", ctrl.removeDlg);
        assertNotNull("No progress indicator", ctrl.progressIndicator);
        assertNotNull("No error panel", ctrl.pnlError);
        assertFalse("Dialog visible", ctrl.removeDlg.isVisible());
        assertFalse("Progress indicator visible",
                ctrl.progressIndicator.isVisible());
        assertFalse("Already an error", ctrl.pnlError.isInErrorState());
    }

    /**
     * Tests whether the UI can be reset for another remove operation.
     */
    public void testResetUI()
    {
        RemoveControllerDlg ctrl = new RemoveControllerDlg();
        ctrl.progressIndicator.setVisible(true);
        ctrl.setCanceled(true);
        ctrl.setInRemoveOperation(true);
        ctrl.btnRemove.setEnabled(false);
        ctrl.pnlError.displayError(new RuntimeException());
        ctrl.labProgress.setText("Some text");
        ctrl.resetUI();
        assertFalse("Progress indicator visible",
                ctrl.progressIndicator.isVisible());
        assertFalse("Already an error", ctrl.pnlError.isInErrorState());
        assertTrue("Remove button not enabled", ctrl.btnRemove.isEnabled());
        assertFalse("Canceled", ctrl.isCanceled());
        assertFalse("In remove operation", ctrl.isInRemoveOperation());
        assertEquals("Got progress text", "", ctrl.labProgress.getText());
    }

    /**
     * Tests whether a remove operation can be initialized.
     */
    public void testPerformRemove()
    {
        RemoveControllerDlg ctrl = new RemoveControllerDlg();
        OverviewTableTestImpl table = new OverviewTableTestImpl();
        Collection<Object> elementIDs = createElementIDs(16);
        RemoveServiceHandlerTestImpl handler =
                new RemoveServiceHandlerTestImpl(new ArrayList<Object>(),
                        ctrl.getRemoveCallback());
        ctrl.performRemove(handler, table, elementIDs);
        assertTrue("Dialog not visible", ctrl.removeDlg.isVisible());
        checkIDsToRemove(ctrl, elementIDs);
        assertFalse("Operation already in progress", ctrl.isInRemoveOperation());
        assertFalse("Canceled", ctrl.isCanceled());
        assertTrue("No count span", ctrl.spanCount.getInnerText().length() > 0);
        assertEquals("Refresh was called", 0, table.getRefreshCount());
        handler.verify();
    }

    /**
     * Tries to initiate a remove operation without element IDs.
     */
    public void testPerformRemoveNoIDs()
    {
        RemoveControllerDlg ctrl = new RemoveControllerDlg();
        try
        {
            ctrl.performRemove(new RemoveServiceHandlerTestImpl(null, null),
                    new OverviewTableTestImpl(), null);
            fail("Missing element IDs not detected!");
        }
        catch (NullPointerException npex)
        {
            // ok
        }
    }

    /**
     * Tries to initiate a remove operation without a table.
     */
    public void testPerformRemoveNoTable()
    {
        RemoveControllerDlg ctrl = new RemoveControllerDlg();
        try
        {
            ctrl.performRemove(new RemoveServiceHandlerTestImpl(null, null),
                    null, createElementIDs(1));
            fail("Missing overview table not detected!");
        }
        catch (NullPointerException npex)
        {
            // ok
        }
    }

    /**
     * Tries to initiate a remove operation without a handler.
     */
    public void testPerformRemoveNoHandler()
    {
        RemoveControllerDlg ctrl = new RemoveControllerDlg();
        try
        {
            ctrl.performRemove(null, new OverviewTableTestImpl(),
                    createElementIDs(1));
            fail("Missing handler not detected!");
        }
        catch (NullPointerException npex)
        {
            // ok
        }
    }

    /**
     * Tries to initiate a remove operation if there are no elements to remove.
     */
    public void testPerformRemoveEmptyIDSet()
    {
        RemoveControllerDlg ctrl = new RemoveControllerDlg();
        try
        {
            ctrl.performRemove(new RemoveServiceHandlerTestImpl(null, null),
                    new OverviewTableTestImpl(), new ArrayList<Object>());
            fail("Empty ID collection not detected!");
        }
        catch (IllegalArgumentException iex)
        {
            // ok
        }
    }

    /**
     * Tests whether getIDsToBeRemoved() can be called before the object is
     * initialized.
     */
    public void testGetIDsToBeRemovedBeforeInit()
    {
        RemoveControllerDlg ctrl = new RemoveControllerDlg();
        assertTrue("Got IDs", ctrl.getIDsToBeRemoved().isEmpty());
    }

    /**
     * Tests whether the remove operation can be canceled before it really
     * starts.
     */
    public void testCancelBeforeStart()
    {
        RemoveControllerDlg ctrl = new RemoveControllerDlg();
        OverviewTableTestImpl table = new OverviewTableTestImpl();
        Collection<Object> elementIDs = createElementIDs(16);
        RemoveServiceHandlerTestImpl handler =
                new RemoveServiceHandlerTestImpl(new ArrayList<Object>(),
                        ctrl.getRemoveCallback());
        ctrl.performRemove(handler, table, elementIDs);
        ctrl.onBtnCancelClick(null);
        assertFalse("Dialog still showing", ctrl.removeDlg.isShowing());
        handler.verify();
    }

    /**
     * Tests a single step of a remove operation.
     */
    public void testRemoveSingleStep()
    {
        final int count = 8;
        RemoveControllerDlg ctrl = new RemoveControllerDlg();
        OverviewTableTestImpl table = new OverviewTableTestImpl();
        RemoveServiceHandlerTestImpl handler =
                new RemoveServiceHandlerTestImpl(createElementIDs(1),
                        ctrl.getRemoveCallback());
        ctrl.performRemove(handler, table, createElementIDs(count));
        ctrl.onBtnRemoveClick(null);
        assertTrue("Progress not visible", ctrl.progressIndicator.isVisible());
        assertFalse("Remove button still enabled", ctrl.btnRemove.isEnabled());
        assertTrue("Operation not in progress", ctrl.isInRemoveOperation());
        assertFalse("Canceled", ctrl.isCanceled());
        checkIDsToRemove(ctrl, createElementIDs(2, count));
        assertEquals("Wrong refresh() count", 0, table.getRefreshCount());
        handler.verify();
    }

    /**
     * Tests a complete and successful remove operation.
     */
    public void testRemoveSuccess()
    {
        final int count = 32;
        RemoveControllerDlg ctrl = new RemoveControllerDlg();
        OverviewTableTestImpl table = new OverviewTableTestImpl();
        Collection<Object> elemIDs = createElementIDs(count);
        RemoveServiceHandlerTestImpl handler =
                new RemoveServiceHandlerTestImpl(elemIDs,
                        ctrl.getRemoveCallback());
        ctrl.performRemove(handler, table, elemIDs);
        ctrl.onBtnRemoveClick(null);
        for (int i = 0; i < count; i++)
        {
            ctrl.getRemoveCallback().onSuccess(Boolean.TRUE);
        }
        assertFalse("Dialog still showing", ctrl.removeDlg.isShowing());
        handler.verify();
        assertEquals("Wrong refresh count", 1, table.getRefreshCount());
    }

    /**
     * Tests whether a remove operation can be canceled.
     */
    public void testRemoveCancel()
    {
        RemoveControllerDlg ctrl = new RemoveControllerDlg();
        OverviewTableTestImpl table = new OverviewTableTestImpl();
        RemoveServiceHandlerTestImpl handler =
                new RemoveServiceHandlerTestImpl(createElementIDs(2),
                        ctrl.getRemoveCallback());
        ctrl.performRemove(handler, table, createElementIDs(128));
        ctrl.onBtnRemoveClick(null);
        ctrl.getRemoveCallback().onSuccess(Boolean.TRUE);
        ctrl.onBtnCancelClick(null);
        assertTrue("Not canceled", ctrl.isCanceled());
        ctrl.getRemoveCallback().onSuccess(Boolean.TRUE);
        assertFalse("Dialog still visible", ctrl.removeDlg.isShowing());
        handler.verify();
        assertEquals("Wrong refresh count", 1, table.getRefreshCount());
        assertTrue("Still IDs to remove", ctrl.getIDsToBeRemoved().isEmpty());
    }

    /**
     * Tests whether errors during a remove operation are handled correctly.
     */
    public void testRemoveError()
    {
        RemoveControllerDlg ctrl = new RemoveControllerDlg();
        OverviewTableTestImpl table = new OverviewTableTestImpl();
        RemoveServiceHandlerTestImpl handler =
                new RemoveServiceHandlerTestImpl(createElementIDs(1),
                        ctrl.getRemoveCallback());
        ctrl.performRemove(handler, table, createElementIDs(128));
        ctrl.onBtnRemoveClick(null);
        Throwable ex = new RuntimeException("Test exception");
        ctrl.getRemoveCallback().onFailure(ex);
        assertFalse("Still in operation", ctrl.isInRemoveOperation());
        assertFalse("Remove button enabled", ctrl.btnRemove.isEnabled());
        assertTrue("Error panel not visible", ctrl.pnlError.isInErrorState());
        assertEquals("Wrong exception", ex, ctrl.pnlError.getError());
        handler.verify();
        ctrl.onBtnCancelClick(null);
        assertFalse("Dialog still showing", ctrl.removeDlg.isShowing());
        assertEquals("Wrong refresh count", 1, table.getRefreshCount());
    }

    /**
     * Tests whether the initialization resets the cancel flag.
     */
    public void testInitOperationAfterCancel()
    {
        RemoveControllerDlg ctrl = new RemoveControllerDlg();
        ctrl.setCanceled(true);
        ctrl.performRemove(new RemoveServiceHandlerTestImpl(null, null),
                new OverviewTableTestImpl(), createElementIDs(64));
        assertFalse("Canceled", ctrl.isCanceled());
    }

    /**
     * Tests whether the correct count text for a single item is generated.
     */
    public void testGenerateCountTextSingle()
    {
        RemoveControllerDlg ctrl = new RemoveControllerDlg();
        assertEquals("Wrong text", "1 item", ctrl.generateCountText(1));
    }

    /**
     * Tests whether the correct count text for multiple items is generated.
     */
    public void testGenerateCountTextMulti()
    {
        RemoveControllerDlg ctrl = new RemoveControllerDlg();
        assertEquals("Wrong text", "8 items", ctrl.generateCountText(8));
    }

    /**
     * A specialized overview table implementation with some mocking facilities.
     */
    private static class OverviewTableTestImpl extends OverviewTable
    {
        /** Stores the number of invocations of the refresh() method. */
        private int refreshCount;

        /**
         * Returns the number of invocations of the refresh() method.
         *
         * @return the counter for refresh() calls
         */
        public int getRefreshCount()
        {
            return refreshCount;
        }

        /**
         * Records this invocation.
         */
        @Override
        public void refresh()
        {
            refreshCount++;
        }
    }

    /**
     * A test implementation of the handler interface. It is used to test
     * whether the expected elements are removed.
     */
    private static class RemoveServiceHandlerTestImpl implements
            RemoveServiceHandler
    {
        /** A collection with the expected element IDs to be removed. */
        private final List<Object> expIDs;

        /** The expected callback object. */
        private final AsyncCallback<Boolean> expCallback;

        /** Stores the service reference. */
        private BasicMediaServiceAsync mediaService;

        /**
         * Creates a new instance of {@code RemoveServiceHandlerTestImpl} and
         * initializes it with the expected element IDs to be removed and the
         * expected callback.
         *
         * @param ids the expected element IDs
         * @param cb the expected callback
         */
        public RemoveServiceHandlerTestImpl(Collection<Object> ids,
                AsyncCallback<Boolean> cb)
        {
            if (ids != null)
            {
                expIDs = new LinkedList<Object>(ids);
            }
            else
            {
                expIDs = null;
            }
            expCallback = cb;
        }

        /**
         * Verifies that the expected invocations have occurred.
         */
        public void verify()
        {
            assertTrue("Expected further invocations: " + expIDs,
                    expIDs.isEmpty());
        }

        /**
         * Checks the parameters of this invocations.
         */
        @Override
        public void removeElement(BasicMediaServiceAsync service,
                Object elemID, AsyncCallback<Boolean> callback)
        {
            assertFalse("Too many invocations", expIDs.isEmpty());
            assertEquals("Wrong element ID", expIDs.remove(0), elemID);
            assertSame("Wrong callback", expCallback, callback);
            if (mediaService == null)
            {
                assertNotNull("No media service", service);
                mediaService = service;
            }
            else
            {
                assertEquals("Wrong media service", mediaService, service);
            }
        }
    }
}
