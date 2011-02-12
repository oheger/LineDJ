package de.oliver_heger.mediastore.shared.model;

import org.junit.Test;

import de.oliver_heger.mediastore.shared.RemoteMediaStoreTestHelper;

/**
 * Test class for {@code ArtistComparators}.
 *
 * @author Oliver Heger
 * @version $Id: $
 */
public class TestArtistComparators
{
    /**
     * Helper method for testing the name comparator. The first name is intended
     * to be less than the second one.
     *
     * @param name1 first name
     * @param name2 second name
     */
    private void checkNameComparator(String name1, String name2)
    {
        ArtistInfo info1 = new ArtistInfo();
        info1.setName(name1);
        ArtistInfo info2 = new ArtistInfo();
        info2.setName(name2);
        RemoteMediaStoreTestHelper.checkComparator(info1, info2,
                ArtistComparators.NAME_COMPARATOR);
    }

    /**
     * Basic tests for the name comparator.
     */
    @Test
    public void testNameComparator()
    {
        checkNameComparator("Abba", "ZZ Top");
    }

    /**
     * Tests whether the name comparator ignores case.
     */
    @Test
    public void testNameComparatorCase()
    {
        checkNameComparator("abba", "zz top");
        checkNameComparator("ABBA", "zz top");
        checkNameComparator("abba", "ZZ TOP");
    }
}
