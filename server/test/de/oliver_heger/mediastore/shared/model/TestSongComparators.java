package de.oliver_heger.mediastore.shared.model;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.Comparator;

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
     * Helper method for checking a comparator.
     *
     * @param s1 the first info object (which is expected to be less than the
     *        2nd)
     * @param s2 the second info object (the greater one)
     * @param comp the comparator
     */
    private static void checkComparator(SongInfo s1, SongInfo s2,
            Comparator<SongInfo> comp)
    {
        assertTrue("Wrong order", comp.compare(s1, s2) < 0);
        assertTrue("Wrong symmetric order", comp.compare(s2, s1) > 0);
        assertEquals("Wrong reflexive order", 0, comp.compare(s1, s1));
    }

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
