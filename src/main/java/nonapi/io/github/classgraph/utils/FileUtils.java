/*
 * This file is part of ClassGraph.
 *
 * Author: Luke Hutchison
 *
 * Hosted at: https://github.com/classgraph/classgraph
 *
 * --
 *
 * The MIT License (MIT)
 *
 * Copyright (c) 2018 Luke Hutchison
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy of this software and associated
 * documentation files (the "Software"), to deal in the Software without restriction, including without
 * limitation the rights to use, copy, modify, merge, publish, distribute, sublicense, and/or sell copies of
 * the Software, and to permit persons to whom the Software is furnished to do so, subject to the following
 * conditions:
 *
 * The above copyright notice and this permission notice shall be included in all copies or substantial
 * portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR IMPLIED, INCLUDING BUT NOT
 * LIMITED TO THE WARRANTIES OF MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO
 * EVENT SHALL THE AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER IN
 * AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE
 * OR OTHER DEALINGS IN THE SOFTWARE.
 */
package nonapi.io.github.classgraph.utils;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.ByteBuffer;
import java.nio.MappedByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.AccessController;
import java.security.PrivilegedAction;
import java.util.AbstractMap.SimpleEntry;
import java.util.Arrays;

/**
 * File utilities.
 */
public class FileUtils {
    /**
     * The current directory path (only reads the current directory once, the first time this field is accessed, so
     * will not reflect subsequent changes to the current directory).
     */
    public static final String CURR_DIR_PATH;

    static {
        String currDirPathStr = "";
        try {
            // The result is moved to currDirPathStr after each step, so we can provide fine-grained debug info and
            // a best guess at the path, if the current dir doesn't exist (#109), or something goes wrong while
            // trying to get the current dir path.
            Path currDirPath = Paths.get("").toAbsolutePath();
            currDirPathStr = currDirPath.toString();
            currDirPath = currDirPath.normalize();
            currDirPathStr = currDirPath.toString();
            currDirPath = currDirPath.toRealPath(LinkOption.NOFOLLOW_LINKS);
            currDirPathStr = currDirPath.toString();
            currDirPathStr = FastPathResolver.resolve(currDirPathStr);
        } catch (final IOException e) {
            throw new RuntimeException("Could not resolve current directory: " + currDirPathStr, e);
        }
        CURR_DIR_PATH = currDirPathStr;
    }

    // -------------------------------------------------------------------------------------------------------------

    /**
     * The minimum filesize at which it becomes more efficient to read a file with a memory-mapped file channel
     * rather than an InputStream. Based on benchmark testing using the following benchmark, averaged over three
     * separate runs, then plotted as a speedup curve for 1, 2, 4 and 8 concurrent threads:
     * 
     * https://github.com/lukehutch/FileReadingBenchmark
     */
    public static final int FILECHANNEL_FILE_SIZE_THRESHOLD;

    static {
        switch (VersionFinder.OS) {
        case Linux:
            // On Linux, FileChannel is more efficient once file sizes are larger than 16kb,
            // and the speedup increases superlinearly, reaching 1.5-3x for a filesize of 1MB
            // (and the performance increase does not level off at 1MB either -- that is as
            // far as this was benchmarked).
        case MacOSX:
            // On older/slower Mac OS X machines, FileChannel is always 10-20% slower than InputStream,
            // except for very large files (>1MB), and only for single-threaded reading.
            // But on newer/faster Mac OS X machines, you get a 10-20% speedup between 16kB and 128kB,
            // then a much larger speedup for files larger than 128kb (topping out at about 2.5x speedup).
            // It's probably worth setting the threshold to 16kB to get the 10-20% speedup for files
            // larger than 16kB in size on modern machines.
        case Solaris:
        case BSD:
        case Unix:
            // No testing has been performed yet on the other unices, so just pick the same val as MacOSX and Linux
            FILECHANNEL_FILE_SIZE_THRESHOLD = 16384;
            break;

        case Windows:
            // Windows is always 10-20% faster with FileChannel than with InputStream, even for small files.
            FILECHANNEL_FILE_SIZE_THRESHOLD = -1;
            break;

        case Unknown:
            // For any other operating system
        default:
            FILECHANNEL_FILE_SIZE_THRESHOLD = 16384;
        }
    }

    // -------------------------------------------------------------------------------------------------------------

