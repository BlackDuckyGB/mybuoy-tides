/*
 * Minimal HDF5 reader.
 *
 * Covers exactly what the EOT20 netCDF-4 files use: superblock version 0,
 * version 2 object headers ("OHDR") whose group members are plain link
 * messages, and contiguous unfiltered datasets of floating point values.
 * Anything outside that is rejected rather than guessed at.
 *
 * Licensed under MIT (see LICENSE).
 */
package generator;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

/** Reads contiguous float datasets out of a simple HDF5 file. */
public final class Hdf5Simple implements AutoCloseable
{
    /** One dataset located in the file. */
    public static final class Dataset
    {
        public String name;
        public long[] shape;
        public int elementSize;
        public int typeClass;
        public long dataAddress = -1L;
        public long dataSize;

        public long elementCount()
        {
            long count = 1L;

            for (long size : shape)
            {
                count *= size;
            }

            return count;
        }
    }

    private static final int MESSAGE_DATASPACE = 0x0001;
    private static final int MESSAGE_DATATYPE = 0x0003;
    private static final int MESSAGE_LAYOUT = 0x0008;
    private static final int MESSAGE_LINK = 0x0006;
    private static final int MESSAGE_CONTINUATION = 0x0010;

    private static final long UNDEFINED_ADDRESS = -1L;

    private final RandomAccessFile _file;
    private final FileChannel _channel;
    private final int _offsetSize;
    private final int _lengthSize;
    private final Map<String, Dataset> _datasets = new LinkedHashMap<>();

    public Hdf5Simple(String path) throws IOException
    {
        _file = new RandomAccessFile(path, "r");
        _channel = _file.getChannel();

        byte[] superblock = _read(0L, 96);

        if (superblock[0] != (byte) 0x89
                || superblock[1] != 'H'
                || superblock[2] != 'D'
                || superblock[3] != 'F')
        {
            _file.close();
            throw new IOException("Not an HDF5 file: " + path);
        }

        int version = superblock[8] & 0xFF;

        if (version != 0)
        {
            _file.close();
            throw new IOException("Unsupported HDF5 superblock version: " + version);
        }

        _offsetSize = superblock[13] & 0xFF;
        _lengthSize = superblock[14] & 0xFF;

        ByteBuffer buffer = ByteBuffer.wrap(superblock).order(ByteOrder.LITTLE_ENDIAN);

        // Root group symbol table entry: link name offset, then the address
        // of the root object header.
        buffer.position(56 + _offsetSize);

        long rootObjectHeader = _offset(buffer);

        _readObject(rootObjectHeader, "", null);
    }

    /** Datasets found, keyed by name. */
    public Map<String, Dataset> datasets()
    {
        return _datasets;
    }

    /** Reads a whole dataset and widens it to double. */
    public double[] readAsDouble(String name) throws IOException
    {
        Dataset dataset = _datasets.get(name);

        if (dataset == null)
        {
            throw new IOException("No such dataset: " + name);
        }

        if (dataset.dataAddress < 0L)
        {
            throw new IOException("Dataset is not stored contiguously: " + name);
        }

        if (dataset.typeClass != 1)
        {
            throw new IOException("Dataset is not floating point: " + name);
        }

        int count = (int) dataset.elementCount();
        double[] values = new double[count];

        int chunk = 1 << 18;
        ByteBuffer buffer = ByteBuffer.allocate(chunk * dataset.elementSize)
                .order(ByteOrder.LITTLE_ENDIAN);

        int index = 0;

        while (index < count)
        {
            int take = Math.min(chunk, count - index);

            buffer.clear();
            buffer.limit(take * dataset.elementSize);

            _channel.position(dataset.dataAddress + (long) index * dataset.elementSize);

            while (buffer.hasRemaining())
            {
                if (_channel.read(buffer) < 0)
                {
                    throw new IOException("Truncated dataset: " + name);
                }
            }

            buffer.flip();

            for (int step = 0; step < take; step++)
            {
                values[index + step] = dataset.elementSize == 4
                        ? buffer.getFloat()
                        : buffer.getDouble();
            }

            index += take;
        }

        return values;
    }

    @Override
    public void close() throws IOException
    {
        _file.close();
    }

    /**
     * Reads one object header. Link messages make the object a group and its
     * children are followed; a dataspace plus a contiguous layout make it a
     * dataset, which is then recorded under its name.
     */
    private void _readObject(long address, String name, Dataset inherited) throws IOException
    {
        byte[] prefix = _read(address, 16);
        ByteBuffer buffer = ByteBuffer.wrap(prefix).order(ByteOrder.LITTLE_ENDIAN);

        byte[] signature = new byte[4];
        buffer.get(signature);

        if (signature[0] != 'O' || signature[1] != 'H' || signature[2] != 'D' || signature[3] != 'R')
        {
            throw new IOException("Expected an OHDR object header at " + address);
        }

        int version = buffer.get() & 0xFF;

        if (version != 2)
        {
            throw new IOException("Unsupported object header version: " + version);
        }

        int flags = buffer.get() & 0xFF;

        int position = 6;

        if ((flags & 0x20) != 0)
        {
            // Access, modification, change and birth times.
            position += 16;
        }

        if ((flags & 0x10) != 0)
        {
            // Maximum compact and minimum dense attribute counts.
            position += 4;
        }

        int sizeWidth = 1 << (flags & 0x03);

        byte[] sizeField = _read(address + position, sizeWidth);
        long chunkSize = _unsigned(sizeField);

        position += sizeWidth;

        boolean creationOrderTracked = (flags & 0x04) != 0;

        Dataset dataset = inherited != null ? inherited : new Dataset();
        dataset.name = name;

        _readMessages(address + position, chunkSize, creationOrderTracked, name, dataset);

        boolean isDataset = dataset.shape != null && dataset.dataAddress >= 0L;

        if (isDataset && !name.isEmpty())
        {
            _datasets.put(name, dataset);
        }
    }

