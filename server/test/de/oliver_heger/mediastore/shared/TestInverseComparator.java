package de.oliver_heger.mediastore.shared;

import static org.junit.Assert.assertEquals;

import java.util.Comparator;

import org.easymock.EasyMock;
import org.junit.Before;
import org.junit.Test;

/**
 * Test class for {@code InverseComparator}.
 *
 * @author Oliver Heger
 * @version $Id: $
 */
public class TestInverseComparator
{
    /** Constant for the first test object. */
    private static final Object O1 = new Object();

    /** Constant for the second test object. */
    private static final Object O2 = new Object();

    /** The wrapped comparator mock. */
    private Comparator<Object> wrapped;

    /** The comparator to be tested. */
    private InverseComparator<Object> comp;

    @SuppressWarnings("unchecked")
    @Before
    public void setUp() throws Exception
    {
        wrapped = EasyMock.createMock(Comparator.class);
        comp = new InverseComparator<Object>(wrapped);
    }

    /**
     * Tests compare() if the first object is greater.
     */
    @Test
    public void testCompareGreater()
    {
        EasyMock.expect(wrapped.compare(O1, O2)).andReturn(17);
        EasyMock.replay(wrapped);
        assertEquals("Wrong result", -1, comp.compare(O1, O2));
        EasyMock.verify(wrapped);
    }

    /**
     * Tests compare() if the first object is less than the 2nd.
     */
    @Test
    public void testCompareLess()
    {
        EasyMock.expect(wrapped.compare(O1, O2)).andReturn(-28);
        EasyMock.replay(wrapped);
        assertEquals("Wrong result", 1, comp.compare(O1, O2));
        EasyMock.verify(wrapped);
    }

    /**
     * Tests compare() if the objects are equal.
     */
    @Test
    public void testCompareEqual()
    {
        EasyMock.expect(wrapped.compare(O1, O2)).andReturn(0);
        EasyMock.replay(wrapped);
        assertEquals("Wrong result", 0, comp.compare(O1, O2));
        EasyMock.verify(wrapped);
    }

    /**
     * Tries to create an instance without a wrapped comparator.
     */
    @Test(expected = NullPointerException.class)
    public void testInitNull()
    {
        new InverseComparator<Object>(null);
    }
}
