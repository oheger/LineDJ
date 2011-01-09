package de.oliver_heger.mediastore.shared;

import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.junit.Test;

/**
 * Test class for {@code ObjectUtils}.
 *
 * @author Oliver Heger
 * @version $Id: $
 */
public class TestObjectUtils
{
    /**
     * Tests nonNullList() for a defined list.
     */
    @Test
    public void testNonNullListNotNull()
    {
        List<Object> list = new ArrayList<Object>();
        assertSame("Wrong list", list, ObjectUtils.nonNullList(list));
    }

    /**
     * Tests nonNullList() for a null list.
     */
    @Test
    public void testNonNullListNull()
    {
        assertTrue("No empty list", ObjectUtils.nonNullList(null).isEmpty());
    }

    /**
     * Tests nonNullSet() for a defined set.
     */
    @Test
    public void testNonNullSetNotNull()
    {
        Set<Object> set = new HashSet<Object>();
        assertSame("Wrong set", set, ObjectUtils.nonNullSet(set));
    }

    /**
     * Tests nonNullSet() for a null set.
     */
    @Test
    public void testNonNullSetNull()
    {
        assertTrue("No empty set", ObjectUtils.nonNullSet(null).isEmpty());
    }
}
