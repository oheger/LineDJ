package de.olix.playa.engine;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;

import junit.framework.Assert;

/**
 * A helper class that provides some utility methods for tests that need to
 * generate test data for audio streams. Test data of arbitrary length can be
 * created.
 * 
 * @author Oliver Heger
 * @version $Id$
 */
class StreamHelper
{
    /** Constant for a sequence for generating test data. */
    private static final String TEST_SEQ = "ThisIsATest";

    /**
     * Creates a test data string of the specified length. Copies the test
     * sequence until the given length is reached.
     * 
     * @param len the desired length of the test data
     * @return a string with the test data
     */
    public static String createTestData(int len)
    {
        StringBuilder buf = new StringBuilder(len);
        do
        {
            buf.append(TEST_SEQ);
        } while (buf.length() < len);
        return (buf.length() > len) ? buf.substring(0, len) : buf.toString();
    }

    /**
     * Creates a stream that contains test data of the given length. Creates a
     * string with the test data and constructs a byte array stream from it.
     * 
     * @param len the desired length of the test data
     * @return the stream with the test data
     */
    public static InputStream createTestStream(int len)
    {
        return new ByteArrayInputStream(createTestData(len).getBytes());
    }

    /**
     * Tests if the given stream contains the expected test data.
     * 
     * @param in the input stream
     * @param len the length of the test data
     * @throws IOException if an IO error occurs
     */
    public static void checkTestData(InputStream in, int len) throws IOException
    {
        ByteArrayOutputStream bos = new ByteArrayOutputStream(len);
        byte[] buffy = new byte[len];
        int read;

        while ((read = in.read(buffy)) != -1)
        {
            bos.write(buffy, 0, read);
        }
        Assert.assertEquals("Wrong content of test stream", createTestData(len), bos
                .toString());
    }
    
    /**
     * Creates a test data string, which is a sub sequence from the test
     * sequence starting and ending at the specified indices.
     * @param from the start index
     * @param to the end ending (excluding)
     * @return the test sequence
     */
    public static String createTestData(int from, int to)
    {
        int offset = from % TEST_SEQ.length();
        String seq = createTestData(to - from + offset);
        return (offset == 0) ? seq : seq.substring(offset);
    }
    
    /**
     * Creates a byte array with test data corresponding to a sub sequence of the
     * test sequence starting at the specified indices.
     * @param from the start index
     * @param to the end index (excluding)
     * @return the test bytes
     */
    public static byte[] createTestBytes(int from, int to)
    {
        return createTestData(from, to).getBytes();
    }
}
