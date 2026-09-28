/*
 * Verifies a packed tide grid: reads it back, predicts tides from it, and
 * compares the result with published tide tables.
 *
 * This is a test harness, deliberately independent of the prediction engine
 * that ships with the application. Two implementations agreeing is a real
 * check; an engine validating a file with its own code would only prove it is
 * consistent with itself.
 *
 * Usage:
 *   TideCheck <grid> --check              regression against the built in table
 *   TideCheck <grid> <lat> <lon> [days]   print the next days at a position
 *
 * Licensed under MIT (see ../LICENSE).
 */
package generator;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteOrder;
import java.nio.LongBuffer;
import java.nio.MappedByteBuffer;
import java.nio.ShortBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Reads a packed grid, predicts from it, and checks the prediction. */
public final class TideCheck
{
    // ---------------------------------------------------------------- check

    /**
     * Published tide tables for twelve ports, from 61 degrees north to 14
     * degrees south and right across the date line. Each block starts with a
     * header line giving the port, its position, and the datum its heights are
     * referenced to; the lines that follow are the predicted high and low
     * waters, in UTC, for a fixed three day window.
     *
     * Sources: the United States National Oceanic and Atmospheric
     * Administration, whose predictions are in the public domain, and for
     * Saint-Malo the French hydrographic service as republished by maree.info.
     * Only what is needed to verify the conversion is reproduced.
     *
     * Ports where the model is known to fail are deliberately absent. A global
     * model on a 1/8 degree grid cannot resolve a long inlet: Seattle, at the
     * far end of Puget Sound, comes out four and a half hours off, and Boston
     * falls on cells the model marks as land. Neither belongs in a regression
     * test, whose job is to catch a broken conversion rather than to record
     * the limits of the model.
     */
    private static final String[] PORTS = {
            "# Saint-Malo, France|48.6486|-2.0261|LAT",
            "2026-09-28T01:34Z L   1.35",
            "2026-09-28T06:54Z H  12.40",
            "2026-09-28T13:51Z L   1.46",
            "2026-09-28T19:12Z H  12.60",
            "2026-09-29T02:10Z L   1.34",
            "2026-09-29T07:28Z H  12.45",
            "2026-09-29T14:26Z L   1.53",
            "2026-09-29T19:46Z H  12.46",
            "2026-09-30T02:44Z L   1.60",
            "2026-09-30T08:01Z H  12.23",
            "2026-09-30T14:59Z L   1.86",
            "2026-09-30T20:21Z H  12.02",
            "# Anchorage, Alaska|61.2375|-149.8904|MLLW",
            "2026-09-28T04:26Z H   9.77",
            "2026-09-28T11:26Z L  -0.24",
            "2026-09-28T16:52Z H   9.42",
            "2026-09-28T23:32Z L   0.59",
            "2026-09-29T04:55Z H   9.89",
            "2026-09-29T12:06Z L  -0.32",
            "2026-09-29T17:32Z H   9.23",
            "2026-09-30T00:05Z L   0.87",
            "2026-09-30T05:28Z H   9.87",
            "2026-09-30T12:46Z L  -0.25",
            "2026-09-30T18:15Z H   8.93",
            "# Adak, Alaska|51.8606|-176.6376|MLLW",
            "2026-09-28T02:19Z H   0.86",
            "2026-09-28T10:08Z L   0.20",
            "2026-09-29T02:26Z H   0.97",
            "2026-09-29T11:00Z L   0.02",
            "2026-09-30T02:46Z H   1.08",
            "2026-09-30T11:51Z L  -0.13",
            "# Atlantic City, New Jersey|39.3567|-74.4180|MLLW",
            "2026-09-28T00:40Z H   1.43",
            "2026-09-28T06:43Z L   0.02",
            "2026-09-28T12:58Z H   1.58",
            "2026-09-28T19:18Z L   0.05",
            "2026-09-29T01:21Z H   1.38",
            "2026-09-29T07:21Z L   0.03",
            "2026-09-29T13:40Z H   1.61",
            "2026-09-29T20:06Z L   0.08",
            "2026-09-30T02:05Z H   1.31",
            "2026-09-30T08:03Z L   0.07",
            "2026-09-30T14:26Z H   1.60",
            "2026-09-30T21:00Z L   0.13",
            "# San Francisco, California|37.8063|-122.4659|MLLW",
            "2026-09-28T01:34Z L   0.10",
            "2026-09-28T08:13Z H   1.57",
            "2026-09-28T13:31Z L   0.50",
            "2026-09-28T19:57Z H   1.88",
            "2026-09-29T02:18Z L  -0.01",
            "2026-09-29T09:09Z H   1.50",
            "2026-09-29T14:08Z L   0.66",
            "2026-09-29T20:30Z H   1.92",
            "2026-09-30T03:06Z L  -0.07",
            "2026-09-30T10:11Z H   1.42",
            "2026-09-30T14:48Z L   0.81",
            "2026-09-30T21:10Z H   1.93",
            "# Port San Luis, California|35.1689|-120.7542|MLLW",
            "2026-09-28T00:18Z L   0.14",
            "2026-09-28T06:27Z H   1.40",
            "2026-09-28T12:02Z L   0.48",
            "2026-09-28T18:14Z H   1.78",
            "2026-09-29T01:07Z L   0.07",
            "2026-09-29T07:23Z H   1.29",
            "2026-09-29T12:32Z L   0.62",
            "2026-09-29T18:47Z H   1.82",
            "2026-09-30T02:00Z L   0.03",
            "2026-09-30T08:28Z H   1.17",
            "2026-09-30T13:04Z L   0.77",
            "2026-09-30T19:27Z H   1.82",
            "# Midway Atoll|28.2117|-177.3600|MLLW",
            "2026-09-28T03:57Z H   0.31",
            "2026-09-28T10:10Z L   0.03",
            "2026-09-28T16:59Z H   0.44",
            "2026-09-28T23:24Z L   0.16",
            "2026-09-29T04:24Z H   0.30",
            "2026-09-29T10:46Z L   0.00",
            "2026-09-29T17:45Z H   0.46",
            "2026-09-30T00:10Z L   0.18",
            "2026-09-30T04:55Z H   0.30",
            "2026-09-30T11:26Z L  -0.02",
            "2026-09-30T18:34Z H   0.47",
            "# Honolulu, Hawaii|21.3033|-157.8645|MLLW",
            "2026-09-28T02:14Z H   0.46",
            "2026-09-28T08:19Z L   0.01",
            "2026-09-28T15:08Z H   0.64",
            "2026-09-28T21:39Z L   0.15",
            "2026-09-29T02:46Z H   0.39",
            "2026-09-29T08:44Z L   0.01",
            "2026-09-29T15:55Z H   0.66",
            "2026-09-29T22:47Z L   0.17",
            "2026-09-30T03:19Z H   0.32",
            "2026-09-30T09:14Z L   0.02",
            "2026-09-30T16:50Z H   0.67",
            "# Wake Island|19.2906|166.6175|MLLW",
            "2026-09-28T04:50Z H   0.87",
            "2026-09-28T11:05Z L  -0.10",
            "2026-09-28T17:07Z H   0.81",
            "2026-09-28T23:06Z L  -0.04",
            "2026-09-29T05:19Z H   0.88",
            "2026-09-29T11:41Z L  -0.09",
            "2026-09-29T17:41Z H   0.75",
            "2026-09-29T23:33Z L  -0.00",
            "2026-09-30T05:50Z H   0.86",
            "2026-09-30T12:19Z L  -0.06",
            "2026-09-30T18:16Z H   0.67",
            "# San Juan, Puerto Rico|18.4589|-66.1164|MLLW",
            "2026-09-28T01:14Z H   0.42",
            "2026-09-28T07:20Z L   0.07",
            "2026-09-28T14:12Z H   0.57",
            "2026-09-28T20:38Z L   0.18",
            "2026-09-29T01:48Z H   0.39",
            "2026-09-29T07:57Z L   0.04",
            "2026-09-29T15:02Z H   0.60",
            "2026-09-29T21:39Z L   0.20",
            "2026-09-30T02:25Z H   0.36",
            "2026-09-30T08:40Z L   0.03",
            "2026-09-30T15:55Z H   0.61",
            "2026-09-30T22:42Z L   0.22",
            "# Apra Harbor, Guam|13.4434|144.6564|MLLW",
            "2026-09-28T04:30Z L   0.26",
            "2026-09-28T10:12Z H   0.75",
            "2026-09-28T16:54Z L   0.01",
            "2026-09-28T23:25Z H   0.73",
            "2026-09-29T05:11Z L   0.34",
            "2026-09-29T10:39Z H   0.74",
            "2026-09-29T17:36Z L  -0.04",
            "2026-09-30T00:23Z H   0.71",
            "2026-09-30T05:55Z L   0.41",
            "2026-09-30T11:10Z H   0.73",
            "2026-09-30T18:24Z L  -0.08",
            "# Pago Pago, American Samoa|-14.2800|-170.6900|MLLW",
            "2026-09-28T00:24Z L  -0.00",
            "2026-09-28T06:44Z H   0.90",
            "2026-09-28T12:59Z L  -0.01",
            "2026-09-28T19:07Z H   0.83",
            "2026-09-29T01:10Z L   0.01",
            "2026-09-29T07:33Z H   0.90",
            "2026-09-29T13:52Z L  -0.00",
            "2026-09-29T20:00Z H   0.79",
            "2026-09-30T02:01Z L   0.03",
            "2026-09-30T08:27Z H   0.88",
            "2026-09-30T14:51Z L   0.01",
            "2026-09-30T21:00Z H   0.74",
    };