    /** The default size of a file buffer. */
    private static final int DEFAULT_BUFFER_SIZE = 16384;

    /**
     * The maximum size of a file buffer array. Eight bytes smaller than {@link Integer#MAX_VALUE}, since some VMs
     * reserve header words in arrays.
     */
    public static final int MAX_BUFFER_SIZE = Integer.MAX_VALUE - 8;

    /** The maximum initial buffer size. */
    private static final int MAX_INITIAL_BUFFER_SIZE = 16 * 1024 * 1024;

    /**
     * Read all the bytes in an {@link InputStream}.
     * 
     * @param inputStream
     *            The {@link InputStream}.
     * @param fileSizeHint
     *            The file size, if known, otherwise -1L.
     * @return The contents of the {@link InputStream} as an Entry consisting of the byte array and number of bytes
     *         used in the array.
     * @throws IOException
     *             If the contents could not be read.
     */
    private static SimpleEntry<byte[], Integer> readAllBytes(final InputStream inputStream, final long fileSizeHint)
            throws IOException {
        if (fileSizeHint > MAX_BUFFER_SIZE) {
            throw new IOException("InputStream is too large to read");
        }
        final int bufferSize = fileSizeHint < 1L
                // If fileSizeHint is unknown, use default buffer size 
                ? DEFAULT_BUFFER_SIZE
                // fileSizeHint is just a hint -- limit the max allocated buffer size, so that invalid ZipEntry
                // lengths do not become a memory allocation attack vector
                : Math.min((int) fileSizeHint, MAX_INITIAL_BUFFER_SIZE);
        byte[] buf = new byte[bufferSize];

        int bufLength = buf.length;
        int totBytesRead = 0;
        for (int bytesRead;;) {
            // Fill buffer -- may fill more or fewer bytes than buffer size
            while ((bytesRead = inputStream.read(buf, totBytesRead, bufLength - totBytesRead)) > 0) {
                totBytesRead += bytesRead;
            }
            if (bytesRead < 0) {
                // Reached end of stream
                break;
            }
            // bytesRead == 0 => grow buffer, avoiding overflow
            if (bufLength <= MAX_BUFFER_SIZE - bufLength) {
                bufLength = bufLength << 1;
            } else {
                if (bufLength == MAX_BUFFER_SIZE) {
                    throw new IOException("InputStream too large to read");
                }
                bufLength = MAX_BUFFER_SIZE;
            }
            buf = Arrays.copyOf(buf, bufLength);
        }
        // Return buffer and number of bytes read
        return new SimpleEntry<>((bufLength == totBytesRead) ? buf : Arrays.copyOf(buf, totBytesRead),
                totBytesRead);
    }

    /**
     * Read all the bytes in an {@link InputStream} as a byte array.
     * 
     * @param inputStream
     *            The {@link InputStream}.
     * @param fileSize
     *            The file size, if known, otherwise -1L.
     * @return The contents of the {@link InputStream} as a byte array.
     * @throws IOException
     *             If the contents could not be read.
     */
    public static byte[] readAllBytesAsArray(final InputStream inputStream, final long fileSize)
            throws IOException {
        final SimpleEntry<byte[], Integer> ent = readAllBytes(inputStream, fileSize);
        final byte[] buf = ent.getKey();
        final int bufBytesUsed = ent.getValue();
        return (buf.length == bufBytesUsed) ? buf : Arrays.copyOf(buf, bufBytesUsed);
    }

    /**
     * Read all the bytes in an {@link InputStream} as a String.
     * 
     * @param inputStream
     *            The {@link InputStream}.
     * @param fileSize
     *            The file size, if known, otherwise -1L.
     * @return The contents of the {@link InputStream} as a String.
     * @throws IOException
     *             If the contents could not be read.
     */
    public static String readAllBytesAsString(final InputStream inputStream, final long fileSize)
            throws IOException {
        final SimpleEntry<byte[], Integer> ent = readAllBytes(inputStream, fileSize);
        final byte[] buf = ent.getKey();
        final int bufBytesUsed = ent.getValue();
        return new String(buf, 0, bufBytesUsed, StandardCharsets.UTF_8);
    }

    // -------------------------------------------------------------------------------------------------------------

