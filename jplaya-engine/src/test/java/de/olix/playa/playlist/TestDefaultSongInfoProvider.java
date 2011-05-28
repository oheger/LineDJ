package de.olix.playa.playlist;

import java.io.IOException;
import java.net.URL;
import java.util.HashMap;
import java.util.Map;

import junit.framework.TestCase;
import de.olix.playa.engine.AudioInfo;

/**
 * Test class for DefaultSongInfoProvider.
 *
 * @author Oliver Heger
 * @version $Id$
 */
public class TestDefaultSongInfoProvider extends TestCase
{
    /** Constant for the resource name of the test mp3 file. */
    private static final String TEST_MP3 = "/test.mp3";

    /** Constant for the name of the interpret. */
    private static final String INTERPRET = "Testinterpret";

    /** Constant for the title. */
    private static final String TITLE = "Testtitle";

    /** Constant for the duration. */
    private static final Long DURATION = 120000L;

    /** Stores the provider to be tested. */
    private DefaultSongInfoProvider provider;

    @Override
    protected void setUp() throws Exception
    {
        super.setUp();
        provider = new DefaultSongInfoProvider();
    }

    /**
     * Creates some test properties.
     *
     * @return the map with the properties
     */
    private Map<String, Object> setUpTestProperties()
    {
        Map<String, Object> props = new HashMap<String, Object>();
        props.put(AudioInfo.KEY_TITLE, TITLE);
        props.put(AudioInfo.KEY_INTERPRET, INTERPRET);
        props.put(AudioInfo.KEY_DURATION, DURATION);

        // add some more properties
        for (int i = 0; i < 20; i++)
        {
            props.put("property" + i, i);
        }
        return props;
    }

    /**
     * Tests a newly created instance.
     */
    public void testInit()
    {
        assertNotNull("No audio info object set", provider.getAudioInfo());
    }

    /**
     * Tries setting the audio info object to null. This should cause an
     * exception.
     */
    public void testSetAudioInfoNull()
    {
        try
        {
            provider.setAudioInfo(null);
            fail("Could set audio info to null!");
        }
        catch (IllegalArgumentException iex)
        {
            // ok
        }
    }

    /**
     * Tests obtaining an info object for a URL.
     */
    public void testGetSongInfo() throws PlaylistException
    {
        URL url = getClass().getResource(TEST_MP3);
        AudioInfoTestImpl ainfo = new AudioInfoTestImpl();
        ainfo.mediaURL = url;
        ainfo.properties = setUpTestProperties();
        provider.setAudioInfo(ainfo);
        SongInfo info = provider.getSongInfo(url);
        assertEquals("Wrong title", TITLE, info.getTitle());
        assertEquals("Wrong interpret", INTERPRET, info.getInterpret());
        assertEquals("Wrong duration", DURATION, info.getDuration());
        assertEquals("Wrong properties", ainfo.properties, info.properties());
    }

    /**
     * Tests exception handling. The info object will throw an exception.
     */
    public void testGetSongInfoWithException()
    {
        URL url = getClass().getResource(TEST_MP3);
        AudioInfoTestImpl ainfo = new AudioInfoTestImpl();
        ainfo.mediaURL = url;
        provider.setAudioInfo(ainfo);
        try
        {
            provider.getSongInfo(url);
            fail("Exception not re-thrown!");
        }
        catch (PlaylistException pex)
        {
            // ok
        }
    }

    /**
     * Tries to call getSongInfo() with a null URL. This should cause an
     * exception.
     */
    public void testGetSongInfoNull() throws PlaylistException
    {
        try
        {
            provider.getSongInfo(null);
            fail("Could obtain null song info!");
        }
        catch (IllegalArgumentException iex)
        {
            // ok
        }
    }

    /**
     * A test implementation of the AudioInfo class that allows to specify the
     * properties to return.
     */
    private static class AudioInfoTestImpl extends AudioInfo
    {
        /** The properties to return. */
        Map<String, Object> properties;

        /** The expected URL. */
        URL mediaURL;

        /**
         * Returns the properties for the media file. This implementation will
         * return the internally set properties. If none have been set, an
         * exception will be thrown.
         */
        @Override
        public Map<String, Object> getPropertiesEx(URL mediaFile)
                throws IOException
        {
            assertEquals("Wrong URL", mediaURL, mediaFile);
            if (properties != null)
            {
                return properties;
            }
            throw new IOException("An exception!");
        }
    }
}