    /**
     * Tolerances. Wide enough to absorb what a global ocean model genuinely
     * cannot do near a coast, tight enough that a broken conversion, which
     * moves tides by hours or by metres, cannot slip through.
     */
    private static final long TIME_TOLERANCE_SECONDS = 60L * 60L;
    private static final double RANGE_TOLERANCE_METRES = 0.40;
    private static final double RANGE_TOLERANCE_FRACTION = 0.15;
    private static final double HEIGHT_TOLERANCE_METRES = 1.0;

    public static void main(String[] arguments) throws Exception
    {
        if (arguments.length < 2)
        {
            System.err.println("usage: TideCheck <grid> --check");
            System.err.println("       TideCheck <grid> <lat> <lon> [days] [zone]");
            System.exit(2);
        }

        Grid grid = new Grid(arguments[0]);

        if ("--check".equals(arguments[1]))
        {
            System.exit(_check(grid) ? 0 : 1);
        }

        double latitude = Double.parseDouble(arguments[1]);
        double longitude = Double.parseDouble(arguments[2]);
        int days = arguments.length > 3 ? Integer.parseInt(arguments[3]) : 3;
        ZoneId zone = ZoneId.of(arguments.length > 4 ? arguments[4] : "Europe/Paris");

        _report(grid, latitude, longitude, days, zone);
    }

