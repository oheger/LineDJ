package de.oliver_heger.mediastore.service.utils;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;

import java.math.BigInteger;
import java.util.Date;

import org.junit.Test;

/**
 * Test class for {@code DTOTransformer}.
 *
 * @author Oliver Heger
 * @version $Id: $
 */
public class TestDTOTransformer
{
    /** Constant for a test long value. */
    private static final long LONG_VALUE = 20101109221019L;

    /** Constant for a test int value. */
    private static final int INT_VALUE = 6338373;

    /** Constant for a test string value. */
    private static final String STR_VALUE = "A test string!";

    /** Constant for a test date value. */
    private static final Date DATE_VALUE = new Date(20002020L);

    /**
     * Tries to perform a copy operation if the source bean is null.
     */
    @Test(expected = IllegalArgumentException.class)
    public void testTransformNullSource()
    {
        DTOTransformer.transform(null, new BeanB());
    }

    /**
     * Tries to perform a copy operation if the destination bean is null.
     */
    @Test(expected = IllegalArgumentException.class)
    public void testTransformNullDest()
    {
        DTOTransformer.transform(new BeanA(), null);
    }

    /**
     * Tests a successful transformation.
     */
    @Test
    public void testTransformSuccess()
    {
        BeanA a = new BeanA();
        a.setPropertyDate(DATE_VALUE);
        a.setPropertyInt(INT_VALUE);
        a.setPropertyLong(LONG_VALUE);
        a.setPropertyString(STR_VALUE);
        a.setSpecificAProperty(this);
        BeanB b = new BeanB();
        DTOTransformer.transform(a, b);
        assertEquals("Wrong date", DATE_VALUE, b.getPropertyDate());
        assertEquals("Wrong int", INT_VALUE, b.getPropertyInt());
        assertEquals("Wrong long", LONG_VALUE, b.getPropertyLong());
        assertEquals("Wrong string", STR_VALUE, b.getPropertyString());
        assertFalse("Specific property set", b.isSpecificBProperty());
    }

    /**
     * Tests a transformation if there is no date value set.
     */
    @Test
    public void testTransformNullDate()
    {
        BeanA a = new BeanA();
        a.setPropertyInt(INT_VALUE);
        BeanB b = new BeanB();
        DTOTransformer.transform(a, b);
        assertEquals("Wrong int", INT_VALUE, b.getPropertyInt());
        assertNull("Got a date", b.getPropertyDate());
    }

    /**
     * Tests whether wrapper types can take part in data conversion.
     */
    @Test
    public void testTransformWithWrappers()
    {
        BeanA a = new BeanA();
        a.setPropertyInt(INT_VALUE);
        a.setPropertyLong(LONG_VALUE);
        BeanWrappers bw = new BeanWrappers();
        DTOTransformer.transform(a, bw);
        assertEquals("Wrong int", INT_VALUE, bw.getPropertyInt().intValue());
        assertEquals("Wrong long", LONG_VALUE, bw.getPropertyLong().longValue());
        BeanB b = new BeanB();
        DTOTransformer.transform(bw, b);
        assertEquals("Wrong int (2)", INT_VALUE, b.getPropertyInt());
        assertEquals("Wrong long (2)", LONG_VALUE, b.getPropertyLong());
    }

    /**
     * Tests whether BigInteger properties can be converted.
     */
    @Test
    public void testTransformWithBigTypes()
    {
        BeanB b = new BeanB();
        b.setPropertyInt(INT_VALUE);
        b.setPropertyLong(LONG_VALUE);
        BeanBigProperties bb = new BeanBigProperties();
        DTOTransformer.transform(b, bb);
        assertEquals("Wrong int", INT_VALUE, bb.getPropertyInt().intValue());
        assertEquals("Wrong long", LONG_VALUE, bb.getPropertyLong().longValue());
    }

    /**
     * Tests transformations with BigInteger properties and wrappers.
     */
    @Test
    public void testTransformWithBigTypesAndWrappers()
    {
        BeanWrappers bw = new BeanWrappers();
        bw.setPropertyInt(INT_VALUE);
        bw.setPropertyLong(LONG_VALUE);
        BeanBigProperties bb = new BeanBigProperties();
        DTOTransformer.transform(bw, bb);
        assertEquals("Wrong int", INT_VALUE, bb.getPropertyInt().intValue());
        assertEquals("Wrong long", LONG_VALUE, bb.getPropertyLong().longValue());
    }

    /**
     * Tests the handling of undefined properties when converting to BigInteger.
     */
    @Test
    public void testTransformBigToWrappersUndefined()
    {
        BeanWrappers bw = new BeanWrappers();
        BeanBigProperties bb = new BeanBigProperties();
        DTOTransformer.transform(bb, bw);
        assertNull("Got a wrapper int", bw.getPropertyInt());
        assertNull("Got a wrapper long", bw.getPropertyLong());
    }

    /**
     * Tests the handling of undefined properties when converting to wrapper
     * types.
     */
    @Test
    public void testTransformWrapperToBigUndefined()
    {
        BeanWrappers bw = new BeanWrappers();
        BeanBigProperties bb = new BeanBigProperties();
        DTOTransformer.transform(bw, bb);
        assertNull("Got a big int", bb.getPropertyInt());
        assertNull("Got a big long", bb.getPropertyLong());
    }

    /**
     * Tests whether an invocation target exception is handled correctly during
     * a transform operation.
     */
    @Test(expected = IllegalArgumentException.class)
    public void testTransformITEx()
    {
        BeanA a = new BeanA()
        {
            @Override
            public Date getPropertyDate()
            {
                throw new RuntimeException("Test exception!");
            };
        };
        DTOTransformer.transform(a, new BeanB());
    }

