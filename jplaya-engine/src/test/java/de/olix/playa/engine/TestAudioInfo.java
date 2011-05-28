package de.olix.playa.engine;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.net.URL;
import java.net.URLConnection;
import java.net.URLStreamHandler;
import java.util.Map;

import junit.framework.TestCase;

/**
 * Test class for AudioInfo.
 *
 * @author Oliver Heger
 * @version $Id$
 */
public class TestAudioInfo extends TestCase
{
    /** Constant for the name of test file 1. */
    private static final String TEST1 = "/test.mp3";

    /** Constant for the name of test file 2. */
    private static final String TEST2 = "/test2.mp3";

    /** Constant for the test output directory. */
    private static final File TEST_DIR = new File("target");

    /** Constant for the name of the interpret. */
    private static final String INTERPRET = "Testinterpret";

    /** Constant for the title. */
    private static final String TITLE = "Testtitle";

    /** The object under test. */
    private AudioInfo info;

    @Override
    protected void setUp() throws Exception
    {
        super.setUp();
        info = new AudioInfo();
    }

    /**
     * Creates an URL that will cause an exception if it is accessed.
     *
     * @return the prepared URL
     */
    private URL getExceptionURL()
    {
        try
        {
            return new URL("test:", "www.test.de", 199, "test",
                    new URLStreamHandler()
                    {
                        @Override
                        protected URLConnection openConnection(URL u)
                                throws IOException
                        {
                            throw new IOException("An IO error occurred!");
                        }
                    });
        }
        catch (IOException ioex)
        {
            fail("A strange exception occurred: " + ioex);
            return null;
        }
    }

    /**
     * Tests obtaining properties for a mp3 file.
     */
    public void testGetProperties()
    {
        Map<String, Object> props = info.getProperties(getClass().getResource(
                TEST1));
        assertEquals("Wrong title", TITLE, props.get(AudioInfo.KEY_TITLE));
        assertEquals("Wrong interpret", INTERPRET, props
                .get(AudioInfo.KEY_INTERPRET));
        assertEquals("Wrong duration", 10842, ((Long) props
                .get(AudioInfo.KEY_DURATION)).intValue());
        assertTrue("Too few properties found", props.size() > 3);
    }

    /**
     * Tests querying properties if there are no ID3 tags. At least the duration
     * should be obtained.
     */
    public void testGetPropertiesUndefined()
    {
        Map<String, Object> props = info.getProperties(getClass().getResource(
                TEST2));
        Long duration = (Long) props.get(AudioInfo.KEY_DURATION);
        assertTrue("Wrong duration: " + duration, duration.intValue() > 6000
                && duration.intValue() < 7000);
    }

    /**
     * Tests the getProperties() method when an exception occurs.
     */
    public void testGetPropertiesWithException()
    {
        Map<String, Object> props = info.getProperties(getExceptionURL());
        assertTrue("Map not empty", props.isEmpty());
    }

    /**
     * Tests whether an exception is re-thrown by the ex variant.
     */
    public void testGetPropertiesExWithException()
    {
        try
        {
            info.getPropertiesEx(getExceptionURL());
            fail("Exception not re-thrown!");
        }
        catch (IOException ioex)
        {
            // ok
        }
    }

    /**
     * Tests passing in a null URL. This should cause an exception.
     */
    public void testGetPropertiesNull()
    {
        try
        {
            info.getProperties(null);
            fail("Null URL did not cause an exception!");
        }
        catch (IllegalArgumentException iex)
        {
            // ok
        }
    }

    /**
     * Tries to obtain info properties for a non supported format. This should
     * cause an exception.
     */
    public void testGetPropertiesUnsupported() throws IOException
    {
        File f = new File(TEST_DIR, "invalidtest.mp3");
        try
        {
            BufferedOutputStream out = new BufferedOutputStream(
                    new FileOutputStream(f));
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

            try
            {
                info.getPropertiesEx(f.toURL());
                fail("Could obtain properties for invalid mp3 file!");
            }
            catch (IOException ioex)
            {
                // ok
            }
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
     * Tests querying properties for a non existing file. This should cause an
     * exception.
     */
    public void testGetPropertiesNonExisting() throws IOException
    {
        File f = new File("nonExistingFile.mp4");
        try
        {
            info.getPropertiesEx(f.toURL());
            fail("No exception for non existing file!");
        }
        catch (IOException ioex)
        {
            // ok
        }
    }
}
