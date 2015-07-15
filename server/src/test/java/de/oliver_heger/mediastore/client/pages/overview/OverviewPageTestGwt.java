package de.oliver_heger.mediastore.client.pages.overview;

import com.google.gwt.junit.client.GWTTestCase;

import de.oliver_heger.mediastore.client.pageman.PageManager;
import de.oliver_heger.mediastore.client.pages.MockPageManager;

/**
 * Test class for {@code OverviewPage}.
 *
 * @author Oliver Heger
 * @version $Id: $
 */
public class OverviewPageTestGwt extends GWTTestCase
{
    @Override
    public String getModuleName()
    {
        return "de.oliver_heger.mediastore.RemoteMediaStore";
    }

    /**
     * Creates a page manager instance.
     *
     * @return the page manager
     */
    private static MockPageManager createPageManager()
    {
        return new MockPageManager();
    }

    /**
     * Tests whether all fields are properly set by the UI binder.
     */
    public void testInit()
    {
        OverviewPage page = new OverviewPage();
        assertNotNull("No tab panel", page.tabPanel);
        assertEquals("Wrong number of tabs", 3, page.tabPanel.getWidgetCount());
    }

    /**
     * Tests whether initialization of the component works as expected.
     */
    public void testInitialize()
    {
        OverviewPage page = new OverviewPage();
        PageManager pm = createPageManager();
        page.initialize(pm);
        assertSame("Page manager not set", pm, page.getPageManager());
        for (int i = 0; i < page.tabPanel.getWidgetCount(); i++)
        {
            AbstractOverviewTable<?> table =
                    (AbstractOverviewTable<?>) page.tabPanel.getWidget(i);
            assertSame("Page manager not set", pm, table.getPageManager());
        }
    }
}
