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

package com.gwtplatform.crawlerservice.server.guice;

import com.gargoylesoftware.htmlunit.BrowserVersion;
import com.gargoylesoftware.htmlunit.WebClient;
import com.google.inject.Provides;
import com.google.inject.servlet.ServletModule;
import com.googlecode.objectify.ObjectifyFilter;
import com.gwtplatform.crawlerservice.server.ClearPage;
import com.gwtplatform.crawlerservice.server.CrawlServiceServlet;
import com.gwtplatform.crawlerservice.server.domain.CachedPage;

import javax.inject.Singleton;

import static com.googlecode.objectify.ObjectifyService.factory;

/**
 * @author Philippe Beaudoin
 */
public class CrawlServiceModule extends ServletModule {

    @Override
    public void configureServlets() {
        factory().register(CachedPage.class);

        bind(ObjectifyFilter.class).in(com.google.inject.Singleton.class);
        filter("/*").through(ObjectifyFilter.class);
        serve("*").with(CrawlServiceServlet.class);
        serve("/cron/delpages").with(ClearPage.class);
        //exclude the local admin modu
        //below should work but actully not, so use Salomon BRYS method to solave this:
        //http://stackoverflow.com/questions/2857279/google-guice-on-google-appengine-mapping-with-working-ah
//        serveRegex("/(?!_ah).*").with(CrawlServiceServlet.class);
    }

    @Singleton
    @Provides
    WebClient getWebClient() {
        return new WebClient(BrowserVersion.FIREFOX_17);
    }
}
