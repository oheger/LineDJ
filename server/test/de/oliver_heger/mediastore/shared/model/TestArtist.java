package de.oliver_heger.mediastore.shared.model;

import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.util.Locale;

import org.junit.Test;

import de.oliver_heger.mediastore.shared.persistence.PersistenceTestHelper;

/**
 * Test class for {@code Artist}.
 *
 * @author Oliver Heger
 * @version $Id: $
 */
public class TestArtist
{
    /** Constant for the name of the artist. */
    private static final String TEST_NAME = "Mike Oldfield";

    /**
     * Tests a newly created instance.
     */
    @Test
    public void testInit()
    {
        Artist a = new Artist();
        assertNull("Got a name", a.getName());
        assertNull("Got a user", a.getUserID());
    }

    /**
     * Tests equals() if the expected result is true.
     */
    @Test
    public void testEqualsTrue()
    {
        Artist a1 = new Artist();
        PersistenceTestHelper.checkEquals(a1, a1, true);
        Artist a2 = new Artist();
        PersistenceTestHelper.checkEquals(a1, a2, true);
        a1.setName(TEST_NAME);
        a2.setName(TEST_NAME);
        PersistenceTestHelper.checkEquals(a1, a2, true);
        a2.setName(TEST_NAME.toLowerCase(Locale.ENGLISH));
        PersistenceTestHelper.checkEquals(a1, a2, true);
        a1.setUserID(PersistenceTestHelper.USER);
        a2.setUserID(PersistenceTestHelper.USER);
        PersistenceTestHelper.checkEquals(a1, a2, true);
    }

    /**
     * Tests equals() if the expected result is false.
     */
    @Test
    public void testEqualsFalse()
    {
        Artist a1 = new Artist();
        Artist a2 = new Artist();
        a1.setName(TEST_NAME);
        PersistenceTestHelper.checkEquals(a1, a2, false);
        a2.setName(TEST_NAME + "_OTHER");
        PersistenceTestHelper.checkEquals(a1, a2, false);
        a2.setName(TEST_NAME);
        a1.setUserID(PersistenceTestHelper.USER);
        PersistenceTestHelper.checkEquals(a1, a2, false);
        a2.setUserID(PersistenceTestHelper.OTHER_USER);
        PersistenceTestHelper.checkEquals(a1, a2, false);
    }

    /**
     * Tests equals() with other objects.
     */
    @Test
    public void testEqualsTrivial()
    {
        Artist a = new Artist();
        a.setName(TEST_NAME);
        PersistenceTestHelper.checkEqualsTrivial(a);
    }

    /**
     * Tests the string representation.
     */
    @Test
    public void testToString()
    {
        Artist a = new Artist();
        a.setName(TEST_NAME);
        String s = a.toString();
        assertTrue("Name not found: " + s, s.contains("name = " + TEST_NAME));
    }

    /**
     * Tests whether an artist can be serialized.
     */
    @Test
    public void testSerialization() throws IOException
    {
        Artist a = new Artist();
        a.setName(TEST_NAME);
        a.setUserID(PersistenceTestHelper.USER);
        PersistenceTestHelper.checkSerialization(a);
    }
}
