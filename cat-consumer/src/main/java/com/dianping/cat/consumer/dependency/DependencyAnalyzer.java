package com.dianping.cat.consumer.dependency;

import com.dianping.cat.CatConstants;
import com.dianping.cat.ServerConfigManager;
import com.dianping.cat.analysis.AbstractMessageAnalyzer;
import com.dianping.cat.consumer.dependency.model.entity.Dependency;
import com.dianping.cat.consumer.dependency.model.entity.DependencyReport;
import com.dianping.cat.consumer.dependency.model.entity.Index;
import com.dianping.cat.consumer.dependency.model.entity.Segment;
import com.dianping.cat.message.Event;
import com.dianping.cat.message.Message;
import com.dianping.cat.message.Transaction;
import com.dianping.cat.message.spi.MessageTree;
import com.dianping.cat.service.DefaultReportManager.StoragePolicy;
import com.dianping.cat.service.ReportManager;
import org.codehaus.plexus.logging.LogEnabled;
import org.codehaus.plexus.logging.Logger;
import org.unidal.lookup.annotation.Inject;

import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class DependencyAnalyzer extends AbstractMessageAnalyzer<DependencyReport> implements LogEnabled {
    public static final String ID = "dependency";

    @Inject(ID)
    private ReportManager<DependencyReport> m_reportManager;

    @Inject
    private DatabaseParser m_parser;

    @Inject
    private ServerConfigManager m_serverConfigManager;

    private Set<String> m_types = new HashSet<String>(Arrays.asList(CatConstants.TYPE_URL, CatConstants.TYPE_ESB_CALL, CatConstants.TYPE_SOA_CALL, CatConstants.TYPE_SOA_SERVICE,
            CatConstants.TYPE_ESB_SERVICE, CatConstants.TYPE_SQL));

    private Set<String> m_exceptions = new HashSet<String>(Arrays.asList("Exception", "RuntimeException", "Error"));

    @Override
    public void doCheckpoint(boolean atEnd) {
        if (atEnd && !isLocalMode()) {
            m_reportManager.storeHourlyReports(getStartTime(), StoragePolicy.FILE_AND_DB);
        } else {
            m_reportManager.storeHourlyReports(getStartTime(), StoragePolicy.FILE);
        }
    }

    @Override
    public void enableLogging(Logger logger) {
        m_logger = logger;
    }

    private DependencyReport findOrCreateReport(String domain) {
        return m_reportManager.getHourlyReport(getStartTime(), domain, true);
    }

    @Override
    public DependencyReport getReport(String domain) {
        DependencyReport report = m_reportManager.getHourlyReport(getStartTime(), domain, false);

        report.getDomainNames().addAll(m_reportManager.getDomains(getStartTime()));
        return report;
    }

    private boolean isCache(String type) {
        return type.startsWith("Cache.");
    }

    @Override
    protected void loadReports() {
        m_reportManager.loadHourlyReports(getStartTime(), StoragePolicy.FILE);
    }

    private String parseDatabase(Transaction t) {
        List<Message> messages = t.getChildren();

        for (Message message : messages) {
            if (message instanceof Event) {
                String type = message.getType();

                if (CatConstants.TYPE_SQL_DATABASE.equals(type)) {
                    return m_parser.parseDatabaseName(message.getName());
                }
            }
        }
        return null;
    }

    private String parseServerName(Transaction t) {
        List<Message> messages = t.getChildren();

        for (Message message : messages) {
            if (message instanceof Event) {
                if (CatConstants.TYPE_ESB_CALL_APP.equals(message.getType()) || CatConstants.TYPE_SOA_CALL_APP.equals(message.getType())) {
                    return message.getName();
                }
            }
        }
        return null;
    }

    @Override
    public void process(MessageTree tree) {
        String domain = tree.getDomain();
        DependencyReport report = findOrCreateReport(domain);
        Message message = tree.getMessage();

        if (message instanceof Transaction) {
            processTransaction(report, tree, (Transaction) message);
        } else if (message instanceof Event) {
            processEvent(report, tree, (Event) message);
        }
    }

    private void processEvent(DependencyReport report, MessageTree tree, Event event) {
        String type = event.getType();

        if (m_exceptions.contains(type)) {
            long current = event.getTimestamp() / 1000 / 60;
            int min = (int) (current % (60));
            Segment segment = report.findOrCreateSegment(min);
            Index index = segment.findOrCreateIndex("Exception");

            index.incTotalCount();
            index.incErrorCount();
        }
    }

    private void processPigeonTransaction(DependencyReport report, MessageTree tree, Transaction t) {
        String type = t.getType();

        if (CatConstants.TYPE_ESB_CALL.equals(type) || CatConstants.TYPE_SOA_CALL.equals(type)) {
            String target = parseServerName(t);
            String callType = "Call";

            if (target != null) {
                updateDependencyInfo(report, t, target, callType);
                DependencyReport serverReport = findOrCreateReport(target);

                updateDependencyInfo(serverReport, t, tree.getDomain(), "Service");
            } else {
                System.err.println(t);
            }
        }
    }

    private void processSqlTransaction(DependencyReport report, Transaction t) {
        String type = t.getType();

        if (CatConstants.TYPE_SQL.equals(type)) {
            String database = parseDatabase(t);

            if (database != null) {
                updateDependencyInfo(report, t, database, "Database");
            }
        }
    }

    private void processTransaction(DependencyReport report, MessageTree tree, Transaction t) {
        if (m_serverConfigManager.discardTransaction(t)) {
            return;
        } else {
            processTransactionType(report, t);
            processSqlTransaction(report, t);
            processPigeonTransaction(report, tree, t);

            List<Message> children = t.getChildren();

            for (Message child : children) {
                if (child instanceof Transaction) {
                    processTransaction(report, tree, (Transaction) child);
                } else if (child instanceof Event) {
                    processEvent(report, tree, (Event) child);
                }
            }
        }
    }

    private void processTransactionType(DependencyReport report, Transaction t) {
        String type = t.getType();

        if (m_types.contains(type) || isCache(type)) {
            long current = t.getTimestamp() / 1000 / 60;
            int min = (int) (current % (60));
            Segment segment = report.findOrCreateSegment(min);
            Index index = segment.findOrCreateIndex(type);

            if (!t.getStatus().equals(Transaction.SUCCESS)) {
                index.incErrorCount();
            }
            index.incTotalCount();
            index.setSum(index.getSum() + t.getDurationInMillis());
            index.setAvg(index.getSum() / index.getTotalCount());
        }

        if (isCache(type)) {
            updateDependencyInfo(report, t, type, "Cache");
        }
    }

    private void updateDependencyInfo(DependencyReport report, Transaction t, String target, String type) {
        long current = t.getTimestamp() / 1000 / 60;
        int min = (int) (current % (60));
        Segment segment = report.findOrCreateSegment(min);
        Dependency dependency = segment.findOrCreateDependency(type + ":" + target);

        dependency.setType(type);
        dependency.setTarget(target);

        if (!t.getStatus().equals(Transaction.SUCCESS)) {
            dependency.incErrorCount();
        }
        dependency.incTotalCount();
        dependency.setSum(dependency.getSum() + t.getDurationInMillis());
        dependency.setAvg(dependency.getSum() / dependency.getTotalCount());
    }
}
