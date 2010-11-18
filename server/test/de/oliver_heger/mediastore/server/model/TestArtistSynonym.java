package de.oliver_heger.mediastore.server.model;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import java.io.IOException;
import java.util.Locale;

import org.junit.Before;
import org.junit.Test;

import de.oliver_heger.mediastore.shared.RemoteMediaStoreTestHelper;

/**
 * Test class for {@code ArtistSynonym}. Some functionality of the base class is
 * tested, too.
 *
 * @author Oliver Heger
 * @version $Id: $
 */
public class TestArtistSynonym
{
    /** Constant for the synonym name. */
    private static final String NAME = "ArtistSynonym";

    /** The synonym to be tested. */
    private ArtistSynonym syn;

    @Before
    public void setUp() throws Exception
    {
        syn = new ArtistSynonym();
    }

    /**
     * Tests a newly created instance.
     */
    @Test
    public void testInit()
    {
        assertNull("Got an ID", syn.getId());
        assertNull("Got a name", syn.getName());
        assertNull("Got an artist", syn.getArtist());
        assertNull("Got a user", syn.getUser());
        assertNull("Got a search name", syn.getSearchName());
    }

    /**
     * Tests whether the search name is updated correctly.
     */
    @Test
    public void testGetSearchName()
    {
        syn.setName(NAME);
        assertEquals("Wrong search name", NAME.toUpperCase(Locale.ENGLISH),
                syn.getSearchName());
        syn.setName(null);
        assertNull("Got a search name", syn.getSearchName());
    }

    /**
     * Tests equals() if the expected result is true.
     */
    @Test
    public void testEqualsTrue()
    {
        RemoteMediaStoreTestHelper.checkEquals(syn, syn, true);
        ArtistSynonym syn2 = new ArtistSynonym();
        RemoteMediaStoreTestHelper.checkEquals(syn, syn2, true);
        syn.setName(NAME);
        syn2.setName(NAME);
        RemoteMediaStoreTestHelper.checkEquals(syn, syn2, true);
        syn2.setName(NAME.toLowerCase(Locale.ENGLISH));
        RemoteMediaStoreTestHelper.checkEquals(syn, syn2, true);
        syn.setArtist(new ArtistEntity());
        syn2.setArtist(syn.getArtist());
        RemoteMediaStoreTestHelper.checkEquals(syn, syn2, true);
    }

    /**
     * Tests equals() if the expected result is false.
     */
    @Test
    public void testEqualsFalse()
    {
        ArtistSynonym syn2 = new ArtistSynonym();
        syn.setName(NAME);
        RemoteMediaStoreTestHelper.checkEquals(syn, syn2, false);
        syn2.setName(NAME + "_other");
        RemoteMediaStoreTestHelper.checkEquals(syn, syn2, false);
        syn2.setName(NAME);
        syn.setArtist(new ArtistEntity());
        RemoteMediaStoreTestHelper.checkEquals(syn, syn2, false);
        ArtistEntity art = new ArtistEntity();
        art.setName(NAME + "_other");
        syn2.setArtist(art);
        RemoteMediaStoreTestHelper.checkEquals(syn, syn2, false);
    }

    /**
     * Tests equals() with other objects.
     */
    @Test
    public void testEqualsTrivial()
    {
        RemoteMediaStoreTestHelper.checkEqualsTrivial(syn);
    }

    /**
     * Tests the string representation.
     */
    @Test
    public void testToString()
    {
        syn.setName(NAME);
        RemoteMediaStoreTestHelper.checkToString(syn, "name = " + NAME);
    }

    /**
     * Tests whether an instance can be serialized.
     */
    @Test
    public void testSerialization() throws IOException
    {
        syn.setName(NAME);
        syn.setArtist(new ArtistEntity());
        RemoteMediaStoreTestHelper.checkSerialization(syn);
    }
}
