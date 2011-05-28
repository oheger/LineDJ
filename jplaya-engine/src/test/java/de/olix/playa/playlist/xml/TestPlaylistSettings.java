package de.olix.playa.playlist.xml;

import java.io.File;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Set;

import de.olix.playa.playlist.PlaylistException;

import junit.framework.TestCase;

/**
 * Test class for PlaylistSettings.
 *
 * @author Oliver Heger
 * @version $Id$
 */
public class TestPlaylistSettings extends TestCase
{
    /** Constant for an item used for testing. */
    private static final PlaylistItem ITEM = new PlaylistItem("TESTITEM");

    /** Constant for the prefix of test playlist items. */
    private static final String ITEM_PREFIX = "ITEM_";

    /** Constant for a test file name for saving data.*/
    private static File SETTINGS_FILE = new File(new File("target"), "test.settings");

    /** Stores the instance to be tested. */
    private PlaylistSettings settings;

    @Override
    protected void setUp() throws Exception
    {
        super.setUp();
        settings = new PlaylistSettings();
    }

    /**
     * Clears the test environment. Removes the test settings file if it
     * exists.
     */
    @Override
    protected void tearDown() throws Exception
    {
        if (SETTINGS_FILE.exists())
        {
            assertTrue("Settings file could not be deleted", SETTINGS_FILE
                    .delete());
        }
    }

    /**
     * Creates a list with playlist items.
     *
     * @param count the number of items in the list
     * @return the initialized list
     */
    private List<PlaylistItem> itemList(int count)
    {
        ArrayList<PlaylistItem> list = new ArrayList<PlaylistItem>(count);
        for (int i = 0; i < count; i++)
        {
            list.add(new PlaylistItem(ITEM_PREFIX + i));
        }
        return list;
    }

    /**
     * Checks the content of an item list.
     *
     * @param list the list
     * @param expectedCount the number of expected items
     */
    private void checkItemList(List<PlaylistItem> list, int expectedCount)
    {
        assertEquals("Wrong number of items", expectedCount, list.size());
        for (int i = 0; i < expectedCount; i++)
        {
            assertEquals("Wrong item path", ITEM_PREFIX + i, list.get(i)
                    .getPathName());
        }
    }

    /**
     * Tests a newly created instance.
     */
    public void testInit()
    {
        assertNull("A name is defined", settings.getName());
        assertNull("A description is defined", settings.getDescription());
        assertNull("A file is defined", settings.getSettingsFile());
        assertTrue("Keep groups are defined", settings.getKeepGroups()
                .isEmpty());
        assertTrue("Exact list is defined", settings.getExactPlaylist()
                .isEmpty());
    }

    /**
     * Tries to obtain a non existing keep group. Null should be returned.
     */
    public void testGetKeepGroupUnknown()
    {
        assertNull("Keep group returned", settings.getKeepGroup(ITEM));
    }

    /**
     * Tests defining a keep group.
     */
    public void testSetKeepGroup()
    {
        final int count = 12;
        settings.setKeepGroup(ITEM, itemList(count));
        checkItemList(settings.getKeepGroup(ITEM), count);
        Set<PlaylistItem> groups = settings.getKeepGroups();
        assertEquals("Wrong number of groups", 1, groups.size());
        assertTrue("Group not found", groups.contains(ITEM));
    }

    /**
     * Tests using a null item for setting a keep group. This should cause an
     * exception.
     */
    public void testSetKeepGroupInvalidItem()
    {
        try
        {
            settings.setKeepGroup(null, itemList(2));
            fail("Could set a keep group for a null item!");
        }
        catch (IllegalArgumentException iex)
        {
            // ok
        }
    }

    /**
     * Tests using a null list for setting a keep group. This should cause an
     * exception.
     */
    public void testSetKeepGroupInvalidList()
    {
        try
        {
            settings.setKeepGroup(ITEM, null);
            fail("Could set a keep group with a null list!");
        }
        catch (IllegalArgumentException iex)
        {
            // ok
        }
    }

    /**
     * Tests removing a keep group.
     */
    public void testRemoveKeepGroup()
    {
        final int count = 27;
        settings.setKeepGroup(ITEM, itemList(count));
        List<PlaylistItem> items = settings.removeKeepGroup(ITEM);
        checkItemList(items, count);
        assertTrue("Group was not removed from set", settings.getKeepGroups()
                .isEmpty());
        assertNull("Group can still be found", settings.getKeepGroup(ITEM));
    }

