package de.oliver_heger.mediastore.client;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

import com.google.gwt.user.client.ui.Grid;

import de.oliver_heger.mediastore.client.SortableTableHeader.SortDirection;
import de.oliver_heger.mediastore.shared.InverseComparator;

/**
 * <p>
 * An abstract base class for model classes for {@code Grid} controls.
 * </p>
 * <p>
 * This base class provides functionality for populating and managing a
 * {@code Grid} widget. It works together with {@link SortableTableHeader} to
 * provide support for sortable tables. When constructing an instance the
 * {@code Grid} control to be managed has to be passed. The first row of this
 * control must contain only widgets of type {@link SortableTableHeader}. The
 * object registers itself as {@code TableHeaderListener} at these objects.
 * </p>
 * <p>
 * When new data becomes available the {@link #initData(List)} method can be
 * called. It resizes the grid so that the data fits in. If a sort order is
 * already specified, the list is sorted. Then the data is written into the
 * grid. For this to work, concrete subclasses have to define the
 * {@link #writeCell(int, int, Object)} method. Here the correct property for
 * the specified column has to be extracted from the passed in data object and
 * to be written into the grid.
 * </p>
 * <p>
 * When the user clicks on one of the table header components the sort order is
 * changed. {@code GridTableModel} is notified about this change. It reacts by
 * calling {@link #fetchComparator(String)} for the property associated with the
 * column clicked. If this method returns a non <b>null</b> comparator, the data
 * list is resorted, and the grid is filled again.
 * </p>
 *
 * @author Oliver Heger
 * @version $Id: $
 */
