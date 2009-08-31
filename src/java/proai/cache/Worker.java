package proai.cache;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.Iterator;
import java.util.List;

import org.apache.log4j.Logger;

import proai.MetadataFormat;
import proai.driver.EscidocAdaptedOAIDriver;
import proai.driver.RemoteIterator;
import proai.util.StreamUtil;

public class Worker extends Thread {

    private static Logger _LOG = Logger.getLogger(Worker.class.getName());

    private Updater _updater;
    private EscidocAdaptedOAIDriver _driver;
    private RCDisk _disk;
    private MetadataValidator _validator;
    

    private int _attemptedCount;
    private int _failedCount;
    private long _totalFetchTime;
    private long _totalValidationTime;


    public Worker(int num, 
        int of, 
        Updater updater, 
        EscidocAdaptedOAIDriver driver, 
        RCDisk disk,
        MetadataValidator validator) {
        super("Worker-" + num + "of" + of);
        _updater = updater;
        _driver = driver;
        _disk = disk;
        _validator = validator;
      
    }

    public void run() {

        _LOG.info("Worker started");

        List<QueueItem> queueItems = _updater.getNextBatch(null);
        while (queueItems != null && !_updater.processingShouldStop()) {
            Iterator<QueueItem> iter = queueItems.iterator();
            while (iter.hasNext() && !_updater.processingShouldStop()) {
                attempt(iter.next());
            }

            if (!_updater.processingShouldStop()) {
                queueItems = _updater.getNextBatch(queueItems);
            } else {
                _LOG.debug("About to finish prematurely because processing should stop");
            }
        }

        _LOG.info("Worker finished");
    }

    private void attempt(QueueItem qi) {

        RCDiskWriter diskWriter = null;
        long retrievalDelay = 0;
        long validationDelay = 0;
        ValidationInfo validationInfo = null;
        try {
                diskWriter = _disk.getNewWriter();

                long startFetchTime = System.currentTimeMillis();
                validationInfo =
                    _driver.writeRecordXML(qi.getIdentifier(),
                        qi.getMDPrefix(), qi.getSourceInfo(), diskWriter);
                if (validationInfo != null) {
                    if (validationInfo.getResult().equals(
                        ValidationResult.valid)) {
                        validationDelay = validationInfo.getValidationDelay();
                    }
                    qi.setState(validationInfo.getResult().toString());
                }
                else {
                    // Record is a 'deleted' record
                    qi.setState(ValidationResult.valid.toString());
                }
                diskWriter.flush();
                diskWriter.close();

                long endFetchTime = System.currentTimeMillis();

                retrievalDelay = endFetchTime - startFetchTime;

                if (!qi.getState().equals("invalid")) {
                    qi.setParsedRecord(new ParsedRecord(qi.getIdentifier(), qi
                        .getMDPrefix(), diskWriter.getPath(), diskWriter
                        .getFile(), qi.getSourceInfo()));
                    qi.setSucceeded(true);
                }
                else {
                    if (diskWriter != null) {
                        diskWriter.close();
                        diskWriter.getFile().delete();
                    }
                }
                if (qi.getState().equals("valid")) {
                    _LOG.info("Successfully processed record");
                }
        }
        catch (Throwable th) {

            _LOG.warn("Failed to process record", th);

            if (diskWriter != null) {
                diskWriter.close();
                diskWriter.getFile().delete();
            }

            StringWriter failReason = new StringWriter();
            th.printStackTrace(new PrintWriter(failReason, true));
            qi.setFailReason(failReason.toString());
            qi.setFailDate(StreamUtil.nowUTCString());
            _failedCount++;
        }
        finally {
            if (validationInfo != null) {
                if (validationInfo.getResult().equals(
                    ValidationResult.wrongSchemaLocation)
                    || validationInfo.getResult().equals(
                        ValidationResult.connectionFailure)
                    || validationInfo.getResult().equals(
                        ValidationResult.invalid)) {
                    _LOG.warn("Failed to validate record", validationInfo
                        .getFailReason());
                    _failedCount++;
                }
            }
            _attemptedCount++;
            _totalFetchTime += retrievalDelay;
            _totalValidationTime += validationDelay;
        }
    }

    public int getAttemptedCount() {
        return _attemptedCount;
    }

    public int getFailedCount() {
        return _failedCount;
    }

    public long getTotalFetchTime() {
        return _totalFetchTime;
    }

    public long getTotalValidationTime() {
        return _totalValidationTime;
    }
}
