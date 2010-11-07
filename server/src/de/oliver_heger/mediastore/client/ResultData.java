package de.oliver_heger.mediastore.client;

/**
 * <p>
 * Definition of an interface providing access to tabular data returned by a
 * service call.
 * </p>
 * <p>
 * Objects implementing this interface are produced by server calls. They
 * contain the result data of a query. The client uses this interface to extract
 * the data and populate its UI controls.
 * </p>
 *
 * @author Oliver Heger
 * @version $Id: $
 */
public interface ResultData
{
    /**
     * Returns the number of columns in the result set.
     *
     * @return the number of columns
     */
    int getColumnCount();

    /**
     * Returns the number of rows in the result set.
     *
     * @return the number of rows
     */
    int getRowCount();

    /**
     * Returns the name of the column with the given index.
     *
     * @param idx the column index (0-based)
     * @return the name of the column with this index
     */
    String getColumnName(int idx);

    /**
     * Returns the value of the specified cell.
     *
     * @param row the row index (0-based)
     * @param col the column index (0-based)
     * @return the value of the specified cell
     */
    String getValueAt(int row, int col);
}
