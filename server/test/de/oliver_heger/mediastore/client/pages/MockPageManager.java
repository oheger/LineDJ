package de.oliver_heger.mediastore.client.pages;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import junit.framework.Assert;

import com.google.gwt.user.client.ui.Widget;

import de.oliver_heger.mediastore.client.pageman.PageFactory;
import de.oliver_heger.mediastore.client.pageman.PageManager;
import de.oliver_heger.mediastore.client.pageman.PageSpecification;
import de.oliver_heger.mediastore.client.pageman.PageView;

/**
 * <p>
 * A mock {@link PageManager} implementation.
 * </p>
 * <p>
 * This class can be used to test whether navigation to other pages is done
 * correctly. Usage is as follows: Create an instance and call the
 * {@code expectCreatePageSpecification()} methods for the pages to be opened.
 * These methods return a {@link PageSpecification} mock object. On this object
 * parameters can be set as usual; by calling these methods for setting
 * parameters the expectations for the test code are defined. It is later
 * checked whether the test code actually set these parameters. Also the
 * {@code open()} and {@code toToken()} methods can be called to record these
 * expectations. At the end of the test the {@code verify()} method of
 * {@code MockPageManager} has to be called. It checks whether the page
 * specifications were created and initialized as expected.
 * </p>
 * <p>
 * All other methods are dummy implementations which throw an exception when
 * called. So this mock implementation just simulates opening a page using a
 * page specification.
 * </p>
 *
 * @author Oliver Heger
 * @version $Id: $
 */
public class MockPageManager implements PageManager
{
    /** Constant for the default token prefix. */
    private static final String TOKEN_PREFIX = "TOKEN_";

    /** A list with the expected page names. */
    private final List<Object> pageIdentifiers;

    /** A list with the mock page specifications. */
    private final List<MockPageSpecification> specifications;

    /** The current index in the list with expectations. */
    private int currentIndex;

    /**
     * Creates a new instance of {@code MockPageManager}.
     */
    public MockPageManager()
    {
        pageIdentifiers = new ArrayList<Object>();
        specifications = new ArrayList<MockPageSpecification>();
    }

    /**
     * Generates a default token for the page with the given name. This token
     * name is returned by a mock page specification if
     * {@code expectCreatePageSpecification()} is called with a <b>null</b>
     * token name.
     *
     * @param pageName the name of the page
     * @return the default token used for this page name
     */
    public static String defaultToken(String pageName)
    {
        return TOKEN_PREFIX + pageName;
    }

    /**
     * Generates a default token for the specified page. This token name is
     * returned by a mock page specification if
     * {@code expectCreatePageSpecification()} is called with a <b>null</b>
     * token name.
     *
     * @param <E> the type of the page specification
     * @param page the enumeration constant defining the page
     * @return the default token used for this page name
     */
    public static <E extends Enum<E>> String defaultToken(E page)
    {
        return defaultToken(page.name());
    }

    /**
     * Initializes this object to expect a request for a page specification for
     * the named page. Returns a mock specification object which can be
     * initialized with the expected parameters.
     *
     * @param name the name of the page
     * @param token the token to be returned by the {@code toToken()} method;
     *        can be <b>null</b>, then a default token is returned starting with
     *        the prefix {@code TOKEN_} followed by the page name
     * @return a mock page specification object
     */
    public PageSpecification expectCreatePageSpecification(String name,
            String token)
    {
        String tokenStr = (token != null) ? token : defaultToken(name);
        return processExpectedPageSpecification(name, tokenStr);
    }

    /**
     * Initializes this object to expect a request for a page specification for
     * the page defined by the enumeration constant. Returns a mock
     * specification object which can be initialized with the expected
     * parameters.
     *
     * @param <E> the type of the page specification
     * @param name the name of the page
     * @param token the token to be returned by the {@code toToken()} method;
     *        can be <b>null</b>, then a default token is returned starting with
     *        the prefix {@code TOKEN_} followed by the page name
     * @return a mock page specification object
     */
    public <E extends Enum<E>> PageSpecification expectCreatePageSpecification(
            E page, String token)
    {
        String tokenStr = (token != null) ? token : defaultToken(page);
        return processExpectedPageSpecification(page, tokenStr);
    }

    /**
     * Verifies whether all expectations have been fulfilled. This method should
     * be called at the end of the test to check whether the test code has
     * really opened all pages expected.
     */
    public void verify()
    {
        Assert.assertEquals("Remaining requests for page specifications: "
                + (specifications.size() - currentIndex),
                specifications.size(), currentIndex);
        for (MockPageSpecification spec : specifications)
        {
            spec.verify();
        }
    }

    /**
     * {@inheritDoc} Throws an exception because of an unexpected method call.
     */
    @Override
    public PageView getPageView()
    {
        throw new UnsupportedOperationException("Unexpected method call!");
    }

    /**
     * {@inheritDoc} Throws an exception because of an unexpected method call.
     */
    @Override
    public void registerPage(String name, PageFactory factory, boolean singleton)
    {
        throw new UnsupportedOperationException("Unexpected method call!");
    }

