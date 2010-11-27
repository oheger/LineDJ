package de.oliver_heger.mediastore.server.search;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import org.junit.Test;

import de.oliver_heger.mediastore.server.model.ArtistEntity;
import de.oliver_heger.mediastore.shared.model.ArtistInfo;
import de.oliver_heger.mediastore.shared.persistence.PersistenceTestHelper;

/**
 * Test class for {@code ArtistSearchConverter}.
 *
 * @author Oliver Heger
 * @version $Id: $
 */
public class TestArtistSearchConverter
{
    /**
     * Tests a conversion.
     */
    @Test
    public void testConvert()
    {
        final Long artistID = 20101127213111L;
        @SuppressWarnings("serial")
        ArtistEntity e = new ArtistEntity()
        {
            @Override
            public Long getId()
            {
                return artistID;
            }
        };
        e.setName("test name");
        e.setUser(PersistenceTestHelper.getTestUser());
        ArtistInfo info = ArtistSearchConverter.INSTANCE.convert(e);
        assertEquals("Wrong ID", artistID, info.getArtistID());
        assertEquals("Wrong name", e.getName(), info.getName());
        assertEquals("Wrong creation date", e.getCreationDate(),
                info.getCreationDate());
    }

    /**
     * Tests a conversion if the member fields are mainly undefined.
     */
    @Test
    public void testConvertNullFields()
    {
        ArtistEntity e = new ArtistEntity();
        e.setCreationDate(null);
        ArtistInfo info = ArtistSearchConverter.INSTANCE.convert(e);
        assertNull("Got an ID", info.getArtistID());
        assertNull("Got a name", info.getName());
        assertNull("Got a creation date", info.getCreationDate());
    }

    /**
     * Tests a conversion if no entity is passed in.
     */
    @Test(expected = IllegalArgumentException.class)
    public void testConvertNull()
    {
        ArtistSearchConverter.INSTANCE.convert(null);
    }
}
