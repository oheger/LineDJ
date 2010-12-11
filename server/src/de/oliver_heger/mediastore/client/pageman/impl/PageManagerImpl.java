package de.oliver_heger.mediastore.client.pageman.impl;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;

import com.google.gwt.event.logical.shared.ValueChangeEvent;
import com.google.gwt.event.logical.shared.ValueChangeHandler;
import com.google.gwt.user.client.History;
import com.google.gwt.user.client.ui.Widget;

import de.oliver_heger.mediastore.client.pageman.PageConfiguration;
import de.oliver_heger.mediastore.client.pageman.PageConfigurationSupport;
import de.oliver_heger.mediastore.client.pageman.PageFactory;
import de.oliver_heger.mediastore.client.pageman.PageManager;
import de.oliver_heger.mediastore.client.pageman.PageSpecification;
import de.oliver_heger.mediastore.client.pageman.PageView;

/**
 * <p>
 * A default implementation of the {@link PageManager} interface.
 * </p>
 * <p>
 * Using this class follows the pattern outlined below:
 * <ol>
 * <li>Create a new instance of {@code PageManagerImpl} and pass the
 * {@link PageView} to be used to the constructor.</li>
 * <li>Register all pages supported by your application.</li>
 * <li>Call the {@link #initialize(String)} method.</li>
 * </ol>
 * Now the page manager is up and running. {@code initialize()} has navigated to
 * the first page of the application (which has either been already available in
 * the history or is obtained from the default page).
 * </p>
 * <p>
 * In order to switch to another page, obtain a {@link PageSpecification} object
 * for this page by calling {@link #createPageSpecification(String)}. Then this
 * specification can be initialized. Finally call {@code open()} on the page
 * specification which displays the new page.
 * </p>
 *
 * @author Oliver Heger
 * @version $Id: $
 */
public class PageManagerImpl implements PageManager, ValueChangeHandler<String>
{
    /** The logger. */
    private static final Logger LOG = Logger.getLogger(PageManagerImpl.class
            .getName());

    /** Stores the page view. */
    private final PageView pageView;

    /** A map with the pages that have been registered. */
    private final Map<String, PageFactory> registeredPages;

    /** An immutable view on the map with pages. */
    private final Map<String, PageFactory> registeredPagesImmutable;

    /**
     * Creates a new instance of {@code PageManagerImpl} and initializes it with
     * the page view.
     *
     * @param view the page view (must not be <b>null</b>)
     * @throws IllegalArgumentException if the page view is <b>null</b>
     */
    public PageManagerImpl(PageView view)
    {
        if (view == null)
        {
            throw new IllegalArgumentException("PageView must not be null!");
        }

        pageView = view;
        registeredPages = new HashMap<String, PageFactory>();
        registeredPagesImmutable = Collections.unmodifiableMap(registeredPages);
    }

    /**
     * Initializes this page manager and navigates to the first page. This
     * method must be called after all pages have been registered. It registers
     * this page manager as listener at the history mechanism. Then it checks
     * whether already a history token is available. If this is the case, it
     * navigates to this page. Otherwise, the default token is evaluated. If it
     * is not <b>null</b>, this page becomes the current page.
     *
     * @param defaultToken the default token
     */
    public void initialize(String defaultToken)
    {
        History.addValueChangeHandler(this);

        if (!navigateToCurrentHistoryToken())
        {
            if (defaultToken != null)
            {
                openPage(defaultToken);
            }
        }
    }

    /**
     * Initializes this page manager. This implementation just calls the method
     * with the same name passing in <b>null</b> as default token. This means
     * that if the history does not already contain a token, no page is
     * displayed.
     */
    public void initialize()
    {
        initialize(null);
    }

    /**
     * Returns the {@link PageView} associated with this object. This
     * implementation returns the view that was passed at construction time.
     *
     * @return the {@link PageView}
     */
    @Override
    public PageView getPageView()
    {
        return pageView;
    }

    /**
     * Registers a page at this object.
     *
     * @param name the name of the page (must not be <b>null</b>)
     * @param factory the corresponding page factory (must not be <b>null</b>)
     * @param singleton the singleton flag
     * @throws NullPointerException if a required parameter is missing
     */
    @Override
    public void registerPage(String name, PageFactory factory, boolean singleton)
    {
        if (name == null)
        {
            throw new NullPointerException("Page name must not be null!");
        }
        if (factory == null)
        {
            throw new NullPointerException("PageFactory must not be null!");
        }

        PageFactory registeredFactory =
                singleton ? new SingletonPageFactory(factory) : factory;
        registeredPages.put(name, registeredFactory);
    }

    /**
     * Registers a non-singleton page at this object.
     *
     * @param name the name of the page (must not be <b>null</b>)
     * @param factory the corresponding page factory (must not be <b>null</b>)
     * @throws NullPointerException if a required parameter is missing
     */
    @Override
    public void registerPage(String name, PageFactory factory)
    {
        registerPage(name, factory, false);
    }

    /**
     * Registers pages defined by enumeration constants and allows setting the
     * singleton flag.
     *
     * @param pages the constants to be registered
     * @param singleton the singleton flag
     */
    @Override
    public <E extends Enum<E>> void registerPages(E[] pages, boolean singleton)
    {
        for (E page : pages)
        {
            registerPage(page.name(), (PageFactory) page, singleton);
        }
    }

    /**
     * Registers pages defined by enumeration constants.
     *
     * @param pages the constants to be registered
     */
    @Override
    public <E extends Enum<E>> void registerPages(E[] pages)
    {
        registerPages(pages, false);
    }

