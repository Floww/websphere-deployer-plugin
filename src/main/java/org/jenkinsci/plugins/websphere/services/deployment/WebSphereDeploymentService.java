package org.jenkinsci.plugins.websphere.services.deployment;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Hashtable;
import java.util.List;
import java.util.Locale;
import java.util.Properties;
import java.util.Set;
import java.util.logging.Logger;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import javax.management.MalformedObjectNameException;
import javax.management.NotificationFilterSupport;
import javax.management.ObjectName;

import org.apache.commons.lang.StringUtils;

import com.ibm.websphere.management.AdminClient;
import com.ibm.websphere.management.AdminClientFactory;
import com.ibm.websphere.management.application.AppConstants;
import com.ibm.websphere.management.application.AppManagement;
import com.ibm.websphere.management.application.AppManagementProxy;
import com.ibm.websphere.management.application.AppNotification;
import com.ibm.websphere.management.application.client.AppDeploymentController;
import com.ibm.websphere.management.application.client.AppDeploymentTask;
import com.ibm.websphere.management.exception.ConnectorException;

/**
 * @author Greg Peters
 */
public class WebSphereDeploymentService extends AbstractDeploymentService {

    public static final String CONNECTOR_TYPE_SOAP = "SOAP";
    private static final String className = WebSphereDeploymentService.class.getName();
    private static Logger log = Logger.getLogger(className);

    private AdminClient client;
    private String connectorType;
    private String targetCluster;
    private String targetServer;
    private String targetNode;
    private String targetCell;

    public List<Server> listServers() {
        try {
            ObjectName jvmQuery = new ObjectName("WebSphere:*,type=Server");
            Set<ObjectName> response = client.queryNames(jvmQuery,null);
            List<Server> servers = new ArrayList<Server>();
            for(ObjectName serverObjectName:response) {
                Server server = new Server();
                server.setCellName(String.valueOf(client.getAttribute(serverObjectName,"cellName")));
                server.setNodeName(String.valueOf(client.getAttribute(serverObjectName,"nodeName")));
                server.setServerName(String.valueOf(client.getAttribute(serverObjectName, "name")));
                server.setProcessId(String.valueOf(client.getAttribute(serverObjectName, "pid")));
                server.setServerVendor(String.valueOf(client.getAttribute(serverObjectName, "serverVendor")));
                server.setServerVersion(String.valueOf(client.getAttribute(serverObjectName, "serverVersion")));
                servers.add(server);
            }
            return servers;
        } catch(Exception e) {
            e.printStackTrace();
            throw new DeploymentServiceException(e.getMessage(),e);
        }
    }

    public void generateEAR(Artifact artifact, File destination,String earLevel) {

            byte[] buf = new byte[1024];
            try {
                String warName = artifact.getSourcePath().getName();
                String context = warName.substring(0,warName.lastIndexOf("."));
                ZipOutputStream out = new ZipOutputStream(new FileOutputStream(destination));
                FileInputStream in = new FileInputStream(artifact.getSourcePath());
                out.putNextEntry(new ZipEntry(artifact.getSourcePath().getName()));
                int len;
                while ((len = in.read(buf)) > 0) {
                    out.write(buf, 0, len);
                }
                out.closeEntry();
                in.close();
                out.putNextEntry(new ZipEntry("META-INF/"));
                out.closeEntry();
                out.putNextEntry(new ZipEntry("META-INF/application.xml"));
                out.write(getApplicationXML(context,earLevel).getBytes());
                out.closeEntry();
                out.close();
            } catch (Exception e) {
                e.printStackTrace();
            }
    }

    private String getApplicationXML(String warName,String earLevel) {
        return "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n" +
                                "<application xmlns=\"http://java.sun.com/xml/ns/javaee\" xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\" xsi:schemaLocation=\"http://java.sun.com/xml/ns/javaee http://java.sun.com/xml/ns/javaee/application_"+earLevel+".xsd\" version=\""+earLevel+"\">\n" +
                                "  <description>"+warName+"</description>\n" +
                                "  <display-name>"+warName+"</display-name>\n" +
                                "  <module>\n" +
                                "    <web>\n" +
                                "      <web-uri>"+warName+".war</web-uri>\n" +
                                "      <context-root>/"+warName+"</context-root>\n" +
                                "    </web>\n" +
                                "  </module>\n" +
                                "</application>";
    }

