package de.oliver_heger.mediastore.client.pages.detail;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Map;

import com.google.gwt.junit.client.GWTTestCase;
import com.google.gwt.user.cellview.client.Column;
import com.google.gwt.user.cellview.client.ColumnSortEvent;

import de.oliver_heger.mediastore.client.pages.MockPageManager;

/**
 * An abstract base class for test classes for detail table implementations.
 * This base class provides some common functionality which can be used by
 * concrete test classes.
 *
 * @author Oliver Heger
 * @version $Id: $
 */
public abstract class AbstractTestDetailsTable extends GWTTestCase
{
    @Override
    public String getModuleName()
    {
        return "de.oliver_heger.mediastore.RemoteMediaStore";
    }

    /**
     * Checks whether a mapping for the specified comparators is created by the
     * given test table.
     *
     * @param table the table to be tested
     * @param comps the expected comparators
     * @param <T> the type of the table
     */
    protected static <T> void checkComparatorMapping(
            AbstractDetailsTable<T> table, Object... comps)
    {
        ListHandlerTestImpl<T> listHandler = new ListHandlerTestImpl<T>();
        table.getTableInitializer().initializeTable(table.cellTable,
                listHandler, new MockPageManager());
        Map<Column<T, ?>, Comparator<T>> map =
                listHandler.getColumnComparators();
        assertEquals("Wrong number of comparators", comps.length, map.size());
        for (int i = 0; i < comps.length; i++)
        {
            assertEquals("Wrong comparator for column " + i, comps[i],
                    map.get(table.cellTable.getColumn(i)));
        }
    }

    /**
     * A test list handler implementation for testing whether the comparators
     * for a cell table have been initialized correctly.
     *
     * @param <T> the type of data objects to deal with
     */
    private static class ListHandlerTestImpl<T> extends
            ColumnSortEvent.ListHandler<T>
    {
        /** A map for storing comparators for columns. */
        private final Map<Column<T, ?>, Comparator<T>> columnComparators;

        public ListHandlerTestImpl()
        {
            super(new ArrayList<T>());
            columnComparators = new HashMap<Column<T, ?>, Comparator<T>>();
        }

        /**
         * Returns the map with the columns and the associated comparators.
         *
         * @return the column to comparator mapping
         */
        public Map<Column<T, ?>, Comparator<T>> getColumnComparators()
        {
            return columnComparators;
        }

        /**
         * {@inheritDoc} Just adds the column mapping.
         */
        @Override
        public void setComparator(Column<T, ?> column, Comparator<T> comparator)
        {
            assertTrue("Column not sortable", column.isSortable());
            columnComparators.put(column, comparator);
        }
    }
}
