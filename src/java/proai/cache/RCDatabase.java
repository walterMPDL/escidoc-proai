package proai.cache;

import java.io.InputStream;
import java.io.PrintWriter;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Date;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Vector;
import proai.SetInfo;

import org.apache.log4j.Logger;

import proai.CloseableIterator;
import proai.MetadataFormat;
import proai.driver.impl.RemoteIteratorImpl;
import proai.driver.impl.SetInfoImpl;
import proai.error.ServerException;
import proai.util.DBUtil;
import proai.util.DDLConverter;
import proai.util.StreamUtil;
import proai.util.TableSpec;

/**
 * Java interface to the database.
 */
public class RCDatabase {

    private static final Logger logger =
            Logger.getLogger(RCDatabase.class.getName());

    public static final String RCADMIN_TABLE_IS_EMPTY = "rcAdmin table is empty";

    private boolean m_backslashIsEscape;
    private boolean m_mySQLTrickling;
    private RCDisk m_rcDisk;

    public RCDatabase(Connection conn, 
                      DDLConverter ddlc,
                      boolean mySQLTrickling,
                      boolean backslashIsEscape,
                      boolean pollingEnabled,
                      RCDisk rcDisk) throws ServerException {
        m_mySQLTrickling = mySQLTrickling;
        m_backslashIsEscape = backslashIsEscape;
        m_rcDisk = rcDisk;
        if (!tablesExist(conn)) {
            createTables(conn, ddlc);
        }
        createAdminRowIfNeeded(conn);
        setPollingEnabled(conn, pollingEnabled);
    }

    // get a single-quoted, sql-escaped string
    private String qs(String in) {
        return DBUtil.quotedString(in, m_backslashIsEscape);
    }

    // get a single-quoted, sql-escaped string followed by ", " (comma and space)
    private String qsc(String in) {
        return DBUtil.quotedString(in, m_backslashIsEscape) + ", ";
    }

    // get a single-quoted, sql-escaped string followed by " " (space)
    private String qss(String in) {
        return DBUtil.quotedString(in, m_backslashIsEscape) + " ";
    }

    private static ResultSet executeQuery(Statement stmt, 
                                          String sql) throws SQLException {
        if (logger.isDebugEnabled()) {
            logger.debug("Executing query: " + sql);
        }
        return stmt.executeQuery(sql);
    }

    private static int executeUpdate(Statement stmt,
                                     String sql) throws SQLException {
        if (logger.isDebugEnabled()) {
            logger.debug("Executing update: " + sql);
        }
        return stmt.executeUpdate(sql);
    }

    /**
     * Get the relative path to the Identify.xml file in the cache, or
     * <code>null</code> if identifyPath is null (first update cycle hasn't run).
     */
    public String getIdentifyPath(Connection conn) throws ServerException {
        Statement stmt = null;
        ResultSet rs = null;
        try {
            stmt = getStatement(conn, false);
            rs = executeQuery(stmt, "SELECT identifyPath FROM rcAdmin");
            if (rs.next()) {
                return rs.getString(1);
            } else {
                throw new ServerException(RCADMIN_TABLE_IS_EMPTY);
            }
        } catch (SQLException e) {
            throw new ServerException("Error reading rcAdmin.identifyPath", e);
        } finally {
            if (rs != null) try { rs.close(); } catch (Exception e) { }
            if (stmt != null) try { stmt.close(); } catch (Exception e) { }
        }
    }

    /**
     * Get the value of the earlistDateStamp, or
     * <code>null</code> if earlistDateStamp is null (first update cycle hasn't run).
     */
    public long getEarliestDatestamp(Connection conn) throws ServerException {
        Statement stmt = null;
        ResultSet rs = null;
        try {
            stmt = getStatement(conn, false);
            rs = executeQuery(stmt, "SELECT earliestDatestamp FROM rcAdmin");
            if (rs.next()) {
                return rs.getLong(1);
            } else {
                throw new ServerException(RCADMIN_TABLE_IS_EMPTY);
            }
        } catch (SQLException e) {
            throw new ServerException("Error reading rcAdmin.earliestDatestamp", e);
        } finally {
            if (rs != null) try { rs.close(); } catch (Exception e) { }
            if (stmt != null) try { stmt.close(); } catch (Exception e) { }
        }
    }
    private Statement getStatement(Connection conn,
                                   boolean possiblyLong) throws SQLException {
        if (m_mySQLTrickling && possiblyLong) {
            // http://dev.mysql.com/doc/connector/j/en/cj-implementation-notes.html
            Statement stmt = conn.createStatement(ResultSet.TYPE_FORWARD_ONLY,
                                                  ResultSet.CONCUR_READ_ONLY);
            stmt.setFetchSize(Integer.MIN_VALUE);
            return stmt;
        } else {
            return conn.createStatement();
        }
    }

    public void setIdentifyPath(Connection conn, String path) throws ServerException {
        Statement stmt = null;
        try {
            String xmlPathToPrune = getIdentifyPath(conn); 
            stmt = getStatement(conn, false);
            String sql;
            if (path == null) {
                sql = "UPDATE rcAdmin SET identifyPath = NULL";
            } else {
                sql = "UPDATE rcAdmin SET identifyPath = " + qs(path);
            }
            executeUpdate(stmt, sql);
            if (xmlPathToPrune != null) {
                addPrunable(stmt, xmlPathToPrune);
            }
        } catch (SQLException se) {
            throw new ServerException("Error setting rcAdmin.identifyPath", se);
        } finally {
            if (stmt != null) try { stmt.close(); } catch (Exception ex) { }
        }
    }

    public void setPollingEnabled(Connection conn,
                                  boolean pollingEnabled) throws ServerException {
        Statement stmt = null;
        try {
            stmt = getStatement(conn, false);
            int val = 0;
            if (pollingEnabled) val = 1;
            executeUpdate(stmt, "UPDATE rcAdmin SET pollingEnabled = " + val);
        } catch (SQLException se) {
            throw new ServerException("Error setting rcAdmin.pollingEnabled", se);
        } finally {
            if (stmt != null) try { stmt.close(); } catch (Exception ex) { }
        }
    }

    public boolean isPollingEnabled(Connection conn) throws ServerException {
        Statement stmt = null;
        ResultSet rs = null;
        try {
            stmt = getStatement(conn, false);
            rs = executeQuery(stmt, "SELECT pollingEnabled FROM rcAdmin");
            rs.next();
            return (rs.getInt(1) == 1);
        } catch (SQLException e) {
            throw new ServerException("Error reading rcAdmin.pollingEnabled", e);
        } finally {
            if (rs != null) try { rs.close(); } catch (Exception e) { }
            if (stmt != null) try { stmt.close(); } catch (Exception e) { }
        }
    }

    //////////////////////////////////////////////////////////////////////////