    private void _readMessages(long start,
                               long size,
                               boolean creationOrderTracked,
                               String name,
                               Dataset dataset) throws IOException
    {
        byte[] block = _read(start, (int) size);
        ByteBuffer buffer = ByteBuffer.wrap(block).order(ByteOrder.LITTLE_ENDIAN);

        int headerWidth = creationOrderTracked ? 6 : 4;

        while (buffer.remaining() > headerWidth)
        {
            int type = buffer.get() & 0xFF;
            int messageSize = buffer.getShort() & 0xFFFF;
            buffer.get();

            if (creationOrderTracked)
            {
                buffer.getShort();
            }

            if (buffer.remaining() < messageSize)
            {
                break;
            }

            byte[] payload = new byte[messageSize];
            buffer.get(payload);

            ByteBuffer message = ByteBuffer.wrap(payload).order(ByteOrder.LITTLE_ENDIAN);

            switch (type)
            {
                case MESSAGE_DATASPACE:
                    _readDataspace(message, dataset);
                    break;

                case MESSAGE_DATATYPE:
                    _readDatatype(message, dataset);
                    break;

                case MESSAGE_LAYOUT:
                    _readLayout(message, dataset);
                    break;

                case MESSAGE_LINK:
                    _readLink(message, name);
                    break;

                case MESSAGE_CONTINUATION:
                {
                    long continuationAddress = _offset(message);
                    long continuationSize = _length(message);

                    // A continuation block opens with an "OCHK" signature and
                    // closes with a checksum.
                    _readMessages(continuationAddress + 4L, continuationSize - 8L,
                            creationOrderTracked, name, dataset);
                    break;
                }

                default:
                    break;
            }
        }
    }

    private void _readLink(ByteBuffer message, String prefix) throws IOException
    {
        int version = message.get() & 0xFF;

        if (version != 1)
        {
            return;
        }

        int flags = message.get() & 0xFF;

        int linkType = 0;

        if ((flags & 0x08) != 0)
        {
            linkType = message.get() & 0xFF;
        }

        if ((flags & 0x04) != 0)
        {
            message.getLong();
        }

        if ((flags & 0x10) != 0)
        {
            message.get();
        }

        int lengthWidth = 1 << (flags & 0x03);
        long nameLength = 0L;

        for (int index = 0; index < lengthWidth; index++)
        {
            nameLength |= ((long) (message.get() & 0xFF)) << (8 * index);
        }

        byte[] rawName = new byte[(int) nameLength];
        message.get(rawName);

        String name = new String(rawName, StandardCharsets.UTF_8);

        if (linkType != 0)
        {
            // Soft and external links are not followed.
            return;
        }

        long objectHeader = _offset(message);

        String full = prefix.isEmpty() ? name : prefix + "/" + name;

        _readObject(objectHeader, full, null);
    }

    private void _readDataspace(ByteBuffer message, Dataset dataset)
    {
        int version = message.get() & 0xFF;
        int rank = message.get() & 0xFF;
        int flags = message.get() & 0xFF;

        if (version == 1)
        {
            message.position(message.position() + 5);
        }
        else
        {
            // Version 2 carries a single dataspace type byte.
            message.get();
        }

        long[] shape = new long[rank];

        for (int axis = 0; axis < rank; axis++)
        {
            shape[axis] = _length(message);
        }

        dataset.shape = shape;
    }

    private void _readDatatype(ByteBuffer message, Dataset dataset)
    {
        int classAndVersion = message.get() & 0xFF;

        dataset.typeClass = classAndVersion & 0x0F;

        message.position(message.position() + 3);
        dataset.elementSize = message.getInt();
    }

    private void _readLayout(ByteBuffer message, Dataset dataset)
    {
        int version = message.get() & 0xFF;

        if (version != 3 && version != 4)
        {
            return;
        }

        int layoutClass = message.get() & 0xFF;

        if (layoutClass != 1)
        {
            // 0 is compact and 2 is chunked; neither occurs in these files.
            return;
        }

        long address = _offset(message);

        if (address == UNDEFINED_ADDRESS)
        {
            return;
        }

        dataset.dataAddress = address;
        dataset.dataSize = _length(message);
    }

    private long _offset(ByteBuffer buffer)
    {
        return _offsetSize == 8 ? buffer.getLong() : Integer.toUnsignedLong(buffer.getInt());
    }

    private long _length(ByteBuffer buffer)
    {
        return _lengthSize == 8 ? buffer.getLong() : Integer.toUnsignedLong(buffer.getInt());
    }

    private long _unsigned(byte[] raw)
    {
        long value = 0L;

        for (int index = 0; index < raw.length; index++)
        {
            value |= ((long) (raw[index] & 0xFF)) << (8 * index);
        }

        return value;
    }

    private byte[] _read(long address, int size) throws IOException
    {
        ByteBuffer buffer = ByteBuffer.allocate(size);
        _channel.position(address);

        while (buffer.hasRemaining())
        {
            if (_channel.read(buffer) < 0)
            {
                break;
            }
        }

        return buffer.array();
    }
}