    /** One published high or low water. */
    private static final class Reference
    {
        final long unixSeconds;
        final boolean high;
        final double heightMetres;

        Reference(long unixSeconds, boolean high, double heightMetres)
        {
            this.unixSeconds = unixSeconds;
            this.high = high;
            this.heightMetres = heightMetres;
        }
    }

    private static boolean _check(Grid grid)
    {
        int ports = 0;
        int failed = 0;

        String name = null;
        double latitude = 0.0;
        double longitude = 0.0;
        boolean chartDatum = false;
        List<Reference> references = new ArrayList<>();

        for (int line = 0; line <= PORTS.length; line++)
        {
            boolean header = line == PORTS.length || PORTS[line].startsWith("#");

            if (header && name != null)
            {
                ports++;

                if (!_checkPort(grid, name, latitude, longitude, chartDatum, references))
                {
                    failed++;
                }
            }

            if (line == PORTS.length)
            {
                break;
            }

            if (header)
            {
                String[] parts = PORTS[line].substring(2).split("\\|");

                name = parts[0];
                latitude = Double.parseDouble(parts[1]);
                longitude = Double.parseDouble(parts[2]);
                chartDatum = "LAT".equals(parts[3]);
                references = new ArrayList<>();
                continue;
            }

            String[] parts = PORTS[line].trim().split("\\s+");

            references.add(new Reference(
                    Instant.parse(parts[0].replace("Z", ":00Z")).getEpochSecond(),
                    "H".equals(parts[1]),
                    Double.parseDouble(parts[2])));
        }

        System.out.printf(Locale.US,
                "%n   %d ports checked, %d failed%n", ports, failed);

        return failed == 0;
    }

