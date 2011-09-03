package de.oliver_heger.jplaya.playlist.impl;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Iterator;
import java.util.List;

import org.apache.commons.configuration.HierarchicalConfiguration;
import org.junit.Before;
import org.junit.Test;

import de.oliver_heger.jplaya.playlist.KeepGroup;
import de.oliver_heger.jplaya.playlist.PlaylistOrder;
import de.oliver_heger.jplaya.playlist.impl.ImmutablePlaylistSettings;
import de.oliver_heger.jplaya.playlist.impl.PlaylistSettingsDTO;
import de.oliver_heger.mediastore.RemoteMediaStoreTestHelper;

/**
 * Test class for {@code PlaylistSettingsDTO}.
 *
 * @author Oliver Heger
 * @version $Id: $
 */
public class TestPlaylistSettingsDTO
{
    /** The object to be tested. */
    private PlaylistSettingsDTO dto;

    @Before
    public void setUp() throws Exception
    {
        dto = new PlaylistSettingsDTO();
    }

    /**
     * Tests a newly created instance.
     */
    @Test
    public void testInit()
    {
        assertNull("Got a name", dto.getName());
        assertNull("Got a description", dto.getDescription());
        assertNull("Got an order", dto.getOrder());
        assertTrue("Got keep groups", dto.getKeepGroups().isEmpty());
        assertTrue("Got an exact list", dto.getExactPlaylist().isEmpty());
    }

    /**
     * Tests the equals() implementation.
     */
    @Test
    public void testEquals()
    {
        RemoteMediaStoreTestHelper.checkEquals(dto, dto, true);
        PlaylistSettingsDTO dto2 = new PlaylistSettingsDTO();
        RemoteMediaStoreTestHelper.checkEquals(dto, dto2, true);
        dto2.setName("a name");
        RemoteMediaStoreTestHelper.checkEquals(dto, dto2, false);
    }

    /**
     * Tests equals() with other objects.
     */
    @Test
    public void testEqualsTrivial()
    {
        RemoteMediaStoreTestHelper.checkEqualsTrivial(dto);
    }

    /**
     * Tries to call save() without a configuration.
     */
    @Test(expected = IllegalArgumentException.class)
    public void testSaveNoConfig()
    {
        dto.setName("A name");
        dto.save(null);
    }

    /**
     * Tests save() if the object does not contain any data.
     */
    @Test
    public void testSaveNoProperties()
    {
        HierarchicalConfiguration config = new HierarchicalConfiguration();
        dto.save(config);
        assertTrue("Got data", config.isEmpty());
    }

    /**
     * Helper method for creating a keep group
     *
     * @param from the from index of the URIs
     * @param to the to index of the URIs
     * @return the keep group
     */
    private static KeepGroup createKeepGroup(int from, int to)
    {
        Collection<String> uris = new ArrayList<String>(to - from);
        for (int i = from; i <= to; i++)
        {
            uris.add("fileURI_" + i);
        }
        return new KeepGroup(uris);
    }

    /**
     * Helper method for comparing the contents of two collections.
     *
     * @param <E> the type of the collections
     * @param c1 collection 1
     * @param c2 collection 2
     */
    private static <E> void checkCollections(Collection<E> c1, Collection<E> c2)
    {
        assertEquals("Different sizes", c1.size(), c2.size());
        Iterator<E> it = c1.iterator();
        int idx = 0;
        for (E e : c2)
        {
            assertEquals("Wrong element at " + idx, e, it.next());
            idx++;
        }
    }

    /**
     * Tests save() with complex properties.
     */
    @Test
    public void testSave()
    {
        dto.setName("A name");
        dto.setDescription("A description");
        dto.setOrder(PlaylistOrder.EXACT);
        List<KeepGroup> groups = new ArrayList<KeepGroup>();
        groups.add(createKeepGroup(1, 10));
        groups.add(createKeepGroup(50, 52));
        groups.add(createKeepGroup(100, 111));
        dto.setKeepGroups(groups);
        List<String> exactList = new ArrayList<String>();
        for (int i = 0; i < 12; i++)
        {
            exactList.add("exactFile_" + i);
        }
        dto.setExactPlaylist(exactList);
        HierarchicalConfiguration config = new HierarchicalConfiguration();
        dto.save(config);
        ImmutablePlaylistSettings settings =
                ImmutablePlaylistSettings.newInstance(config);
        assertEquals("Wrong name", dto.getName(), settings.getName());
        assertEquals("Wrong desc", dto.getDescription(),
                settings.getDescription());
        assertEquals("Wrong order", dto.getOrder(), settings.getOrder());
        checkCollections(dto.getKeepGroups(), settings.getKeepGroups());
        checkCollections(dto.getExactPlaylist(), settings.getExactPlaylist());
    }
}