    /**
     * Add or modify the given format.
     */
    public FormatChange putFormat(Connection conn, MetadataFormat format)
    throws ServerException {
    FormatChange changed = null;
    String newprefix = format.getPrefix();
    String newuri = format.getNamespaceURI();
    String newloc = format.getSchemaLocation();
    String newdissemination = format.getDissemination();
    Statement stmt = null;
    Statement stmt2 = null;
    Statement newStmt = null;
    ResultSet rs = null;
    ResultSet rs1 = null;
    try {
        stmt = getStatement(conn, false);
        String sql =
            "SELECT formatKey, namespaceURI, schemaLocation, dissemination "
                + "FROM rcFormat " + "WHERE mdPrefix = " + qs(newprefix);
        rs = executeQuery(stmt, sql);

        if (rs.next()) {
            // If nsUri or dissemination of the format changed we should
            // also set the last poll date
            // to the initial value 0 in order to re-cache all records in
            // this format.
            int formatKey = rs.getInt(1);
            String uri = rs.getString(2);
            String loc = rs.getString(3);
            String dissemination = rs.getString(4);
            if ((!uri.equals(newuri))
                || (!dissemination.equals(newdissemination))) {
                logger
                    .info("NsUri or dissemination of the format with prefix "
                        + newprefix
                        + " changed.  Updating in db: update table rcFormat, delete all"
                        + "records of this format from rcRecord, delete membership of these "
                        + "records from rcMembership.");
                sql =
                    "UPDATE rcFormat SET namespaceURI = " + qsc(newuri)
                        + "schemaLocation = " + qsc(newloc)
                        + "dissemination  = " + qsc(newdissemination)
                        + "lastPollDate  = " + 0 + " WHERE formatKey = "
                        + formatKey;
                executeUpdate(stmt, sql);

                // We should remove all existing records in the old format
                // and entries
                // referencing the old format from the tables rcqueue and
                // rcfailure.

                stmt2 = getStatement(conn, false);
                String deleteFromQueue =
                    "DELETE from rcQueue WHERE mdPrefix = '" + newprefix
                        + "'";
                executeUpdate(stmt2, deleteFromQueue);

                String deleteFromFailure =
                    "DELETE from rcFailure WHERE mdPrefix = '" + newprefix
                        + "'";
                executeUpdate(stmt2, deleteFromFailure);

                // first mark xmlPaths of records in this format as prunable
                // and delete set membership for relevant records
                String selectRecordKey =
                    "SELECT recordKey, xmlPath FROM rcRecord WHERE formatKey = "
                        + formatKey;
                newStmt = getStatement(conn, false);
                rs1 = executeQuery(newStmt, selectRecordKey);

                while (rs1.next()) {
                    int recordKey = rs1.getInt(1);
                    String xmlPathToPrune = rs1.getString(2);
                    executeUpdate(stmt,
                        "DELETE from rcMembership WHERE recordKey = "
                            + recordKey);
                    addPrunable(stmt, xmlPathToPrune);
                }
                // then delete the actual records
                executeUpdate(stmt,
                    "DELETE FROM rcRecord WHERE formatKey = " + formatKey);
                changed = FormatChange.uriOrDissemination;
            }
            else if (!loc.equals(newloc)) {
                logger.info("Schema location of the format with prefix "
                    + newprefix + " changed.  Updating in rcFormat.");
                sql =
                    "UPDATE rcFormat SET schemaLocation = " + qss(newloc)
                        + " WHERE formatKey = " + formatKey;
                executeUpdate(stmt, sql);
                changed = FormatChange.schemaLocation;
            }

        }
        else {
            logger.info("Format " + newprefix + " is new.  Adding to db.");
            sql =
                "INSERT INTO rcFormat (mdPrefix, " + "namespaceURI, "
                    + "schemaLocation, dissemination) " + "VALUES ("
                    + qsc(newprefix) + qsc(newuri) + qsc(newloc)
                    + qs(newdissemination) + ")";
            executeUpdate(stmt, sql);
        }
    }
    catch (SQLException se) {
        throw new ServerException(
            "Unable to add/modify format in cache db", se);
    }
    finally {
        if (rs != null)
            try {
                rs.close();
            }
            catch (Exception ex) {
            }
        if (rs1 != null)
            try {
                rs1.close();
            }
            catch (Exception ex) {
            }
        if (stmt != null)
            try {
                stmt.close();
            }
            catch (Exception ex) {
            }
        if (stmt2 != null)
            try {
                stmt2.close();
            }
            catch (Exception ex) {
            }
        if (newStmt != null)
            try {
                newStmt.close();
            }
            catch (Exception ex) {
            }
    }
    if (changed != null) {
        return changed;
    }
    return FormatChange.nothing;
}


    public long getEarliestPollDate(Connection conn) throws ServerException {
        try {
            return getLongValue(conn, "SELECT lastPollDate "
                                    + "FROM rcFormat "
                                    + "ORDER BY lastPollDate ASC");
        } catch (SQLException e) {
            throw new ServerException("Error getting earliest poll date", e);
        }
    }

    public long getLastPollDate(Connection conn, 
                                String mdPrefix) throws ServerException {
        try {
            return getLongValue(conn, "SELECT lastPollDate "
                                    + "FROM rcFormat "
                                    + "WHERE mdPrefix = " + qs(mdPrefix));
        } catch (SQLException e) {
            throw new ServerException("Error getting last poll date", e);
        }
    }

    public void setLastPollDate(Connection conn, 
                                String mdPrefix,
                                long lastPollDate) throws ServerException {

        Statement stmt = null;
        ResultSet rs = null;
        try {
            stmt = getStatement(conn, false);
            executeUpdate(stmt, "UPDATE rcFormat "
                              + "SET lastPollDate = " + lastPollDate + " "
                              + "WHERE mdPrefix = " + qs(mdPrefix));
        } catch (SQLException e) {
            throw new ServerException("Error setting last poll date", e);
        } finally {
            if (rs != null) try { rs.close(); } catch (Exception e) { }
            if (stmt != null) try { stmt.close(); } catch (Exception e) { }
        }
    }

    /**
     * Execute the given query and return the <code>long</code> value in the 
     * first column of the first row of the <code>ResultSet</code>, 
     * or zero if there are no results.
     */
    public long getLongValue(Connection conn, String query) throws SQLException {
        Statement stmt = null;
        ResultSet rs = null;
        try {
            stmt = getStatement(conn, true);
            rs = executeQuery(stmt, query);
            if (rs.next()) {
                Long d = rs.getLong(1);
                return d;
            } else {
                return 0;
            }
        } finally {
            if (rs != null) try { rs.close(); } catch (Exception e) { }
            if (stmt != null) try { stmt.close(); } catch (Exception e) { }
        }
    }

    public List<CachedMetadataFormat>  getFormats(Connection conn) throws ServerException {
        return getFormats(conn, null);
    }

    public List<CachedMetadataFormat>  getFormats(Connection conn, String identifier) throws ServerException {
        List<CachedMetadataFormat> list = new ArrayList<CachedMetadataFormat>();
        Statement stmt = null;
        ResultSet rs = null;
        try {
            stmt = getStatement(conn, false);
            String query;
            if (identifier == null) {
                query = "SELECT formatKey, mdPrefix, namespaceURI, schemaLocation, dissemination "
                        + "FROM rcFormat";
            } else {
                query = "SELECT rcFormat.formatKey, rcFormat.mdPrefix, rcFormat.namespaceURI, rcFormat.schemaLocation, rcFormat.dissemination "
                        + "FROM rcFormat, rcItem, rcRecord "
                       + "WHERE rcItem.identifier = " + qss(identifier)
                         + "AND rcRecord.itemKey = rcItem.itemKey "
                         + "AND rcRecord.formatKey = rcFormat.formatKey";
            }
            rs = executeQuery(stmt, query);
            while (rs.next()) {
                list.add(new CachedMetadataFormat(rs.getInt(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5)));
            }
            return list;
        } catch (SQLException e) {
            throw new ServerException("Error reading rcFormat", e);
        } finally {
            if (rs != null) try { rs.close(); } catch (Exception e) { }
            if (stmt != null) try { stmt.close(); } catch (Exception e) { }
        }
    }

    /**
     * Get a map of prefix (String) to formatKey (Integer) for all formats 
     * in the database.
     */
    public Map<String, Integer> getFormatKeyMap(Connection conn) throws ServerException {
        Map<String, Integer> map = new HashMap<String, Integer>();
        Iterator<CachedMetadataFormat> iter = getFormats(conn).iterator();
        while (iter.hasNext()) {
            CachedMetadataFormat format = (CachedMetadataFormat) iter.next();
            map.put(format.getPrefix(), new Integer(format.getKey()));
        }
        return map;
    }

