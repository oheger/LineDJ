package de.oliver_heger.mediastore.server.search;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;

import javax.persistence.EntityManager;

import org.easymock.EasyMock;
import org.junit.Before;
import org.junit.Test;

import de.oliver_heger.mediastore.server.model.ArtistEntity;
import de.oliver_heger.mediastore.server.model.SongEntity;
import de.oliver_heger.mediastore.shared.model.SongInfo;
import de.oliver_heger.mediastore.shared.persistence.PersistenceTestHelper;

/**
 * Test class for {@code SongSearchConverter}.
 *
 * @author Oliver Heger
 * @version $Id: $
 */
public class TestSongSearchConverter
{
    /** Constant for an ID of a referenced entity. */
    private static final Long REF_ID = 20110103204622L;

    /** The test converter. */
    private SongSearchConverter converter;

    @Before
    public void setUp() throws Exception
    {
        converter = new SongSearchConverter();
    }

    /**
     * Creates an artist entity with the specified ID.
     *
     * @param id the artist ID
     * @return the artist entity
     */
    private static ArtistEntity createArtist(final Long id)
    {
        return new ArtistEntity()
        {
            private static final long serialVersionUID = 1L;

            @Override
            public Long getId()
            {
                return id;
            }
        };
    }

    /**
     * Tests whether basic properties can be converted.
     */
    @Test
    public void testConvertProperties()
    {
        SongEntity entity = new SongEntity();
        entity.setCreationDate(new Date());
        entity.setDuration(Long.valueOf(20110103203502L));
        entity.setInceptionYear(1999);
        entity.setName("A test song");
        entity.setPlayCount(42);
        entity.setTrackNo(3);
        entity.setUser(PersistenceTestHelper.getTestUser());
        SongInfo info = converter.convert(entity);
        assertEquals("Wrong creation date", entity.getCreationDate(),
                info.getCreationDate());
        assertEquals("Wrong duration", entity.getDuration(), info.getDuration());
        assertEquals("Wrong year", entity.getInceptionYear(),
                info.getInceptionYear());
        assertEquals("Wrong name", entity.getName(), info.getName());
        assertEquals("Wrong play count", entity.getPlayCount(),
                info.getPlayCount());
        assertEquals("Wrong track", entity.getTrackNo(), info.getTrackNo());
        assertNull("Got an artist ID", info.getArtistID());
        assertNull("Got an artist name", info.getArtistName());
    }

    /**
     * Tests whether an artist can be resolved if the converter has been
     * initialized with a list of artist entities.
     */
    @Test
    public void testResolveArtistFromList()
    {
        ArtistEntity artist = createArtist(REF_ID);
        artist.setName("TestArtist");
        SongEntity entity = new SongEntity();
        entity.setArtistID(REF_ID);
        converter.initResolvedArtists(Collections.singleton(artist));
        SongInfo info = converter.convert(entity);
        assertEquals("Wrong artist ID", REF_ID, info.getArtistID());
        assertEquals("Wrong artist name", artist.getName(),
                info.getArtistName());
    }

    /**
     * Tests resolveArtist() if a list of artists is provided, but the ID cannot
     * be resolved.
     */
    @Test
    public void testResolveArtistFromListNotFound()
    {
        converter.initResolvedArtists(new ArrayList<ArtistEntity>());
        SongEntity song = new SongEntity();
        song.setArtistID(REF_ID);
        SongInfo info = converter.convert(song);
        assertNull("Got an artist ID", info.getArtistID());
        assertNull("Got an artist name", info.getArtistName());
    }

    /**
     * Tests whether an artist can be resolved using an entity manager.
     */
    @Test
    public void testResolveArtistEM()
    {
        EntityManager em = EasyMock.createMock(EntityManager.class);
        ArtistEntity artist = new ArtistEntity();
        artist.setName("An artist");
        EasyMock.expect(em.find(ArtistEntity.class, REF_ID)).andReturn(artist);
        EasyMock.replay(em);
        SongEntity song = new SongEntity();
        song.setArtistID(REF_ID);
        converter.setEntityManager(em);
        SongInfo info = converter.convert(song);
        assertEquals("Wrong artist ID", REF_ID, info.getArtistID());
        assertEquals("Wrong artist name", artist.getName(),
                info.getArtistName());
        EasyMock.verify(em);
    }

    /**
     * Tests resolveArtist() if the entity manager is used, but the ID cannot be
     * resolved.
     */
    @Test
    public void testResolveArtistEMNotFound()
    {
        EntityManager em = EasyMock.createMock(EntityManager.class);
        EasyMock.expect(em.find(ArtistEntity.class, REF_ID)).andReturn(null);
        EasyMock.replay(em);
        converter.setEntityManager(em);
        SongEntity song = new SongEntity();
        song.setArtistID(REF_ID);
        SongInfo info = converter.convert(song);
        assertNull("Got an artist ID", info.getArtistID());
        assertNull("Got an artist name", info.getArtistName());
        EasyMock.verify(em);
    }
}
