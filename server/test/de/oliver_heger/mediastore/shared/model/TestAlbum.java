package de.oliver_heger.mediastore.shared.model;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.util.Locale;

import org.junit.Test;

import de.oliver_heger.mediastore.shared.persistence.PersistenceTestHelper;

/**
 * Test class for {@code Album}.
 *
 * @author Oliver Heger
 * @version $Id: $
 */
public class TestAlbum
{
    /** Constant for a test album name. */
    private static final String NAME = "Test Album";

    /** Constant of the test inception year. */
    private static final Integer YEAR = 2010;

    /**
     * Creates a test album instance.
     *
     * @return the test album
     */
    private Album createTestAlbum()
    {
        Album album = new Album();
        album.setName(NAME);
        album.setInceptionYear(YEAR);
        album.setUserID(PersistenceTestHelper.USER);
        return album;
    }

    /**
     * Tests a newly created instance.
     */
    @Test
    public void testInit()
    {
        Album album = new Album();
        assertNull("Got a name", album.getName());
        assertNull("Got an ID", album.getId());
        assertNull("Got a year", album.getInceptionYear());
        assertNull("Got a user", album.getUserID());
    }

    /**
     * Tests equals() if the expected result is true.
     */
    @Test
    public void testEqualsTrue()
    {
        Album album1 = createTestAlbum();
        PersistenceTestHelper.checkEquals(album1, album1, true);
        Album album2 = createTestAlbum();
        PersistenceTestHelper.checkEquals(album1, album2, true);
        album2.setName(NAME.toLowerCase(Locale.ENGLISH));
        PersistenceTestHelper.checkEquals(album1, album2, true);
        album2.setName(null);
        album1.setName(null);
        PersistenceTestHelper.checkEquals(album1, album2, true);
        album2.setInceptionYear(null);
        album1.setInceptionYear(null);
        PersistenceTestHelper.checkEquals(album1, album2, true);
        album2.setUserID(null);
        album1.setUserID(null);
        PersistenceTestHelper.checkEquals(album1, album2, true);
    }

    /**
     * Tests equals() if the expected result is false.
     */
    @Test
    public void testEqualsFalse()
    {
        Album album1 = createTestAlbum();
        Album album2 = createTestAlbum();
        album2.setName(null);
        PersistenceTestHelper.checkEquals(album1, album2, false);
        album2.setName(NAME + "_other");
        PersistenceTestHelper.checkEquals(album1, album2, false);
        album2.setName(NAME);
        album2.setInceptionYear(YEAR - 1);
        PersistenceTestHelper.checkEquals(album1, album2, false);
        album2.setInceptionYear(null);
        PersistenceTestHelper.checkEquals(album1, album2, false);
        album2.setInceptionYear(YEAR);
        album2.setUserID(PersistenceTestHelper.OTHER_USER);
        PersistenceTestHelper.checkEquals(album1, album2, false);
        album2.setUserID(null);
        PersistenceTestHelper.checkEquals(album1, album2, false);
    }

    /**
     * Tests equals() with other objects.
     */
    @Test
    public void testEqualsTrivial()
    {
        PersistenceTestHelper.checkEqualsTrivial(createTestAlbum());
    }

    /**
     * Tests the string representation of an album.
     */
    @Test
    public void testToString()
    {
        String s = createTestAlbum().toString();
        assertTrue("Name not found: " + s, s.contains("name = " + NAME));
        assertTrue("Year not found: " + s,
                s.contains("inceptionYear = " + YEAR));
    }

    /**
     * Tests toString() for undefined properties.
     */
    @Test
    public void testToStringNullValues()
    {
        Album album = new Album();
        String s = album.toString();
        assertTrue("Name not found: " + s, s.contains("name = null"));
        assertFalse("Got a year: " + s, s.contains("inceptionYear ="));
    }

    /**
     * Tests whether an album can be serialized.
     */
    @Test
    public void testSerialization() throws IOException
    {
        Album album = createTestAlbum();
        PersistenceTestHelper.checkSerialization(album);
    }
}