    public void deleteFormat(Connection conn, String prefix)
    throws ServerException {
    Statement stmt = null;
    Statement stmt1 = null;
    ResultSet rs = null;
    ResultSet rs1 = null;
    try {
        logger.info("Deleting format: " + prefix);
        stmt = getStatement(conn, false);
        rs =
            executeQuery(stmt,
                "SELECT formatKey FROM rcFormat WHERE mdPrefix = "
                    + qs(prefix));
        if (rs.next()) {
            int formatKey = rs.getInt(1);
            executeUpdate(stmt, "DELETE FROM rcFormat WHERE formatKey = "
                + formatKey);
            rs.close();
            // first mark xmlPaths of records in this format as prunable
            // and delete set membership for relevant records
            stmt1 = getStatement(conn, false);
            String selectRecordKey =
                "SELECT recordKey, xmlPath FROM rcRecord WHERE formatKey = "
                    + formatKey;
            rs1 = executeQuery(stmt1, selectRecordKey);
            while (rs1.next()) {
                int recordKey = rs1.getInt(1);
                String xmlPathToPrune = rs1.getString(2);
                executeUpdate(stmt,
                    "DELETE from rcMembership WHERE recordKey = "
                        + recordKey);
                addPrunable(stmt, xmlPathToPrune);
            }
            // then delete the actual records
            executeUpdate(stmt, "DELETE FROM rcRecord WHERE formatKey = "
                + formatKey);
        }
        else {
            throw new ServerException(
                "Format does not exist in rcFormat table: " + prefix);
        }

        // remove entries referencing this format from the tables rcqueue
        // and rcfailure.

        String deleteFromQueue =
            "DELETE from rcQueue WHERE mdPrefix = '" + prefix + "'";
        executeUpdate(stmt, deleteFromQueue);

        String deleteFromFailure =
            "DELETE from rcFailure WHERE mdPrefix = '" + prefix + "'";
        executeUpdate(stmt, deleteFromFailure);
    }
    catch (SQLException e) {
        throw new ServerException("Error deleting format: " + prefix, e);
    }
    finally {
        if (rs != null)
            try {
                rs.close();
            }
            catch (Exception e) {
            }
            if (rs1 != null)
                try {
                    rs1.close();
                }
                catch (Exception e) {
                }
        if (stmt != null)
            try {
                stmt.close();
            }
            catch (Exception e) {
            }
            if (stmt1 != null)
                try {
                    stmt1.close();
                }
                catch (Exception e) {
                }
    }
}
    public void deleteRecordIfExist(
        Connection conn, String itemId, String mdPrefix) {
        Statement stmt = null;
        ResultSet rs = null;
        ResultSet rs1 = null;
        ResultSet rs2 = null;
        try {
            stmt = getStatement(conn, false);
            rs2 =
                executeQuery(stmt,
                    "SELECT formatKey FROM rcFormat WHERE mdPrefix = '"
                        + mdPrefix + "'");
            int formatKey = 0;
            if (rs2.next()) {
                formatKey = rs2.getInt(1);
            }
            else {
                String message =
                    "Format for the prefix " + mdPrefix + " is mising.";
                logger.error(message);
                throw new ServerException(message);
            }

            rs =
                executeQuery(stmt,
                    "SELECT itemKey FROM rcItem WHERE identifier = '" + itemId
                        + "'");
            int itemKey = 0;
            if (rs.next()) {
                itemKey = rs.getInt(1);
                rs1 =
                    executeQuery(stmt,
                        "SELECT recordKey, xmlPath FROM rcRecord WHERE itemKey = "
                            + itemKey + " AND formatKey = " + formatKey);
                if (rs1.next()) {
                    int recordKey = rs1.getInt(1);
                    String xmlPath = rs1.getString(2);
                    deleteRecord(conn, recordKey, xmlPath);
                }
            }

        }
        catch (SQLException e) {
            throw new ServerException("Error deleting record", e);
        }
        finally {
            if (rs != null)
                try {
                    rs.close();
                }
                catch (Exception e) {
                }
                if (rs1 != null)
                    try {
                        rs1.close();
                    }
                    catch (Exception e) {
                    }
                    if (rs2 != null)
                        try {
                            rs2.close();
                        }
                        catch (Exception e) {
                        }
            if (stmt != null)
                try {
                    stmt.close();
                }
                catch (Exception e) {
                }
        }
    }
    
    public void deleteRecord(Connection conn, int recordKey, String xmlPath)
    throws ServerException {
    Statement stmt = null;
    ResultSet rs = null;
    try {
        logger.info("Deleting record: " + recordKey);
        stmt = getStatement(conn, false);
        executeUpdate(stmt, "DELETE from rcMembership WHERE recordKey = "
            + recordKey);
        addPrunable(stmt, xmlPath);
        // then delete the actual record
        executeUpdate(stmt, "DELETE FROM rcRecord WHERE recordKey = "
            + recordKey);
    }
    catch (SQLException e) {
        throw new ServerException("Error deleting record with key: "
            + recordKey, e);
    }
    finally {
        if (rs != null)
            try {
                rs.close();
            }
            catch (Exception e) {
            }
        if (stmt != null)
            try {
                stmt.close();
            }
            catch (Exception e) {
            }
    }
}
    public void putSetInfo(Connection conn, String setSpec, String xmlPath) throws ServerException {

        Statement stmt = null;
        ResultSet rs = null;

        try {
            stmt = getStatement(conn, false);
            rs = executeQuery(stmt, "SELECT setKey, xmlPath FROM rcSet WHERE setSpec = " + qs(setSpec));
            if (rs.next()) {
                // we're doing an update
                int setKey = rs.getInt(1);
                String xmlPathToPrune = rs.getString(2);
                logger.info("Set " + setSpec + " exists. Updating in db.");
                rs.close();

                // update the set table and mark the old xmlPath for pruning
                executeUpdate(stmt, "UPDATE rcSet SET xmlPath = " + qss(xmlPath) 
                                  + "WHERE setKey = " + setKey);
                addPrunable(stmt, xmlPathToPrune);
            } else {
                logger.info("Set " + setSpec + " is new. Adding to db.");
                executeUpdate(stmt, "INSERT INTO rcSet (setSpec, xmlPath) "
                                  + "VALUES (" + qsc(setSpec) + qs(xmlPath) + ")");
            }
        } catch (SQLException e) {
            throw new ServerException("Error reading rcSet", e);
        } finally {
            if (rs != null) try { rs.close(); } catch (Exception e) { }
            if (stmt != null) try { stmt.close(); } catch (Exception e) { }
        }
    }

    public List<SetInfo> getSetInfo(Connection conn) throws ServerException {
        List<SetInfo> list = new ArrayList<SetInfo>();
        Statement stmt = null;
        ResultSet rs = null;
        try {
            stmt = getStatement(conn, false);
            rs = executeQuery(stmt, "SELECT setSpec, xmlPath FROM rcSet");
            while (rs.next()) {
                list.add(new SetInfoImpl(rs.getString(1), m_rcDisk.getFile(rs.getString(2))));
            }
            return list;
        } catch (SQLException e) {
            throw new ServerException("Error reading rcSet", e);
        } finally {
            if (rs != null) try { rs.close(); } catch (Exception e) { }
            if (stmt != null) try { stmt.close(); } catch (Exception e) { }
        }
    }

