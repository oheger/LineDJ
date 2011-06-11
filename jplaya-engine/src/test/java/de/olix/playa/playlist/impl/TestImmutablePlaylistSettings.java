package de.olix.playa.playlist.impl;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import java.util.List;
import java.util.Locale;

import org.apache.commons.configuration.HierarchicalConfiguration;
import org.junit.Test;

import de.oliver_heger.mediastore.RemoteMediaStoreTestHelper;
import de.olix.playa.playlist.KeepGroup;
import de.olix.playa.playlist.PlaylistOrder;

/**
 * Test class for {@code ImmutablePlaylistSettings}.
 *
 * @author Oliver Heger
 * @version $Id: $
 */
public class TestImmutablePlaylistSettings
{
    /** The name for the playlist. */
    private static final String NAME = "Cool LaLa";

    /** The description of the playlist. */
    private static final String DESC = "Here goes the Punk up!";

    /**
     * Tries to create an instance without a configuration.
     */
    @Test(expected = IllegalArgumentException.class)
    public void testNewInstanceNoConfig()
    {
        ImmutablePlaylistSettings.newInstance(null);
    }

    /**
     * Tests the special empty instance.
     */
    @Test
    public void testEmptyInstance()
    {
        ImmutablePlaylistSettings settings =
                ImmutablePlaylistSettings.emptyInstance();
        assertNull("Got a name", settings.getName());
        assertNull("Got a description", settings.getDescription());
        assertEquals("Wrong order", PlaylistOrder.UNDEFINED,
                settings.getOrder());
        assertTrue("Got keep groups", settings.getKeepGroups().isEmpty());
        assertTrue("Got an exact list", settings.getExactPlaylist().isEmpty());
    }

    /**
     * Tests whether the empty instance is shared.
     */
    @Test
    public void testEmptyInstanceShared()
    {
        ImmutablePlaylistSettings settings =
                ImmutablePlaylistSettings.emptyInstance();
        assertSame("Multiple instances", settings,
                ImmutablePlaylistSettings.emptyInstance());
    }

    /**
     * Tests an instance created from a configuration.
     */
    @Test
    public void testNewInstance()
    {
        HierarchicalConfiguration config = new HierarchicalConfiguration();
        setUpConfig(config);
        ImmutablePlaylistSettings settings =
                ImmutablePlaylistSettings.newInstance(config);
        assertEquals("Wrong name", NAME, settings.getName());
        assertEquals("Wrong description", DESC, settings.getDescription());
        assertEquals("Wrong order", PlaylistOrder.RANDOM, settings.getOrder());
        List<KeepGroup> keepGroups = settings.getKeepGroups();
        assertEquals("Wrong number of keep groups", 2, keepGroups.size());
        KeepGroup kg = keepGroups.get(0);
        assertEquals("Wrong size of group 1", 2, kg.size());
        assertEquals("Wrong file 1 in group 1", "file1", kg.getSongURI(0));
        assertEquals("Wrong file 2 in group 1", "file2", kg.getSongURI(1));
        kg = keepGroups.get(1);
        assertEquals("Wrong size of group 2", 3, kg.size());
        assertEquals("Wrong file 1 in group 2", "file3", kg.getSongURI(0));
        assertEquals("Wrong file 2 in group 2", "file4", kg.getSongURI(1));
        assertEquals("Wrong file 3 in group 2", "file5", kg.getSongURI(2));
    }

    /**
     * Sets up a configuration with properties for playlist settings.
     *
     * @param config the configuration
     */
    private void setUpConfig(HierarchicalConfiguration config)
    {
        config.setProperty("name", NAME);
        config.setProperty("description", DESC);
        config.setProperty("order.mode", PlaylistOrder.RANDOM.name());
        config.addProperty("order.keep.file[@name]", "file1");
        config.addProperty("order.keep(0).file(-1)[@name]", "file2");
        config.addProperty("order.keep(-1).file[@name]", "file3");
        config.addProperty("order.keep(1).file(-1)[@name]", "file4");
        config.addProperty("order.keep(1).file(-1)[@name]", "file5");
    }

    /**
     * Creates and fills a configuration with properties for playlist settings.
     *
     * @return the configuration
     */
    private HierarchicalConfiguration setUpConfig()
    {
        HierarchicalConfiguration config = new HierarchicalConfiguration();
        setUpConfig(config);
        return config;
    }

