package de.oliver_heger.mediastore.server.sync;

import de.oliver_heger.mediastore.server.NotLoggedInException;
import de.oliver_heger.mediastore.service.ArtistData;

/**
 * <p>The implementation of the {@link MediaSyncService} interface.</p>
 *
 * @author Oliver Heger
 * @version $Id: $
 */
public class MediaSyncServiceImpl implements MediaSyncService
{

    @Override
    public SyncResult<Long> syncArtist(ArtistData artist)
            throws NotLoggedInException
    {
        // TODO Auto-generated method stub
        throw new UnsupportedOperationException("Not yet implemented!");
    }

}
