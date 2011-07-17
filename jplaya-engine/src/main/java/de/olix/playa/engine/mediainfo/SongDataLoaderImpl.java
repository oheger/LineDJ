package de.olix.playa.engine.mediainfo;

import java.io.IOException;
import java.math.BigInteger;
import java.util.HashMap;
import java.util.Map;

import javax.sound.sampled.AudioFileFormat;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.UnsupportedAudioFileException;

import javazoom.jl.decoder.Bitstream;
import javazoom.jl.decoder.BitstreamException;
import javazoom.jl.decoder.Header;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.commons.vfs.FileContent;
import org.apache.commons.vfs.FileObject;
import org.apache.commons.vfs.FileSystemManager;

import de.oliver_heger.mediastore.service.ObjectFactory;
import de.oliver_heger.mediastore.service.SongData;

/**
 * <p>
 * A default implementation of the {@code SongDataLoader} interface.
 * </p>
 * <p>
 * This implementation uses Java sound and some proprietary code from the
 * <em>javazoom</em> library to extract a set of ID3 tags which can be
 * transformed to a {@code SongData} object.
 * </p>
 *
 * @author Oliver Heger
 * @version $Id: $
 */
public class SongDataLoaderImpl implements SongDataLoader
{
    /** Constant for the title key. */
    public static final String KEY_TITLE = "title";

    /** Constant for the interpret key. */
    public static final String KEY_INTERPRET = "author";

    /** Constant for the duration key. The value of this property is a Long. */
    public static final String KEY_DURATION = "duration";

    /** Constant for the album key. Here the name of the album is stored. */
    public static final String KEY_ALBUM = "album";

    /**
     * Constant for the track key identifying the track number. Note that the
     * value is a string because it may contain arbitrary information like
     * 01/10.
     */
    public static final String KEY_TRACK = "mp3.id3tag.track";

    /** Constant for the inception year key. */
    public static final String KEY_YEAR = "date";

    /** Constant for the duration property if it is stored in an ID3 tag. */
    private static final String KEY_ID3_DURATION = "mp3.id3tag.length";

    /** Constant for the milliseconds factor. */
    private static final double MILLIS = 1000;

    /** The logger. */
    private Log log = LogFactory.getLog(getClass());

    /** The file system manager. */
    private final FileSystemManager fileSystemManager;

    /** A factory for creating song data objects. */
    private final ObjectFactory factory;

    /**
     * Creates a new instance of {@code SongDataLoaderImpl} and sets the virtual
     * file system manager.
     *
     * @param fsm the {@code FileSystemManager} (must not be <b>null</b>)
     * @throws IllegalArgumentException if the manager is <b>null</b>
     */
    public SongDataLoaderImpl(FileSystemManager fsm)
    {
        if (fsm == null)
        {
            throw new IllegalArgumentException(
                    "FileSystemManager must not be null!");
        }

        fileSystemManager = fsm;
        factory = new ObjectFactory();
    }

    /**
     * Returns a {@code SongData} object for the media file with the specified
     * URI. This method delegates to {@link #getProperties(String)} and
     * populates the {@code SongData} instance from the map with properties. If
     * this is not possible or if an exception is thrown, result is <b>null</b>.
     *
     * @param uri the URI of the media file in question (must not be
     *        <b>null</b>)
     * @return the {@code SongData} object with information about this file
     * @throws IllegalArgumentException if no URI is specified
     */
    @Override
    public SongData extractSongData(String uri)
    {
        try
        {
            Map<String, Object> props = getProperties(uri);
            return createSongDataFromProperties(props);
        }
        catch (IOException ioex)
        {
            return null;
        }
    }

    /**
     * Returns the properties for the specified media file.
     *
     * @param mediaFile the URI to the media file
     * @return a map with the properties for this media file
     * @throws IOException if an IO error occurs
     * @throws IllegalArgumentException if the passed in URI is <b>null</b>
     */
    public Map<String, Object> getProperties(String mediaFile)
            throws IOException
    {
        if (mediaFile == null)
        {
            throw new IllegalArgumentException("URL to file must not be null!");
        }

        FileObject file = fileSystemManager.resolveFile(mediaFile);
        FileContent content = file.getContent();
        try
        {
            AudioFileFormat format =
                    AudioSystem.getAudioFileFormat(content.getInputStream());
            Map<String, Object> data =
                    new HashMap<String, Object>(format.properties());
            determineDuration(data, content);

            return data;
        }
        catch (UnsupportedAudioFileException usafex)
        {
            throw new IOException("Unsupported audio file: " + mediaFile);
        }
    }

