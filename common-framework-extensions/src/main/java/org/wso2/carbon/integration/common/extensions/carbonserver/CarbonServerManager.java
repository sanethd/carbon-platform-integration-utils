/*
 * Copyright 2005-2007 WSO2, Inc. (http://wso2.com)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.wso2.carbon.integration.common.extensions.carbonserver;

import org.apache.commons.io.FilenameUtils;
import org.apache.commons.lang3.ArrayUtils;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.wso2.carbon.authenticator.stub.LoginAuthenticationExceptionException;
import org.wso2.carbon.automation.engine.context.AutomationContext;
import org.wso2.carbon.automation.engine.frameworkutils.CodeCoverageUtils;
import org.wso2.carbon.integration.common.admin.client.ServerAdminClient;
import org.wso2.carbon.integration.common.extensions.utils.ArchiveExtractor;
import org.wso2.carbon.integration.common.extensions.utils.ExtensionCommonConstants;
import org.wso2.carbon.integration.common.extensions.utils.InputStreamHandler;
import org.wso2.carbon.integration.common.extensions.utils.ServerLogReader;
import org.wso2.carbon.integration.common.utils.ClientConnectionUtil;
import org.wso2.carbon.utils.FileManipulator;
import org.wso2.carbon.utils.ServerConstants;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * A set of utility methods such as starting & stopping a Carbon server.
 */
public class CarbonServerManager {
    private static final Log log = LogFactory.getLog(CarbonServerManager.class);
    private Process process;
    private String carbonHome;
    private AutomationContext automationContext;
    private ServerLogReader inputStreamHandler;
    private boolean isCoverageEnable = false;
    private static final String SERVER_SHUTDOWN_MESSAGE = "Halting JVM";
    private static final String SERVER_STARTUP_MESSAGE = "Mgt Console URL";
    private static final long DEFAULT_START_STOP_WAIT_MS = 1000 * 60 * 5;
    private int defaultHttpsPort = 9443;
    private static final String CMD_ARG = "cmdArg";

    public CarbonServerManager(AutomationContext context) {
        this.automationContext = context;
    }

    public synchronized void startServerUsingCarbonHome(String carbonHome,
                                                        Map<String, String> commandMap)
            throws Exception {
        if (process != null) { // An instance of the server is running
            return;
        }
        Process tempProcess = null;
        isCoverageEnable = Boolean.parseBoolean(automationContext.getConfigurationValue("//coverage"));
        try {
            if (isCoverageEnable) {
                CodeCoverageUtils.init();
                CodeCoverageUtils.instrument(carbonHome);
            }
            defaultHttpsPort = Integer.parseInt(automationContext.getInstance().getPorts().get("https"));
            int defaultHttpPort = Integer.parseInt(automationContext.getInstance().getPorts().get("http"));
            //set carbon home only if port offset is default.
            if (!commandMap.isEmpty()) {
                if (getPortOffsetFromCommandMap(commandMap) == 0) {
                    System.setProperty(ServerConstants.CARBON_HOME, carbonHome);
                    System.setProperty("user.dir", carbonHome);
                }
            }
            File commandDir = new File(carbonHome);

            log.info("Starting server............. ");
            String scriptName = getStartupScriptFileName();
            final int portOffset = getPortOffsetFromCommandMap(commandMap);
            String[] parameters = expandServerStartupCommandList(commandMap);

            if (System.getProperty("os.name").toLowerCase().contains("windows")) {
                commandDir = new File(carbonHome + File.separator + "bin");
                String[] cmdArray;

                if (isCoverageEnable) {
                    cmdArray = new String[]{"cmd.exe", "/c", scriptName + ".bat",
                                            "-Demma.properties=" + System.getProperty("emma.properties"),
                                            "-Demma.rt.control.port=" + (47653 + portOffset)};
                    cmdArray = mergePropertiesToCommandArray(parameters, cmdArray);
                } else {
                    cmdArray = new String[]{"cmd.exe", "/c", scriptName + ".bat"};
                    cmdArray = mergePropertiesToCommandArray(parameters, cmdArray);
                }
                tempProcess = Runtime.getRuntime().exec(cmdArray, null, commandDir);

            } else {

                String[] cmdArray;
                if (isCoverageEnable) {
                    cmdArray = new String[]{"sh", "bin/" + scriptName + ".sh",
                                            "-Demma.properties=" + System.getProperty("emma.properties"),
                                            "-Demma.rt.control.port=" + (47653 + portOffset)};
                    cmdArray = mergePropertiesToCommandArray(parameters, cmdArray);
                } else {
                    cmdArray = new String[]{"sh", "bin/" + scriptName + ".sh"};
                    cmdArray = mergePropertiesToCommandArray(parameters, cmdArray);
                }
                tempProcess = Runtime.getRuntime().exec(cmdArray, null, commandDir);
            }

            InputStreamHandler errorStreamHandler =
                    new InputStreamHandler("errorStream", tempProcess.getErrorStream());
            inputStreamHandler = new ServerLogReader("inputStream", tempProcess.getInputStream());
            // start the stream readers
            inputStreamHandler.start();
            errorStreamHandler.start();
            Runtime.getRuntime().addShutdownHook(new Thread() {
                public void run() {
                    try {
                        serverShutdown(portOffset);
                    } catch (Exception e) {
                        log.error("Error while server shutdown ..", e);
                    }
                }
            });
            ClientConnectionUtil.waitForPort(defaultHttpPort + portOffset,
                                             DEFAULT_START_STOP_WAIT_MS, false,
                                             automationContext.getInstance().getHosts().get("default"));
            //wait until Mgt console url printed.
            long time = System.currentTimeMillis() + 60 * 1000;
            while (!inputStreamHandler.getOutput().contains(SERVER_STARTUP_MESSAGE) &&
                   System.currentTimeMillis() < time) {
                // wait until server startup is completed
            }
            ClientConnectionUtil.waitForLogin(automationContext);
            log.info("Server started successfully.");
        } catch (IOException e) {
            throw new RuntimeException("Unable to start server", e);
        } catch (LoginAuthenticationExceptionException e) {
            e.printStackTrace();
        }
        process = tempProcess;
    }

