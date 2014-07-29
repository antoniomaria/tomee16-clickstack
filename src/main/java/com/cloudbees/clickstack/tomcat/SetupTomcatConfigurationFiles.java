package com.cloudbees.clickstack.tomcat;

import com.cloudbees.clickstack.domain.metadata.*;
import com.cloudbees.clickstack.util.Strings2;
import com.cloudbees.clickstack.util.XmlUtils;
import com.google.common.base.Preconditions;
import com.google.common.base.Predicate;
import com.google.common.collect.Collections2;
import com.google.common.collect.Iterables;
import com.google.common.collect.Sets;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.w3c.dom.Document;
import org.w3c.dom.Element;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import javax.xml.parsers.ParserConfigurationException;
import java.io.FileOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.SimpleDateFormat;
import java.util.*;

public class SetupTomcatConfigurationFiles {

    private final Logger logger = LoggerFactory.getLogger(getClass());
    private Metadata metadata;
    private Set<String> databaseProperties = Sets.newHashSet("minIdle", "maxIdle", "maxActive", "maxWait",
            "initialSize",
            "validationQuery", "validationQueryTimeout", "testOnBorrow", "testOnReturn",
            "timeBetweenEvictionRunsMillis", "numTestsPerEvictionRun", "minEvictableIdleTimeMillis", "testWhileIdle",
            "removeAbandoned", "removeAbandonedTimeout", "logAbandoned", "defaultAutoCommit", "defaultReadOnly",
            "defaultTransactionIsolation", "poolPreparedStatements", "maxOpenPreparedStatements", "defaultCatalog",
            "connectionInitSqls", "connectionProperties", "accessToUnderlyingConnectionAllowed",
            /* Tomcat JDBC Enhanced Attributes */
            "factory", "type", "validatorClassName", "initSQL", "jdbcInterceptors", "validationInterval", "jmxEnabled",
            "fairQueue", "abandonWhenPercentageFull", "maxAge", "useEquals", "suspectTimeout", "rollbackOnReturn",
            "commitOnReturn", "alternateUsernameAllowed", "useDisposableConnectionFacade", "logValidationErrors",
            "propagateInterruptState");

    public SetupTomcatConfigurationFiles(Metadata metadata) {
        this.metadata = metadata;
    }

    protected SetupTomcatConfigurationFiles addDatabase(Database database, Document serverDocument, Document contextXmlDocument) {
        logger.info("Add DataSource name={}, url={}", database.getName(), database.getUrl());
        Element e = contextXmlDocument.createElement("Resource");
        e.setAttribute("name", "jdbc/" + database.getName());
        e.setAttribute("auth", "Container");
        e.setAttribute("type", "javax.sql.DataSource");
        e.setAttribute("url", "jdbc:" + database.getUrl());
        e.setAttribute("driverClassName", database.getJavaDriver());
        e.setAttribute("username", database.getUsername());
        e.setAttribute("password", database.getPassword());

        // by default, use use tomcat-jdbc-pool
        e.setAttribute("factory", "org.apache.tomcat.jdbc.pool.DataSourceFactory");

        int maxActive = database.getMaxConnections();
        int maxIdle = Math.max(maxActive / 2, 1);
        e.setAttribute("maxActive", String.valueOf(maxActive));
        e.setAttribute("maxIdle", String.valueOf(maxIdle));
        e.setAttribute("minIdle", "1");
        e.setAttribute("initialSize", "1");

        // test on borrow and while idle to release idle connections
        e.setAttribute("testOnBorrow", "true");
        e.setAttribute("testWhileIdle", "true");
        e.setAttribute("validationQuery", database.getValidationQuery());
        e.setAttribute("validationInterval", "5000"); // 5 secs

        // all the parameters can be overwritten
        for (Map.Entry<String, String> entry : database.getProperties().entrySet()) {
            if (databaseProperties.contains(entry.getKey())) {
                e.setAttribute(entry.getKey(), entry.getValue());
            } else {
                logger.debug("Ignore unknown datasource property '{}'", entry);
            }
        }

        contextXmlDocument.getDocumentElement().appendChild(e);
        return this;
    }

    protected SetupTomcatConfigurationFiles addSyslogAccessLogValve(Metadata metadata, Document serverDocument, Document contextXmlDocument) {
        // Syslog Access Log Valve
        if (!"true".equalsIgnoreCase(metadata.getRuntimeParameter("accessLog", "syslog", "false"))) {
            return this;
        }

        logger.info("Add Syslog Access Log Valve");

        Element e = serverDocument.createElement("Valve");
        e.setAttribute("className", "com.cloudbees.tomcat.valves.SyslogAccessLogValve");

        e.setAttribute("appName", metadata.getRuntimeParameter("accessLog", "syslog.appName", "access_log"));
        e.setAttribute("hostname", metadata.getRuntimeParameter("accessLog", "syslog.appHostname", "${SYSLOG_APP_HOSTNAME}"));
        e.setAttribute("syslogServerHostname", metadata.getRuntimeParameter("accessLog", "syslog.syslogServerHost", "${SYSLOG_HOST}"));
        e.setAttribute("syslogServerPort", metadata.getRuntimeParameter("accessLog", "syslog.syslogServerPort", "${SYSLOG_PORT}"));
        e.setAttribute("pattern", metadata.getRuntimeParameter("accessLog", "pattern", "combined"));
        e.setAttribute("requestAttributesEnabled", "true");

        Element remoteIpValve = XmlUtils.getUniqueElement(serverDocument, "//Valve[@className='org.apache.catalina.valves.RemoteIpValve']");

        XmlUtils.insertSiblingAfter(e, remoteIpValve);

        return this;
    }

