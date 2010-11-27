package de.oliver_heger.mediastore.server.search;

import de.oliver_heger.mediastore.server.model.ArtistEntity;
import de.oliver_heger.mediastore.service.utils.DTOTransformer;
import de.oliver_heger.mediastore.shared.model.ArtistInfo;

/**
 * <p>
 * A specialized implementation of the {@link SearchConverter} interface for
 * artists.
 * </p>
 * <p>
 * This class converts entity objects of type {@link ArtistEntity} to data
 * objects of type {@link ArtistInfo}. Implementation note: The converter does
 * not need any state, so a single instance is sufficient which can be shared.
 * Therefore an enumeration type is used for the implementation which ensures
 * that there is only a single instance.
 * </p>
 *
 * @author Oliver Heger
 * @version $Id: $
 */
public enum ArtistSearchConverter implements
        SearchConverter<ArtistEntity, ArtistInfo>
{
    /** The single instance of this class. */
    INSTANCE;

    /**
     * Converts an artist entity object to a data object.
     *
     * @param e the entity to be converted
     * @return the result of the conversion
     * @throws IllegalArgumentException if the entity is <b>null</b>
     */
    @Override
    public ArtistInfo convert(ArtistEntity e)
    {
        ArtistInfo info = new ArtistInfo();
        DTOTransformer.transform(e, info);
        info.setArtistID(e.getId());

        return info;
    }
}
