package de.oliver_heger.mediastore.shared.model;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

/**
 * Test class for {@code DurationFormatter}.
 *
 * @author Oliver Heger
 * @version $Id: $
 */
public class TestDurationFormatter
{
    /**
     * Helper method for calculating a milliseconds value.
     *
     * @param hours the hours
     * @param minutes the minutes
     * @param seconds the seconds
     * @return the resulting milliseconds
     */
    private static Long calcMillis(int hours, int minutes, int seconds)
    {
        return ((hours * 60 + minutes) * 60 + seconds) * 1000L;
    }

    /**
     * Tests formatMinutes() for null input.
     */
    @Test
    public void testFormatMinutesNull()
    {
        assertEquals("Wrong result", "", DurationFormatter.formatMinutes(null));
    }

    /**
     * Tests formatMinutes() for a value of 0.
     */
    @Test
    public void testFormatMinutes0()
    {
        assertEquals("Wrong result", "00:00",
                DurationFormatter.formatMinutes(0L));
    }

    /**
     * Tests formatMinutes() for a normal value.
     */
    @Test
    public void testFormatMinutesMinutesAndSeconds()
    {
        assertEquals("Wrong result", "07:42",
                DurationFormatter.formatMinutes(calcMillis(0, 7, 42)));
    }

    /**
     * Tests formatMinutes() for a long value.
     */
    @Test
    public void testFormatMinutesHours()
    {
        assertEquals("Wrong result", "120:17",
                DurationFormatter.formatMinutes(calcMillis(2, 0, 17)));
    }

    /**
     * Tests formatHours() for null input.
     */
    @Test
    public void testFormatHoursNull()
    {
        assertEquals("Wrong result", "", DurationFormatter.formatHours(null));
    }

    /**
     * Tests formatHours() for a value of 0.
     */
    @Test
    public void testFormatHours0()
    {
        assertEquals("Wrong result", "0:00:00",
                DurationFormatter.formatHours(0L));
    }

    /**
     * Tests formatHours() for a value below an hour.
     */
    @Test
    public void testFormatHoursMinutesOnly()
    {
        assertEquals("Wrong result", "0:59:58",
                DurationFormatter.formatHours(calcMillis(0, 59, 58)));
    }

    /**
     * Tests formatHours() for a value larger than an hour.
     */
    @Test
    public void testFormatHoursMoreHours()
    {
        assertEquals("Wrong result", "5:16:48",
                DurationFormatter.formatHours(calcMillis(5, 16, 48)));
    }
}
