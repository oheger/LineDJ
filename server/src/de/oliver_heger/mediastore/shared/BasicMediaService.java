package de.oliver_heger.mediastore.shared;

import com.google.gwt.user.client.rpc.RemoteService;

import de.oliver_heger.mediastore.shared.model.ArtistDetailInfo;

/**
 * <p>The service interface of the basic media service.</p>
 * <p>The <em>basic media service</em> provides a set of standard operations on
 * the media types supported. This includes querying detail information, removing
 * entities, handling synonyms, etc.</p>
 *
 * @author Oliver Heger
 * @version $Id: $
 */
public interface BasicMediaService extends RemoteService
{
    ArtistDetailInfo fetchArtistDetails(long artistID);
}
