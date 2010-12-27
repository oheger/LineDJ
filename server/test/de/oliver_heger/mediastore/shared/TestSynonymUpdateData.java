package de.oliver_heger.mediastore.shared;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.util.HashSet;
import java.util.Set;

import org.junit.Test;

/**
 * Test class for {@code SynonymUpdateData}.
 *
 * @author Oliver Heger
 * @version $Id: $
 */
public class TestSynonymUpdateData
{
    /** Constant for a synonym prefix. */
    private static final String SYN = "ATestSyonym_";

    /** Constant for the number of test objects. */
    private static final int COUNT = 8;

    /**
     * Creates a set with test synonyms.
     *
     * @return the synonym set
     */
    private static Set<String> createSynonymNames()
    {
        Set<String> result = new HashSet<String>();
        for (int i = 0; i < COUNT; i++)
        {
            result.add(SYN + i);
        }
        return result;
    }

    /**
     * Helper method for testing the set with synonyms to remove.
     *
     * @param data the data object
     */
    private static void checkRemoveSynonyms(SynonymUpdateData data)
    {
        Set<String> syns = data.getRemoveSynonyms();
        assertEquals("Wrong number", COUNT, syns.size());
        assertTrue("Invalid names: " + syns,
                syns.containsAll(createSynonymNames()));
    }

    /**
     * Creates a set with IDs for new synonyms.
     *
     * @return the synonym ID set
     */
    private static Set<Object> createSynonymIDs()
    {
        Set<Object> result = new HashSet<Object>();
        for (int i = 0; i < COUNT; i++)
        {
            result.add(Integer.valueOf(i));
        }
        return result;
    }

    /**
     * Helper method for testing the set with new synonym IDs.
     *
     * @param data the data object
     */
    private static void checkNewSynonymIDs(SynonymUpdateData data)
    {
        Set<Object> syns = data.getNewSynonymIDs();
        assertEquals("Wrong number", COUNT, syns.size());
        assertTrue("Invalid IDs: " + syns, syns.containsAll(createSynonymIDs()));
    }

    /**
     * Tests whether the default constructor works and null sets can be handled.
     */
    @Test
    public void testInitDefault()
    {
        SynonymUpdateData data = new SynonymUpdateData();
        assertTrue("Got remove synonyms", data.getRemoveSynonyms().isEmpty());
        assertTrue("Got new synonyms", data.getNewSynonymIDs().isEmpty());
    }

    /**
     * Tests whether the correct remove synonyms are returned.
     */
    @Test
    public void testGetRemoveSynonyms()
    {
        SynonymUpdateData data =
                new SynonymUpdateData(createSynonymNames(), null);
        checkRemoveSynonyms(data);
    }

    /**
     * Tests that the set with remove synonyms cannot be changed.
     */
    @Test(expected = UnsupportedOperationException.class)
    public void testGetRemoveSynonymsModify()
    {
        SynonymUpdateData data =
                new SynonymUpdateData(createSynonymNames(), null);
        data.getRemoveSynonyms().clear();
    }

    /**
     * Tests whether a copy is created from the remove synonyms.
     */
    @Test
    public void testRemoveSynonymsDefensiveCopy()
    {
        Set<String> syns = createSynonymNames();
        SynonymUpdateData data = new SynonymUpdateData(syns, null);
        syns.add(SYN);
        checkRemoveSynonyms(data);
    }

    /**
     * Tests whether the correct new synonym IDs are returned.
     */
    @Test
    public void testGetNewSynonymIDs()
    {
        SynonymUpdateData data =
                new SynonymUpdateData(null, createSynonymIDs());
        checkNewSynonymIDs(data);
    }

    /**
     * Tests that the set with new synonym IDs cannot be modified.
     */
    @Test(expected = UnsupportedOperationException.class)
    public void testGetNewSynonymIDsModify()
    {
        SynonymUpdateData data =
                new SynonymUpdateData(null, createSynonymIDs());
        data.getNewSynonymIDs().clear();
    }

    /**
     * Tests whether a copy is created from the new synonym IDs.
     */
    @Test
    public void testNewSynonymIDsDefensiveCopy()
    {
        Set<Object> syns = createSynonymIDs();
        SynonymUpdateData data = new SynonymUpdateData(null, syns);
        syns.add(this);
        checkNewSynonymIDs(data);
    }

    /**
     * Tests whether an instance can be serialized.
     */
    @Test
    public void testSerialization() throws IOException
    {
        SynonymUpdateData data =
                new SynonymUpdateData(createSynonymNames(), createSynonymIDs());
        SynonymUpdateData data2 = RemoteMediaStoreTestHelper.serialize(data);
        checkRemoveSynonyms(data2);
        checkNewSynonymIDs(data2);
    }
}
