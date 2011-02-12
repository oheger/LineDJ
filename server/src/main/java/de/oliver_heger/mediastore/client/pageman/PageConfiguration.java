package de.oliver_heger.mediastore.client.pageman;

import java.util.Set;

/**
 * <p>
 * Definition of an interface providing information about a page which is
 * currently opened.
 * </p>
 * <p>
 * An object implementing this interface can be passed to a page when it becomes
 * the active page of the application. Through the methods provided it is
 * possible to access parameters passed to this page. In an application it is a
 * typical use case that an action in one page causes another page to be opened.
 * For instance, an overview page can provide buttons or links to open a detail
 * page for a selected element. Then all information required to identify the
 * element in question has to be passed to the detail page. The page can
 * evaluate this information and load the corresponding data.
 * </p>
 * <p>
 * {@code PageConfiguration} is tailored to use cases as described above. It
 * expects that parameters are typically primary keys of entities managed by the
 * application. It provides rudimentary data type conversion facilities, but
 * these are also focused on the typical types used for primary keys.
 * </p>
 *
 * @author Oliver Heger
 * @version $Id: $
 */
public interface PageConfiguration
{
    /**
     * Returns the name of the represented page. Each page has a unique name.
     *
     * @return the name of the associated page
     */
    String getPageName();

    /**
     * Returns the default parameter as a string. If a page has only a single
     * default parameter (which is a frequent case), no parameter name has to be
     * provided. This method returns the string value of this default parameter.
     *
     * @return the string value of the default parameter
     * @throws IllegalArgumentException if there is no default parameter
     */
    String getStringParameter();

    /**
     * Returns the default parameter as a long. If a page has only a single
     * default parameter (which is a frequent case), no parameter name has to be
     * provided. This method tries to convert the default parameter value to a
     * long. Often the ID of an element to be displayed or manipulated by the
     * current page is used as single parameter. For such scenarios this method
     * is appropriate.
     *
     * @return the default parameter converted to a long value
     * @throws NumberFormatException if conversion to a long fails
     * @throws IllegalArgumentException if there is no default parameter
     */
    long getLongParameter();

    /**
     * Returns the value of the parameter with the specified name as string.
     * Using this method a named parameter can be queried. Named parameters are
     * required for pages that have more than a single default parameter.
     *
     * @param name the name of the parameter
     * @return the value of this parameter
     * @throws IllegalArgumentException if the parameter cannot be resolved
     */
    String getStringParameter(String name);

    /**
     * Returns the value of the parameter with the specified name as long. Works
     * like {@link #getStringParameter(String)}, but converts the parameter
     * value to a long.
     *
     * @param name the name of the parameter
     * @return the value of this parameter converted to long
     * @throws NumberFormatException if conversion to a long fails
     * @throws IllegalArgumentException if the parameter cannot be resolved
     */
    long getLongParameter(String name);

    /**
     * Returns a set with the names of all parameters available. All strings
     * contained in this set can be passed to one of the
     * {@code getXXXParameter(String)} methods. If this object also contains a
     * default parameter, the set contains a <b>null</b> value.
     *
     * @return a set with the names of the parameters available
     */
    Set<String> getParameterNames();
}
