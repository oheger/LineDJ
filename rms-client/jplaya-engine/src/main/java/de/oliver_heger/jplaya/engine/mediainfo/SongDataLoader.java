package de.oliver_heger.jplaya.engine.mediainfo;

import de.oliver_heger.mediastore.service.SongData;

/**
 * <p>
 * Definition of an interface for extracting media information from an audio
 * file.
 * </p>
 * <p>
 * This interface is used to obtain ID3 tags from MP3 audio files. Usage is
 * pretty simple. There is a single method which is passed the URI of an audio
 * file and which returns a corresponding {@code SongData} object. It is up to a
 * concrete implementation how the media information is actually obtained.
 * </p>
 *
 * @author Oliver Heger
 * @version $Id: $
 */
public interface SongDataLoader
{
    /**
     * Obtains an object with song information from the audio file with the
     * specified URI. If the file format is supported, a {@code SongData} object
     * should be created and populated with the relevant information. Otherwise,
     * <b>null</b> should be returned. An implementation should not throw an
     * exception. Access to media information is an optional operation, so it
     * should not break the calling application.
     *
     * @param uri the URI to the media file in question
     * @return a {@code SongData} object with information about the media file
     *         or <b>null</b> if no data can be extracted
     */
    SongData extractSongData(String uri);
}
