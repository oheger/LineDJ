package de.oliver_heger.jplaya.engine.mediainfo;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

import org.apache.commons.vfs.FileSystemManager;
import org.apache.commons.vfs.VFS;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;

import de.oliver_heger.jplaya.engine.mediainfo.SongDataLoaderImpl;
import de.oliver_heger.mediastore.service.SongData;

/**
 * Test class for {@code SongDataLoaderImpl}.
 *
 * @author Oliver Heger
 * @version $Id: $
 */
public class TestSongDataLoaderImpl
{
    /** Constant for the name of test file 1. */
    private static final String TEST1 = "res:test.mp3";

    /** Constant for the name of test file 2. */
    private static final String TEST2 = "res:test2.mp3";

    /** Constant for the test output directory. */
    private static final File TEST_DIR = new File("target");

    /** Constant for the name of the interpret. */
    private static final String INTERPRET = "Testinterpret";

    /** Constant for the title. */
    private static final String TITLE = "Testtitle";

    /** Constant for the original duration of the audio file. */
    private static final long DURATION = 10842;

    /** Constant for the duration of the audio file in seconds. */
    private static final long DURATION_SEC = 11;

    /** The file system manager. */
    private static FileSystemManager manager;

    /** The loader to be tested. */
    private SongDataLoaderImpl loader;

    @BeforeClass
    public static void setUpBeforeClass() throws Exception
    {
        manager = VFS.getManager();
    }

    @Before
    public void setUp() throws Exception
    {
        loader = new SongDataLoaderImpl(manager);
    }

    /**
     * Tries to create an instance without a manager.
     */
    @Test(expected = IllegalArgumentException.class)
    public void testInitNoManager()
    {
        new SongDataLoaderImpl(null);
    }

    /**
     * Tests obtaining properties for a mp3 file.
     */
    @Test
    public void testGetProperties() throws IOException
    {
        Map<String, Object> props = loader.getProperties(TEST1);
        assertEquals("Wrong title", TITLE,
                props.get(SongDataLoaderImpl.KEY_TITLE));
        assertEquals("Wrong interpret", INTERPRET,
                props.get(SongDataLoaderImpl.KEY_INTERPRET));
        assertEquals("Wrong duration", DURATION,
                ((Long) props.get(SongDataLoaderImpl.KEY_DURATION)).intValue());
        assertTrue("Too few properties found", props.size() > 3);
    }

    /**
     * Tests querying properties if there are no ID3 tags. At least the duration
     * should be obtained.
     */
    @Test
    public void testGetPropertiesUndefined() throws IOException
    {
        Map<String, Object> props = loader.getProperties(TEST2);
        Long duration = (Long) props.get(SongDataLoaderImpl.KEY_DURATION);
        assertTrue("Wrong duration: " + duration, duration.intValue() > 6000
                && duration.intValue() < 7000);
    }

    /**
     * Tries to extract song data from a null URI.
     */
    @Test(expected = IllegalArgumentException.class)
    public void testExtractSongDataNoURI()
    {
        loader.extractSongData(null);
    }

    /**
     * Tests whether a SongData object can be obtained for a media file.
     */
    @Test
    public void testExtractSongDataSuccess()
    {
        SongData data = loader.extractSongData(TEST1);
        assertEquals("Wrong title", TITLE, data.getName());
        assertEquals("Wrong interpret", INTERPRET, data.getArtistName());
        assertEquals("Wrong duration", DURATION_SEC, data.getDuration()
                .longValue());
        assertEquals("Wrong year", 2006, data.getInceptionYear().intValue());
        assertEquals("Wrong track", 1, data.getTrackNo().intValue());
        assertEquals("Wrong album", "A Test Collection", data.getAlbumName());
        assertEquals("Wrong play count", 0, data.getPlayCount());
    }

    /**
     * Tries to obtain media information for a non supported format.
     */
    @Test
    public void testExtractSongDataUnsupported() throws IOException
    {
        File f = new File(TEST_DIR, "invalidtest.mp3");
        try
        {
            BufferedOutputStream out =
                    new BufferedOutputStream(new FileOutputStream(f));
            try
            {
                for (int i = 1; i < 128; i++)
                {
                    out.write(i);
                }
            }
            finally
            {
                out.close();
            }
            assertNull("Got song data",
                    loader.extractSongData(f.toURI().toString()));
        }
        finally
        {
            if (f.exists())
            {
                assertTrue("Test file could not be deleted", f.delete());
            }
        }
    }

    /**
     * Tries to query media information for a non existing file.
     */
    @Test
    public void testGetPropertiesNonExisting() throws IOException
    {
        assertNull("Got song data",
                loader.extractSongData("nonExistingFile.mp4"));
    }

    /**
     * Tests whether undefined numeric properties are handled correctly.
     */
    @Test
    public void testExtractSongDataUndefinedNumericProperties()
    {
        Map<String, Object> props = new HashMap<String, Object>();
        SongData data = loader.createSongDataFromProperties(props);
        assertNull("Got a track number", data.getTrackNo());
    }

    /**
     * Tests whether invalid numeric properties are handled correctly.
     */
    @Test
    public void testExtractSongDataInvalidNumericProperties()
    {
        Map<String, Object> props = new HashMap<String, Object>();
        props.put(SongDataLoaderImpl.KEY_TRACK, "Not a number!");
        SongData data = loader.createSongDataFromProperties(props);
        assertNull("Got a track number", data.getTrackNo());
    }
}
