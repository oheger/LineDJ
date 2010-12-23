package de.oliver_heger.mediastore.client.pages.detail;

import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;

import com.google.gwt.core.client.GWT;
import com.google.gwt.event.dom.client.ChangeEvent;
import com.google.gwt.event.dom.client.ClickEvent;
import com.google.gwt.uibinder.client.UiBinder;
import com.google.gwt.uibinder.client.UiField;
import com.google.gwt.uibinder.client.UiHandler;
import com.google.gwt.user.client.ui.Button;
import com.google.gwt.user.client.ui.Composite;
import com.google.gwt.user.client.ui.DialogBox;
import com.google.gwt.user.client.ui.Image;
import com.google.gwt.user.client.ui.ListBox;
import com.google.gwt.user.client.ui.TextBox;
import com.google.gwt.user.client.ui.Widget;

import de.oliver_heger.mediastore.shared.model.HasSynonyms;
import de.oliver_heger.mediastore.shared.search.MediaSearchParameters;

/**
 * <p>
 * A component for editing synonyms of an entity.
 * </p>
 * <p>
 * This component provides a button which can be clicked by the user to open the
 * actual synonym editor. On clicking the button a dialog box opens. In this
 * dialog box existing synonyms can be removed or new ones can be added by
 * searching for entities of the same type.
 * </p>
 *
 * @author Oliver Heger
 * @version $Id: $
 */
public class SynonymEditor extends Composite implements SynonymSearchResultView
{
    /** The UI binder. */
    private static SynonymEditorUiBinder uiBinder = GWT
            .create(SynonymEditorUiBinder.class);

    /** The actual edit dialog. */
    @UiField
    DialogBox editDlg;

    /** The list box with the existing synonyms. */
    @UiField
    ListBox lstExistingSyns;

    /** The list box with the synonyms that have been removed. */
    @UiField
    ListBox lstRemovedSyns;

    /** The button for removing an existing synonym. */
    @UiField
    Button btnRemoveExisting;

    /** The button for adding an existing synonym. */
    @UiField
    Button btnAddExisting;

    /** The list box with the synonym search results. */
    @UiField
    ListBox lstSearchSyns;

    /** The list box with the synonym search results that have been added. */
    @UiField
    ListBox lstNewSyns;

    /** The button for removing an search result synonym. */
    @UiField
    Button btnRemoveSearchSyn;

    /** The button for adding a search result synonym. */
    @UiField
    Button btnAddSearchSyn;

    /** The save button. */
    @UiField
    Button btnSave;

    /** The cancel button. */
    @UiField
    Button btnCancel;

    /** The text field for a synonym search. */
    @UiField
    TextBox txtSearch;

    /** The progress indicator image. */
    @UiField
    Image progressIndicator;

    /**
     * A map for maintaining IDs of synonyms obtained through a search
     * operation.
     */
    private final Map<String, Object> searchSynIDs;

    /** A map for managing newly added synonyms. */
    private final Map<String, Object> newSynIDs;

    /** The synonym query handler. */
    private SynonymQueryHandler synonymQueryHandler;

    /** The object to be notified when editing is complete. */
    private SynonymEditResultsProcessor resultsProcessor;

    /** A counter for generating values for client search parameters. */
    private long searchParameterCounter;

    /**
     * Creates a new instance of {@code SynonymEditor}.
     */
    public SynonymEditor()
    {
        initWidget(uiBinder.createAndBindUi(this));

        searchSynIDs = new HashMap<String, Object>();
        newSynIDs = new HashMap<String, Object>();
    }

    /**
     * Returns the handler for synonym searches.
     *
     * @return the {@link SynonymQueryHandler}
     */
    public SynonymQueryHandler getSynonymQueryHandler()
    {
        return synonymQueryHandler;
    }

    /**
     * Sets the handler for synonym searches. This object must be set before
     * this editor can be used.
     *
     * @param synonymQueryHandler the {@link SynonymQueryHandler}
     */
    public void setSynonymQueryHandler(SynonymQueryHandler synonymQueryHandler)
    {
        this.synonymQueryHandler = synonymQueryHandler;
    }

    /**
     * Returns the object for processing the results of the synonym edit
     * operation.
     *
     * @return the {@link SynonymEditResultsProcessor}
     */
    public SynonymEditResultsProcessor getResultsProcessor()
    {
        return resultsProcessor;
    }

    /**
     * Sets the object which processes the results of the synonym edit
     * operation. This object is invoked when the user hits the cancel button
     * and the synonyms have been changed.
     *
     * @param resultsProcessor the {@link SynonymEditResultsProcessor}
     */
    public void setResultsProcessor(SynonymEditResultsProcessor resultsProcessor)
    {
        this.resultsProcessor = resultsProcessor;
    }

