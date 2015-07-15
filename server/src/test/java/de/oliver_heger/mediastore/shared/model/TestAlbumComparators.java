package de.oliver_heger.mediastore.shared.model;

import static de.oliver_heger.mediastore.RemoteMediaStoreTestHelper.checkComparator;

import org.junit.Test;

/**
 * Test class for {@code AlbumComparators}.
 *
 * @author Oliver Heger
 * @version $Id: $
 */
public class TestAlbumComparators
{
    /**
     * Helper method for testing the comparator for song names.
     *
     * @param name1 the first song name
     * @param name2 the second song name
     */
    private void checkNameComparator(String name1, String name2)
    {
        AlbumInfo info1 = new AlbumInfo();
        info1.setName(name1);
        AlbumInfo info2 = new AlbumInfo();
        info2.setName(name2);
        checkComparator(info1, info2, AlbumComparators.NAME_COMPARATOR);
    }

    /**
     * Tests the comparator for album names.
     */
    @Test
    public void testNameComparator()
    {
        checkNameComparator("A Album", "Z Album");
    }

    /**
     * Tests whether the name comparator ignores case.
     */
    @Test
    public void testNameComparatorCaseInsensitive()
    {
        checkNameComparator("A script for a Jester's tear", "Zodiac");
        checkNameComparator("a script for a Jester's tear", "zodiac");
        checkNameComparator("a script for a Jester's tear", "ZODIAC");
        checkNameComparator("A SCRIPT for a Jester's tear", "zodiac");
    }

    /**
     * Tests the duration comparator if both durations are defined.
     */
    @Test
    public void testDurationComparator()
    {
        AlbumInfo album1 = new AlbumInfo();
        AlbumInfo album2 = new AlbumInfo();
        album1.setDuration(100L);
        album2.setDuration(200L);
        checkComparator(album1, album2, AlbumComparators.DURATION_COMPARATOR);
    }

    /**
     * Tests the duration comparator if a duration is undefined.
     */
    @Test
    public void testDurationComparatorWithNull()
    {
        AlbumInfo album1 = new AlbumInfo();
        AlbumInfo album2 = new AlbumInfo();
        album2.setDuration(200L);
        checkComparator(album1, album2, AlbumComparators.DURATION_COMPARATOR);
    }

    /**
     * Tests the comparator for the number of songs.
     */
    @Test
    public void testSongCountComparator()
    {
        AlbumInfo album1 = new AlbumInfo();
        AlbumInfo album2 = new AlbumInfo();
        album1.setNumberOfSongs(10);
        album2.setNumberOfSongs(12);
        checkComparator(album1, album2, AlbumComparators.SONGCOUNT_COMPARATOR);
    }

    /**
     * Tests the year comparator if both years are defined.
     */
    @Test
    public void testYearComparator()
    {
        AlbumInfo album1 = new AlbumInfo();
        AlbumInfo album2 = new AlbumInfo();
        album1.setInceptionYear(1980);
        album2.setInceptionYear(1990);
        checkComparator(album1, album2, AlbumComparators.YEAR_COMPARATOR);
    }

    /**
     * Tests the year comparator if a year is undefined.
     */
    public void testYearComparatorWithNull()
    {
        AlbumInfo album1 = new AlbumInfo();
        AlbumInfo album2 = new AlbumInfo();
        album2.setInceptionYear(1990);
        checkComparator(album1, album2, AlbumComparators.YEAR_COMPARATOR);
    }
}