    private static boolean _checkPort(Grid grid,
                                      String name,
                                      double latitude,
                                      double longitude,
                                      boolean chartDatum,
                                      List<Reference> references)
    {
        Constants constants = new Constants(grid, latitude, longitude);

        if (constants.count() == 0)
        {
            System.out.printf(Locale.US, "   %-30s FAIL  no tidal data at this position%n", name);
            return false;
        }

        long start = references.get(0).unixSeconds - 86400L;
        long end = references.get(references.size() - 1).unixSeconds + 86400L;

        List<Event> events = _extrema(constants, start, end, 60);

        double datum = chartDatum ? _lowestAstronomicalTide(constants, start) : 0.0;

        long worstDelay = 0L;
        double worstRange = 0.0;
        double worstHeight = 0.0;
        double rangeLimit = 0.0;

        double[] matched = new double[references.size()];
        boolean complete = true;

        for (int index = 0; index < references.size(); index++)
        {
            Reference reference = references.get(index);

            Event closest = null;
            long bestGap = Long.MAX_VALUE;

            for (Event event : events)
            {
                if (event.high != reference.high)
                {
                    continue;
                }

                long gap = Math.abs(event.unixSeconds - reference.unixSeconds);

                if (gap < bestGap)
                {
                    bestGap = gap;
                    closest = event;
                }
            }

            if (closest == null)
            {
                complete = false;
                break;
            }

            matched[index] = closest.heightMetres;
            worstDelay = Math.max(worstDelay, bestGap);

            if (chartDatum)
            {
                worstHeight = Math.max(worstHeight,
                        Math.abs(closest.heightMetres - datum - reference.heightMetres));
            }
        }

        if (!complete)
        {
            System.out.printf(Locale.US, "   %-30s FAIL  a tide is missing%n", name);
            return false;
        }

        // The datum differs from one national table to the next, so what is
        // compared here is the range between consecutive tides, which no datum
        // can shift.
        for (int index = 0; index + 1 < references.size(); index++)
        {
            if (references.get(index).high == references.get(index + 1).high)
            {
                continue;
            }

            double expected = Math.abs(references.get(index + 1).heightMetres
                    - references.get(index).heightMetres);
            double predicted = Math.abs(matched[index + 1] - matched[index]);

            worstRange = Math.max(worstRange, Math.abs(predicted - expected));
            rangeLimit = Math.max(rangeLimit,
                    Math.max(RANGE_TOLERANCE_METRES, RANGE_TOLERANCE_FRACTION * expected));
        }

        boolean ok = worstDelay <= TIME_TOLERANCE_SECONDS
                && worstRange <= rangeLimit
                && (!chartDatum || worstHeight <= HEIGHT_TOLERANCE_METRES);

        System.out.printf(Locale.US,
                "   %-30s %6.2f %9.2f  %3d min  range %5.2f m of %5.2f%s  %s%n",
                name, latitude, longitude,
                worstDelay / 60L, worstRange, rangeLimit,
                chartDatum
                        ? String.format(Locale.US, "  height %4.2f m", worstHeight)
                        : "",
                ok ? "ok" : "FAIL");

        return ok;
    }