    /**
     * Tests whether an Integer object can be transformed to a primitive.
     */
    @Test
    public void testToPrimitiveInteger()
    {
        Integer v = 20110222;
        assertEquals("Wrong result", v.intValue(),
                DTOTransformer.toPrimitive(v));
    }

    /**
     * Tests whether toPrimitive() can handle null Integer objects.
     */
    @Test
    public void testToPrimitiveIntegerNull()
    {
        assertEquals("Wrong result", DTOTransformer.UNDEFINED,
                DTOTransformer.toPrimitive((Integer) null));
    }

    /**
     * Tests whether a Long object can be transformed to a primitive.
     */
    @Test
    public void testToPrimitiveLong()
    {
        Long v = 20110222211832L;
        assertEquals("Wrong result", v.longValue(),
                DTOTransformer.toPrimitive(v));
    }

    /**
     * Tests whether toPrimitive() can handle null Long objects.
     */
    @Test
    public void testToPrimitiveLongNull()
    {
        assertEquals("Wrong result", DTOTransformer.UNDEFINED,
                DTOTransformer.toPrimitive((Long) null));
    }

    /**
     * Tests whether an int value can be converted to a wrapper.
     */
    @Test
    public void testToWrapperInt()
    {
        int value = 20110222;
        assertEquals("Wrong result", value, DTOTransformer.toWrapper(value)
                .intValue());
    }

    /**
     * Tests the conversion of an undefined int value to a wrapper.
     */
    @Test
    public void testToWrapperIntUndef()
    {
        int value = DTOTransformer.UNDEFINED;
        assertNull("Wrong result", DTOTransformer.toWrapper(value));
    }

    /**
     * Tests whether a long value can be converted to a wrapper.
     */
    @Test
    public void testToWrapperLong()
    {
        long value = 20110222212744L;
        assertEquals("Wrong result", value, DTOTransformer.toWrapper(value)
                .longValue());
    }

    /**
     * Tests the conversion of an undefined long value to a wrapper.
     */
    @Test
    public void testToWrapperLongUndef()
    {
        long value = DTOTransformer.UNDEFINED;
        assertNull("Wrong result", DTOTransformer.toWrapper(value));
    }

    /**
     * A simple test bean for testing whether all properties can be copied.
     */
    public static class BeanA
    {
        private long propertyLong;

        private int propertyInt;

        private String propertyString;

        private Date propertyDate;

        private Object specificAProperty;

        public long getPropertyLong()
        {
            return propertyLong;
        }

        public void setPropertyLong(long propertyLong)
        {
            this.propertyLong = propertyLong;
        }

        public int getPropertyInt()
        {
            return propertyInt;
        }

        public void setPropertyInt(int propertyInt)
        {
            this.propertyInt = propertyInt;
        }

        public String getPropertyString()
        {
            return propertyString;
        }

        public void setPropertyString(String propertyString)
        {
            this.propertyString = propertyString;
        }

        public Date getPropertyDate()
        {
            return propertyDate;
        }

        public void setPropertyDate(Date propertyDate)
        {
            this.propertyDate = propertyDate;
        }

        public Object getSpecificAProperty()
        {
            return specificAProperty;
        }

        public void setSpecificAProperty(Object specificAProperty)
        {
            this.specificAProperty = specificAProperty;
        }
    }

    /**
     * Another simple test bean.
     */
    public static class BeanB
    {
        private long propertyLong;

        private int propertyInt;

        private String propertyString;

        private Date propertyDate;

        private boolean specificBProperty;

        public long getPropertyLong()
        {
            return propertyLong;
        }

        public void setPropertyLong(long propertyLong)
        {
            this.propertyLong = propertyLong;
        }

        public int getPropertyInt()
        {
            return propertyInt;
        }

        public void setPropertyInt(int propertyInt)
        {
            this.propertyInt = propertyInt;
        }

        public String getPropertyString()
        {
            return propertyString;
        }

        public void setPropertyString(String propertyString)
        {
            this.propertyString = propertyString;
        }

        public Date getPropertyDate()
        {
            return propertyDate;
        }

        public void setPropertyDate(Date propertyDate)
        {
            this.propertyDate = propertyDate;
        }

        public boolean isSpecificBProperty()
        {
            return specificBProperty;
        }

        public void setSpecificBProperty(boolean specificBProperty)
        {
            this.specificBProperty = specificBProperty;
        }
    }

    /**
     * A test bean class that has wrapper types as properties.
     */
    public static class BeanWrappers
    {
        private Integer propertyInt;

        private Long propertyLong;

        public Integer getPropertyInt()
        {
            return propertyInt;
        }

        public void setPropertyInt(Integer propertyInt)
        {
            this.propertyInt = propertyInt;
        }

        public Long getPropertyLong()
        {
            return propertyLong;
        }

        public void setPropertyLong(Long propertyLong)
        {
            this.propertyLong = propertyLong;
        }
    }

    /**
     * A test bean class which has properties of type BigInteger.
     */
    public static class BeanBigProperties
    {
        private BigInteger propertyLong;

        private BigInteger propertyInt;

        public BigInteger getPropertyLong()
        {
            return propertyLong;
        }

        public void setPropertyLong(BigInteger propertyLong)
        {
            this.propertyLong = propertyLong;
        }

        public BigInteger getPropertyInt()
        {
            return propertyInt;
        }

        public void setPropertyInt(BigInteger propertyInt)
        {
            this.propertyInt = propertyInt;
        }
    }
}
