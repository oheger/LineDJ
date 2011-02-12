package de.oliver_heger.mediastore.shared.model;

import static de.oliver_heger.mediastore.shared.RemoteMediaStoreTestHelper.checkComparator;

import org.junit.Test;

/**
 * Test class for {@code SongComparators}. This class tests the various
 * comparators defined for {@link SongInfo} objects.
 *
 * @author Oliver Heger
 * @version $Id: $
 */
public class TestSongComparators
{
    /**
     * Helper method for testing the comparator for song names.
     *
     * @param name1 the first song name
     * @param name2 the second song name
     */
    private void checkNameComparator(String name1, String name2)
    {
        SongInfo info1 = new SongInfo();
        info1.setName(name1);
        SongInfo info2 = new SongInfo();
        info2.setName(name2);
        checkComparator(info1, info2, SongComparators.NAME_COMPARATOR);
    }

    /**
     * Tests the name comparator.
     */
    @Test
    public void testNameComparator()
    {
        checkNameComparator("Song1", "Song2");
        checkNameComparator("Absolute Beginners", "Zz");
    }

    /**
     * Tests whether case does not matter when comparing song names.
     */
    @Test
    public void testNameComparatorCaseInsensitive()
    {
        checkNameComparator("song1", "SONG2");
    }

    /**
     * Tests comparing songs by their duration.
     */
    @Test
    public void testDurationPropertyComparator()
    {
        SongInfo info1 = new SongInfo();
        SongInfo info2 = new SongInfo();
        info1.setDuration(20110113075830L);
        checkComparator(info2, info1,
                SongComparators.DURATION_PROPERTY_COMPARATOR);
        info2.setDuration(info1.getDuration() + 1);
        checkComparator(info1, info2,
                SongComparators.DURATION_PROPERTY_COMPARATOR);
    }

    /**
     * Tests whether songs can be compared by their play count.
     */
    @Test
    public void testPlaycountPropertyComparator()
    {
        SongInfo info1 = new SongInfo();
        SongInfo info2 = new SongInfo();
        info2.setPlayCount(1);
        checkComparator(info1, info2,
                SongComparators.PLAYCOUNT_PROPERTY_COMPARATOR);
    }

    /**
     * Tests the comparator for durations if the durations are different.
     */
    @Test
    public void testDurationComparatorDifferentDurations()
    {
        SongInfo info1 = new SongInfo();
        SongInfo info2 = new SongInfo();
        info1.setDuration(20110114211950L);
        checkComparator(info2, info1, SongComparators.DURATION_COMPARATOR);
        info2.setDuration(info1.getDuration() + 1);
        checkComparator(info1, info2, SongComparators.DURATION_COMPARATOR);
    }

    /**
     * Tests the comparator for durations if the durations are equal and the
     * song names have to be checked.
     */
    public void testDurationComparatorSameDurations()
    {
        SongInfo info1 = new SongInfo();
        SongInfo info2 = new SongInfo();
        info1.setDuration(20110114213500L);
        info2.setDuration(info1.getDuration());
        info1.setName("Songa");
        info2.setName("SongZ");
        checkComparator(info1, info2, SongComparators.DURATION_COMPARATOR);
    }

    /**
     * Tests the comparator for playCount if the values are different.
     */
    @Test
    public void testPlaycountComparatorDifferentCounts()
    {
        SongInfo info1 = new SongInfo();
        SongInfo info2 = new SongInfo();
        info1.setPlayCount(1);
        info2.setPlayCount(2);
        checkComparator(info1, info2, SongComparators.PLAYCOUNT_COMPARATOR);
    }

    /**
     * Tests the comparator for playCount if the values are equal and the song
     * names have to be checked.
     */
    @Test
    public void testPlaycountComparatorSameCounts()
    {
        SongInfo info1 = new SongInfo();
        SongInfo info2 = new SongInfo();
        info1.setPlayCount(10);
        info2.setPlayCount(info1.getPlayCount());
        info1.setName("All of my Love");
        info2.setName("zombie");
        checkComparator(info1, info2, SongComparators.PLAYCOUNT_COMPARATOR);
    }
}
