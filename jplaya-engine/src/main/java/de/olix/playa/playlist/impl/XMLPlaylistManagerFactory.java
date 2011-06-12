package de.olix.playa.playlist.impl;

import java.io.IOException;

import de.olix.playa.playlist.CurrentPositionInfo;
import de.olix.playa.playlist.PlaylistManager;
import de.olix.playa.playlist.PlaylistManagerFactory;

public class XMLPlaylistManagerFactory implements PlaylistManagerFactory
{

    @Override
    public PlaylistManager createPlaylistManager()
    {
        // TODO Auto-generated method stub
        throw new UnsupportedOperationException("Not yet implemented!");
    }

    /**
     * Saves the current state of the specified {@code PlaylistManager}. This method
     * is called by the {@code XMLPlaylistManager} class if the manager's state
     * is to be saved. This
     * implementation stores sufficient information in an XML file to continue
     * the playlist at the current position. If a {@code CurrentPositionInfo} object
     * is specified, the current position in the currently played song is stored.
     * Otherwise, it is expected that the current song should be played from the
     * beginning.
     * @param manager the playlist manager
     * @param posInfo the current position in the current song
     * @param currentIndex the index of the current song in the playlist; a value &lt; 0 means that the playlist is finished
     * @throws IOException if an IO exception occurs
     */
    public void saveState(XMLPlaylistManager manager, CurrentPositionInfo posInfo, int currentIndex) throws IOException
    {
        //TODO implementation
        throw new UnsupportedOperationException("Not yet implemented!");
    }
}
