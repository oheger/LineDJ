package de.oliver_heger.mediastore.client.pageman.impl;

import java.util.Iterator;
import java.util.Set;

import com.google.gwt.event.logical.shared.ValueChangeEvent;
import com.google.gwt.event.logical.shared.ValueChangeHandler;
import com.google.gwt.junit.client.GWTTestCase;
import com.google.gwt.user.client.History;
import com.google.gwt.user.client.ui.Composite;
import com.google.gwt.user.client.ui.Label;
import com.google.gwt.user.client.ui.Widget;

import de.oliver_heger.mediastore.client.pageman.PageConfiguration;
import de.oliver_heger.mediastore.client.pageman.PageConfigurationSupport;
import de.oliver_heger.mediastore.client.pageman.PageFactory;
import de.oliver_heger.mediastore.client.pageman.PageManager;
import de.oliver_heger.mediastore.client.pageman.PageView;

/**
 * Test class of {@code PageManagerImpl}.
 *
 * @author Oliver Heger
 * @version $Id: $
 */
public class TestPageManagerImpl extends GWTTestCase
{
    /** Constant for a name of a test page. */
    private static final String NAME = "TestPageName";

    /** A mock page view object. */
    private PageViewImpl view;

    @Override
    public String getModuleName()
    {
        return "de.oliver_heger.mediastore.RemoteMediaStore";
    }

    /**
     * Creates the test instance.
     *
     * @return the test instance
     */
    private PageManagerImpl createPageManager()
    {
        view = new PageViewImpl();
        return new PageManagerImpl(view);
    }

    /**
     * Tries to create an instance without a view.
     */
    public void testInitNoView()
    {
        try
        {
            new PageManagerImpl(null);
            fail("Could create instance with null view!");
        }
        catch (IllegalArgumentException iex)
        {
            // ok
        }
    }

    /**
     * Tests whether the correct page view is returned.
     */
    public void testGetPageView()
    {
        PageManagerImpl pageMan = createPageManager();
        assertSame("Wrong page view", view, pageMan.getPageView());
    }

    /**
     * Tries to register a page with a null name.
     */
    public void testRegisterPageNullName()
    {
        PageManagerImpl pm = createPageManager();
        try
        {
            pm.registerPage(null, new PageFactoryTestImpl(new Label(), pm));
            fail("Null page name not detected!");
        }
        catch (NullPointerException npex)
        {
            // ok
        }
    }

    /**
     * Tries to register a page with a null factory.
     */
    public void testRegisterPageNullFactory()
    {
        PageManagerImpl pm = createPageManager();
        try
        {
            pm.registerPage(NAME, null);
            fail("Null page name not detected!");
        }
        catch (NullPointerException npex)
        {
            // ok
        }
    }

    /**
     * Tests whether a page can be registered successfully.
     */
    public void testRegisterPage()
    {
        PageManagerImpl pm = createPageManager();
        Widget page = new Label();
        PageFactoryTestImpl factory = new PageFactoryTestImpl(page, pm);
        pm.registerPage(NAME, factory);
        assertSame("Wrong page (1)", page, pm.getPageWidget(NAME));
        assertSame("Wrong page (2)", page, pm.getPageWidget(NAME));
        assertEquals("Wrong number of calls", 2, factory.getNumberOfCalls());
    }

    /**
     * Tests whether a page can be registered as a singleton.
     */
    public void testRegisterPageSingleton()
    {
        PageManagerImpl pm = createPageManager();
        Widget page = new Label();
        PageFactoryTestImpl factory = new PageFactoryTestImpl(page, pm);
        pm.registerPage(NAME, factory, true);
        assertSame("Wrong page (1)", page, pm.getPageWidget(NAME));
        assertSame("Wrong page (2)", page, pm.getPageWidget(NAME));
        assertEquals("Wrong number of calls", 1, factory.getNumberOfCalls());
    }

    /**
     * Tests whether enumerations can be registered.
     */
    public void testRegisterPagesNoSingleton()
    {
        PageManagerImpl pm = createPageManager();
        pm.registerPages(TestPages.values());
        for (TestPages p : TestPages.values())
        {
            Label lab = (Label) pm.getPageWidget(p);
            assertEquals("Wrong label text", p.name(), lab.getText());
            assertNotSame("Only a single instance", lab, pm.getPageWidget(p));
        }
    }

    /**
     * Tests whether enumerations can be registered as singletons.
     */
    public void testRegisterPagesSingleton()
    {
        PageManagerImpl pm = createPageManager();
        pm.registerPages(TestPages.values(), true);
        for (TestPages p : TestPages.values())
        {
            Label lab = (Label) pm.getPageWidget(p);
            assertEquals("Wrong label text", p.name(), lab.getText());
            assertSame("Multiple instances", lab, pm.getPageWidget(p));
        }
    }