    /**
     * {@inheritDoc} Throws an exception because of an unexpected method call.
     */
    @Override
    public void registerPage(String name, PageFactory factory)
    {
        throw new UnsupportedOperationException("Unexpected method call!");
    }

    /**
     * {@inheritDoc} Throws an exception because of an unexpected method call.
     */
    @Override
    public <E extends Enum<E>> void registerPages(E[] pages, boolean singleton)
    {
        throw new UnsupportedOperationException("Unexpected method call!");
    }

    /**
     * {@inheritDoc} Throws an exception because of an unexpected method call.
     */
    @Override
    public <E extends Enum<E>> void registerPages(E[] pages)
    {
        throw new UnsupportedOperationException("Unexpected method call!");
    }

    @Override
    public PageSpecification createPageSpecification(String name)
    {
        return nextMockSpecification(name);
    }

    @Override
    public <E extends Enum<E>> PageSpecification createPageSpecification(E page)
    {
        return nextMockSpecification(page);
    }

    /**
     * {@inheritDoc} Throws an exception because of an unexpected method call.
     */
    @Override
    public Set<String> getPageNames()
    {
        throw new UnsupportedOperationException("Unexpected method call!");
    }

    /**
     * {@inheritDoc} Throws an exception because of an unexpected method call.
     */
    @Override
    public Widget getPageWidget(String name)
    {
        throw new UnsupportedOperationException("Unexpected method call!");
    }

    /**
     * {@inheritDoc} Throws an exception because of an unexpected method call.
     */
    @Override
    public <E extends Enum<E>> Widget getPageWidget(E page)
    {
        throw new UnsupportedOperationException("Unexpected method call!");
    }

    /**
     * {@inheritDoc} Throws an exception because of an unexpected method call.
     */
    @Override
    public void openPage(String token)
    {
        throw new UnsupportedOperationException("Unexpected method call!");
    }

    /**
     * Records the expectation for a page specification request.
     *
     * @param pageID the identifier for the page
     * @param token the token to be returned by toToken()
     * @return the mock page specification
     */
    private PageSpecification processExpectedPageSpecification(Object pageID,
            String token)
    {
        MockPageSpecification spec = new MockPageSpecification(token);
        pageIdentifiers.add(pageID);
        specifications.add(spec);
        return spec;
    }

    /**
     * Handles a request for a page specification by test code.
     *
     * @param pageID the identifier of the page
     * @return the mock page specification
     */
    private PageSpecification nextMockSpecification(Object pageID)
    {
        Assert.assertTrue("Too many requests for page specifications",
                currentIndex < specifications.size());
        Assert.assertEquals("Wrong page identifier",
                pageIdentifiers.get(currentIndex), pageID);
        MockPageSpecification spec = specifications.get(currentIndex++);
        spec.replay();
        return spec;
    }

    /**
     * A mock implementation of {@link PageSpecification} used internally. The
     * {@code createPageSpecification()} methods of the mock page manager return
     * instances of this class. This class supports two modes: In the record
     * mode method calls are just recorded and define the expectations on the
     * test code. In the replay mode the test code interacts with an instance.
     * It is checked whether the expected methods are called.
     */
    private static class MockPageSpecification implements PageSpecification
    {
        /** The token to be returned by toToken(). */
        private final String token;

        /** A map with the parameters. */
        private final Map<String, Object> parameters;

        /** A counter for the toToken() calls. */
        private int toTokenCount;

        /** A flag whether open was called. */
        private boolean openFlag;

        /** The replay flag. */
        private boolean replay;

        /**
         * Creates a new instance of {@code MockPageSpecification}.
         *
         * @param tokenStr the string to be returned by toToken()
         */
        public MockPageSpecification(String tokenStr)
        {
            token = tokenStr;
            parameters = new HashMap<String, Object>();
        }

        /**
         * Switches to replay mode. Now the methods called are checked against
         * the expectations defined before.
         */
        public void replay()
        {
            replay = true;
        }

        /**
         * Verifies whether all expectations have been met.
         */
        public void verify()
        {
            Assert.assertTrue(
                    "Not all parameters have been set: " + parameters,
                    parameters.isEmpty());
            Assert.assertEquals("Missing calls to toToken(): " + toTokenCount,
                    0, toTokenCount);
            Assert.assertFalse("open() not called", openFlag);
        }

        @Override
        public PageSpecification withParameter(Object value)
        {
            return withParameter(null, value);
        }

        @Override
        public PageSpecification withParameter(String name, Object value)
        {
            if (replay)
            {
                Object expValue = parameters.remove(name);
                Assert.assertNotNull("Unexpected parameter: " + name, expValue);
                Assert.assertEquals("Wrong parameter value", expValue, value);
            }
            else
            {
                parameters.put(name, value);
            }
            return this;
        }

        @Override
        public void open()
        {
            if (replay)
            {
                Assert.assertTrue("open() called too many times", openFlag);
                openFlag = false;
            }
            else
            {
                openFlag = true;
            }
        }

        @Override
        public String toToken()
        {
            if (replay)
            {
                Assert.assertTrue("toToken() called too many times",
                        toTokenCount > 0);
                toTokenCount--;
            }
            else
            {
                toTokenCount++;
            }
            return token;
        }
    }
}