    /**
     * Produce an {@link InputStream} that is able to read from a {@link ByteBuffer}.
     * 
     * @param byteBuffer
     *            The {@link ByteBuffer}.
     * @return An {@link InputStream} that reads from the {@link ByteBuffer}.
     */
    public static InputStream byteBufferToInputStream(final ByteBuffer byteBuffer) {
        // https://stackoverflow.com/questions/4332264/wrapping-a-bytebuffer-with-an-inputstream/6603018#6603018
        return new InputStream() {
            final ByteBuffer buf = byteBuffer;

            @Override
            public int read() {
                if (!buf.hasRemaining()) {
                    return -1;
                }
                return buf.get() & 0xFF;
            }

            @Override
            public int read(final byte[] bytes, final int off, final int len) {
                if (!buf.hasRemaining()) {
                    return -1;
                }

                final int bytesRead = Math.min(len, buf.remaining());
                buf.get(bytes, off, bytesRead);
                return bytesRead;
            }
        };
    }

    // -------------------------------------------------------------------------------------------------------------

    /** Sanitize relative paths against "zip slip" vulnerability. */
    public static String sanitizeEntryPath(final String entryPath) {
        String path = entryPath;
        // Remove path segments if "/../" is found
        int idx2 = path.indexOf("/../");
        if (idx2 >= 0) {
            final StringBuilder buf = new StringBuilder();
            for (int src = 0;;) {
                buf.append(path, src, idx2);
                final int lastSlash = buf.lastIndexOf("/");
                if (lastSlash >= 0 && lastSlash < buf.length() - 1) {
                    buf.setLength(lastSlash);
                }
                src = idx2 + 3;
                idx2 = path.indexOf("/../", src);
                if (idx2 < 0) {
                    buf.append(path, src, path.length());
                    break;
                }
            }
            path = buf.toString();
        }
        // Replace "/./" with "/"
        int idx1 = path.indexOf("/./");
        if (idx1 >= 0) {
            final StringBuilder buf = new StringBuilder();
            for (int src = 0;;) {
                buf.append(path, src, idx1);
                src = idx1 + 2;
                idx1 = path.indexOf("/./", src);
                if (idx1 < 0) {
                    buf.append(path, src, path.length());
                    break;
                }
            }
            path = buf.toString();
        }
        // Replace "//" with "/"
        int idx3 = path.indexOf("//");
        if (idx3 >= 0) {
            final StringBuilder buf = new StringBuilder();
            for (int src = 0;;) {
                buf.append(path, src, idx3);
                src = idx3 + 1;
                idx3 = path.indexOf("//", src);
                if (idx3 < 0) {
                    buf.append(path, src, path.length());
                    break;
                }
            }
            path = buf.toString();
        }
        // Strip off leading "./" or "../", if present
        while (path.startsWith("./") || path.startsWith("../")) {
            path = path.startsWith("./") ? path.substring(2) : path.substring(3);
        }
        // Strip off leading '/', if present
        while (path.startsWith("/")) {
            path = path.substring(1);
        }
        return path;
    }

    // -------------------------------------------------------------------------------------------------------------

    /**
     * @param path
     *            A file path.
     * @return true if path has a ".class" extension, ignoring case.
     */
    public static boolean isClassfile(final String path) {
        final int len = path.length();
        return len > 6 && path.regionMatches(true, len - 6, ".class", 0, 6);
    }

    // -------------------------------------------------------------------------------------------------------------

    /**
     * @param file
     *            A {@link File}.
     * @return true if a file exists and can be read.
     */
    public static boolean canRead(final File file) {
        try {
            return file.canRead();
        } catch (final SecurityException e) {
            return false;
        }
    }

    // -------------------------------------------------------------------------------------------------------------

    private static Method cleanMethod;
    private static Method attachmentMethod;
    private static Object theUnsafe;

