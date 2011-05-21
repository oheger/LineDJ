package de.oliver_heger.mediastore.client.pages.detail;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.google.gwt.dom.client.Document;
import com.google.gwt.dom.client.NativeEvent;
import com.google.gwt.event.dom.client.DomEvent;
import com.google.gwt.event.dom.client.KeyCodes;
import com.google.gwt.junit.client.GWTTestCase;
import com.google.gwt.user.client.Timer;
import com.google.gwt.user.client.ui.ListBox;

import de.oliver_heger.mediastore.shared.SynonymUpdateData;
import de.oliver_heger.mediastore.shared.model.ArtistDetailInfo;
import de.oliver_heger.mediastore.shared.search.MediaSearchParameters;

/**
 * Test class for {@code SynonymEditor}.
 *
 * @author Oliver Heger
 * @version $Id: $
 */
public class GwtTestSynonymEditor extends GWTTestCase
{
    /** Constant for a synonym prefix. */
    private static final String SYN = "TestSynonym_";

    /** Constant for the default number of test synonyms. */
    private static final int SYN_COUNT = 4;

    @Override
    public String getModuleName()
    {
        return "de.oliver_heger.mediastore.RemoteMediaStore";
    }

    /**
     * Creates the given number of test synonyms.
     *
     * @param count the number of synonyms to create
     * @return a set with the test synonyms
     */
    private static Set<String> createSyns(int count)
    {
        Set<String> result = new LinkedHashSet<String>(createSynList(count));
        return result;
    }

    /**
     * Creates a list with a number of test synonyms.
     *
     * @param count the number of synonyms to create
     * @return the list with the test synonyms
     */
    private static List<String> createSynList(int count)
    {
        List<String> result = new ArrayList<String>(count);
        for (int i = 0; i < count; i++)
        {
            result.add(SYN + i);
        }
        return result;
    }

    /**
     * Creates a map with synonym search results from the given synonym list.
     *
     * @param syns the list with synonyms
     * @return the map
     */
    private static Map<Object, String> createSearchResultsMap(List<String> syns)
    {
        Map<Object, String> results = new HashMap<Object, String>();
        for (String syn : syns)
        {
            Integer id = Integer.parseInt(syn.substring(SYN.length()));
            results.put(id, syn);
        }
        return results;
    }

    /**
     * Obtains the number of selected items in the given list box.
     *
     * @param lst the list box
     * @return the number of selected items in this list
     */
    private static int getSelectionCount(ListBox lst)
    {
        int result = 0;
        for (int i = 0; i < lst.getItemCount(); i++)
        {
            if (lst.isItemSelected(i))
            {
                result++;
            }
        }
        return result;
    }

    /**
     * Adds items to a list box and optionally clears the selection.
     *
     * @param lst the list box
     * @param items the list with the items to add
     * @param clearSel a flag whether the selection should be cleared
     */
    private static void fillList(ListBox lst, List<String> items,
            boolean clearSel)
    {
        for (String item : items)
        {
            lst.addItem(item);
        }
        if (clearSel)
        {
            select(lst, false);
        }
    }

    /**
     * Sets the selection for all list items.
     *
     * @param lst the list box
     * @param enabled the selection enabled flag
     */
    private static void select(ListBox lst, boolean enabled)
    {
        for (int i = 0; i < lst.getItemCount(); i++)
        {
            lst.setItemSelected(i, enabled);
        }
    }

    /**
     * Tests whether the specified list box contains all passed in items. If the
     * all flag is set, it is also checked whether the list does not contain any
     * other items.
     *
     * @param lst the list box
     * @param all the all flag
     * @param items the items to be checked
     */
    private static void checkList(ListBox lst, boolean all, String... items)
    {
        for (String item : items)
        {
            assertTrue("Item not found: " + item, itemIndex(lst, item) >= 0);
        }
        if (all)
        {
            assertEquals("List contains additional items", items.length,
                    lst.getItemCount());
        }
    }

    /**
     * Returns the item index of the specified item in the given list box. A
     * value less than 0 means that the item is not found.
     *
     * @param lst the list box
     * @param item the item to be searched
     * @return the index of this item
     */
    private static int itemIndex(ListBox lst, String item)
    {
        for (int i = 0; i < lst.getItemCount(); i++)
        {
            if (item.equals(lst.getItemText(i)))
            {
                return i;
            }
        }
        return -1;
    }