    private static void _report(Grid grid, double latitude, double longitude,
                                int days, ZoneId zone)
    {
        Constants constants = new Constants(grid, latitude, longitude);

        if (constants.count() == 0)
        {
            System.out.println("   no tidal data at this position");
            return;
        }

        ZonedDateTime startOfDay = ZonedDateTime.now(zone).toLocalDate().atStartOfDay(zone);

        long start = startOfDay.toEpochSecond();
        long end = startOfDay.plusDays(days).toEpochSecond();

        double datum = _lowestAstronomicalTide(constants, start);

        System.out.printf(Locale.US,
                "   %.4f, %.4f  %d constituents, chart datum %.2f m below mean level%n%n",
                latitude, longitude, constants.count(), -datum);

        DateTimeFormatter dayFormat =
                DateTimeFormatter.ofPattern("EEEE d MMMM yyyy", Locale.UK);
        DateTimeFormatter timeFormat = DateTimeFormatter.ofPattern("HH:mm", Locale.UK);

        String currentDay = "";
        Event previous = null;

        for (Event event : _extrema(constants, start, end, 60))
        {
            ZonedDateTime moment = Instant.ofEpochSecond(event.unixSeconds).atZone(zone);
            String day = moment.format(dayFormat);

            if (!day.equals(currentDay))
            {
                System.out.printf("   %s%n", day);
                currentDay = day;
            }

            String range = "";

            if (previous != null && previous.high != event.high)
            {
                range = String.format(Locale.US, "   range %5.2f m",
                        Math.abs(event.heightMetres - previous.heightMetres));
            }

            System.out.printf(Locale.US, "     %s  %s  %5.2f m%s%n",
                    moment.format(timeFormat),
                    event.high ? "high" : "low ",
                    event.heightMetres - datum,
                    range);

            previous = event;
        }
    }

    // ------------------------------------------------------------ prediction

    /** One turning point of the tide curve. */
    private static final class Event
    {
        final long unixSeconds;
        final double heightMetres;
        final boolean high;

        Event(long unixSeconds, double heightMetres, boolean high)
        {
            this.unixSeconds = unixSeconds;
            this.heightMetres = heightMetres;
            this.high = high;
        }
    }

    private static List<Event> _extrema(Constants constants, long start, long end, int step)
    {
        int count = (int) ((end - start) / step) + 1;
        double[] heights = new double[count];

        for (int index = 0; index < count; index++)
        {
            heights[index] = constants.heightAt(start + (long) index * step);
        }

        List<Event> events = new ArrayList<>();

        for (int index = 1; index < count - 1; index++)
        {
            double before = heights[index - 1];
            double here = heights[index];
            double after = heights[index + 1];

            boolean maximum = here > before && here >= after;
            boolean minimum = here < before && here <= after;

            if (!maximum && !minimum)
            {
                continue;
            }

            double denominator = before - 2.0 * here + after;
            double offset = denominator == 0.0 ? 0.0 : 0.5 * (before - after) / denominator;

            events.add(new Event(
                    start + Math.round((index + offset) * step),
                    here - 0.25 * (before - after) * offset,
                    maximum));
        }

        return events;
    }

    /**
     * Lowest level reached over a full nodal cycle, which is what French
     * charts are referenced to. Needs no data beyond the constituents.
     */
    private static double _lowestAstronomicalTide(Constants constants, long start)
    {
        double lowest = Double.MAX_VALUE;
        long end = start + 19L * 365L * 86400L;

        for (long moment = start; moment < end; moment += 600L)
        {
            lowest = Math.min(lowest, constants.heightAt(moment));
        }

        return lowest;
    }

    // ------------------------------------------------------------ astronomy

    private static final String[] NAMES = {
            "2n2", "j1", "k1", "k2", "m2", "m4", "mf", "mm", "n2",
            "o1", "p1", "q1", "s1", "s2", "sa", "ssa", "t2"
    };

    /** Doodson coefficients over { tau, s, h, p, n, pp, k }. */
    private static final double[][] DOODSON = {
            { 2, -2, 0, 2, 0, 0, 0 },   // 2n2
            { 1, 2, 0, -1, 0, 0, 1 },   // j1
            { 1, 1, 0, 0, 0, 0, 1 },    // k1
            { 2, 2, 0, 0, 0, 0, 0 },    // k2
            { 2, 0, 0, 0, 0, 0, 0 },    // m2
            { 4, 0, 0, 0, 0, 0, 0 },    // m4
            { 0, 2, 0, 0, 0, 0, 0 },    // mf
            { 0, 1, 0, -1, 0, 0, 0 },   // mm
            { 2, -1, 0, 1, 0, 0, 0 },   // n2
            { 1, -1, 0, 0, 0, 0, -1 },  // o1
            { 1, 1, -2, 0, 0, 0, -1 },  // p1
            { 1, -2, 0, 1, 0, 0, -1 },  // q1
            { 1, 1, -1, 0, 0, 0, 2 },   // s1
            { 2, 2, -2, 0, 0, 0, 0 },   // s2
            { 0, 0, 1, 0, 0, -1, 0 },   // sa
            { 0, 0, 2, 0, 0, 0, 0 },    // ssa
            { 2, 2, -3, 0, 0, 1, 0 },   // t2
    };