    public String getAppName(String path) {
        return getAppName(new File(path));
    }

    public String getAppName(File file) {
        try {
            Hashtable<String,Object> preferences = new Hashtable<String,Object>();
            preferences.put(AppConstants.APPDEPL_LOCALE, Locale.getDefault());

            preferences.put(AppConstants.APPDEPL_DFLTBNDG, new Properties());

            AppDeploymentController controller = AppDeploymentController.readArchive(file.getAbsolutePath(),preferences);

            AppDeploymentTask task = controller.getFirstTask();
            while (task != null) {
                String[][] data = task.getTaskData();
                task.setTaskData(data);
                task = controller.getNextTask();
            }
            controller.saveAndClose();

            Hashtable<String,Object> config = controller.getAppDeploymentSavedResults();
            return (String)config.get(AppConstants.APPDEPL_APPNAME);
        } catch(Exception e) {
            e.printStackTrace();
            throw new DeploymentServiceException(e.getMessage(),e);
        }
    }

    public void installArtifact(Artifact artifact,HashMap<String,Object> options) {
        if(!isConnected()) {
            throw new DeploymentServiceException("Cannot install artifact, no connection to WebSphere Application Server exists");
        }
        try {
            Hashtable<String,Object> preferences = new Hashtable<String,Object>();
            preferences.put(AppConstants.APPDEPL_LOCALE, Locale.getDefault());

            Properties defaultBinding = new Properties();
            preferences.put(AppConstants.APPDEPL_DFLTBNDG, defaultBinding);
            if(options.containsKey(AppConstants.APPDEPL_DFLTBNDG_VHOST)) {
                defaultBinding.put(AppConstants.APPDEPL_DFLTBNDG_VHOST, options.get(AppConstants.APPDEPL_DFLTBNDG_VHOST));
            }

            AppDeploymentController controller = AppDeploymentController.readArchive(artifact.getSourcePath().getAbsolutePath(), preferences);
            
            String[] validationResult = controller.validate();
            if (validationResult != null && validationResult.length > 0) {
               throw new DeploymentServiceException("Unable to complete all task data for deployment preparation. Reason: " + Arrays.toString(validationResult));
            }

            controller.saveAndClose();

            preferences.put(AppConstants.APPDEPL_LOCALE, Locale.getDefault());
            preferences.put(AppConstants.APPDEPL_ARCHIVE_UPLOAD, Boolean.TRUE);
            preferences.put(AppConstants.APPDEPL_PRECOMPILE_JSP, artifact.isPrecompile());

            Hashtable<String,Object> module2server = new Hashtable<String,Object>();
            module2server.put("*", getTarget());
            preferences.put(AppConstants.APPDEPL_MODULE_TO_SERVER, module2server);
            
            AppManagement appManagementProxy = AppManagementProxy.getJMXProxyForClient(getAdminClient());
            
            appManagementProxy.installApplication(artifact.getSourcePath().getAbsolutePath(),artifact.getAppName(),preferences, null);
            
            NotificationFilterSupport filterSupport = createFilterSupport();
            DeploymentNotificationListener listener = new DeploymentNotificationListener(getAdminClient(), 
                     filterSupport, "Install " + artifact.getAppName(),AppNotification.INSTALL);            
            
            synchronized(listener) 
            {
               listener.wait();
            }

            if(!listener.isSuccessful())
               throw new IllegalStateException("Application not sucessfully deployed: " + listener.getMessage());            
            
        } catch (Exception e) {
            e.printStackTrace();
            throw new DeploymentServiceException("Failed to install artifact: "+e.getMessage());
        }
    }

    public void uninstallArtifact(String appName) throws Exception {
    	try {
			Hashtable<Object, Object> prefs = new Hashtable<Object, Object>();
			NotificationFilterSupport filterSupport = createFilterSupport();
			
			DeploymentNotificationListener listener = new DeploymentNotificationListener(getAdminClient(),filterSupport,"Uninstall " + appName, AppNotification.UNINSTALL);        

			AppManagement appManagementProxy = AppManagementProxy.getJMXProxyForClient(getAdminClient());
			
			appManagementProxy.uninstallApplication(appName,prefs,null);
			
			synchronized(listener) 
			{
			   listener.wait();
			}
			if(!listener.isSuccessful()){
			   throw new IllegalStateException("Application not sucessfully undeployed: " + listener.getMessage());
			}
		} catch (Exception e) {
			throw new DeploymentServiceException("Could not undeploy application", e);
		}
    }