    /**
     * Tests whether the specified set contains exactly the specified elements.
     *
     * @param set the set to check
     * @param items the items
     */
    private static void checkSet(Set<?> set, Object... items)
    {
        assertEquals("Wrong number of elements", items.length, set.size());
        assertTrue("Invalid elements: " + set,
                set.containsAll(Arrays.asList(items)));
    }

    /**
     * Tests a newly created instance.
     */
    public void testInit()
    {
        SynonymEditor editor = new SynonymEditor();
        assertNotNull("No edit dialog", editor.editDlg);
        assertFalse("Dialog is visible", editor.editDlg.isVisible());
        assertNotNull("No list box for existing synonyms",
                editor.lstExistingSyns);
        assertNotNull("No list box for removed existing synonyms",
                editor.lstRemovedSyns);
        assertTrue("No multi select existing synonyms",
                editor.lstExistingSyns.isMultipleSelect());
        assertTrue("No multi select removed synonyms",
                editor.lstRemovedSyns.isMultipleSelect());
        assertNotNull("No remove existing button", editor.btnRemoveExisting);
        assertNotNull("No add existing button", editor.btnAddExisting);
        assertNotNull("No list box for search synonyms", editor.lstSearchSyns);
        assertNotNull("No list box for new synonyms", editor.lstNewSyns);
        assertTrue("No multi select search synonyms",
                editor.lstSearchSyns.isMultipleSelect());
        assertTrue("No multi select new synonyms",
                editor.lstNewSyns.isMultipleSelect());
        assertNotNull("No add search button", editor.btnAddSearchSyn);
        assertNotNull("No remove search button", editor.btnRemoveSearchSyn);
        assertNotNull("No search text field", editor.txtSearch);
        assertNotNull("No progress indicator", editor.progressIndicator);
        assertFalse("Progress indicator visible",
                editor.progressIndicator.isVisible());
        assertNull("Got a synonym query handler",
                editor.getSynonymQueryHandler());
        assertNull("Got a results processor", editor.getResultsProcessor());
    }

    /**
     * Tests invoking the main entry method.
     */
    public void testEdit()
    {
        ArtistDetailInfo info = new ArtistDetailInfo();
        info.setSynonyms(createSyns(SYN_COUNT));
        SynonymEditor editor = new SynonymEditor();
        editor.lstExistingSyns.addItem(SYN);
        editor.lstNewSyns.addItem(SYN);
        editor.lstRemovedSyns.addItem(SYN);
        editor.lstSearchSyns.addItem(SYN);
        editor.txtSearch.setText(SYN);
        editor.edit(info);
        assertTrue("Dialog not visible", editor.editDlg.isVisible());
        assertEquals("Wrong number of existing items", SYN_COUNT,
                editor.lstExistingSyns.getItemCount());
        assertEquals("Got a selection", 0,
                getSelectionCount(editor.lstExistingSyns));
        assertEquals("Got removed items", 0,
                editor.lstRemovedSyns.getItemCount());
        assertEquals("Got search items", 0, editor.lstSearchSyns.getItemCount());
        assertEquals("got new items", 0, editor.lstNewSyns.getItemCount());
        assertFalse("Btn remove enabled", editor.btnRemoveExisting.isEnabled());
        assertFalse("Btn add existing enabled",
                editor.btnAddExisting.isEnabled());
        assertFalse("Btn add search enabled",
                editor.btnAddSearchSyn.isEnabled());
        assertFalse("Btn remove search enabled",
                editor.btnRemoveSearchSyn.isEnabled());
        assertEquals("Text in search field", "", editor.txtSearch.getText());
        assertSame("Wrong current entity", info, editor.getCurrentEntity());
    }

    /**
     * Tests whether the button for removing an existing synonym is correctly
     * enabled and disabled based on the list selection.
     */
    public void testBtnRemoveExistingEnabled()
    {
        SynonymEditor editor = new SynonymEditor();
        editor.lstExistingSyns.addItem(SYN);
        editor.lstExistingSyns.setItemSelected(0, false);
        editor.listExistingSynonymsSelectionChanged(null);
        assertFalse("Button enabled", editor.btnRemoveExisting.isEnabled());
        editor.lstExistingSyns.setItemSelected(0, true);
        editor.listExistingSynonymsSelectionChanged(null);
        assertTrue("Button disabled", editor.btnRemoveExisting.isEnabled());
    }