    /**
     * Tests getPageWidget() if the name cannot be resolved.
     */
    public void testGetPageWidgetUnknown()
    {
        PageManagerImpl pm = createPageManager();
        try
        {
            pm.getPageWidget(NAME);
            fail("Could obtain non-existing page!");
        }
        catch (IllegalArgumentException iex)
        {
            // ok
        }
    }

    /**
     * Tests getPageWidget() for an unknown enumeration constant.
     */
    public void testGetPageWidgetUnknownEnum()
    {
        PageManagerImpl pm = createPageManager();
        try
        {
            pm.getPageWidget(TestPages.PAGE1);
            fail("Could obtain non-existing page!");
        }
        catch (IllegalArgumentException iex)
        {
            // ok
        }
    }

    /**
     * Tests whether the names of registered pages can be queried.
     */
    public void testGetPageNames()
    {
        PageManagerImpl pm = createPageManager();
        pm.registerPage(NAME, new PageFactoryTestImpl(new Label(), pm));
        pm.registerPages(TestPages.values());
        Set<String> names = pm.getPageNames();
        assertEquals("Wrong number of pages", TestPages.values().length + 1,
                names.size());
        for (TestPages p : TestPages.values())
        {
            assertTrue("Page not found: " + p, names.contains(p.name()));
        }
        assertTrue("Test page not found", names.contains(NAME));
    }

    /**
     * Tests that the set with page names cannot be modified.
     */
    public void testGetPageNamesModify()
    {
        PageManagerImpl pm = createPageManager();
        pm.registerPages(TestPages.values());
        Set<String> names = pm.getPageNames();
        Iterator<String> it = names.iterator();
        it.next();
        try
        {
            it.remove();
            fail("Could manipulate set!");
        }
        catch (UnsupportedOperationException uex)
        {
            // ok
        }
    }

    /**
     * Tests whether a page specification can be created.
     */
    public void testCreatePageSpecification()
    {
        PageManagerImpl pm = createPageManager();
        pm.registerPage(NAME, new PageFactoryTestImpl(new Label(), pm));
        PageSpecificationImpl spec =
                (PageSpecificationImpl) pm.createPageSpecification(NAME);
        assertEquals("Wrong token", NAME, spec.toToken());
        assertSame("Wrong page manager", pm, spec.getPageManager());
    }

    /**
     * Tests whether a page specification for an enumeration constant can be
     * created.
     */
    public void testCreatePageSpecificationEnum()
    {
        PageManagerImpl pm = createPageManager();
        pm.registerPages(TestPages.values());
        PageSpecificationImpl spec =
                (PageSpecificationImpl) pm
                        .createPageSpecification(TestPages.PAGE1);
        assertEquals("Wrong token", TestPages.PAGE1.name(), spec.toToken());
        assertSame("Wrong page manager", pm, spec.getPageManager());
    }

    /**
     * Tries to query a page specification for an unknown page.
     */
    public void testCreatePageSpecificationUnknown()
    {
        PageManagerImpl pm = createPageManager();
        try
        {
            pm.createPageSpecification(NAME);
            fail("Could obtain specification for unknown page!");
        }
        catch (IllegalArgumentException iex)
        {
            // ok
        }
    }

    /**
     * Tests whether opening a page causes a history change event.
     */
    public void testOpenPage()
    {
        HistoryListener l = new HistoryListener();
        History.addValueChangeHandler(l);
        PageManagerImpl pm = createPageManager();
        pm.openPage(NAME);
        assertEquals("Wrong token", NAME, l.getToken());
    }

    /**
     * Creates a value changed event for the specified token.
     *
     * @param token the token
     * @return the event
     */
    private static ValueChangeEvent<String> createChangeEvent(String token)
    {
        return new ValueChangeEvent<String>(token)
        {
        };
    }

    /**
     * Tests onValueChange() for an unknown page. This should have no effect.
     */
    public void testOnValueChangeUnknownPage()
    {
        PageManagerImpl pm = createPageManager();
        pm.onValueChange(createChangeEvent(NAME));
        assertNull("Got a page", view.getActivePage());
    }

    /**
     * Tests onValueChange() if the target page does not implement the
     * PageConfigurationSupport interface.
     */
    public void testOnValueChangeNoConfigSupport()
    {
        PageManagerImpl pm = createPageManager();
        Widget page = new Label();
        pm.registerPage(NAME, new PageFactoryTestImpl(page, pm), true);
        pm.onValueChange(createChangeEvent(NAME));
        assertSame("Page not set", page, view.getActivePage());
    }

