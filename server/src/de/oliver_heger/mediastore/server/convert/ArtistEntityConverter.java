package de.oliver_heger.mediastore.server.convert;

import de.oliver_heger.mediastore.server.model.ArtistEntity;
import de.oliver_heger.mediastore.service.utils.DTOTransformer;
import de.oliver_heger.mediastore.shared.model.ArtistInfo;

/**
 * <p>
 * A specialized implementation of the {@link EntityConverter} interface for
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
public enum ArtistEntityConverter implements
        EntityConverter<ArtistEntity, ArtistInfo>
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
        convert(e, info);

        return info;
    }

    /**
     * Converts the specified artist entity object to the specified artist info
     * object. The properties of the entity are extracted and copied into the
     * corresponding fields of the info object.
     *
     * @param e the artist entity object
     * @param info the target info object
     * @throws IllegalArgumentException if one of the objects is <b>null</b>
     */
    public void convert(ArtistEntity e, ArtistInfo info)
    {
        DTOTransformer.transform(e, info);
        info.setArtistID(e.getId());
    }
}