    /**
     * Tests whether the button for adding an existing synonym is correctly
     * enabled and disabled based on the list selection.
     */
    public void testBtnAddExistingEnabled()
    {
        SynonymEditor editor = new SynonymEditor();
        editor.lstRemovedSyns.addItem(SYN);
        editor.lstRemovedSyns.setItemSelected(0, false);
        editor.listRemovedSynonymsSelectionChanged(null);
        assertFalse("Button enabled", editor.btnAddExisting.isEnabled());
        editor.lstRemovedSyns.setItemSelected(0, true);
        editor.listRemovedSynonymsSelectionChanged(null);
        assertTrue("Button disabled", editor.btnAddExisting.isEnabled());
    }

    /**
     * Tests whether the button for adding a search result as synonym is
     * correctly enabled and disabled based on the list selection.
     */
    public void testBtnAddSearchSynEnabled()
    {
        SynonymEditor editor = new SynonymEditor();
        editor.lstSearchSyns.addItem(SYN);
        editor.lstSearchSyns.setItemSelected(0, false);
        editor.listSearchSynsSelectionChanged(null);
        assertFalse("Button enabled", editor.btnAddSearchSyn.isEnabled());
        editor.lstSearchSyns.setItemSelected(0, true);
        editor.listSearchSynsSelectionChanged(null);
        assertTrue("Button disabled", editor.btnAddSearchSyn.isEnabled());
    }

    /**
     * Tests whether the button for adding a search result as synonym is
     * correctly enabled and disabled based on the list selection.
     */
    public void testBtnRemoveSearchSynEnabled()
    {
        SynonymEditor editor = new SynonymEditor();
        editor.lstNewSyns.addItem(SYN);
        editor.lstNewSyns.setItemSelected(0, false);
        editor.listNewSynsSelectionChanged(null);
        assertFalse("Button enabled", editor.btnRemoveSearchSyn.isEnabled());
        editor.lstNewSyns.setItemSelected(0, true);
        editor.listNewSynsSelectionChanged(null);
        assertTrue("Button disabled", editor.btnRemoveSearchSyn.isEnabled());
    }

    /**
     * Tests whether the button for removing existing synonyms works as
     * expected.
     */
    public void testOnBtnRemoveExistingClick()
    {
        SynonymEditor editor = new SynonymEditor();
        List<String> syns = createSynList(SYN_COUNT);
        fillList(editor.lstExistingSyns, syns, true);
        editor.lstExistingSyns.setItemSelected(0, true);
        editor.lstExistingSyns.setItemSelected(2, true);
        editor.onBtnRemoveExistingClick(null);
        checkList(editor.lstRemovedSyns, true, syns.get(0), syns.get(2));
        syns.remove(2);
        syns.remove(0);
        checkList(editor.lstExistingSyns, true, syns.toArray(new String[0]));
    }

    /**
     * Tests the order of items which have been moved to another list.
     */
    public void testOnBtnRemoveExistingClickOrder()
    {
        SynonymEditor editor = new SynonymEditor();
        List<String> syns = createSynList(SYN_COUNT);
        fillList(editor.lstExistingSyns, syns, true);
        editor.lstExistingSyns.setItemSelected(0, true);
        editor.lstExistingSyns.setItemSelected(1, true);
        editor.onBtnRemoveExistingClick(null);
        assertEquals("Wrong first item", syns.get(0),
                editor.lstRemovedSyns.getItemText(0));
        assertEquals("Wrong second item", syns.get(1),
                editor.lstRemovedSyns.getItemText(1));
    }

    /**
     * Tests whether the button for re-adding an existing synonym works as
     * expected.
     */
    public void testOnBtnAddExistingClick()
    {
        SynonymEditor editor = new SynonymEditor();
        List<String> syns = createSynList(SYN_COUNT);
        fillList(editor.lstRemovedSyns, syns, true);
        editor.lstRemovedSyns.setItemSelected(0, true);
        editor.lstRemovedSyns.setItemSelected(2, true);
        editor.onBtnAddExistingClick(null);
        checkList(editor.lstExistingSyns, true, syns.get(0), syns.get(2));
        syns.remove(2);
        syns.remove(0);
        checkList(editor.lstRemovedSyns, true, syns.toArray(new String[0]));
    }

