/*
 * ========================================================================
 *
 * Codehaus CARGO, copyright 2004-2011 Vincent Massol, 2012-2021 Ali Tokmen.
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
import java.io.UnsupportedEncodingException;
import java.net.URL;
import java.net.URLConnection;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
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
    private static Set<File> files = Collections.synchronizedSet(new HashSet<File>());

    private static Set<URL> attachments = Collections.synchronizedSet(new HashSet<URL>());

    private static Map<String, String> blogpostIdentifiers =
        Collections.synchronizedMap(new HashMap<String, String>());

    private static Map<URL, Exception> exceptions =
        Collections.synchronizedMap(new HashMap<URL, Exception>());

    private static final boolean downloadAttachments =
        Boolean.parseBoolean(System.getProperty("cargo.downloadAttachments", "true"));

    private static final Pattern headerPattern = Pattern.compile("<h[1-4]");

    private static final String googleAds =
        "<script type=\"text/javascript\">\n" +
        "  // Google Ads code\n" +
        "  google_ad_client = \"ca-pub-7996505557003356\";\n" +
        "  google_ad_slot = \"5363897989\";\n" +
        "  google_ad_width = 728;\n" +
        "  google_ad_height = 90;\n" +
        "</script>" +
        "<center style=\"padding-bottom: 1mm; margin-bottom: 2mm; border: 1px solid #eee\">\n" +
        "  <script type=\"text/javascript\" src=\"https://pagead2.googlesyndication.com/pagead/show_ads.js\">\n" +
        "  </script>\n" +
        "</center>";

    private static final ScheduledThreadPoolExecutor executor = new ScheduledThreadPoolExecutor(8);

    public static void main(String[] args) throws Exception
    {
        if (Boolean.parseBoolean(System.getProperty("cargo.download", "true")))
        {
            download();
        }
        parse();
    }

    private static String toFilename(String title) throws UnsupportedEncodingException
    {
        StringBuilder sb = new StringBuilder();
        for (char character : title.toCharArray())
        {
            if ((character >= '0' && character <= '9') ||
                (character >= 'a' && character <= 'z') ||
                (character >= 'A' && character <= 'Z') ||
                 character == '.' || character == '-')
            {
                sb.append(character);
            }
            else
            {
                sb.append(' ');
            }
        }
        String result = sb.toString();
        while (result.contains("  "))
        {
            result = result.replace("  ", " ");
        }
        result = result.trim();
        return URLEncoder.encode(result, "UTF-8");
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

        URL url = new URL("https://codehaus-cargo.atlassian.net/wiki/rest/api/space/CARGO/content?limit=2048&expand=ancestors");
        URLConnection connection = url.openConnection();
        StringBuilder sb = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(connection.getInputStream())))
        {
            for (String line = reader.readLine(); line != null; line = reader.readLine())
            {
                sb.append(line);
            }
        }

        JSONObject response = new JSONObject(sb.toString());
        JSONArray pages = response.getJSONObject("page").getJSONArray("results");
        System.out.println("Found " + pages.length() + " pages to handle");
        for (int i = 0; i < pages.length(); i++)
        {
            JSONObject links = pages.getJSONObject(i).getJSONObject("_links");
            WebsiteGenerator runnable = new WebsiteGenerator();
            runnable.url = new URL(links.getString("self") + "?expand=body.view");
            Thread thread = new Thread(runnable);
            executor.submit(thread);
        }

        JSONArray blogposts = response.getJSONObject("blogpost").getJSONArray("results");
        System.out.println("Found " + blogposts.length() + " blog posts to handle");
        for (int i = 0; i < blogposts.length(); i++)
        {
            blogpostIdentifiers.put(
                blogposts.getJSONObject(i).getString("id"),
                toFilename(blogposts.getJSONObject(i).getString("title")));
            JSONObject links = blogposts.getJSONObject(i).getJSONObject("_links");
            WebsiteGenerator runnable = new WebsiteGenerator();
            runnable.url = new URL(links.getString("self") + "?expand=body.view");
            Thread thread = new Thread(runnable);
            executor.submit(thread);
        }
        blogpostIdentifiers.put("476119041", "Configuring+HTTP+2+for+Tomcat+8.5+and+above");

        while (executor.getCompletedTaskCount() < pages.length() + blogposts.length() + attachments.size())
        {
            Thread.sleep(5000);
            System.out.println("  - Completed " + executor.getCompletedTaskCount() + "/"
                + (pages.length() + blogposts.length() + attachments.size()) + " tasks - "
                +  ((System.currentTimeMillis() - start) / 1000) + " seconds spent so far");
        }
        if (executor.getCompletedTaskCount() < pages.length() + blogposts.length() + attachments.size())
        {
            throw new Exception("WARNING: Only completed " + executor.getCompletedTaskCount()
                + " tasks out of " + (pages.length() + blogposts.length() + attachments.size()));
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
        writeFile(new File(tempDirectory, "pages.json"), pages.toString(4));
        System.out.println("Export completed, total time taken " + ((System.currentTimeMillis() - start) / 1000) + " seconds");
    }

    private static void parse() throws Exception
    {
        System.out.println("Parsing files and generating Web site");
        File target = new File("target");
        File attachments = new File(target, "attachments");
        File classes = new File(target, "classes");
        Map<String, List<String>> breadcrumbs = new HashMap<String, List<String>>();
        JSONArray pages = new JSONArray(readFile(new File(target, "temp/pages.json")));
        for (int i = 0; i < pages.length(); i++)
        {
            JSONObject page = pages.getJSONObject(i);
            JSONArray ancestors = page.getJSONArray("ancestors");
            List<String> breadcrumb = new ArrayList<String>(ancestors.length());
            for (int j = 0; j < ancestors.length(); j++)
            {
                breadcrumb.add(ancestors.getJSONObject(j).getString("title"));
            }
            breadcrumbs.put(toFilename(page.getString("title")), breadcrumb);
        }
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
            value = value.replace("http://repo.maven", "https://repo.maven");
            value = value.replace("http://repo1.maven", "https://repo.maven");
            value = value.replaceAll(
                "<script type=\"syntaxhighlighter\"[^>]+><\\!\\[CDATA\\[", "<pre>");
            value = value.replace("]]></script>", "</pre>");
            value = value.replaceAll("<div id=\"refresh-module-\\d*\"", "<div");
            value = value.replaceAll("<div id=\"jira-issues-\\d*\"", "<div");
            value = value.replaceAll("<span id=\"total-issues-count-\\d*\"", "<span");
            value = value.replaceAll("(?s)<div id=\"refresh-\\d*\".*?</div>", "");
            value = value.replaceAll("(?s)<span id=\"error-message-\\d*\".*?</span>", "");
            value = value.replaceAll("(?s)<span class=\"refresh-action-group\".*?</span>", "");
            value = value.replaceAll("(?s)<textarea id=\"refresh-wiki-\\d*\".*?</textarea>", "");
            value = value.replaceAll("<input id=\"refresh-page-id-\\d*\"[^>]+>", "");
            Matcher headerMatcher = headerPattern.matcher(value);
            if (headerMatcher.find())
            {
                int hIndex = headerMatcher.start() + 3;
                headerMatcher = headerPattern.matcher(value.substring(hIndex));
                if (headerMatcher.find())
                {
                    hIndex = hIndex + headerMatcher.start() + 3;
                    headerMatcher = headerPattern.matcher(value.substring(hIndex));
                    if (headerMatcher.find())
                    {
                        hIndex = hIndex + headerMatcher.start();
                        value = value.substring(0, hIndex) + googleAds + value.substring(hIndex);
                    }
                }
            }
            if (value.indexOf(googleAds) == -1)
            {
                value = value + googleAds;
            }
            StringBuilder breadcrumbsSB = new StringBuilder();
            if (breadcrumbs.containsKey(name))
            {
                for (String breadcrumb : breadcrumbs.get(name))
                {
                    breadcrumbsSB.append("<a href=\"");
                    breadcrumbsSB.append(toFilename(breadcrumb));
                    breadcrumbsSB.append(".html\">");
                    breadcrumbsSB.append(breadcrumb);
                    breadcrumbsSB.append("</a> &gt; ");
                }
            }
            writeFile(file, Jsoup.parse(template.replace("$name", name).replace("$title",
                URLDecoder.decode(name, "UTF-8")).replace("$breadcrumbs", breadcrumbsSB.toString())
                .replace("$value", value).replaceAll(
                    "\\s*data-[^=\\s]+=\"[^\"]+\"", "").replaceAll(
                    "\\s*id=\"refresh-[^\"]+\"", "").replace(" class=\"external-link\"", "").replace(
                    " rel=\"nofollow\"", "").replace("<a href=\"http://java.sun.com\">java.sun.com</a>", "").replace(
                    "http://jira.codehaus.org/browse/CARGO-",
                    "https://codehaus-cargo.atlassian.net/browse/CARGO-").replace(
                    "https://jira.codehaus.org/browse/CARGO-",
                    "https://codehaus-cargo.atlassian.net/browse/CARGO-").replace(
                    "<div class=\"confluence-information-macro confluence-information-macro-note " +
                    "conf-macro output-block\"><span class=\"aui-icon aui-icon-small " +
                    "aui-iconfont-warning confluence-information-macro-icon\"> </span><div " +
                    "class=\"confluence-information-macro-body\"><p>This page / section has been " +
                    "automatically generated by Cargo's build. Do not edit it directly as it'll " +
                    "be overwritten next time it's generated again.</p></div></div>", "").replace(
                    "<div class=\"confluence-information-macro confluence-information-macro-note " +
                    "conf-macro output-block\"><span class=\"aui-icon aui-icon-small " +
                    "aui-iconfont-warning confluence-information-macro-icon\"> </span><div " +
                    "class=\"confluence-information-macro-body\"><p>This page has been " +
                    "automatically generated by Cargo's build. Do not edit it directly as it'll " +
                    "be overwritten next time it's generated again.</p></div></div>", "")).html());
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
                    filePath = filePath.substring(filePath.lastIndexOf('/'));
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

                Pattern pattern = Pattern.compile("<span class=\"logoBlock\"[^>]*>(.*?)<\\/span>", Pattern.DOTALL);
                Matcher matcher = pattern.matcher(value);
                value = matcher.replaceAll("");

                pattern = Pattern.compile("href=\"[^\"]*/wiki/[^\"]+/CARGO/[^\"]+\"|href='[^\']*/wiki/[^\']+/CARGO/[^']+'");
                matcher = pattern.matcher(value);
                int start = 0;
                StringBuilder sb = new StringBuilder();
                while (matcher.find())
                {
                    sb.append(value.substring(start, matcher.start()));
                    sb.append("href=\"");
                    String filename = value.substring(matcher.start() + 6, matcher.end() - 1);
                    filename = filename.substring(filename.lastIndexOf('/') + 1);
                    int hash = filename.indexOf('#');
                    String anchor = "";
                    if (hash != -1)
                    {
                        anchor = filename.substring(hash);
                        filename = filename.substring(0, hash);
                    }
                    if ("overview".equals(filename))
                    {
                        filename = "Home";
                    }
                    if (blogpostIdentifiers.containsKey(filename))
                    {
                        filename = blogpostIdentifiers.get(filename);
                    }
                    filename = filename.replace("%3A", "").replace("%2C", "");
                    sb.append(filename);
                    sb.append(".html");
                    sb.append(anchor);
                    sb.append("\"");
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
                        attachment = attachment.replace("&amp;", "&");
                        if ("http://www.codehaus.org/newtest.gif".equals(attachment))
                        {
                            attachment = "blank.gif";
                        }
                        else if (attachment.startsWith("/"))
                        {
                            attachment = "https://codehaus-cargo.atlassian.net" + attachment;
                        }
                        int questionMark = attachment.indexOf('?');
                        if (questionMark != -1)
                        {
                            attachment = attachment.substring(0, questionMark);
                        }
                        if (attachment.endsWith("default.png"))
                        {
                            attachment = "blank.gif";
                        }
                        if (!attachment.endsWith("blank.gif"))
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
                    }
                    sb.append(attachment);
                    sb.append("\"");
                    start = matcher.end();
                }
                sb.append(value.substring(start));
                value = sb.toString();

                File file = new File("target/source", toFilename(result.getString("title")));
                if (value.contains("https://codehaus-cargo.atlassian.net/wiki/pages/resumedraft.action"))
                {
                    throw new IllegalArgumentException("Page " + result.getString("title") + " contains a draft link");
                }
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
