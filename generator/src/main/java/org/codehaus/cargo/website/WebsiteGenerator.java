/*
 * ========================================================================
 *
 * Codehaus CARGO, copyright 2004-2011 Vincent Massol, 2012-2015 Ali Tokmen.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 * ========================================================================
 */
package org.codehaus.cargo.website;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.URL;
import java.net.URLConnection;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.json.JSONArray;
import org.json.JSONObject;
import org.jsoup.Jsoup;

public class WebsiteGenerator implements Runnable
{
    private static Set<File> files;

    private static Set<URL> attachments;

    private static Map<URL, Exception> exceptions;

    private static final boolean downloadAttachments =
        Boolean.parseBoolean(System.getProperty("cargo.downloadAttachments", "true"));

    private static final ScheduledThreadPoolExecutor executor = new ScheduledThreadPoolExecutor(8);

    public static void main(String[] args) throws Exception
    {
        if (Boolean.parseBoolean(System.getProperty("cargo.download", "true"))) {
            download();
        }
        parse();
    }

    private static void download() throws Exception
    {
        long start = System.currentTimeMillis();

        File attachmentsDirectory = new File("target", "attachments");
        if (!attachmentsDirectory.isDirectory())
        {
            attachmentsDirectory.mkdirs();
        }
        File sourceDirectory = new File("target", "source");
        if (!sourceDirectory.isDirectory())
        {
            sourceDirectory.mkdirs();
        }
        File tempDirectory = new File("target", "temp");
        if (!tempDirectory.isDirectory())
        {
            tempDirectory.mkdirs();
        }

        URL url = new URL("https://codehaus-cargo.atlassian.net/wiki/rest/api/space/CARGO/content/page?limit=2048");
        URLConnection connection = url.openConnection();
        StringBuilder sb = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(connection.getInputStream())))
        {
            for (String line = reader.readLine(); line != null; line = reader.readLine())
            {
                sb.append(line);
            }
        }
        JSONArray results = new JSONObject(sb.toString()).getJSONArray("results");
        System.out.println("Found " + results.length() + " objects to handle");

        files = Collections.synchronizedSet(new HashSet<File>(results.length()));
        exceptions = Collections.synchronizedMap(new HashMap<URL, Exception>());
        attachments = Collections.synchronizedSet(new HashSet<URL>(results.length()));
        for (int i = 0; i < results.length(); i++)
        {
            JSONObject links = results.getJSONObject(i).getJSONObject("_links");
            WebsiteGenerator runnable = new WebsiteGenerator();
            runnable.url = new URL(links.getString("self") + "?expand=body.view");
            Thread thread = new Thread(runnable);
            executor.submit(thread);
        }

        while (executor.getCompletedTaskCount() < results.length() + attachments.size())
        {
            Thread.sleep(5000);
            System.out.println("  - Completed " + executor.getCompletedTaskCount() + "/"
                + (results.length() + attachments.size()) + " tasks - "
                +  ((System.currentTimeMillis() - start) / 1000) + " seconds spent so far");
        }
        if (executor.getCompletedTaskCount() < results.length() + attachments.size())
        {
            throw new Exception("WARNING: Only completed " + executor.getCompletedTaskCount() + " tasks out of " + (results.length() + attachments.size()));
        }
        System.out.println("All tasks complete");
        for (File file : files)
        {
            System.out.println("  - Wrote file " + file.getAbsolutePath());
        }
        if (exceptions.size() > 0)
        {
            for (HashMap.Entry<URL, Exception> exception : exceptions.entrySet())
            {
                System.out.println("  - Pending exception for URL " + exception.getKey() + ": " + exception);
            }
            throw new Exception("Some files have failed download");
        }
        System.out.println("Export completed, total time taken " + ((System.currentTimeMillis() - start) / 1000) + " seconds");
    }

    private static void parse() throws Exception
    {
        System.out.println("Parsing files and generating Web site");
        File target = new File("target");
        File attachments = new File(target, "attachments");
        File classes = new File(target, "classes");
        Files.copy(new File(classes, "blank.gif").toPath(),
            new File(attachments, "blank.gif").toPath(), StandardCopyOption.REPLACE_EXISTING);
        Files.copy(new File(classes, "favicon.ico").toPath(),
            new File(attachments, "favicon.ico").toPath(), StandardCopyOption.REPLACE_EXISTING);
        Files.copy(new File(classes, "rss.gif").toPath(),
            new File(attachments, "rss.gif").toPath(), StandardCopyOption.REPLACE_EXISTING);
        writeFile(new File(attachments, "site.css"), readFile(new File(classes, "site.css")));
        File sourceDirectory = new File(target, "source");
        Files.copy(new File(classes, "search.html").toPath(),
            new File(sourceDirectory, "Search").toPath(), StandardCopyOption.REPLACE_EXISTING);
        String template = readFile(new File(target, "classes/cargo-template.html"));
        String navigation = readFile(new File(sourceDirectory, "Navigation"));
        template = template.replace("$navigation", navigation);
        for (File sourceFile : sourceDirectory.listFiles())
        {
            String name = sourceFile.getName();
            File file = new File(target, name + ".html");
            String value = readFile(sourceFile);
            value = value.replaceAll(
                "<script type=\"syntaxhighlighter\"[^>]+><\\!\\[CDATA\\[", "<pre>");
            value = value.replace("]]></script>", "</pre>");
            writeFile(file, Jsoup.parse(template.replace("$name", name).replace("$title",
                URLDecoder.decode(name, "UTF-8")).replace("$value", value).replace(
                    "http://jira.codehaus.org/browse/CARGO-",
                    "https://codehaus-cargo.atlassian.net/browse/CARGO-").replace(
                    "https://jira.codehaus.org/browse/CARGO-",
                    "https://codehaus-cargo.atlassian.net/browse/CARGO-")).html());
            System.out.println("  - Wrote file " + file.getAbsolutePath());
        }
        System.out.println("Parsing complete");
    }

    private static String readFile(File f) throws IOException
    {
        byte[] bytes = Files.readAllBytes(f.toPath());
        return new String(bytes, Charset.forName("UTF-8"));
    }

    private static void writeFile(File f, String value) throws IOException
    {
        try (PrintWriter writer = new PrintWriter(f, "UTF-8"))
        {
            writer.write(value.replace("\r\n", "\n").replace("\n\r", "\n").replace("\r", "\n").replace("\n", "\r\n"));
        }
    }

    private URL url;

    @Override
    public void run()
    {
        try
        {
            String value = "";
            for (int i = 0; i < 10; i++)
            {
                URLConnection connection = url.openConnection();
                try (InputStream is = connection.getInputStream())
                {
                    String filePath = url.getPath();
                    int questionMark = filePath.lastIndexOf('?');
                    if (questionMark != -1)
                    {
                        filePath = filePath.substring(filePath.lastIndexOf('/'), questionMark);
                    }
                    else
                    {
                        filePath = filePath.substring(filePath.lastIndexOf('/'));
                    }
                    filePath = URLDecoder.decode(filePath, "UTF-8");
                    File file = new File("target");
                    if (url.getPath().contains("/wiki/rest/api/content/"))
                    {
                        file = new File(file, "temp");
                    }
                    else
                    {
                        file = new File(file, "attachments");
                    }
                    file = new File(file, filePath);
                    try (FileOutputStream fos = new FileOutputStream(file))
                    {
                        byte[] buffer = new byte[8 * 1024];
                        int bytesRead;
                        while ((bytesRead = is.read(buffer)) != -1)
                        {
                            fos.write(buffer, 0, bytesRead);
                        }
                    }
                    if (url.getPath().contains("/wiki/rest/api/content/"))
                    {
                        value = readFile(file);
                    }
                }
                catch (IOException e)
                {
                    if (i == 9)
                    {
                        throw e;
                    }
                    Thread.sleep(1000);
                }
            }

            if (!value.isEmpty())
            {
                JSONObject result = new JSONObject(value);
                value = result.getJSONObject("body").getJSONObject("view").getString("value");

                Pattern pattern = Pattern.compile("href=\"/wiki/display/CARGO/[^\"]+\"|href='/wiki/display/CARGO/[^']+'");
                Matcher matcher = pattern.matcher(value);
                int start = 0;
                StringBuilder sb = new StringBuilder();
                while (matcher.find())
                {
                    sb.append(value.substring(start, matcher.start()));
                    sb.append("href=\"");
                    sb.append(value.substring(matcher.start() + 26, matcher.end() - 1));
                    sb.append(".html\"");
                    start = matcher.end();
                }
                sb.append(value.substring(start));
                value = sb.toString();

                pattern = Pattern.compile("href=\"/wiki/download/attachments/[^\"]+\"|href='/wiki/download/attachments/[^']+'");
                matcher = pattern.matcher(value);
                start = 0;
                sb = new StringBuilder();
                while (matcher.find())
                {
                    sb.append(value.substring(start, matcher.start()));
                    sb.append("href=\"attachments");
                    String attachment = value.substring(matcher.start() + 6, matcher.end() - 1);
                    if (attachment.startsWith("/"))
                    {
                        attachment = "https://codehaus-cargo.atlassian.net" + attachment;
                    }
                    if (downloadAttachments)
                    {
                        URL attachmentUrl = new URL(attachment);
                        synchronized (attachments)
                        {
                            if (!attachments.contains(attachmentUrl))
                            {
                                attachments.add(attachmentUrl);
                                WebsiteGenerator runnable = new WebsiteGenerator();
                                runnable.url = attachmentUrl;
                                Thread thread = new Thread(runnable);
                                executor.submit(thread);
                            }
                        }
                    }
                    int questionMark = attachment.lastIndexOf('?');
                    if (questionMark != -1)
                    {
                        sb.append(attachment.substring(attachment.lastIndexOf('/'), questionMark));
                    }
                    else
                    {
                        sb.append(attachment.substring(attachment.lastIndexOf('/')));
                    }
                    sb.append("\"");
                    start = matcher.end();
                }
                sb.append(value.substring(start));
                value = sb.toString();

                pattern = Pattern.compile("href=\"\\s*/wiki/[^\"]+\"|href='\\s*/wiki/[^']+'");
                matcher = pattern.matcher(value);
                start = 0;
                sb = new StringBuilder();
                while (matcher.find())
                {
                    sb.append(value.substring(start, matcher.start()));
                    sb.append("href=\"https://codehaus-cargo.atlassian.net");
                    sb.append(value.substring(matcher.start() + 6, matcher.end() - 1).trim());
                    sb.append("\"");
                    start = matcher.end();
                }
                sb.append(value.substring(start));
                value = sb.toString();

                pattern = Pattern.compile("src=\"[^\"]+\"|src='[^']+'");
                matcher = pattern.matcher(value);
                start = 0;
                sb = new StringBuilder();
                while (matcher.find())
                {
                    sb.append(value.substring(start, matcher.start()));
                    sb.append("src=\"");
                    String attachment = value.substring(matcher.start() + 5, matcher.end() - 1);
                    if (!attachment.startsWith("https://semaphoreci.com/"))
                    {
                        sb.append("attachments/");
                        if ("http://www.codehaus.org/newtest.gif".equals(attachment))
                        {
                            attachment = "blank.gif";
                        }
                        else if (attachment.startsWith("/"))
                        {
                            attachment = "https://codehaus-cargo.atlassian.net" + attachment;
                        }
                        if (!"blank.gif".equals(attachment))
                        {
                            URL attachmentUrl = new URL(attachment);
                            synchronized (attachments)
                            {
                                if (!attachments.contains(attachmentUrl))
                                {
                                    attachments.add(attachmentUrl);
                                    WebsiteGenerator runnable = new WebsiteGenerator();
                                    runnable.url = attachmentUrl;
                                    Thread thread = new Thread(runnable);
                                    executor.submit(thread);
                                }
                            }
                        }
                        attachment = attachment.substring(attachment.lastIndexOf('/') + 1);
                        int questionMark = attachment.lastIndexOf('?');
                        if (questionMark != -1)
                        {
                            attachment = attachment.substring(0, questionMark);
                        }
                        if ("default.png".equals(attachment))
                        {
                            attachment = "blank.gif";
                        }
                    }
                    sb.append(attachment);
                    sb.append("\"");
                    start = matcher.end();
                }
                sb.append(value.substring(start));
                value = sb.toString();

                String title = result.getString("title");
                File file = new File("target/source", URLEncoder.encode(title, "UTF-8"));
                writeFile(file, value);
                files.add(file);
            }
        }
        catch (Exception e)
        {
            exceptions.put(url, e);
        }
    }
}
