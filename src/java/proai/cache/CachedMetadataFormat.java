package proai.cache;

import proai.MetadataFormat;

public class CachedMetadataFormat implements MetadataFormat {

    private int m_key;
    private String m_prefix;
    private String m_namespaceURI;
    private String m_schemaLocation;
    private String m_dissemination;

    public CachedMetadataFormat(int key,
                              String prefix,
                              String namespaceURI,
                              String schemaLocation,
                              String dissemination) {
        m_key = key;
        m_prefix = prefix;
        m_namespaceURI = namespaceURI;
        m_schemaLocation = schemaLocation;
    }

    public int getKey() {
        return m_key;
    }

    public String getPrefix() {
        return m_prefix;
    }

    public String getNamespaceURI() {
        return m_namespaceURI;
    }

    public String getSchemaLocation() {
        return m_schemaLocation;
    }
    
    public String getDissemination() {
        return m_dissemination;
    }

}