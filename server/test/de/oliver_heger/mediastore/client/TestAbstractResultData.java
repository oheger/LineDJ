package de.oliver_heger.mediastore.client;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import java.util.ArrayList;
import java.util.List;

import org.junit.Before;
import org.junit.Test;

/**
 * Test class for {@code AbstractResultData}.
 *
 * @author Oliver Heger
 * @version $Id: $
 */
public class TestAbstractResultData
{
    /** An array with the test columns. */
    private static final String[] COLUMNS = {
            "firstName", "lastName", "birthDate", "position", "salary"
    };

    /**
     * Constant for the separator used by the test implementation of
     * getPropertyForColumn().
     */
    private static final String SEPARATOR = "/";

    /** Constant for the prefix for generated ID values. */
    private static final String ID_PREFIX = "ID: ";

    /** Constant for the number of rows of test data. */
    private static final int DATA_SIZE = 16;

    /** The data object to be tested. */
    private AbstractResultDataTestImpl resultData;

    @Before
    public void setUp() throws Exception
    {
        resultData = new AbstractResultDataTestImpl(COLUMNS, createTestData());
    }

    /**
     * Creates a list with test data objects. This implementation just creates
     * integer objects with increasing values.
     *
     * @return the list with test data
     */
    private static List<Object> createTestData()
    {
        List<Object> data = new ArrayList<Object>(DATA_SIZE);
        for (int i = 0; i < DATA_SIZE; i++)
        {
            data.add(Integer.valueOf(i));
        }
        return data;
    }

    /**
     * Tests whether the correct number of columns is returned.
     */
    @Test
    public void testGetColumnCount()
    {
        assertEquals("Wrong number of columns", COLUMNS.length,
                resultData.getColumnCount());
    }

    /**
     * Tests whether the correct names for columns can be queried.
     */
    @Test
    public void testGetColumnName()
    {
        for (int i = 0; i < COLUMNS.length; i++)
        {
            assertEquals("Wrong column name at " + i, COLUMNS[i],
                    resultData.getColumnName(i));
        }
    }

    /**
     * Tests whether the correct number of rows is returned.
     */
    @Test
    public void testGetRowCount()
    {
        assertEquals("Wrong number of rows", DATA_SIZE,
                resultData.getRowCount());
    }

    /**
     * Tests whether cell values can be queried.
     */
    @Test
    public void testGetValueAt()
    {
        for (int i = 0; i < DATA_SIZE; i++)
        {
            for (int j = 0; j < COLUMNS.length; j++)
            {
                String exp = String.valueOf(i) + SEPARATOR + j;
                assertEquals(String.format("Wrong value at (%d, %d)", i, j),
                        exp, resultData.getValueAt(i, j));
            }
        }
    }

    /**
     * Tests getValueAt() for an invalid row index.
     */
    @Test(expected = IndexOutOfBoundsException.class)
    public void testGetValueAtInvalidRowIndex()
    {
        resultData.getValueAt(DATA_SIZE, 0);
    }

    /**
     * Tests getValueAt() for an invalid column index.
     */
    @Test(expected = IndexOutOfBoundsException.class)
    public void testGetValueAtColIndexTooBig()
    {
        resultData.getValueAt(0, COLUMNS.length);
    }

    /**
     * Tests getValueAt() for a negative column index.
     */
    @Test(expected = IndexOutOfBoundsException.class)
    public void testGetValueAtColIndexNegative()
    {
        resultData.getValueAt(0, -1);
    }

    /**
     * Tests whether convertToString() can deal with null values.
     */
    @Test
    public void testConvertToStringNull()
    {
        assertNull("Wrong value", resultData.convertToString(null));
    }

    /**
     * Tests whether the IDs of the rows can be obtained.
     */
    @Test
    public void testGetID()
    {
        for (int i = 0; i < DATA_SIZE; i++)
        {
            String exp = ID_PREFIX + i;
            assertEquals("Wrong ID at " + i, exp, resultData.getID(i));
        }
    }

    /**
     * Tests getID() if an invalid row index is passed in.
     */
    @Test(expected = IndexOutOfBoundsException.class)
    public void testGetIDInvalidRow()
    {
        resultData.getID(-1);
    }

    /**
     * A concrete test implementation of AbstractResultData.
     */
    private static class AbstractResultDataTestImpl extends
            AbstractResultData<Object>
    {
        public AbstractResultDataTestImpl(String[] colNames, List<Object> data)
        {
            super(colNames, data);
        }

        /**
         * {@inheritDoc} Just returns a string created from the parameters. This
         * allows testing whether this method is called in the expected context.
         */
        @Override
        protected Object getPropertyForColumn(Object data, int col)
        {
            return data + SEPARATOR + col;
        }

        /**
         * {@inheritDoc} Returns a string consisting of the ID prefix and the
         * data object passed in.
         */
        @Override
        protected Object getIDOfObject(Object data)
        {
            return ID_PREFIX + data;
        }
    }
}
