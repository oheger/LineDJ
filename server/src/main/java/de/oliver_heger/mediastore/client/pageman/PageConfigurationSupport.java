package de.oliver_heger.mediastore.client.pageman;

/**
 * <p>
 * Definition of an interface to be implemented by widgets to indicate that they
 * support the injection of a {@link PageConfiguration} object.
 * </p>
 * <p>
 * When a page is to be opened the framework determines the widget object
 * implementing the page. Before it is actually displayed it is checked whether
 * it implements the {@code PageConfigurationSupport} interface. If this is the
 * case, a {@link PageConfiguration} object is injected via the
 * {@code setPageConfiguration()} method.
 * </p>
 * <p>
 * Implementing this interface allows pages to take part in a communication
 * mechanism: The component which opens a page can specify an arbitrary number
 * of parameters. These parameters can be queried from a
 * {@link PageConfiguration} object. So if the target page implements this
 * interface, it gains access to a configuration and can obtain its parameters.
 * </p>
 *
 * @author Oliver Heger
 * @version $Id: $
 */
public interface PageConfigurationSupport
{
    /**
     * Passes the current {@link PageConfiguration} object to this object. This
     * method is called by the framework immediately before this page becomes
     * the new main page of the application. An implementation can for instance
     * retrieve some data based on the parameters defined in the configuration
     * object.
     *
     * @param config the {@link PageConfiguration} for this page
     */
    void setPageConfiguration(PageConfiguration config);
}
