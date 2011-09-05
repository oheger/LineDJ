package de.oliver_heger.jplaya.engine;

import java.util.ArrayList;

import org.easymock.EasyMock;

import de.oliver_heger.jplaya.engine.AudioStreamData;
import de.oliver_heger.jplaya.engine.IteratorAudioStreamSource;

import junit.framework.TestCase;

/**
 * Test class for IteratorAudioStreamSource.
 * 
 * @author Oliver Heger
 * @version $Id$
 */
public class TestIteratorAudioStreamSource extends TestCase
{
    /** Stores the instance to be tested. */
    private IteratorAudioStreamSource source;

    protected void setUp() throws Exception
    {
        super.setUp();
    }

    /**
     * Tests initializing the source with a null iterator. This should cause an
     * exception.
     */
    public void testInitNull()
    {
        try
        {
            source = new IteratorAudioStreamSource(null);
            fail("Could create instance with null iterator!");
        }
        catch (IllegalArgumentException iex)
        {
            // ok
        }
    }

    /**
     * Tests behavior if the passed in iterator is empty.
     */
    public void testEmptyIterator() throws InterruptedException
    {
        source = new IteratorAudioStreamSource(new ArrayList<AudioStreamData>()
                .iterator());
        AudioStreamData data = source.nextAudioStream();
        assertTrue("No end marker", data.size() < 0);
        data = source.nextAudioStream();
        assertTrue("No end marker on 2nd trial", data.size() < 0);
    }

    /**
     * Tests an iteration over some mock streams.
     */
    public void testNextAudioStream() throws InterruptedException
    {
        final int count = 10;
        ArrayList<AudioStreamData> list = new ArrayList<AudioStreamData>(count);
        for (int i = 0; i < count; i++)
        {
            list.add(initStreamData(i));
        }

        source = new IteratorAudioStreamSource(list.iterator());
        for (int i = 0; i < count; i++)
        {
            AudioStreamData asd = source.nextAudioStream();
            assertEquals("Wrong stream size", i, asd.size());
            assertEquals("Wrong stream name", String.valueOf(i), asd.getName());
        }
        AudioStreamData asd = source.nextAudioStream();
        assertTrue("No end marker found", asd.size() < 0);
    }

    /**
     * Creates a mock stream data object.
     * 
     * @param index the index used for initializing some properties
     * @return the initialized mock object
     */
    private AudioStreamData initStreamData(int index)
    {
        AudioStreamData asd = EasyMock.createMock(AudioStreamData.class);
        EasyMock.expect(asd.size()).andStubReturn(Long.valueOf(index));
        EasyMock.expect(asd.getName()).andReturn(String.valueOf(index));
        EasyMock.replay(asd);
        return asd;
    }
}
