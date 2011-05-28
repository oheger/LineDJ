package de.olix.playa.engine;

import java.io.IOException;
import java.net.URL;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

import javax.sound.sampled.AudioFileFormat;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.UnsupportedAudioFileException;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

import javazoom.jl.decoder.Bitstream;
import javazoom.jl.decoder.BitstreamException;
import javazoom.jl.decoder.Header;

/**
 * <p>
 * A class for obtaining information about an audio stream.
 * </p>
 * <p>
 * This class is able to extract information (e.g. mp3 tags) from a specified
 * audio stream. This information is returned in an unstructured map. Some keys
 * for central properties are defined as constants. Other properties are
 * directly obtained from the responsible audio provider.
 * </p>
 *
 * @author Oliver Heger
 * @version $Id$
 */
public class AudioInfo
{
    /** Constant for the title key. */
    public static final String KEY_TITLE = "title";

    /** Constant for the interpret key. */
    public static final String KEY_INTERPRET = "author";

    /** Constant for the duration key. The value of this property is a Long. */
    public static final String KEY_DURATION = "duration";

    /** Constant for the duration property if it is stored in an ID3 tag. */
    private static final String KEY_ID3_DURATION = "mp3.id3tag.length";

    /** The logger. */
    private Log log = LogFactory.getLog(getClass());

    /**
     * Returns the properties for the specified media file. If no properties can
     * be obtained for the given media file, an empty map will be returned.
     *
     * @param mediaFile the URL to the media file
     * @return a map with the properties for this media file
     * @throws IllegalArgumentException if the passed in URL is <b>null</b>
     */
    public Map<String, Object> getProperties(URL mediaFile)
    {
        try
        {
            return getPropertiesEx(mediaFile);
        }
        catch (IOException ioex)
        {
            log.info("Cannot obtain properties for audio file " + mediaFile,
                    ioex);
            return Collections.emptyMap();
        }
    }

    /**
     * Returns the properties for the specified media file and passes occurring
     * exceptions to the caller. This method works like
     * <code>getProperties()</code>, but if an IO error occurs, this
     * exception will be thrown to the caller.
     *
     * @param mediaFile the URL to the media file
     * @return a map with the properties for this media file
     * @throws IOException if an IO error occurs
     * @throws IllegalArgumentException if the passed in URL is <b>null</b>
     */
    public Map<String, Object> getPropertiesEx(URL mediaFile)
            throws IOException
    {
        if (mediaFile == null)
        {
            throw new IllegalArgumentException("URL to file must not be null!");
        }

        try
        {
            AudioFileFormat format = AudioSystem.getAudioFileFormat(mediaFile);
            Map<String, Object> data = new HashMap<String, Object>(format
                    .properties());
            determineDuration(data, mediaFile);

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
     * <code>fetchDuration()</code> will be called for determining the
     * duration from the media file.
     *
     * @param props the properties
     * @param mediaFile the URL to the media file
     * @throws IOException if an error occurs
     */
    protected void determineDuration(Map<String, Object> props, URL mediaFile)
            throws IOException
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
            duration = fetchDuration(mediaFile);
        }

        props.put(KEY_DURATION, duration);
    }

    /**
     * Determines the playback duration of the specified media file. This method
     * is called if the duration cannot be obtained from the properties.
     *
     * @param mediaFile the media file
     * @return the duration in milli seconds
     * @throws IOException
     */
    protected long fetchDuration(URL mediaFile) throws IOException
    {
        Bitstream bis = new Bitstream(mediaFile.openStream());
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
}
