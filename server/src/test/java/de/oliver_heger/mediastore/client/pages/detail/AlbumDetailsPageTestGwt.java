package de.oliver_heger.mediastore.client.pages.detail;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.google.gwt.core.client.GWT;

import de.oliver_heger.mediastore.client.pages.MockPageManager;
import de.oliver_heger.mediastore.client.pages.Pages;
import de.oliver_heger.mediastore.shared.model.AlbumDetailInfo;
import de.oliver_heger.mediastore.shared.model.ArtistInfo;
import de.oliver_heger.mediastore.shared.model.SongInfo;
import de.oliver_heger.mediastore.shared.search.MediaSearchService;
import de.oliver_heger.mediastore.shared.search.MediaSearchServiceAsync;

/**
 * Test class for {@code AlbumDetailsPage}.
 *
 * @author Oliver Heger
 * @version $Id: $
 */
public class AlbumDetailsPageTestGwt extends AbstractTestDetailsPage
{
    /**
     * Creates an info object with some basic properties.
     *
     * @return the info object
     */
    private AlbumDetailInfo createBasicInfo()
    {
        AlbumDetailInfo info = new AlbumDetailInfo();
        info.setCreationDate(new Date());
        info.setDuration((60 * 60 + 11) * 1000L);
        info.setInceptionYear(2010);
        info.setName("A brand new test album");
        return info;
    }

    /**
     * Tests whether the member fields have been created correctly.
     */
    public void testInit()
    {
        AlbumDetailsPage page = new AlbumDetailsPage();
        checkBaseFields(page);
        assertNotNull("No name span", page.spanName);
        assertNotNull("No artist link", page.lnkArtist);
        assertNotNull("No duration span", page.spanDuration);
        assertNotNull("No synonyms span", page.spanSynonyms);
        assertNotNull("No panel for songs", page.pnlSongs);
        assertNotNull("No panel for artists", page.pnlArtists);
        assertNotNull("No table for songs", page.tabSongs);
        assertNotNull("No table for artists", page.tabArtists);
        assertNotNull("No label for multiple artists", page.labMultiArtists);
    }

    /**
     * Tests whether the expected query handler is returned.
     */
    public void testGetDetailsQueryHandler()
    {
        AlbumDetailsPage page = new AlbumDetailsPage();
        assertTrue(
                "Wrong details entity handler",
                page.getDetailsEntityHandler() instanceof AlbumDetailsEntityHandler);
    }

    /**
     * Tests that only a single instance of the details query handler exists.
     */
    public void testGetDetailsQueryHandlerCached()
    {
        AlbumDetailsPage page = new AlbumDetailsPage();
        DetailsEntityHandler<AlbumDetailInfo> handler =
                page.getDetailsEntityHandler();
        assertSame("Multiple details query handler", handler,
                page.getDetailsEntityHandler());
    }

    /**
     * Tests whether the synonym query handler can be retrieved.
     */
    public void testGetSynonymQueryHandler()
    {
        AlbumDetailsPage page = new AlbumDetailsPage();
        MediaSearchServiceAsync searchService =
                GWT.create(MediaSearchService.class);
        AlbumSynonymQueryHandler handler =
                (AlbumSynonymQueryHandler) page
                        .getSynonymQueryHandler(searchService);
        assertSame("Wrong search service", searchService,
                handler.getSearchService());
    }