    // return a closeableiterator of string[] (path)
    public List<Vector<String>>  getSetInfoPaths(Connection conn) throws ServerException {
        List<Vector<String>> list = new ArrayList<Vector<String>>();
        Statement stmt = null;
        ResultSet rs = null;
        try {
            stmt = getStatement(conn, false);
            rs = executeQuery(stmt, "SELECT xmlPath FROM rcSet");
            while (rs.next()) {
                Vector<String> a = new Vector<String> ();
             //   String[] a = new String[1];
              //  a[0] = rs.getString(1);
                a.add(rs.getString(1));
                list.add(a);
            }
            return list;
        } catch (SQLException e) {
            throw new ServerException("Error reading rcSet", e);
        } finally {
            if (rs != null) try { rs.close(); } catch (Exception e) { }
            if (stmt != null) try { stmt.close(); } catch (Exception e) { }
        }
    }
    
    
    public void addNewUserDefinedSet(
        Connection conn, String curentSpec, String xmlPath) {

        logger.info("Add a new user defined set  " + curentSpec);
        Statement stmt = null;
        try {
            stmt = getStatement(conn, false);
            executeUpdate(stmt, "INSERT INTO rcSet (setSpec, xmlPath) "
                + "VALUES (" + qsc(curentSpec) + qs(xmlPath) + ")");

        }
        catch (SQLException e) {
            throw new ServerException("Error adding a new set", e);
        }
        finally {

            if (stmt != null)
                try {
                    stmt.close();
                }
                catch (Exception e) {
                }
        }
    }
    
    
    public void putSetMembership(
        Connection conn, String curentSpec, Vector<String> resourceIds) {
        Statement stmt = null;
        ResultSet rs = null;
        try {
           
            logger.info("Updating user defined set membership: " + curentSpec);
            stmt = getStatement(conn, true);
            StringBuffer itemIdPartBuffer = new StringBuffer();
            Iterator<String> resourceIdsIterator = resourceIds.iterator();
            int i = 0;
            while (resourceIdsIterator.hasNext()) {
                String resourceId = resourceIdsIterator.next();
                if (i == 0) {
                    itemIdPartBuffer.append(" rcitem.identifier = "
                        + qs(resourceId));
                    i++;
                }
                else {
                    itemIdPartBuffer.append(" OR rcitem.identifier = "
                        + qs(resourceId));
                }
            }
            String itemIdPart = itemIdPartBuffer.toString();
            rs =
                executeQuery(stmt, "SELECT rcrecord.recordkey "
                    + "FROM rcitem, rcrecord "
                    + "WHERE (rcitem.itemkey=rcrecord.itemkey) AND ("
                    + itemIdPart + " )");
            Vector<Integer> currentRecordKeys = new Vector<Integer>();
            while (rs.next()) {
                currentRecordKeys.add(new Integer(rs.getInt(1)));
            }

            rs.close();
            rs =
                executeQuery(stmt, "SELECT setkey FROM rcset WHERE setspec = "
                    + qs(curentSpec));
            int currentSetKey = 0;
            if (rs.next()) {
                currentSetKey = rs.getInt(1);
            }
            else {
                throw new ServerException("Set is not listed in the set table : "
                    + curentSpec);
            }
            rs.close();

            rs =
                executeQuery(stmt,
                    "SELECT rcmembership.recordkey FROM rcmembership "
                        + " where rcmembership.setKey = " + currentSetKey);
            Vector<Integer> existRecordKeys = new Vector<Integer>();

            while (rs.next()) {
                existRecordKeys.add(new Integer(rs.getInt(1)));
            }
            Iterator<Integer> existRecordKeysIterator =
                    existRecordKeys.iterator();
            rs.close();

            Iterator<Integer> currentRecordKeysIterator =
                currentRecordKeys.iterator();
            while (currentRecordKeysIterator.hasNext()) {
                Integer recordKey = currentRecordKeysIterator.next();
                if (!existRecordKeys.contains(recordKey)) {
                    logger.info("Set membership is new. Adding to db.");
                    int recordKeyInt = recordKey.intValue();
                    executeUpdate(stmt,
                        "INSERT INTO rcMembership (setKey, recordKey) "
                            + "VALUES (" + currentSetKey + ", " + recordKeyInt
                            + ")");
                }
            }

            while (existRecordKeysIterator.hasNext()) {
                Integer existKey = existRecordKeysIterator.next();
                logger.info("Set membership is altered. Deleting from db.");
                int recordKeyInt = existKey.intValue();
                executeUpdate(stmt, "DELETE from rcMembership WHERE recordKey = " + recordKeyInt);
            }
        }
        catch (SQLException e) {
            throw new ServerException(
                "Error while adapting set membership of set : " + curentSpec, e);
        }
        finally {
            if (rs != null)
                try {
                    rs.close();
                }
                catch (Exception e) {
                }
            if (stmt != null)
                try {
                    stmt.close();
                }
                catch (Exception e) {
                }
        }

    }
    public void deleteSet(Connection conn, 
                          String setSpec) throws ServerException {

        Statement stmt = null;
        ResultSet rs = null;

        try {
            logger.info("Deleting set: " + setSpec);
            stmt = getStatement(conn, false);
            rs = executeQuery(stmt, "SELECT setKey, xmlPath "
                                  + "FROM rcSet "
                                  + "WHERE setSpec = " + qs(setSpec));
            if (rs.next()) {
                // the set exists, so it will be deleted
                int setKey = rs.getInt(1);
                String xmlPathToPrune = rs.getString(2);
                rs.close();

                // delete all refs to the set and mark the xmlPath as prunable
                executeUpdate(stmt, "DELETE from rcSet WHERE setKey = " + setKey);
                executeUpdate(stmt, "DELETE from rcMembership WHERE setKey = " + setKey);
                addPrunable(stmt, xmlPathToPrune);
            } else {
                throw new ServerException("Set does not exist in rcSet table: " + setSpec);
            }
        } catch (SQLException e) {
            throw new ServerException("Error deleting set: " + setSpec, e);
        } finally {
            if (rs != null) try { rs.close(); } catch (Exception e) { }
            if (stmt != null) try { stmt.close(); } catch (Exception e) { }
        }
    }

    public void setUncommittedRecordDates(Connection conn,
                                          Date newDate) throws ServerException {
        Statement stmt = null;
        try {
            stmt = getStatement(conn, false);
            executeUpdate(stmt, "UPDATE rcRecord SET modDate = " + newDate.getTime() + " WHERE modDate IS NULL");
        } catch (SQLException e) {
            throw new ServerException("Error setting uncommitted record dates", e);
        } finally {
            if (stmt != null) try { stmt.close(); } catch (Exception e) { }
        }
    }

