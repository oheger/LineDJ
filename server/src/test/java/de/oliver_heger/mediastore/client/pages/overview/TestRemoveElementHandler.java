package de.oliver_heger.mediastore.client.pages.overview;

import java.util.Collections;
import java.util.Set;

import org.easymock.EasyMock;
import org.junit.Before;
import org.junit.Test;

/**
 * Test class for {@code RemoveElementHandler}.
 *
 * @author Oliver Heger
 * @version $Id: $
 */
public class TestRemoveElementHandler
{
    /** A mock for the service handler. */
    private RemoveServiceHandler serviceHandler;

    /** A mock for the associated control. */
    private Refreshable control;

    /** A mock for the remove controller. */
    private RemoveController controller;

    /** The handler to be tested. */
    private RemoveElementHandler handler;

    @Before
    public void setUp() throws Exception
    {
        serviceHandler = EasyMock.createMock(RemoveServiceHandler.class);
        control = EasyMock.createMock(Refreshable.class);
        controller = EasyMock.createMock(RemoveController.class);
        handler = new RemoveElementHandler(serviceHandler, control, controller);
    }

    /**
     * Tests whether a single element is correctly handled.
     */
    @Test
    public void testHandleElement()
    {
        final Object elemID = 20110521212935L;
        controller.performRemove(serviceHandler, control,
                Collections.singleton(elemID));
        EasyMock.replay(serviceHandler, control, controller);
        handler.handleElement(elemID);
        EasyMock.verify(serviceHandler, control, controller);
    }

    /**
     * Tests whether a set of elements is handled correctly.
     */
    @Test
    public void testHandleElements()
    {
        @SuppressWarnings("unchecked")
        Set<Object> ids = EasyMock.createMock(Set.class);
        controller.performRemove(serviceHandler, control, ids);
        EasyMock.replay(serviceHandler, control, controller, ids);
        handler.handleElements(ids);
        EasyMock.verify(serviceHandler, control, controller, ids);
    }
}