    protected SetupTomcatConfigurationFiles addEmail(Email email, Document serverDocument, Document contextXmlDocument) {
        logger.info("Add MailSession user={}", email.getUsername());
        Element e = contextXmlDocument.createElement("Resource");
        e.setAttribute("name", email.getName());
        e.setAttribute("auth", "Container");
        e.setAttribute("type", "javax.mail.Session");
        e.setAttribute("mail.smtp.user", email.getUsername());
        e.setAttribute("password", email.getPassword());
        e.setAttribute("mail.smtp.host", email.getHost());
        e.setAttribute("mail.smtp.auth", "true");

        contextXmlDocument.getDocumentElement().appendChild(e);
        return this;
    }

    @Nullable
    protected Resource getResourceByType(@Nonnull Metadata metadata, @Nullable final String type) {

        Collection<Resource> matchingResources = Collections2.filter(metadata.getResources().values(), new Predicate<Resource>() {
            @Override
            public boolean apply(@Nullable Resource r) {
                return Objects.equals(type, r.getType());
            }
        });

        Preconditions.checkState(matchingResources.size() <= 1, "More than 1 resource with type='%s': %s", type, matchingResources);

        return Iterables.getFirst(matchingResources, null);
    }

    protected SetupTomcatConfigurationFiles addSessionStore(SessionStore store, Document serverDocument, Document contextXmlDocument, Metadata metadata) {
        logger.info("Add Memcache SessionStore");

        Resource applicationResource = getResourceByType(metadata, "application");
        boolean stickySessionDefaultValue = applicationResource == null ? false : Boolean.valueOf(applicationResource.getProperty("stickySession", "false"));
        boolean sessionBackupAsyncDefaultValue = stickySessionDefaultValue;

        Element e = contextXmlDocument.createElement("Manager");
        e.setAttribute("className", "de.javakaffee.web.msm.MemcachedBackupSessionManager");
        e.setAttribute("transcoderFactoryClass", "de.javakaffee.web.msm.serializer.kryo.KryoTranscoderFactory");
        e.setAttribute("memcachedProtocol", "binary");
        e.setAttribute("requestUriIgnorePattern", ".*\\.(ico|png|gif|jpg|css|js)$");
        e.setAttribute("sessionBackupAsync", String.valueOf(sessionBackupAsyncDefaultValue));
        e.setAttribute("sticky", String.valueOf(stickySessionDefaultValue));
        e.setAttribute("memcachedNodes", store.getNodes());
        e.setAttribute("username", store.getUsername());
        e.setAttribute("password", store.getPassword());

        Set<String> excludedParameters = Sets.newHashSet("servers", "username", "password", "region", "__resource_name__", "__resource_type__");
        for (Map.Entry<String, String> entry : store.getProperties().entrySet()) {
            if (!excludedParameters.contains(entry.getKey())) {
                e.setAttribute(entry.getKey(), entry.getValue());
            }
        }

        contextXmlDocument.getDocumentElement().appendChild(e);
        return this;
    }

    protected SetupTomcatConfigurationFiles addRemoteAddrValve(Metadata metadata, Document serverXmlDocument, Document contextXmlDocument) {
        String section = "remoteAddress";

        RuntimeProperty runtimeProperty = metadata.getRuntimeProperty(section);
        if (runtimeProperty == null) {
            return this;
        }
        logger.info("Add RemoteAddrValve");

        Set<String> privateAppProperties = new HashSet<>(Arrays.asList(
                "className", "allow", "deny", "denyStatus"));

        Element remoteAddrValve = serverXmlDocument.createElement("Valve");

        remoteAddrValve.setAttribute("className", "org.apache.catalina.valves.RemoteAddrValve");


        for (Map.Entry<String, String> entry : runtimeProperty.entrySet()) {
            if (privateAppProperties.contains(entry.getKey())) {
                remoteAddrValve.setAttribute(entry.getKey(), entry.getValue());
            } else {
                logger.debug("remoteAddrValve: ignore unknown property '" + entry.getKey() + "'");
            }
        }

        Element remoteIpValve = XmlUtils.getUniqueElement(serverXmlDocument, "//Valve[@className='org.apache.catalina.valves.RemoteIpValve']");
        XmlUtils.insertSiblingAfter(remoteAddrValve, remoteIpValve);
        return this;
    }