    /**
     * Add or update a record.
     *
     * This will create an rcItem for it if it doesn't exist.
     *
     * NOTE: Records will initially be given a NULL date.  After a group of
     *       records are updated, the date is set together with
     *       setUncommittedRecordDates(..)
     */
    public void putRecord(Connection conn, 
                          ParsedRecord rec,
                          Map<String, Integer> formatKeyMap,
                          String state) throws ServerException {
        String xmlPath = rec.getSourceInfo();
        Statement stmt = null;
        ResultSet rs = null;
        Date lmd = rec.getDate();
        try {
            logger.info("Putting record: " + rec.getItemID() + " (" + rec.getPrefix() + ")");
            //
            // To update a record:
            //    Make sure there's an rcItem for it.
            //    If it's already in rcRecord:
            //       Update its row with new values
            //       If it's already a member of any sets:
            //          keep the ones it's still in, if any
            //          add the ones it's not in (throw error if it's in a set not in rcSet)
            //          delete the ones it's no longer in
            //       If it's not:
            //          add the ones it's not in (throw error if it's in a set not in rcSet)
            //    If it's not:
            //       For each set its a member of:
            //           add it to rcMembership
            //       
            int itemKey = getItemKey(conn, rec.getItemID());
            Integer fKey = (Integer) formatKeyMap.get(rec.getPrefix());
            if (fKey == null) {
                throw new ServerException("Error in parsed record; no such format in cache: " + rec.getPrefix());
            }
            int formatKey = fKey.intValue();
            stmt = getStatement(conn, false);
            int[] setKeys = getSetKeys(stmt, rec.getSetSpecs());
            rs = executeQuery(stmt, "SELECT recordKey, xmlPath "
                                 + "FROM rcRecord "
                                 + "WHERE itemKey = " + itemKey + " "
                                 + "AND formatKey = " + formatKey);      
            if (rs.next()) {

                // we're updating it
                int recordKey = rs.getInt(1);
                String xmlPathToPrune = rs.getString(2);
                rs.close();

                // update the record 
                // and mark the old xmlPath path as prunable
                
                
              //workaround: modDate is set to NULL, and will be set to the commit time stamp shortly before the commit
                //in order to deliver records without a time lag caused by caching
                //when OAI-PMH protocol will be extended to allow inform a harvester about a real request
                //time range, modDate should be set to lmd.getTime()
                
                executeUpdate(stmt, "UPDATE rcRecord SET modDate = NULL, "
                                               + "xmlPath = " + qsc(xmlPath)
                                               + "state =" + qss(state)
                                               + "WHERE recordKey = " + recordKey);
                
                addPrunable(stmt, xmlPathToPrune);

                // Modified rcRecord. Now list the ids of the sets it WAS in,
                // and rectify that with the ones it's NOW in
                List<Integer> priorSetKeys = new ArrayList<Integer>();
                rs = executeQuery(stmt, "SELECT setKey from rcMembership WHERE recordKey = " + recordKey);
                while (rs.next()) {
                    priorSetKeys.add(new Integer(rs.getInt(1)));
                }
                rs.close();
                rs = null;

                // which sets is the record a new member of?
                for (int i = 0; i < setKeys.length; i++) {
                    Integer newSetKey = new Integer(setKeys[i]);
                    if (!priorSetKeys.contains(newSetKey)) {
                        int nsk = newSetKey.intValue();
                        executeUpdate(stmt, "INSERT INTO rcMembership (setKey, recordKey) "
                                         + "VALUES (" + nsk + ", " + recordKey + ")");
                    }
                }

                // which sets is the record no longer a member of?
                Iterator<Integer> liter = priorSetKeys.iterator();
                while (liter.hasNext()) {
                    Integer priorSetKey = (Integer) liter.next();
                    int psk = priorSetKey.intValue();
                    boolean noLongerInSet = true;
                    for (int i = 0; i < setKeys.length; i++) {
                        if (setKeys[i] == psk) noLongerInSet = false;
                    }
                    if (noLongerInSet) {
                        // FIXME: Could make this more efficient with 
                        //        "AND ( setKey = x  OR setKey = y [...] )"
                        executeUpdate(stmt, "DELETE FROM rcMembership "
                                         + "WHERE recordKey = " + recordKey + " "
                                         + "AND setKey = " + psk);
                    }
                }

            } else {
                // we're creating it
                rs.close();
                //workaround: modDate is set to NULL, and will be set to the commit time stamp shortly before the commit
                //in order to deliver records without a time lag caused by caching
                //when OAI-PMH protocol will be extended to allow inform a harvester about a real request
                //time range, modDate should be set to lmd.getTime()
                executeUpdate(stmt, "INSERT INTO rcRecord (itemKey, formatKey, modDate, xmlPath, state) "
                        + "VALUES (" + itemKey + ", " + formatKey + ", NULL, " + qs(xmlPath) 
                        +  ", " +  qs(state) + ")");
                
                rs = executeQuery(stmt, "SELECT recordKey from rcRecord "
                                     + "WHERE itemKey = " + itemKey + " "
                                     + "AND formatKey = " + formatKey);
                if (rs.next()) {
                    int recordKey = rs.getInt(1);
                    rs.close();
                    // Added to rcRecord, now all we have to do is add it to
                    // the appropriate sets
                    for (int i = 0; i < setKeys.length; i++) {
                        executeUpdate(stmt, "INSERT INTO rcMembership (setKey, recordKey) "
                                + "VALUES (" + setKeys[i] + ", " + recordKey + ")");
                    }
                } else {
                    throw new ServerException("Insert into rcRecord didn't work "
                            + "(itemkey, formatkey = " + itemKey + ", " + formatKey + ")");
                }
            }
        } catch (SQLException e) {
            throw new ServerException("Error putting record", e);
        } finally {
            if (rs != null) try { rs.close(); } catch (Exception e) { }
            if (stmt != null) try { stmt.close(); } catch (Exception e) { }
        }
    }
    
    public void changeRecordState(
        int recordKey, String state, Connection conn) {
        Statement stmt = null;
        try {
            stmt = getStatement(conn, false);
            executeUpdate(stmt, "UPDATE rcRecord SET state = "
                + qs(state) + "WHERE recordKey = " + recordKey);

        }
        catch (SQLException e) {
            logger.error("Error updating record state");
            throw new ServerException("Error updating record state", e);
        }
        finally {
            if (stmt != null)
                try {
                    stmt.close();
                }
                catch (Exception e) {
                }
        }
    }
    
    public int[] getSetKeys(Statement stmt, List<String> specs) throws ServerException {
        ResultSet rs = null;
        try {
            Vector<Integer> keysVector = new Vector<Integer>();
            
            for (int i = 0; i < specs.size(); i++) {
                rs = executeQuery(stmt, "SELECT setKey from rcSet WHERE setSpec = " + qs(specs.get(i)));
                if (rs.next()) {
                    keysVector.add(Integer.valueOf(rs.getInt(1)));
                } 
//               else {
//                    throw new ServerException("Record contains setSpec not listed sets: " + specs.get(i));
//                }
            }
            int[] keys = new int[keysVector.size()];
            for (int i = 0; i < keysVector.size(); i++) {
                keys[i] = keysVector.get(i).intValue();
            }
            return keys;
        } catch (SQLException e) {
            throw new ServerException("Unable to get setKey(s) for record's setSpec(s)", e);
        } finally {
            if (rs != null) try { rs.close(); } catch (Exception e) { }
        }
    }

    // get or create an item key
    private int getItemKey(Connection conn, String itemID) throws ServerException {
        Statement stmt = null;
        ResultSet rs = null;
        try {
            stmt = getStatement(conn, false);
            rs = executeQuery(stmt, "SELECT itemKey from rcItem where identifier = " + qs(itemID));
            if (rs.next()) {
                return rs.getInt(1);
            } else {
                rs.close();
                executeUpdate(stmt, "INSERT INTO rcItem (identifier) VALUES (" + qs(itemID) + ")");
                rs = executeQuery(stmt, "SELECT itemKey from rcItem where identifier = " + qs(itemID));
                if (rs.next()) {
                    return rs.getInt(1);
                } else {
                    throw new ServerException("Insert into rcItem didn't work (identifier = " + itemID + ")");
                }
            }
        } catch (SQLException e) {
            throw new ServerException("Error getting key for itemID " + itemID, e);
        } finally {
            if (rs != null) try { rs.close(); } catch (Exception e) { }
            if (stmt != null) try { stmt.close(); } catch (Exception e) { }
        }
    }

    public boolean itemExists(Connection conn, String itemID) throws ServerException {
        Statement stmt = null;
        ResultSet rs = null;
        try {
            stmt = getStatement(conn, false);
            rs = executeQuery(stmt, "SELECT itemKey from rcItem where identifier = " + qs(itemID));
            return rs.next();
        } catch (SQLException e) {
            throw new ServerException("Error determining if item exists: " + itemID, e);
        } finally {
            if (rs != null) try { rs.close(); } catch (Exception e) { }
            if (stmt != null) try { stmt.close(); } catch (Exception e) { }
        }
    }

    // path, datestring
    public Vector<String> getRecordInfo(Connection conn,
                                  String itemID,
                                  String mdPrefix) throws ServerException {
        Statement stmt = null;
        ResultSet rs = null;
        Vector<String> result = new Vector<String>();
        try {
            stmt = getStatement(conn, false);
            String query = "SELECT rcRecord.recordkey, rcRecord.xmlPath, rcRecord.modDate from rcItem, rcRecord, rcFormat "
                + "WHERE rcItem.identifier = " + qss(itemID)
                + "AND rcItem.itemKey = rcRecord.itemKey "
                + "AND rcRecord.formatKey = rcFormat.formatKey "
                + "AND rcFormat.mdPrefix = " + qss(mdPrefix)
                + "AND state = 'valid'";
            
            rs = executeQuery(stmt, query);
            
            
            if (rs.next()) {
                String key = rs.getString(1);
                String path = rs.getString(2);
                result.add(path);
                Date d = new Date(rs.getLong(3));
                String dateString = null;
                try {
                    dateString = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'").format(d);
                result.add(dateString);
                String selectSetSpec =
                    "SELECT setSpec FROM rcSet, rcMembership WHERE rcmembership.recordKey =" + key
                    + " AND rcSet.setKey = rcMembership.setkey";         
                Statement newStmt = getStatement(conn, false);
                ResultSet rs1 = executeQuery(newStmt, selectSetSpec);
                
                while (rs1.next()) {
                    String setSpec = rs1.getString(1);
                    result.add(setSpec);
                }
                } catch (Exception e) {
                    // won't happen
                }
                return result;
            }
            else {
                return null;
            }
        }
        catch (SQLException e) {
            throw new ServerException("Error determining if item exists: "
                + itemID, e);
        }
        finally {
            if (rs != null)
                try {
                    rs.close();
                }
                catch (Exception e) {
                }
            if (stmt != null)
                try {
                    stmt.close();
                }
                catch (Exception e) {
                }
        }
    }

