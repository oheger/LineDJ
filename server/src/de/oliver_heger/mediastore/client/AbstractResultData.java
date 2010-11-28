package de.oliver_heger.mediastore.client;

import java.util.List;

/**
 * <p>
 * An abstract base implementation of the {@link ResultData} interface.
 * </p>
 * <p>
 * This implementation is backed by a list with data objects of type <em>T</em>.
 * <p>
 * A part of the methods required by the {@link ResultData} interface is already
 * implemented. Concrete subclasses mainly have to deal with the mapping between
 * column indices and properties of the data objects.
 * </p>
 *
 * @author Oliver Heger
 * @version $Id: $
 * @param <T> the type of data objects contained in this {@link ResultData}
 *        implementation
 */
abstract class AbstractResultData<T> implements ResultData
{
    /** An array with the column names. */
    private String[] columnNames;

    /** The list with data objects. */
    private List<T> dataList;

    /**
     * Standard constructor. Needed for serialization.
     */
    protected AbstractResultData()
    {
    }

    /**
     * Creates a new instance of {@code AbstractResultData} and initializes it.
     * Note: Because this class is intended to be used internally only, no
     * defensive copies of parameters are created.
     *
     * @param colNames an array with the column names
     * @param data the data list
     */
    protected AbstractResultData(String[] colNames, List<T> data)
    {
        columnNames = colNames;
        dataList = data;
    }

    /**
     * Returns the number of columns. This implementation obtains the column
     * count from the columns array passed to the constructor.
     *
     * @return the number of columns
     */
    @Override
    public int getColumnCount()
    {
        return columnNames.length;
    }

    /**
     * Returns the number of rows. This implementation returns the size of the
     * list with data objects.
     *
     * @return the number of rows
     */
    @Override
    public int getRowCount()
    {
        return dataList.size();
    }

    /**
     * Returns the name of the column with the given index. This implementation
     * returns the element with the corresponding index from the array of column
     * names passed to the constructor.
     *
     * @param idx the column index
     * @return the name of the column with the given index
     */
    @Override
    public String getColumnName(int idx)
    {
        return columnNames[idx];
    }

    /**
     * Returns the value at the specified position. This implementation obtains
     * the data object at the given row. Then it delegates to
     * {@link #getPropertyForColumn(Object, int)} to obtain the property which
     * corresponds to the column index.
     *
     * @param row the row index
     * @param col the column index
     * @return the value at this position
     */
    @Override
    public String getValueAt(int row, int col)
    {
        if (col < 0 || col >= getColumnCount())
        {
            throw new IndexOutOfBoundsException("Invalid column index: " + col);
        }

        T data = getDataAt(row);
        return convertToString(getPropertyForColumn(data, col));
    }

    /**
     * Returns the ID of the object in the specified row. This implementation
     * obtains the data object at the given row and delegates to
     * {@link #getIDOfObject(Object)} to extract the actual ID value.
     *
     * @param row the row index
     * @return the ID of the object in this row
     */
    @Override
    public Object getID(int row)
    {
        return getIDOfObject(getDataAt(row));
    }

    /**
     * Returns the data object at the specified row.
     *
     * @param row the row
     * @return the data object at this row
     */
    protected T getDataAt(int row)
    {
        T data = dataList.get(row);
        return data;
    }

    /**
     * Converts the specified value to a string. This method is called by
     * {@link #getValueAt(int, int)} after the property of the corresponding
     * data object has been retrieved. This implementation checks whether the
     * passed in value is <b>null</b>. If so, result is also <b>null</b>.
     * Otherwise, the passed in object's {@code toString()} method is called.
     *
     * @param value the value
     * @return the value transformed to a string
     */
    protected String convertToString(Object value)
    {
        return (value == null) ? null : value.toString();
    }

    /**
     * Returns the value of the data object property for the specified column
     * index. This method is called by the implementation of
     * {@link #getValueAt(int, int)}. A concrete implementation has to match the
     * column index to a corresponding property of the data object.
     *
     * @param data the data object
     * @param col the column index
     * @return the value of this property
     */
    protected abstract Object getPropertyForColumn(T data, int col);

    /**
     * Returns the ID of the specified data object. This method is called by the
     * implementation of {@link #getID(int)}. A concrete implementation has to
     * extract the correct ID value from the data object passed in.
     *
     * @param data the data object
     * @return the ID of this data object
     */
    protected abstract Object getIDOfObject(T data);
}
