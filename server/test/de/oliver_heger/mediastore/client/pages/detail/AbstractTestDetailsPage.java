package de.oliver_heger.mediastore.client.pages.detail;

import com.google.gwt.junit.client.GWTTestCase;

import de.oliver_heger.mediastore.client.pages.MockPageManager;
import de.oliver_heger.mediastore.client.pages.Pages;

/**
 * An abstract base class for test classes for detail pages. This base class
 * provides some functionality which is used by all tests for concrete detail page
 * implementations.
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
     * During initialization the page accesses the page manager to construct some
     * tokens needed by hyper links. This method prepares the mock page manager
     * correspondingly.
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
     * Tests whether the fields defined by the base class have been created correctly.
     * @param page the page
     */
    protected static void checkBaseFields(AbstractDetailsPage<?> page)
    {
        assertNotNull("No progress indicator", page.progressIndicator);
        assertNotNull("No error panel", page.pnlError);
        assertNotNull("No overview link", page.lnkOverview);
        assertNotNull("No edit synonyms button", page.btnEditSynonyms);
    }
}
