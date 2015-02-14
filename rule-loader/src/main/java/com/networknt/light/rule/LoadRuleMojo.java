/*
 * Copyright 2015 Network New Technologies Inc.
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

package com.networknt.light.rule;

import java.io.*;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Scanner;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.orientechnologies.orient.core.db.document.ODatabaseDocumentPool;
import com.orientechnologies.orient.core.db.document.ODatabaseDocumentTx;
import com.orientechnologies.orient.core.metadata.schema.OClass;
import com.orientechnologies.orient.core.metadata.schema.OSchema;
import com.orientechnologies.orient.core.record.impl.ODocument;
import com.orientechnologies.orient.core.metadata.schema.OType;
import com.orientechnologies.orient.core.sql.query.OSQLSynchQuery;
import org.apache.http.HttpEntity;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.entity.StringEntity;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;
import org.apache.http.util.EntityUtils;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;

/**
 * Goal load java source code from the project source to Light Server through REST API impRule. It checks if the
 * class implements com.networknt.light.rule.Rule interface and if source code has been changed.
 *
 * @goal load
 *
 * @phase process-sources
 *  
 */
public class LoadRuleMojo extends AbstractMojo {
    ObjectMapper mapper = new ObjectMapper();
    CloseableHttpClient httpclient = null;
    Map<String, String> ruleMap = null;
    String jwt = null;

    private final List<String> files = new ArrayList<String>(10000);
    private String sqlString = "";

    /**
     * Location of the file.
     *
     * @parameter expression="${project.build.directory}"
     * @required
     */
    private final File outputDirectory = new File("");

    /**
     * Project's source directory as specified in the POM.
     *
     * @parameter expression="${project.build.sourceDirectory}"
     * @readonly
     * @required
     *  
     */
    private final File sourceDirectory = new File("");

    /**
     * Project's source directory for test code as specified in the POM.
     *
     * @parameter expression="${project.build.testSourceDirectory}"
     * @readonly
     * @required
     *    
     */
    private final File testSourceDirectory = new File("");
    /**
     * Encoding of source files
     *
     * @parameter default-value="UTF-8"
     * @required
     *
     */
    private String encoding;

    /**
     * server url
     *
     * @parameter default-value="http://example:8080"
     * @required
     */
    private String serverUrl;


    /**
     * server user
     *
     * @parameter default-value="stevehu"
     * @required
     */
    private String serverUser;

    /**
     * server pass
     *
     * @parameter default-value="123456"
     * @required
     */
    private String serverPass;

    /**
     * client id
     *
     * @parameter default-value="example@Browser"
     * @required
     */
    private String clientId;