    /**
     * Tests onValueChange() if the target page implements the
     * PageConfigurationSupport interface.
     */
    public void testOnValueChangeConfigSupport()
    {
        PageManagerImpl pm = createPageManager();
        PageWidget page = new PageWidget();
        final String param = "testParam";
        final String value = "testValue";
        final String token = NAME + ";" + param + "=" + value;
        pm.registerPage(NAME, new PageFactoryTestImpl(page, pm), true);
        pm.onValueChange(createChangeEvent(token));
        assertSame("Page not set", page, view.getActivePage());
        PageConfiguration config = page.getPageConfiguration();
        assertEquals("Parameter not found", value,
                config.getStringParameter(param));
    }

    /**
     * Tests the initialize() method if no page specification is available.
     */
    public void testInitializeNoPage()
    {
        PageManagerImpl pm = createPageManager();
        pm.registerPages(TestPages.values(), true);
        pm.initialize();
        assertNull("Got a page", view.getActivePage());
        History.newItem(TestPages.PAGE1.name());
        assertEquals("Wrong active page", pm.getPageWidget(TestPages.PAGE1),
                view.getActivePage());
    }

    /**
     * Tests initialize() if a default page is provided.
     */
    public void testInitializeDefaultPage()
    {
        PageManagerImpl pm = createPageManager();
        pm.registerPages(TestPages.values(), true);
        pm.initialize(TestPages.PAGE1.name());
        assertEquals("Wrong active page", pm.getPageWidget(TestPages.PAGE1),
                view.getActivePage());
    }

    /**
     * Tests whether the initial page can be obtained from the history.
     */
    public void testInitializeFromHistory()
    {
        History.newItem(TestPages.PAGE1.name());
        PageManagerImpl pm = createPageManager();
        pm.registerPages(TestPages.values(), true);
        pm.initialize(NAME);
        assertEquals("Wrong active page", pm.getPageWidget(TestPages.PAGE1),
                view.getActivePage());
    }

    /**
     * A test implementation of the PageView interface.
     */
    private static class PageViewImpl implements PageView
    {
        /** The active page. */
        private Widget activePage;

        /**
         * Returns the active page.
         *
         * @return the active page
         */
        public Widget getActivePage()
        {
            return activePage;
        }

        /**
         * Just sets the passed in object as the active page.
         *
         * @param widget the widget to be displayed
         */
        @Override
        public void showPage(Widget widget)
        {
            activePage = widget;
        }
    }

    /**
     * A test implementation of the PageFactory interface.
     */
    private static class PageFactoryTestImpl implements PageFactory
    {
        /** The widget to be returned. */
        private final Widget pageWidget;

        /** The expected page manager. */
        private final PageManager expectedPM;

        /** A counter for the invocations of getPageWidget(). */
        private int calls;

        /**
         * Creates a new instance of {@code PageFactoryTestImpl}.
         *
         * @param page the page widget to be returned by this factory
         * @param pm the expected page manager
         */
        public PageFactoryTestImpl(Widget page, PageManager pm)
        {
            pageWidget = page;
            expectedPM = pm;
        }

        /**
         * Returns the number of calls to this factory.
         *
         * @return the number of calls
         */
        public int getNumberOfCalls()
        {
            return calls;
        }

        /**
         * Records this invocation, checks the argument, and returns the
         * specified test widget.
         */
        @Override
        public Widget getPageWidget(PageManager pageManager)
        {
            assertSame("Wrong page manager", expectedPM, pageManager);
            calls++;
            return pageWidget;
        }
    }

    /**
     * A specialized widget class for testing whether a page configuration is
     * automatically injected.
     */
    private static class PageWidget extends Composite implements
            PageConfigurationSupport
    {
        /** Stores the page configuration. */
        private PageConfiguration pageConfiguration;

        /**
         * Returns the page configuration object passed to this object.
         *
         * @return the page configuration
         */
        public PageConfiguration getPageConfiguration()
        {
            return pageConfiguration;
        }

        /**
         * Stores the passed in page configuration
         *
         * @param config the page configuration
         */
        @Override
        public void setPageConfiguration(PageConfiguration config)
        {
            pageConfiguration = config;
        }
    }

    /**
     * A test enumeration class defining some pages.
     */
    private static enum TestPages implements PageFactory
    {
        PAGE1, PAGE2, PAGE3;

        /**
         * Returns a label with the name of this constant.
         */
        @Override
        public Widget getPageWidget(PageManager pageManager)
        {
            return new Label(name());
        }
    }

    /**
     * A listener class for testing whether the correct history events are
     * produced.
     */
    private static class HistoryListener implements ValueChangeHandler<String>
    {
        /** The history token. */
        private String token;

        /**
         * Returns the token received.
         *
         * @return the token
         */
        public String getToken()
        {
            return token;
        }

        /**
         * Stores the passed in string as token.
         */
        @Override
        public void onValueChange(ValueChangeEvent<String> event)
        {
            token = event.getValue();
        }
    }
}
