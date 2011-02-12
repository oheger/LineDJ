package de.oliver_heger.mediastore.client.pages.detail;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.google.gwt.user.client.rpc.AsyncCallback;
import com.google.gwt.user.client.ui.Grid;
import com.google.gwt.user.client.ui.Hyperlink;

import de.oliver_heger.mediastore.client.pages.MockPageConfiguration;
import de.oliver_heger.mediastore.client.pages.MockPageManager;
import de.oliver_heger.mediastore.client.pages.Pages;
import de.oliver_heger.mediastore.shared.BasicMediaServiceAsync;
import de.oliver_heger.mediastore.shared.SynonymUpdateData;
import de.oliver_heger.mediastore.shared.model.AlbumInfo;
import de.oliver_heger.mediastore.shared.model.ArtistDetailInfo;
import de.oliver_heger.mediastore.shared.model.HasSynonyms;
import de.oliver_heger.mediastore.shared.model.SongInfo;

/**
 * Test class for {@code ArtistDetailsPage}. This class also tests functionality
 * inherited from the super class.
 *
 * @author Oliver Heger
 * @version $Id: $
 */
public class GwtTestArtistDetailsPage extends AbstractTestDetailsPage
{
    /** Constant for the ID of the test artist. */
    private static final Long ARTIST_ID = 20101216075421L;

    /** Constant for the test artist name. */
    private static final String NAME = "Elvis";

    /** An array with synonyms. */
    private static final String[] SYNONYMS = {
            "The King", "Elvis Presley"
    };

    /**
     * Creates a details info object for an artist with test data.
     *
     * @return the details object
     */
    private static ArtistDetailInfo createArtistInfo()
    {
        ArtistDetailInfo info = new ArtistDetailInfo();
        info.setArtistID(ARTIST_ID);
        info.setName(NAME);
        info.setSynonyms(new HashSet<String>(Arrays.asList(SYNONYMS)));
        info.setCreationDate(new Date());
        return info;
    }

    /**
     * Tests whether a page can be created correctly.
     */
    public void testInit()
    {
        ArtistDetailsPage page = new ArtistDetailsPage();
        assertNotNull("No name span", page.spanArtistName);
        assertNotNull("No creation date span", page.spanCreationDate);
        assertNotNull("No synonyms span", page.spanSynonyms);
        checkBaseFields(page);
    }

    /**
     * Tests whether the synonym editor has been correctly initialized.
     */
    public void testInitSynonymEditor()
    {
        ArtistDetailsPage page = new ArtistDetailsPage();
        initializePage(page);
        assertNotNull("No synonym editor", page.synEditor);
        ArtistSynonymQueryHandler handler =
                (ArtistSynonymQueryHandler) page.synEditor
                        .getSynonymQueryHandler();
        assertNotNull("No search service set", handler.getSearchService());
        assertSame("No results processor set", page,
                page.synEditor.getResultsProcessor());
    }

    /**
     * Tests the setPageConfiguration() method. This method should initiate a
     * server request.
     */
    public void testSetPageConfiguration()
    {
        final Map<String, Object> params = new HashMap<String, Object>();
        ArtistDetailsPage page = new ArtistDetailsPage()
        {
            @Override
            protected DetailsEntityHandler<ArtistDetailInfo> getDetailsEntityHandler()
            {
                assertTrue("No progress indicator",
                        progressIndicator.isVisible());
                assertFalse("Error panel visible", pnlError.isInErrorState());
                return new DetailsEntityHandler<ArtistDetailInfo>()
                {
                    @Override
                    public void fetchDetails(
                            BasicMediaServiceAsync mediaService, String elemID,
                            AsyncCallback<ArtistDetailInfo> callback)
                    {
                        assertEquals("Wrong callback",
                                getFetchDetailsCallback(), callback);
                        assertEquals("Wrong ID", ARTIST_ID.toString(), elemID);
                        assertNotNull("No media service", mediaService);
                        params.put(NAME, Boolean.TRUE);
                    }

                    @Override
                    public void updateSynonyms(
                            BasicMediaServiceAsync mediaService, String elemID,
                            SynonymUpdateData upData,
                            AsyncCallback<Void> callback)
                    {
                        throw new UnsupportedOperationException(
                                "Unexpected call!");
                    }
                };
            }
        };
        page.pnlError.displayError(new Exception());
        page.progressIndicator.setVisible(false);
        params.put(null, ARTIST_ID.toString());
        page.setPageConfiguration(new MockPageConfiguration(Pages.ARTISTDETAILS
                .name(), params));
        assertTrue("Handler not called", params.containsKey(NAME));
        assertFalse("In error state", page.pnlError.isInErrorState());
        assertTrue("No progress indicator", page.progressIndicator.isVisible());
        assertFalse("Edit synonyms button enabled",
                page.btnEditSynonyms.isEnabled());
        assertEquals("Wrong current ID", String.valueOf(ARTIST_ID),
                page.getCurrentEntityID());
    }

