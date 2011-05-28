package de.olix.playa.playlist.xml;

import java.io.File;

import junit.framework.TestCase;

/**
 * Test class for PlaylistItem.
 * 
 * @author Oliver Heger
 * @version $Id$
 */
public class TestPlaylistItem extends TestCase
{
    /** Constant for the name of the interpret directory. */
    private static final String INTERPRET_DIR = "dir interpret";

    /** Constant for the name of the album directory. */
    private static final String ALBUM_DIR = "dir album";

    /** Constant for the file name. */
    private static final String FILE_NAME = "title.mp3";

    /** Constant for the root directory. */
    private static final File ROOT = new File("target");

    /** Constant for the test file. */
    private static final File TEST_FILE = new File(new File(new File(ROOT,
            INTERPRET_DIR), ALBUM_DIR), FILE_NAME);

    /** Stores the item to be tested. */
    private PlaylistItem item;

    /**
     * Creates a test playlist item with a relative path.
     * 
     * @return the test item
     */
    private PlaylistItem setUpTestItem()
    {
        return new PlaylistItem(ROOT.getAbsolutePath(), TEST_FILE);
    }

    /**
     * Creates a test playlist item that lies in the top level directory. So
     * this item won't have a relative path.
     * 
     * @return the test item
     */
    private PlaylistItem setUpTopLevelItem()
    {
        File f = new File(ROOT, FILE_NAME);
        return new PlaylistItem(ROOT.getAbsolutePath(), f);
    }

    /**
     * Tests whether the standard properties can be accessed.
     */
    public void testGetProperties()
    {
        item = setUpTestItem();
        String path = INTERPRET_DIR + PlaylistItem.PATH_SEPARATOR + ALBUM_DIR;
        assertEquals("Wrong relative path", path, item.getPath());
        assertEquals("Wrong file name", FILE_NAME, item.getName());
        assertEquals("Wrong path name", path + PlaylistItem.PATH_SEPARATOR
                + FILE_NAME, item.getPathName());
    }

    /**
     * Tests obtaining the file object.
     */
    public void testGetFile()
    {
        item = setUpTestItem();
        assertEquals("Wrong file object returned", TEST_FILE, item
                .getFile(ROOT));
    }

    /**
     * Tests the equals method.
     */
    public void testEquals()
    {
        item = setUpTestItem();
        PlaylistItem i2 = new PlaylistItem(ROOT.getAbsolutePath(), new File(
                ROOT.getName() + File.separator + INTERPRET_DIR
                        + File.separator + ALBUM_DIR + File.separator
                        + FILE_NAME));
        checkEquals(i2, true);
    }

    /**
     * Tests equals for non equal objects.
     */
    public void testNotEquals()
    {
        item = setUpTestItem();
        checkEquals(null, false);
        checkEquals("A test", false);
        PlaylistItem i2 = new PlaylistItem(ROOT.getAbsolutePath(), new File(
                ROOT.getName() + File.separator + INTERPRET_DIR
                        + File.separator + ALBUM_DIR + File.separator
                        + "anotherTitle.mp3"));
        checkEquals(i2, false);
    }

    /**
     * Tests an item that has no relative path.
     */
    public void testNoPath()
    {
        File itemFile = new File(ROOT, FILE_NAME);
        item = setUpTopLevelItem();
        assertEquals("Wrong path", "", item.getPath());
        assertEquals("Wrong name", FILE_NAME, item.getName());
        assertEquals("Wrong path name", FILE_NAME, item.getPathName());
        assertEquals("Wrong file", itemFile, item.getFile(ROOT));
        PlaylistItem i2 = new PlaylistItem(ROOT.getAbsolutePath(), itemFile);
        checkEquals(i2, true);
    }

    /**
     * Tests the compareTo() method for two equal objects.
     */
    public void testCompareToEquals()
    {
        item = setUpTestItem();
        PlaylistItem i2 = setUpTestItem();
        assertEquals("Wrong comparison result", 0, item.compareTo(i2));
        assertEquals("Comparison not reflexive", 0, i2.compareTo(item));
    }

    /**
     * Tests the compareTo() method for objects that are different.
     */
    public void testCompareToNotEquals()
    {
        item = setUpTestItem();
        PlaylistItem i2 = setUpTopLevelItem();
        assertTrue("Wrong order (1)", item.compareTo(i2) > 0);
        assertTrue("Wrong order (2)", i2.compareTo(item) < 0);
    }

    /**
     * Checks the equals() method.
     * 
     * @param c the object to compare with
     * @param expected the expected result
     */
    private void checkEquals(Object c, boolean expected)
    {
        assertEquals("Wrong result for equals", expected, item.equals(c));
        if (c != null)
        {
            assertEquals("Not symmetric", expected, c.equals(item));
        }
        if (expected)
        {
            assertTrue("Hash code not identical", item.hashCode() == c
                    .hashCode());
            assertEquals("compareTo() != 0", 0, item
                    .compareTo((PlaylistItem) c));
        }
    }

    /**
     * Tests the replace operation for file separators.
     */
    public void testReplace()
    {
        item = setUpTestItem();
        String path = "test" + File.separator + "path" + File.separator
                + "file";
        assertEquals("Wrong replaced path", "test*path*file", item.replace(
                path, File.separator, "*"));
    }

    /**
     * Tests the replace() method when nothing is to be replaced.
     */
    public void testReplaceNoOp()
    {
        item = setUpTestItem();
        String path = "test/path/file";
        assertSame("String was modified", path, item.replace(path, "/",
                PlaylistItem.PATH_SEPARATOR));
    }

    /**
     * Tests initializing an item from a path name.
     */
    public void testInitWithPath()
    {
        item = new PlaylistItem("test/my/file.txt");
        assertEquals("Wrong path name", "test/my/file.txt", item.getPathName());
        assertEquals("Wrong path", "test/my", item.getPath());
        assertEquals("Wrong name", "file.txt", item.getName());
    }

    /**
     * Tests the toString() method.
     */
    public void testToString()
    {
        item = setUpTopLevelItem();
        assertEquals("Wrong string representation", PlaylistItem.class
                .getName()
                + ": " + FILE_NAME, item.toString());
    }
}