    /** Harmonic constants sampled at one position, and the prediction on them. */
    private static final class Constants
    {
        private final int[] _index;
        private final double[] _real;
        private final double[] _imaginary;
        private int _count;

        Constants(Grid grid, double latitude, double longitude)
        {
            _index = new int[NAMES.length];
            _real = new double[NAMES.length];
            _imaginary = new double[NAMES.length];

            for (int slot = 0; slot < grid.names.length; slot++)
            {
                int known = _nameIndex(grid.names[slot]);

                if (known < 0)
                {
                    continue;
                }

                double[] sampled = grid.sample(slot, latitude, longitude);

                if (sampled == null)
                {
                    continue;
                }

                _index[_count] = known;
                _real[_count] = sampled[0];
                _imaginary[_count] = sampled[1];
                _count++;
            }
        }

        int count()
        {
            return _count;
        }

        double heightAt(long unixSeconds)
        {
            double mjd = 40587.0 + unixSeconds / 86400.0;

            return _heightAtMjd(mjd);
        }

        private double _heightAtMjd(double mjd)
        {
            double t = (mjd - 51544.5) / 36525.0;

            double lunar = _polynomial(t, 218.3164477, 481267.88123421,
                    -1.5786e-3, 1.855835e-6, -1.53388e-8);
            double elongation = _polynomial(t, 297.8501921, 445267.1114034,
                    -1.8819e-3, 1.83195e-6, -8.8445e-9);

            double s = _wrap(lunar);
            double h = _wrap(lunar - elongation);
            double p = _wrap(_polynomial(t, 83.3532465, 4069.0137287, -1.032e-2, -1.249172e-5));
            double n = _wrap(_polynomial(t, 125.04452, -1934.136261, 2.0708e-3, 2.22222e-6));
            double pp = _wrap(282.94 + 1.7192 * t);

            double hour = 24.0 * (mjd - Math.floor(mjd));
            double tau = 15.0 * hour - s + h;

            double[] schureman = _schureman(Math.toRadians(p), Math.toRadians(n));

            double centimetres = 0.0;

            for (int slot = 0; slot < _count; slot++)
            {
                int constituent = _index[slot];
                double[] coefficients = DOODSON[constituent];

                double argument = coefficients[0] * tau
                        + coefficients[1] * s
                        + coefficients[2] * h
                        + coefficients[3] * p
                        + coefficients[4] * n
                        + coefficients[5] * pp
                        + coefficients[6] * 90.0;

                double[] modulation = _nodal(constituent, schureman);

                double theta = Math.toRadians(argument) + modulation[1];

                centimetres += modulation[0]
                        * (_real[slot] * Math.cos(theta) - _imaginary[slot] * Math.sin(theta));
            }

            return centimetres / 100.0;
        }
    }

    /** Schureman angles: { I, xi, nu, nu', nu'' }, radians. */
    private static double[] _schureman(double p, double n)
    {
        double inclination = Math.acos(0.913694997 - 0.035692561 * Math.cos(n));

        double first = Math.atan(1.01883 * Math.tan(n / 2.0));
        double second = Math.atan(0.64412 * Math.tan(n / 2.0));

        double raw = -first - second + n;
        double xi = Math.atan2(Math.sin(raw), Math.cos(raw));
        double nu = first - second;

        double primeTop = Math.sin(2.0 * inclination) * Math.sin(nu);
        double primeBottom = Math.sin(2.0 * inclination) * Math.cos(nu) + 0.3347;
        double nuPrime = Math.atan(primeTop / primeBottom);

        double sinSquared = Math.sin(inclination) * Math.sin(inclination);
        double secondTop = sinSquared * Math.sin(2.0 * nu);
        double secondBottom = sinSquared * Math.cos(2.0 * nu) + 0.0727;
        double nuSecond = 0.5 * Math.atan(secondTop / secondBottom);

        return new double[] { inclination, xi, nu, nuPrime, nuSecond };
    }

