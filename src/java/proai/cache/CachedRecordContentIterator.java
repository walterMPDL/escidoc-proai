package proai.cache;

import java.util.Vector;

import proai.CloseableIterator;
import proai.error.ServerException;

public class CachedRecordContentIterator implements CloseableIterator<CachedContent> {

    private CloseableIterator<Vector<String>> m_arrays;
    private RCDisk m_rcDisk;
    private boolean m_identifiers;

    private boolean m_closed;

    public CachedRecordContentIterator(CloseableIterator<Vector<String>> paths,
                                       RCDisk rcDisk,
                                       boolean identifiers) {
        m_arrays = paths;
        m_rcDisk = rcDisk;
        m_identifiers = identifiers;

        m_closed = false;
    }

    public boolean hasNext() throws ServerException {
        return m_arrays.hasNext();
    }

    public CachedContent next() throws ServerException {
        if (!hasNext()) return null;
        try {
            Vector<String> array = m_arrays.next();
            return m_rcDisk.getContent(array.remove(0), array.remove(0), array, m_identifiers);
        } catch (Exception e) {
            close();
            throw new ServerException("Could not get next record content from iterator", e);
        }
    }

    public void close() {
        if (!m_closed) {
            m_closed = true;
            m_arrays.close();
        }
    }

    public void finalize() {
        close();
    }

    public void remove() throws UnsupportedOperationException {
        throw new UnsupportedOperationException("CachedRecordContentIterator does not support remove().");
    }

}