    private String[] mergePropertiesToCommandArray(String[] parameters, String[] cmdArray) {
        if (parameters != null) {
            cmdArray = mergerArrays(cmdArray, parameters);
        }
        return cmdArray;
    }

    public synchronized String setUpCarbonHome(String carbonServerZipFile)
            throws IOException {
        if (process != null) { // An instance of the server is running
            return carbonHome;
        }
        int indexOfZip = carbonServerZipFile.lastIndexOf(".zip");
        if (indexOfZip == -1) {
            throw new IllegalArgumentException(carbonServerZipFile + " is not a zip file");
        }
        String fileSeparator = (File.separator.equals("\\")) ? "\\" : "/";
        if (fileSeparator.equals("\\")) {
            carbonServerZipFile = carbonServerZipFile.replace("/", "\\");
        }
        String extractedCarbonDir =
                carbonServerZipFile.substring(carbonServerZipFile.lastIndexOf(fileSeparator) + 1,
                                              indexOfZip);
        FileManipulator.deleteDir(extractedCarbonDir);
        String extractDir = "carbontmp" + System.currentTimeMillis();
        String baseDir = (System.getProperty("basedir", ".")) + File.separator + "target";
        log.info("Extracting carbon zip file.. ");
        new ArchiveExtractor().extractFile(carbonServerZipFile, baseDir + File.separator + extractDir);
        return carbonHome =
                new File(baseDir).getAbsolutePath() + File.separator + extractDir + File.separator +
                extractedCarbonDir;
    }

