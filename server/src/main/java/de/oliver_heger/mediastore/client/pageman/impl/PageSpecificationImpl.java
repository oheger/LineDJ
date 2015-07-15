package de.oliver_heger.mediastore.client.pageman.impl;

import de.oliver_heger.mediastore.client.pageman.PageManager;
import de.oliver_heger.mediastore.client.pageman.PageSpecification;

/**
 * <p>
 * A default implementation of the {@link PageSpecification} interface.
 * </p>
 * <p>
 * This class is used under the hood by the page manager implementation.
 * Instances are created by the page manager's {@code createPageSpecification()}
 * methods. They store a reference to the {@link PageManagerImpl} object so that
 * they can trigger it to open a page.
 * </p>
 * <p>
 * The methods for adding parameters operate on a string basis. They simply add
 * the new parameter and its value in an encoded form to the string buffer used
 * for building up the page token.
 * </p>
 *
 * @author Oliver Heger
 * @version $Id: $
 */
class PageSpecificationImpl implements PageSpecification
{
    /** Constant for the default buffer size. */
    private static final int BUF_SIZE = 128;

    /** Constant for an empty string. */
    private static final String EMPTY = "";

    /** Stores a reference to the page manager that created this instance. */
    private final PageManager pageManager;

    /** A buffer for building up the token. */
    private final StringBuilder bufToken;

    /**
     * Creates a new instance of {@code PageSpecificationImpl} and initializes
     * it with the associated page manager and the name of the target page.
     *
     * @param pm the owning {@link PageManagerImpl} object
     * @param pageName the name of the target page
     */
    public PageSpecificationImpl(PageManager pm, String pageName)
    {
        pageManager = pm;
        bufToken = new StringBuilder(BUF_SIZE);
        bufToken.append(pageName);
    }

    /**
     * Returns a reference to the {@link PageManager} associated with this
     * object.
     *
     * @return the associated page manager
     */
    public PageManager getPageManager()
    {
        return pageManager;
    }

    /**
     * Adds a default parameter.
     *
     * @param value the value of the default parameter
     * @return a reference to this object for method chaining
     * @throws IllegalArgumentException if the value is undefined
     */
    @Override
    public PageSpecification withParameter(Object value)
    {
        return withParameter(null, value);
    }

    /**
     * Adds a named parameter.
     *
     * @param name the name of the parameter
     * @param value the parameter value
     * @return a reference to this object for method chaining
     * @throws IllegalArgumentException if the value is undefined
     */
    @Override
    public PageSpecification withParameter(String name, Object value)
    {
        String sValue = checkAndEncodeValue(value);
        bufToken.append(ParametersHelper.PARAM_SEPARATOR);
        bufToken.append(ParametersHelper.encode(name));
        bufToken.append(ParametersHelper.VALUE_SEPARATOR);
        bufToken.append(sValue);
        return this;
    }

    /**
     * Opens the page specified by this object. This implementation calls back
     * to the associated page manager and passes the token constructed by this
     * object.
     */
    @Override
    public void open()
    {
        getPageManager().openPage(toToken());
    }

    /**
     * Returns the token for the page specified by this object.
     *
     * @return the token for the page
     */
    @Override
    public String toToken()
    {
        return bufToken.toString();
    }

    /**
     * Checks and encodes a parameter value. This method checks whether the
     * value is defined. If it contains special characters, they are encoded.
     *
     * @param value the parameter value
     * @return the encoded parameter value
     * @throws IllegalArgumentException if the value is undefined
     */
    private static String checkAndEncodeValue(Object value)
    {
        String sValue = (value == null) ? EMPTY : value.toString();
        if (sValue.length() < 1)
        {
            throw new IllegalArgumentException(
                    "Parameter value must not be null or empty!");
        }

        return ParametersHelper.encodeValue(sValue);
    }
}