    /**
     * Starts the synonym editor on the specified entity. This method is the
     * main entry point in the editor.
     *
     * @param entity the entity whose synonyms are to be edited
     */
    public void edit(HasSynonyms entity)
    {
        lstExistingSyns.clear();
        for (String syn : entity.getSynonyms())
        {
            lstExistingSyns.addItem(syn);
        }
        lstNewSyns.clear();
        lstRemovedSyns.clear();
        lstSearchSyns.clear();
        clearSelection(lstExistingSyns);
        newSynIDs.clear();

        btnAddExisting.setEnabled(false);
        btnAddSearchSyn.setEnabled(false);
        btnRemoveExisting.setEnabled(false);
        btnRemoveSearchSyn.setEnabled(false);

        editDlg.center();
    }

    /**
     * Tests whether the specified search parameters object fits to the current
     * search. This implementation uses a counter which is increased for each
     * search operation. The current value of the counter is passed to the
     * server as client parameter. So when results become available it can be
     * checked whether in the meantime a new search has started.
     *
     * @param params the search parameters
     */
    @Override
    public boolean acceptResults(MediaSearchParameters params)
    {
        return Long.valueOf(searchParameterCounter).equals(
                params.getClientParameter());
    }

    /**
     * Adds new synonym search results to this object. They are added to the
     * corresponding list box.
     */
    @Override
    public void addResults(Map<Object, String> synResults, boolean moreResults)
    {
        for (Map.Entry<Object, String> e : synResults.entrySet())
        {
            String value = String.valueOf(e.getKey());
            lstSearchSyns.addItem(e.getValue(), value);
            searchSynIDs.put(value, e.getKey());
        }

        if (!moreResults)
        {
            progressIndicator.setVisible(false);
        }
    }

    /**
     * An error occurred while searching for synonyms. We do not implement any
     * sophisticated error handling here. We just consider the search as
     * complete.
     */
    @Override
    public void onFailure(Throwable ex)
    {
        Logger logger = Logger.getLogger(getClass().getName());
        logger.log(Level.SEVERE, "Error when searching for synonyms!", ex);

        progressIndicator.setVisible(false);
    }

    /**
     * Starts a search for synonyms. If a search text has been entered, the
     * query handler is invoked for handling the search.
     */
    void startSynonymSearch()
    {
        lstSearchSyns.clear();
        searchSynIDs.clear();

        if (txtSearch.getText().length() > 0)
        {
            progressIndicator.setVisible(true);
            getSynonymQueryHandler().querySynonyms(this, txtSearch.getText(),
                    nextSearchClientParameter());
        }
        else
        {
            progressIndicator.setVisible(false);
        }
    }

    /**
     * The list box with existing synonyms has been changed.
     *
     * @param event the change event
     */
    @UiHandler("lstExistingSyns")
    void listExistingSynonymsSelectionChanged(ChangeEvent event)
    {
        updateButtonState(lstExistingSyns, btnRemoveExisting);
    }

    /**
     * The list box with removed existing synonyms has been changed.
     *
     * @param event the change event
     */
    @UiHandler("lstRemovedSyns")
    void listRemovedSynonymsSelectionChanged(ChangeEvent event)
    {
        updateButtonState(lstRemovedSyns, btnAddExisting);
    }

    /**
     * The list box with synonym search results has been changed.
     *
     * @param event the change event
     */
    @UiHandler("lstSearchSyns")
    void listSearchSynsSelectionChanged(ChangeEvent event)
    {
        updateButtonState(lstSearchSyns, btnAddSearchSyn);
    }

    /**
     * The list box with newly added synonyms has been changed.
     *
     * @param event the change event
     */
    @UiHandler("lstNewSyns")
    void listNewSynsSelectionChanged(ChangeEvent event)
    {
        updateButtonState(lstNewSyns, btnRemoveSearchSyn);
    }

    /**
     * The button for removing an existing synonym was clicked.
     *
     * @param event the click event
     */
    @UiHandler("btnRemoveExisting")
    void onBtnRemoveExistingClick(ClickEvent event)
    {
        transferItems(lstExistingSyns, lstRemovedSyns, null);
    }

    /**
     * The button for adding an existing synonym (that has been removed before)
     * was clicked.
     *
     * @param event the click event
     */
    @UiHandler("btnAddExisting")
    void onBtnAddExistingClick(ClickEvent event)
    {
        transferItems(lstRemovedSyns, lstExistingSyns, null);
    }

    /**
     * The button for removing a new synonym was clicked.
     *
     * @param event the click event
     */
    @UiHandler("btnRemoveSearchSyn")
    void onBtnRemoveSearchSynClick(ClickEvent event)
    {
        Set<String> removedItems = new HashSet<String>();
        transferItems(lstNewSyns, null, removedItems);

        // also remove elements from the map with new synonyms
        newSynIDs.keySet().removeAll(removedItems);
    }

