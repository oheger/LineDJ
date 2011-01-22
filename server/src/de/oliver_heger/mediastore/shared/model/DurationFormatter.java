package de.oliver_heger.mediastore.shared.model;

/**
 * <p>
 * A helper class for formatting the duration of songs and albums.
 * </p>
 * <p>
 * In the database the duration of songs is stored in milliseconds. This class
 * provides methods for transforming such a number to a readable form like 05:43
 * or 1:12:17.
 * </p>
 *
 * @author Oliver Heger
 * @version $Id: $
 */
class DurationFormatter
{
    /** Constant for the result of null input. */
    private static final String NULL_RESULT = "";

    /** Constant for milliseconds. */
    private static final long MS = 1000;

    /** Constant for minutes. */
    private static final long MINUTES = 60;

    /** Constant for hours. */
    private static final long HOURS = MINUTES * MINUTES * MS;

    /** Constant for the string buffer size for formatMinutes(). */
    private static final int BUF_SIZE_MIN = 5;

    /** Constant for the string buffer size for formatHours(). */
    private static final int BUF_SIZE_HOUR = 10;

    /** Constant for the separator for the formatted duration. */
    private static final char DURATION_SEPARATOR = ':';

    /**
     * Formats the specified number in milliseconds to minutes and seconds. An
     * example output is 8:24. <b>null</b> values are transformed to an empty
     * string.
     *
     * @param ms the milliseconds (can be <b>null</b>
     * @return the formatted duration as minutes and seconds
     */
    public static String formatMinutes(Long ms)
    {
        if (ms == null)
        {
            return NULL_RESULT;
        }

        StringBuilder buf = new StringBuilder(BUF_SIZE_MIN);
        doFormatMinutes(buf, ms);
        return buf.toString();
    }

    /**
     * Formats the specified number in milliseconds to hours, minutes, and
     * seconds. Example output looks like 1:18:47 or 0:01:55. <b>null</b> values
     * are transformed to an empty string.
     *
     * @param ms the milliseconds (can be <b>null</b>
     * @return the formatted duration as hours, minutes, and seconds
     */
    public static String formatHours(Long ms)
    {
        if (ms == null)
        {
            return NULL_RESULT;
        }

        StringBuilder buf = new StringBuilder(BUF_SIZE_HOUR);
        long hours = ms.longValue() / HOURS;
        buf.append(hours).append(DURATION_SEPARATOR);
        doFormatMinutes(buf, ms.longValue() % HOURS);
        return buf.toString();
    }

    /**
     * Helper method for formatting the parts of minutes and seconds.
     *
     * @param buf the target buffer
     * @param ms the value in milliseconds
     */
    private static void doFormatMinutes(StringBuilder buf, Long ms)
    {
        long durSecs = ms.longValue() / MS;
        appendDurationPart(buf, durSecs / MINUTES);
        buf.append(DURATION_SEPARATOR);
        appendDurationPart(buf, durSecs % MINUTES);
    }

    /**
     * Appends a duration part to a buffer.
     *
     * @param buf the buffer
     * @param part the part to be added
     */
    private static void appendDurationPart(StringBuilder buf, long part)
    {
        if (part < 10)
        {
            buf.append('0');
        }
        buf.append(part);
    }
}
