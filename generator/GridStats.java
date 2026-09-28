/*
 * Amplitude distribution of each EOT20 constituent, used to pick the
 * quantisation step and to spot cells holding implausible values.
 *
 * Licensed under MIT (see LICENSE).
 */
package generator;

import java.io.File;
import java.util.Arrays;
import java.util.Locale;

/** Reports how the amplitudes are spread, to size the quantisation sensibly. */
public final class GridStats
{
    public static void main(String[] arguments) throws Exception
    {
        File directory = new File(arguments[0]);

        String[] names = arguments.length > 1
                ? arguments[1].split(",")
                : new String[] { "2N2", "J1", "K1", "K2", "M2", "M4", "MF", "MM",
                        "N2", "O1", "P1", "Q1", "S1", "S2", "SA", "SSA", "T2" };

        System.out.printf(Locale.US, "%-5s %10s %10s %10s %10s %10s%n",
                "name", "median", "p99", "p99.99", "max", ">100cm");

        for (String name : names)
        {
            File file = new File(directory, name + "_ocean_eot20.nc");
            Hdf5Simple netcdf = new Hdf5Simple(file.getPath());

            double[] amplitude = netcdf.readAsDouble("amplitude");
            double[] phase = netcdf.readAsDouble("phase");

            int valid = 0;

            for (int index = 0; index < amplitude.length; index++)
            {
                double amp = amplitude[index];
                double pha = phase[index];

                boolean ok = !Double.isNaN(amp)
                        && Math.abs(amp) < 1.0e10
                        && (amp != 0.0 || pha != 0.0);

                if (ok)
                {
                    amplitude[valid++] = Math.abs(amp);
                }
            }

            double[] values = Arrays.copyOf(amplitude, valid);
            Arrays.sort(values);

            int above = 0;

            for (int index = valid - 1; index >= 0 && values[index] > 100.0; index--)
            {
                above++;
            }

            System.out.printf(Locale.US, "%-5s %10.2f %10.2f %10.2f %10.2f %10d%n",
                    name.toLowerCase(Locale.US),
                    values[valid / 2],
                    values[(int) (valid * 0.99)],
                    values[(int) (valid * 0.9999)],
                    values[valid - 1],
                    above);

            netcdf.close();
        }
    }
}
