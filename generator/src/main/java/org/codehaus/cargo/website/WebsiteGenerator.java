/*
 * ========================================================================
 *
 * Codehaus Cargo, copyright 2004-2011 Vincent Massol, 2012-2025 Ali Tokmen.
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
import java.util.concurrent.ThreadLocalRandom;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.json.JSONArray;
import org.json.JSONObject;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;

/**
 * Java application which generates the Codehaus Cargo Web site based on the Confluence wiki.
 */
public class WebsiteGenerator implements Runnable
{
    /**
     * Wiki pages (original extracts)
     */
    private static Set<File> pages = Collections.synchronizedSet(new HashSet<File>());

    /**
     * Attachments of pages, including images.
     */
    private static Set<URL> attachments = Collections.synchronizedSet(new HashSet<URL>());

    /**
     * Blog post identifiers.
     */
    private static Map<String, String> blogpostIdentifiers =
        Collections.synchronizedMap(new HashMap<String, String>());

    /**
     * Any exceptions that happened during the asynchronous downloads.
     */
    private static Map<URL, Throwable> exceptions =
        Collections.synchronizedMap(new HashMap<URL, Throwable>());

    /**
     * Downloaded amount in bytes (regularly reset), to calculate speed.
     */
    private static long speed = 0;

    /**
     * Downloaded amount in bytes.
     */
    private static long size = 0;

    /**
     * Whether the download attachments.
     */
    private static final boolean DOWNLOAD_ATTACHMENTS =
        Boolean.parseBoolean(System.getProperty("cargo.downloadAttachments", "true"));

    /**
     * Multi-thread executor for parallel downloads.
     */
    private static final ScheduledThreadPoolExecutor CONTENT_DOWNLOADERS =
        new ScheduledThreadPoolExecutor(4);

    /**
     * Number of retries to Atlassian Confluence APIs.
     */
    private static final int NUMBER_RETRIES = 20;

    /**
     * Download the content and parse (i.e., generate the "full" HTML content)
     * @param args Not used.
     * @throws Exception If anything goes wrong.
     */
    public static void main(String[] args) throws Exception
    {
        if (Boolean.parseBoolean(System.getProperty("cargo.download", "true")))
        {
            download();
        }
        parse();
    }