    protected SetupTomcatConfigurationFiles updateConnectorConfiguration(Metadata metadata, Document serverXmlDocument) {
        String section = "tomcat";
        RuntimeProperty runtimeProperty = metadata.getRuntimeProperty(section);

        if (runtimeProperty == null) {
            return null;
        }

        Iterable<Map.Entry<String, String>> connectorProperties = Iterables.filter(runtimeProperty.entrySet(), new Predicate<Map.Entry<String, String>>() {
            @Override
            public boolean apply(@Nullable Map.Entry<String, String> property) {
                return property != null && property.getKey().startsWith("connector.");
            }
        });

        Element connector = XmlUtils.getUniqueElement(serverXmlDocument, "/Server/Service/Connector");
        for (Map.Entry<String, String> property : connectorProperties) {
            String attributeName = Strings2.substringAfterFirst(property.getKey(), '.');
            connector.setAttribute(attributeName, property.getValue());
        }

        return this;
    }

    protected SetupTomcatConfigurationFiles addPrivateAppValve(Metadata metadata, Document serverXmlDocument, Document contextXmlDocument) {
        String section = "privateApp";

        RuntimeProperty runtimeProperty = metadata.getRuntimeProperty(section);
        if (runtimeProperty == null) {
            return this;
        }
        logger.info("Add PrivateAppValve");

        Set<String> privateAppProperties = new HashSet<>(Arrays.asList(
                "className", "secretKey",
                "authenticationEntryPointName",
                "authenticationParameterName", "authenticationHeaderName", "authenticationUri", "authenticationCookieName",
                "enabled", "realmName", "ignoredUriRegexp"));

        Element privateAppValve = serverXmlDocument.createElement("Valve");

        privateAppValve.setAttribute("className", "com.cloudbees.tomcat.valves.PrivateAppValve");

        for (Map.Entry<String, String> entry : runtimeProperty.entrySet()) {
            if (privateAppProperties.contains(entry.getKey())) {
                privateAppValve.setAttribute(entry.getKey(), entry.getValue());
            } else {
                logger.debug("privateAppValve: ignore unknown property '" + entry.getKey() + "'");
            }
        }
        if (privateAppValve.getAttribute("secretKey").isEmpty()) {
            throw new IllegalStateException("Invalid '" + section +
                    "' configuration, '" + section + "." + "secretKey' is missing");
        }

        Element remoteIpValve = XmlUtils.getUniqueElement(serverXmlDocument, "//Valve[@className='org.apache.catalina.valves.RemoteIpValve']");
        XmlUtils.insertSiblingAfter(privateAppValve, remoteIpValve);
        return this;
    }

    protected void buildTomcatConfiguration(Metadata metadata, Document serverXmlDocument, Document contextXmlDocument) throws ParserConfigurationException {

        String message = "File generated by tomcat-clickstack at " + new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssZ").format(new Date());

        serverXmlDocument.appendChild(serverXmlDocument.createComment(message));
        contextXmlDocument.appendChild(contextXmlDocument.createComment(message));

        for (Resource resource : metadata.getResources().values()) {
            if (resource instanceof Database) {
                addDatabase((Database) resource, serverXmlDocument, contextXmlDocument);
            } else if (resource instanceof Email) {
                addEmail((Email) resource, serverXmlDocument, contextXmlDocument);
            } else if (resource instanceof SessionStore) {
                addSessionStore((SessionStore) resource, serverXmlDocument, contextXmlDocument, metadata);
            }
        }
        addPrivateAppValve(metadata, serverXmlDocument, contextXmlDocument);
        addRemoteAddrValve(metadata, serverXmlDocument, contextXmlDocument);
        addSyslogAccessLogValve(metadata, serverXmlDocument, contextXmlDocument);
        updateConnectorConfiguration(metadata, serverXmlDocument);
    }

    public void buildTomcatConfigurationFiles(Path catalinaBase) throws Exception {

        Preconditions.checkArgument(Files.exists(catalinaBase), "Given catalina.base does not exist %s", catalinaBase);
        Preconditions.checkArgument(Files.isDirectory(catalinaBase), "Given catalina.base is not a directory %s", catalinaBase);


        Path contextXmlPath = catalinaBase.resolve("conf/context.xml");
        Preconditions.checkArgument(Files.exists(contextXmlPath), "Given context.xml does not exist %s", contextXmlPath);

        Document contextXmlDocument = XmlUtils.loadXmlDocumentFromFile(contextXmlPath.toFile());
        XmlUtils.checkRootElement(contextXmlDocument, "Context");


        Path serverXmlPath = catalinaBase.resolve("conf/server.xml");
        Preconditions.checkArgument(Files.exists(serverXmlPath), "Given server.xml does not exist %s", serverXmlPath);

        Document serverXmlDocument = XmlUtils.loadXmlDocumentFromFile(serverXmlPath.toFile());

        this.buildTomcatConfiguration(metadata, serverXmlDocument, contextXmlDocument);

        XmlUtils.flush(contextXmlDocument, new FileOutputStream(contextXmlPath.toFile()));
        XmlUtils.flush(serverXmlDocument, new FileOutputStream(serverXmlPath.toFile()));
    }
}