    /**
     * Tests whether the callback works correctly if the server call was
     * successful.
     */
    public void testFetchDetailsCallbackSuccess()
    {
        ArtistDetailsPage page = new ArtistDetailsPage();
        initializePage(page);
        page.pnlError.displayError(new Exception());
        page.progressIndicator.setVisible(true);
        page.btnEditSynonyms.setEnabled(false);
        page.pnlSongs.setOpen(true);
        AsyncCallback<ArtistDetailInfo> callback =
                page.getFetchDetailsCallback();
        ArtistDetailInfo info = createArtistInfo();
        callback.onSuccess(info);
        assertFalse("Got a progress indicator",
                page.progressIndicator.isVisible());
        assertEquals("Wrong artist name", NAME,
                page.spanArtistName.getInnerText());
        assertNotNull("No creation date", page.spanCreationDate.getInnerText());
        String s = page.spanSynonyms.getInnerText();
        for (String syn : SYNONYMS)
        {
            assertTrue("Synonym not found: " + s, s.contains(syn));
        }
        assertSame("Wrong current object", info, page.getCurrentEntity());
        assertTrue("Edit synonyms button not enabled",
                page.btnEditSynonyms.isEnabled());
        assertEquals("Got songs", 1, page.tabSongs.getRowCount());
        assertEquals("Wrong song count", "Songs (0)", page.pnlSongs
                .getHeaderTextAccessor().getText());
        assertFalse("Songs panel is open", page.pnlSongs.isOpen());
        assertEquals("Got albums", 1, page.tabAlbums.getRowCount());
        assertEquals("Wrong album count", "Albums (0)", page.pnlAlbums
                .getHeaderTextAccessor().getText());
        assertFalse("Albums panel is open", page.pnlAlbums.isOpen());
    }

    /**
     * Tests whether exceptions are correctly processed by the callback.
     */
    public void testFetchDetailsCallbackError()
    {
        ArtistDetailsPage page = new ArtistDetailsPage();
        initializePage(page);
        Throwable ex = new Exception("Test exception");
        page.progressIndicator.setVisible(true);
        AsyncCallback<ArtistDetailInfo> callback =
                page.getFetchDetailsCallback();
        callback.onFailure(ex);
        assertTrue("Not in error state", page.pnlError.isInErrorState());
        assertEquals("Wrong exception", ex, page.pnlError.getError());
        assertFalse("Got a progress indicator",
                page.progressIndicator.isVisible());
        assertEmpty("Got artist name", page.spanArtistName.getInnerText());
        assertEmpty("Got creation date", page.spanCreationDate.getInnerText());
        assertEmpty("Got synonyms", page.spanSynonyms.getInnerText());
        assertFalse("Edit synonyms button not disabled",
                page.btnEditSynonyms.isEnabled());
    }

    /**
     * Tests whether formatSynonyms() can handle a null set.
     */
    public void testFormatSynonymsNull()
    {
        ArtistDetailsPage page = new ArtistDetailsPage();
        assertEmpty("Got synonyms", page.formatSynonyms(null));
    }

    /**
     * Tests whether a single synonym is correctly formatted.
     */
    public void testFormatSynonymsSingle()
    {
        Set<String> syns = Collections.singleton(SYNONYMS[0]);
        assertEquals("Wrong formatted synonyms", SYNONYMS[0],
                new ArtistDetailsPage().formatSynonyms(syns));
    }

    /**
     * Tests whether multiple synonyms can be correctly formatted.
     */
    public void testFormatSynonymsMultiple()
    {
        Set<String> syns = new LinkedHashSet<String>();
        syns.add(SYNONYMS[0]);
        syns.add(SYNONYMS[1]);
        ArtistDetailsPage page = new ArtistDetailsPage();
        assertEquals("Wrong formatted synonyms", SYNONYMS[0] + ", "
                + SYNONYMS[1], page.formatSynonyms(syns));
    }

