/*
 * Converts the EOT20 ocean tide grids into the compact binary read by the
 * MyBuoy tide engine.
 *
 * Usage:
 *   PackEot20 <ocean_tides dir> <output file> [constituents] [box] [step]
 *
 *   constituents  comma separated lower case names, or "all"
 *   box           latMin,latMax,lonMin,lonMax, or "world"
 *   step          quantisation step in centimetres; 0.1 is a good default
 *
 * Only ocean cells are written. An occupancy bitmap and a per row index give
 * random access, which keeps the file memory mappable while dropping the
 * third of the planet that is land.
 *
 * Source data: EOT20, Hart-Davis et al. (2021), CC BY 4.0,
 * https://doi.org/10.17882/79489
 *
 * Licensed under MIT (see LICENSE).
 */
package generator;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

/** Builds the packed tide grid. */
public final class PackEot20
{
    private static final String MAGIC = "EOT20PK2";

    private static final String[] ALL_CONSTITUENTS = {
            "2n2", "j1", "k1", "k2", "m2", "m4", "mf", "mm", "n2",
            "o1", "p1", "q1", "s1", "s2", "sa", "ssa", "t2"
    };

    private static final double DEFAULT_STEP_CENTIMETRES = 0.1;

    private final File _directory;
    private final String[] _constituents;
    private final double _step;

    private double[] _longitudes;
    private double[] _latitudes;
    private int _firstColumn;
    private int _columnCount;
    private int _firstRow;
    private int _rowCount;

    private long[] _occupancy;
    private int[] _rowStart;
    private int _oceanCount;

    public static void main(String[] arguments) throws Exception
    {
        if (arguments.length < 2)
        {
            System.err.println(
                    "usage: PackEot20 <ocean_tides dir> <output> [constituents] [box] [step]");
            System.exit(2);
        }

        String[] constituents = arguments.length > 2 && !"all".equals(arguments[2])
                ? arguments[2].split(",")
                : ALL_CONSTITUENTS;

        double[] box = null;

        if (arguments.length > 3 && !"world".equals(arguments[3]))
        {
            String[] parts = arguments[3].split(",");

            box = new double[] {
                    Double.parseDouble(parts[0]),
                    Double.parseDouble(parts[1]),
                    Double.parseDouble(parts[2]),
                    Double.parseDouble(parts[3])
            };
        }

        double step = arguments.length > 4
                ? Double.parseDouble(arguments[4])
                : DEFAULT_STEP_CENTIMETRES;

        PackEot20 packer = new PackEot20(new File(arguments[0]), constituents, step);

        packer.writeTo(new File(arguments[1]), box);
    }

    public PackEot20(File directory, String[] constituents, double step)
    {
        _directory = directory;
        _constituents = constituents;
        _step = step;
    }

    /** Converts the whole set and writes the packed grid. */
    public void writeTo(File output, double[] box) throws IOException
    {
        _readAxes(box);
        _buildOccupancy();
        _write(output);
    }

    private void _readAxes(double[] box) throws IOException
    {
        Hdf5Simple source = new Hdf5Simple(_fileOf(_constituents[0]).getPath());

        _longitudes = source.readAsDouble("lon");
        _latitudes = source.readAsDouble("lat");

        source.close();

        _firstColumn = 0;
        _columnCount = _longitudes.length;
        _firstRow = 0;
        _rowCount = _latitudes.length;

        if (box != null)
        {
            int[] rows = _rangeOf(_latitudes, box[0], box[1]);
            int[] columns = _rangeOf(_longitudes, box[2], box[3]);

            _firstRow = rows[0];
            _rowCount = rows[1];
            _firstColumn = columns[0];
            _columnCount = columns[1];
        }

        System.out.printf(Locale.US,
                "grid %d x %d, longitude %.4f to %.4f, latitude %.4f to %.4f, step %.4f deg%n",
                _columnCount, _rowCount,
                _longitudes[_firstColumn], _longitudes[_firstColumn + _columnCount - 1],
                _latitudes[_firstRow], _latitudes[_firstRow + _rowCount - 1],
                _longitudes[1] - _longitudes[0]);
    }

    /**
     * Marks every cell held by at least one constituent. The constituents all
     * share the same mask in practice, but taking the union costs nothing and
     * removes the assumption.
     */
    private void _buildOccupancy() throws IOException
    {
        int cellCount = _rowCount * _columnCount;

        _occupancy = new long[(cellCount + 63) / 64];

        for (String constituent : _constituents)
        {
            Hdf5Simple source = new Hdf5Simple(_fileOf(constituent).getPath());

            double[] amplitude = source.readAsDouble("amplitude");
            double[] phase = source.readAsDouble("phase");

            int sourceColumns = _longitudes.length;

            for (int row = 0; row < _rowCount; row++)
            {
                for (int column = 0; column < _columnCount; column++)
                {
                    int offset = (_firstRow + row) * sourceColumns + _firstColumn + column;

                    if (!_isValid(amplitude[offset], phase[offset]))
                    {
                        continue;
                    }

                    int cell = row * _columnCount + column;
                    _occupancy[cell >>> 6] |= 1L << (cell & 63);
                }
            }

            source.close();
        }

        _rowStart = new int[_rowCount];
        _oceanCount = 0;

        for (int row = 0; row < _rowCount; row++)
        {
            _rowStart[row] = _oceanCount;

            for (int column = 0; column < _columnCount; column++)
            {
                int cell = row * _columnCount + column;

                if ((_occupancy[cell >>> 6] & (1L << (cell & 63))) != 0L)
                {
                    _oceanCount++;
                }
            }
        }

        System.out.printf(Locale.US, "ocean cells %d of %d (%.1f %%)%n",
                _oceanCount, cellCount, 100.0 * _oceanCount / cellCount);
    }