    /**
     * Tests whether the button for removing a newly added synonym from search
     * results works as expected.
     */
    public void testOnBtnRemoveSearchSynClick()
    {
        SynonymEditor editor = new SynonymEditor();
        List<String> syns = createSynList(SYN_COUNT);
        fillList(editor.lstNewSyns, syns, true);
        editor.lstNewSyns.setItemSelected(0, true);
        editor.lstNewSyns.setItemSelected(2, true);
        editor.onBtnRemoveSearchSynClick(null);
        syns.remove(2);
        syns.remove(0);
        checkList(editor.lstNewSyns, true, syns.toArray(new String[0]));
        assertEquals("Got search results", 0,
                editor.lstSearchSyns.getItemCount());
    }

    /**
     * Tests whether the button for adding a new synonym from search results
     * works as expected.
     */
    public void testOnBtnAddSearchSynClick()
    {
        SynonymEditor editor = new SynonymEditor();
        List<String> syns = createSynList(SYN_COUNT);
        fillList(editor.lstSearchSyns, syns, true);
        editor.lstSearchSyns.setItemSelected(0, true);
        editor.lstSearchSyns.setItemSelected(2, true);
        editor.onBtnAddSearchSynClick(null);
        checkList(editor.lstNewSyns, true, syns.get(0), syns.get(2));
        syns.remove(2);
        syns.remove(0);
        checkList(editor.lstSearchSyns, true, syns.toArray(new String[0]));
    }

    /**
     * Tests whether the order of items added to the list of new synonyms is
     * correct.
     */
    public void testOnBtnAddSearchSynClickOrder()
    {
        SynonymEditor editor = new SynonymEditor();
        List<String> syns = createSynList(SYN_COUNT);
        fillList(editor.lstSearchSyns, syns, true);
        editor.lstSearchSyns.setItemSelected(0, true);
        editor.lstSearchSyns.setItemSelected(1, true);
        editor.onBtnAddSearchSynClick(null);
        assertEquals("Wrong first item", syns.get(0),
                editor.lstNewSyns.getItemText(0));
        assertEquals("Wrong second item", syns.get(1),
                editor.lstNewSyns.getItemText(1));
    }

    /**
     * Tests whether no duplicates are created when synonyms from search results
     * are added.
     */
    public void testOnBtnAddSearchSynClickDuplicates()
    {
        SynonymEditor editor = new SynonymEditor();
        editor.addResults(createSearchResultsMap(createSynList(SYN_COUNT / 2)),
                false);
        select(editor.lstSearchSyns, true);
        editor.onBtnAddSearchSynClick(null);
        assertEquals("Still got search results", 0,
                editor.lstSearchSyns.getItemCount());
        List<String> syns = createSynList(SYN_COUNT);
        editor.addResults(createSearchResultsMap(syns), false);
        select(editor.lstSearchSyns, true);
        editor.onBtnAddSearchSynClick(null);
        checkList(editor.lstNewSyns, true, syns.toArray(new String[0]));
    }

    /**
     * Tests startSearch() if no search text is provided.
     */
    public void testStartSearchNoText()
    {
        SynonymEditor editor = new SynonymEditor();
        SynonymQueryHandlerTestImpl handler =
                new SynonymQueryHandlerTestImpl(editor);
        editor.setSynonymQueryHandler(handler);
        editor.lstSearchSyns.addItem(SYN);
        editor.progressIndicator.setVisible(true);
        editor.startSynonymSearch();
        assertNull("Handler invoked", handler.getClientParam());
        assertEquals("List not cleared", 0, editor.lstSearchSyns.getItemCount());
        assertFalse("Progress indicator visible",
                editor.progressIndicator.isVisible());
    }

    /**
     * Tests whether a search for a given search text can be started.
     */
    public void testStartSearch()
    {
        SynonymEditor editor = new SynonymEditor();
        SynonymQueryHandlerTestImpl handler =
                new SynonymQueryHandlerTestImpl(editor);
        editor.setSynonymQueryHandler(handler);
        editor.lstSearchSyns.addItem(SYN);
        editor.txtSearch.setText(SYN);
        editor.startSynonymSearch();
        assertEquals("List not cleared", 0, editor.lstSearchSyns.getItemCount());
        assertEquals("Wrong search text", SYN, handler.getSearchText());
        assertEquals("Wrong client parameter", Long.valueOf(1),
                handler.getClientParam());
        assertTrue("Progress indicator not visible",
                editor.progressIndicator.isVisible());
    }

