package de.oliver_heger.mediastore.server.convert;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.easymock.EasyMock;
import org.junit.Test;

/**
 * Test class for {@code ConvertUtils}.
 *
 * @author Oliver Heger
 * @version $Id: $
 */
public class TestConvertUtils
{
    /**
     * Tests whether a list of entities can be converted.
     */
    @Test
    public void testConvertEntities()
    {
        @SuppressWarnings("unchecked")
        EntityConverter<Integer, String> conv =
                EasyMock.createMock(EntityConverter.class);
        final int count = 16;
        List<Integer> src = new ArrayList<Integer>(count);
        List<String> exp = new ArrayList<String>(count);
        for (int i = 0; i < count; i++)
        {
            Integer inp = Integer.valueOf(i);
            String c = String.valueOf(i);
            src.add(inp);
            exp.add(c);
            EasyMock.expect(conv.convert(inp)).andReturn(c);
        }
        EasyMock.replay(conv);
        assertEquals("Wrong result list", exp,
                ConvertUtils.convertEntities(src, conv));
        EasyMock.verify(conv);
    }

    /**
     * Tests convertEntities() if a null converter is passed in.
     */
    @Test(expected = NullPointerException.class)
    public void testConvertEntitiesNullConv()
    {
        ConvertUtils.convertEntities(new ArrayList<Integer>(), null);
    }

    /**
     * Tests convertEntities() if a null collection is passed in.
     */
    @Test
    public void testConvertEntitiesNullSrc()
    {
        @SuppressWarnings("unchecked")
        EntityConverter<Integer, String> conv =
                EasyMock.createMock(EntityConverter.class);
        EasyMock.replay(conv);
        List<Integer> src = null;
        assertTrue("Got results", ConvertUtils.convertEntities(src, conv)
                .isEmpty());
        EasyMock.verify(conv);
    }

    /**
     * Tests extractSynonymData() if a null collection is passed in.
     */
    @Test
    public void testExtractSynonymDataNull()
    {
        assertTrue("Got synonym data", ConvertUtils.extractSynonymData(null)
                .isEmpty());
    }
}