    public synchronized void serverShutdown(int portOffset) throws Exception {
        if (process != null) {
            log.info("Shutting down server..");
            if (ClientConnectionUtil.isPortOpen(Integer.parseInt(ExtensionCommonConstants.
                                                                         SERVER_DEFAULT_HTTPS_PORT) + portOffset, automationContext.getInstance().getHosts().get("default"))) {
                int httpsPort = defaultHttpsPort + portOffset;
                String url = automationContext.getContextUrls().getBackEndUrl();
                String backendURL = url.replaceAll("(:\\d+)", ":" + httpsPort);
                ServerAdminClient serverAdminServiceClient = new ServerAdminClient(backendURL,
                                                                                   automationContext.getContextTenant().getTenantAdmin().getUserName(),
                                                                                   automationContext.getContextTenant().getTenantAdmin().getPassword());
                serverAdminServiceClient.shutdown();
                long time = System.currentTimeMillis() + DEFAULT_START_STOP_WAIT_MS;
                while (!inputStreamHandler.getOutput().contains(SERVER_SHUTDOWN_MESSAGE) &&
                       System.currentTimeMillis() < time) {
                    // wait until server shutdown is completed
                }
                log.info("Server stopped successfully...");
                inputStreamHandler.stop();
                process.destroy();
                process = null;
                if (isCoverageEnable) {
                    List<File> list = new ArrayList<File>();
                    list.add(new File(carbonHome));
                    CodeCoverageUtils.generateReports(list);
                }
                if (portOffset == 0) {
                    System.clearProperty(ServerConstants.CARBON_HOME);
                }
            }
        }
    }

    public synchronized void restartGracefully(ServerAdminClient serverAdminClient)
            throws Exception {
        serverAdminClient.restartGracefully();
        long time = System.currentTimeMillis() + DEFAULT_START_STOP_WAIT_MS;
        while (!inputStreamHandler.getOutput().contains(SERVER_SHUTDOWN_MESSAGE) &&
               System.currentTimeMillis() < time) {
            // wait until server shutdown is completed
        }
        Thread.sleep(5000);//wait for port to close
        ClientConnectionUtil.waitForPort(Integer.parseInt(automationContext.getInstance().getPorts().get("https")),
                                         automationContext.getInstance().getHosts().get("default"));
        ClientConnectionUtil.waitForLogin(automationContext);
    }

    private String[] expandServerStartupCommandList(Map<String, String> commandMap) {
        if (commandMap == null || commandMap.size() == 0) {
            return null;
        }
        String[] cmdParaArray = null;
        String cmdArg = null;
        if (commandMap.containsKey(CMD_ARG)) {
            cmdArg = commandMap.get(CMD_ARG);
            cmdParaArray = cmdArg.trim().split("\\s+");
            commandMap.remove(CMD_ARG);
        }
        String[] parameterArray = new String[commandMap.size()];
        int arrayIndex = 0;
        Set<Map.Entry<String, String>> entries = commandMap.entrySet();
        for (Map.Entry<String, String> entry : entries) {
            String parameter;
            String key = entry.getKey();
            String value = entry.getValue();
            if (value == null || value.isEmpty()) {
                parameter = key;
            } else {
                parameter = key + "=" + value;
            }
            parameterArray[arrayIndex++] = parameter;
        }
        //setting cmdArg again
        commandMap.put(CMD_ARG, cmdArg);
        if (cmdParaArray == null || cmdParaArray.length == 0) {
            return parameterArray;
        } else {
            return ArrayUtils.addAll(parameterArray, cmdParaArray);
        }
    }

    private int getPortOffsetFromCommandMap(Map<String, String> commandMap) {
        if (commandMap.containsKey(ExtensionCommonConstants.PORT_OFFSET_COMMAND)) {
            return Integer.parseInt(commandMap.get(
                    ExtensionCommonConstants.PORT_OFFSET_COMMAND));
        } else {
            return 0;
        }
    }

    private String[] mergerArrays(String[] array1, String[] array2) {
        return ArrayUtils.addAll(array1, array2);
    }

    /**
     * Filter start up script name from extracted distribution.
     */
    private String getStartupScriptFileName() throws FileNotFoundException {
        File[] allScripts = new File(carbonHome + File.separator + "bin").listFiles();
        String scriptName = null;
        if (allScripts != null) {
            for (File scriptFileName : allScripts) {
                if (scriptFileName.getName().contains(ExtensionCommonConstants.SEVER_STARTUP_SCRIPT_NAME)) {
                    scriptName = scriptFileName.getAbsoluteFile().getName();
                    break;
                } else if (scriptFileName.getName().contains("server")) {
                    scriptName = scriptFileName.getName();
                    break;
                }
            }
        } else {
            throw new FileNotFoundException("Server startup script not found at " +
                                            carbonHome + File.separator + "bin");
        }
        return FilenameUtils.removeExtension(scriptName);
    }
}
