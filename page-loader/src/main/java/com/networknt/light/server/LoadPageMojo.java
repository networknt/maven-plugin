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

package com.networknt.light.server;

import java.io.*;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Scanner;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
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
 * Goal load page source code from the project source to Light Server through REST API impPage. It checks if the
 * source code has been changed.
 *
 * @goal load
 *
 * @phase process-sources
 *  
 */
public class LoadPageMojo extends AbstractMojo {
    ObjectMapper mapper = new ObjectMapper();
    CloseableHttpClient httpclient = null;
    Map<String, String> pageMap = null;
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

        // get page id and content map from the server in order to compare.
        pageMap = getPageMap();

        fillListWithAllFilesRecursiveTask(sourceDirectory, files);
        //fillListWithAllFilesRecursiveTask(testSourceDirectory, files);

        for (final String filePath : files) {
            // load server file and import to Light Server
            parsePageFile(filePath);
        }

        // write a sql out put file in case you are using the server engine with SQL database.
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

    public void parsePageFile(final String filePath) {
        System.out.println("Process file = " + filePath);
        StringBuilder content = new StringBuilder();
        try {
            File file = new File(filePath);
            String id = getFileName(file);
            if (".html".equals(getExtension(file))) {
                final Scanner scan = new Scanner(file, encoding);
                String line = scan.nextLine();
                while (scan.hasNext()) {
                    content.append(line);
                    content.append("\n");
                    line = scan.nextLine();
                }
                content.append(line);
                content.append("\n");

                // only import if content has been changed after comparing with server
                if(!content.toString().equals(pageMap.get(id))) {
                    impPage(id, content.toString());
                    // generate SQL insert statements

                    String sql = "INSERT INTO PAGE(id, content) VALUES ('" + id + "', '" + content.toString().replaceAll("'", "''") + "');\n";
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
     * Get all pages from the server and construct a map in order to compare content
     * to detect changes or not.
     *
     * @return Map<String, String>
     */
    private Map<String, String> getPageMap() {
        Map<String, String> map = null;

        Map<String, Object> inputMap = new HashMap<String, Object>();
        inputMap.put("category", "page");
        inputMap.put("name", "getPageMap");
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
            System.out.println("Got page map from server");
            map = mapper.readValue(json,
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
        return map;
    }

    private void impPage(String id, String content) {

        Map<String, Object> inputMap = new HashMap<String, Object>();
        inputMap.put("category", "page");
        inputMap.put("name", "impPage");
        inputMap.put("readOnly", false);
        Map<String, Object> data = new HashMap<String, Object>();
        data.put("id", id);
        data.put("content", content);
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
            System.out.println("Loaded " + id);
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
            path.append("server.sql");

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


    public static String getFileName(File file) {
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