    static void getCleanMethodPrivileged() {
        if (VersionFinder.JAVA_MAJOR_VERSION < 9) {
            try {
                // See: https://stackoverflow.com/a/19447758/3950982
                cleanMethod = Class.forName("sun.misc.Cleaner").getMethod("clean");
                cleanMethod.setAccessible(true);
                final Class<?> directByteBufferClass = Class.forName("sun.nio.ch.DirectBuffer");
                attachmentMethod = directByteBufferClass.getMethod("attachment");
                attachmentMethod.setAccessible(true);
            } catch (final SecurityException e) {
                throw new RuntimeException(
                        "You need to grant classgraph RuntimePermission(\"accessClassInPackage.sun.misc\") "
                                + "and ReflectPermission(\"suppressAccessChecks\")");
            } catch (final Exception ex) {
            }
        } else {
            try {
                Class<?> unsafeClass;
                try {
                    unsafeClass = Class.forName("sun.misc.Unsafe");
                } catch (final Exception e) {
                    // jdk.internal.misc.Unsafe doesn't yet have an invokeCleaner() method,
                    // but that method should be added if sun.misc.Unsafe is removed.
                    unsafeClass = Class.forName("jdk.internal.misc.Unsafe");
                }
                cleanMethod = unsafeClass.getMethod("invokeCleaner", ByteBuffer.class);
                cleanMethod.setAccessible(true);
                final Field theUnsafeField = unsafeClass.getDeclaredField("theUnsafe");
                theUnsafeField.setAccessible(true);
                theUnsafe = theUnsafeField.get(null);
            } catch (final SecurityException e) {
                throw new RuntimeException(
                        "You need to grant classgraph RuntimePermission(\"accessClassInPackage.sun.misc\"), "
                                + "RuntimePermission(\"accessClassInPackage.jdk.internal.misc\") "
                                + "and ReflectPermission(\"suppressAccessChecks\")");
            } catch (final Exception ex) {
            }
        }
    }

    static {
        AccessController.doPrivileged(new PrivilegedAction<Object>() {
            @Override
            public Object run() {
                getCleanMethodPrivileged();
                return null;
            }
        });
    }

    private static boolean closeDirectByteBufferPrivileged(final ByteBuffer byteBuffer, final LogNode log) {
        try {
            if (cleanMethod == null) {
                if (log != null) {
                    log.log("Could not unmap ByteBuffer, cleanMethod == null");
                }
                return false;
            }
            if (VersionFinder.JAVA_MAJOR_VERSION < 9) {
                if (attachmentMethod == null) {
                    if (log != null) {
                        log.log("Could not unmap ByteBuffer, attachmentMethod == null");
                    }
                    return false;
                }
                // Make sure duplicates and slices are not cleaned, since this can result in duplicate
                // attempts to clean the same buffer, which trigger a crash with:
                // "A fatal error has been detected by the Java Runtime Environment: EXCEPTION_ACCESS_VIOLATION"
                // See: https://stackoverflow.com/a/31592947/3950982
                if (attachmentMethod.invoke(byteBuffer) != null) {
                    // Buffer is a duplicate or slice
                    return false;
                }
                // Invoke ((DirectBuffer) byteBuffer).cleaner().clean()
                final Method cleaner = byteBuffer.getClass().getMethod("cleaner");
                cleaner.setAccessible(true);
                cleanMethod.invoke(cleaner.invoke(byteBuffer));
                return true;
            } else {
                if (theUnsafe == null) {
                    if (log != null) {
                        log.log("Could not unmap ByteBuffer, theUnsafe == null");
                    }
                    return false;
                }
                // In JDK9+, calling the above code gives a reflection warning on stderr,
                // need to call Unsafe.theUnsafe.invokeCleaner(byteBuffer) , which makes
                // the same call, but does not print the reflection warning.
                try {
                    cleanMethod.invoke(theUnsafe, byteBuffer);
                    return true;
                } catch (final IllegalArgumentException e) {
                    // Buffer is a duplicate or slice
                    return false;
                }
            }
        } catch (final Exception e) {
            if (log != null) {
                log.log("Could not unmap ByteBuffer: " + e);
            }
            return false;
        }
    }

    /**
     * Close a {@code DirectByteBuffer} -- in particular, will unmap a {@link MappedByteBuffer}.
     * 
     * @param byteBuffer
     *            The {@link ByteBuffer} to close/unmap.
     * @param log
     *            The log.
     * @return True if the byteBuffer was closed/unmapped.
     */
    public static boolean closeDirectByteBuffer(final ByteBuffer byteBuffer, final LogNode log) {
        if (byteBuffer != null && byteBuffer.isDirect()) {
            return AccessController.doPrivileged(new PrivilegedAction<Boolean>() {
                @Override
                public Boolean run() {
                    return closeDirectByteBufferPrivileged(byteBuffer, log);
                }
            });
        } else {
            // Nothing to unmap
            return false;
        }
    }
}
