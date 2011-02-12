package de.oliver_heger.mediastore.client.pageman;

/**
 * <p>
 * Definition of an interface for defining a page to be opened.
 * </p>
 * <p>
 * This interface is used by applications when they want to switch to another
 * page. An object implementing it is obtained from the page manager. It can
 * then be used to specify parameters. To display this page the {@link #open()}
 * method has to be called.
 * </p>
 *
 * @author Oliver Heger
 * @version $Id: $
 */
public interface PageSpecification
{
    /**
     * Adds the specified default parameter to this object. This parameter will
     * be available to the target page when it is opened.
     *
     * @param value the value of the default parameter
     * @return a reference to this object to support method chaining
     * @see #withParameter(String, Object)
     */
    PageSpecification withParameter(Object value);

    /**
     * Adds the specified parameter to this object. This parameter can be
     * queried by name from the target page when it is opened. Many pages just
     * support a single default parameter, e.g. the ID of an entity to be
     * displayed. In this case the overloaded method which does not expect a
     * parameter name can be used; this is a bit more convenient. If there are
     * multiple parameters, with this method corresponding names can be
     * specified. The parameter value is transformed to a string because it has
     * to be stored together with the URI of the page.
     *
     * @param name the parameter name (<b>null</b> for the default parameter)
     * @param value the value of the parameter
     * @return a reference to this object to support method chaining
     */
    PageSpecification withParameter(String name, Object value);

    /**
     * Opens the page described by this object. This method can be called after
     * all parameters have been set. It calls back to the page manager to
     * actually display this page.
     */
    void open();

    /**
     * Returns a token for the page described by this object which can be used
     * for the history mechanism. This token contains the name of this page and
     * the parameter in an encoded form.
     *
     * @return a token for the represented page
     */
    String toToken();
}