    /** Nodal factor and angle of a constituent, FES formulation. */
    private static double[] _nodal(int constituent, double[] schureman)
    {
        double inclination = schureman[0];
        double xi = schureman[1];
        double nu = schureman[2];

        double sin = Math.sin(inclination);
        double cosHalf = Math.cos(inclination / 2.0);
        double sinTwice = Math.sin(2.0 * inclination);

        switch (NAMES[constituent])
        {
            case "m2":
            case "2n2":
            case "n2":
                return new double[] { Math.pow(cosHalf, 4.0) / 0.9154, 2.0 * xi - 2.0 * nu };

            case "m4":
            {
                double[] parent = _nodal(_nameIndex("m2"), schureman);
                return new double[] { parent[0] * parent[0], 2.0 * parent[1] };
            }

            case "o1":
            case "q1":
                return new double[] { sin * cosHalf * cosHalf / 0.38, 2.0 * xi - nu };

            case "j1":
                return new double[] { sinTwice / 0.7214, -nu };

            case "k1":
                return new double[] {
                        Math.sqrt(0.8965 * sinTwice * sinTwice
                                + 0.6001 * sinTwice * Math.cos(nu) + 0.1006),
                        -schureman[3] };

            case "k2":
                return new double[] {
                        Math.sqrt(19.0444 * Math.pow(sin, 4.0)
                                + 2.7702 * sin * sin * Math.cos(2.0 * nu) + 0.0981),
                        -2.0 * schureman[4] };

            case "mm":
                return new double[] { (2.0 / 3.0 - sin * sin) / 0.5021, 0.0 };

            case "mf":
                return new double[] { sin * sin / 0.1578, -2.0 * xi };

            default:
                // Solar and long period terms carry no nodal modulation.
                return new double[] { 1.0, 0.0 };
        }
    }

    private static int _nameIndex(String name)
    {
        for (int index = 0; index < NAMES.length; index++)
        {
            if (NAMES[index].equals(name))
            {
                return index;
            }
        }

        return -1;
    }

    private static double _polynomial(double t, double... coefficients)
    {
        double sum = 0.0;
        double power = 1.0;

        for (double coefficient : coefficients)
        {
            sum += coefficient * power;
            power *= t;
        }

        return sum;
    }

    private static double _wrap(double degrees)
    {
        double wrapped = degrees % 360.0;

        return wrapped < 0.0 ? wrapped + 360.0 : wrapped;
    }

    // ---------------------------------------------------------- grid reading

    /** Independent reader of the packed format, written from its spec. */
    private static final class Grid
    {
        final String[] names;

        private final double _lonMin;
        private final double _lonStep;
        private final int _columns;
        private final double _latMin;
        private final double _latStep;
        private final int _rows;
        private final int _oceanCount;
        private final LongBuffer _occupancy;
        private final int[] _rowStart;
        private final double[][] _scales;
        private final ShortBuffer[] _real;
        private final ShortBuffer[] _imaginary;