    /**
     * Creates a {@link PageSpecification} object for the page with the given
     * name.
     *
     * @param name the name of the page
     * @return the {@link PageSpecification} pointing to this page
     * @throws IllegalArgumentException if the page is not registered
     */
    @Override
    public PageSpecification createPageSpecification(String name)
    {
        if (!registeredPages.containsKey(name))
        {
            throw new IllegalArgumentException("Unknown page: " + name);
        }

        return new PageSpecificationImpl(this, name);
    }

    /**
     * Creates a {@link PageSpecification} object for the page described by the
     * given enumeration constant.
     *
     * @param page the enumeration constant referencing the page
     * @return the {@link PageSpecification} pointing to this page
     * @throws IllegalArgumentException if the page is not registered
     */
    @Override
    public <E extends Enum<E>> PageSpecification createPageSpecification(E page)
    {
        return createPageSpecification(page.name());
    }

    /**
     * Returns an unmodifiable set with the names of the pages that have been
     * registered.
     *
     * @return a set with the names of the registered pages
     */
    @Override
    public Set<String> getPageNames()
    {
        return registeredPagesImmutable.keySet();
    }

    /**
     * Returns the widget for the page with the given name.
     *
     * @param name the name of the desired page
     * @return the widget for this page
     * @throws IllegalArgumentException if the page name cannot be resolved
     */
    @Override
    public Widget getPageWidget(String name)
    {
        Widget page = fetchPageWidget(name);
        if (page == null)
        {
            throw new IllegalArgumentException("Page cannot be resolved: "
                    + name);
        }

        return page;
    }

    /**
     * Returns the widget for the page that was registered for the given
     * enumeration constant.
     *
     * @param page the enumeration constant
     * @return the widget for this page
     * @throws IllegalArgumentException if the page name cannot be resolved
     */
    @Override
    public <E extends Enum<E>> Widget getPageWidget(E page)
    {
        return getPageWidget(page.name());
    }

    /**
     * Opens the page described by the given token. This method is used
     * internally by helper classes to direct the page manager to a specific
     * page. The token passed in must conform to the format expected by the page
     * manager. It is added as new item to the history manager. This causes an
     * event to be fired which is then processed by the
     * {@link #onValueChange(ValueChangeEvent)} method.
     *
     * @param token the token describing the page to be opened
     */
    @Override
    public void openPage(String token)
    {
        History.newItem(token);
    }

    /**
     * Processes a value change event from the history manager. This method
     * contains the actual implementation for switching to another page. It
     * parses the token for the new page to a {@link PageConfiguration} and
     * obtains the corresponding page widget. This widget is passed to the page
     * view. If the page token cannot be processed, this method has no effect.
     *
     * @param event the value changed event
     */
    @Override
    public void onValueChange(ValueChangeEvent<String> event)
    {
        PageConfiguration config =
                PageConfigurationImpl.parse(event.getValue());
        Widget page = fetchPageWidget(config.getPageName());

        if (page != null)
        {
            initPageBeforeDisplay(page, config);
            getPageView().showPage(page);
        }
        else
        {
            LOG.log(Level.WARNING,
                    "onValueChange() for unknown page: " + event.getValue());
        }
    }

    /**
     * Helper method for obtaining the widget implementing a specific page. The
     * widget is obtained from the registered page factory. If the page is not
     * registered, <b>null</b> is returned.
     *
     * @param pageName the name of the page in question
     * @return the widget for this page or <b>null</b>
     */
    private Widget fetchPageWidget(String pageName)
    {
        PageFactory factory = registeredPages.get(pageName);
        return (factory != null) ? factory.getPageWidget(this) : null;
    }

    /**
     * Initializes a page widget before it is passed to the page view. This
     * implementation injects the page configuration if this is supported.
     *
     * @param page the page
     * @param config the page configuration
     */
    private void initPageBeforeDisplay(Widget page, PageConfiguration config)
    {
        if (page instanceof PageConfigurationSupport)
        {
            ((PageConfigurationSupport) page).setPageConfiguration(config);
        }
    }

    /**
     * Tries to navigate to the current page in the history. If there is already
     * a token when this object is initialized, the corresponding page is
     * displayed.
     *
     * @return a flag whether the history contained a token
     */
    private boolean navigateToCurrentHistoryToken()
    {
        if (History.getToken().length() > 0)
        {
            History.fireCurrentHistoryState();
            return true;
        }

        return false;
    }

    /**
     * A specialized implementation of {@code PageFactory} which implements the
     * singleton property. An instance of this class is initialized with another
     * factory. When called for the first time it delegates to this other
     * factory. Then it caches the result; on further calls the page widget is
     * directly returned. Note: On the client side everything is
     * single-threaded, so there is no need for any synchronization.
     */
    private static class SingletonPageFactory implements PageFactory
    {
        /** The wrapped factory. */
        private final PageFactory wrappedFactory;

        /** The page widget. */
        private Widget pageWidget;

        /**
         * Creates a new instance of {@code SingletonPageFactory} and sets the
         * underlying factory.
         *
         * @param f the wrapped factory
         */
        public SingletonPageFactory(PageFactory f)
        {
            wrappedFactory = f;
        }

        /**
         * Returns the page widget managed by this factory. This implementation
         * caches the widget obtained from the wrapped factory. On further calls
         * it is directly returned.
         *
         * @param pageManager the page manager
         * @return the page widget
         */
        @Override
        public Widget getPageWidget(PageManager pageManager)
        {
            if (pageWidget == null)
            {
                pageWidget = wrappedFactory.getPageWidget(pageManager);
            }
            return pageWidget;
        }
    }
}
