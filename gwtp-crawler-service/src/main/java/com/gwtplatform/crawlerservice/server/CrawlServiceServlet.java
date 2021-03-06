/**
 * Copyright 2011 ArcBees Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */

package com.gwtplatform.crawlerservice.server;

import com.gargoylesoftware.htmlunit.NicelyResynchronizingAjaxController;
import com.gargoylesoftware.htmlunit.SilentCssErrorHandler;
import com.gargoylesoftware.htmlunit.WebClient;
import com.gargoylesoftware.htmlunit.WebRequest;
import com.gargoylesoftware.htmlunit.html.HtmlPage;
import com.google.common.base.Strings;
import com.google.inject.Inject;
import com.gwtplatform.crawlerservice.server.domain.CachedPage;

import javax.inject.Provider;
import javax.inject.Singleton;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.io.PrintWriter;
import java.net.URLDecoder;
import java.util.Date;
import java.util.logging.Level;
import java.util.logging.Logger;

import static com.googlecode.objectify.ObjectifyService.ofy;

/**
 * Servlet that makes it possible to fetch an external page, renders it using HTMLUnit and returns the HTML page.
 */
@Singleton
public class CrawlServiceServlet extends HttpServlet {

    private class SyncAllAjaxController extends NicelyResynchronizingAjaxController {
        private static final long serialVersionUID = 1L;

        @Override
        public boolean processSynchron(HtmlPage page, WebRequest request, boolean async) {
            return true;
        }
    }

    private static final String CHAR_ENCODING = "UTF-8";

    private static final long serialVersionUID = -6129110224710383122L;

    @Inject(optional = true)
    @HtmlUnitTimeoutMillis
    private long timeoutMillis = 12000;
    private long jsTimeoutMillis = 1000;
    private long pageWaitMillis = 200;
    private int maxLoopChecks = 2;

    @Inject(optional = true)
    @CachedPageTimeoutSec
    public static long cachedPageTimeoutSec = 15 * 60;

    private final Logger log;
    private final Provider<WebClient> webClientProvider;

    private final String key;


