package de.olix.playa.playlist.xml;

import java.util.Iterator;
import java.util.NoSuchElementException;

import junit.framework.TestCase;

/**
 * Test class for Playlist.
 * 
 * @author Oliver Heger
 * @version $Id$
 */
public class TestPlaylist extends TestCase
{
    /** Constant for the prefix of playlist items.*/
    private static final String ITEM_PREFIX = "ITEM_";
    
    /** The playlist instance to be tested.*/
    private Playlist list;
    
    protected void setUp() throws Exception
    {
        super.setUp();
        list = new Playlist();
    }
    
    /**
     * Creates a playlist item that can be used for testing. The item will be
     * initialized with the name prefix appended by the given index.
     * @param index the index
     * @return the playlist item
     */
    private static PlaylistItem item(int index)
    {
        return new PlaylistItem(ITEM_PREFIX + index);
    }
    
    /**
     * Adds test items to a playlist.
     * @param lst the playlist
     * @param startIndex the index of the start item
     * @param count the number of items to add
     */
    private static void fillList(Playlist lst, int startIndex, int count)
    {
        for(int i = 0, idx = startIndex; i < count; i++, idx++)
        {
            lst.addItem(item(idx));
        }
    }
    
    /**
     * Adds test items to the test list.
     * @param startIndex the index of the start item
     * @param count the number of items to add
     */
    private void fillList(int startIndex, int count)
    {
        fillList(list, startIndex, count);
    }

    /**
     * Tests a newly created instance.
     */
    public void testInit()
    {
        assertEquals("Wrong list size", 0, list.size());
        assertTrue("List is not empty", list.isEmpty());
        assertFalse("Iterator has elements", list.items().hasNext());
    }
    
    /**
     * Tests adding items to the playlist.
     */
    public void testAddItem()
    {
        final int count = 10;
        fillList(0, count);
        checkList(count);
    }
    
    /**
     * Tests adding a null item to the playlist. This should cause an exception.
     */
    public void testAddItemNull()
    {
        try
        {
            list.addItem(null);
            fail("Could add null item!");
        }
        catch(IllegalArgumentException iex)
        {
            //ok
        }
    }
    
    /**
     * Tests adding another playlist to a list.
     */
    public void testAddItems()
    {
        final int count1 = 7;
        final int count2 = 19;
        Playlist list2 = new Playlist();
        fillList(0, count1);
        fillList(list2, count1, count2);
        list.addItems(list2);
        checkList(count1 + count2);
    }
    
    /**
     * Tests adding a null playlist. This should cause an exception.
     */
    public void testAddItemsNull()
    {
        try
        {
            list.addItems(null);
            fail("Could add null playlist!");
        }
        catch(IllegalArgumentException iex)
        {
            //ok
        }
    }
    
    /**
     * Tests the items in a playlist.
     * @param count the expected number of items
     */
    private void checkList(int count)
    {
        assertEquals("Wrong empty flag", count == 0, list.isEmpty());
        assertEquals("Wrong number of items", count, list.size());
        Iterator<PlaylistItem> it = list.items();
        for(int i = 0; i < count; i++)
        {
            assertTrue("Too few items in iteration", it.hasNext());
            PlaylistItem item = it.next();
            assertEquals("Wrong item at position " + i, ITEM_PREFIX + i, item.getPathName());
        }
        assertFalse("Too many items in iteration", it.hasNext());
    }
    
    /**
     * Tests removing items from a playlist.
     */
    public void testTake()
    {
        final int count = 11;
       fillList(0, count);
       for(int i = 0; i < count; i++)
       {
           assertEquals("Wrong list size", count - i, list.size());
           PlaylistItem item = list.take();
           assertEquals("Wrong item", ITEM_PREFIX + i, item.getPathName());
       }
       assertTrue("List not empty", list.isEmpty());
    }
    
    /**
     * Tests taking an element from an empty list. This should cause an exception.
     */
    public void testTakeEmpty()
    {
        try
        {
            list.take();
            fail("Could remove from an empty list!");
        }
        catch(NoSuchElementException nsex)
        {
            //ok
        }
    }
    
    /**
     * Tests obtaining the ID from a list.
     */
    public void testGetID()
    {
        fillList(10, 20);
        String id = list.getID();
        assertNotNull("List ID is null", id);
        assertFalse("List ID is empty string", "".equals(id));
        assertSame("Second ID is different", id, list.getID());
    }
    
    /**
     * Tests obtaining an ID for an empty list.
     */
    public void testGetIDEmpty()
    {
        String id = list.getID();
        assertNotNull("List ID is null", id);
        assertFalse("List ID is empty string", "".equals(id));
    }
    
    /**
     * Tests if manipulating a list changes its ID.
     */
    public void testGetIDChanged()
    {
        fillList(12, 34);
        String id = list.getID();
        list.take();
        String id2 = list.getID();
        assertFalse("ID not changed", id.equals(id2));
        list.addItem(item(100));
        String id3 = list.getID();
        assertFalse("Same as ID1", id.equals(id3));
        assertFalse("Same as ID2", id2.equals(id3));
    }
    
    /**
     * Tests if the calculated ID is the same for two lists with identic content.
     */
    public void testGetIDSameContent()
    {
        fillList(0, 17);
        Playlist list2 = new Playlist();
        fillList(list2, 0, 17);
        assertEquals("Different IDs", list.getID(), list2.getID());
    }
    
    /**
     * Tests sorting a playlist.
     */
    public void testSort()
    {
        int[] indices = { 9, 3, 5, 8, 1, 6, 2, 4, 0, 7 };
        for(int i = 0; i < indices.length; i++)
        {
            list.addItem(item(indices[i]));
        }
        list.sort();
        checkList(indices.length);
    }
    
    /**
     * Tests creating a random order.
     */
    public void testShuffle()
    {
        final int count = 32;
        fillList(0, count);
        int trial = 0;
        
        do
        {
            list.shuffle();
            int idx = 0;
            for(Iterator<PlaylistItem> it = list.items(); it.hasNext(); idx++)
            {
                if(!(ITEM_PREFIX + idx).equals(it.next().getPathName()))
                {
                    // order was changed
                    return;
                }
            }
        }while(++trial < 100);
        
        fail("Could not create a random order!");
    }
    
    /**
     * Tests the copy constructor of a playlist.
     */
    public void testCopyPlaylist()
    {
    	final int count = 8;
    	fillList(0, count);
    	Playlist copy = new Playlist(list);
    	assertEquals("Wrong size of playlist", count, copy.size());
    	while(!copy.isEmpty())
    	{
    		assertEquals("Wrong item", list.take(), copy.take());
    	}
    }
    
    /**
     * Tries calling the copy constructor with a null object. This should cause
     * an exception.
     */
    public void testCopyPlaylistNull()
    {
    	try
    	{
    		new Playlist(null);
    		fail("Could call copy constructor with null!");
    	}
    	catch(IllegalArgumentException iex)
    	{
    		//ok
    	}
    }
}
