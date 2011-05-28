package de.olix.playa.playlist.xml;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Collections;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Set;

import de.olix.playa.engine.AudioStreamData;
import de.olix.playa.engine.AudioStreamSource;
import de.olix.playa.engine.EndAudioStreamData;

/**
 * <p>
 * An internally used implementation of the <code>AudioStreamSource</code>
 * interface that is backed by a <code>Playlist</code> object.
 * </p>
 * <p>
 * This class obtains the next audio streams to be played from a playlist. It
 * also provides an implementation of the <code>AudioStreamData</code>
 * interface that operates on a <code>PlaylistItem</code> object.
 * </p>
 * <p>
 * The class internally stores the IDs of the streams that have been requested.
 * This allows it to correctly update a playlist when end playback or start
 * playback callbacks arrive.
 * </p>
 * 
 * @author Oliver Heger
 * @version $Id: PlaylistInfo.java 58 2006-11-13 19:22:50Z hacker $
 */
class PlaylistStreamSource implements AudioStreamSource
{
	/** Stores the current iterator. */
	private Iterator<PlaylistItem> iterator;

	/** Stores the root of the source directory structure. */
	private File rootFolder;

	/** Stores the active stream IDs. */
	private Set<Object> activeStreams;

	/**
     * Creates a new instance of <code>PlaylistStreamSource</code> and
     * initializes it.
     * 
     * @param list the playlist to be played
     * @param musicDir the root directory with the audio files
     */
	public PlaylistStreamSource(Playlist list, File musicDir)
	{
		Playlist c = new Playlist();
		c.addItems(list);
		iterator = c.items();
		rootFolder = musicDir;
		activeStreams = Collections.synchronizedSet(new HashSet<Object>());
	}

	/**
     * Returns the next audio stream data object. This is obtained from the
     * underlying playlist.
     * 
     * @return the next audio stream data object
     * @throws InterruptedException won't be thrown by this implementation
     */
	public AudioStreamData nextAudioStream() throws InterruptedException
	{
		if (iterator.hasNext())
		{
			PlaylistItem item = iterator.next();
			activeStreams.add(getFileForItem(item));
			return new PlaylistItemStreamData(item);
		}
		else
		{
			return new EndAudioStreamData();
		}
	}

	/**
     * Updates the playlist in reaction of a callback. This method will check
     * whether the passed in ID belongs to the active stream IDs. If this is the
     * case, all elements in the playlist before this element will be removed.
     * If the current callback is a playback end callback, the element itself
     * will also be removed.
     * 
     * @param list the playlist to be updated
     * @param streamID the affected stream ID
     * @param start a flag whether this is a start playback callback
     * @return a flag whether the passed in ID was valid
     */
	public boolean updatePlaylist(Playlist list, Object streamID, boolean start)
	{
		if (activeStreams.contains(streamID))
		{
			int count = findPosition(list, streamID);
			if (!start)
			{
				count++; // remove item itself
			}

			for (int i = 0; i < count; i++)
			{
				PlaylistItem item = list.take();
				activeStreams.remove(getFileForItem(item));
			}
			return true;
		}

		else
		{
			return false;
		}
	}

	/**
     * Finds the position of the specified stream in the given playlist.
     * 
     * @param list the playlist
     * @param streamID the stream ID
     * @return the index of this stream ID in the playlist
     */
	private int findPosition(Playlist list, Object streamID)
	{
		Iterator<PlaylistItem> it = list.items();
		int count = 0;
		while (true)
		{
			PlaylistItem item = it.next();
			if (streamID.equals(getFileForItem(item)))
			{
				break;
			}
			else
			{
				count++;
			}
		}
		return count;
	}

	/**
     * Returns an ID for the specified playlist item object. This will be the
     * associated audio file.
     * 
     * @param item the item
     * @return an ID for this item
     */
	private File getFileForItem(PlaylistItem item)
	{
		return item.getFile(rootFolder);
	}

	/**
     * An internally used implementation of the <code>AudioStreamData</code>
     * interface that is backed by a <code>PlaylistItem</code> object.
     */
	class PlaylistItemStreamData implements AudioStreamData
	{
		/** Stores the underlying item. */
		private PlaylistItem item;

		/**
         * Creates a new instance of <code>PlaylistItemStreamData</code> and
         * sets the underlying item object.
         * 
         * @param i the item
         */
		public PlaylistItemStreamData(PlaylistItem i)
		{
			item = i;
		}

		/**
         * Returns the stream's ID. This is the associated file object.
         * 
         * @return the ID
         */
		public Object getID()
		{
			return XMLPlaylistManager.fetchStreamID(item, rootFolder);
		}

		/**
         * Returns a name for this stream. This is the name of the associated
         * audio file.
         * 
         * @return the name
         */
		public String getName()
		{
			return item.getPathName();
		}

		/**
         * Returns the current position in the audio stream. This method is not
         * supported here, so always 0 is returned.
         * 
         * @return the position
         */
		public long getPosition()
		{
			return 0;
		}

		/**
         * Returns the stream.
         * 
         * @return the stream
         * @throws IOException if an error occurs
         */
		public InputStream getStream() throws IOException
		{
			return new FileInputStream(getItemFile());
		}

		/**
         * Returns the size of the stream.
         * 
         * @return the size
         */
		public long size()
		{
			return getItemFile().length();
		}

		/**
         * Returns the associated audio file.
         * 
         * @return the file
         */
		private File getItemFile()
		{
			return getFileForItem(item);
		}
	}
}
