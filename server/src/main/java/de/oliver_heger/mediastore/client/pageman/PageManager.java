package de.oliver_heger.mediastore.client.pageman;

import java.util.Set;

import com.google.gwt.user.client.ui.Widget;

/**
 * <p>
 * The main interface of the page manager framework.
 * </p>
 * <p>
 * This interface allows the registration of an arbitrary number of pages for an
 * application. Then the application can switch between these page as it
 * desires.
 * </p>
 * <p>
 * The typical usage scenario is that on application startup an object
 * implementing this interface is created. Then the {@code registerPage()}
 * methods are called to specify the pages available. During runtime of the
 * application the manager can be asked for a {@link PageSpecification} for a
 * page to be opened. This specification object can be initialized with
 * parameters, and eventually used to open the page.
 * </p>
 *
 * @author Oliver Heger
 * @version $Id: $
 */
public interface PageManager
{
    /**
     * Returns the {@link PageView} object used by this manager. This is the
     * layout object actually responsible for displaying page widgets.
     *
     * @return the {@link PageView} used by this page manager
     */
    PageView getPageView();

    /**
     * Registers a page and its associated factory and allows setting the
     * <em>singleton</em> flag. When the page is to be displayed for the first
     * time, the {@link PageFactory} is invoked to create the corresponding
     * widget. If the page is marked as a singleton, the widget is cached, so
     * that the factory is not invoked again when the page is to be displayed
     * another time. If the <em>singleton</em> flag is <b>false</b>, each access
     * to the page causes the {@link PageFactory} to be called. So the factory
     * can decide if and when new instances of the page widget are to be
     * created.
     *
     * @param name the name under which the page is to be registered (must not
     *        be <b>null</b>)
     * @param factory the {@link PageFactory} for creating the widget
     *        implementing the page (must not be <b>null</b>)
     * @param singleton the <em>singleton</em> flag
     * @throws NullPointerException if a required parameter is missing
     */
    void registerPage(String name, PageFactory factory, boolean singleton);

    /**
     * Registers a page and its associated factory. This is a convenience method
     * which sets the <em>singleton</em> flag to false. This gives the
     * {@link PageFactory} full control over the instances of the page widget as
     * it is called every time the page is to be shown.
     *
     * @param name the name under which the page is to be registered (must not
     *        be <b>null</b>)
     * @param factory the {@link PageFactory} for creating the widget
     *        implementing the page (must not be <b>null</b>)
     * @throws NullPointerException if a required parameter is missing
     */
    void registerPage(String name, PageFactory factory);

    /**
     * Registers a set of pages defined by an enumeration type and allows
     * setting the <em>singleton</em> flag. In order to define constants for
     * page names in a safe way, it makes sense to create a corresponding
     * enumeration class. If the enumeration class also implements the
     * {@link PageFactory} interface, there is a direct association between page
     * names and factories. Also, the constants of the class can be used every
     * time access to a page is needed. This method supports such a design. It
     * expects a number of constants from an enumeration class which also
     * implements the {@link PageFactory} interface. Each constant is registered
     * under its name.
     *
     * @param <E> the type of the enumeration class
     * @param pages an array with enumeration constants defining pages to be
     *        registered
     * @param singleton the <em>singleton</em> flag
     */
    <E extends Enum<E>> void registerPages(E[] pages, boolean singleton);

    /**
     * Registers a set of pages defined by an enumeration type. Works like the
     * method with the same name, but always sets the <em>singleton</em> flag to
     * <b>false</b>.
     *
     * @param <E> the type of the enumeration class
     * @param pages an array with enumeration constants defining pages to be
     *        registered
     */
    <E extends Enum<E>> void registerPages(E[] pages);

    /**
     * Creates a {@link PageSpecification} object which can be used to
     * initialize and open a page. The methods defined by the
     * {@link PageSpecification} interface can be used to set arbitrary
     * parameters for the target page. Finally the page can be opened. Note that
     * the {@code PageManager} interface does not define a method for opening a
     * page directly. Rather, the {@link PageSpecification} obtained through
     * this method has to be used to navigate to a page.
     *
     * @param name the name of the page to be opened
     * @return a {@link PageSpecification} for defining and opening the page
     * @throws IllegalArgumentException if the page is not registered
     */
    PageSpecification createPageSpecification(String name);

    /**
     * Creates a {@link PageSpecification} object which can be used to
     * initialize and open the page defined by the passed in enumeration
     * constant. This is a convenience method which obtains the name of the page
     * from the enumeration constant. This is compatible with the
     * {@link #registerPages(Enum[])} method.
     *
     * @param <E> the type of the enumeration class
     * @param page the constant defining the page in question (must not be
     *        <b>null</b>)
     * @return a {@link PageSpecification} for defining and opening the page
     * @throws IllegalArgumentException if the page is not registered
     * @throws NullPointerException if the page constant is <b>null</b>
     */
    <E extends Enum<E>> PageSpecification createPageSpecification(E page);

    /**
     * Returns a set with the names of all pages registered at this manager.
     *
     * @return a set with the names of the pages that have been registered
     */
    Set<String> getPageNames();

    /**
     * Returns the widget representing the page with the given name. This method
     * can be used when a page widget needs to be accessed directly.
     *
     * @param name the name of the page
     * @return the corresponding widget
     * @throws IllegalArgumentException if the page is not registered
     */
    Widget getPageWidget(String name);

    /**
     * Returns the widget representing the page specified by the given
     * enumeration constant. This method works like the overloaded variant, but
     * the name of the page is extracted from the enumeration constant.
     *
     * @param <E> the type of the enumeration class
     * @param page the constant defining the page in question (must not be
     *        <b>null</b>)
     * @return the corresponding widget
     * @throws IllegalArgumentException if the page is not registered
     * @throws NullPointerException if the page constant is <b>null</b>
     */
    <E extends Enum<E>> Widget getPageWidget(E page);

    /**
     * Opens the page described by the given token. This method is a very direct
     * variant for opening a page. It expects a correct token to be passed in.
     * The recommended way of opening a page is to obtain a
     * {@link PageSpecification} object and calling {@code open()} on this
     * object.
     *
     * @param token the token describing the page to be opened
     * @throws NullPointerException if the token is <b>null</b>
     */
    void openPage(String token);
}
