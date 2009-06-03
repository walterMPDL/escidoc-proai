package proai.cache;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.util.Iterator;
import java.util.Vector;

import proai.Writable;
import proai.error.ServerException;

public class CachedContent implements Writable {

    private File m_file;

    private String m_dateStamp;
    private boolean m_headerOnly;
    private boolean m_cacheTimeStamps;
    private String m_earliestDateStamp;
    private String m_string;
    private Vector<String> m_setSpecs = null; 
    public CachedContent(File file) {
        m_file = file;
    }

    public CachedContent(File file, String earliestDateStamp) {
        m_file = file;
        m_earliestDateStamp = earliestDateStamp;
        
    }
    public CachedContent(File file, String dateStamp, Vector<String> setSpecs,
        boolean headerOnly, boolean isCacheTimeStamps) {
        m_file = file;
        m_dateStamp = dateStamp;
        m_headerOnly = headerOnly;
        m_setSpecs = setSpecs;
        m_cacheTimeStamps = isCacheTimeStamps;
    }

    public CachedContent(String content) {
        m_string = content;
    }
    
    

    public void write(PrintWriter out) throws ServerException {
        if (m_file != null) {
            if (m_earliestDateStamp != null) {
                writeChangedIdentify(out);
            }
            else if (m_dateStamp == null) {
                BufferedReader reader = null;
                try {
                    reader = new BufferedReader(
                                 new InputStreamReader(
                                     new FileInputStream(m_file), "UTF-8"));
                    String line = reader.readLine();
                    while (line != null) {
                        out.println(line);
                        line = reader.readLine();
                    }
                } catch (Exception e) {
                    throw new ServerException("Error reading from file: " + m_file.getPath(), e);
                } finally {
                    if (reader != null) try { reader.close(); } catch (Exception e) { }
                }
            } else {
                // need to read the file while changing the <datestamp>,
                // and only output things inside <header> if m_headerOnly
                writeChanged(out);
            }
        } else {
            out.println(m_string);
        }
    }
    
    private void writeChangedIdentify(PrintWriter out) {
        BufferedReader reader = null;
        try {
            reader = new BufferedReader(
                         new InputStreamReader(
                             new FileInputStream(m_file), "UTF-8"));
            StringBuffer identifyBuffer = new StringBuffer();
            String line = reader.readLine();
            while (line != null) {
                identifyBuffer.append(line);
                line = reader.readLine();    
            }
            //replace a real earlistDateStamp of the repository with a earliest cache update time stamp
            String fixed = identifyBuffer.toString().replaceFirst(
                "earliestDatestamp>[^<]+<", "earliestDatestamp>" + m_earliestDateStamp + "<");
            out.println(fixed);
        } catch (Exception e) {
            throw new ServerException("Error reading from file: " + m_file.getPath(), e);
        } finally {
            if (reader != null) try { reader.close(); } catch (Exception e) { }
        }   
    }

    private void writeChanged(PrintWriter out) throws ServerException {
        BufferedReader reader = null;
        try {
            reader = new BufferedReader(
                         new InputStreamReader(
                             new FileInputStream(m_file), "UTF-8"));
          
            Iterator<String> iterator = m_setSpecs.iterator();
            StringBuffer setSpecsBuffer = new StringBuffer();
            while (iterator.hasNext()) {
                String setSpec = iterator.next();
                setSpecsBuffer.append("    <setSpec>" + setSpec
                + "</setSpec>" + "\n");
                
            }
            String setSpecs = setSpecsBuffer.toString();
            StringBuffer upToHeaderEnd = new StringBuffer();
            String line = reader.readLine();
            
            boolean sawHeaderEnd = false;
            while (line != null && !sawHeaderEnd) {
                upToHeaderEnd.append(line + "\n");
                if (line.indexOf("</h") != -1) {
                    sawHeaderEnd = true;
                }
                    line = reader.readLine();
            }
            if (!sawHeaderEnd) throw new ServerException("While parsing, never saw </header>");
            String fixed = upToHeaderEnd.toString();
            if (m_cacheTimeStamps) {
            fixed = fixed.replaceFirst("p>[^<]+<", "p>" + m_dateStamp + "<");
            }
            if(setSpecs.length() > 0) {
                fixed = fixed.replaceFirst("</header>", setSpecs + "</header>"  );
            }
            if (m_headerOnly) {
                int headerStart = fixed.indexOf("<h");
                if (headerStart == -1) throw new ServerException("While parsing, never saw <header...");
                fixed = fixed.substring(headerStart);
                int headerEnd = fixed.indexOf("</h"); // we already know this exists
                fixed = fixed.substring(0, headerEnd) + "</header>";
                out.println(fixed);
            } else {
                out.print(fixed);
                out.println(line);
                line = reader.readLine();
                while (line != null) {
                    out.println(line);
                    line = reader.readLine();
                }
            }
        } catch (Exception e) {
            throw new ServerException("Error reading/transforming file: " + m_file.getPath(), e);
        } finally {
            if (reader != null) try { reader.close(); } catch (Exception e) { }
        }

    }

}