    /**
     * Tests whether the grid with the songs and its model are correctly set up.
     */
    public void testInitSongGridModel()
    {
        AlbumDetailsPage page = new AlbumDetailsPage();
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
     * Tests whether the table for the artists is correctly set up.
     */
    public void testInitArtistTable()
    {
        AlbumDetailsPage page = new AlbumDetailsPage();
        initializePage(page);
        assertEquals("Wrong number of columns", 1,
                page.tabArtists.cellTable.getColumnCount());
    }

    /**
     * Helper method for checking the panel of the artists table.
     *
     * @param page the page
     * @param count the expected number of artists
     * @param open a flag whether the panel with artists should be open
     */
    private void checkArtistTable(AlbumDetailsPage page, int count, boolean open)
    {
        checkDisclosurePanel(page.pnlArtists, "Artists", count, open);
        assertEquals("Wrong number of artists", count, page.tabArtists
                .getDataProvider().getList().size());
    }

    /**
     * Helper method for checking the panel of the songs table.
     *
     * @param page the page
     * @param count the expected number of songs
     * @param open a flag whether the panel with songs should be open
     */
    private void checkSongTable(AlbumDetailsPage page, int count, boolean open)
    {
        checkDisclosurePanel(page.pnlSongs, "Songs", count, open);
    }

    /**
     * Tests fillPage() if only basic attributes are available.
     */
    public void testFillPageSimple()
    {
        AlbumDetailsPage page = new AlbumDetailsPage();
        initializePage(page);
        AlbumDetailInfo info = createBasicInfo();
        page.fillPage(info);
        assertEquals("Wrong name", info.getName(), page.spanName.getInnerText());
        assertEquals("Wrong duration", info.getFormattedDuration(),
                page.spanDuration.getInnerText());
        assertEquals("Wrong year", String.valueOf(info.getInceptionYear()),
                page.spanYear.getInnerText());
        assertTrue("No creation date", page.spanCreationDate.getInnerText()
                .length() > 0);
        assertFalse("Got an artist link", page.lnkArtist.isVisible());
        assertFalse("Got label for multiple artists",
                page.labMultiArtists.isVisible());
        checkSongTable(page, 0, false);
        checkArtistTable(page, 0, false);
    }

    /**
     * Tests whether synonyms are correctly displayed.
     */
    public void testFillPageSynonyms()
    {
        AlbumDetailsPage page = new AlbumDetailsPage();
        initializePage(page);
        AlbumDetailInfo info = createBasicInfo();
        Map<String, String> synonyms = new HashMap<String, String>();
        synonyms.put("k1", "AlbumSyn1");
        synonyms.put("k2", "Cool album");
        synonyms.put("k3", "Total cool album");
        info.setSynonymData(synonyms);
        page.fillPage(info);
        String synTxt = page.spanSynonyms.getInnerText();
        for (String syn : synonyms.values())
        {
            assertTrue("Synonym not found: " + syn, synTxt.contains(syn));
        }
    }

    /**
     * Tests fillPage() if there is a single artist.
     */
    public void testFillPageSingleArtist()
    {
        AlbumDetailInfo info = createBasicInfo();
        ArtistInfo art = new ArtistInfo();
        art.setArtistID(20110202223020L);
        art.setName("Harry Hirsch");
        info.setArtists(Collections.singletonList(art));
        AlbumDetailsPageTestImpl page = new AlbumDetailsPageTestImpl();
        MockPageManager pm = initializePage(page);
        pm.expectCreatePageSpecification(Pages.ARTISTDETAILS, null)
                .withParameter(art.getArtistID()).toToken();
        page.fillPage(info);
        checkArtistTable(page, 1, false);
        assertEquals("Wrong link text", art.getName(), page.lnkArtist.getText());
        assertEquals("Wrong link target",
                MockPageManager.defaultToken(Pages.ARTISTDETAILS),
                page.lnkArtist.getTargetHistoryToken());
        assertTrue("Link not visible", page.lnkArtist.isVisible());
        assertFalse("Got label for multiple artists",
                page.labMultiArtists.isVisible());
        pm.verify();
        checkTableContent(info.getArtists(), page.tabArtists);
    }

    /**
     * Tests fillPage() if there are multiple artists.
     */
    public void testFillPageMultipleArtists()
    {
        AlbumDetailInfo info = createBasicInfo();
        final int artistCount = 8;
        List<ArtistInfo> artists = new ArrayList<ArtistInfo>(artistCount);
        for (int i = 0; i < artistCount; i++)
        {
            ArtistInfo ai = new ArtistInfo();
            ai.setArtistID(Long.valueOf(i));
            ai.setName("Artist" + i);
            artists.add(ai);
        }
        info.setArtists(artists);
        AlbumDetailsPageTestImpl page = new AlbumDetailsPageTestImpl();
        initializePage(page);
        page.fillPage(info);
        checkArtistTable(page, artistCount, true);
        assertFalse("Link is visible", page.lnkArtist.isVisible());
        assertTrue("Label for multiple artists not visible",
                page.labMultiArtists.isVisible());
        assertNull("Got a target", page.lnkArtist.getTargetHistoryToken());
        checkTableContent(info.getArtists(), page.tabArtists);
    }

    /**
     * Tests whether the songs are correctly displayed.
     */
    public void testFillPageSongs()
    {
        AlbumDetailInfo info = new AlbumDetailInfo();
        final int songCount = 16;
        List<SongInfo> songs = new ArrayList<SongInfo>(songCount);
        for (int i = 0; i < songCount; i++)
        {
            SongInfo song = new SongInfo();
            song.setSongID(String.valueOf(i));
            song.setName("Song" + i);
            songs.add(song);
        }
        info.setSongs(songs);
        AlbumDetailsPageTestImpl page = new AlbumDetailsPageTestImpl();
        initializePage(page);
        page.fillPage(info);
        checkSongTable(page, songCount, true);
        assertSame("Wrong song list", songs, page.getSongs());
    }

    /**
     * Tests whether the page can be cleared.
     */
    public void testClearPage()
    {
        AlbumDetailsPage page = new AlbumDetailsPage();
        initializePage(page);
        page.fillPage(createBasicInfo());
        page.clearPage();
        assertEmpty("Got a name", page.spanName.getInnerText());
        assertEmpty("Got a creation date", page.spanCreationDate.getInnerText());
        assertEmpty("Got a duration", page.spanDuration.getInnerText());
        assertEmpty("Got a year", page.spanYear.getInnerText());
        assertEmpty("Got synonyms", page.spanSynonyms.getInnerText());
        checkArtistTable(page, 0, false);
        checkSongTable(page, 0, false);
    }

    /**
     * A test implementation of the details page class which supports mocking
     * the artist and song tables.
     */
    private static class AlbumDetailsPageTestImpl extends AlbumDetailsPage
    {
        /** A list with the songs passed to the songs table. */
        private List<SongInfo> songs;

        /**
         * Returns the songs to be displayed by the songs table.
         *
         * @return the songs
         */
        public List<SongInfo> getSongs()
        {
            return songs;
        }

        /**
         * Returns a mock table model which stores the passed in songs in an
         * internal member field.
         *
         * @return the mock table model
         */
        @Override
        SongGridTableModel getSongTableModel()
        {
            return new SongGridTableModel(tabSongs, new MockPageManager())
            {
                @Override
                public void initData(List<SongInfo> data)
                {
                    songs = data;
                }
            };
        }
    }
}
