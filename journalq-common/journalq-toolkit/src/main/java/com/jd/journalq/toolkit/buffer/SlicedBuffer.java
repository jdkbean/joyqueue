package com.jd.journalq.toolkit.buffer;

import com.jd.journalq.toolkit.buffer.bytes.Bytes;

/**
 * 切片缓冲器.
 * <p>
 * The sliced buffer provides a view of a subset of an underlying buffer. This buffer operates directly on the
 * {@link Bytes}
 * underlying the child {@link Buffer} instance.
 */
public class SlicedBuffer extends AbstractBuffer {
    protected final Buffer root;

    SlicedBuffer(final Buffer root, final long offset, final long initialCapacity, final long maxCapacity) {
        super(root.bytes(), offset, initialCapacity, maxCapacity);
        this.root = root;
        root.acquire();
    }

    /**
     * Returns the root buffer.
     *
     * @return The root buffer.
     */
    public Buffer root() {
        return root;
    }

    @Override
    public boolean isDirect() {
        return root.isDirect();
    }

    @Override
    protected void compact(final long from, final long to, final long length) {
        if (root instanceof AbstractBuffer) {
            ((AbstractBuffer) root).compact(from, to, length);
        }
    }

    @Override
    public boolean isFile() {
        return root.isFile();
    }

    @Override
    public boolean isReadOnly() {
        return root.isReadOnly();
    }

    @Override
    public Buffer compact() {
        return null;
    }

    @Override
    public void acquire() {
        root.acquire();
    }

    @Override
    public boolean release() {
        return root.release();
    }

    @Override
    public void close() {
        root.close();
    }

}