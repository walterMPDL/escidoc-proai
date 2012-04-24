package proai.cache;

import java.io.IOException;
import java.io.InputStream;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLConnection;
import java.util.HashMap;
import java.util.Map;

import javax.xml.validation.Schema;

import org.xml.sax.SAXException;

public class SchemaLocator {
    private static Map<String, Schema> schemaCache = new HashMap<String, Schema>();
    /**
     * Gets the Schema object for the provided md predix.
     * 
     * @param mdPrefix
     *            The mdPrefix to get the schema for.
     * @return Returns the Schema object.
     * @throws Exception
     *             If anything fails.
     */
    private Schema getSchema(final String mdPrefix) throws MalformedURLException,
    IOException, SAXException {
        Schema schema = schemaCache.get(mdPrefix);
        
        return schema;
    }
    
}