    /**
     * Tests whether the correct query handler is returned.
     */
    public void testGetDetailsQueryHandler()
    {
        ArtistDetailsPage page = new ArtistDetailsPage();
        assertTrue(
                "Wrong query handler",
                page.getDetailsEntityHandler() instanceof ArtistDetailsEntityHandler);
    }

    /**
     * Tests whether only a single instance of the query handler exists.
     */
    public void testGetDetailsQueryHandlerCached()
    {
        ArtistDetailsPage page = new ArtistDetailsPage();
        DetailsEntityHandler<ArtistDetailInfo> handler =
                page.getDetailsEntityHandler();
        assertSame("Multiple handlers", handler, page.getDetailsEntityHandler());
    }

    /**
     * Tests whether the link back to the overview page is correctly
     * initialized.
     */
    public void testInitOverviewLink()
    {
        ArtistDetailsPage page = new ArtistDetailsPage();
        MockPageManager pm = initializePage(page);
        assertEquals("Wrong token",
                MockPageManager.defaultToken(Pages.OVERVIEW),
                page.lnkOverview.getTargetHistoryToken());
        pm.verify();
    }

    /**
     * Tests whether the synonym editor can be invoked.
     */
    public void testOnClickEditSynonyms()
    {
        ArtistDetailInfo info = createArtistInfo();
        final List<Object> editedObjects = new ArrayList<Object>(1);
        SynonymEditor editor = new SynonymEditor()
        {
            @Override
            public void edit(HasSynonyms entity)
            {
                editedObjects.add(entity);
            }
        };
        ArtistDetailsPage page = new ArtistDetailsPage();
        page.synEditor = editor;
        page.setCurrentEntity(info);
        page.onClickEditSynonyms(null);
        assertEquals("Wrong number of edit calls", 1, editedObjects.size());
        assertSame("Wrong edited object", info, editedObjects.get(0));
    }

    /**
     * Tests whether the notification of the synonym editor about changed
     * synonyms is correctly processed.
     */
    public void testSynonymsChanged()
    {
        final SynonymUpdateData updateData = new SynonymUpdateData();
        final List<Object> callList = new ArrayList<Object>();
        ArtistDetailsPage page = new ArtistDetailsPage()
        {
            @Override
            public String getCurrentEntityID()
            {
                return String.valueOf(ARTIST_ID);
            }

            @Override
            protected DetailsEntityHandler<ArtistDetailInfo> getDetailsEntityHandler()
            {
                return new DetailsEntityHandler<ArtistDetailInfo>()
                {
                    @Override
                    public void updateSynonyms(
                            BasicMediaServiceAsync mediaService, String elemID,
                            SynonymUpdateData upData,
                            AsyncCallback<Void> callback)
                    {
                        assertEquals("Wrong element ID",
                                String.valueOf(ARTIST_ID), elemID);
                        assertEquals("Wrong callback",
                                getUpdateSynonymsCallback(), callback);
                        assertSame("Wrong update data", updateData, upData);
                        callList.add(Boolean.TRUE);
                    }

                    @Override
                    public void fetchDetails(
                            BasicMediaServiceAsync mediaService, String elemID,
                            AsyncCallback<ArtistDetailInfo> callback)
                    {
                        throw new UnsupportedOperationException(
                                "Unexpected call!");
                    }
                };
            }
        };
        page.synonymsChanged(updateData);
        assertEquals("Handler not called", 1, callList.size());
        assertTrue("Progress indicator not enabled",
                page.progressIndicator.isVisible());
        assertFalse("Button edit synonyms enabled",
                page.btnEditSynonyms.isEnabled());
    }

    /**
     * Tests the synonym update callback.
     */
    public void testUpdateSynonymCallbackSuccess()
    {
        final List<Object> callList = new ArrayList<Object>();
        ArtistDetailsPage page = new ArtistDetailsPage()
        {
            @Override
            void refresh()
            {
                callList.add(Boolean.TRUE);
            }
        };
        AsyncCallback<Void> callback = page.getUpdateSynonymsCallback();
        callback.onSuccess(null);
        assertEquals("Refresh not called", 1, callList.size());
    }