    // return a closeableiterator of string[] (path, dateString)
    // NOTE: Unlike other methods of this class, this method has
    // the responsibility of releasing the connection in some cases.
    // In particular, if this method does NOT return an iterator
    // that is attached to a ResultSet, it must release the connection.
    public CloseableIterator<Vector<String>> findRecordInfo(
        Connection conn, Date from, Date until, String prefix, String set)
        throws ServerException {
        // since the database is in milliseconds, but the given date is
        // in seconds, we need to check for the case where from == until, and
        // shift until by 999 before doing the query
        if (from != null && until != null && from.getTime() == until.getTime()) {
            until.setTime(until.getTime() + 999);
        }

        Statement stmt = null;
        Statement stmtNew = null;
        ResultSet rs = null;
        boolean releaseConnectionBeforeReturning = true;
        try {
            stmt = getStatement(conn, true);
            stmtNew = getStatement(conn, false);
            // DETERMINE THE FORMAT KEY
            rs =
                executeQuery(stmt,
                    "SELECT formatKey FROM rcFormat WHERE mdPrefix = "
                        + qs(prefix));
            if (!rs.next()) {
                // no such format -- return an empty iterator
                try {
                    rs.close();
                }
                catch (Exception e) {
                }
                try {
                    stmt.close();
                }
                catch (Exception e) {
                }
                try {
                    stmtNew.close();
                }
                catch (Exception e) {
                }
                // return new RemoteIteratorImpl<String[]>(new
                // ArrayList<String[]>().iterator());
                return new RemoteIteratorImpl<Vector<String>>(
                    new ArrayList<Vector<String>>().iterator());
            }
            int formatKey = rs.getInt(1);
            rs.close();

            // DETERMINE THE SET KEY, IF SPECIFIED
            int setKey = -1;
            if (set != null) {
                rs =
                    executeQuery(stmt,
                        "SELECT setKey FROM rcSet WHERE setSpec = " + qs(set));
                if (!rs.next()) {
                    // no such set -- return an empty iterator
                    try {
                        rs.close();
                    }
                    catch (Exception e) {
                    }
                    try {
                        stmt.close();
                    }
                    catch (Exception e) {
                    }
                    try {
                        stmtNew.close();
                    }
                    catch (Exception e) {
                    }
                    return new RemoteIteratorImpl<Vector<String>>(
                        new ArrayList<Vector<String>>().iterator());
                    // return new RemoteIteratorImpl<String[]>(new
                    // ArrayList<String[]>().iterator());
                }
                setKey = rs.getInt(1);
                rs.close();
            }

            StringBuffer query = new StringBuffer();
            if (set == null) {
                query
                    .append("SELECT recordKey, xmlPath, modDate FROM rcRecord WHERE formatKey = "
                        + formatKey); 
            }
            else {    
                query
                    .append("SELECT rcRecord.recordKey, rcRecord.xmlPath, rcRecord.modDate FROM rcMembership, rcRecord "
                        + "WHERE rcMembership.setKey = "
                        + setKey
                        + " "
                        + "AND rcMembership.recordKey = rcRecord.recordKey "
                        + "AND rcRecord.formatKey = " + formatKey);
            }
            if (from == null) {
                if (until == null) {
                    // (no conditions to add to query)
                }
                else {
                    query.append(" AND rcRecord.modDate <= " + until.getTime());
                }
            }
            else if (until == null) {
                query.append(" AND rcRecord.modDate >= " + from.getTime());
            }
            else {
                query.append(" AND rcRecord.modDate >= " + from.getTime());
                query.append(" AND rcRecord.modDate <= " + until.getTime());
            }
            
            query.append(" AND state = 'valid'");
            
            rs = executeQuery(stmt, query.toString());
            releaseConnectionBeforeReturning = false;

            return new StringResultIterator(conn, stmt, rs, stmtNew);
        }
        catch (SQLException se) {
            if (rs != null)
                try {
                    rs.close();
                }
                catch (Exception e) {
                }
            if (stmt != null)
                try {
                    stmt.close();
                }
                catch (Exception e) {
                }
            try {
                stmtNew.close();
            }
            catch (Exception e) {
            }
            throw new ServerException("Error finding record paths", se);
        }
        finally {
            if (releaseConnectionBeforeReturning) {
                RecordCache.releaseConnection(conn);
            }
        }
    }

    // ////////////////////////////////////////////////////////////////////////

    private boolean tablesExist(Connection conn) throws ServerException {
        Statement stmt = null;
        ResultSet results = null;
        try {
            stmt = getStatement(conn, false);
            results = executeQuery(stmt, "SELECT * from rcAdmin");
            return true;
        }
        catch (SQLException e) {
            return false;
        }
        finally {
            if (results != null)
                try {
                    results.close();
                }
                catch (Exception ex) {
                }
            if (stmt != null)
                try {
                    stmt.close();
                }
                catch (Exception ex) {
                }
        }
    }

    private void createTables(Connection conn, DDLConverter ddlc)
        throws ServerException {
        logger.debug("Creating tables...");
        List<TableSpec> specs;
        try {
            InputStream in = this.getClass().getResourceAsStream("/dbspec.xml");
            specs = TableSpec.getTableSpecs(in);
        }
        catch (Exception e) {
            throw new ServerException("Unable to initialize tablespecs", e);
        }
        List<String> createdCommands = new ArrayList<String>();
        Iterator<TableSpec> iter = specs.iterator();
        Statement stmt = null;
        String tableName = null;
        String command = null;
        try {
            stmt = getStatement(conn, false);
            while (iter.hasNext()) {
                TableSpec spec = iter.next();
                tableName = spec.getName();
                logger.info("Creating " + tableName + " table");
                List<String> commands = ddlc.getDDL(spec);
                for (int i = 0; i < commands.size(); i++) {
                    command = (String) commands.get(i);
                    executeUpdate(stmt, command);
                    createdCommands.add(command);
                }
            }
        }
        catch (Exception e) {
            StringBuffer msg = new StringBuffer();
            msg.append("Error creating table: " + tableName
                + ".  The following command failed:\n" + command);
            String dropCmd = "(unknown)";
            if (createdCommands.size() > 0) {
                Statement dstmt = null;
                try {
                    dstmt = getStatement(conn, false);
                    for (int i = 0; i < createdCommands.size(); i++) {
                        String createdCommand = (String) createdCommands.get(i);
                        dropCmd = ddlc.getDropDDL(createdCommand);
                        executeUpdate(stmt, dropCmd);
                    }
                }
                catch (Exception ex) {
                    msg.append("\nWARNING: An additional error occurred while "
                        + "attempting to drop partially-created tables, "
                        + "while running the following command:\n");
                    msg.append(dropCmd);
                    String ae =
                        ex.getClass().getName() + ": " + ex.getMessage();
                    msg.append("\nThe additional error was: " + ae);
                    msg
                        .append("\nBefore trying again, you should manually "
                            + "drop any remaining tables or other database objects "
                            + "created by this process.");
                }
                finally {
                    if (dstmt != null)
                        try {
                            dstmt.close();
                        }
                        catch (Exception ex) {
                        }
                }
            }
            throw new ServerException(msg.toString(), e);
        }
        finally {
            if (stmt != null)
                try {
                    stmt.close();
                }
                catch (Exception ex) {
                }
        }
    }

