package de.oliver_heger.mediastore.client.pages.detail;

import java.util.Collection;

import com.google.gwt.core.client.GWT;
import com.google.gwt.uibinder.client.UiBinder;
import com.google.gwt.uibinder.client.UiField;
import com.google.gwt.user.cellview.client.CellTable;
import com.google.gwt.user.cellview.client.ColumnSortEvent;
import com.google.gwt.user.cellview.client.ColumnSortEvent.ListHandler;
import com.google.gwt.user.cellview.client.SimplePager;
import com.google.gwt.user.cellview.client.SimplePager.TextLocation;
import com.google.gwt.user.client.ui.Composite;
import com.google.gwt.user.client.ui.Widget;
import com.google.gwt.view.client.ListDataProvider;
import com.google.gwt.view.client.ProvidesKey;

import de.oliver_heger.mediastore.client.pageman.PageManager;

/**
 * <p>
 * An abstract base class which wraps a cell table widget for displaying static
 * information about media data.
 * </p>
 * <p>
 * This base class already provides functionality for the interaction with a
 * cell table widget. The widget is created and associated with a pager
 * component. Concrete sub classes mainly have to provide helper objects needed
 * for the correct configuration of the widget.
 * </p>
 *
 * @author Oliver Heger
 * @version $Id: $
 * @param <T> the type of data objects displayed by the table widget
 */
public abstract class AbstractDetailsTable<T> extends Composite
{
    /** The binder instance. */
    private static DetailsTableUiBinder uiBinder = GWT
            .create(DetailsTableUiBinder.class);

    /** The main table widget. */
    @UiField(provided = true)
    final CellTable<T> cellTable;

    /** The pager providing paging functionality. */
    @UiField(provided = true)
    final SimplePager pager;

    /** The table initializer. */
    private final TableInitializer<T> tableInitializer;

    /** Stores the list data provider. */
    private final ListDataProvider<T> dataProvider;

    /**
     * Creates a new instance of {@code AbstractDetailsTable} and initializes
     * the UI components wrapped by this object. The {@code ListDataProvider} to
     * be used can be passed; if this object is <b>null</b>, a default instance
     * is created.
     *
     * @param keyProvider the {@code ProvidesKey} implementation
     * @param initializer the object for initializing the table widget
     * @param dataProv the (optional) data provider for the table widget
     */
    protected AbstractDetailsTable(ProvidesKey<T> keyProvider,
            TableInitializer<T> initializer, ListDataProvider<T> dataProv)
    {
        cellTable = initCellTable(keyProvider);
        dataProvider = initDataProvider(dataProv);
        pager = initPager();
        tableInitializer = initializer;
        initWidget(uiBinder.createAndBindUi(this));
    }

    /**
     * Returns the {@code TableInitializer} used by this table.
     *
     * @return the {@code TableInitializer}
     */
    public TableInitializer<T> getTableInitializer()
    {
        return tableInitializer;
    }

    /**
     * Initializes this component. This method must be called once before the
     * table can be used.
     *
     * @param pm the{@code PageManager}
     */
    public void initialize(PageManager pm)
    {
        ListHandler<T> listHandler = createSortHandler();
        cellTable.addColumnSortHandler(listHandler);
        tableInitializer.initializeTable(cellTable, listHandler , pm);
    }

    /**
     * Sets the data to be displayed in the table widget. The content of the
     * passed in list becomes the content of the table. The list can be
     * <b>null</b>, then the table is cleared.
     *
     * @param data the data to be displayed by the table
     */
    public void setData(Collection<? extends T> data)
    {
        getDataProvider().getList().clear();
        if (data != null)
        {
            getDataProvider().getList().addAll(data);
        }
    }

    /**
     * Returns the list data provider used by this table.
     *
     * @return the currently used list data provider
     */
    protected ListDataProvider<T> getDataProvider()
    {
        return dataProvider;
    }

    /**
     * Creates the handler responsible for sorting support.
     *
     * @return the list handler
     */
    protected ColumnSortEvent.ListHandler<T> createSortHandler()
    {
        return new ColumnSortEvent.ListHandler<T>(getDataProvider().getList());
    }

    /**
     * Creates and initializes the cell table component.
     *
     * @param keyProvider the key provider
     * @return the table component
     */
    private CellTable<T> initCellTable(ProvidesKey<T> keyProvider)
    {
        CellTable<T> tab = new CellTable<T>(keyProvider);
        tab.setWidth("100%", true);
        return tab;
    }

    /**
     * Initializes the pager component. The table component must have been
     * created before.
     */
    private SimplePager initPager()
    {
        SimplePager.Resources pagerResources =
                GWT.create(SimplePager.Resources.class);
        SimplePager sp =
                new SimplePager(TextLocation.CENTER, pagerResources, false, 0,
                        true);
        sp.setDisplay(cellTable);
        return sp;
    }

    /**
     * Initializes the list data provider. If a provider has been passed, it is
     * used directly. Otherwise, a new default list data provider is created.
     *
     * @param dataProv the provider passed to the constructor
     * @return the list data provider to be used
     */
    private ListDataProvider<T> initDataProvider(ListDataProvider<T> dataProv)
    {
        ListDataProvider<T> provider =
                (dataProv != null) ? dataProv : new ListDataProvider<T>();
        provider.addDataDisplay(cellTable);
        return provider;
    }

    /**
     * The binder interface used by this component.
     */
    interface DetailsTableUiBinder extends
            UiBinder<Widget, AbstractDetailsTable<?>>
    {
    }
}
