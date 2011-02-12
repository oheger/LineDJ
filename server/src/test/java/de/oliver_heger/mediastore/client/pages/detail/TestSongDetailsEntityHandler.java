package de.oliver_heger.mediastore.client.pages.detail;

import org.easymock.EasyMock;
import org.junit.Before;
import org.junit.Test;

import com.google.gwt.user.client.rpc.AsyncCallback;

import de.oliver_heger.mediastore.shared.BasicMediaServiceAsync;
import de.oliver_heger.mediastore.shared.SynonymUpdateData;
import de.oliver_heger.mediastore.shared.model.SongDetailInfo;

/**
 * Test class for {@code SongDetailsEntityHandler}.
 *
 * @author Oliver Heger
 * @version $Id: $
 */
public class TestSongDetailsEntityHandler
{
    /** Constant for the ID of a test song. */
    private static final String SONG_ID = "ID_of_a_test_song";

    /** The handler to be tested. */
    private SongDetailsEntityHandler handler;

    @Before
    public void setUp() throws Exception
    {
        handler = new SongDetailsEntityHandler();
    }

    /**
     * Helper method for creating a callback mock of the specified type.
     *
     * @param <T> the type of the mock
     * @return the callback mock
     */
    private static <T> AsyncCallback<T> createCallbackMock()
    {
        @SuppressWarnings("unchecked")
        AsyncCallback<T> result = EasyMock.createMock(AsyncCallback.class);
        return result;
    }

    /**
     * Tests whether detail information about a song can be fetched.
     */
    @Test
    public void testFetchDetails()
    {
        BasicMediaServiceAsync service =
                EasyMock.createMock(BasicMediaServiceAsync.class);
        AsyncCallback<SongDetailInfo> callback = createCallbackMock();
        service.fetchSongDetails(SONG_ID, callback);
        EasyMock.replay(service, callback);
        handler.fetchDetails(service, SONG_ID, callback);
        EasyMock.verify(service, callback);
    }

    /**
     * Tests whether the synonyms of a song can be manipulated.
     */
    @Test
    public void testUpdateSynonyms()
    {
        BasicMediaServiceAsync service =
                EasyMock.createMock(BasicMediaServiceAsync.class);
        AsyncCallback<Void> callback = createCallbackMock();
        SynonymUpdateData upData = new SynonymUpdateData();
        service.updateSongSynonyms(SONG_ID, upData, callback);
        EasyMock.replay(service, callback);
        handler.updateSynonyms(service, SONG_ID, upData, callback);
        EasyMock.verify(service, callback);
    }
}
