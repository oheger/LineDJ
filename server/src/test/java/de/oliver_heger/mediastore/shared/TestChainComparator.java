package de.oliver_heger.mediastore.shared;

import static org.junit.Assert.assertEquals;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;

import org.easymock.EasyMock;
import org.junit.Test;

/**
 * Test class for {@code ChainComparator}.
 *
 * @author Oliver Heger
 * @version $Id: $
 */
public class TestChainComparator
{
    /**
     * Creates a comparator mock.
     *
     * @return the mock
     */
    private static Comparator<Object> comparatorMock()
    {
        @SuppressWarnings("unchecked")
        Comparator<Object> comp = EasyMock.createMock(Comparator.class);
        return comp;
    }

    /**
     * Tries to create an instance without children.
     */
    @Test(expected = NullPointerException.class)
    public void testInitNull()
    {
        new ChainComparator<Object>(null);
    }

    /**
     * Tries to create an instance with a collection that returns a null
     * element.
     */
    @Test(expected = NullPointerException.class)
    public void testInitNullElements()
    {
        Collection<Comparator<Object>> compCol =
                new ArrayList<Comparator<Object>>();
        compCol.add(comparatorMock());
        compCol.add(null);
        new ChainComparator<Object>(compCol);
    }

    /**
     * Tests whether a defensive copy from the collection passed to the
     * constructor is created.
     */
    @Test
    public void testInitDefensiveCopy()
    {
        Collection<Comparator<Object>> compCol =
                new ArrayList<Comparator<Object>>();
        ChainComparator<Object> comp = new ChainComparator<Object>(compCol);
        Comparator<Object> child = comparatorMock();
        EasyMock.replay(child);
        compCol.add(child);
        assertEquals("Wrong result", 0, comp.compare(this, "test"));
        EasyMock.verify(child);
    }

    /**
     * Tests whether an object can be compared if there is a difference.
     */
    @Test
    public void testCompareTo()
    {
        Comparator<Object> c1 = comparatorMock();
        Comparator<Object> c2 = comparatorMock();
        Comparator<Object> c3 = comparatorMock();
        final Object o1 = new Object();
        final Object o2 = new Object();
        EasyMock.expect(c1.compare(o1, o2)).andReturn(0);
        EasyMock.expect(c2.compare(o1, o2)).andReturn(1);
        EasyMock.replay(c1, c2, c3);
        Collection<Comparator<Object>> children =
                new ArrayList<Comparator<Object>>();
        children.add(c1);
        children.add(c2);
        children.add(c3);
        ChainComparator<Object> comp = new ChainComparator<Object>(children);
        assertEquals("Wrong result", 1, comp.compare(o1, o2));
        EasyMock.verify(c1, c2, c3);
    }

    /**
     * Tests whether objects can be compared if there are no differences.
     */
    @Test
    public void testCompareToNoDifference()
    {
        Comparator<Object> c1 = comparatorMock();
        Comparator<Object> c2 = comparatorMock();
        Comparator<Object> c3 = comparatorMock();
        final Object o1 = new Object();
        final Object o2 = new Object();
        EasyMock.expect(c1.compare(o1, o2)).andReturn(0);
        EasyMock.expect(c2.compare(o1, o2)).andReturn(0);
        EasyMock.expect(c3.compare(o1, o2)).andReturn(0);
        EasyMock.replay(c1, c2, c3);
        Collection<Comparator<Object>> children =
                new ArrayList<Comparator<Object>>();
        children.add(c1);
        children.add(c2);
        children.add(c3);
        ChainComparator<Object> comp = new ChainComparator<Object>(children);
        assertEquals("Wrong result", 0, comp.compare(o1, o2));
        EasyMock.verify(c1, c2, c3);
    }

    /**
     * Tests the optimization if the objects are actually the same.
     */
    @Test
    public void testCompareToSameObjects()
    {
        Comparator<Object> c = comparatorMock();
        EasyMock.replay(c);
        Collection<Comparator<Object>> children = Collections.singleton(c);
        ChainComparator<Object> comp = new ChainComparator<Object>(children);
        assertEquals("Wrong result", 0, comp.compare(this, this));
        EasyMock.verify(c);
    }
}