    private void createAdminRowIfNeeded(Connection conn) throws ServerException {
        try {
            getIdentifyPath(conn);
        }
        catch (ServerException e) {
            if (e.getMessage().equals(RCADMIN_TABLE_IS_EMPTY)) {
                Statement stmt = null;
                try {
                 // set the estimated earliest date for all added/modified records
                 // in the cache data base.  
                    Date earlistDate = new Date(StreamUtil.nowUTC().getTime() + 5000);
                    stmt = getStatement(conn, false);
                    executeUpdate(stmt,
                        "INSERT INTO rcAdmin (pollingEnabled, earliestDatestamp) VALUES (1, "
                        + earlistDate.getTime() + ")");
                    
                    
                }
                catch (SQLException se) {
                    throw new ServerException(
                        "Error creating initial rcAdmin row", se);
                }
                finally {
                    if (stmt != null)
                        try {
                            stmt.close();
                        }
                        catch (Exception ex) {
                        }
                }
            }
            else {
                throw new ServerException(
                    "Error determining if rcAdmin table is empty", e);
            }
        }
    }

    /**
     * Copy all qualifying records from rcFailure to rcQueue.
     * 
     * To avoid unintentional duplicates in the queue, it's important that the
     * caller ensures the queue is processed beforehand.
     */
    public void queueFailedRecords(Connection conn, int maxFailedRetries)
        throws ServerException {

        if (maxFailedRetries > 0) {

            Statement stmt = null;
            ResultSet results = null;
            Connection queueConn = null;
            if (m_mySQLTrickling) {
                // use separate connection for update during select
                try {
                    queueConn = RecordCache.getConnection();
                }
                catch (SQLException se) {
                    throw new ServerException("Unable to get additional "
                        + "connection for queueing failed records", se);
                }
            }
            try {

                stmt = getStatement(conn, true);
                results =
                    executeQuery(stmt,
                        "SELECT identifier, mdPrefix, sourceInfo "
                            + "FROM rcFailure " + "WHERE failCount <= "
                            + maxFailedRetries);
                while (results.next()) {
                    if (queueConn != null) {
                        queueFailedRecord(queueConn, results.getString(1),
                            results.getString(2), DBUtil.getLongString(results,
                                3));
                    }
                    else {
                        queueFailedRecord(conn, results.getString(1), results
                            .getString(2), DBUtil.getLongString(results, 3));
                    }
                }
            }
            catch (SQLException e) {
                throw new ServerException("Failed while attempting to enqueue "
                    + "failed records", e);
            }
            finally {
                if (results != null)
                    try {
                        results.close();
                    }
                    catch (Exception ex) {
                    }
                if (stmt != null)
                    try {
                        stmt.close();
                    }
                    catch (Exception ex) {
                    }
                if (queueConn != null)
                    try {
                        queueConn.close();
                    }
                    catch (Exception ex) {
                    }
            }
        }
    }

    private void queueFailedRecord(
        Connection conn, String identifier, String mdPrefix, String sourceInfo)
        throws SQLException {
        Statement stmt = null;
        try {
            stmt = conn.createStatement();
            executeUpdate(stmt, getQueueInsertSQL(identifier, mdPrefix,
                sourceInfo, 'F'));
        }
        finally {
            if (stmt != null)
                try {
                    stmt.close();
                }
                catch (Exception ex) {
                }
        }
    }

    private String getQueueInsertSQL(
        String identifier, String mdPrefix, String sourceInfo, char queueSource) {
        if ((sourceInfo.indexOf("\n") != -1)
            || (sourceInfo.indexOf("\r") != -1)) {
            throw new ServerException("INSERT aborted: bad sourceInfo for "
                + identifier + "/" + mdPrefix + " (contains " + "newline(s))");
        }
        return "INSERT INTO rcQueue (identifier, " + "mdPrefix, "
            + "sourceInfo, " + "queueSource) " + "VALUES (" + qsc(identifier)
            + qsc(mdPrefix) + qsc(sourceInfo) + "'" + queueSource + "')";
    }

    public void queueRemoteRecord(
        Connection conn, String identifier, String mdPrefix, String sourceInfo)
        throws ServerException {
        Statement stmt = null;
        ResultSet results = null;
        try {

            stmt = conn.createStatement();
            executeUpdate(stmt, getQueueInsertSQL(identifier, mdPrefix,
                sourceInfo, 'R'));
        }
        catch (SQLException e) {
            throw new ServerException("Failed while attempting to enqueue "
                + "remote record", e);
        }
        finally {
            if (results != null)
                try {
                    results.close();
                }
                catch (Exception ex) {
                }
            if (stmt != null)
                try {
                    stmt.close();
                }
                catch (Exception ex) {
                }
        }
    }

    public int getQueueSize(Connection conn) throws ServerException {

        Statement stmt = null;
        ResultSet results = null;
        try {

            stmt = conn.createStatement();
            results = executeQuery(stmt, "SELECT count(*) FROM rcQueue");

            results.next();
            return results.getInt(1);
        }
        catch (SQLException e) {
            throw new ServerException("Failed to determine queue size", e);
        }
        finally {
            if (results != null)
                try {
                    results.close();
                }
                catch (Exception ex) {
                }
            if (stmt != null)
                try {
                    stmt.close();
                }
                catch (Exception ex) {
                }
        }
    }

    public void dumpQueue(Connection conn, PrintWriter writer)
        throws ServerException {

        logger.info("Preparing queue for processing...");

        Statement stmt = null;
        ResultSet results = null;
        int resultCount = 0;

        try {

            stmt = conn.createStatement();
            results =
                executeQuery(stmt, "SELECT queueKey, identifier, "
                    + "mdPrefix, sourceInfo, queueSource " + "FROM rcQueue "
                    + "ORDER BY queueKey ASC");
            while (results.next()) {
                resultCount++;

                writer.print(results.getInt(1) + " ");

                String identifier = results.getString(2);
                writer.print(identifier + " ");

                String mdPrefix = results.getString(3);
                writer.print(mdPrefix + " ");

                writer.print(results.getString(5) + " ");

                String sourceInfo = DBUtil.getLongString(results, 4);
                if ((sourceInfo.indexOf("\n") != -1)
                    || (sourceInfo.indexOf("\r") != -1)) {
                    throw new ServerException(
                        "rcQueue contains bad sourceInfo for " + identifier
                            + "/" + mdPrefix + " (contains " + "newline(s)): '"
                            + sourceInfo + "'");
                }
                writer.println(sourceInfo);
            }
        }
        catch (SQLException e) {
            throw new ServerException("Failed to dump queue", e);
        }
        finally {
            if (results != null)
                try {
                    results.close();
                }
                catch (Exception ex) {
                }
            if (stmt != null)
                try {
                    stmt.close();
                }
                catch (Exception ex) {
                }

            if (resultCount > 0) {
                // JDBC driver may be using a lot of heap space at this point,
                // so try to convince the VM to clean up after it
                results = null;
                stmt = null;
                System.gc();
            }
        }
    }
    
    
    public HashMap<Integer, String> getFormatRecordsInConnectionFailureState(Connection conn, String formatPrefix) {
        HashMap<Integer, String> records = new HashMap<Integer, String>();
        Statement stmt = null;
        ResultSet rs = null;
        try {
            stmt = getStatement(conn, false);
            rs = executeQuery(stmt, "SELECT rcRecord.recordKey, rcRecord.xmlPath FROM rcRecord, rcFormat WHERE rcRecord.formatKey = rcFormat.formatKey" +
                    " AND rcFormat.mdPrefix=" + qs(formatPrefix) + " AND rcRecord.state='connectionFailure'");
            while (rs.next()) {
                int recordKey = rs.getInt(1);
                String xmlPath = rs.getString(2);
                records.put(recordKey, xmlPath);
            }
            return records;
        } catch (SQLException e) {
            throw new ServerException("Error reading rcSet", e);
        } finally {
            if (rs != null) try { rs.close(); } catch (Exception e) { }
            if (stmt != null) try { stmt.close(); } catch (Exception e) { }
        }
    }
    
    
    public HashMap<Integer, String[]> getFormatRecordsInNotValidState(Connection conn, String formatPrefix) {
        HashMap<Integer, String[]> records = new HashMap<Integer, String[]>();
        Statement stmt = null;
        ResultSet rs = null;
        try {
            stmt = getStatement(conn, false);
            rs = executeQuery(stmt, "SELECT rcRecord.recordKey, rcRecord.xmlPath, rcRecord.state FROM rcRecord, rcFormat WHERE rcRecord.formatKey = rcFormat.formatKey" +
                    " AND rcFormat.mdPrefix=" + qss(formatPrefix)
                  + "AND (rcRecord.state='connectionFailure' OR rcRecord.state='wrongSchemaLocation')");
            while (rs.next()) {
                String [] recordInfo = new String [2];
                int recordKey = rs.getInt(1);
                recordInfo[0] = rs.getString(2);
                recordInfo[1] = rs.getString(3);
                records.put(new Integer(recordKey), recordInfo);
            }
            return records;
        } catch (SQLException e) {
            throw new ServerException("Error reading rcSet", e);
        } finally {
            if (rs != null) try { rs.close(); } catch (Exception e) { }
            if (stmt != null) try { stmt.close(); } catch (Exception e) { }
        }
    }