        Grid(String path) throws IOException
        {
            RandomAccessFile file = new RandomAccessFile(path, "r");
            FileChannel channel = file.getChannel();

            MappedByteBuffer buffer = channel.map(
                    FileChannel.MapMode.READ_ONLY, 0L, channel.size());
            buffer.order(ByteOrder.LITTLE_ENDIAN);

            byte[] magic = new byte[8];
            buffer.get(magic);

            if (!"EOT20PK2".equals(new String(magic, StandardCharsets.US_ASCII)))
            {
                file.close();
                throw new IOException("not a packed tide grid: " + path);
            }

            int constituents = buffer.getInt();

            _lonMin = buffer.getDouble();
            _lonStep = buffer.getDouble();
            _columns = buffer.getInt();
            _latMin = buffer.getDouble();
            _latStep = buffer.getDouble();
            _rows = buffer.getInt();
            _oceanCount = buffer.getInt();

            int words = (_rows * _columns + 63) / 64;

            LongBuffer occupancy = buffer.slice().order(ByteOrder.LITTLE_ENDIAN).asLongBuffer();
            occupancy.limit(words);
            _occupancy = occupancy;
            buffer.position(buffer.position() + words * 8);

            _rowStart = new int[_rows];

            for (int row = 0; row < _rows; row++)
            {
                _rowStart[row] = buffer.getInt();
            }

            names = new String[constituents];
            _scales = new double[constituents][];
            _real = new ShortBuffer[constituents];
            _imaginary = new ShortBuffer[constituents];

            for (int slot = 0; slot < constituents; slot++)
            {
                byte[] raw = new byte[8];
                buffer.get(raw);

                names[slot] = new String(raw, StandardCharsets.US_ASCII)
                        .replace("\0", "").trim();

                _scales[slot] = new double[] { buffer.getFloat(), buffer.getFloat() };

                _real[slot] = _slice(buffer);
                _imaginary[slot] = _slice(buffer);
            }

            file.close();
        }

        private ShortBuffer _slice(MappedByteBuffer buffer)
        {
            ShortBuffer values = buffer.slice().order(ByteOrder.LITTLE_ENDIAN).asShortBuffer();
            values.limit(_oceanCount);
            buffer.position(buffer.position() + _oceanCount * 2);

            return values;
        }

        double[] sample(int slot, double latitude, double longitude)
        {
            double wrapped = longitude;

            while (wrapped < _lonMin)
            {
                wrapped += 360.0;
            }

            while (wrapped >= _lonMin + 360.0)
            {
                wrapped -= 360.0;
            }

            double columnPosition = (wrapped - _lonMin) / _lonStep;
            double rowPosition = (latitude - _latMin) / _latStep;

            int column = (int) Math.floor(columnPosition);
            int row = (int) Math.floor(rowPosition);

            if (row < 0 || row >= _rows - 1)
            {
                return null;
            }

            double columnFraction = columnPosition - column;
            double rowFraction = rowPosition - row;

            double real = 0.0;
            double imaginary = 0.0;
            double weightSum = 0.0;

            for (int rowOffset = 0; rowOffset <= 1; rowOffset++)
            {
                for (int columnOffset = 0; columnOffset <= 1; columnOffset++)
                {
                    int sampleColumn = (column + columnOffset) % _columns;

                    if (sampleColumn < 0)
                    {
                        sampleColumn += _columns;
                    }

                    int cell = _cellOf(row + rowOffset, sampleColumn);

                    if (cell < 0)
                    {
                        continue;
                    }

                    double weight = (rowOffset == 0 ? 1.0 - rowFraction : rowFraction)
                            * (columnOffset == 0 ? 1.0 - columnFraction : columnFraction);

                    real += weight * _real[slot].get(cell) * _scales[slot][0];
                    imaginary += weight * _imaginary[slot].get(cell) * _scales[slot][1];
                    weightSum += weight;
                }
            }

            if (weightSum <= 0.0)
            {
                return null;
            }

            return new double[] { real / weightSum, imaginary / weightSum };
        }

        private int _cellOf(int row, int column)
        {
            long bit = (long) row * _columns + column;

            if ((_occupancy.get((int) (bit >>> 6)) & (1L << (bit & 63))) == 0L)
            {
                return -1;
            }

            long rowBit = (long) row * _columns;
            int slot = _rowStart[row];

            int firstWord = (int) (rowBit >>> 6);
            int lastWord = (int) (bit >>> 6);

            long leading = -1L << (rowBit & 63);

            if (firstWord == lastWord)
            {
                return slot + Long.bitCount(
                        _occupancy.get(firstWord) & leading & ((1L << (bit & 63)) - 1L));
            }

            slot += Long.bitCount(_occupancy.get(firstWord) & leading);

            for (int word = firstWord + 1; word < lastWord; word++)
            {
                slot += Long.bitCount(_occupancy.get(word));
            }

            return slot + Long.bitCount(_occupancy.get(lastWord) & ((1L << (bit & 63)) - 1L));
        }
    }
}