    /**
     * The button for adding a synonym from the search results was clicked.
     * Note: we cannot use the generic transferItems() method here because we
     * have to update the map with newly added synonyms, too.
     *
     * @param event the click event
     */
    @UiHandler("btnAddSearchSyn")
    void onBtnAddSearchSynClick(ClickEvent event)
    {
        int insertPos = lstNewSyns.getItemCount();

        for (int i = lstSearchSyns.getItemCount() - 1; i >= 0; i--)
        {
            if (lstSearchSyns.isItemSelected(i))
            {
                String itemValue = lstSearchSyns.getValue(i);
                if (!newSynIDs.containsKey(itemValue))
                {
                    String itemText = lstSearchSyns.getItemText(i);
                    lstNewSyns.insertItem(itemText, itemValue, insertPos);
                    lstSearchSyns.removeItem(i);
                    newSynIDs.put(itemValue, searchSynIDs.get(itemValue));
                }
            }
        }
    }

    /**
     * The button for saving the dialog was clicked. If changes were made on the
     * synonyms, the registered {@link SynonymEditResultsProcessor} is called
     * before the dialog is closed.
     *
     * @param event the click event
     */
    @UiHandler("btnSave")
    void onBtnSaveClick(ClickEvent event)
    {
        if (!newSynIDs.isEmpty() || lstRemovedSyns.getItemCount() > 0)
        {
            Set<String> removedSyns = fetchRemovedSynonyms();
            Set<Object> newSyns = fetchNewSynonyms();
            getResultsProcessor().synonymsChanged(removedSyns, newSyns);
        }

        closeDialog();
    }

    /**
     * The cancel button was clicked. This causes the dialog to be closed.
     *
     * @param event the click event
     */
    void onBtnCancelClick(ClickEvent event)
    {
        closeDialog();
    }

    /**
     * Closes the dialog window.
     */
    private void closeDialog()
    {
        editDlg.hide();
    }

    /**
     * Updates the state of a button based on the selection of the given list
     * box.
     *
     * @param lst the list box
     * @param btn the button
     */
    private void updateButtonState(ListBox lst, Button btn)
    {
        btn.setEnabled(hasSelection(lst));
    }

    /**
     * Transfers all selected items from the source list box to the destination
     * list box.
     *
     * @param lstSource the source list box
     * @param lstDest the destination list box (can be <b>null</b>, then
     *        selected items will simply be removed)
     * @param removedItems an optional set in which the values of the removed
     *        items are stored
     */
    private void transferItems(ListBox lstSource, ListBox lstDest,
            Set<String> removedItems)
    {
        int insertPos = -1;

        for (int i = lstSource.getItemCount() - 1; i >= 0; i--)
        {
            if (lstSource.isItemSelected(i))
            {
                String itemValue = lstSource.getValue(i);

                if (lstDest != null)
                {
                    // ensure that the order of items is maintained
                    if (insertPos < 0)
                    {
                        insertPos = lstDest.getItemCount();
                    }
                    String itemText = lstSource.getItemText(i);
                    lstDest.insertItem(itemText, itemValue, insertPos);
                }
                lstSource.removeItem(i);

                if (removedItems != null)
                {
                    removedItems.add(itemValue);
                }
            }
        }
    }

    /**
     * Returns the next client parameter for a call to the search service.
     *
     * @return the next client search parameter
     */
    private Long nextSearchClientParameter()
    {
        return Long.valueOf(++searchParameterCounter);
    }

    /**
     * Obtains a set with the IDs of the synonyms which have been added.
     *
     * @return a set with the added synonyms
     */
    private Set<Object> fetchNewSynonyms()
    {
        Set<Object> newSyns = new HashSet<Object>();
        newSyns.addAll(newSynIDs.values());
        return newSyns;
    }

    /**
     * Obtains a set with the names of the synonyms which have been removed.
     *
     * @return a set with the removed synonyms
     */
    private Set<String> fetchRemovedSynonyms()
    {
        Set<String> removedSyns = new LinkedHashSet<String>();
        for (int i = 0; i < lstRemovedSyns.getItemCount(); i++)
        {
            removedSyns.add(lstRemovedSyns.getItemText(i));
        }
        return removedSyns;
    }

    /**
     * Helper method for clearing the selection of a list box. It seems that
     * newly added items are always selected. This method sets the selected
     * flags for all list items to <b>false</b>.
     *
     * @param lst the list box
     */
    private static void clearSelection(ListBox lst)
    {
        for (int i = 0; i < lst.getItemCount(); i++)
        {
            lst.setItemSelected(i, false);
        }
    }

    /**
     * Tests whether the specified list box has selected elements.
     *
     * @param lst the list box
     * @return <b>true</b> if there is at least one selected item; <b>false</b>
     *         otherwise
     */
    private static boolean hasSelection(ListBox lst)
    {
        for (int i = 0; i < lst.getItemCount(); i++)
        {
            if (lst.isItemSelected(i))
            {
                return true;
            }
        }

        return false;
    }

    /**
     * The specialized binder interface.
     */
    interface SynonymEditorUiBinder extends UiBinder<Widget, SynonymEditor>
    {
    }
}
