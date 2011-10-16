package de.oliver_heger.mediastore.client.pages.overview;

import com.google.gwt.user.client.rpc.AsyncCallback;

import de.oliver_heger.mediastore.client.BasicMediaServiceTestImpl;

/**
 * A mock service implementation which allows mocking the remove methods.
 *
 * @author Oliver Heger
 * @version $Id: $
 */
class RemoveMediaServiceMock extends BasicMediaServiceTestImpl
{
    /** Constant for a stub callback for remove operations. */
    private static final AsyncCallback<Boolean> REMOVE_CALLBACK =
            new AsyncCallback<Boolean>()
            {
                @Override
                public void onFailure(Throwable caught)
                {
                    throw new UnsupportedOperationException(
                            "Unexpected method call!");
                }

                @Override
                public void onSuccess(Boolean result)
                {
                    throw new UnsupportedOperationException(
                            "Unexpected method call!");
                }
            };

    /** The ID of the artist to be removed. */
    private Long removeArtistID;

    /** The ID of the album to be removed. */
    private Long removeAlbumID;

    /** The ID of the song to be removed. */
    private String removeSongID;

    /**
     * Returns the ID of the artist to be removed.
     *
     * @return the artist ID
     */
    public Long getRemoveArtistID()
    {
        return removeArtistID;
    }

    /**
     * Returns the ID of the album to be removed.
     *
     * @return the album ID
     */
    public Long getRemoveAlbumID()
    {
        return removeAlbumID;
    }

    /**
     * Returns the ID of the song to be removed.
     *
     * @return the song ID
     */
    public String getRemoveSongID()
    {
        return removeSongID;
    }

    @Override
    public void removeArtist(long artistID, AsyncCallback<Boolean> callback)
    {
        checkRemoveInvocation(callback);
        removeArtistID = artistID;
    }

    @Override
    public void removeSong(String songID, AsyncCallback<Boolean> callback)
    {
        checkRemoveInvocation(callback);
        removeSongID = songID;
    }

    @Override
    public void removeAlbum(long albumID, AsyncCallback<Boolean> callback)
    {
        checkRemoveInvocation(callback);
        removeAlbumID = albumID;
    }

    /**
     * Returns a callback implementation to be used for tests of remove
     * operations.
     *
     * @return the callback for test remove operations
     */
    public static AsyncCallback<Boolean> getRemoveCallback()
    {
        return REMOVE_CALLBACK;
    }

    /**
     * Checks whether a remove invocation is valid. The callback is checked and
     * whether no other element has been removed before.
     *
     * @param callback the callback
     */
    private void checkRemoveInvocation(AsyncCallback<Boolean> callback)
    {
        OverviewPageTestGwt.assertNull("Already an artist removed",
                removeArtistID);
        OverviewPageTestGwt.assertNull("Already an album removed",
                removeAlbumID);
        OverviewPageTestGwt.assertNull("Already a song removed", removeSongID);
        OverviewPageTestGwt.assertSame("Wrong callback", getRemoveCallback(),
                callback);
    }
}
