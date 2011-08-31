package de.oliver_heger.mediastore.shared.model;

import java.util.List;
import java.util.Map;
import java.util.Set;

import de.oliver_heger.mediastore.shared.ObjectUtils;

/**
 * <p>
 * A specialized data object with detailed information about an artist.
 * </p>
 * <p>
 * This class extends the plain {@link ArtistInfo} class. It also provides data
 * about entities associated with the artist, e.g. synonyms, songs, etc. An
 * instance of this class is used to populate a detail view of an artist.
 * </p>
 *
 * @author Oliver Heger
 * @version $Id: $
 */
public class ArtistDetailInfo extends ArtistInfo implements HasSynonyms
{
    /**
     * The serial version UID.
     */
    private static final long serialVersionUID = 20110831L;

    /** A map with the synonyms defined for this artist. */
    private Map<String, String> synonyms;

    /** A list with info objects for the songs associated with this artist. */
    private List<SongInfo> songs;

    /** A list with info objects for the albums associated with this artist. */
    private List<AlbumInfo> albums;

    /**
     * Returns a string representation of this artist's ID.
     *
     * @return the ID of this artist as string
     */
    @Override
    public String getIDAsString()
    {
        Long id = getArtistID();
        return (id != null) ? id.toString() : null;
    }

    /**
     * Returns a collection with the synonyms of this artist.
     *
     * @return the synonyms of this artist
     */
    @Override
    public Set<String> getSynonyms()
    {
        return null;
    }

    /**
     * Sets a collection with the synonyms of this artist.
     *
     * @param synonyms the synonyms of this artist
     * @deprecated use {@link #setSynonymData(Map)}
     */
    @Deprecated
    public void setSynonyms(Set<String> synonyms)
    {
    }

    /**
     * Returns a map with information about the synonyms of this artist. Return
     * value is never <b>null</b>.
     *
     * @return a map with synonym data
     */
    @Override
    public Map<String, String> getSynonymData()
    {
        return ObjectUtils.nonNullMap(synonyms);
    }

    /**
     * Sets the map with information about the synonyms of this artist.
     *
     * @param data the map with synonym data
     */
    public void setSynonymData(Map<String, String> data)
    {
        synonyms = data;
    }

    /**
     * Returns a list with {@link SongInfo} objects for the songs associated
     * with this artist.
     *
     * @return a list with associated song info objects
     */
    public List<SongInfo> getSongs()
    {
        return ObjectUtils.nonNullList(songs);
    }

    /**
     * Sets a list with {@link SongInfo} objects for the songs associated with
     * this artist.
     *
     * @param songs the list with associated song info objects
     */
    public void setSongs(List<SongInfo> songs)
    {
        this.songs = songs;
    }

    /**
     * Returns a list of info objects for the albums this artist occurs on.
     *
     * @return a list of related albums
     */
    public List<AlbumInfo> getAlbums()
    {
        return ObjectUtils.nonNullList(albums);
    }

    /**
     * Sets a list with info objects for albums this artist occurs on.
     *
     * @param albums the list with the related albums
     */
    public void setAlbums(List<AlbumInfo> albums)
    {
        this.albums = albums;
    }
}
