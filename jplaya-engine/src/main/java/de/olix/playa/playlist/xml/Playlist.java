package de.olix.playa.playlist.xml;

import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.zip.CRC32;

/**
 * <p>
 * A class representing a playlist.
 * </p>
 * <p>
 * This class provides a thin wrapper over a collection of
 * <code>{@link PlaylistItem}</code> objects. In addition there are some
 * methods for manipulating the order of the contained elements.
 * </p>
 * <p>
 * Implementation note: <code>Playlist</code> objects are not thread-safe.
 * They should be manipulated by a single thread only.
 * </p>
 * 
 * @author Oliver Heger
 * @version $Id$
 */
public class Playlist
{
	/** Stores the underlying list. */
	private List<PlaylistItem> items;

	/** Stores the ID of this playlist. */
	private String listID;

	/**
     * Creates a new instance of <code>Playlist</code>.
     */
	public Playlist()
	{
		items = new LinkedList<PlaylistItem>();
	}

	/**
     * Creates a new instance of <code>Playlist</code> and copies the content
     * of another playlist.
     * 
     * @param c the playlist to be copied
     */
	public Playlist(Playlist c)
	{
		this();
		addItems(c);
	}

	/**
     * Adds a new item to this playlist.
     * 
     * @param item the item to be added (must not be <b>null</b>)
     */
	public void addItem(PlaylistItem item)
	{
		if (item == null)
		{
			throw new IllegalArgumentException("Item to add must not be null!");
		}
		items.add(item);
		listID = null;
	}

	/**
     * Adds all items stored in the given playlist to this playlist.
     * 
     * @param list the list to be copied
     */
	public void addItems(Playlist list)
	{
		if (list == null)
		{
			throw new IllegalArgumentException(
					"Playlist to add must not be null!");
		}
		for (Iterator<PlaylistItem> it = list.items(); it.hasNext();)
		{
			addItem(it.next());
		}
	}

	/**
     * Returns the number of elements stored in this playlist.
     * 
     * @return the number of contained items
     */
	public int size()
	{
		return items.size();
	}

	/**
     * Returns a flag whether this playlist is empty.
     * 
     * @return a flag if this list is empty
     */
	public boolean isEmpty()
	{
		return items.isEmpty();
	}

	/**
     * Returns an iterator with the contained playlist items.
     * 
     * @return an iterator with the elements stored in this playlist
     */
	public Iterator<PlaylistItem> items()
	{
		return items.iterator();
	}

	/**
     * Returns the first item in this playlist and removes it.
     * 
     * @return the first item in this playlist
     * @throws NoSuchElementException if the playlist is empty
     */
	public PlaylistItem take() throws NoSuchElementException
	{
		if (isEmpty())
		{
			throw new NoSuchElementException("No items in playlist!");
		}
		listID = null;
		return items.remove(0);
	}

	/**
     * Returns an ID for this playlist. This ID is calculated as a checksum over
     * all contained playlist items. It should be unique with a high likelyhood.
     * 
     * @return an ID for this playlist
     */
	public String getID()
	{
		if (listID == null)
		{
			listID = calcID();
		}
		return listID;
	}

	/**
     * Sorts this playlist based on the natural order of its items. This means
     * that the items will be sorted by their relative path and then by their
     * name. If typical naming conventions for sound files are met, this will
     * sort the songs by their interpret and album.
     */
	public void sort()
	{
		Collections.sort(items);
	}

	/**
     * Re-orders this playlist using a random order.
     */
	public void shuffle()
	{
		Collections.shuffle(items);
	}

	/**
     * Calculates an ID for this list.
     * 
     * @return the ID
     */
	protected String calcID()
	{
		CRC32 checksum = new CRC32();
		for (PlaylistItem item : items)
		{
			checksum.update(item.getPathName().getBytes());
		}
		return Long.toHexString(checksum.getValue());
	}
}