    /**
     * Determines the duration of the media file. This implementation first
     * checks whether the duration can be obtained from the properties. If not,
     * {@link #fetchDuration(FileContent)} will be called for determining the
     * duration from the media file.
     *
     * @param props the properties
     * @param content the content of the media file in question
     * @throws IOException if an error occurs
     */
    protected void determineDuration(Map<String, Object> props,
            FileContent content) throws IOException
    {
        Object durationTag = props.get(KEY_ID3_DURATION);
        Long duration;

        if (durationTag != null)
        {
            // transform duration into long
            duration = Long.valueOf(String.valueOf(durationTag));
        }
        else
        {
            duration = fetchDuration(content);
        }

        props.put(KEY_DURATION, duration);
    }

    /**
     * Determines the playback duration of the specified media file. This method
     * is called if the duration cannot be obtained from the properties.
     *
     * @param content the content of the media file
     * @return the duration in milliseconds
     * @throws IOException
     */
    protected long fetchDuration(FileContent content) throws IOException
    {
        Bitstream bis = new Bitstream(content.getInputStream());
        long length = 0;

        try
        {
            Header header;
            while ((header = bis.readFrame()) != null)
            {
                length += header.ms_per_frame();
                bis.closeFrame();
            }
        }
        catch (BitstreamException bisex)
        {
            log.error("BitstreamException when determining duration", bisex);
            throw new IOException("Error when determining duration: " + bisex);
        }
        finally
        {
            try
            {
                bis.close();
            }
            catch (BitstreamException bisex)
            {
                log.warn("Error when closing bit stream", bisex);
            }
        }

        return length;
    }

    /**
     * Transforms the specified map with media information to a {@code SongData}
     * object. This method is called {@link #extractSongData(String)}. It copies
     * the properties corresponding to the fields of a {@code SongData} object.
     *
     * @param props the map with properties
     * @return the populated {@code SongData} object
     */
    protected SongData createSongDataFromProperties(Map<String, Object> props)
    {
        SongData data = newSongData();
        data.setAlbumName(strProperty(props, KEY_ALBUM));
        data.setArtistName(strProperty(props, KEY_INTERPRET));
        data.setName(strProperty(props, KEY_TITLE));
        Long dur = (Long) props.get(KEY_DURATION);
        if (dur != null)
        {
            data.setDuration(BigInteger.valueOf(Math.round(dur.longValue()
                    / MILLIS)));
        }
        data.setInceptionYear(parseNumericProperty(props.get(KEY_YEAR)));
        data.setTrackNo(parseNumericProperty(props.get(KEY_TRACK)));
        return data;
    }

    /**
     * Creates a new {@code SongData} object.
     *
     * @return the new object
     */
    protected SongData newSongData()
    {
        return factory.createSongData();
    }

    /**
     * Parses a numeric property in string form. If the property is undefined
     * (i.e. <b>null</b>) or does not start with a valid numeric value,
     * <b>null</b> is returned. Otherwise, the leading part of the value that
     * consists only of digits is parsed into a numeric value.
     *
     * @param val the value of the property as a string
     * @return the corresponding numeric value
     */
    private static BigInteger parseNumericProperty(Object val)
    {
        if (val == null)
        {
            return null;
        }

        String value = val.toString();
        int len = value.length();
        int pos = 0;
        while (pos < len && Character.isDigit(value.charAt(pos)))
        {
            pos++;
        }
        if (pos < 1)
        {
            return null;
        }

        String numVal = (pos == len) ? value : value.substring(0, pos);
        return new BigInteger(numVal);
    }

    /**
     * Helper method for converting an object property to a string. If the
     * property is undefined, <b>null</b> is returned.
     *
     * @param props the map with properties
     * @param key the key in question
     * @return the string value of this property
     */
    private static String strProperty(Map<String, Object> props, String key)
    {
        Object value = props.get(key);
        return (value != null) ? value.toString() : null;
    }
}