    /**
     * Tests whether results of a synonym search can be added.
     */
    public void testAddResults()
    {
        SynonymEditor editor = new SynonymEditor();
        editor.progressIndicator.setVisible(true);
        List<String> syns = createSynList(SYN_COUNT);
        editor.addResults(createSearchResultsMap(syns), true);
        checkList(editor.lstSearchSyns, true, syns.toArray(new String[0]));
        assertTrue("Progress indicator not visible",
                editor.progressIndicator.isVisible());
    }

    /**
     * Tests whether the search complete flag is evaluated when adding search
     * results.
     */
    public void testAddResultsSearchComplete()
    {
        SynonymEditor editor = new SynonymEditor();
        editor.progressIndicator.setVisible(true);
        List<String> syns = createSynList(SYN_COUNT);
        editor.addResults(
                createSearchResultsMap(syns.subList(0, SYN_COUNT / 2)), true);
        editor.addResults(
                createSearchResultsMap(syns.subList(SYN_COUNT / 2, SYN_COUNT)),
                false);
        checkList(editor.lstSearchSyns, true, syns.toArray(new String[0]));
        assertFalse("Progress indicator still visible",
                editor.progressIndicator.isVisible());
    }

    /**
     * Tests whether search results that have already been added as new synonyms
     * are suppressed.
     */
    public void testAddResultsAlreadyExisting()
    {
        SynonymEditor editor = new SynonymEditor();
        List<String> syns = createSynList(SYN_COUNT);
        editor.addResults(createSearchResultsMap(syns), false);
        select(editor.lstSearchSyns, true);
        editor.onBtnAddSearchSynClick(null);
        assertEquals("Got entires in search list (1)", 0,
                editor.lstSearchSyns.getItemCount());
        editor.addResults(createSearchResultsMap(createSynList(1)), false);
        assertEquals("Got entires in search list (2)", 0,
                editor.lstSearchSyns.getItemCount());
    }

    /**
     * Tests whether the name of the current entity is suppressed when new
     * search results are added.
     */
    public void testAddResultsCurrentName()
    {
        ArtistDetailInfo info = new ArtistDetailInfo();
        info.setName(SYN);
        info.setSynonyms(new HashSet<String>());
        SynonymEditor editor = new SynonymEditor();
        editor.edit(info);
        Map<Object, String> map = new HashMap<Object, String>();
        map.put(1, SYN);
        editor.addResults(map, false);
        assertEquals("Got search results", 0,
                editor.lstSearchSyns.getItemCount());
    }

    /**
     * Helper method for testing acceptResults().
     *
     * @param param the client parameter
     * @param expected the expected result
     */
    private void checkAcceptResults(long param, boolean expected)
    {
        SynonymEditor editor = new SynonymEditor();
        editor.setSynonymQueryHandler(new SynonymQueryHandlerTestImpl(editor));
        editor.txtSearch.setText(SYN);
        editor.startSynonymSearch();
        MediaSearchParameters params = new MediaSearchParameters();
        params.setClientParameter(param);
        assertEquals("Wrong result", expected, editor.acceptResults(params));
    }

    /**
     * Tests acceptResults() if the expected result is true.
     */
    public void testAcceptResultsTrue()
    {
        checkAcceptResults(1L, true);
    }

    /**
     * Tests acceptResults() if the expected result is false.
     */
    public void testAcceptResultsFalse()
    {
        checkAcceptResults(0L, false);
    }

    /**
     * Tests whether newly added synonyms are taken into account when the dialog
     * is saved. We also test whether moving search results between the lists
     * works as expected.
     */
    public void testOnBtnSaveClickNewSynonyms()
    {
        SynonymEditor editor = new SynonymEditor();
        SynonymEditResultsProcessorTestImpl proc =
                new SynonymEditResultsProcessorTestImpl();
        editor.setResultsProcessor(proc);
        editor.addResults(createSearchResultsMap(createSynList(SYN_COUNT)),
                false);
        select(editor.lstSearchSyns, true);
        editor.lstSearchSyns.setItemSelected(0, false);
        editor.lstSearchSyns.setItemSelected(1, false);
        editor.onBtnAddSearchSynClick(null);
        select(editor.lstNewSyns, true);
        editor.onBtnRemoveSearchSynClick(null);
        assertEquals("Wrong number of items", 2,
                editor.lstSearchSyns.getItemCount());
        select(editor.lstSearchSyns, true);
        editor.onBtnAddSearchSynClick(null);
        editor.onBtnSaveClick(null);
        assertFalse("Dialog still showing", editor.editDlg.isShowing());
        assertTrue("Got removed synonyms", proc.getRemovedSynonyms().isEmpty());
        checkSet(proc.getAddedSynonyms(), "0", "1");
    }

