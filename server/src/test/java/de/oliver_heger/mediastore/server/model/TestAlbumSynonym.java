package de.oliver_heger.mediastore.server.model;

import java.io.IOException;
import java.util.Locale;

import org.junit.Before;
import org.junit.Test;

import de.oliver_heger.mediastore.shared.RemoteMediaStoreTestHelper;
import de.oliver_heger.mediastore.shared.persistence.PersistenceTestHelper;

/**
 * Test class for {@code AlbumSynonym}.
 *
 * @author Oliver Heger
 * @version $Id: $
 */
public class TestAlbumSynonym
{
    /** The synonym to be tested. */
    private AlbumSynonym synonym;

    @Before
    public void setUp() throws Exception
    {
        synonym = new AlbumSynonym();
    }

    /**
     * Tests equals() if the expected result is true.
     */
    @Test
    public void testEqualsTrue()
    {
        RemoteMediaStoreTestHelper.checkEquals(synonym, synonym, true);
        AlbumSynonym syn2 = new AlbumSynonym();
        RemoteMediaStoreTestHelper.checkEquals(synonym, syn2, true);
        synonym.setName("TestAlbum");
        syn2.setName(synonym.getName().toUpperCase(Locale.ENGLISH));
        RemoteMediaStoreTestHelper.checkEquals(synonym, syn2, true);
        AlbumEntity album = new AlbumEntity();
        synonym.setAlbum(album);
        syn2.setAlbum(album);
        RemoteMediaStoreTestHelper.checkEquals(synonym, syn2, true);
    }

    /**
     * Tests equals() if the expected result is false.
     */
    @Test
    public void testEqualsFalse()
    {
        AlbumSynonym syn2 = new AlbumSynonym();
        synonym.setName("A script for a Jester's Tear");
        RemoteMediaStoreTestHelper.checkEquals(synonym, syn2, false);
        syn2.setName("A brief encounter");
        RemoteMediaStoreTestHelper.checkEquals(synonym, syn2, false);
        syn2.setName(synonym.getName());
        synonym.setAlbum(new AlbumEntity());
        RemoteMediaStoreTestHelper.checkEquals(synonym, syn2, false);
        AlbumEntity a = new AlbumEntity();
        a.setName("Fugazzi");
        syn2.setAlbum(a);
        RemoteMediaStoreTestHelper.checkEquals(synonym, syn2, false);
    }

    /**
     * Tests equals() with other objects.
     */
    @Test
    public void testEqualsTrivial()
    {
        synonym.setName("Some synonym");
        RemoteMediaStoreTestHelper.checkEqualsTrivial(synonym);
    }

    /**
     * Tests whether an instance can be serialized.
     */
    @Test
    public void testSerialization() throws IOException
    {
        synonym.setName("The wedding album");
        synonym.setAlbum(new AlbumEntity());
        synonym.setUser(PersistenceTestHelper.getTestUser());
        RemoteMediaStoreTestHelper.checkSerialization(synonym);
    }
}