    /**
     * Tests removing a keep group that does not exist. This should be a noop.
     */
    public void testRemoveUnknownKeepGroup()
    {
        assertNull("Wrong result of removeKeepGroup()", settings
                .removeKeepGroup(ITEM));
    }

    /**
     * Tests setting an exact playlist.
     */
    public void testSetExactPlaylist()
    {
        final int count = 32;
        settings.setExactPlaylist(itemList(count));
        checkItemList(settings.getExactPlaylist(), count);
    }

    /**
     * Tests setting the exact playlist to null.
     */
    public void testSetExactPlaylistNull()
    {
        settings.setExactPlaylist(itemList(4));
        settings.setExactPlaylist(null);
        assertTrue("Exact list not empty", settings.getExactPlaylist()
                .isEmpty());
    }

    /**
     * Tests saving a settings file and open it again.
     */
    public void testSaveAndLoad() throws PlaylistException
    {
        final File settingsFile = SETTINGS_FILE;
        final String name = "My playlist's name";
        final String desc = "Description for my playlist";
        final int groupCnt = 8;
        final int groupCnt2 = 1;
        final int exactCnt = 22;

        settings.setName(name);
        settings.setDescription(desc);
        settings.setOrder(PlaylistSettings.Order.random);
        settings.setKeepGroup(ITEM, itemList(groupCnt));
        PlaylistItem item2 = new PlaylistItem("Group2");
        settings.setKeepGroup(item2, itemList(groupCnt2));
        settings.setExactPlaylist(itemList(exactCnt));
        settings.setSettingsFile(settingsFile);

        settings.save();
        assertTrue("File was not created", settingsFile.exists());
        PlaylistSettings set2 = new PlaylistSettings();
        set2.setSettingsFile(settingsFile);
        set2.load();
        assertEquals("Wrong name", name, set2.getName());
        assertEquals("Wrong desc", desc, set2.getDescription());
        assertEquals("Wrong order", PlaylistSettings.Order.random, set2
                .getOrder());
        assertEquals("Wrong number of keep groups", 2, set2.getKeepGroups()
                .size());
        checkItemList(set2.getKeepGroup(ITEM), groupCnt);
        checkItemList(set2.getKeepGroup(item2), groupCnt2);
        checkItemList(set2.getExactPlaylist(), exactCnt);
    }

    /**
     * Tests whether special characters are correctly treated when saving and
     * loading data. I.e. the encoding must be correctly set and list delimiters
     * must be disabled.
     */
    public void testSaveSpecialCharacters() throws PlaylistException
    {
        final String name = "M\u00e4ine sch\u00f6ne, \u00c4c\u00fcstische M\u00fcsic";
        final String desc = "Viele Lieder, Tanz, Gesang und Tralali,"
                + "Hold\u00e4r\u00e4 Di dudeld\u00f6!";
        List<PlaylistItem> items = new ArrayList<PlaylistItem>();
        items.add(new PlaylistItem("Erzherz\u00f6g Johann-J\u00f6dler"));
        items
                .add(new PlaylistItem(
                        "\u00c4\u00d6\u00dc\u00e4\u00f6\u00fc\u00df"));
        settings.setName(name);
        settings.setDescription(desc);
        settings.setExactPlaylist(items);
        settings.setOrder(PlaylistSettings.Order.exact);
        settings.setSettingsFile(SETTINGS_FILE);
        settings.save();

        PlaylistSettings set2 = new PlaylistSettings();
        set2.setSettingsFile(SETTINGS_FILE);
        set2.load();
        assertEquals("Wrong name", name, set2.getName());
        assertEquals("Wrong desc", desc, set2.getDescription());
        assertEquals("Wrong order", PlaylistSettings.Order.exact, set2
                .getOrder());
        List<PlaylistItem> lst = settings.getExactPlaylist();
        assertEquals("Wrong list size", items.size(), lst.size());
        for (Iterator<PlaylistItem> it1 = items.iterator(), it2 = lst
                .iterator(); it1.hasNext();)
        {
            assertTrue("Items not equal", it1.next().equals(it2.next()));
        }
    }