    public void removeFromQueue(Connection conn, int queueKey)
        throws ServerException {
        Statement stmt = null;
        try {
            stmt = conn.createStatement();
            executeUpdate(stmt, "DELETE FROM rcQueue WHERE queueKey = "
                + queueKey);
        }
        catch (SQLException e) {
            throw new ServerException("Failed to remove record from queue", e);
        }
        finally {
            if (stmt != null)
                try {
                    stmt.close();
                }
                catch (Exception ex) {
                }
        }
    }

    public void removeFailure(
        Connection conn, String identifier, String mdPrefix)
        throws ServerException {

        Statement stmt = null;
        try {
            stmt = conn.createStatement();
            executeUpdate(stmt, "DELETE FROM rcFailure "
                + "WHERE identifier = " + qss(identifier) + "AND mdPrefix = "
                + qs(mdPrefix));
        }
        catch (SQLException e) {
            throw new ServerException("Failed to remove record from rcFailure",
                e);
        }
        finally {
            if (stmt != null)
                try {
                    stmt.close();
                }
                catch (Exception ex) {
                }
        }
    }

    // if in rcFailure, get failCount, otherwise return -1
    public int getFailCount(Connection conn, String identifier, String mdPrefix) {

        Statement stmt = null;
        ResultSet results = null;
        try {

            stmt = conn.createStatement();
            results =
                executeQuery(stmt, "SELECT failCount FROM rcFailure "
                    + "WHERE identifier = " + qss(identifier)
                    + "AND mdPrefix = " + qs(mdPrefix));

            if (results.next()) {
                return results.getInt(1);
            }
            else {
                return -1;
            }
        }
        catch (SQLException e) {
            throw new ServerException("Failed to determine queue size", e);
        }
        finally {
            if (results != null)
                try {
                    results.close();
                }
                catch (Exception ex) {
                }
            if (stmt != null)
                try {
                    stmt.close();
                }
                catch (Exception ex) {
                }
        }
    }

    public void addFailure(
        Connection conn, String identifier, String mdPrefix, String sourceInfo,
        String failDate, String failReason) throws ServerException {
        Statement stmt = null;
        try {
            stmt = conn.createStatement();
            executeUpdate(stmt, "INSERT INTO rcFailure " + "(identifier, "
                + "mdPrefix, " + "sourceInfo, " + "failCount, "
                + "firstFailDate, " + "lastFailDate, " + "lastFailReason) "
                + "VALUES (" + qsc(identifier) + qsc(mdPrefix)
                + qsc(sourceInfo) + "0, " + qsc(failDate) + qsc(failDate)
                + qs(failReason) + ")");
        }
        catch (SQLException e) {
            throw new ServerException("Failed to add row to rcFailure", e);
        }
        finally {
            if (stmt != null)
                try {
                    stmt.close();
                }
                catch (Exception ex) {
                }
        }
    }

    public void updateFailure(
        Connection conn, String identifier, String mdPrefix, String sourceInfo,
        int newFailCount, String failDate, String failReason)
        throws ServerException {
        Statement stmt = null;
        try {
            stmt = conn.createStatement();
            executeUpdate(stmt, "UPDATE rcFailure " + "SET sourceInfo = "
                + qs(sourceInfo) + ", " + "failCount = " + newFailCount + ", "
                + "lastFailDate = " + qsc(failDate) + "lastFailReason = "
                + qss(failReason) + "WHERE identifier = " + qss(identifier)
                + "AND mdPrefix = " + qs(mdPrefix));
        }
        catch (SQLException e) {
            throw new ServerException("Failed to update row in rcFailure", e);
        }
        finally {
            if (stmt != null)
                try {
                    stmt.close();
                }
                catch (Exception ex) {
                }
        }
    }

    private void addPrunable(Statement stmt, String xmlPathToPrune)
        throws SQLException {
        executeUpdate(stmt, "INSERT INTO rcPrunable (xmlPath) VALUES ("
            + qs(xmlPathToPrune) + ")");
    }

    public int getPrunableCount(Connection conn) throws ServerException {

        Statement stmt = null;
        ResultSet results = null;
        try {

            stmt = conn.createStatement();
            results = executeQuery(stmt, "SELECT count(*) FROM rcPrunable");

            results.next();
            return results.getInt(1);
        }
        catch (SQLException e) {
            throw new ServerException("Failed to determine prunable count", e);
        }
        finally {
            if (results != null)
                try {
                    results.close();
                }
                catch (Exception ex) {
                }
            if (stmt != null)
                try {
                    stmt.close();
                }
                catch (Exception ex) {
                }
        }
    }

    /**
     * Delete one or more items from the prunable list, by database key.
     */
    public void deletePrunables(Connection conn, int[] keys, int num)
        throws SQLException {

        StringBuffer sql = new StringBuffer();
        sql.append("DELETE FROM rcPrunable WHERE pruneKey IN (");
        for (int i = 0; i < num; i++) {
            if (i > 0)
                sql.append(", ");
            sql.append(keys[i]);
        }
        sql.append(")");

        Statement stmt = null;

        try {
            stmt = conn.createStatement();
            executeUpdate(stmt, sql.toString());
        }
        finally {
            if (stmt != null)
                try {
                    stmt.close();
                }
                catch (Exception e) {
                }
        }
    }

    public int dumpPrunables(Connection conn, PrintWriter writer)
        throws ServerException {

        logger.info("Preparing list of prunable files in cache");

        Statement stmt = null;
        ResultSet results = null;
        int resultCount = 0;

        try {
            stmt = conn.createStatement();
            results =
                executeQuery(stmt, "SELECT pruneKey, xmlPath "
                    + "FROM rcPrunable");
            while (results.next()) {
                resultCount++;
                writer.println(results.getInt(1) + " " + results.getString(2));
            }

            return resultCount;

        }
        catch (SQLException e) {
            throw new ServerException("Failed to dump prunables", e);
        }
        finally {
            if (results != null)
                try {
                    results.close();
                }
                catch (Exception ex) {
                }
            if (stmt != null)
                try {
                    stmt.close();
                }
                catch (Exception ex) {
                }

            if (resultCount > 0) {
                // JDBC driver may be using a lot of heap space at this point,
                // so try to convince the VM to clean up after it
                results = null;
                stmt = null;
                System.gc();
            }
        }
    }
}