    @Inject
    CrawlServiceServlet(Provider<WebClient> webClientProvider, Logger log, @ServiceKey String key) {
        this.webClientProvider = webClientProvider;
        this.log = log;
        this.key = key;
    }

    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp) {
        PrintWriter out = null;
        try {
            boolean keyValid = validateKey(req, resp);

            if (keyValid) {
                out = resp.getWriter();

                String url = Strings.nullToEmpty(req.getParameter("url"));
                //url is decoded after calling getParameter(), no need to decode again
                //                url = URLDecoder.decode(url, CHAR_ENCODING);

                if (!url.isEmpty()) {
                    renderResponse(url, resp);
                }
            }
        } catch (IOException e) {
            e.printStackTrace();

            resp.setStatus(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
        } finally {
            if (out != null) {
                out.close();
            }
        }
    }

    private boolean validateKey(HttpServletRequest request, HttpServletResponse response) throws IOException {
        PrintWriter output = response.getWriter();
        String receivedKey = request.getParameter("key");
        boolean keyIsValid = false;

        if (Strings.isNullOrEmpty(receivedKey)) {
            output.println("No service key attached to the request.");
        } else {
            String decodedKey = URLDecoder.decode(receivedKey, CHAR_ENCODING);

            if (!key.equals(decodedKey)) {
                output.println("The service key received does not match the desired key.");
            } else {
                keyIsValid = true;
            }
        }

        if (!keyIsValid) {
            response.setStatus(HttpServletResponse.SC_FORBIDDEN);
        }

        return keyIsValid;
    }

    private void renderResponse(String url, HttpServletResponse response) throws IOException {
        PrintWriter out = response.getWriter();

        response.setCharacterEncoding(CHAR_ENCODING);
        response.setHeader("Content-Type", "text/plain; charset=" + CHAR_ENCODING);

        log.info("find existing page from datastore which url equals: " + url);
        CachedPage fetchedPage = ofy().load().type(CachedPage.class).id(url).now();
        log.info("fetched page: " + fetchedPage);

        Date currDate = new Date();

        if (!needToFetchPage2(fetchedPage, currDate)) {
            if (fetchedPage.isFetchInProgress()) {
                log.info("no need to fetch page: fetch in progress.");
                out.println("FETCH_IN_PROGRESS");
            } else {
                log.info("no need to fetch page: use page in datastore.");
                out.println(fetchedPage.getContent());
            }
        } else {
            //fetch now!
            log.info("about to fetch page!");
            if (fetchedPage != null) {
                log.info("delete expired page in datastore");
                ofy().delete().entity(fetchedPage).now();
            }

            log.info("save a new page to datastore and mark fetching is true");
            CachedPage newPage = createPlaceholderPage(url, currDate);
            //must store it in datastore to indicating next impatient Google Bot request that a fetch is in progress
            ofy().save().entity(newPage).now();
            String renderedHtml = fetchPage(url);
            log.info("save the new page with page content to datastore");
            saveFetchedPage(newPage, renderedHtml);
            out.println(renderedHtml);
        }
    }

    private boolean isFetchingExpired(CachedPage fetchedPage, Date currDate) {
        //gae urlfetch deadline is 60 seconds
        // If fetch is in progress since more than 60 seconds, we consider something went wrong and fetch again.
        return currDate.getTime() > fetchedPage.getFetchDate().getTime() + 60000;
    }

    private boolean isDbPageExpired(CachedPage fetchedPage, Date currDate) {
        //gae urlfetch deadline is 60 seconds
        // If fetch is in progress since more than 60 seconds, we consider something went wrong and fetch again.
        return currDate.getTime() > fetchedPage.getFetchDate().getTime() + cachedPageTimeoutSec * 1000;
    }

    private void saveFetchedPage(CachedPage cachedPage, String stringBuilder) {
        cachedPage.setContent(stringBuilder);
        cachedPage.setFetchInProgress(false);
        ofy().save().entity(cachedPage).now();
    }

    private boolean needToFetchPage2(CachedPage fetchedPage, Date currDate) {
        if (fetchedPage == null) {
            return true;
        }

        //the exising page is expired
        if (isDbPageExpired(fetchedPage,currDate)) {
            return true;
        }

        if (fetchedPage.isFetchInProgress()) {
            //gae urlfetch deadline is 60 seconds
            // If fetch is in progress since more than 60 seconds, we consider something went wrong and fetch again.
            if (isFetchingExpired(fetchedPage, currDate)) {
                return true;
            } else {
                return false;
            }
        } else {
            return false;
        }
    }

    private CachedPage createPlaceholderPage(String url, Date currDate) {
        CachedPage result = new CachedPage();
        result.setUrl(url);
        result.setFetchDate(currDate);
        result.setFetchInProgress(true);
        return result;
    }

    /**
     * Fetches the page at {@code url} and renders the page in a {@link StringBuilder}. The rendered page is prefixed
     * with a message indicating this is a non-interactive version.
     *
     * @param url The URL of the page to render.
     * @return The rendered page, in a {@link StringBuilder}.
     */
    private String fetchPage(String url) throws IOException {
        WebClient webClient = webClientProvider.get();

        webClient.getCache().clear();
        webClient.getOptions().setCssEnabled(false);
        webClient.getOptions().setJavaScriptEnabled(true);
        webClient.getOptions().setThrowExceptionOnScriptError(false);
        webClient.getOptions().setRedirectEnabled(false);
        webClient.setAjaxController(new SyncAllAjaxController());
        webClient.setCssErrorHandler(new SilentCssErrorHandler());

        HtmlPage page = webClient.getPage(url);
        webClient.getJavaScriptEngine().pumpEventLoop(timeoutMillis);

        int waitForBackgroundJavaScript = webClient.waitForBackgroundJavaScript(jsTimeoutMillis);
        int loopCount = 0;

        while (waitForBackgroundJavaScript > 0 && loopCount < maxLoopChecks) {
            ++loopCount;
            waitForBackgroundJavaScript = webClient.waitForBackgroundJavaScript(jsTimeoutMillis);

            if (waitForBackgroundJavaScript == 0) {
                log.fine("HtmlUnit exits background javascript at loop counter " + loopCount);
                break;
            }

            synchronized (page) {
                log.fine("HtmlUnit waits for background javascript at loop counter " + loopCount);
                try {
                    page.wait(pageWaitMillis);
                } catch (InterruptedException e) {
                    log.log(Level.SEVERE, "HtmlUnit ERROR on page.wait at loop counter " + loopCount, e);
                }
            }
        }

        webClient.closeAllWindows();

        return page.asXml();
    }
}