    public void startArtifact(String appName) throws Exception {
    	startArtifact(appName, 5);
    }
    
    public void startArtifact(String appName, int deploymentTimeout) throws Exception {
        try {
        	NotificationFilterSupport filterSupport = createFilterSupport();
        	AppManagement appManagementProxy = AppManagementProxy.getJMXProxyForClient(getAdminClient());
            DeploymentNotificationListener distributionListener = null;
            int checkCount = 0;
            
            int secsToWait = deploymentTimeout * 60;
            
            while (checkDistributionStatus(distributionListener) != AppNotification.DISTRIBUTION_DONE
                  && ++checkCount < secsToWait)
            {
               Thread.sleep(1000);
               
               distributionListener = new DeploymentNotificationListener(getAdminClient(), filterSupport, null, AppNotification.DISTRIBUTION_STATUS_NODE);
               
               synchronized(distributionListener)
               {
                  appManagementProxy.getDistributionStatus(appName, new Hashtable<Object, Object>(), null);
                  distributionListener.wait();
               }
            }

            if (checkCount <= secsToWait)
            {
               String targetsStarted = appManagementProxy.startApplication(appName, null, null);
               log.info("Application was started on the following targets: " + targetsStarted);
               if (targetsStarted == null)
                  throw new IllegalStateException("Start of the application was not successful. WAS JVM logs should contain the detailed error message.");
            } else {
               throw new IllegalStateException("Distribution of application did not succeed to all nodes.");
            }        	
            AppManagementProxy.getJMXProxyForClient(getAdminClient()).startApplication(appName, new Hashtable(), null);
        } catch(Exception e) {
            e.printStackTrace();
            throw new DeploymentServiceException("Could not start artifact '"+appName+"': "+e.toString());
        }
    }

    public void stopArtifact(String name) throws Exception {
        try {
            AppManagementProxy.getJMXProxyForClient(getAdminClient()).stopApplication(name, new Hashtable(), null);
        } catch(Exception e) {
            e.printStackTrace();
            throw new DeploymentServiceException("Could not stop artifact '"+name+"': "+e.getMessage());
        }
    }

    public boolean isArtifactInstalled(String name) {
        try {
            return AppManagementProxy.getJMXProxyForClient(getAdminClient()).checkIfAppExists(name, new Hashtable(), null);
        } catch(Exception e) {
            e.printStackTrace();
            throw new DeploymentServiceException("Could not determine if artifact '"+name+"' is installed: "+e.getMessage());
        }
    }

    private NotificationFilterSupport createFilterSupport(){
        NotificationFilterSupport filterSupport = new NotificationFilterSupport();
        filterSupport.enableType(AppConstants.NotificationType);
        return filterSupport;
    }

    public boolean isConnected() {
        try {
            return client != null && client.isAlive() != null;
        } catch(Exception e) {
            return false;
        }
    }

    public void connect() throws Exception {
        if(isConnected()) {
            System.out.println("WARNING: Already connected to WebSphere Application Server");
        }
        Properties config = new Properties();
        config.put (AdminClient.CONNECTOR_HOST, getHost());
        config.put (AdminClient.CONNECTOR_PORT, getPort());
        if(StringUtils.trimToNull(getUsername()) != null) {
            injectSecurityConfiguration(config);
        }
        config.put(AdminClient.AUTH_TARGET, getTarget());
        config.put (AdminClient.CONNECTOR_TYPE, getConnectorType());
        client = AdminClientFactory.createAdminClient(config);
        if(client == null) {
            throw new DeploymentServiceException("Unable to connect to IBM WebSphere Application Server @ "+getHost()+":"+getPort());
        }
    }

    public void disconnect() {
        client = null;
    }

    public boolean isAvailable() {
        try {
            Class.forName("com.ibm.websphere.management.AdminClientFactory", false, getClass().getClassLoader());
            return true;
        } catch(Throwable e) {
            return false;
        }
    }