    @Override
    public void execute() throws MojoExecutionException {
        if (!ensureTargetDirectoryExists()) {
            getLog().error("Could not create target directory");
            return;
        }
        if (!sourceDirectory.exists()) {
            getLog().error("Source directory \"" + sourceDirectory + "\" is not valid.");
            return;
        }

        httpclient = HttpClients.createDefault();

        login();

        // get ruleClass and sourceCode map from the server in order to compare.
        ruleMap = getRuleMap();

        fillListWithAllFilesRecursiveTask(sourceDirectory, files);
        //fillListWithAllFilesRecursiveTask(testSourceDirectory, files);

        for (final String filePath : files) {
            // load rule file and import to Light Server
            parseRuleFile(filePath);
        }

        // write a sql out put file in case you are using the rule engine with SQL database.
        writeSqlToOutputFile();

        if (httpclient != null) {
            try {
                httpclient.close();
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
    }

    private boolean ensureTargetDirectoryExists() {
        if (outputDirectory.exists()) {
            return true;
        }
        return outputDirectory.mkdirs();
    }

    public void parseRuleFile(final String filePath) {
        System.out.println("Process file = " + filePath);
        String packageName = null;
        StringBuilder sourceCode = new StringBuilder();
        boolean validRule = false;
        try {
            File file = new File(filePath);
            String className = getClassName(file);
            if (".java".equals(getExtension(file))) {
                final Scanner scan = new Scanner(file, encoding);
                String line = scan.nextLine();
                while (scan.hasNext()) {
                    if (!line.trim().isEmpty()) {
                        if (line.startsWith("package")) {
                            packageName = line.substring(8, line.length() -1);
                        }
                        if (!validRule && line.indexOf("implements") != -1 && line.indexOf("Rule") != -1) {
                            validRule = true;
                        }
                    }
                    sourceCode.append(line);
                    sourceCode.append("\n");
                    line = scan.nextLine();
                }
                sourceCode.append(line);
                sourceCode.append("\n");
            }
            if (validRule) {
                // connect to example:8080 to upload rule here.
                String ruleClass = packageName + "." + className;

                // only import the rule if source has been changed after comparing with server
                if(!sourceCode.toString().equals(ruleMap.get(ruleClass))) {
                    impRule(ruleClass, sourceCode.toString());
                    // generate SQL insert statements

                    String sql = "INSERT INTO RULE(class_name, source_code) VALUES ('" + ruleClass + "', '" + sourceCode.toString().replaceAll("'", "''") + "');\n";
                    sqlString += sql;
                }
            }
        } catch (final IOException e) {
            getLog().error(e.getMessage());
        }

    }
    private void login() {
        // login to the server
        Map<String, Object> inputMap = new HashMap<String, Object>();
        inputMap.put("category", "user");
        inputMap.put("name", "signInUser");
        inputMap.put("readOnly", false);
        Map<String, Object> data = new HashMap<String, Object>();
        data.put("userIdEmail", serverUser);
        data.put("password", serverPass);
        data.put("rememberMe", true);
        data.put("clientId", clientId);
        inputMap.put("data", data);

        CloseableHttpResponse response = null;
        try {
            HttpPost httpPost = new HttpPost(serverUrl + "/api/rs");
            StringEntity input = new StringEntity(mapper.writeValueAsString(inputMap));
            input.setContentType("application/json");
            httpPost.setEntity(input);
            response = httpclient.execute(httpPost);
            HttpEntity entity = response.getEntity();
            BufferedReader rd = new BufferedReader(new InputStreamReader(entity.getContent()));
            String json = "";
            String line = "";
            while ((line = rd.readLine()) != null) {
                json = json + line;
            }
            Map<String, Object> jsonMap = mapper.readValue(json,
                    new TypeReference<HashMap<String, Object>>() {
                    });
            jwt = (String)jsonMap.get("accessToken");
            EntityUtils.consume(entity);
            System.out.println("Logged in successfully");
        } catch (Exception e) {
            e.printStackTrace();
        } finally {
            if (response != null) {
                try {
                    response.close();
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }
        }
    }

    /**
     * Get all rules from the server and construct a map in order to compare source code
     * to detect changes or not.
     *
     * @return Map<String, String>
     */
    private Map<String, String> getRuleMap() {
        Map<String, String> ruleMap = null;

        Map<String, Object> inputMap = new HashMap<String, Object>();
        inputMap.put("category", "rule");
        inputMap.put("name", "getRuleMap");
        inputMap.put("readOnly", true);

        CloseableHttpResponse response = null;
        try {
            HttpPost httpPost = new HttpPost(serverUrl + "/api/rs");
            httpPost.addHeader("Authorization", "Bearer " + jwt);
            StringEntity input = new StringEntity(mapper.writeValueAsString(inputMap));
            input.setContentType("application/json");
            httpPost.setEntity(input);
            response = httpclient.execute(httpPost);
            HttpEntity entity = response.getEntity();
            BufferedReader rd = new BufferedReader(new InputStreamReader(entity.getContent()));
            String json = "";
            String line = "";
            while ((line = rd.readLine()) != null) {
                json = json + line;
            }
            EntityUtils.consume(entity);
            System.out.println("Got rule map from server");
            ruleMap = mapper.readValue(json,
                    new TypeReference<HashMap<String, String>>() {
                    });
        } catch (Exception e) {
            e.printStackTrace();
        } finally {
            if (response != null) {
                try {
                    response.close();
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }
        }
        return ruleMap;
    }

    private void impRule(String ruleClass, String sourceCode) {

        Map<String, Object> inputMap = new HashMap<String, Object>();
        inputMap.put("category", "rule");
        inputMap.put("name", "impRule");
        inputMap.put("readOnly", false);
        Map<String, Object> data = new HashMap<String, Object>();
        data.put("ruleClass", ruleClass);
        data.put("sourceCode", sourceCode);
        inputMap.put("data", data);

        CloseableHttpResponse response = null;
        try {
            HttpPost httpPost = new HttpPost(serverUrl + "/api/rs");
            httpPost.addHeader("Authorization", "Bearer " + jwt);
            StringEntity input = new StringEntity(mapper.writeValueAsString(inputMap));
            input.setContentType("application/json");
            httpPost.setEntity(input);
            response = httpclient.execute(httpPost);
            HttpEntity entity = response.getEntity();
            BufferedReader rd = new BufferedReader(new InputStreamReader(entity.getContent()));
            String json = "";
            String line = "";
            while ((line = rd.readLine()) != null) {
                json = json + line;
            }
            EntityUtils.consume(entity);
            System.out.println("Loaded " + ruleClass);
        } catch (Exception e) {
            e.printStackTrace();
        } finally {
            if (response != null) {
                try {
                    response.close();
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }
        }
    }

    private void writeSqlToOutputFile() {
        OutputStreamWriter out = null;
        try {
            final StringBuffer path = new StringBuffer();
            path.append(outputDirectory);
            path.append(System.getProperty("file.separator"));
            path.append("rule.sql");

            final FileOutputStream fos = new FileOutputStream(path.toString());
            out = new OutputStreamWriter(fos, encoding);
            out.write(sqlString);

        } catch (final IOException e) {
            getLog().error(e.getMessage());
        } finally {
            if (null != out) {
                try {
                    out.close();
                } catch (final IOException e) {
                    getLog().error(e.getMessage());
                }
            }
        }
    }

    /*
    */
    public static void fillListWithAllFilesRecursiveTask(final File root, final List<String> files) {
        if (root.isFile()) {
            files.add(root.getPath());
            return;
        }
        for (final File file : root.listFiles()) {
            if (file.isDirectory()) {
                fillListWithAllFilesRecursiveTask(file, files);
            } else {
                files.add(file.getPath());
            }
        }
    }


    public static String getClassName(File file) {
        String filename = file.getName();
        if (filename.indexOf(".") > 0) {
            filename = filename.substring(0, filename.lastIndexOf("."));
        }
        return filename;
    }


    public static String getExtension(File file) {
        String filename = file.getName();
        final int dotPos = filename.lastIndexOf(".");
        if (-1 == dotPos) {
            return "undefined";
        } else {
            return filename.substring(dotPos);
        }
    }
}