    /**
     * Generate the file name from a page title.
     * @param title Page title.
     * @return Filename, i.e. the page title with various characters filtered out.
     * @throws UnsupportedEncodingException Should not be thrown.
     */
    private static String toFilename(String title) throws UnsupportedEncodingException
    {
        StringBuilder sb = new StringBuilder();
        for (char character : title.toCharArray())
        {
            if (character >= '0' && character <= '9'
                || character >= 'a' && character <= 'z'
                || character >= 'A' && character <= 'Z'
                || character == '.' || character == '-')
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

    /**
     * Trigger the asynchronous download of content from the Wiki.
     * @throws Exception If anything goes wrong.
     */
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

        URL url =
            new URL("https://codehaus-cargo.atlassian.net/wiki/rest/api/space/CARGO/content?limit=2048&expand=ancestors");
        URLConnection connection = url.openConnection();
        StringBuilder sb = new StringBuilder();
        try (BufferedReader reader =
            new BufferedReader(new InputStreamReader(connection.getInputStream())))
        {
            for (String line = reader.readLine(); line != null; line = reader.readLine())
            {
                sb.append(line);
            }
        }

        JSONObject response = new JSONObject(sb.toString());
        JSONArray pages = response.getJSONObject("page").getJSONArray("results");
        System.out.println("Found " + pages.length() + " pages to handle");
        boolean wildfly37x = false;
        boolean wildfly38x = false;
        for (int i = 0; i < pages.length(); i++)
        {
            JSONObject links = pages.getJSONObject(i).getJSONObject("_links");
            WebsiteGenerator runnable = new WebsiteGenerator();
            runnable.url = new URL(links.getString("self") + "?expand=body.view");
            Thread thread = new Thread(runnable);
            CONTENT_DOWNLOADERS.submit(thread);
            if ("WildFly 37.x".equals(pages.getJSONObject(i).getString("title")))
            {
                wildfly37x = true;
            }
            else if ("WildFly 38.x".equals(pages.getJSONObject(i).getString("title")))
            {
                wildfly38x = true;
            }
        }
        if (!wildfly37x)
        {
            // FIXME: Temporary hack as the REST API v1 doesn't return all pages.
            //        Moving to v2 is the only option proposed by Atlassian.
            WebsiteGenerator runnable = new WebsiteGenerator();
            runnable.url = new URL(
                "https://codehaus-cargo.atlassian.net/wiki/rest/api/content/3213066241?expand=body.view");
            Thread thread = new Thread(runnable);
            CONTENT_DOWNLOADERS.submit(thread);
        }
        if (!wildfly38x)
        {
            // FIXME: Temporary hack as the REST API v1 doesn't return all pages.
            //        Moving to v2 is the only option proposed by Atlassian.
            WebsiteGenerator runnable = new WebsiteGenerator();
            runnable.url = new URL(
                "https://codehaus-cargo.atlassian.net/wiki/rest/api/content/3326476289?expand=body.view");
            Thread thread = new Thread(runnable);
            CONTENT_DOWNLOADERS.submit(thread);
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
            CONTENT_DOWNLOADERS.submit(thread);
        }
        blogpostIdentifiers.put("476119041", "Configuring+HTTP+2+for+Tomcat+8.5+and+above");

        if (DOWNLOAD_ATTACHMENTS)
        {
            String[] banners = new String[]
            {
                "cargo-banner-left.png",
                "cargo-banner-center.png",
                "cargo-banner-right.png"
            };
            for (String banner : banners)
            {
                URL attachmentUrl =
                    new URL("https://codehaus-cargo.atlassian.net/wiki/download/attachments/491540/" + banner);
                synchronized (attachments)
                {
                    if (!attachments.contains(attachmentUrl))
                    {
                        attachments.add(attachmentUrl);
                        WebsiteGenerator runnable = new WebsiteGenerator();
                        runnable.url = attachmentUrl;
                        Thread thread = new Thread(runnable);
                        CONTENT_DOWNLOADERS.submit(thread);
                    }
                }
            }
        }

        while (CONTENT_DOWNLOADERS.getCompletedTaskCount()
            < pages.length() + blogposts.length() + attachments.size())
        {
            Thread.sleep(5000);
            System.out.println("  - Completed " + CONTENT_DOWNLOADERS.getCompletedTaskCount() + "/"
                + (pages.length() + blogposts.length() + attachments.size()) + " tasks - "
                +  ((System.currentTimeMillis() - start) / 1000) + " seconds spent so far, approximate "
                + "download speed since last message has been " + (WebsiteGenerator.speed / 1024 / 5) + " KB/s");
            WebsiteGenerator.speed = 0;
        }
        if (CONTENT_DOWNLOADERS.getCompletedTaskCount()
            < pages.length() + blogposts.length() + attachments.size())
        {
            throw new Exception("WARNING: Only completed " + CONTENT_DOWNLOADERS.getCompletedTaskCount()
                + " tasks out of " + (pages.length() + blogposts.length() + attachments.size()));
        }
        System.out.println(
            "All tasks complete, total downloaded: " + (WebsiteGenerator.size / 1024 / 1024) + " MB");
        for (File page : WebsiteGenerator.pages)
        {
            System.out.println("  - Wrote file " + page.getAbsolutePath());
        }
        if (exceptions.size() > 0)
        {
            for (HashMap.Entry<URL, Throwable> exception : exceptions.entrySet())
            {
                System.out.println("  - Pending exception for URL " + exception.getKey() + ": " + exception);
            }
            throw new Exception("Some files have failed download");
        }
        writeFile(new File(tempDirectory, "pages.json"), pages.toString(4));
        System.out.println(
            "Export completed, total time taken " + ((System.currentTimeMillis() - start) / 1000) + " seconds");
    }

    /**
     * Parse the content and generate the Web site.
     * @throws Exception If anything goes wrong.
     */
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
            Document document = Jsoup.parse(template
                .replace("$name", name)
                .replace("$title", URLDecoder.decode(name, "UTF-8"))
                .replace("$breadcrumbs", breadcrumbsSB.toString())
                .replace("$value", value).replaceAll("\\s*data-[^=\\s]+=\"[^\"]+\"", "")
                .replaceAll("\\s*id=\"refresh-[^\"]+\"", "").replace(" data-macro-id=\"\"", "")
                .replace(" class=\"external-link\"", "").replace(" rel=\"nofollow\"", "")
                .replace(" class=\"conf-macro output-inline\"", "")
                .replace("<a href=\"http://java.sun.com\">java.sun.com</a>", "java.sun.com")
                .replace("<a href=\"http://java.io\">java.io</a>", "java.io")
                .replace("http://jira.codehaus.org/browse/CARGO-",
                    "https://codehaus-cargo.atlassian.net/browse/CARGO-")
                .replace("https://jira.codehaus.org/browse/CARGO-",
                    "https://codehaus-cargo.atlassian.net/browse/CARGO-")
                .replace(
                    "src=\"https://codehaus-cargo.semaphoreci.com/badges/",
                    "id=\"ci-status-image\" src=\"https://codehaus-cargo.semaphoreci.com/badges/")
                .replace("<div class=\"confluence-information-macro confluence-information-macro-note "
                    + "conf-macro output-block\"><span class=\"aui-icon aui-icon-small "
                    + "aui-iconfont-warning confluence-information-macro-icon\"> </span><div "
                    + "class=\"confluence-information-macro-body\"><p>This page / section has been "
                    + "automatically generated by Cargo's build. Do not edit it directly as it'll "
                    + "be overwritten next time it's generated again.</p></div></div>", "")
                .replace("<div class=\"confluence-information-macro confluence-information-macro-note "
                    + "conf-macro output-block\"><span class=\"aui-icon aui-icon-small "
                    + "aui-iconfont-warning confluence-information-macro-icon\"> </span><div "
                    + "class=\"confluence-information-macro-body\"><p>This page has been "
                    + "automatically generated by Cargo's build. Do not edit it directly as it'll "
                    + "be overwritten next time it's generated again.</p></div></div>", ""));

            // Allow certain characters (dots, equal signs, etc.) act as whitespace in
            // <code> elements inside tables, so page widths remain "reasonable"
            for (Element table : document.getElementsByClass("confluenceTable"))
            {
                for (Element code : table.getElementsByTag("code"))
                {
                    String codeHtml = code.html();
                    if (!codeHtml.contains("<"))
                    {
                        code.html(codeHtml
                            .replace(".", ".<wbr>")
                            .replace(".<wbr>*", ".")
                            .replace(".<wbr>.<wbr>.<wbr>", "...")
                            .replace("(", "<wbr>(")
                            .replace("=", "=<wbr>")
                            .replace("&gt;", "&gt;<wbr>")
                            .replace("&lt;/", "<wbr>&lt;/"));
                    }
                }
            }

            writeFile(file, document.html()
                .replace("<p>&nbsp; <a", "<p><a")
                .replace("&nbsp;<code>", " <code>")
                .replace("<code><wbr>", "<code>")
                .replace("<wbr></code>", "</code>")
                .replace("\u201C", "\"")
                .replace("\u201D", "\"")
                .replace("\u2019", "'"));
            System.out.println("  - Wrote file " + file.getAbsolutePath());
        }
        System.out.println("Parsing complete");
    }

    /**
     * Helper function to read a file.
     * @param f File name.
     * @return File contents.
     * @throws IOException If anything goes wrong reading the file.
     */
    private static String readFile(File f) throws IOException
    {
        byte[] bytes = Files.readAllBytes(f.toPath());
        return new String(bytes, Charset.forName("UTF-8"));
    }

    /**
     * Helper function to read a file.
     * @param f File name.
     * @param value File contents.
     * @throws IOException If anything goes wrong reading the file.
     */
    private static void writeFile(File f, String value) throws IOException
    {
        try (PrintWriter writer = new PrintWriter(f, "UTF-8"))
        {
            writer.write(value.replace("\r\n", "\n").replace("\n\r", "\n").replace("\r", "\n").replace("\n", "\r\n"));
        }
    }

    /**
     * URL being downloaded by the asynchronous downloader.
     */
    private URL url;

    /**
     * Perform the download action. If anything goes wrong, the associated exception is added to the
     * {@link WebsiteGenerator#exceptions} map.
     */
    @Override
    public void run()
    {
        try
        {
            String value = "";
            for (int i = 0; i < WebsiteGenerator.NUMBER_RETRIES; i++)
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
                            WebsiteGenerator.speed += bytesRead;
                            WebsiteGenerator.size += bytesRead;
                        }
                    }
                    if (url.getPath().contains("/wiki/rest/api/content/"))
                    {
                        value = readFile(file);
                    }
                }
                catch (IOException e)
                {
                    if (i == WebsiteGenerator.NUMBER_RETRIES - 1)
                    {
                        throw new IllegalStateException("Failed after " + i + " retries", e);
                    }
                    Thread.sleep(ThreadLocalRandom.current().nextInt(5000, 15000));
                }
            }

            if (!value.isEmpty())
            {
                JSONObject result = new JSONObject(value);
                value = result.getJSONObject("body").getJSONObject("view").getString("value");

                Pattern pattern = Pattern.compile("<span class=\"logoBlock\"[^>]*>(.*?)<\\/span>", Pattern.DOTALL);
                Matcher matcher = pattern.matcher(value);
                value = matcher.replaceAll("");

                // Atlassian replaced most emojis with UTF-8 on December 2022, but forgot some
                value = value.replace(":cross_mark:", "\u274C");
                value = value.replace(":check_mark:", "\u2705");
                value = value.replace(":green_star:", "\u2B50");
                value = value.replaceAll("<img [^>]+alt=\"\\(thumbs up\\)\"[^>]+>", "\uD83D\uDC4D");
                value = value.replaceAll("<img [^>]+alt=\"\\(thumbs down\\)\"[^>]+>", "\uD83D\uDC4E");

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
                    filename = filename.replace("%21", "").replace("%2C", "").replace("%3A", "");
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
                    if (DOWNLOAD_ATTACHMENTS)
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
                                CONTENT_DOWNLOADERS.submit(thread);
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
                    if (!attachment.startsWith("https://codehaus-cargo.semaphoreci.com"))
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
                                    CONTENT_DOWNLOADERS.submit(thread);
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

                File page = new File("target/source", toFilename(result.getString("title")));
                if (value.contains("https://codehaus-cargo.atlassian.net/wiki/pages/resumedraft.action"))
                {
                    throw new IllegalArgumentException("Page " + result.getString("title") + " contains a draft link");
                }
                writeFile(page, value);
                pages.add(page);
            }
        }
        catch (Throwable t)
        {
            exceptions.put(url, t);
        }
    }
}
