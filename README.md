# Tomee 1.6 ClickStack

To use: 

    bees app:deploy -t tomee16 -a APP_ID -RPLUGIN.SRC.tomee16=https://s3.amazonaws.com/clickstacks/antoniomaria/tomee16-clickstack-1.0.0-SNAPSHOT.zip WAR_FILE path/to/my/app.war

Tomcat 8 ClickStack for CloudBees PaaS.


# Build 

    $  gradlew clean installClickstack distClickstack

After successful build

* an expanded `tomcat8-clickstack`is created under `build/install` and can be used with your local [genapp](http://genapp-docs.cloudbees.com/).
* `tomcat8-clickstack-1.2.3.zip` is created under `build/distributions` and can be uploaded to the CloudBees platform location by the CloudBees team.

# Local development

Note: You should be familiar with developing ClickStacks using the genapp system first. \[[see docs](http://genapp-docs.cloudbees.com/quickstart.html)\]

* Build the plugin project using make to prepare for use in local app deploys
* In plugins\_home, add a symlink to the `tomcat8-clickstack/build/install/tomcat8-clickstack` dir named 'tomcat8'

   ```
   $ ln -s tomcat8-clickstack/build/install/tomcat8-clickstack $GENAPP_PLUGINS_HOME/tomcat8
   ```

* In your metadata.json, you can now reference the stack using the name 'tomcat8'

   ```
   { "app": {  "plugins": ["tomcat8"] } }
   ```

Once the plugin is published to a public URL, you can update an app to use it with the CloudBees SDK:

   ```
$ bees app:deploy -a APP_ID -t tomcat8 -RPLUGIN.SRC.tomcat8=URL_TO_YOUR_PLUGIN_ZIP PATH_TO_WARFILE
```

# Key concepts


* `build.gradle` : the gradle build to create the clickstack
* `com.cloudbees.clickstack.tomcat.Setup`: the setup code that instantiate the clickstack


## Clickstack layout

<code><pre>
└── tomcat8-clickstack
    ├── deps <== DEPS TO ADD TO THE DEPLOYED APP
    │   ├── control-lib <== DEPS FOR CONTROL SCRIPTS
    │   │   └── … .jar
    │   ├── javaagent-lib <== DEPS FOR JAVA AGENTS
    │   │   └── ... .jar
    │   ├── tomcat-lib <== DEPS TO UNCONDITIONALLY ADD TO TOMCAT LIB
    │   │   └── ... .jar
    │   ├── tomcat-lib-mail <== DEPS TO ADD IF A MAIL SESSION IS CONFIGURED (SENDGRID)
    │   │   └── ... .jar
    │   ├── tomcat-lib-memcache <== DEPS TO ADD IF MEMCACHE BASED SESSION REPLICATION IS CONFIGURED
    │   │   └── ... .jar
    │   ├── tomcat-lib-mysql <== DEPS TO ADD IF a MYSQL DATABASE IS BOUND TO THE APP
    │   │   └── ... .jar
    │   └── tomcat-lib-postgresql <== DEPS TO ADD IF a POSTGRESQL DATABASE IS BOUND TO THE APP
    │       └── ... .jar
    │
    ├── dist  <== FILES THAT WILL BE COPIED DIRECTLY UNDER APP_DIR
    │   ├── .genapp
    │   │   └── control
    │   │       ├── functions
    │   │       │   └── functions
    │   │       ├── jmx_invoker
    │   │       ├── print_environment
    │   │       ├── send_sigquit
    │   │       ├── start
    │   │       └── stats-appstat
    │   └── catalina-base
    │       └── conf
    │           ├── context.xml
    │           ├── logging.properties
    │           ├── server.xml
    │           ├── tomcat-metrics.xml
    │           └── web.xml
    │ 
    ├── lib <== JARS USED BY THE SETUP SCRIPT
    │   ├── ...
    │   ├── tomcat8-clickstack-1.0.0-SNAPSHOT.jar
    │   └── ...
    │
    ├── setup
    ├── setup.bat
    └── tomcat-8.0.0-RC10.zip <== TOMCAT PACKAGE TO DEPLOY
</pre></code>

### ClickStack Detailed layout

<code><pre>
└── tomcat8-clickstack
    ├── deps
    │   ├── control-lib
    │   │   └── cloudbees-jmx-invoker-1.0.2-jar-with-dependencies.jar
    │   ├── javaagent-lib
    │   │   ├── cloudbees-clickstack-javaagent-1.2.0.jar
    │   │   ├── jmxtrans-agent-1.0.6.jar
    │   │   └── jsr305-2.0.1.jar
    │   ├── tomcat-lib
    │   │   └── cloudbees-web-container-extras-1.0.1.jar
    │   ├── tomcat-lib-mail
    │   │   ├── activation-1.1.jar
    │   │   └── mail-1.4.7.jar
    │   ├── tomcat-lib-memcache
    │   │   ├── annotations-1.3.9.jar
    │   │   ├── asm-3.2.jar
    │   │   ├── jsr305-1.3.9.jar
    │   │   ├── kryo-1.04.jar
    │   │   ├── kryo-serializers-0.10.jar
    │   │   ├── memcached-session-manager-1.6.4.jar
    │   │   ├── memcached-session-manager-tc7-1.6.4.jar
    │   │   ├── minlog-1.2.jar
    │   │   ├── msm-kryo-serializer-1.6.4.jar
    │   │   ├── reflectasm-1.01.jar
    │   │   └── spymemcached-2.8.12.jar
    │   ├── tomcat-lib-mysql
    │   │   └── mysql-connector-java-5.1.25.jar
    │   └── tomcat-lib-postgresql
    │       └── postgresql-9.1-901-1.jdbc4.jar
    ├── dist
    │   ├── .genapp
    │   │   └── control
    │   │       ├── functions
    │   │       │   └── functions
    │   │       ├── jmx_invoker
    │   │       ├── print_environment
    │   │       ├── send_sigquit
    │   │       ├── start
    │   │       └── stats-appstat
    │   └── catalina-base
    │       └── conf
    │           ├── context.xml
    │           ├── logging.properties
    │           ├── server.xml
    │           ├── tomcat-metrics.xml
    │           └── web.xml
    ├── lib
    │   ├── Saxon-HE-9.4.jar
    │   ├── clickstack-framework-1.0.0.jar
    │   ├── dom4j-1.6.1.jar
    │   ├── guava-14.0.1.jar
    │   ├── hamcrest-core-1.3.jar
    │   ├── jackson-annotations-2.1.2.jar
    │   ├── jackson-core-2.1.3.jar
    │   ├── jackson-databind-2.1.3.jar
    │   ├── jdom-1.1.jar
    │   ├── jsr305-2.0.1.jar
    │   ├── slf4j-api-1.7.5.jar
    │   ├── slf4j-simple-1.7.5.jar
    │   ├── tomcat8-clickstack-1.0.0-SNAPSHOT.jar
    │   ├── xalan-2.7.0.jar
    │   ├── xercesImpl-2.8.0.jar
    │   ├── xml-matchers-1.0-RC1.jar
    │   ├── xml-resolver-1.2.jar
    │   ├── xmlunit-1.3.jar
    │   └── xom-1.2.5.jar
    ├── setup
    ├── setup.bat
    └── tomcat-8.0.0-RC10.zip    </pre></code>
    
# Deployed Application Layout

<code><pre>
├── .genapp
│   ├── control
│   │   ├── config
│   │   ├── env
│   │   ├── functions
│   │   │   └── functions
│   │   ├── java-opts-10-core
│   │   ├── java-opts-20-javaagent
│   │   ├── java-opts-20-tomcat-opts
│   │   ├── java-opts-60-jmxtrans-agent
│   │   ├── jmx_invoker
│   │   ├── print_environment
│   │   ├── send_sigquit
│   │   ├── start
│   │   └── stats-appstat
│   ├── lib
│   │   ├── cloudbees-jmx-invoker-1.0.2-jar-with-dependencies.jar
│   │   └── cloudbees-jmx-invoker-jar-with-dependencies.jar -> .../.genapp/lib/cloudbees-jmx-invoker-1.0.2-jar-with-dependencies.jar
│   ├── log
│   ├── metadata.json
│   ├── ports
│   │   └── 8604
│   └── setup_status
│       ├── ok
│       └── plugin_tomcat8_clickstack_0
├── apache-tomcat-8.0.0-RC10
│   ├── LICENSE
│   ├── NOTICE
│   ├── RELEASE-NOTES
│   ├── RUNNING.txt
│   ├── bin
│   │   ├── bootstrap.jar
│   │   ├── catalina-tasks.xml
│   │   ├── catalina.bat
│   │   ├── catalina.sh
│   │   ├── commons-daemon-native.tar.gz
│   │   ├── commons-daemon.jar
│   │   ├── configtest.bat
│   │   ├── configtest.sh
│   │   ├── cpappend.bat
│   │   ├── daemon.sh
│   │   ├── digest.bat
│   │   ├── digest.sh
│   │   ├── setclasspath.bat
│   │   ├── setclasspath.sh
│   │   ├── shutdown.bat
│   │   ├── shutdown.sh
│   │   ├── startup.bat
│   │   ├── startup.sh
│   │   ├── tomcat-juli.jar
│   │   ├── tomcat-native.tar.gz
│   │   ├── tool-wrapper.bat
│   │   ├── tool-wrapper.sh
│   │   ├── version.bat
│   │   └── version.sh
│   ├── conf
│   │   ├── catalina.policy
│   │   ├── catalina.properties
│   │   ├── context.xml
│   │   ├── logging.properties
│   │   ├── server.xml
│   │   ├── tomcat-users.xml
│   │   └── web.xml
│   ├── lib
│   │   ├── annotations-api.jar
│   │   ├── catalina-ant.jar
│   │   ├── catalina-ha.jar
│   │   ├── catalina-storeconfig.jar
│   │   ├── catalina-tribes.jar
│   │   ├── catalina.jar
│   │   ├── ecj-4.2.2.jar
│   │   ├── el-api.jar
│   │   ├── jasper-el.jar
│   │   ├── jasper.jar
│   │   ├── jsp-api.jar
│   │   ├── servlet-api.jar
│   │   ├── tomcat-api.jar
│   │   ├── tomcat-coyote.jar
│   │   ├── tomcat-dbcp.jar
│   │   ├── tomcat-i18n-es.jar
│   │   ├── tomcat-i18n-fr.jar
│   │   ├── tomcat-i18n-ja.jar
│   │   ├── tomcat-jdbc.jar
│   │   ├── tomcat-jni.jar
│   │   ├── tomcat-spdy.jar
│   │   ├── tomcat-util.jar
│   │   ├── tomcat-websocket.jar
│   │   └── websocket-api.jar
│   ├── logs
│   ├── temp
│   │   └── safeToDelete.tmp
│   ├── webapps
│   │   ├── ROOT
│   │   │   └── ...
│   │   ├── docs
│   │   │   └── ...
│   │   ├── examples
│   │   │   └── ...
│   │   ├── host-manager
│   │   │   └── ...
│   │   └── manager
│   │       └── ...
│   └── work
├── app-extra-files
├── catalina-base
│   ├── conf
│   │   ├── context.xml
│   │   ├── logging.properties
│   │   ├── server.xml
│   │   ├── tomcat-metrics.xml
│   │   └── web.xml
│   ├── lib
│   │   ├── activation-1.1.jar
│   │   ├── cloudbees-web-container-extras-1.0.1.jar
│   │   ├── mail-1.4.7.jar
│   │   └── mysql-connector-java-5.1.25.jar
│   ├── logs
│   ├── webapps
│   │   └── ROOT
│   │       ├── WEB-INF
│   │       │   ├── classes
│   │       │   │   └── *.class
│   │       │   ├── lib
│   │       │   │   └── *.jar
│   │       │   └── web.xml
│   │       └── *.jsp ...
│   └── work
├── javaagent-lib
│   ├── cloudbees-clickstack-javaagent-1.2.0.jar
│   └── jmxtrans-agent-1.0.6.jar
└── tmp</pre></code>