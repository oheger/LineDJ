package de.oliver_heger.mediastore.server.convert;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;

import javax.persistence.EntityManager;

import org.easymock.EasyMock;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import com.google.appengine.api.datastore.Key;
import com.google.appengine.api.datastore.KeyFactory;
import com.google.appengine.tools.development.testing.LocalDatastoreServiceTestConfig;

import de.oliver_heger.mediastore.server.model.AlbumEntity;
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
public class TestSongEntityConverter
{
    /** Constant for an ID of a referenced entity. */
    private static final Long REF_ID = 20110103204622L;

    /** The persistence test helper. */
    private final PersistenceTestHelper helper = new PersistenceTestHelper(
            new LocalDatastoreServiceTestConfig());

    /** The key of the song entity. */
    private Key key;

    /** The test converter. */
    private SongEntityConverter converter;

    @Before
    public void setUp() throws Exception
    {
        helper.setUp();
        key = KeyFactory.createKey("testKey", 20110104103228L);
        converter = new SongEntityConverter();
    }

    @After
    public void tearDown() throws Exception
    {
        helper.tearDown();
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
     * Creates an album entity with the specified ID.
     *
     * @param id the album ID
     * @return the album entity
     */
    private static AlbumEntity createAlbum(final Long id)
    {
        return new AlbumEntity()
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
        assertNull("Got a song ID", info.getSongID());
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
     * Tests whether the key of the entity is converted correctly.
     */
    @Test
    public void testConvertKey()
    {
        SongEntity entity = new SongEntity()
        {
            private static final long serialVersionUID = 1L;

            @Override
            public Key getId()
            {
                return key;
            }
        };
        SongInfo info = converter.convert(entity);
        assertEquals("Wrong ID", KeyFactory.keyToString(key), info.getSongID());
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

    /**
     * Tests resolveArtist() if neither an entity manager nor a list of artists
     * has been provided.
     */
    @Test
    public void testResolveArtistNoEM()
    {
        SongEntity song = new SongEntity();
        song.setArtistID(REF_ID);
        SongInfo info = converter.convert(song);
        assertNull("Got an artist ID", info.getArtistID());
        assertNull("Got an artist name", info.getArtistName());
    }

    /**
     * Tests whether an album ID can be converted from a list of resolved
     * albums.
     */
    @Test
    public void testResolveAlbumFromList()
    {
        AlbumEntity album = createAlbum(REF_ID);
        album.setName("Blurring the edges");
        SongEntity song = new SongEntity();
        song.setAlbumID(REF_ID);
        converter.initResolvedAlbums(Collections.singleton(album));
        SongInfo info = converter.convert(song);
        assertEquals("Wrong album ID", REF_ID, info.getAlbumID());
        assertEquals("Wrong album name", album.getName(), info.getAlbumName());
    }

    /**
     * Tests resolveAlbum() if a list of albums is provided, by an ID cannot be
     * resolved.
     */
    @Test
    public void testResolveAlbumFromListNotFound()
    {
        converter.initResolvedAlbums(new ArrayList<AlbumEntity>());
        SongEntity song = new SongEntity();
        song.setAlbumID(REF_ID);
        SongInfo info = converter.convert(song);
        assertNull("Got an album ID", info.getAlbumID());
        assertNull("Got an album name", info.getAlbumName());
    }

    /**
     * Tests whether an album can be resolved using the entity manager.
     */
    @Test
    public void testResolveAlbumEM()
    {
        EntityManager em = EasyMock.createMock(EntityManager.class);
        AlbumEntity album = new AlbumEntity();
        album.setName("Test Album");
        EasyMock.expect(em.find(AlbumEntity.class, REF_ID)).andReturn(album);
        EasyMock.replay(em);
        converter.setEntityManager(em);
        SongEntity e = new SongEntity();
        e.setAlbumID(REF_ID);
        SongInfo info = converter.convert(e);
        assertEquals("Wrong album name", album.getName(), info.getAlbumName());
        assertEquals("Wrong album ID", REF_ID, info.getAlbumID());
    }

    /**
     * Tests resolveAlbum() with an entity manager if the ID cannot be resolved.
     */
    @Test
    public void testResolveAlbumEMNotFound()
    {
        EntityManager em = EasyMock.createMock(EntityManager.class);
        AlbumEntity album = new AlbumEntity();
        album.setName("Test Album");
        EasyMock.expect(em.find(AlbumEntity.class, REF_ID)).andReturn(null);
        EasyMock.replay(em);
        converter.setEntityManager(em);
        SongEntity e = new SongEntity();
        e.setAlbumID(REF_ID);
        SongInfo info = converter.convert(e);
        assertNull("Got an album name", info.getAlbumName());
        assertNull("Got an album ID", info.getAlbumID());
    }

    /**
     * Tests resolveAlbum() if neither a list nor an EM are provided.
     */
    @Test
    public void testResolveAlbumNoEM()
    {
        SongEntity song = new SongEntity();
        song.setAlbumID(REF_ID);
        SongInfo info = converter.convert(song);
        assertNull("Got an album name", info.getAlbumName());
        assertNull("Got an album ID", info.getAlbumID());
    }

    /**
     * Tries to call convert() with a null entity.
     */
    @Test(expected = IllegalArgumentException.class)
    public void testConvertNullEntity()
    {
        converter.convert(null, new SongInfo());
    }

    /**
     * Tries to call convert() with a null info object.
     */
    @Test(expected = IllegalArgumentException.class)
    public void testConvertNullInfo()
    {
        converter.convert(new SongEntity(), null);
    }

    /**
     * Tests whether a null collection for initializing resolved elements is
     * handled as expected.
     */
    @Test(expected = NullPointerException.class)
    public void testInitListNull()
    {
        converter.initResolvedAlbums(null);
    }

    /**
     * Tests whether null elements in a collection for initializing resolved
     * elements are handled as expected.
     */
    @Test(expected = NullPointerException.class)
    public void testInitListNullElements()
    {
        List<ArtistEntity> arts = new ArrayList<ArtistEntity>();
        arts.add(createArtist(REF_ID));
        arts.add(null);
        converter.initResolvedArtists(arts);
    }
}