    private AdminClient getAdminClient() throws ConnectorException {
        if(client == null) {
            throw new DeploymentServiceException("No connection to WebSphere exists");
        }
        return client;
    }

    private void injectSecurityConfiguration(Properties config) {
        config.put(AdminClient.CACHE_DISABLED, "false");
        config.put(AdminClient.CONNECTOR_SECURITY_ENABLED, "true");
        config.put(AdminClient.USERNAME, getUsername());
        config.put(AdminClient.PASSWORD, getPassword());

        config.put("com.ibm.ssl.trustStore", getTrustStoreLocation().getAbsolutePath());
        config.put("javax.net.ssl.trustStore", getTrustStoreLocation().getAbsolutePath());

        config.put("com.ibm.ssl.keyStore", getKeyStoreLocation().getAbsolutePath());
        config.put("javax.net.ssl.keyStore", getKeyStoreLocation().getAbsolutePath());

        config.put("com.ibm.ssl.trustStorePassword", getTrustStorePassword());
        config.put("javax.net.ssl.trustStorePassword",getTrustStorePassword());

        config.put("com.ibm.ssl.keyStorePassword", getKeyStorePassword());
        config.put("javax.net.ssl.keyStorePassword", getKeyStorePassword());
    }

    private String getTarget() {
        StringBuilder builder = new StringBuilder();
        builder.append("WebSphere:");
        appendTarget(builder,"cluster=",getTargetCluster());
        appendTarget(builder,"cell=",getTargetCell());
        appendTarget(builder,"node=",getTargetNode());
        appendTarget(builder,"server=",getTargetServer());
        return builder.toString();
    }

    private void appendTarget(StringBuilder builder,String target,String value) {
        if(StringUtils.trimToNull(value) != null) {
            if(!isFirstTarget(builder)) {
                builder.append(",");
            }
            builder.append(target);
            builder.append(value);
        }
    }

    private boolean isFirstTarget(StringBuilder builder) {
        return builder.toString().endsWith(":");
    }

    public void setConnectorType(String type) {
        this.connectorType = type;
    }

    public String getConnectorType() {
        return this.connectorType;
    }

    public String getTargetCluster() {
        return targetCluster;
    }

    public void setTargetCluster(String targetCluster) {
        this.targetCluster = targetCluster;
    }

    public String getTargetServer() {
        return targetServer;
    }

    public void setTargetServer(String targetServer) {
        this.targetServer = targetServer;
    }

    public String getTargetNode() {
        return targetNode;
    }

    public void setTargetNode(String targetNode) {
        this.targetNode = targetNode;
    }

    public String getTargetCell() {
        return targetCell;
    }

    public void setTargetCell(String targetCell) {
        this.targetCell = targetCell;
    }

    /*
     * Checks the listener and figures out the aggregate distribution status of all nodes
     */
    private String checkDistributionStatus(DeploymentNotificationListener listener) throws MalformedObjectNameException, NullPointerException, IllegalStateException {
       String distributionState = AppNotification.DISTRIBUTION_UNKNOWN;
       if (listener != null)
       {
         String compositeStatus = listener.getNotificationProps()
            .getProperty(AppNotification.DISTRIBUTION_STATUS_COMPOSITE);
         if (compositeStatus != null)
         {
            log.finer("compositeStatus: " + compositeStatus);
            String[] serverStati = compositeStatus.split("\\+");
            int countTrue = 0, countFalse = 0, countUnknown = 0;
            for (String serverStatus : serverStati)
            {
               ObjectName objectName = new ObjectName(serverStatus);
               distributionState = objectName.getKeyProperty("distribution");
               log.finer("distributionState: " + distributionState);
               if (distributionState.equals("true"))
                  countTrue++;
               if (distributionState.equals("false"))
                  countFalse++;
               if (distributionState.equals("unknown"))
                  countUnknown++;
            }
            if (countUnknown > 0)
            {
               distributionState = AppNotification.DISTRIBUTION_UNKNOWN;
            } else if (countFalse > 0) {
               distributionState = AppNotification.DISTRIBUTION_NOT_DONE;
            } else if (countTrue > 0) {
               distributionState = AppNotification.DISTRIBUTION_DONE;
            } else {
               throw new IllegalStateException("Reported distribution status is invalid.");
            }
         }
       }
       return distributionState;
    }

}