    /**
     * Tests the load() method when no file was set. This should cause an
     * exception.
     */
    public void testLoadWithoutFile()
    {
        try
        {
            settings.load();
            fail("Could load from undefined file!");
        }
        catch (PlaylistException pex)
        {
            // ok
        }
    }

    /**
     * Tests the save() method when no file was set. This should cause an
     * exception.
     */
    public void testSaveWithoutFile()
    {
        try
        {
            settings.save();
            fail("Could load from undefined file!");
        }
        catch (PlaylistException pex)
        {
            // ok
        }
    }

    /**
     * Tests creating a random order.
     */
    public void testApplyOrderRandom()
    {
        final int count = 10;
        final int keepIdx = 4;
        final int testCount = 20;
        Playlist list = createPlaylist(count);
        settings.setOrder(PlaylistSettings.Order.random);
        PlaylistItem groupItem = new PlaylistItem(ITEM_PREFIX + keepIdx);
        List<PlaylistItem> group = new ArrayList<PlaylistItem>(1);
        group.add(new PlaylistItem(ITEM_PREFIX + (keepIdx + 1)));
        settings.setKeepGroup(groupItem, group);
        int changedOrderCnt = 0;

        for (int i = 0; i < testCount; i++)
        {
            boolean changedOrder = false;
            Playlist randomList = settings.applyOrder(list);
            assertEquals("Wrong size of playlist", count, randomList.size());
            int idx = 0;
            while (!randomList.isEmpty())
            {
                PlaylistItem item = randomList.take();
                int itemIdx = itemIndex(item);
                if (itemIdx != idx)
                {
                    changedOrder = true;
                }
                if (itemIdx == keepIdx)
                {
                    idx++;
                    item = randomList.take();
                    assertEquals("Group was not kept together", keepIdx + 1,
                            itemIndex(item));
                }
            }
            if (changedOrder)
            {
                changedOrderCnt++;
            }
        }

        assertTrue("Order of playlist was never changed", changedOrderCnt > 0);
    }

    /**
     * Tests creating a order based on the directory structure. Because this is
     * the natural order of a playlist after it was loaded, we only check here
     * if nothing was changed.
     */
    public void testApplyOrderDirectories()
    {
        final int count = 10;
        Playlist list = createPlaylist(count);
        settings.setOrder(PlaylistSettings.Order.directories);
        Playlist orderedList = settings.applyOrder(list);
        assertSame("Different playlist returned", list, orderedList);
        for (int i = 0; i < count; i++)
        {
            assertEquals("Wrong index", i, itemIndex(orderedList.take()));
        }
        assertTrue("Too many items", orderedList.isEmpty());
    }

    /**
     * Tests creating an exact order. Here the original content of the playlist
     * is thrown away and the explicitely specified files are added.
     */
    public void testApplyExactOrder()
    {
        final int[] indices =
        { 3, 9, 1, 17, 24, 11, 10, 4, 2, 19 };
        List<PlaylistItem> exactList = new ArrayList<PlaylistItem>(
                indices.length);
        for (int i = 0; i < indices.length; i++)
        {
            exactList.add(new PlaylistItem(ITEM_PREFIX + indices[i]));
        }
        settings.setExactPlaylist(exactList);
        settings.setOrder(PlaylistSettings.Order.exact);
        Playlist list = createPlaylist(33);
        Playlist targetList = settings.applyOrder(list);
        assertEquals("Wrong size of playlist", indices.length, targetList
                .size());
        for (int idx = 0; !targetList.isEmpty(); idx++)
        {
            assertEquals("Wrong playlist item", exactList.get(idx), targetList
                    .take());
        }
    }

    /**
     * Helper method for creating a test playlist.
     *
     * @param count the number of expected items
     * @return the list
     */
    private Playlist createPlaylist(int count)
    {
        Playlist list = new Playlist();
        for (int i = 0; i < count; i++)
        {
            list.addItem(new PlaylistItem(ITEM_PREFIX + i));
        }
        return list;
    }

    /**
     * Extracts the index from the passed in playlist item's name.
     *
     * @param item the item
     * @return the index
     */
    private int itemIndex(PlaylistItem item)
    {
        String pathName = item.getPathName();
        int idxpos = pathName.lastIndexOf('_');
        return Integer.parseInt(pathName.substring(idxpos + 1));
    }
}