    /**
     * Tests whether the exact order list can be parsed.
     */
    @Test
    public void testNewInstanceExactOrder()
    {
        HierarchicalConfiguration config = new HierarchicalConfiguration();
        config.setProperty("order.mode", PlaylistOrder.EXACT.name());
        config.addProperty("order.list.file(-1)[@name]", "file1");
        config.addProperty("order.list.file(-1)[@name]", "file2");
        ImmutablePlaylistSettings settings =
                ImmutablePlaylistSettings.newInstance(config);
        assertEquals("Wrong number of exact items", 2, settings
                .getExactPlaylist().size());
        assertEquals("Wrong file 1", "file1", settings.getExactPlaylist()
                .get(0));
        assertEquals("Wrong file 2", "file2", settings.getExactPlaylist()
                .get(1));
    }

    /**
     * Tests that the list with keep groups cannot be modified.
     */
    @Test(expected = UnsupportedOperationException.class)
    public void testGetKeepGroupsModify()
    {
        ImmutablePlaylistSettings.emptyInstance().getKeepGroups().add(null);
    }

    /**
     * Tests that the exact playlist cannot be modified.
     */
    @Test(expected = UnsupportedOperationException.class)
    public void testGetExactListModify()
    {
        ImmutablePlaylistSettings.emptyInstance().getExactPlaylist()
                .add("test");
    }

    /**
     * Helper method for testing the order property.
     *
     * @param orderStr the string to be passed to the property
     * @param expOrder the expected resulting order
     */
    private void checkOrder(String orderStr, PlaylistOrder expOrder)
    {
        HierarchicalConfiguration config = new HierarchicalConfiguration();
        config.addProperty("order.mode", orderStr);
        ImmutablePlaylistSettings settings =
                ImmutablePlaylistSettings.newInstance(config);
        assertEquals("Wrong order", expOrder, settings.getOrder());
    }

    /**
     * Tests whether case does not matter when defining the order.
     */
    @Test
    public void testOrderCase()
    {
        checkOrder(
                PlaylistOrder.DIRECTORIES.name().toLowerCase(Locale.ENGLISH),
                PlaylistOrder.DIRECTORIES);
    }

    /**
     * Tests whether an invalid order mode is handled correctly.
     */
    @Test
    public void testOrderUnknown()
    {
        checkOrder("unknown order string?", PlaylistOrder.UNDEFINED);
    }

    /**
     * Tests equals() if the expected result is true.
     */
    @Test
    public void testEqualsTrue()
    {
        HierarchicalConfiguration config = new HierarchicalConfiguration();
        ImmutablePlaylistSettings set1 =
                ImmutablePlaylistSettings.newInstance(config);
        RemoteMediaStoreTestHelper.checkEquals(set1, set1, true);
        RemoteMediaStoreTestHelper.checkEquals(set1,
                ImmutablePlaylistSettings.emptyInstance(), true);
        setUpConfig(config);
        set1 = ImmutablePlaylistSettings.newInstance(config);
        ImmutablePlaylistSettings set2 =
                ImmutablePlaylistSettings.newInstance(config);
        RemoteMediaStoreTestHelper.checkEquals(set1, set2, true);
    }

    /**
     * Tests equals() if the expected result is false.
     */
    @Test
    public void testEqualsFalse()
    {
        ImmutablePlaylistSettings set1 =
                ImmutablePlaylistSettings.newInstance(setUpConfig());
        HierarchicalConfiguration config = setUpConfig();
        config.clearProperty("name");
        ImmutablePlaylistSettings set2 =
                ImmutablePlaylistSettings.newInstance(config);
        RemoteMediaStoreTestHelper.checkEquals(set1, set2, false);
        config = setUpConfig();
        config.clearProperty("description");
        RemoteMediaStoreTestHelper.checkEquals(set1, set2, false);
        config = setUpConfig();
        config.clearProperty("order.mode");
        RemoteMediaStoreTestHelper.checkEquals(set1, set2, false);
        config = setUpConfig();
        config.clearTree("order.mode.keep(1)");
        RemoteMediaStoreTestHelper.checkEquals(set1, set2, false);
    }

    /**
     * Tests equals() with other objects.
     */
    @Test
    public void testEqualsTrivial()
    {
        RemoteMediaStoreTestHelper.checkEqualsTrivial(ImmutablePlaylistSettings
                .newInstance(setUpConfig()));
    }

    /**
     * Tests the string representation.
     */
    @Test
    public void testToString()
    {
        String s =
                ImmutablePlaylistSettings.newInstance(setUpConfig()).toString();
        assertTrue("Name not found: " + s, s.contains("name=" + NAME));
        assertTrue("Desc not found: " + s, s.contains("description=" + DESC));
    }
}