    private void _write(File output) throws IOException
    {
        OutputStream stream = new BufferedOutputStream(
                new FileOutputStream(output), 1 << 20);

        stream.write(MAGIC.getBytes(StandardCharsets.US_ASCII));
        stream.write(_int32(_constituents.length));
        stream.write(_float64(_longitudes[_firstColumn]));
        stream.write(_float64(_longitudes[1] - _longitudes[0]));
        stream.write(_int32(_columnCount));
        stream.write(_float64(_latitudes[_firstRow]));
        stream.write(_float64(_latitudes[1] - _latitudes[0]));
        stream.write(_int32(_rowCount));
        stream.write(_int32(_oceanCount));

        ByteBuffer bitmap = ByteBuffer.allocate(_occupancy.length * 8)
                .order(ByteOrder.LITTLE_ENDIAN);

        for (long word : _occupancy)
        {
            bitmap.putLong(word);
        }

        stream.write(bitmap.array());

        ByteBuffer rows = ByteBuffer.allocate(_rowCount * 4)
                .order(ByteOrder.LITTLE_ENDIAN);

        for (int start : _rowStart)
        {
            rows.putInt(start);
        }

        stream.write(rows.array());

        for (String constituent : _constituents)
        {
            _writeConstituent(stream, constituent);
        }

        stream.close();

        System.out.printf(Locale.US, "%nwrote %s: %.1f MB, %d constituents, step %.3f cm%n",
                output.getName(), output.length() / 1048576.0,
                _constituents.length, _step);
    }

    private void _writeConstituent(OutputStream stream, String constituent) throws IOException
    {
        Hdf5Simple source = new Hdf5Simple(_fileOf(constituent).getPath());

        double[] amplitude = source.readAsDouble("amplitude");
        double[] phase = source.readAsDouble("phase");

        source.close();

        int sourceColumns = _longitudes.length;

        ByteBuffer realValues = ByteBuffer.allocate(_oceanCount * 2)
                .order(ByteOrder.LITTLE_ENDIAN);
        ByteBuffer imaginaryValues = ByteBuffer.allocate(_oceanCount * 2)
                .order(ByteOrder.LITTLE_ENDIAN);

        double largest = 0.0;
        int clamped = 0;

        for (int row = 0; row < _rowCount; row++)
        {
            for (int column = 0; column < _columnCount; column++)
            {
                int cell = row * _columnCount + column;

                if ((_occupancy[cell >>> 6] & (1L << (cell & 63))) == 0L)
                {
                    continue;
                }

                int offset = (_firstRow + row) * sourceColumns + _firstColumn + column;

                double amp = amplitude[offset];
                double pha = phase[offset];

                double real = 0.0;
                double imaginary = 0.0;

                if (_isValid(amp, pha))
                {
                    // Same convention as the reference implementations:
                    // the complex constant is amplitude * exp(-i * phase).
                    double radians = Math.toRadians(pha);

                    real = amp * Math.cos(radians);
                    imaginary = -amp * Math.sin(radians);

                    largest = Math.max(largest, Math.abs(amp));
                }

                if (Math.abs(real) / _step > 32767.0 || Math.abs(imaginary) / _step > 32767.0)
                {
                    clamped++;
                }

                realValues.putShort(_quantise(real / _step));
                imaginaryValues.putShort(_quantise(imaginary / _step));
            }
        }

        stream.write(_name(constituent));
        stream.write(_float32((float) _step));
        stream.write(_float32((float) _step));
        stream.write(realValues.array());
        stream.write(imaginaryValues.array());

        System.out.printf(Locale.US,
                "  %-4s largest amplitude %8.2f cm, clamped cells %d%n",
                constituent, largest, clamped);
    }

    private File _fileOf(String constituent)
    {
        return new File(_directory, constituent.toUpperCase(Locale.US) + "_ocean_eot20.nc");
    }

    /**
     * The distribution marks land with a fill value, and a cell whose
     * amplitude and phase are both exactly zero carries no information.
     */
    private boolean _isValid(double amplitude, double phase)
    {
        return !Double.isNaN(amplitude)
                && !Double.isNaN(phase)
                && Math.abs(amplitude) < 1.0e10
                && Math.abs(phase) < 1.0e10
                && (amplitude != 0.0 || phase != 0.0);
    }

    private short _quantise(double value)
    {
        long rounded = Math.round(value);

        if (rounded > 32767L)
        {
            rounded = 32767L;
        }

        if (rounded < -32767L)
        {
            rounded = -32767L;
        }

        return (short) rounded;
    }

    private int[] _rangeOf(double[] axis, double minimum, double maximum)
    {
        int first = -1;
        int last = -1;

        for (int index = 0; index < axis.length; index++)
        {
            if (axis[index] >= minimum && axis[index] <= maximum)
            {
                if (first < 0)
                {
                    first = index;
                }

                last = index;
            }
        }

        return new int[] { first, last - first + 1 };
    }

    private byte[] _name(String constituent)
    {
        byte[] padded = new byte[8];
        byte[] raw = constituent.getBytes(StandardCharsets.US_ASCII);

        System.arraycopy(raw, 0, padded, 0, raw.length);

        return padded;
    }

    private byte[] _int32(int value)
    {
        return ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(value).array();
    }

    private byte[] _float32(float value)
    {
        return ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putFloat(value).array();
    }

    private byte[] _float64(double value)
    {
        return ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putDouble(value).array();
    }
}