public abstract class GridTableModel<T> implements
        SortableTableHeader.TableHeaderListener
{
    /** Stores the managed grid. */
    private final Grid grid;

    /** The list with the data of this model. */
    private List<T> modelData;

    /** An array with the header components to be managed. */
    private SortableTableHeader[] headers;

    /** The header which defines the current sort order. */
    private SortableTableHeader currentSortHeader;

    /**
     * Creates a new instance of {@code GridTableModel} and initializes it with
     * the underlying grid.
     *
     * @param wrappedGrid the {@code Grid} to be managed by this instance (must
     *        not be <b>null</b>)
     * @throws NullPointerException if the {@code Grid} is <b>null</b>
     */
    public GridTableModel(Grid wrappedGrid)
    {
        if (wrappedGrid == null)
        {
            throw new NullPointerException("Grid must not be null!");
        }

        grid = wrappedGrid;
    }

    /**
     * Returns the {@code Grid} managed by this object.
     *
     * @return the underlying {@code Grid} component
     */
    public Grid getGrid()
    {
        return grid;
    }

    /**
     * Sets the data to be filled into the underlying grid. If the model already
     * contains data, it is replaced by the new one.
     *
     * @param data the list with data to be displayed by the model (must not be
     *        <b>null</b>)
     * @throws NullPointerException if the list is <b>null</b>
     */
    public void initData(List<T> data)
    {
        if (data == null)
        {
            throw new NullPointerException("List with data must not be null!");
        }

        modelData = new ArrayList<T>(data);
        sortModelData(modelData, getCurrentSortHeader());

        getGrid().resizeRows(data.size() + 1);
        populateGrid();
    }

    /**
     * Notifies this object that one of the {@link SortableTableHeader} objects
     * in the grid has been clicked by the user. This will cause the data to be
     * sorted according to the updated sort order.
     *
     * @param header the header component that has been clicked
     */
    @Override
    public void onSortableTableHeaderClick(SortableTableHeader header)
    {
        if (modelData == null)
        {
            throw new IllegalStateException("No model data available!");
        }

        resetSortDirection(header);
        currentSortHeader = header;

        sortModelData(modelData, header);
        populateGrid();
    }

    /**
     * Returns the number of columns of this model.
     *
     * @return the number of columns
     */
    private int getColumnCount()
    {
        return getHeaders().length;
    }

    /**
     * Sorts the table model according to the order defined by the given header.
     * This method is called whenever data needs to be sorted. The passed in
     * sort header may be <b>null</b> if no sort order is defined yet. In this
     * case, this implementation does nothing. Otherwise, it calls
     * {@link #fetchComparator(String)} with the name of the property associated
     * with the header. If here a {@code Comparator} is returned, the list is
     * sorted.
     *
     * @param data the list to be sorted
     * @param sortHeader the header component determining the sort order (may be
     *        <b>null</b>)
     */
    protected void sortModelData(List<T> data, SortableTableHeader sortHeader)
    {
        if (sortHeader != null)
        {
            Comparator<T> comp = getComparatorForHeader(sortHeader);
            if (comp != null)
            {
                Collections.sort(data, comp);
            }
        }
    }

    /**
     * Obtains a comparator for applying the sort order determined by the passed
     * in header component. This implementation delegates to
     * {@link #fetchComparator(String)} in order to retrieve the comparator
     * associated with the property represented by the column header. If the
     * sort direction is descending, an inverse comparator is created.
     *
     * @param sortHeader the current sort header
     * @return the comparator to be used for this header
     */
    protected Comparator<T> getComparatorForHeader(
            SortableTableHeader sortHeader)
    {
        Comparator<T> comp = fetchComparator(sortHeader.getPropertyName());

        if (comp != null)
        {
            if (sortHeader.getSortDirection() == SortDirection.SORT_DESCENDING)
            {
                comp = new InverseComparator<T>(comp);
            }
        }
        return comp;
    }

    /**
     * Writes data into a cell of the underlying grid. This method is called for
     * each cell when populating the grid.
     *
     * @param row the row index
     * @param col the column index
     * @param obj the data object for this row
     */
    protected abstract void writeCell(int row, int col, T obj);

    /**
     * Returns the {@code Comparator} for the specified property. This
     * comparator is then used to sort the data of the model. An implementation
     * may return <b>null</b>; in this case no sort is performed.
     *
     * @param property the name of the property
     * @return the {@code Comparator} which corresponds to this property
     */
    protected abstract Comparator<T> fetchComparator(String property);

    /**
     * Returns an array with the table header components. This array is created
     * on first access.
     *
     * @return the array with the header components
     */
    private SortableTableHeader[] getHeaders()
    {
        if (headers == null)
        {
            initHeaders();
        }

        return headers;
    }

    /**
     * Initializes the array with the header components.
     */
    private void initHeaders()
    {
        headers = new SortableTableHeader[grid.getColumnCount()];
        for (int i = 0; i < headers.length; i++)
        {
            headers[i] = (SortableTableHeader) grid.getWidget(0, i);
            headers[i].setTableHeaderListener(this);
        }
    }

    /**
     * Returns the header which determines the current sort order. Result may be
     * <b>null</b> if there is none.
     *
     * @return the header for the current sort column
     */
    private SortableTableHeader getCurrentSortHeader()
    {
        if (currentSortHeader == null)
        {
            initSortHeader();
        }

        return currentSortHeader;
    }

    /**
     * Initializes the header that determines the sort order. This method is
     * called when the underlying grid is populated for the very first time. It
     * checks whether any of the headers has the initial sort property set. If
     * this is the case, it becomes the current sort header.
     */
    private void initSortHeader()
    {
        for (SortableTableHeader header : getHeaders())
        {
            if (header.applyInitialOrder())
            {
                currentSortHeader = header;
                break;
            }
        }
    }

    /**
     * Resets the sort direction of all managed header components. This method
     * is called when the user clicked on one of the headers. Then this header
     * becomes the new current sort header. All other headers have to be reset.
     *
     * @param header the new current sort header
     */
    private void resetSortDirection(SortableTableHeader header)
    {
        for (SortableTableHeader h : getHeaders())
        {
            if (h != header)
            {
                h.setSortDirection(SortDirection.SORT_NONE);
            }
        }
    }

    /**
     * Fills the grid with the data of this model.
     */
    private void populateGrid()
    {
        int row = 1;
        for (T obj : modelData)
        {
            for (int col = 0; col < getColumnCount(); col++)
            {
                writeCell(row, col, obj);
            }
            row++;
        }
    }
}