    /**
     * Tests whether songs can be displayed.
     */
    public void testFillPageWithSongs()
    {
        ArtistDetailsPage page = new ArtistDetailsPage();
        MockPageManager pm = initializePage(page);
        final int songCount = 4;
        final String songTitle = "Mambo No ";
        final int duration = 3 * 60;
        List<SongInfo> songInfos = new ArrayList<SongInfo>(songCount);
        for (int i = 0; i < songCount; i++)
        {
            SongInfo si = new SongInfo();
            si.setSongID(String.valueOf(i));
            si.setName(songTitle + i);
            si.setDuration(Long.valueOf((duration + i) * 1000L));
            si.setPlayCount(i);
            pm.expectCreatePageSpecification(Pages.SONGDETAILS, null)
                    .withParameter(si.getSongID()).toToken();
            songInfos.add(si);
        }
        List<SongInfo> randomSongs = new ArrayList<SongInfo>(songInfos);
        Collections.swap(randomSongs, 0, 2);
        Collections.swap(randomSongs, 3, 1);
        ArtistDetailInfo info = new ArtistDetailInfo();
        info.setSongs(randomSongs);
        page.fillPage(info);
        pm.verify();
        assertTrue("Songs panel not open", page.pnlSongs.isOpen());
        assertEquals("Wrong song count", "Songs (" + songCount + ")",
                page.pnlSongs.getHeaderTextAccessor().getText());
        assertEquals("Wrong number of song rows", songCount + 1,
                page.tabSongs.getRowCount());
        for (int i = 0; i < songCount; i++)
        {
            SongInfo si = songInfos.get(i);
            Hyperlink link = (Hyperlink) page.tabSongs.getWidget(i + 1, 0);
            assertEquals("Wrong target",
                    MockPageManager.defaultToken(Pages.SONGDETAILS),
                    link.getTargetHistoryToken());
            assertEquals("Wrong link text", si.getName(), link.getText());
            assertEquals("Wrong duration", si.getFormattedDuration(),
                    page.tabSongs.getText(i + 1, 1));
            assertEquals("Wrong play count", String.valueOf(i),
                    page.tabSongs.getText(i + 1, 2));
        }
    }

    /**
     * Tests whether the grid with the songs and its model are correctly set up.
     */
    public void testInitSongGridModel()
    {
        ArtistDetailsPage page = new ArtistDetailsPage();
        MockPageManager pm = initializePage(page);
        SongGridTableModel model = page.getSongTableModel();
        assertEquals("Wrong number of columns", 3, model.getColumnCount());
        assertSame("Wrong page manager", pm, model.getPageManager());
        assertEquals("Wrong property (1)", "name", model.getProperty(0));
        assertEquals("Wrong property (2)", "duration", model.getProperty(1));
        assertEquals("Wrong property (3)", "playCount", model.getProperty(2));
        assertSame("Wrong grid", page.tabSongs, model.getGrid());
    }

    /**
     * Tests whether the grid with the albums and its model are correctly set
     * up.
     */
    public void testInitAlbumGridModel()
    {
        ArtistDetailsPage page = new ArtistDetailsPage();
        MockPageManager pm = initializePage(page);
        AlbumGridTableModel model = page.getAlbumTableModel();
        assertEquals("Wrong number of columns", 1, model.getColumnCount());
        assertEquals("Wrong property (1)", "name", model.getProperty(0));
        assertSame("Wrong page manager", pm, model.getPageManager());
        assertSame("Wrong grid", page.tabAlbums, model.getGrid());
    }

    /**
     * Tests whether albums are correctly handled when the page is filled.
     */
    public void testFillPageWithAlbums()
    {
        final List<List<AlbumInfo>> dataLists =
                new ArrayList<List<AlbumInfo>>();
        final AlbumGridTableModel model =
                new AlbumGridTableModel(new Grid(), new MockPageManager())
                {
                    @Override
                    public void initData(List<AlbumInfo> data)
                    {
                        dataLists.add(data);
                    }
                };
        ArtistDetailsPage page = new ArtistDetailsPage()
        {
            @Override
            AlbumGridTableModel getAlbumTableModel()
            {
                return model;
            }
        };
        initializePage(page);
        AlbumInfo ai = new AlbumInfo();
        ai.setName("TestAlbum");
        ArtistDetailInfo info = new ArtistDetailInfo();
        info.setAlbums(Collections.singletonList(ai));
        page.fillPage(info);
        assertEquals("Wrong number of initData() calls for albums", 1,
                dataLists.size());
        assertSame("Albums not initialized", info.getAlbums(), dataLists.get(0));
        assertTrue("Album panel not open", page.pnlAlbums.isOpen());
        assertEquals("Wrong album count", "Albums (1)", page.pnlAlbums
                .getHeaderTextAccessor().getText());
    }
}
