package de.oliver_heger.mediastore.client.pages.detail;

import java.util.Collection;
import java.util.List;

import com.google.gwt.junit.client.GWTTestCase;
import com.google.gwt.user.client.ui.DisclosurePanel;

import de.oliver_heger.mediastore.client.pages.MockPageManager;
import de.oliver_heger.mediastore.client.pages.Pages;

/**
 * An abstract base class for test classes for detail pages. This base class
 * provides some functionality which is used by all tests for concrete detail
 * page implementations.
 *
 * @author Oliver Heger
 * @version $Id: $
 */
public abstract class AbstractTestDetailsPage extends GWTTestCase
{
    @Override
    public String getModuleName()
    {
        return "de.oliver_heger.mediastore.RemoteMediaStore";
    }

    /**
     * Tests whether the passed in string is empty. This means that the string
     * is either null or has length 0.
     *
     * @param msg the error message
     * @param s the string to check
     */
    protected static void assertEmpty(String msg, String s)
    {
        assertTrue(msg, s == null || s.length() < 1);
    }

    /**
     * Creates a mock page manager for the initialization of a details page.
     * During initialization the page accesses the page manager to construct
     * some tokens needed by hyper links. This method prepares the mock page
     * manager correspondingly.
     *
     * @return the mock page manager
     */
    protected static MockPageManager createPMForInitialize()
    {
        MockPageManager pm = new MockPageManager();
        pm.expectCreatePageSpecification(Pages.OVERVIEW, null).toToken();
        return pm;
    }

    /**
     * Prepares a call to the initialize() method. This method prepares the mock
     * page manager correspondingly.
     *
     * @param page the page
     * @return the mock page manager
     */
    protected static MockPageManager initializePage(AbstractDetailsPage<?> page)
    {
        MockPageManager pm = createPMForInitialize();
        page.initialize(pm);
        return pm;
    }

    /**
     * Tests whether the fields defined by the base class have been created
     * correctly.
     *
     * @param page the page
     */
    protected static void checkBaseFields(AbstractDetailsPage<?> page)
    {
        assertNotNull("No progress indicator", page.progressIndicator);
        assertNotNull("No error panel", page.pnlError);
        assertNotNull("No overview link", page.lnkOverview);
        assertNotNull("No edit synonyms button", page.btnEditSynonyms);
    }

    /**
     * Tests whether the specified table contains the expected data.
     *
     * @param expContent a collection with the data expected
     * @param table the table component to be checked
     */
    protected static <T> void checkTableContent(Collection<T> expContent,
            AbstractDetailsTable<T> table)
    {
        List<T> list = table.getDataProvider().getList();
        assertEquals("Wrong number of entries", expContent.size(), list.size());
        assertTrue("Wrong content: " + list, expContent.containsAll(list));
    }

    /**
     * Helper method for testing the state of a disclosure panel.
     *
     * @param pnl the panel
     * @param txt the expected header text
     * @param count the number of elements
     * @param open the open flag
     */
    protected static void checkDisclosurePanel(DisclosurePanel pnl, String txt,
            int count, boolean open)
    {
        assertEquals("Wrong header text", generateHeaderString(txt, count), pnl
                .getHeaderTextAccessor().getText());
        assertEquals("Wrong open state", open, pnl.isOpen());
    }

    /**
     * Generates a header text for a disclosure panel.
     *
     * @param txt the text
     * @param count the number
     * @return the header text
     */
    private static String generateHeaderString(String txt, int count)
    {
        StringBuilder buf = new StringBuilder();
        buf.append(txt);
        buf.append(" (").append(count).append(")");
        return buf.toString();
    }
}
