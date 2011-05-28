package de.olix.playa.playlist;

import java.io.IOException;
import java.net.URL;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

import de.olix.playa.engine.AudioInfo;

/**
 * <p>
 * A default implementation of the <code>SongInfoProvider</code> interface.
 * </p>
 * <p>
 * This implementation uses the properties returned by an
 * <code>{@link AudioInfo}</code> object to construct a
 * <code>{@link SongInfo}</code> object for a specified media file.
 * </p>
 *
 * @author Oliver Heger
 * @version $Id$
 */
public class DefaultSongInfoProvider implements SongInfoProvider
{
    /** Stores the audio info object for obtaining the properties. */
    private AudioInfo audioInfo;

    /**
     * Creates a new instance of <code>DefaultSongInfoProvider</code>.
     */
    public DefaultSongInfoProvider()
    {
        setAudioInfo(new AudioInfo());
    }

    /**
     * Returns the internally used <code>AudioInfo</code> object.
     *
     * @return the audio info object
     */
    public AudioInfo getAudioInfo()
    {
        return audioInfo;
    }

    /**
     * Sets the internally used <code>AudioInfo</code> object.
     *
     * @param audioInfo the audio info object to be used (must not be <b>null</b>)
     * @throws IllegalArgumentException if the info object is <b>null</b>
     */
    public void setAudioInfo(AudioInfo audioInfo)
    {
        if (audioInfo == null)
        {
            throw new IllegalArgumentException(
                    "AudioInfo object must not be null!");
        }
        this.audioInfo = audioInfo;
    }

    /**
     * Returns a <code>SongInfo</code> object for the specified media file.
     *
     * @param mediaFile the URL to the affected file
     * @return a <code>SongInfo</code> object for the properties of this media
     * file
     * @throws PlaylistException if an error occurs
     * @throws IllegalArgumentException if <b>null</b> is passed in
     */
    public SongInfo getSongInfo(URL mediaFile) throws PlaylistException
    {
        try
        {
            return new SongInfoImpl(getAudioInfo().getPropertiesEx(mediaFile));
        }
        catch (IOException ioex)
        {
            throw new PlaylistException(
                    "IO error when accessing properties of media file "
                            + mediaFile, ioex);
        }
    }

    /**
     * A simple default implementation of the <code>SongInfo</code> interface.
     * Objects of this class will be returned by the
     * <code>SongInfoProvider</code> implementation.
     */
    static class SongInfoImpl implements SongInfo
    {
        /** Stores further properties. */
        private Map<String, Object> props;

        /**
         * Creates a new instance of <code>SongInfoImpl</code> and initializes
         * it with the given data map.
         *
         * @param data a map with the properties obtained from an audio file
         */
        public SongInfoImpl(Map<String, Object> data)
        {
            props = new HashMap<String, Object>(data);
        }

        /**
         * Returns the playback duration in microseconds.
         *
         * @return the playback duration
         */
        public Long getDuration()
        {
            return (Long) props.get(AudioInfo.KEY_DURATION);
        }

        /**
         * Returns the interpret.
         *
         * @return the interpret
         */
        public String getInterpret()
        {
            return (String) props.get(AudioInfo.KEY_INTERPRET);
        }

        /**
         * Returns the title.
         *
         * @return the title
         */
        public String getTitle()
        {
            return (String) props.get(AudioInfo.KEY_TITLE);
        }

        /**
         * Returns a map with additional properties.
         *
         * @return properties
         */
        public Map<String, Object> properties()
        {
            return Collections.unmodifiableMap(props);
        }
    }
}
