package de.oliver_heger.mediastore.shared.model;

import java.util.List;
import java.util.Map;

import de.oliver_heger.mediastore.shared.ObjectUtils;

/**
 * <p>
 * A specialized data object with detail information about an album.
 * </p>
 * <p>
 * This class extends the plain {@link AlbumInfo} class by providing more
 * detailed information about an album, e.g. synonyms or songs. This information
 * is used to populate the details page for albums.
 * </p>
 *
 * @author Oliver Heger
 * @version $Id: $
 */
public class AlbumDetailInfo extends AlbumInfo implements HasSynonyms
{
    /**
     * The serial version UID.
     */
    private static final long serialVersionUID = 20110831L;

    /** A map with information about the synonyms of this album. */
    private Map<String, String> synonyms;

    /** A list with the songs associated for this album. */
    private List<SongInfo> songs;

    /** A list with the artists that occur on this album. */
    private List<ArtistInfo> artists;

    /**
     * Returns a string representation of this object's ID.
     *
     * @return a string with the album ID
     */
    @Override
    public String getIDAsString()
    {
        Long id = getAlbumID();
        return (id != null) ? id.toString() : null;
    }

    /**
     * Returns a map with information about the synonyms of this album. This
     * method never returns <b>null</b>.
     *
     * @return a map with synonym data
     */
    @Override
    public Map<String, String> getSynonymData()
    {
        return ObjectUtils.nonNullMap(synonyms);
    }

    /**
     * Sets synonym data for this album.
     *
     * @param data the map with synonym data
     */
    public void setSynonymData(Map<String, String> data)
    {
        synonyms = data;
    }

    /**
     * Returns a list with the songs of this album. Note that the list may be
     * incomplete; only the songs stored in this database are taken into
     * account.
     *
     * @return a list with the songs
     */
    public List<SongInfo> getSongs()
    {
        return ObjectUtils.nonNullList(songs);
    }

    /**
     * Sets the list with the songs of this album.
     *
     * @param songs the list with songs
     */
    public void setSongs(List<SongInfo> songs)
    {
        this.songs = songs;
    }

    /**
     * Returns a list with the artists related to this album. This list contains
     * the artists associated with the songs on this album.
     *
     * @return the artists of this album
     */
    public List<ArtistInfo> getArtists()
    {
        return ObjectUtils.nonNullList(artists);
    }

    /**
     * Sets the list with the artists of this album.
     *
     * @param artists the list with artists
     */
    public void setArtists(List<ArtistInfo> artists)
    {
        this.artists = artists;
    }
}
