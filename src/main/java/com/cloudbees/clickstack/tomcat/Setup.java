/*
 * Copyright 2010-2013, CloudBees Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.cloudbees.clickstack.tomcat;

import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.net.InetAddress;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collection;
import java.util.Collections;
import java.util.Properties;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.cloudbees.clickstack.domain.environment.Environment;
import com.cloudbees.clickstack.domain.metadata.Database;
import com.cloudbees.clickstack.domain.metadata.Email;
import com.cloudbees.clickstack.domain.metadata.Metadata;
import com.cloudbees.clickstack.domain.metadata.SessionStore;
import com.cloudbees.clickstack.domain.metadata.Syslog;
import com.cloudbees.clickstack.plugin.java.JavaPlugin;
import com.cloudbees.clickstack.plugin.java.JavaPluginResult;
import com.cloudbees.clickstack.util.CommandLineUtils;
import com.cloudbees.clickstack.util.Files2;
import com.cloudbees.clickstack.util.Manifests;
import com.google.common.base.Charsets;
import com.google.common.base.Preconditions;
import com.google.common.base.Predicate;
import com.google.common.collect.Collections2;
import com.google.common.collect.Iterables;

public class Setup {

    protected final Logger logger = LoggerFactory.getLogger(getClass());
    @Nonnull
    final Path appDir;
    @Nonnull
    final Path genappDir;
    @Nonnull
    final Path controlDir;
    @Nonnull
    final Path clickstackDir;
    @Nonnull
    final Path javaHome;
    @Nonnull
    final Path catalinaBase;
    @Nonnull
    final Path warFile;
    @Nonnull
    final Path logDir;
    @Nonnull
    final Path tmpDir;
    @Nonnull
    final Path agentLibDir;
    @Nonnull
    final Path appExtraFilesDir;
    @Nonnull
    final Metadata metadata;
    @Nonnull
    final Environment env;
    /**
     * initialised by {@link #installCatalinaHome()}
     */
    @Nullable
    Path catalinaHome;

    public Setup(@Nonnull
    Environment env, @Nonnull
    Metadata metadata, @Nonnull
    Path javaHome) throws IOException {
        logger.info("Setup: {}, {}", env, metadata);

        this.env = env;
        appDir = env.appDir;

        genappDir = env.genappDir;

        controlDir = env.controlDir;
        logDir = Files.createDirectories(genappDir.resolve("log"));
        Files2.chmodAddReadWrite(logDir);

        catalinaBase = Files.createDirectories(appDir.resolve("catalina-base"));

        agentLibDir = Files.createDirectories(appDir.resolve("javaagent-lib"));

        tmpDir = Files.createDirectories(appDir.resolve("tmp"));
        Files2.chmodAddReadWrite(tmpDir);

        clickstackDir = env.clickstackDir;
        Preconditions.checkState(Files.exists(clickstackDir) && Files.isDirectory(clickstackDir));

        warFile = env.packageDir.resolve("app.war");
        Preconditions.checkState(Files.exists(warFile), "File not found %s", warFile);
        Preconditions.checkState(!Files.isDirectory(warFile), "Expected to be a file and not a directory %s", warFile);

        appExtraFilesDir = Files.createDirectories(appDir.resolve("app-extra-files"));
        Files2.chmodAddReadWrite(appExtraFilesDir);

        this.metadata = metadata;

        this.javaHome = Preconditions.checkNotNull(javaHome, "javaHome");
        Preconditions.checkArgument(Files.exists(javaHome), "JavaHome does not exist %s", javaHome);

        logger.debug("warFile: {}", warFile.toAbsolutePath());
        logger.debug("catalinaBase: {}", catalinaBase.toAbsolutePath());
        logger.debug("agentLibDir: {}", agentLibDir.toAbsolutePath());
        logger.debug("appExtraFilesDir: {}", appExtraFilesDir.toAbsolutePath());
    }

    public static void main(String[] args) throws Exception {
        try {
            Logger initialisationLogger = LoggerFactory.getLogger(Setup.class);

            initialisationLogger.info("Setup clickstack {} - {}, current dir {}",
                    Manifests.getAttribute(Setup.class, "Implementation-Artifact"),
                    Manifests.getAttribute(Setup.class, "Implementation-Date"), FileSystems.getDefault().getPath(".")
                            .toAbsolutePath());
            Environment env = CommandLineUtils.argumentsToEnvironment(args);
            Path metadataPath = env.genappDir.resolve("metadata.json");
            Metadata metadata = Metadata.Builder.fromFile(metadataPath);

            JavaPlugin javaPlugin = new JavaPlugin();
            JavaPluginResult javaPluginResult = javaPlugin.setup(metadata, env);

            Setup setup = new Setup(env, metadata, javaPluginResult.getJavaHome());
            setup.setup();
        } catch (Exception e) {
            String hostname;
            try {
                hostname = InetAddress.getLocalHost().getHostAddress();
            } catch (Exception e2) {
                hostname = "#hostname#";
            }
            throw new Exception("Exception deploying on " + hostname, e);
        }
    }

    public void setup() throws Exception {
        installCatalinaHome();
        installSkeleton();
        Path catalinaBase = installCatalinaBase();
        installEnvClickstackProvided();
        installCloudBeesJavaAgent();
        installJmxTransAgent();
        writeJavaOpts();
        writeConfig();
        installControlScripts();
        installTomcatJavaOpts();

        SetupTomcatConfigurationFiles setupTomcatConfigurationFiles = new SetupTomcatConfigurationFiles(metadata);
        setupTomcatConfigurationFiles.buildTomcatConfigurationFiles(catalinaBase);
        logger.info("Clickstack successfully installed");
    }

    public void installSkeleton() throws IOException {
        logger.debug("installSkeleton() {}", appDir);

        Files2.copyDirectoryContent(clickstackDir.resolve("dist"), appDir);
    }

    public void installTomcatJavaOpts() throws IOException {
        Path optsFile = controlDir.resolve("java-opts-20-tomcat-opts");
        logger.debug("installTomcatJavaOpts() {}", optsFile);

        String opts = "" + "-Djava.io.tmpdir=\"" + tmpDir + "\" " + "-Dcatalina.home=\"" + catalinaHome + "\" "
                + "-Dcatalina.base=\"" + catalinaBase + "\" " + "-Dapp_extra_files=\"" + appExtraFilesDir + "\" "
                + "-Djava.util.logging.manager=org.apache.juli.ClassLoaderLogManager "
                + "-Djava.util.logging.config.file=\"" + catalinaBase + "/conf/logging.properties\"";

        Files.write(optsFile, Collections.singleton(opts), Charsets.UTF_8);
    }

    public void installCatalinaHome() throws Exception {

        Path tomcatPackagePath = Files2.findArtifact(clickstackDir, "apache-tomee", "zip");
        Files2.unzip(tomcatPackagePath, appDir);
        catalinaHome = Files2.findUniqueDirectoryBeginningWith(appDir, "apache-tomee");
        logger.debug("installCatalinaHome() {}", catalinaHome);

        Files2.chmodReadOnly(catalinaHome);

        // some frameworks like Grails try to write in ${catalina.home}/logs
        Path logsDir = Files.createDirectories(catalinaHome.resolve("logs"));
        Files2.chmodAddReadWrite(logsDir);

        Path workDir = Files.createDirectories(catalinaHome.resolve("work"));
        Files2.chmodAddReadWrite(workDir);

        Path tempDir = Files.createDirectories(catalinaHome.resolve("temp"));
        Files2.chmodAddReadWrite(tempDir);

    }

    public Path installCatalinaBase() throws IOException {
        logger.debug("installCatalinaBase() {}", catalinaBase);

        Files.createDirectories(catalinaBase.resolve("work"));

        Files.createDirectories(catalinaBase.resolve("logs"));

        String contextPath = metadata.getRuntimeParameter("webapp", "contextPath", null);
        if (contextPath == null) {
            contextPath = "ROOT";
        } else {
            if (contextPath.startsWith("/")) {
                contextPath = contextPath.substring(1);
            }
            if (contextPath.isEmpty()) {
                contextPath = "ROOT";
            }
            logger.info("Deploy application under custom contextPath '/{}'", contextPath);
        }

        // WEB APP
        Path rootWebAppDir = Files.createDirectories(catalinaBase.resolve("webapps").resolve(contextPath));
        Files2.unzip(warFile, rootWebAppDir);

        // CONFIGURATION FILES
        Path webAppBundledContextXmlFile = rootWebAppDir.resolve("META-INF/context.xml");
        Path catalinaBaseContextXml = catalinaBase.resolve("conf/context.xml");
        if (Files.exists(webAppBundledContextXmlFile) && !Files.isDirectory(webAppBundledContextXmlFile)) {
            logger.info("Copy application provided context.xml");
            Files.move(catalinaBaseContextXml, catalinaBase.resolve("conf/context-initial.xml"));
            Files.copy(webAppBundledContextXmlFile, catalinaBaseContextXml);
        }

        Path webAppBundledServerXmlFile = rootWebAppDir.resolve("META-INF/server.xml");
        Path catalinaBaseServerXml = catalinaBase.resolve("conf/server.xml");
        if (Files.exists(webAppBundledServerXmlFile) && !Files.isDirectory(webAppBundledServerXmlFile)) {
            logger.info("Copy application provided server.xml");
            Files.move(catalinaBaseServerXml, catalinaBase.resolve("conf/server-initial.xml"));
            Files.copy(webAppBundledServerXmlFile, catalinaBaseServerXml);
        }

        Path webAppBundledExtraFiles = rootWebAppDir.resolve("META-INF/extra-files");
        if (Files.exists(webAppBundledExtraFiles) && Files.isDirectory(webAppBundledExtraFiles)) {
            logger.info("Copy application provided extra files");
            Files2.copyDirectoryContent(webAppBundledExtraFiles, appExtraFilesDir);
        }

        Path webAppBundledExtraLibs = rootWebAppDir.resolve("META-INF/lib");
        if (Files.exists(webAppBundledExtraLibs) && Files.isDirectory(webAppBundledExtraLibs)) {
            logger.info("Copy application provided extra libs");
            Files2.copyDirectoryContent(webAppBundledExtraLibs, catalinaBase.resolve("lib"));
        }

        // CUSTOM JAAS REALM
        /*-
        Path jaasConfigurationFile = clickstackDir.resolve("conf/jaas.config.properties");
        Path configurationRootDir = catalinaBase.resolve("conf/");
        logger.error("clickstackDir" + clickstackDir);
        logger.error("configurationRootDir" + configurationRootDir);

        if (Files.exists(jaasConfigurationFile)) {
            logger.info("JAAS configuration file copied");
            Files.copy(jaasConfigurationFile, configurationRootDir);
        }*/

        // LIBRARIES

        Path targetLibDir = Files.createDirectories(catalinaBase.resolve("lib"));
        Files2.copyDirectoryContent(clickstackDir.resolve("deps/tomcat-lib"), targetLibDir);

        // JDBC Drivers
        Collection<Database> mysqlDatabases = Collections2.filter(metadata.getResources(Database.class),
                new Predicate<Database>() {
                    @Override
                    public boolean apply(@Nullable
                    Database database) {
                        return Database.DRIVER_MYSQL.equals(database.getDriver());
                    }
                });
        if (!mysqlDatabases.isEmpty()) {
            logger.debug("Add mysql jars");
            Files2.copyDirectoryContent(clickstackDir.resolve("deps/tomcat-lib-mysql"), targetLibDir);
        }

        Collection<Database> postgresqlDatabases = Collections2.filter(metadata.getResources(Database.class),
                new Predicate<Database>() {
                    @Override
                    public boolean apply(@Nullable
                    Database database) {
                        return Database.DRIVER_POSTGRES.equals(database.getDriver());
                    }
                });
        if (!postgresqlDatabases.isEmpty()) {
            Files2.copyDirectoryContent(clickstackDir.resolve("deps/tomcat-lib-postgresql"), targetLibDir);
        }

        // Mail
        if (!metadata.getResources(Email.class).isEmpty()) {
            logger.debug("Add mail jars");
            Files2.copyDirectoryContent(clickstackDir.resolve("deps/tomcat-lib-mail"), targetLibDir);
        }

        // Memcache
        if (!metadata.getResources(SessionStore.class).isEmpty()) {
            logger.debug("Add memcache jars");
            Files2.copyDirectoryContent(clickstackDir.resolve("deps/tomcat-lib-memcache"), targetLibDir);
        }

        Files2.chmodAddReadWrite(catalinaBase);

        return catalinaBase;
    }

    public void installJmxTransAgent() throws IOException {
        logger.debug("installJmxTransAgent() {}", agentLibDir);

        Path jmxtransAgentJarFile = Files2.copyArtifactToDirectory(clickstackDir.resolve("deps/javaagent-lib"),
                "jmxtrans-agent", agentLibDir);
        Path jmxtransAgentConfigurationFile = catalinaBase.resolve("conf/tomcat-metrics.xml");
        Preconditions.checkState(Files.exists(jmxtransAgentConfigurationFile), "File %s does not exist",
                jmxtransAgentConfigurationFile);
        Path jmxtransAgentDataFile = logDir.resolve("tomcat-metrics.data");

        Path agentOptsFile = controlDir.resolve("java-opts-60-jmxtrans-agent");

        String agentOptsFileData = "-javaagent:" + jmxtransAgentJarFile.toString() + "="
                + jmxtransAgentConfigurationFile.toString() + " -Dtomcat_metrics_data_file="
                + jmxtransAgentDataFile.toString();

        Files.write(agentOptsFile, Collections.singleton(agentOptsFileData), Charsets.UTF_8);
    }

    public void installCloudBeesJavaAgent() throws IOException {
        logger.debug("installCloudBeesJavaAgent() {}", agentLibDir);

        Path cloudbeesJavaAgentJarFile = Files2.copyArtifactToDirectory(clickstackDir.resolve("deps/javaagent-lib"),
                "cloudbees-clickstack-javaagent", agentLibDir);
        Path agentOptsFile = controlDir.resolve("java-opts-20-javaagent");

        Path envFile = controlDir.resolve("env");
        if (!Files.exists(envFile)) {
            throw new IllegalStateException("'env' file not found at " + envFile);
        }

        Path envClickstackProvidedFile = controlDir.resolve("env-clickstack-provided");
        if (!Files.exists(envClickstackProvidedFile)) {
            throw new IllegalStateException("'env-clickstack-provided' file not found at " + envClickstackProvidedFile);
        }

        // 'env' file declared after 'env-clickstack-provided' because customer
        // provided values must override clickstack-provided values
        String agentOptsFileData = "-javaagent:" + cloudbeesJavaAgentJarFile + "=" + envClickstackProvidedFile + ","
                + envFile;

        Files.write(agentOptsFile, Collections.singleton(agentOptsFileData), Charsets.UTF_8);
    }

    public void writeJavaOpts() throws IOException {
        Path javaOptsFile = controlDir.resolve("java-opts-10-core");
        logger.debug("writeJavaOpts() {}", javaOptsFile);

        String javaOpts = metadata.getRuntimeParameter("java", "opts", "");
        Files.write(javaOptsFile, Collections.singleton(javaOpts), Charsets.UTF_8);
    }

    public void writeConfig() throws IOException {

        Path configFile = controlDir.resolve("config");
        logger.debug("writeConfig() {}", configFile);

        PrintWriter writer = new PrintWriter(Files.newOutputStream(configFile));

        writer.println("app_dir=\"" + appDir + "\"");
        writer.println("app_extra_files=\"" + appExtraFilesDir + "\"");
        writer.println("app_tmp=\"" + appDir.resolve("tmp") + "\"");
        writer.println("log_dir=\"" + logDir + "\"");
        writer.println("catalina_home=\"" + catalinaHome + "\"");
        writer.println("catalina_base=\"" + catalinaBase + "\"");

        writer.println("port=" + env.appPort);

        writer.println("JAVA_HOME=\"" + javaHome + "\"");
        writer.println("java=\"" + javaHome.resolve("bin/java") + "\"");
        writer.println("genapp_dir=\"" + genappDir + "\"");

        writer.println("catalina_opts=\"-Dport.http=" + env.appPort + "\"");

        String classpath = "" + catalinaHome.resolve("bin/bootstrap.jar") + ":"
                + catalinaHome.resolve("bin/tomcat-juli.jar") + ":" + catalinaHome.resolve("lib");
        writer.println("java_classpath=\"" + classpath + "\"");

        writer.close();
    }

    public void installEnvClickstackProvided() throws IOException {
        Properties envClickstackProvided = new Properties();

        Collection<Syslog> syslogConfigs = metadata.getResources(Syslog.class);
        if (syslogConfigs.size() == 1) {
            Syslog syslog = Iterables.getOnlyElement(syslogConfigs);
            envClickstackProvided.put("SYSLOG_HOST", syslog.getHostname());
            envClickstackProvided.put("SYSLOG_PORT", String.valueOf(syslog.getPort()));
            envClickstackProvided.put("SYSLOG_FACILITY", syslog.getFacility());
            envClickstackProvided.put("SYSLOG_APP_NAME", syslog.getAppName(env));
            envClickstackProvided.put("SYSLOG_APP_HOSTNAME", syslog.getAppHostname(env));

        } else {
            // TODO: inject env variables suffixed by the "sanitized" resource
            // name (e.g. )
            logger.warn("More or less ({}) than 1 syslog configuration found, don't inject configuration in : {}",
                    syslogConfigs.size(), syslogConfigs);

        }

        OutputStream out = Files.newOutputStream(controlDir.resolve("env-clickstack-provided"));
        envClickstackProvided.store(out, "Generated by tomcat-clickstack");
    }

    public void installControlScripts() throws IOException {
        logger.debug("installControlScripts() {}", controlDir);

        Files2.chmodAddReadExecute(controlDir);

        Path genappLibDir = genappDir.resolve("lib");
        Files.createDirectories(genappLibDir);

        Path jmxInvokerPath = Files2.copyArtifactToDirectory(clickstackDir.resolve("deps/control-lib"),
                "cloudbees-jmx-invoker", genappLibDir);
        // create symlink without version to simplify jmx_invoker script
        Files.createSymbolicLink(genappLibDir.resolve("cloudbees-jmx-invoker-jar-with-dependencies.jar"),
                jmxInvokerPath);
    }
}