    /**
     * Tests whether removed synonyms are taken into account when the dialog is
     * saved.
     */
    public void testOnBtnSaveClickRemovedSynonyms()
    {
        SynonymEditor editor = new SynonymEditor();
        SynonymEditResultsProcessorTestImpl proc =
                new SynonymEditResultsProcessorTestImpl();
        editor.setResultsProcessor(proc);
        List<String> syns = createSynList(SYN_COUNT);
        fillList(editor.lstExistingSyns, syns, true);
        editor.lstExistingSyns.setItemSelected(0, true);
        editor.lstExistingSyns.setItemSelected(1, true);
        editor.onBtnRemoveExistingClick(null);
        editor.onBtnSaveClick(null);
        assertTrue("Got new synonyms", proc.getAddedSynonyms().isEmpty());
        checkSet(proc.getRemovedSynonyms(), syns.get(0), syns.get(1));
    }

    /**
     * Changes the behavior of the save button if there are no changes. In this
     * case the results processor should not be called.
     */
    public void testOnBtnSaveClickNoChanges()
    {
        SynonymEditor editor = new SynonymEditor();
        SynonymEditResultsProcessorTestImpl proc =
                new SynonymEditResultsProcessorTestImpl();
        editor.setResultsProcessor(proc);
        editor.onBtnSaveClick(null);
        assertNull("Processor was called", proc.getAddedSynonyms());
        assertFalse("Dialog still showing", editor.editDlg.isShowing());
    }

    /**
     * Tests the reaction on the cancel button.
     */
    public void testOnBtnCancelClick()
    {
        SynonymEditor editor = new SynonymEditor();
        SynonymEditResultsProcessorTestImpl proc =
                new SynonymEditResultsProcessorTestImpl();
        editor.setResultsProcessor(proc);
        editor.onBtnCancelClick(null);
        assertNull("Processor was called", proc.getAddedSynonyms());
        assertFalse("Dialog still showing", editor.editDlg.isShowing());
    }

    /**
     * Tests error handling for synonym search.
     */
    public void testOnFailure()
    {
        SynonymEditor editor = new SynonymEditor();
        editor.progressIndicator.setVisible(true);
        editor.onFailure(new Exception("Test exception!"));
        assertFalse("Progress indicator still visible",
                editor.progressIndicator.isVisible());
    }

    /**
     * Tests whether the timer for starting the synonym search works as
     * expected.
     */
    public void testGetTimer()
    {
        SynonymEditor editor = new SynonymEditor();
        SynonymQueryHandlerTestImpl handler =
                new SynonymQueryHandlerTestImpl(editor);
        editor.setSynonymQueryHandler(handler);
        editor.txtSearch.setText(SYN);
        Timer timer = editor.getTimer();
        timer.run();
        assertEquals("Query handler not called", SYN, handler.getSearchText());
    }

    /**
     * Tests that only a single timer instance exists.
     */
    public void testGetTimerSingleInstance()
    {
        SynonymEditor editor = new SynonymEditor();
        Timer timer = editor.getTimer();
        assertSame("Another timer instance", timer, editor.getTimer());
    }

    /**
     * Helper method for simulating a key event in the synonym search text
     * field.
     *
     * @param editor the editor
     * @param keyCode the key code to fire
     */
    private static void fireTxtSearchKeyEvent(SynonymEditor editor, int keyCode)
    {
        NativeEvent keyEvent =
                Document.get().createKeyUpEvent(false, false, false, false,
                        keyCode);
        DomEvent.fireNativeEvent(keyEvent, editor.txtSearch);
    }

