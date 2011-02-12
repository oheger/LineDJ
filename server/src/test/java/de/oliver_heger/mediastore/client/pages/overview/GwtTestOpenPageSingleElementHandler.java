package de.oliver_heger.mediastore.client.pages.overview;

import com.google.gwt.junit.client.GWTTestCase;

import de.oliver_heger.mediastore.client.pages.MockPageManager;
import de.oliver_heger.mediastore.client.pages.Pages;

/**
 * Test class for {@code OpenPageSingleElementHandler}.
 *
 * @author Oliver Heger
 * @version $Id: $
 */
public class GwtTestOpenPageSingleElementHandler extends GWTTestCase
{
    /** Constant for the ID of the element. */
    private static final Long ELEM_ID = 20101213222945L;

    @Override
    public String getModuleName()
    {
        return "de.oliver_heger.mediastore.RemoteMediaStore";
    }

    /**
     * Tests whether the handler opens the correct page.
     */
    public void testHandleElement()
    {
        MockPageManager pm = new MockPageManager();
        pm.expectCreatePageSpecification(Pages.OVERVIEW, null)
                .withParameter(ELEM_ID).open();
        OpenPageSingleElementHandler handler =
                new OpenPageSingleElementHandler(pm, Pages.OVERVIEW);
        handler.handleElement(ELEM_ID);
        pm.verify();
    }
}