    /**
     * Tests whether a key event in the synonym search text field is handled
     * correctly.
     */
    public void testOnTxtSearchKeyUpOtherKey()
    {
        final TimerTestImpl timer = new TimerTestImpl();
        SynonymEditor editor = new SynonymEditor()
        {
            @Override
            Timer getTimer()
            {
                return timer;
            }
        };
        fireTxtSearchKeyEvent(editor, KeyCodes.KEY_BACKSPACE);
        assertEquals("Wrong timer delay", SynonymEditor.SYNONYM_SEARCH_DELAY,
                timer.getDelay());
    }

    /**
     * Tests whether an ENTER key in the synonym search text field is handled
     * correctly.
     */
    public void testOnTxtSearchKeyUpEnter()
    {
        final TimerTestImpl timer = new TimerTestImpl();
        SynonymEditor editor = new SynonymEditor()
        {
            @Override
            Timer getTimer()
            {
                return timer;
            }
        };
        SynonymQueryHandlerTestImpl handler =
                new SynonymQueryHandlerTestImpl(editor);
        editor.setSynonymQueryHandler(handler);
        editor.txtSearch.setText(SYN);
        fireTxtSearchKeyEvent(editor, KeyCodes.KEY_ENTER);
        assertEquals("Query handler not called", SYN, handler.getSearchText());
    }

    /**
     * A test synonym query handler implementation for testing whether the
     * handler is correctly invoked when a search begins.
     */
    private static class SynonymQueryHandlerTestImpl implements
            SynonymQueryHandler
    {
        /** The expected search view. */
        private final SynonymSearchResultView expectedView;

        /** The search text. */
        private String searchText;

        /** The client parameter. */
        private Object clientParam;

        /**
         * Creates a new instance of {@code SynonymQueryHandlerTestImpl}.
         *
         * @param v the expected view
         */
        public SynonymQueryHandlerTestImpl(SynonymSearchResultView v)
        {
            expectedView = v;
        }

        /**
         * Returns the search text passed to this object.
         *
         * @return the search text
         */
        public String getSearchText()
        {
            return searchText;
        }

        /**
         * Returns the client parameter passed to this object.
         *
         * @return the client parameter
         */
        public Object getClientParam()
        {
            return clientParam;
        }

        /**
         * Records this invocation and checks parameters.
         */
        @Override
        public void querySynonyms(SynonymSearchResultView view,
                String searchText, Serializable clientParam)
        {
            assertSame("Wrong view", expectedView, view);
            this.clientParam = clientParam;
            this.searchText = searchText;
        }
    }

    /**
     * A test implementation of {@link SynonymEditResultsProcessor} which allows
     * testing whether the expected sets with modified synonyms are produced.
     */
    private static class SynonymEditResultsProcessorTestImpl implements
            SynonymEditResultsProcessor
    {
        /** Stores the names of the removed synonyms. */
        private Set<String> removedSynonyms;

        /** Stores the IDs of the newly added synonyms. */
        private Set<String> addedSynonyms;

        /**
         * Returns the set with the removed synonyms.
         *
         * @return the removed synonyms
         */
        public Set<String> getRemovedSynonyms()
        {
            return removedSynonyms;
        }

        /**
         * Returns the set with the added synonym IDs.
         *
         * @return the added synonyms
         */
        public Set<String> getAddedSynonyms()
        {
            return addedSynonyms;
        }

        /**
         * Records this invocation.
         */
        @Override
        public void synonymsChanged(SynonymUpdateData data)
        {
            removedSynonyms = data.getRemoveSynonyms();
            addedSynonyms = data.getNewSynonymIDs();
        }
    }

    /**
     * A specialized timer implementation for testing the handling of key events
     * in the synonym search text field.
     */
    private static class TimerTestImpl extends Timer
    {
        /** A flag whether cancel was called. */
        private boolean canceled;

        /** The delay passed to schedule(). */
        private int delay;

        /**
         * Returns a flag whether the timer was canceled.
         *
         * @return the cancel flag
         */
        public boolean isCanceled()
        {
            return canceled;
        }

        /**
         * Returns the delay passed to schedule().
         *
         * @return the delay
         */
        public int getDelay()
        {
            return delay;
        }

        /**
         * Records this invocation.
         */
        @Override
        public void cancel()
        {
            canceled = true;
        }

        /**
         * Records this invocation.
         */
        @Override
        public void schedule(int delayMillis)
        {
            assertTrue("Not canceled", isCanceled());
            delay = delayMillis;
        }

        @Override
        public void run()
        {
            throw new UnsupportedOperationException("Unexpected method call!");
        }
    }
}
